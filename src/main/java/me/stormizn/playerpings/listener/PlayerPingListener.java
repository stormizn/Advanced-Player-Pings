package me.stormizn.playerpings.listener;

import me.stormizn.playerpings.storage.PingDatabase;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records a player's ping when they disconnect, so the stored value reflects the
 * last time the server actually saw them rather than the last time somebody ran
 * {@code /ping} on them.
 */
public final class PlayerPingListener implements Listener {

    private final Logger logger;
    private final PingDatabase database;

    /**
     * @param logger   where write failures are reported
     * @param database where the quit ping is stored
     */
    public PlayerPingListener(Logger logger, PingDatabase database) {
        this.logger = logger;
        this.database = database;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        int ping = player.getPing();
        if (ping <= 0 || database.isClosed()) {
            // Not enough samples yet (or the connection died before a measurement),
            // or the plugin is shutting down and storage is already gone.
            return;
        }

        database.savePing(player.getUniqueId(), player.getName(), ping, System.currentTimeMillis())
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        logger.log(Level.WARNING, "Could not record the quit ping of " + player.getName() + ".", error);
                    }
                });
    }
}
