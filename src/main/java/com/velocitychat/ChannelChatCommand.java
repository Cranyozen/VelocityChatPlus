package com.velocitychat;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Player commands for the chat channels (分区聊天) feature.
 * <p>
 * Usage:
 *   /ch               — show current channel &amp; help
 *   /ch list          — list available channels
 *   /ch join <id>     — join a channel
 *   /ch leave         — leave back to the default channel
 *   /ch <message>     — send a message to your current channel
 * <p>
 * Registered as /ch with /channel alias.
 */
public class ChannelChatCommand implements SimpleCommand {

    private final ProxyServer server;
    private final ChannelManager channels;
    private final ConfigManager config;
    private final GroupManager groups;
    private final ForbiddenWordsManager forbiddenWords;
    private final Logger logger;

    public ChannelChatCommand(ProxyServer server, ChannelManager channels, ConfigManager config,
                              GroupManager groups, ForbiddenWordsManager forbiddenWords, Logger logger) {
        this.server = server;
        this.channels = channels;
        this.config = config;
        this.groups = groups;
        this.forbiddenWords = forbiddenWords;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (!(source instanceof Player player)) {
            ColorUtils.sendMessage(source, logger, config.getMessage("ch.console"));
            return;
        }

        if (!config.isChannelsEnabled()) {
            player.sendMessage(Component.text(config.getMessage("ch.disabled")));
            return;
        }

        if (args.length == 0) {
            showStatus(player);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "join" -> join(player, args);
            case "leave" -> leave(player);
            case "list" -> list(player);
            default -> sendToChannel(player, String.join(" ", args));
        }
    }

    private void showStatus(Player player) {
        String help = "§6=== 频道 / ch ===\n"
                + "§7/ch list §f- 查看可用频道\n"
                + "§7/ch join <频道> §f- 加入频道\n"
                + "§7/ch leave §f- 返回默认频道\n"
                + "§7/ch <消息> §f- 发送到当前频道";
        player.sendMessage(Component.text(help));

        channels.getPlayerChannel(player).ifPresent(c ->
                player.sendMessage(Component.text(config.getMessage("ch.status",
                        c.displayName(), String.valueOf(channels.getChannelPlayerCount(c.getId()))))));
    }

    private void join(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text(config.getMessage("ch.join.usage")));
            return;
        }
        String id = args[1].toLowerCase();
        var opt = channels.getChannel(id);
        if (opt.isEmpty()) {
            player.sendMessage(Component.text(config.getMessage("ch.not_found", id)));
            return;
        }
        var channel = opt.get();
        if (!channels.canJoin(player, channel)) {
            player.sendMessage(Component.text(config.getMessage("ch.no_permission", channel.displayName())));
            return;
        }
        channels.setPlayerChannel(player, id);
        player.sendMessage(Component.text(config.getMessage("ch.joined", channel.displayName())));
        logger.info("{} joined channel {}", player.getUsername(), id);
    }

    private void leave(Player player) {
        var opt = channels.getPlayerChannel(player);
        if (opt.isEmpty() || opt.get().isDefault()) {
            player.sendMessage(Component.text(config.getMessage("ch.already_default")));
            return;
        }
        var def = channels.getDefaultChannel();
        channels.setPlayerChannel(player, def.getId());
        player.sendMessage(Component.text(config.getMessage("ch.left", def.displayName())));
    }

    private void list(Player player) {
        StringBuilder sb = new StringBuilder("§6=== 可用频道 (" + channels.getChannels().size() + ") ===");
        for (var c : channels.getChannels()) {
            String marker = c.isDefault() ? " §8[默认]" : "";
            String perm = c.getPermission() == null || c.getPermission().isBlank() ? "" : " §7(需权限)";
            String featured = channels.getPlayerChannel(player)
                    .map(pc -> pc.getId().equalsIgnoreCase(c.getId()) ? " §a← 当前" : "").orElse("");
            sb.append("\n").append(c.displayName())
                    .append(" §7(").append(c.getId()).append(")")
                    .append(marker).append(perm).append(featured);
        }
        player.sendMessage(Component.text(sb.toString()));
    }

    /**
     * Compose and deliver a chat message to the player's active channel.
     */
    void sendToChannel(Player player, String rawMessage) {
        var opt = channels.getPlayerChannel(player);
        if (opt.isEmpty()) {
            player.sendMessage(Component.text(config.getMessage("ch.no_channel")));
            return;
        }
        var channel = opt.get();

        // 违禁词检测 / Forbidden word filter
        if (forbiddenWords.isEnabled()
                && !forbiddenWords.hasBypass(player)
                && forbiddenWords.containsForbiddenWord(rawMessage)) {
            player.sendMessage(Component.text(config.getMessage("qu_an.chat.message.filter.blocked")));
            logger.info("Blocked forbidden channel message from {}: {}", player.getUsername(), rawMessage);
            return;
        }

        // Sender display name (with group title)
        String senderName = player.getUsername();
        String title = groups.getPlayerTitle(player.getUsername());
        if (!title.isEmpty()) senderName = title + " " + senderName;

        String serverName = "§7Proxy";
        if (player.getCurrentServer().isPresent()) {
            serverName = config.getServerDisplayName(player.getCurrentServer().get().getServerInfo().getName());
        }

        String content = ColorUtils.translate(rawMessage);
        String format = config.getChannelFormat();
        String formatted = format
                .replace("{0}", channel.displayName())
                .replace("{1}", senderName)
                .replace("{2}", serverName)
                .replace("{3}", content);

        channels.broadcastToChannel(channel.getId(), formatted, true, player);
        logger.info(ColorUtils.toAnsi(formatted));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        String prefix = args.length > 0 ? args[args.length - 1].toLowerCase() : "";
        if (args.length <= 1) {
            return filterSuggestions(List.of("join", "leave", "list"), prefix);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
            List<String> ids = channels.getChannels().stream()
                    .map(c -> c.getId())
                    .collect(Collectors.toCollection(ArrayList::new));
            return filterSuggestions(ids, prefix);
        }
        return List.of();
    }

    private List<String> filterSuggestions(List<String> suggestions, String prefix) {
        if (prefix.isEmpty()) return suggestions;
        return suggestions.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix))
                .collect(Collectors.toList());
    }
}