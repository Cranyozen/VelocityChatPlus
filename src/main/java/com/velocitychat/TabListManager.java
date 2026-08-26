package com.velocitychat;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Custom TabList (自定义 TabList).
 * <p>
 * Periodically refreshes every online player's tab list with a custom header,
 * footer and an entry for every real player across the network sorted by server.
 * Configured in the {@code tablist:} section of config.yml.
 * <p>
 * Entries are updated <strong>in place</strong> rather than clearing the whole
 * list, so Carpet fake players (mod bots spawned with {@code /player spawn}) —
 * which are injected directly by the backend server and never appear in the
 * proxy's online player list — are preserved in the tab instead of being wiped
 * on each refresh. Only entries this plugin added for cross-server players that
 * have since gone offline are removed, so a stale tab never builds up.
 */
public class TabListManager {

    private final VelocityChat plugin;
    private final ProxyServer server;
    private final ConfigManager config;
    private final GroupManager groupManager;
    private final Logger logger;

    private ScheduledTask task;

    // Tracks which entries we added ourselves, per viewer UUID (player UUID ->
    // set of profile UUIDs we injected). Used to clean up offline cross-server
    // players without ever touching backend-injected entries such as Carpet bots.
    private final ConcurrentHashMap<UUID, Set<UUID>> proxyEntries = new ConcurrentHashMap<>();

    public TabListManager(VelocityChat plugin, ProxyServer server, ConfigManager config,
                          GroupManager groupManager, Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.config = config;
        this.groupManager = groupManager;
        this.logger = logger;
    }

    /**
     * Apply the custom TabList immediately for all online players.
     */
    public void refresh() {
        boolean manage = config.isTabListManageHeaderFooter();
        if (!config.isTabListEnabled()) {
            // Feature turned off: make sure no stale custom header/footer remains
            for (Player viewer : server.getAllPlayers()) {
                try {
                    viewer.getTabList().clearHeaderAndFooter();
                } catch (Exception ignored) {
                }
            }
            proxyEntries.clear();
            return;
        }

        // Header/footer are only owned by the plugin when manage-header-footer is on.
        // When off (e.g. Carpet /log owns the tab footer), pass null so refreshViewer
        // never writes or clears header/footer and the backend keeps them.
        // Header/footer with {online} replaced but {server} left as token
        // for per-viewer resolution in refreshViewer.
        String headerRaw = null;
        String footerRaw = null;
        if (manage) {
            int online = server.getAllPlayers().size();
            headerRaw = config.getTabListHeader().replace("{online}", String.valueOf(online));
            footerRaw = config.getTabListFooter().replace("{online}", String.valueOf(online));
        }

        // Snapshot of real online players, sorted by server then name
        List<Player> allPlayers = new ArrayList<>(server.getAllPlayers());
        allPlayers.sort((a, b) -> {
            int cmp = serverLabel(a).compareToIgnoreCase(serverLabel(b));
            return cmp != 0 ? cmp : a.getUsername().compareToIgnoreCase(b.getUsername());
        });

        for (Player viewer : allPlayers) {
            refreshViewer(viewer, headerRaw, footerRaw, allPlayers);
        }
    }

    private void refreshViewer(Player viewer, String headerRaw, String footerRaw, List<Player> allPlayers) {
        var tab = viewer.getTabList();
        UUID viewerId = viewer.getUniqueId();
        try {
            // Only own the header/footer region when manage-header-footer is on
            if (headerRaw != null) {
                String serverName = serverLabel(viewer);
                String playerPing = String.valueOf(ping(viewer));

                String h = headerRaw.replace("{server}", serverName)
                        .replace("{ping}", playerPing);
                String f = footerRaw.replace("{server}", serverName)
                        .replace("{ping}", playerPing);

                boolean hEmpty = h.isBlank();
                boolean fEmpty = f.isBlank();
                if (!hEmpty || !fEmpty) {
                    Component header = hEmpty ? Component.empty() : Component.text(ColorUtils.translate(h));
                    Component footer = fEmpty ? Component.empty() : Component.text(ColorUtils.translate(f));
                    tab.setHeaderAndFooter(header, footer);
                }
            }

            // Remembered cross-server entries we added for this viewer
            Set<UUID> added = proxyEntries.computeIfAbsent(viewerId, k -> ConcurrentHashMap.newKeySet());
            // Look up every real online player by their tab-list profile id.
            Map<UUID, Player> byProfileId = new HashMap<>();
            for (Player p : allPlayers) byProfileId.put(p.getGameProfile().getId(), p);
            // The profile ids currently present in the viewer's own tab
            Set<UUID> present = new HashSet<>();
            // The lowercased entry names already present (used to avoid re-adding a
            // real player whose UUID couldn't be matched above).
            Set<String> presentNames = new HashSet<>();

            logger.info("[TabList] viewer '{}' viewers='{}' online='{}' proxy-remembered='{}'",
                    viewer.getUsername(), viewerId, byProfileId.size(), added);

            for (TabListEntry entry : tab.getEntries()) {
                UUID id = entry.getProfile().getId();
                String dsp = entry.getDisplayNameComponent()
                        .map(c -> c.toString())  // Component#toString is available in every Adventure version
                        .orElse("<none>");
                present.add(id);
                String entryName = entry.getProfile().getName();
                // Resolve the real player by UUID only.  Name matching is intentionally
                // disabled: a Carpet fake player can share a real player's name, and
                // matching by name would misclassify the bot as the real player, hiding
                // the actual player from the tab.
                Player target = byProfileId.get(id);
                if (target != null) {
                    // Real online player: always apply custom format (server prefix + title).
                    presentNames.add(target.getUsername().toLowerCase());
                    entry.setDisplayName(entryComponent(target));
                    entry.setLatency(ping(target));
                    logger.info("[TabList]   entry id='{}' name='{}' dsp='{}' -> ONLINE '{}'",
                            id, entryName, dsp, target.getUsername());
                } else if (added.contains(id)) {
                    // Cross-server player we injected who has since disconnected
                    tab.removeEntry(id);
                    added.remove(id);
                    present.remove(id);
                    logger.info("[TabList]   entry id='{}' name='{}' dsp='{}' -> REMOVED (our cross-server, now offline)",
                            id, entryName, dsp);
                } else {
                    // Backend-injected entry (Carpet bot or stale leftover) — apply
                    // the bot-specific format so bots are visually distinct.
                    entry.setDisplayName(botComponent(viewer, entryName));
                    logger.info("[TabList]   entry id='{}' name='{}' dsp='{}' -> KEPT (bot, styled)",
                            id, entryName, dsp);
                }
            }

            // Add every real online player missing from the list, with custom format.
            for (Player other : allPlayers) {
                UUID id = other.getGameProfile().getId();
                if (present.contains(id)
                        || presentNames.contains(other.getUsername().toLowerCase())) continue;
                tab.addEntry(tab.buildEntry(other.getGameProfile(),
                        entryComponent(other), ping(other), 0));
                added.add(id);
                presentNames.add(other.getUsername().toLowerCase());
                logger.info("[TabList]   ADD id='{}' name='{}' (missing)",
                        id, other.getUsername());
            }

            // After the pass: report the exact set of profile ids now tracked for this viewer,
            // including any duplicates (same id appearing more than once) — that is the symptom.
            logger.info("[TabList] viewer '{}' final-pids='{}' remembered='{}'",
                    viewer.getUsername(), present, added);
        } catch (Exception e) {
            logger.debug("Failed to update tab list for {}", viewer.getUsername(), e);
        }
    }

    /**
     * The server display label for a real player ({server} placeholder).
     */
    private String serverLabel(Player player) {
        return player.getCurrentServer()
                .map(s -> config.getServerDisplayName(s.getServerInfo().getName()))
                .orElse("§7Proxy");
    }

    /**
     * Build the display component for a real online player, including any group title.
     */
    private Component entryComponent(Player player) {
        String title = groupManager.getPlayerTitle(player.getUsername());
        String group = groupManager.getPlayerGroup(player.getUsername());
        return Component.text(formatEntry(config.getTabListEntryFormat(),
                serverLabel(player), player.getUsername(), title, group, ping(player)));
    }

    /**
     * Build the display component for a backend-injected entry (e.g. a Carpet fake
     * player). The entry is labelled with the viewer's own server; if the entry's
     * name is in a title group (bots can be added to groups too), its title is shown.
     */
    private Component botComponent(Player viewer, String botName) {
        String title = groupManager.getPlayerTitle(botName);
        String group = groupManager.getPlayerGroup(botName);
        return Component.text(formatEntry(config.getTabListBotFormat(),
                serverLabel(viewer), botName, title, group, 0));
    }

    /**
     * Substitute the placeholders of the given format template and translate color codes.
     */
    private String formatEntry(String format, String server, String player,
                               String title, String group, int ping) {
        String t = (title == null || title.isBlank()) ? "" : ColorUtils.translate(title);
        String g = (group == null || group.isBlank()) ? "" : group;
        String name = player == null ? "" : player;
        String s = server == null ? "" : server;
        return ColorUtils.translate(format
                .replace("{server}", s)
                .replace("{player}", name)
                .replace("{title}", t)
                .replace("{group}", g)
                .replace("{ping}", String.valueOf(ping)));
    }

    /**
     * Returns true if both players are connected to the same backend server.
     */
    private static boolean isSameServer(Player a, Player b) {
        return a.getCurrentServer()
                .flatMap(as -> b.getCurrentServer()
                        .map(bs -> as.getServerInfo().getName().equals(bs.getServerInfo().getName())))
                .orElse(false);
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
     * Start (or restart) the background refresh loop with the current config interval.
     */
    public void start() {
        stop();
        long interval = config.getTabListRefreshInterval();
        task = server.getScheduler()
                .buildTask(plugin, this::refresh)
                .repeat(interval, TimeUnit.SECONDS)
                .schedule();
        logger.info("Custom TabList task started (enabled={}, interval={}s, manage-header-footer={})",
                config.isTabListEnabled(), interval, config.isTabListManageHeaderFooter());
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