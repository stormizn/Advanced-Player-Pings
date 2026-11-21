package me.stormizn.playerpings.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Owns the SQLite database (a HikariCP pool in front of a single file) and runs
 * every query on a dedicated background thread.
 * <p>
 * Failures are reported by completing the returned future exceptionally; callers
 * are expected to react to them instead of treating a broken database as "no data".
 * SQLite is configured in WAL mode with a single pooled connection, since it only
 * ever allows one writer at a time.
 */
public final class PingDatabase implements AutoCloseable {

    /** How long SQLite waits for a competing writer before failing with SQLITE_BUSY. */
    private static final String SQLITE_BUSY_TIMEOUT_MILLIS = "5000";

    private static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS player_pings ("
            + "uuid TEXT PRIMARY KEY, "
            + "name TEXT NOT NULL, "
            + "last_ping INTEGER NOT NULL, "
            + "updated_at INTEGER NOT NULL)";

    private static final String SAVE_SQL = "INSERT INTO player_pings (uuid, name, last_ping, updated_at) "
            + "VALUES (?, ?, ?, ?) "
            + "ON CONFLICT(uuid) DO UPDATE SET "
            + "name = excluded.name, "
            + "last_ping = excluded.last_ping, "
            + "updated_at = excluded.updated_at";

    private static final String LOOKUP_SQL = "SELECT name, last_ping, updated_at FROM player_pings WHERE uuid = ?";

    private final File dataFolder;
    private final Logger logger;
    private final String fileName;
    private final AtomicBoolean initialised = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile HikariDataSource dataSource;
    private volatile ExecutorService executor;

    /**
     * @param plugin   owning plugin (used for the data folder and the logger)
     * @param fileName database file name inside that data folder
     */
    public PingDatabase(JavaPlugin plugin, String fileName) {
        this(plugin.getDataFolder(), plugin.getLogger(), fileName);
    }

    /**
     * Test-friendly constructor: works on a plain directory and logger.
     *
     * @param dataFolder directory the database file lives in
     * @param logger     where errors should be reported
     * @param fileName   database file name inside that directory
     */
    PingDatabase(File dataFolder, Logger logger, String fileName) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.fileName = fileName;
    }

    /**
     * Creates the pool, switches the file to WAL mode and creates the table.
     * Calling this more than once is a no-op, so a re-enable cannot leak pools.
     *
     * @throws SQLException           if the table cannot be created
     * @throws IllegalStateException  if the data folder cannot be created, or the
     *                                database has already been closed
     */
    public void initialise() throws SQLException {
        if (closed.get()) {
            throw new IllegalStateException("The ping database has already been closed");
        }
        if (!initialised.compareAndSet(false, true)) {
            return;
        }

        prepareDataFolder();

        ExecutorService service = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PlayerPings-Database");
            thread.setDaemon(true);
            return thread;
        });
        this.executor = service;

        HikariDataSource pool = null;
        try {
            pool = createDataSource();
            try (Connection connection = pool.getConnection();
                 PreparedStatement statement = connection.prepareStatement(CREATE_TABLE_SQL)) {
                statement.execute();
            }
            this.dataSource = pool;
        } catch (SQLException | RuntimeException e) {
            if (pool != null) {
                pool.close();
            }
            service.shutdownNow();
            this.executor = null;
            initialised.set(false);
            throw e;
        }
    }

    private void prepareDataFolder() {
        if (!dataFolder.isDirectory() && !dataFolder.mkdirs() && !dataFolder.isDirectory()) {
            throw new IllegalStateException("Could not create the plugin data folder: " + dataFolder);
        }
        File databaseFile = new File(dataFolder, fileName);
        File parent = databaseFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Could not create the database folder: " + parent);
        }
    }

    private HikariDataSource createDataSource() {
        File databaseFile = new File(dataFolder, fileName);

        HikariConfig config = new HikariConfig();
        config.setPoolName("PlayerPings-SQLite");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        // SQLite serialises writers per file, so extra connections only add lock contention.
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000L);
        config.setMaxLifetime(0L);
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("busy_timeout", SQLITE_BUSY_TIMEOUT_MILLIS);
        return new HikariDataSource(config);
    }

    /**
     * Inserts or updates the row for a player.
     *
     * @param uuid      the player
     * @param name      their current name, kept for offline lookups
     * @param ping      measured ping in milliseconds
     * @param timestamp when it was measured, epoch millis
     * @return a future that completes once the write is committed
     */
    public CompletableFuture<Void> savePing(UUID uuid, String name, int ping, long timestamp) {
        HikariDataSource pool = this.dataSource;
        return submit(pool, "store the ping of " + name, () -> {
            try (Connection connection = pool.getConnection();
                 PreparedStatement statement = connection.prepareStatement(SAVE_SQL)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, name);
                statement.setInt(3, ping);
                statement.setLong(4, timestamp);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Looks up the stored ping of a player.
     *
     * @param uuid the player
     * @return a future resolving to the record, or empty if the player has no row
     */
    public CompletableFuture<Optional<PingRecord>> getPing(UUID uuid) {
        HikariDataSource pool = this.dataSource;
        return submit(pool, "read the ping of " + uuid, () -> {
            try (Connection connection = pool.getConnection();
                 PreparedStatement statement = connection.prepareStatement(LOOKUP_SQL)) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.<PingRecord>empty();
                    }
                    return Optional.of(new PingRecord(
                            resultSet.getString("name"),
                            resultSet.getInt("last_ping"),
                            resultSet.getLong("updated_at")));
                }
            }
        });
    }

    /**
     * Runs a query on the database thread, turning failures into an exceptional future
     * instead of silently returning empty data.
     */
    private <T> CompletableFuture<T> submit(HikariDataSource pool, String operation, Query<T> query) {
        ExecutorService service = this.executor;
        if (pool == null || service == null || closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("The ping database is not available, cannot " + operation));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return query.run();
                } catch (SQLException e) {
                    logger.warning("Failed to " + operation + ": " + e.getMessage());
                    throw new CompletionException(e);
                }
            }, service);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Lets queued writes finish, then closes the pool. Safe to call more than once.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        ExecutorService service = this.executor;
        this.executor = null;
        if (service != null) {
            service.shutdown();
            try {
                if (!service.awaitTermination(5L, TimeUnit.SECONDS)) {
                    service.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                service.shutdownNow();
            }
        }

        HikariDataSource pool = this.dataSource;
        this.dataSource = null;
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
    }

    /** @return whether {@link #close()} has run. */
    public boolean isClosed() {
        return closed.get();
    }

    /** One row of the {@code player_pings} table. */
    public record PingRecord(String name, int ping, long updatedAt) {
    }

    /** A database query that is allowed to fail with a {@link SQLException}. */
    @FunctionalInterface
    private interface Query<T> {
        T run() throws SQLException;
    }
}
