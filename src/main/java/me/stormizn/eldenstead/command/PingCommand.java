package me.stormizn.eldenstead.command;

import me.stormizn.eldenstead.PingPlugin;
import me.stormizn.eldenstead.config.PluginConfig;
import me.stormizn.eldenstead.storage.PingDatabase;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Backs the {@code /ping <player>} command and its tab completion.
 * <p>
 * If the target is online we grab their live ping, store it, and report it. If
 * they're offline we fall back to whatever we last saved for them. Tab
 * completion just suggests the names of players currently online.
 */
public final class PingCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final PingPlugin plugin;
    private final PingDatabase database;
    private final PluginConfig config;

    /**
     * @param plugin   owning plugin, used for scheduling and logging
     * @param database where pings are read from and written to
     * @param config   message templates and settings
     */
    public PingCommand(PingPlugin plugin, PingDatabase database, PluginConfig config) {
        this.plugin = plugin;
        this.database = database;
        this.config = config;
    }

    /**
     * Handles {@code /ping <player>}. Expects exactly one argument (the name).
     *
     * @return always {@code true} so Bukkit doesn't print the usage line itself;
     *         we handle bad input with our own message.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /ping <player>");
            return true;
        }

        String targetName = args[0];
        Player online = Bukkit.getPlayerExact(targetName);

        if (online != null) {
            int ping = online.getPing();
            long now = System.currentTimeMillis();
            database.savePing(online.getUniqueId(), online.getName(), ping, now)
                    .whenComplete((v, err) -> {
                        if (err != null) {
                            plugin.getLogger().warning("Async save failed: " + err.getMessage());
                        }
                    });
            sender.sendMessage(format(config.getMessageResult(), online.getName(), ping, now));
            return true;
        }

        var offline = Bukkit.getOfflinePlayer(targetName);
        database.getPing(offline.getUniqueId()).whenComplete((record, err) -> {
            // Hop back to the main thread before messaging the sender.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (err != null || record == null || record.isEmpty()) {
                    sender.sendMessage(format(config.getMessageNoRecord(), targetName, 0, 0));
                } else {
                    var r = record.get();
                    sender.sendMessage(format(config.getMessageResult(), r.name(), r.ping(), r.updatedAt()));
                }
            });
        });
        return true;
    }

    /**
     * Suggests online player names for the first argument, filtered by whatever
     * the sender has typed so far.
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    matches.add(p.getName());
                }
            }
            return matches;
        }
        return List.of();
    }

    private String format(String template, String player, int ping, long timestamp) {
        String time = timestamp > 0 ? TIME_FORMAT.format(Instant.ofEpochMilli(timestamp)) : "n/a";
        String out = template
                .replace("%player%", player)
                .replace("%ping%", String.valueOf(ping))
                .replace("%time%", time);
        return ChatColor.translateAlternateColorCodes('&', out);
    }
}
