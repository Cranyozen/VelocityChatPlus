package com.velocitychat;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * Custom TabList (自定义 TabList).
 * <p>
 * Periodically refreshes every online player's tab list with a custom header,
 * footer and an entry for every player across the network sorted by server.
 * Configured in the {@code tablist:} section of config.yml.
 */
public class TabListManager {

    private final VelocityChat plugin;
    private final ProxyServer server;
    private final ConfigManager config;
    private final Logger logger;

    private ScheduledTask task;

    public TabListManager(VelocityChat plugin, ProxyServer server, ConfigManager config, Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.config = config;
        this.logger = logger;
    }

    /**
     * Apply the custom TabList immediately for all online players.
     */
    public void refresh() {
        if (!config.isTabListEnabled()) {
            // Feature turned off: make sure no stale custom header/footer remains
            for (Player viewer : server.getAllPlayers()) {
                try {
                    viewer.getTabList().clearHeaderAndFooter();
                } catch (Exception ignored) {
                }
            }
            return;
        }

        int online = server.getAllPlayers().size();
        Component header = Component.text(ColorUtils.translate(
                config.getTabListHeader().replace("{online}", String.valueOf(online))));
        Component footer = Component.text(ColorUtils.translate(
                config.getTabListFooter().replace("{online}", String.valueOf(online))));

        // Sorted list: server (display) then player name
        var entries = server.getAllPlayers().stream()
                .sorted(Comparator
                        .comparing((Player p) -> p.getCurrentServer()
                                .map(s -> config.getServerDisplayName(s.getServerInfo().getName()))
                                .orElse("§7-"))
                        .thenComparing(Player::getUsername))
                .toList();

        for (Player viewer : server.getAllPlayers()) {
            var tab = viewer.getTabList();
            try {
                tab.setHeaderAndFooter(header, footer);
                // Remove stale entries not currently online (they disconnect on their own,
                // but clearAll avoids duplicates across our refreshes)
                tab.clearAll();
                for (Player other : entries) {
                    Component display = Component.text(
                            serverDisplay(other) + other.getUsername());
                    tab.addEntry(tab.buildEntry(other.getGameProfile(), display,
                            ping(other), 0));
                }
            } catch (Exception e) {
                logger.debug("Failed to update tab list for {}", viewer.getUsername(), e);
            }
        }
    }

    private String serverDisplay(Player player) {
        return player.getCurrentServer()
                .map(s -> config.getServerDisplayName(s.getServerInfo().getName()) + " §8▏")
                .orElse("§7Proxy §8▏");
    }

    private int ping(Player player) {
        try {
            long ms = player.getPing();
            return (int) Math.max(0, Math.min(Integer.MAX_VALUE, ms));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Start the background refresh loop (placeholder text uses a fixed 1s schedule docked
     * on the main scheduler; refreshing every few seconds keeps it responsive).
     */
    public void start() {
        if (task != null) return;
        task = server.getScheduler()
                .buildTask(plugin, this::refresh)
                .repeat(3L, TimeUnit.SECONDS)
                .schedule();
        logger.info("Custom TabList task started (enabled={})", config.isTabListEnabled());
    }

    /**
     * Stop the refresh loop (on reload and shutdown).
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}