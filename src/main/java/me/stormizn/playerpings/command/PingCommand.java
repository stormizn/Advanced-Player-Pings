package me.stormizn.playerpings.command;

import me.stormizn.playerpings.PlayerPingsPlugin;
import me.stormizn.playerpings.config.PluginConfig;
import me.stormizn.playerpings.storage.PingDatabase;
import me.stormizn.playerpings.storage.PingDatabase.PingRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Backs {@code /ping <player>} and {@code /ping reload}.
 * <p>
 * An online target is pinged, stored and reported; an offline target is looked up
 * from the database. Replies are always sent on the main thread, and only after the
 * database operation actually succeeded, so a broken storage layer is visible to the
 * sender instead of being reported as "no record".
 */
public final class PingCommand implements CommandExecutor, TabCompleter {

    private static final String RELOAD_ARGUMENT = "reload";
    private static final String PERMISSION_USE = "ping.use";
    private static final String PERMISSION_RELOAD = "ping.reload";

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final PlayerPingsPlugin plugin;
    private final PingDatabase database;
    private final Map<UUID, Long> lastUsage = new ConcurrentHashMap<>();

    /**
     * @param plugin   owning plugin, used for the config, scheduling and logging
     * @param database where pings are read from and written to
     */
    public PingCommand(PlayerPingsPlugin plugin, PingDatabase database) {
        this.plugin = plugin;
        this.database = database;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        PluginConfig config = plugin.getPluginConfig();

        if (!sender.hasPermission(PERMISSION_USE)) {
            send(sender, config.getMessageNoPermission());
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(LEGACY.deserialize("&cUsage: /" + label + " " + command.getUsage()));
            return true;
        }

        String argument = args[0];
        Player online = Bukkit.getPlayerExact(argument);

        if (online == null && RELOAD_ARGUMENT.equalsIgnoreCase(argument)) {
            if (!sender.hasPermission(PERMISSION_RELOAD)) {
                send(sender, config.getMessageNoPermission());
                return true;
            }
            send(sender, plugin.reloadPluginConfig().getMessageReloaded());
            return true;
        }

        long remainingCooldown = registerUsage(sender, config.getCooldownMillis());
        if (remainingCooldown > 0L) {
            send(sender, config.getMessageCooldown(),
                    Map.of("%seconds%", String.format(Locale.ROOT, "%.1f", remainingCooldown / 1000.0D)));
            return true;
        }

        if (online != null) {
            reportOnlinePing(sender, online);
            return true;
        }

        reportStoredPing(sender, argument);
        return true;
    }

    /** Measures and stores the ping of a player who is currently online. */
    private void reportOnlinePing(CommandSender sender, Player target) {
        String name = target.getName();
        UUID uuid = target.getUniqueId();
        int ping = target.getPing();
        long timestamp = System.currentTimeMillis();

        database.savePing(uuid, name, ping, timestamp).whenComplete((ignored, error) -> runOnMainThread(() -> {
            PluginConfig config = plugin.getPluginConfig();
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Could not store the ping of " + name + ".", error);
                send(sender, config.getMessageError(), Map.of("%player%", name));
                return;
            }
            send(sender, config.getMessageResult(), Map.of(
                    "%player%", name,
                    "%ping%", Integer.toString(ping),
                    "%time%", formatTime(timestamp)));
        }));
    }

    /** Looks up the last stored ping of a player who is not online. */
    private void reportStoredPing(CommandSender sender, String targetName) {
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(targetName);
        if (cached == null) {
            send(sender, plugin.getPluginConfig().getMessageUnknownPlayer(), Map.of("%player%", targetName));
            return;
        }

        database.getPing(cached.getUniqueId()).whenComplete((record, error) -> runOnMainThread(() -> {
            PluginConfig config = plugin.getPluginConfig();
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Could not read the stored ping of " + targetName + ".", error);
                send(sender, config.getMessageError(), Map.of("%player%", targetName));
                return;
            }
            if (record == null || record.isEmpty()) {
                send(sender, config.getMessageNoRecord(), Map.of("%player%", targetName));
                return;
            }
            PingRecord stored = record.get();
            send(sender, config.getMessageResult(), Map.of(
                    "%player%", stored.name(),
                    "%ping%", Integer.toString(stored.ping()),
                    "%time%", formatTime(stored.updatedAt())));
        }));
    }

    /**
     * Applies the per-player cooldown.
     *
     * @return 0 if the sender may proceed (usage is recorded), otherwise the remaining wait
     */
    private long registerUsage(CommandSender sender, long cooldownMillis) {
        if (cooldownMillis <= 0L || !(sender instanceof Player player)) {
            return 0L;
        }

        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        Long previous = lastUsage.get(uuid);
        if (previous != null) {
            long elapsed = now - previous;
            if (elapsed >= 0L && elapsed < cooldownMillis) {
                return cooldownMillis - elapsed;
            }
        }

        lastUsage.put(uuid, now);
        if (lastUsage.size() > 512) {
            lastUsage.entrySet().removeIf(entry -> now - entry.getValue() > cooldownMillis * 10L);
        }
        return 0L;
    }

    /** Hops back onto the main thread, tolerating a plugin that is disabling right now. */
    private void runOnMainThread(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException e) {
            plugin.getLogger().log(Level.FINE, "Dropped a /ping reply because the plugin was disabled.", e);
        }
    }

    private static String formatTime(long timestamp) {
        return timestamp > 0L ? TIME_FORMAT.format(Instant.ofEpochMilli(timestamp)) : "n/a";
    }

    private void send(CommandSender sender, String template) {
        sender.sendMessage(render(template, Map.of()));
    }

    /**
     * Renders a message template: the template itself is parsed once for legacy colour
     * codes, then every placeholder is inserted as literal text so player input can
     * never inject colour codes or other formatting.
     */
    private void send(CommandSender sender, String template, Map<String, String> placeholders) {
        sender.sendMessage(render(template, placeholders));
    }

    private static Component render(String template, Map<String, String> placeholders) {
        Component message = LEGACY.deserialize(template);
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            message = message.replaceText(TextReplacementConfig.builder()
                    .matchLiteral(placeholder.getKey())
                    .replacement(Component.text(placeholder.getValue()))
                    .build());
        }
        return message;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission(PERMISSION_USE)) {
            return List.of();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        if (sender.hasPermission(PERMISSION_RELOAD) && RELOAD_ARGUMENT.startsWith(prefix)) {
            matches.add(RELOAD_ARGUMENT);
        }

        List<String> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                players.add(player.getName());
            }
        }
        players.sort(String.CASE_INSENSITIVE_ORDER);
        matches.addAll(players);
        return matches;
    }
}
