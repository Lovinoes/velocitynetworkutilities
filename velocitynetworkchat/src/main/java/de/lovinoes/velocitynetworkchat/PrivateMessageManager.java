package de.lovinoes.velocitynetworkchat;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.velocitynetworkchat.showcase.ShowcaseService;
import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import de.lovinoes.velocitynetworkutilities.api.VelocityNetworkAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proxy-owned private messaging. Velocity's ProxyServer.getPlayer lookup spans every backend
 * server, so /msg works across the whole network instead of being limited to whichever
 * backend a local plugin's /msg command happened to be registered on.
 */
public final class PrivateMessageManager {

    private final ProxyServer proxyServer;
    private final String seeVanishedPermission;
    private final String colorPermission;
    private final String outgoingFormat;
    private final String incomingFormat;
    private final String notFoundFormat;
    private final String noReplyTargetFormat;
    private final MentionService mentionService;
    private final ShowcaseService showcaseService;
    private final SenderQueue senderQueue;
    private final AntiSpam antiSpam;
    private final Map<UUID, UUID> lastMessaged = new ConcurrentHashMap<>();

    public PrivateMessageManager(ProxyServer proxyServer, String seeVanishedPermission, String colorPermission,
                                  String outgoingFormat, String incomingFormat, String notFoundFormat,
                                  String noReplyTargetFormat, MentionService mentionService,
                                  ShowcaseService showcaseService, SenderQueue senderQueue, AntiSpam antiSpam) {
        this.antiSpam = antiSpam;
        this.proxyServer = proxyServer;
        this.seeVanishedPermission = seeVanishedPermission;
        this.colorPermission = colorPermission;
        this.outgoingFormat = outgoingFormat;
        this.incomingFormat = incomingFormat;
        this.notFoundFormat = notFoundFormat;
        this.noReplyTargetFormat = noReplyTargetFormat;
        this.mentionService = mentionService;
        this.showcaseService = showcaseService;
        this.senderQueue = senderQueue;
    }

    public void sendByName(Player sender, String targetName, String message) {
        // "? extends": Velocity-CTD declares getPlayer that way, and it compiles against both.
        Optional<? extends Player> target = proxyServer.getPlayer(targetName);
        if (target.isEmpty() || isHiddenFrom(sender, target.get())) {
            sendNotFound(sender, targetName);
            return;
        }
        deliver(sender, target.get(), message);
    }

    public void reply(Player sender, String message) {
        UUID targetId = lastMessaged.get(sender.getUniqueId());
        if (targetId == null) {
            sender.sendMessage(ChatColorParser.parse(noReplyTargetFormat));
            return;
        }
        // "? extends": Velocity-CTD declares getPlayer that way, and it compiles against both.
        Optional<? extends Player> target = proxyServer.getPlayer(targetId);
        if (target.isEmpty() || isHiddenFrom(sender, target.get())) {
            sender.sendMessage(ChatColorParser.parse(noReplyTargetFormat));
            return;
        }
        deliver(sender, target.get(), message);
    }

    /**
     * Drops the disconnecting player's own reply target, and clears them as anyone else's
     * reply target too, so the map doesn't accumulate entries for players who left.
     */
    public void clearSession(UUID playerId) {
        lastMessaged.remove(playerId);
        lastMessaged.values().removeIf(playerId::equals);
    }

    private boolean isHiddenFrom(Player viewer, Player target) {
        return VelocityNetworkAPI.get().isVanished(target.getUniqueId())
                && !viewer.hasPermission(seeVanishedPermission)
                && !viewer.getUniqueId().equals(target.getUniqueId());
    }

    /**
     * Runs in the sender's queue alongside their channel messages, so a private message with
     * [item] in it and whatever they type next still arrive in the order they were sent.
     */
    private void deliver(Player sender, Player target, String message) {
        // Only once the target is known to exist, so a mistyped name does not start a cooldown.
        if (!antiSpam.allowPrivate(sender, target.getUniqueId(), message)) {
            return;
        }
        Component messageComponent = sender.hasPermission(colorPermission)
                ? ChatColorParser.parse(message)
                : ChatColorParser.plain(message);

        senderQueue.submit(sender.getUniqueId(), () -> {
            // A private message reaches exactly two people, so they are the only ones who can be
            // mentioned in it. Naming a third player would otherwise ping them for a conversation
            // they cannot see, which is the same ghost ping the channel filter exists to prevent.
            MentionService.Result mentions = mentionService.process(sender, messageComponent,
                    recipient -> recipient.getUniqueId().equals(sender.getUniqueId())
                            || recipient.getUniqueId().equals(target.getUniqueId()));

            return showcaseService.apply(sender, mentions.message()).thenAccept(result -> {
                // Empty when the sender is on showcase cooldown: nothing is sent, and
                // ShowcaseService has already told them why.
                if (result.isEmpty()) {
                    return;
                }
                Component shown = result.get();
                Component toSender = ChatColorParser.parse(outgoingFormat,
                        Placeholder.unparsed("target", target.getUsername()),
                        Placeholder.component("message", shown));
                Component toTarget = ChatColorParser.parse(incomingFormat,
                        Placeholder.unparsed("sender", sender.getUsername()),
                        Placeholder.component("message", shown));

                sender.sendMessage(toSender);
                target.sendMessage(toTarget);
                mentionService.playMentionSounds(sender, mentions.mentioned());

                // Either of them may have left while this waited on a backend, after disconnect
                // cleanup had already run. Recording a reply target for someone who is gone would
                // put back exactly the entry clearSession removed, and nothing would clear it again.
                boolean bothOnline = proxyServer.getPlayer(sender.getUniqueId()).isPresent()
                        && proxyServer.getPlayer(target.getUniqueId()).isPresent();
                if (bothOnline) {
                    lastMessaged.put(sender.getUniqueId(), target.getUniqueId());
                    lastMessaged.put(target.getUniqueId(), sender.getUniqueId());
                }
            });
        });
    }

    private void sendNotFound(Player sender, String targetName) {
        sender.sendMessage(ChatColorParser.parse(notFoundFormat, Placeholder.unparsed("target", targetName)));
    }
}
