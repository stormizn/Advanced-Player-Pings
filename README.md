# Advanced Player Pings

A small Paper plugin that shows a player's current ping and remembers it in a local SQLite
database, so you can still look up the last known ping of somebody who has logged off.

- Plugin name: **AdvancedPlayerPings** → data folder `plugins/AdvancedPlayerPings/`
- Gradle project: `advanced-player-pings` → jar `advanced-player-pings-<version>.jar`

---

## Features

- `/ping <player>` — reports the live ping of an online player and records it.
- Falls back to the last stored ping when the target is offline.
- Records a player's ping when they disconnect, so "last seen" data stays current even if nobody
  runs `/ping` on them.
- Pings are persisted in `plugins/AdvancedPlayerPings/pings.db` (SQLite, via HikariCP).
- Tab completion suggests online players (and `reload` for admins), filtered by permission.
- Permission checks, a configurable per-player cooldown and Adventure-based messages.
- Per-player lookups are rate limited, and database errors are reported to the sender instead of
  being disguised as "no record".
- `/ping reload` re-reads `config.yml` without a restart.
- Database access runs on a dedicated background thread; writes are drained on shutdown.

## Requirements

| Requirement | Version |
| --- | --- |
| Server | Paper (or a Paper fork) **26.2+** — `api-version: '26.2'` |
| Java | **25+** — the build is pinned to a Java 25 toolchain, so the produced class files need a Java 25 runtime |
| Build tools | JDK 25 and the bundled Gradle wrapper (9.6.0) — only needed to build from source |

Compiled against `io.papermc.paper:paper-api:26.2.build.92-stable`; the plugin will not load on
servers older than the 26.2 API.

## Installation

1. Build the jar (see [Building from source](#building-from-source)) or drop in a released jar.
2. Copy `build/libs/advanced-player-pings-1.0.0.jar` into your server's `plugins/` folder.
3. Restart the server.
4. Optionally edit `plugins/AdvancedPlayerPings/config.yml` and run `/ping reload` (or restart).

Confirm the plugin loaded with `/plugins` — it will appear as `AdvancedPlayerPings`.

> **Upgrading from the old `Ping-Plugin` build:** the plugin was renamed, so the data folder moved
> from `plugins/Ping-Plugin/` to `plugins/AdvancedPlayerPings/`. Move (or copy) the old folder before
> starting the server to keep your stored pings.

## Commands

| Command | Arguments | Permission | Description |
| --- | --- | --- | --- |
| `/ping` | `<player>` | `ping.use` (default: everyone) | Shows the target's ping. Online targets are measured **and stored**; offline targets are read from the database. |
| `/ping` | `reload` | `ping.reload` (default: op) | Re-reads `config.yml`. `database.file-name` still needs a restart. |

If an online player is actually called `reload`, the player lookup wins over the subcommand.

Example session:

```
/ping Notch
Notch's ping: 42ms (recorded 2026-11-21 12:04)

/ping Notch            # once they have logged off
Notch's ping: 42ms (recorded 2026-11-21 12:04)

/ping SomeoneUnknown
No player called SomeoneUnknown has ever joined this server.
```

## Permissions

| Node | Default | Description |
| --- | --- | --- |
| `ping.use` | `true` (everyone) | Use `/ping <player>`. Also gates tab completion. |
| `ping.reload` | `op` | Use `/ping reload`, and see `reload` in tab completion. |

If you want the command to be staff-only, set `ping.use` to `false` for the `default` group in your
permissions plugin. The plugin deliberately does not put `permission:` on the command in
`plugin.yml`, so the denial message comes from `messages.no-permission` in `config.yml`.

## Configuration

`plugins/AdvancedPlayerPings/config.yml`:

```yaml
database:
  file-name: "pings.db"

cooldown-seconds: 1

messages:
  result: "&a%player%&7's ping: &b%ping%ms &7(recorded %time%)"
  no-record: "&eNo recorded ping for &6%player%&e yet!"
  unknown-player: "&eNo player called &6%player%&e has ever joined this server."
  error: "&cCouldn't read the ping database, please try again later."
  cooldown: "&ePlease wait &6%seconds%s&e before using /ping again."
  no-permission: "&cYou don't have permission to do that."
  reloaded: "&aPing configuration reloaded."
```

| Key | Default | Description |
| --- | --- | --- |
| `database.file-name` | `pings.db` | SQLite file name inside the plugin data folder. Must be a plain file name (no paths, no `..`). Needs a restart to change. |
| `cooldown-seconds` | `1` | Minimum delay between two lookups by the same player. `0` disables it; console senders are never limited. |
| `messages.result` | see above | Shown for live and stored pings. |
| `messages.no-record` | see above | Nothing stored for that player yet. |
| `messages.unknown-player` | see above | The name is not in the server's player cache. |
| `messages.error` | see above | The database could not be read or written. |
| `messages.cooldown` | see above | The sender is still on cooldown. |
| `messages.no-permission` | see above | The sender lacks a permission node. |
| `messages.reloaded` | see above | Confirmation after `/ping reload`. |

Placeholders: `%player%`, `%ping%`, `%time%` (`uuuu-MM-dd HH:mm`, server time zone, `n/a` when there
is no timestamp) and `%seconds%` (cooldown only).

Formatting: colours use the legacy `&` syntax (and `&r`, `&l`, …). The template is parsed once and
placeholders are then inserted as literal text, so a player name can never inject colour codes or
other formatting. Hex/RGB colours and MiniMessage tags are not supported.

Messages are read when the plugin enables and whenever `/ping reload` runs; a missing or blank key
is replaced with the built-in default and logged as a warning.

## How it works

```
/ping <name>
      │
      ├─ ping.use check ──► yes ──► "reload"? (admins) ──► reload config
      │                       └──► cooldown check
      │
      ├─ Bukkit.getPlayerExact(name) != null ──► player.getPing()
      │                                           └─ async UPSERT into player_pings
      │                                           └─ reply on the main thread once committed
      │
      └─ otherwise ──► Bukkit.getOfflinePlayerIfCached(name)   (never blocks)
                        ├─ null  → "unknown player"
                        └─ async SELECT by UUID
                             └─ back on the main thread
                                  ├─ record found → reply with stored ping + timestamp
                                  ├─ no record    → messages.no-record
                                  └─ error        → messages.error (and a console warning)
```

- Online pings are written with `INSERT ... ON CONFLICT(uuid) DO UPDATE`, so each player has exactly
  one row holding their latest value and name.
- `PlayerQuitEvent` also writes the player's last ping, so history does not depend on somebody
  running `/ping` on them. Quit pings below `1ms` (no measurement yet) are skipped.
- Replies are only sent after the database operation succeeded, so a broken storage layer is
  reported as an error rather than as "no record".
- Everything on the database side runs on a single dedicated daemon thread named
  `PlayerPings-Database`; `onDisable` lets queued writes finish before the pool is closed.

## Storage

- File: `plugins/AdvancedPlayerPings/pings.db` (SQLite), opened through a HikariCP pool.
- One pooled connection, WAL journal, `synchronous=NORMAL`, `busy_timeout=5000` — SQLite only allows
  a single writer, so extra connections would just add lock contention.
- Table:

```sql
CREATE TABLE IF NOT EXISTS player_pings (
    uuid       TEXT    PRIMARY KEY,   -- player UUID as a string
    name       TEXT    NOT NULL,      -- last known name
    last_ping  INTEGER NOT NULL,      -- milliseconds
    updated_at INTEGER NOT NULL       -- epoch millis
);
```

Deleting the file resets all history; it is recreated on the next start. `-wal` and `-shm` files may
appear next to it while the server is running.

## Building from source

```bash
# Windows
gradlew.bat build

# macOS / Linux
./gradlew build
```

Output: `build/libs/advanced-player-pings-1.0.0.jar` (Paper API excluded, HikariCP relocated,
sqlite-jdbc bundled).

Notes:

- `build` runs the tests and the ShadowJar task. There is no thin jar; the shaded jar is the only
  artifact.
- The build fails without network access unless the dependencies are already in the Gradle cache;
  `gradlew.bat --offline build` works when they are.
- The jar is ~14 MB, almost entirely sqlite-jdbc native libraries. Paper also ships its own
  `sqlite-jdbc` on the server classpath, but the plugin does not rely on it because Paper documents
  that library as temporary backwards compatibility.
- `org.slf4j` is intentionally **not** shaded: it is a `paper-api` dependency at runtime, and a
  plugin-local copy would shadow the server's version and silence pool logging.

### Tests

```bash
gradlew.bat test
```

`PingDatabaseTest` runs the storage layer against a real SQLite file in a temp directory: save/read,
upsert into a single row, unknown player, WAL mode, idempotent `initialise()`, queued writes flushed
by `close()`, and rejection of work before init/after close (8 tests).

## Project layout

```
Advanced-Player-Pings/
├── build.gradle.kts                     # Java 25 toolchain, Paper API, ShadowJar, Hikari relocation
├── settings.gradle.kts                  # rootProject "advanced-player-pings"
├── gradle/wrapper/                      # Gradle 9.6.0 wrapper
└── src/
    ├── main/java/me/stormizn/playerpings/
    │   ├── PlayerPingsPlugin.java       # entry point: config, DB lifecycle, wiring, /ping reload
    │   ├── command/PingCommand.java     # /ping executor + tab completer + Adventure messages
    │   ├── config/PluginConfig.java     # validated, typed access to config.yml
    │   ├── listener/PlayerPingListener.java  # records the ping on quit
    │   └── storage/PingDatabase.java    # HikariCP/SQLite pool, WAL, async save/lookup
    ├── main/resources/
    │   ├── plugin.yml                   # name, api-version 26.2, commands, permissions
    │   └── config.yml                   # database, cooldown and message templates
    └── test/java/me/stormizn/playerpings/storage/PingDatabaseTest.java
```

## Bug fixes

The review that led to the current state, with what was wrong and what changed, is recorded in
[`BUGS.md`](BUGS.md). Everything listed there is fixed; the build and 8 database tests pass.

## License

No license file is included in this repository, so all rights are reserved by the author
(`stormizn`). Add a `LICENSE` file before distributing the jar publicly.
