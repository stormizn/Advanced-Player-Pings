package me.stormizn.eldenstead.storage;

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

/**
 * Handles all SQLite access for the plugin. A HikariCP pool sits in front of a
 * single {@code .db} file in the data folder, and every query runs off the main
 * thread via {@link CompletableFuture}. Callers that touch the Bukkit API with a
 * result are responsible for hopping back onto the main thread themselves.
 */
public class PingDatabase {

    private final JavaPlugin plugin;
    private final String fileName;
    private HikariDataSource dataSource;

    /**
     * @param plugin   owning plugin (used for the data folder and logger)
     * @param fileName database file name inside that data folder
     */
    public PingDatabase(JavaPlugin plugin, String fileName) {
        this.plugin = plugin;
        this.fileName = fileName;
    }

    /**
     * Spins up the connection pool and creates the table if it isn't there yet.
     * Call this once, on enable.
     *
     * @throws SQLException if the table can't be created
     */
    public void initialise() throws SQLException {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create data folder.");
        }

        File dbFile = new File(plugin.getDataFolder(), fileName);

        HikariConfig config = new HikariConfig();
        config.setPoolName("PingPlugin-SQLite");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        // SQLite is single-writer; a small pool avoids "database is locked".
        config.setMaximumPoolSize(4);
        config.setConnectionTestQuery("SELECT 1");

        this.dataSource = new HikariDataSource(config);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS player_pings (" +
                             "uuid TEXT PRIMARY KEY, " +
                             "name TEXT NOT NULL, " +
                             "last_ping INTEGER NOT NULL, " +
                             "updated_at INTEGER NOT NULL)")) {
            stmt.execute();
        }
    }

    /**
     * Saves a player's latest ping, inserting a new row or updating the existing
     * one (keyed on UUID). Runs asynchronously.
     *
     * @param uuid      the player
     * @param name      their current name (kept so we can show offline lookups)
     * @param ping      measured ping in ms
     * @param timestamp when it was measured, epoch millis
     * @return a future that completes once the write is done
     */
    public CompletableFuture<Void> savePing(UUID uuid, String name, int ping, long timestamp) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO player_pings (uuid, name, last_ping, updated_at) " +
                    "VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET " +
                    "name = excluded.name, " +
                    "last_ping = excluded.last_ping, " +
                    "updated_at = excluded.updated_at";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, name);
                stmt.setInt(3, ping);
                stmt.setLong(4, timestamp);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to save ping for " + name + ": " + e.getMessage());
            }
        });
    }

    /**
     * Looks up a stored ping by UUID. Runs asynchronously.
     *
     * @param uuid the player
     * @return a future resolving to the record, or empty if we've never seen them
     */
    public CompletableFuture<Optional<PingRecord>> getPing(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT name, last_ping, updated_at FROM player_pings WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new PingRecord(
                                uuid,
                                rs.getString("name"),
                                rs.getInt("last_ping"),
                                rs.getLong("updated_at")));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to load ping: " + e.getMessage());
            }
            return Optional.empty();
        });
    }

    /** Shuts the pool down. Safe to call more than once. */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    /**
     * One row of the {@code player_pings} table.
     *
     * @param uuid      the player
     * @param name      last known name
     * @param ping      last recorded ping in ms
     * @param updatedAt when that ping was written, epoch millis
     */
    public record PingRecord(UUID uuid, String name, int ping, long updatedAt) {
    }
}
