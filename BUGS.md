# Bug report and resolution log

Findings from the review of commit `3fdedb3` (`initial commit`), and what was changed to fix them.

**Verification of the current state**

- `gradlew.bat clean build` — **BUILD SUCCESSFUL** (Gradle 9.6.0, Java 25 toolchain).
- `PingDatabaseTest` — **8 tests, 0 failures** (real SQLite file in a temp directory).
- Shaded jar inspected: `advanced-player-pings-1.0.0.jar`, no `org/slf4j` classes, HikariCP relocated
  to `me/stormizn/playerpings/libs/hikari`, single artifact (the plain `jar` task is disabled).
- `api-version: '26.2'` re-verified against `apiVersioning.json` in the resolved
  `paper-api-26.2.build.92-stable.jar` (`{"currentApiVersion":"26.2"}`).

Status: **all findings below are fixed.**

---

## High

### B1. `/ping` had no permission check, and tab completion leaked the player list

`PingCommand`, `plugin.yml`

Anyone could query anyone, and tab completion listed every online player.

**Fixed:** `ping.use` (default `true`) is checked before any work, and `onTabComplete` returns an
empty list without it. `ping.reload` (default `op`) gates `/ping reload` and the `reload` suggestion.
Both nodes are declared in `plugin.yml` and documented in the README, along with how to make the
command staff-only.

### B2. Database errors were reported as "no record"

`PingDatabase.savePing` / `getPing`, `PingCommand`

`SQLException` was caught and logged, then the future completed normally — `savePing` with `null`,
`getPing` with `Optional.empty()`. A locked or broken database was therefore indistinguishable from
"never seen this player", and the command's `err != null` branches could never see a SQL failure.

**Fixed:** both methods now complete their future exceptionally (`CompletionException`), and
`PingCommand` renders `messages.error` and logs the cause on `err != null`. "No record" is now only
shown when the query actually succeeded and returned nothing.

### B3. Blocking `Bukkit.getOfflinePlayer(String)` on the main thread

`PingCommand`

The 26.2 javadoc says the method *"may involve a blocking web request"*; it was called directly in
the command handler. It also never returns `null`, so unknown names silently became a synthetic UUID
and always produced "no record".

**Fixed:** the lookup now uses `Bukkit.getOfflinePlayerIfCached(name)`, which never blocks and
returns `null` for unknown names; `null` produces the new `messages.unknown-player` message instead of
a misleading "no record".

---

## Medium

### B4. Unrelocated `org.slf4j` was bundled into the shaded jar

`build.gradle.kts`

HikariCP 5.1.0 and sqlite-jdbc both depend on `org.slf4j:slf4j-api` (1.7.36). The fat jar contained
38 `org/slf4j/**` entries with no provider, while Paper ships slf4j 2.x via `paper-api`.

**Fixed:** `slf4j-api` is excluded from both dependencies; the jar now contains **0** slf4j entries.
`paper-api` supplies it at runtime (it is a declared `paper-api` dependency), and `testRuntimeOnly`
provides it for the unit tests, where HikariCP reads it during class initialisation.

### B5. SQLite used a 4-connection pool with no busy timeout

`PingDatabase.createDataSource`

The comment claimed a small pool avoids "database is locked", but SQLite serialises writers per file;
extra connections only add `SQLITE_BUSY` contention.

**Fixed:** `maximumPoolSize(1)`, `minimumIdle(1)`, `connectionTimeout(10s)`, `maxLifetime(0)`, and
explicit `journal_mode=WAL`, `synchronous=NORMAL`, `busy_timeout=5000` driver properties. The WAL
setting is asserted by a test.

### B6. Blocking JDBC ran on `ForkJoinPool.commonPool()`

`PingDatabase`

`CompletableFuture.runAsync/supplyAsync` without an executor share the JVM-wide common pool with every
other plugin, and nothing was shut down on disable.

**Fixed:** a dedicated single-thread daemon executor (`PlayerPings-Database`) is created in
`initialise()` and drained and shut down in `close()`.

### B7. `runTask` from a completion handler could throw during shutdown

`PingCommand`

`IllegalPluginAccessException` if the plugin was disabled between the async read and the callback, and
the exception was swallowed by the `CompletableFuture`, so the sender never heard back.

**Fixed:** replies go through `runOnMainThread`, which (a) runs inline when already on the main
thread, (b) skips work when the plugin is disabled, and (c) catches `IllegalPluginAccessException`
and logs it at `FINE`.

### B8. Writes were fire-and-forget with no rate limiting

`PingCommand`, `config.yml`

The success message was sent before the write was confirmed, and repeated `/ping` calls queued
unbounded writes.

**Fixed:** the reply for an online target is only sent once the write has committed (and an error
message if it did not), and a configurable per-player cooldown (`cooldown-seconds`, default `1`,
`0` disables) is enforced by `PingCommand.registerUsage`. The cooldown map is pruned periodically, and
console senders are never limited.

### B9. The plugin disabled itself from `onEnable` and hid the cause

`PlayerPingsPlugin`

**Fixed:** the failure is logged with `Level.SEVERE` **and the full stack trace**, then the plugin
disables itself and closes any partially created database. The same treatment is applied to a missing
`ping` command.

### B10. Deprecated legacy colour handling

`PingCommand`, `config.yml`

`ChatColor` is deprecated on 26.2 (it produced two compiler warnings), there was no RGB support, and
colour translation ran **after** placeholder substitution, so `/ping &cfoo` let a sender inject colour
codes into their own output.

**Fixed:** messages are built with Adventure. The template is deserialized once with
`LegacyComponentSerializer.legacyAmpersand()`, then each placeholder is inserted with
`TextReplacementConfig` and `Component.text(...)` as literal text — input can no longer inject
formatting. Code compiles with `-Xlint:deprecation` and no warnings.

---

## Low

### B11. Non-volatile `dataSource`, non-idempotent `initialise()`, racy `close()`

**Fixed:** `dataSource` and `executor` are `volatile`; `initialise()` is guarded by an
`AtomicBoolean` and is a no-op on repeat calls (and resets on failure); `close()` is idempotent,
drains queued writes before closing the pool, and refuses new work. Tests cover idempotent init, the
flush-on-close behaviour, and rejection of work before init/after close.

### B12. `mkdirs()` failure only warned and execution continued

**Fixed:** `prepareDataFolder()` throws `IllegalStateException` with the offending path, so startup
aborts with the real cause instead of a confusing Hikari error.

### B13. Message defaults were duplicated in code and `config.yml`

**Fixed:** the defaults live once in `PluginConfig` (as documented constants), a missing/blank key is
reported with a warning naming the key, and `config.yml` ships the same values. `/ping reload`
re-reads the file so the defaults and the shipped YAML cannot silently drift on a running server.

### B14. Hard-coded usage string

**Fixed:** the usage message is built from `Command#getUsage()` (i.e. `plugin.yml`) and the label the
command was invoked with.

### B15. Inconsistent naming between plugin, project and artifact

**Fixed:** plugin `AdvancedPlayerPings`, root project `advanced-player-pings`, package
`me.stormizn.playerpings`, artifact `advanced-player-pings-1.0.0.jar`, version `1.0.0` instead of a
`-SNAPSHOT`. The README documents the resulting data-folder rename and how to migrate.

### B16. `shadowJar` wrote over the plain `jar` output

**Fixed:** `tasks.jar { enabled = false }` — the shaded jar is the only artifact, so there is no
task-ordering hazard. Verified: `build/libs` contains exactly `advanced-player-pings-1.0.0.jar`.

### B17. Dead code and a test dependency with no tests

**Fixed:** `PingRecord.uuid` is gone (nothing read it); `getPluginConfig()` is now actually used by
`/ping reload` and by `PingCommand`; `database.isClosed()` is used by the quit listener; and real
tests were added (`PingDatabaseTest`, 8 tests) so the JUnit dependencies are no longer dead weight.

### B18. "Last seen" was really "last time somebody pinged"

**Fixed:** a `PlayerQuitEvent` listener records each player's ping on disconnect (skipping
`<= 0ms`, and skipping when storage is already closed), and the default result message now says
"recorded" instead of "last seen". `/ping` on an online player still records the live value.

---

## Not bugs (checked, unchanged)

- `api-version: '26.2'` matches the resolved API build.
- `INSERT … ON CONFLICT(uuid) DO UPDATE` needs SQLite ≥ 3.24; the plugin bundles 3.49.1.0 (the same
  version Paper 26.2 ships) and it is covered by tests.
- The `plugin.yml` version placeholder expands correctly (`1.0.0`).
- `META-INF/services/java.sql.Driver` in the shaded jar is harmless: the driver is loaded explicitly
  through `driverClassName`, not through `DriverManager` discovery.
- HikariCP 5.1.0 is Java 11+ and works on the Java 25 toolchain.

## Remaining notes

- The jar still bundles sqlite-jdbc (~14 MB, mostly native libraries). Paper happens to ship
  sqlite-jdbc too, but it is documented as temporary backwards compatibility, so the plugin keeps its
  own copy and does not depend on the server's.
- Hex/RGB colours and MiniMessage are still unsupported; the message templates use the legacy `&`
  syntax on purpose.
- No `LICENSE` file exists in the repository. Distribution terms are up to the author.
