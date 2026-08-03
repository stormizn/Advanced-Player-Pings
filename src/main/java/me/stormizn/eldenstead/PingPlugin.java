package me.stormizn.eldenstead;

import me.stormizn.eldenstead.command.PingCommand;
import me.stormizn.eldenstead.config.PluginConfig;
import me.stormizn.eldenstead.storage.PingDatabase;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin class. Wires up the config, the SQLite storage layer and the
 * {@code /ping} command, and makes sure the database pool is opened on enable
 * and closed again on disable (so {@code /reload} doesn't leak connections).
 */
public final class PingPlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private PingDatabase database;

    /**
     * Loads config, opens the database and registers the command. If the
     * database fails to come up we disable the plugin rather than run in a
     * broken state.
     */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.pluginConfig = new PluginConfig(this);

        try {
            this.database = new PingDatabase(this, pluginConfig.getDatabaseFileName());
            this.database.initialise();
            getLogger().info("SQLite storage initialised successfully.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialise database, disabling plugin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PingCommand pingCommand = new PingCommand(this, database, pluginConfig);
        var command = getCommand("ping");
        if (command != null) {
            command.setExecutor(pingCommand);
            command.setTabCompleter(pingCommand);
        } else {
            getLogger().warning("Command 'ping' is missing from plugin.yml.");
        }

        getLogger().info("Plugin enabled.");
    }

    /** Closes the database pool so a reload/restart starts from a clean slate. */
    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
            getLogger().info("Database connection pool closed.");
        }
        getLogger().info("Plugin disabled.");
    }

    /** @return the loaded config values. */
    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }
}
