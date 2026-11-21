package me.stormizn.playerpings.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the storage layer against a real SQLite file in a temporary directory,
 * which is the closest we can get to the server environment without booting Paper.
 */
class PingDatabaseTest {

    private static final Logger LOGGER = Logger.getLogger("PingDatabaseTest");
    private static final long TIMEOUT_SECONDS = 10L;
    private static final String FILE_NAME = "pings.db";

    @TempDir
    Path tempDirectory;

    private PingDatabase database;

    private PingDatabase openDatabase() throws SQLException {
        PingDatabase opened = new PingDatabase(tempDirectory.toFile(), LOGGER, FILE_NAME);
        opened.initialise();
        this.database = opened;
        return opened;
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void savesAndReadsAPing() throws Exception {
        PingDatabase storage = openDatabase();
        UUID uuid = UUID.randomUUID();

        storage.savePing(uuid, "Notch", 42, 1_700_000_000_000L).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        PingDatabase.PingRecord record = storage.getPing(uuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .orElseThrow(() -> new AssertionError("expected a stored ping"));
        assertEquals("Notch", record.name());
        assertEquals(42, record.ping());
        assertEquals(1_700_000_000_000L, record.updatedAt());
    }

    @Test
    void savingAgainUpdatesTheSameRow() throws Exception {
        PingDatabase storage = openDatabase();
        UUID uuid = UUID.randomUUID();

        storage.savePing(uuid, "Notch", 20, 1L).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        storage.savePing(uuid, "NotchRenamed", 55, 2L).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        PingDatabase.PingRecord record = storage.getPing(uuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .orElseThrow(() -> new AssertionError("expected a stored ping"));
        assertEquals("NotchRenamed", record.name());
        assertEquals(55, record.ping());
        assertEquals(2L, record.updatedAt());
        assertEquals(1, countRows());
    }

    @Test
    void unknownPlayerHasNoRecord() throws Exception {
        PingDatabase storage = openDatabase();

        assertTrue(storage.getPing(UUID.randomUUID()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void databaseUsesWriteAheadLogging() throws Exception {
        openDatabase();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile().getAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode")) {
            assertTrue(resultSet.next(), "PRAGMA journal_mode returned no row");
            assertEquals("wal", resultSet.getString(1).toLowerCase(Locale.ROOT));
        }
    }

    @Test
    void initialiseIsIdempotent() throws Exception {
        PingDatabase storage = openDatabase();
        storage.initialise();
        storage.initialise();

        UUID uuid = UUID.randomUUID();
        storage.savePing(uuid, "Notch", 12, 3L).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(storage.getPing(uuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isPresent());
    }

    @Test
    void closeFlushesQueuedWrites() throws Exception {
        PingDatabase storage = openDatabase();
        UUID uuid = UUID.randomUUID();

        // Deliberately not waiting: close() has to drain the queue itself.
        storage.savePing(uuid, "Notch", 7, 4L);
        storage.close();
        this.database = null;

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile().getAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT name, last_ping FROM player_pings WHERE uuid = '" + uuid + "'")) {
            assertTrue(resultSet.next(), "the queued write was lost when the database closed");
            assertEquals("Notch", resultSet.getString("name"));
            assertEquals(7, resultSet.getInt("last_ping"));
        }
    }

    @Test
    void closedDatabaseRejectsNewWork() {
        PingDatabase storage;
        try {
            storage = openDatabase();
        } catch (SQLException e) {
            throw new AssertionError("could not open the database", e);
        }
        storage.close();
        this.database = null;

        CompletableFuture<Optional<PingDatabase.PingRecord>> future = storage.getPing(UUID.randomUUID());
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNotNull(failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("not available"));
    }

    @Test
    void uninitialisedDatabaseRejectsWork() {
        PingDatabase storage = new PingDatabase(tempDirectory.toFile(), LOGGER, FILE_NAME);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> storage.getPing(UUID.randomUUID()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNotNull(failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("not available"));
    }

    private File databaseFile() {
        return new File(tempDirectory.toFile(), FILE_NAME);
    }

    private int countRows() throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile().getAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM player_pings")) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }
}
