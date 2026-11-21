package me.stormizn.playerpings;

import me.stormizn.playerpings.command.PingCommand;
import me.stormizn.playerpings.config.PluginConfig;
import me.stormizn.playerpings.listener.PlayerPingListener;
import me.stormizn.playerpings.storage.PingDatabase;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Main plugin class. Wires up the config, the SQLite storage layer, the
 * {@code /ping} command and the quit listener, and makes sure the database is
 * opened on enable and drained and closed again on disable so a reload does not
 * leak connections or lose queued writes.
 */
public final class PlayerPingsPlugin extends JavaPlugin {

    private volatile PluginConfig pluginConfig;
    private PingDatabase database;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.pluginConfig = new PluginConfig(this);

        try {
            this.database = new PingDatabase(this, pluginConfig.getDatabaseFileName());
            this.database.initialise();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE,
                    "The ping database could not be initialised, disabling " + getName() + ".", e);
            if (database != null) {
                database.close();
                database = null;
            }
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PluginCommand command = getCommand("ping");
        if (command == null) {
            getLogger().severe("Command 'ping' is missing from plugin.yml, disabling " + getName() + ".");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PingCommand executor = new PingCommand(this, database);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getServer().getPluginManager().registerEvents(new PlayerPingListener(getLogger(), database), this);

        getLogger().info("Enabled - pings are stored in " + pluginConfig.getDatabaseFileName() + ".");
    }

    /** Drains queued writes and closes the pool so a restart starts from a clean slate. */
    @Override
    public void onDisable() {
        PingDatabase current = this.database;
        this.database = null;
        if (current != null) {
            current.close();
            getLogger().info("Database closed.");
        }
    }

    /** @return the config currently in use; never {@code null} after {@code onEnable}. */
    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    /**
     * Re-reads {@code config.yml} and swaps in a fresh {@link PluginConfig}. The
     * database file name cannot change at runtime, so a change is only reported.
     *
     * @return the new config, for message formatting after a reload
     */
    public PluginConfig reloadPluginConfig() {
        reloadConfig();
        PluginConfig previous = this.pluginConfig;
        PluginConfig reloaded = new PluginConfig(this);
        if (previous != null && !previous.getDatabaseFileName().equals(reloaded.getDatabaseFileName())) {
            getLogger().warning("database.file-name changed to '" + reloaded.getDatabaseFileName()
                    + "'; restart the server for it to take effect.");
        }
        this.pluginConfig = reloaded;
        getLogger().info("Configuration reloaded.");
        return reloaded;
    }
}
