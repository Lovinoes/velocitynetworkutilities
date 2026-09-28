package de.lovinoes.velocitynetworkchat.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.velocitynetworkchat.AntiSpam;
import de.lovinoes.velocitynetworkchat.ChannelManager;
import de.lovinoes.velocitynetworkchat.ChatChannel;
import de.lovinoes.velocitynetworkchat.MentionService;
import de.lovinoes.velocitynetworkchat.PlaceholderResolver;
import de.lovinoes.velocitynetworkchat.PlayerActions;
import de.lovinoes.velocitynetworkchat.SenderQueue;
import de.lovinoes.velocitynetworkchat.VaultDataCache;
import de.lovinoes.velocitynetworkchat.backend.BackendBridge;
import de.lovinoes.velocitynetworkchat.showcase.ShowcaseService;
import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import de.lovinoes.networkutilitiescommon.vanish.VanishPermissions;
import de.lovinoes.velocitynetworkutilities.api.VelocityNetworkAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Chat is captured here on the proxy so one set of channels, formats and colour rules covers the
 * whole network. Denying PlayerChatEvent would normally kick a 1.19.1+ client, because the proxy
 * cannot drop a signed message on its own; SignedVelocity (proxy plugin plus its backend half)
 * is what makes that safe, and it is required for this plugin to work.
 *
 * Each message is worked out in this order:
 * <ol>
 *   <li>its audience. For a distance-limited channel that means asking the sender's backend who
 *       is in range, because only the backend knows where anyone is standing.</li>
 *   <li>mentions, against exactly that audience, so nobody is pinged for a message they will
 *       not receive</li>
 *   <li>[item], [inv] and [pos], from the sender's backend</li>
 *   <li>the channel's format, through PlaceholderAPI if it needs it</li>
 * </ol>
 * and then sent to the audience by the proxy itself. The rendered message never travels to a
 * backend: a server accepts at most 32767 bytes of plugin message from the proxy and disconnects
 * the player over anything larger, which a message carrying an inventory can easily be.
 */
public final class ChatListener {

    private final ProxyServer proxyServer;
    private final ChannelManager channelManager;
    private final VaultDataCache vaultDataCache;
    private final BackendBridge bridge;
    private final MentionService mentionService;
    private final ShowcaseService showcaseService;
    private final PlaceholderResolver placeholderResolver;
    private final SenderQueue senderQueue;
    private final String colorPermission;
    private final String noChannelPermissionFormat;
    private final PlayerActions playerActions;
    private final AntiSpam antiSpam;

    public ChatListener(ProxyServer proxyServer, ChannelManager channelManager, VaultDataCache vaultDataCache,
                         BackendBridge bridge, MentionService mentionService, ShowcaseService showcaseService,
                         PlaceholderResolver placeholderResolver, SenderQueue senderQueue, String colorPermission,
                         String noChannelPermissionFormat, PlayerActions playerActions, AntiSpam antiSpam) {
        this.proxyServer = proxyServer;
        this.channelManager = channelManager;
        this.vaultDataCache = vaultDataCache;
        this.bridge = bridge;
        this.mentionService = mentionService;
        this.showcaseService = showcaseService;
        this.placeholderResolver = placeholderResolver;
        this.senderQueue = senderQueue;
        this.colorPermission = colorPermission;
        this.noChannelPermissionFormat = noChannelPermissionFormat;
        this.playerActions = playerActions;
        this.antiSpam = antiSpam;
    }

    /**
     * setResult is deprecated because, on its own, denying a 1.19.1+ client's signed chat
     * disconnects them. That is precisely the problem SignedVelocity exists to solve: its proxy
     * half defers the decision to its backend half, which can drop a signed message safely. So
     * this call is deliberate and supported here, given SignedVelocity is a hard requirement.
     */
    @SuppressWarnings("deprecation")
    // Positive priority so this runs early, before other chat handlers.
    @Subscribe(priority = 100)
    public void onPlayerChat(PlayerChatEvent event) {
        // Something that ran earlier has already stopped this message, most likely a mute from
        // VelocityNetworkModeration, which sits at a higher priority precisely so it gets here
        // first. Velocity keeps calling the remaining handlers regardless, so without this the
        // message would still be formatted and broadcast to the whole channel.
        if (!event.getResult().isAllowed()) {
            return;
        }

        Player sender = event.getPlayer();
        // The message as it stands after earlier handlers, not as the client sent it: the word
        // filter in VelocityNetworkModeration runs first and may have censored it.
        String rawMessage = event.getResult().getMessage().orElse(event.getMessage());

        Optional<ChatChannel> triggered = channelManager.byPrefixTrigger(rawMessage,
                candidate -> mayWrite(sender, candidate));
        ChatChannel channel = triggered.orElseGet(() -> channelManager.activeChannel(sender.getUniqueId()));
        String message = triggered.isPresent() ? rawMessage.substring(channel.prefixTrigger().length()) : rawMessage;

        event.setResult(PlayerChatEvent.ChatResult.denied());

        if (!mayWrite(sender, channel)) {
            // Only reachable through the active channel now, since a prefix never routes anyone
            // into a channel they cannot write in: someone switched into it while they still had
            // the permission and has since lost it. Say so rather than swallowing the message,
            // because a silent drop looks exactly like the chat plugin being broken.
            if (!noChannelPermissionFormat.isBlank()) {
                sender.sendMessage(ChatColorParser.parse(noChannelPermissionFormat,
                        Placeholder.unparsed("channel", channel.displayName()),
                        Placeholder.unparsed("channel_key", channel.key())));
            }
            return;
        }

        // After the permission check, so a message that was never going to be sent does not
        // start a cooldown. AntiSpam tells the player why when it refuses.
        if (!antiSpam.allowChat(sender, channel.key(), message)) {
            return;
        }

        Component messageComponent = sender.hasPermission(colorPermission)
                ? ChatColorParser.parse(message)
                : ChatColorParser.plain(message);

        senderQueue.submit(sender.getUniqueId(), () -> process(sender, channel, messageComponent));
    }

    private static boolean mayWrite(Player sender, ChatChannel channel) {
        return channel.permission().isEmpty() || sender.hasPermission(channel.permission());
    }

    private CompletableFuture<Void> process(Player sender, ChatChannel channel, Component typed) {
        return audience(sender, channel).thenCompose(audience -> {
            Set<UUID> ids = new HashSet<>();
            audience.forEach(player -> ids.add(player.getUniqueId()));

            MentionService.Result mentions = mentionService.process(sender, typed,
                    player -> ids.contains(player.getUniqueId()));

            // Empty when the sender is on showcase cooldown: the message is dropped, not sent
            // with its tokens as typed, and ShowcaseService has already told them why.
            return showcaseService.apply(sender, mentions.message())
                    .thenCompose(shown -> shown.isEmpty()
                            ? CompletableFuture.<Void>completedFuture(null)
                            : format(sender, channel)
                                    .thenAccept(format -> deliver(sender, channel, format, shown.get(), audience,
                                            mentions.mentioned())));
        });
    }

    /**
     * Who receives the message. The sender always does, even when a read permission or their own
     * vanish would otherwise leave them out: seeing your own message is how you know it was sent.
     */
    private CompletableFuture<List<Player>> audience(Player sender, ChatChannel channel) {
        if (!channel.hasDistanceLimit()) {
            return CompletableFuture.completedFuture(everyoneWhoCanRead(sender, channel));
        }
        return bridge.nearby(sender, channel.distance()).thenApply(nearby -> {
            if (nearby.isEmpty()) {
                // The backend could not say who is in range, most likely because PaperNetworkChat
                // is not installed there. Falling back to the whole server keeps the message
                // rather than losing it; BackendBridge logs which server did not answer.
                return everyoneWhoCanRead(sender, channel);
            }
            List<Player> inRange = new ArrayList<>();
            inRange.add(sender);
            for (UUID id : nearby.get()) {
                if (id.equals(sender.getUniqueId())) {
                    continue;
                }
                proxyServer.getPlayer(id)
                        .filter(player -> canReceive(sender, channel, player))
                        .ifPresent(inRange::add);
            }
            return inRange;
        });
    }

    private List<Player> everyoneWhoCanRead(Player sender, ChatChannel channel) {
        List<Player> readers = new ArrayList<>();
        readers.add(sender);
        for (Player player : proxyServer.getAllPlayers()) {
            if (!player.getUniqueId().equals(sender.getUniqueId()) && canReceive(sender, channel, player)) {
                readers.add(player);
            }
        }
        return readers;
    }

    /**
     * Only the format from config is ever run through PlaceholderAPI, never the player's own
     * message. Resolving placeholders in player input would let anyone print another player's
     * placeholder values just by typing them. It is skipped entirely when the format has no '%'.
     */
    private CompletableFuture<String> format(Player sender, ChatChannel channel) {
        String format = channel.format();
        return placeholderResolver.needsResolving(format)
                ? placeholderResolver.resolve(format, sender.getUniqueId())
                : CompletableFuture.completedFuture(format);
    }

    private void deliver(Player sender, ChatChannel channel, String format, Component message,
                         List<Player> audience, Set<Player> mentioned) {
        Component rendered = ChatColorParser.parse(format, placeholders(sender, channel, message));
        proxyServer.getConsoleCommandSource().sendMessage(rendered);
        for (Player recipient : audience) {
            recipient.sendMessage(rendered);
        }
        mentionService.playMentionSounds(sender, mentioned);
    }

    private TagResolver[] placeholders(Player sender, ChatChannel channel, Component message) {
        String server = sender.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("");
        return new TagResolver[] {
                Placeholder.component("player", playerActions.decorate(sender.getUsername())),
                Placeholder.component("message", message),
                Placeholder.unparsed("server", server),
                Placeholder.unparsed("channel", channel.displayName()),
                Placeholder.unparsed("channel_key", channel.key()),
                Placeholder.unparsed("vault_prefix", vaultDataCache.getPrefix(sender.getUniqueId())),
                Placeholder.unparsed("vault_suffix", vaultDataCache.getSuffix(sender.getUniqueId())),
                Placeholder.unparsed("vault_group", vaultDataCache.getGroup(sender.getUniqueId()))
        };
    }

    /**
     * Whether one player may read what another writes in a channel: the channel's scope, its read
     * permission, and the sender's vanish. For a distance-limited channel this is applied on top
     * of the backend's answer about who is in range.
     */
    private boolean canReceive(Player sender, ChatChannel channel, Player recipient) {
        if (channel.scope() == ChatChannel.ChannelScope.SERVER && !isOnSameServer(sender, recipient)) {
            return false;
        }
        // Without this a channel's permission only decided who could WRITE into it, and every
        // message went to the whole network regardless. A staff channel was readable by every
        // player online, which is the opposite of what a staff channel is for.
        if (!channel.readPermission().isEmpty() && !recipient.hasPermission(channel.readPermission())) {
            return false;
        }
        return !VelocityNetworkAPI.get().isVanished(sender.getUniqueId())
                || recipient.hasPermission(VanishPermissions.SEE_VANISHED);
    }

    private boolean isOnSameServer(Player sender, Player recipient) {
        return sender.getCurrentServer().isPresent()
                && recipient.getCurrentServer().isPresent()
                && sender.getCurrentServer().get().getServerInfo()
                        .equals(recipient.getCurrentServer().get().getServerInfo());
    }
}
