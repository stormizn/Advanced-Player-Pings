package me.stormizn.eldenstead.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Small holder for the values we read out of {@code config.yml}. Reading them
 * once here keeps the rest of the code from poking at the raw config, and
 * gives us sensible defaults if a key is missing. Recreate this after a reload
 * to pick up edits.
 */
public class PluginConfig {

    private final String databaseFileName;
    private final String messageNoRecord;
    private final String messageResult;

    /**
     * Pulls the values straight from the plugin's config. Defaults are used for
     * any key that isn't present.
     */
    public PluginConfig(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        this.databaseFileName = config.getString("database.file-name", "pings.db");
        this.messageNoRecord = config.getString("messages.no-record", "No record for %player%.");
        this.messageResult = config.getString("messages.result", "%player%: %ping%ms (%time%)");
    }

    /** @return the SQLite file name, relative to the plugin data folder. */
    public String getDatabaseFileName() {
        return databaseFileName;
    }

    /** @return message shown when we have no ping stored for a player yet. */
    public String getMessageNoRecord() {
        return messageNoRecord;
    }

    /** @return message shown for a successful lookup (with placeholders). */
    public String getMessageResult() {
        return messageResult;
    }
}
