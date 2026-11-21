package me.stormizn.playerpings.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Typed, validated view of {@code config.yml}.
 * <p>
 * Values are read once per instantiation, so a new instance has to be created to
 * pick up edits (the plugin does this through {@code /ping reload}). Every value
 * has a built-in default that matches the shipped {@code config.yml}, and a
 * missing or empty key is reported once at load time instead of being silently
 * replaced.
 */
public final class PluginConfig {

    public static final String DEFAULT_DATABASE_FILE = "pings.db";
    public static final long DEFAULT_COOLDOWN_SECONDS = 1L;

    public static final String DEFAULT_MESSAGE_RESULT = "&a%player%&7's ping: &b%ping%ms &7(recorded %time%)";
    public static final String DEFAULT_MESSAGE_NO_RECORD = "&eNo recorded ping for &6%player%&e yet!";
    public static final String DEFAULT_MESSAGE_UNKNOWN_PLAYER = "&eNo player called &6%player%&e has ever joined this server.";
    public static final String DEFAULT_MESSAGE_ERROR = "&cCouldn't read the ping database, please try again later.";
    public static final String DEFAULT_MESSAGE_COOLDOWN = "&ePlease wait &6%seconds%s&e before using /ping again.";
    public static final String DEFAULT_MESSAGE_NO_PERMISSION = "&cYou don't have permission to do that.";
    public static final String DEFAULT_MESSAGE_RELOADED = "&aPing configuration reloaded.";

    private final String databaseFileName;
    private final long cooldownMillis;
    private final String messageResult;
    private final String messageNoRecord;
    private final String messageUnknownPlayer;
    private final String messageError;
    private final String messageCooldown;
    private final String messageNoPermission;
    private final String messageReloaded;

    /**
     * Reads and validates every supported key from the plugin's current config.
     *
     * @param plugin plugin whose {@code config.yml} should be read
     */
    public PluginConfig(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();

        this.databaseFileName = readDatabaseFileName(plugin, config);
        this.cooldownMillis = readCooldownMillis(plugin, config);

        this.messageResult = readMessage(plugin, config, "messages.result", DEFAULT_MESSAGE_RESULT);
        this.messageNoRecord = readMessage(plugin, config, "messages.no-record", DEFAULT_MESSAGE_NO_RECORD);
        this.messageUnknownPlayer = readMessage(plugin, config, "messages.unknown-player", DEFAULT_MESSAGE_UNKNOWN_PLAYER);
        this.messageError = readMessage(plugin, config, "messages.error", DEFAULT_MESSAGE_ERROR);
        this.messageCooldown = readMessage(plugin, config, "messages.cooldown", DEFAULT_MESSAGE_COOLDOWN);
        this.messageNoPermission = readMessage(plugin, config, "messages.no-permission", DEFAULT_MESSAGE_NO_PERMISSION);
        this.messageReloaded = readMessage(plugin, config, "messages.reloaded", DEFAULT_MESSAGE_RELOADED);
    }

    private static String readDatabaseFileName(JavaPlugin plugin, FileConfiguration config) {
        String fileName = config.getString("database.file-name");
        if (fileName != null) {
            fileName = fileName.trim();
        }
        if (fileName == null || fileName.isEmpty() || fileName.contains("..")
                || fileName.startsWith("/") || fileName.startsWith("\\")
                || fileName.matches("^[A-Za-z]:.*")) {
            plugin.getLogger().warning("database.file-name is missing or not a plain file name, using '"
                    + DEFAULT_DATABASE_FILE + "'.");
            return DEFAULT_DATABASE_FILE;
        }
        return fileName;
    }

    private static long readCooldownMillis(JavaPlugin plugin, FileConfiguration config) {
        long seconds = config.getLong("cooldown-seconds", DEFAULT_COOLDOWN_SECONDS);
        if (seconds < 0L) {
            plugin.getLogger().warning("cooldown-seconds must be 0 or greater, using 0.");
            seconds = 0L;
        }
        return seconds * 1000L;
    }

    private static String readMessage(JavaPlugin plugin, FileConfiguration config, String path, String fallback) {
        String message = config.getString(path);
        if (message == null || message.isBlank()) {
            plugin.getLogger().warning("Config key '" + path + "' is missing or empty, using the built-in default.");
            return fallback;
        }
        return message;
    }

    /** @return the SQLite file name, relative to the plugin data folder. */
    public String getDatabaseFileName() {
        return databaseFileName;
    }

    /** @return the minimum delay between two lookups by the same player, in milliseconds. */
    public long getCooldownMillis() {
        return cooldownMillis;
    }

    /** @return message template for a successful lookup. */
    public String getMessageResult() {
        return messageResult;
    }

    /** @return message template used when nothing is stored for the player. */
    public String getMessageNoRecord() {
        return messageNoRecord;
    }

    /** @return message template used when the name has never been seen by the server. */
    public String getMessageUnknownPlayer() {
        return messageUnknownPlayer;
    }

    /** @return message template used when the database cannot be read. */
    public String getMessageError() {
        return messageError;
    }

    /** @return message template used while the sender is rate limited. */
    public String getMessageCooldown() {
        return messageCooldown;
    }

    /** @return message template used when the sender lacks a permission. */
    public String getMessageNoPermission() {
        return messageNoPermission;
    }

    /** @return message sent after {@code /ping reload}. */
    public String getMessageReloaded() {
        return messageReloaded;
    }
}
