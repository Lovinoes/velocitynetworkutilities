package de.lovinoes.velocitynetworkchat.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import de.lovinoes.velocitynetworkchat.AntiSpam;
import de.lovinoes.velocitynetworkchat.ChannelManager;
import de.lovinoes.velocitynetworkchat.PrivateMessageManager;
import de.lovinoes.velocitynetworkchat.SenderQueue;
import de.lovinoes.velocitynetworkchat.showcase.ShowcaseService;

import java.util.UUID;

/**
 * Everything this plugin remembers per player lives in memory and is keyed by UUID: the active
 * channel, the /r reply target, the showcase cooldown, the anti-spam timer and the message queue. Without this they
 * would accumulate one stale entry per player who has ever connected, for the life of the proxy.
 */
public final class SessionCleanupListener {

    private final ChannelManager channelManager;
    private final PrivateMessageManager privateMessageManager;
    private final ShowcaseService showcaseService;
    private final SenderQueue senderQueue;
    private final AntiSpam antiSpam;

    public SessionCleanupListener(ChannelManager channelManager, PrivateMessageManager privateMessageManager,
                                  ShowcaseService showcaseService, SenderQueue senderQueue, AntiSpam antiSpam) {
        this.channelManager = channelManager;
        this.privateMessageManager = privateMessageManager;
        this.showcaseService = showcaseService;
        this.senderQueue = senderQueue;
        this.antiSpam = antiSpam;
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        channelManager.clearActiveChannel(player);
        privateMessageManager.clearSession(player);
        showcaseService.forget(player);
        senderQueue.forget(player);
        antiSpam.forget(player);
    }
}
