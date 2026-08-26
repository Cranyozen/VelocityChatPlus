package com.velocitychat;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import org.slf4j.Logger;

/**
 * Routes normal player chat into the player's active channel (分区聊天).
 * <p>
 * When channel chat is enabled and {@code route-chat} is on, ordinary chat
 * messages are intercepted, forwarded to everyone on the same channel across
 * servers, and prevented from reaching the backend server directly. Player join
 * / leave also resets the player to the default channel.
 */
public class ChatRouter {

    private final ChannelManager channels;
    private final ConfigManager config;
    private final ChannelChatCommand channelCommand;
    private final Logger logger;

    public ChatRouter(ChannelManager channels, ConfigManager config,
                      ChannelChatCommand channelCommand, Logger logger) {
        this.channels = channels;
        this.config = config;
        this.channelCommand = channelCommand;
        this.logger = logger;
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        if (!config.isChannelsEnabled() || !config.isRouteChatEnabled()) return;

        var player = event.getPlayer();
        var message = event.getMessage();

        // A player with no active channel is not part of any conversation here —
        // let the message pass through to their backend normally.
        var channelOpt = channels.getPlayerChannel(player);
        if (channelOpt.isEmpty()) return;

        String mode = config.getRouteChatMode().trim();
        logger.debug("[ChatRouter] mode='{}' sendToSender={}", mode, !"channel-cross".equals(mode));

        switch (mode) {
            case "channel":
                event.setResult(PlayerChatEvent.ChatResult.denied());
                break;
            case "channel-log":
                event.setResult(PlayerChatEvent.ChatResult.denied());
                logger.info("<{}> {}", player.getUsername(), message);
                break;
            case "channel-cross":
                break;
            case "both":
            default:
                break;
        }

        boolean sendToSender = !"channel-cross".equals(mode);
        channelCommand.sendToChannel(player, message, sendToSender);
    }

    /**
     * Reset a player's active channel when they disconnect so a fresh session
     * starts on the default channel again.
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        channels.resetPlayerChannel(event.getPlayer());
    }
}