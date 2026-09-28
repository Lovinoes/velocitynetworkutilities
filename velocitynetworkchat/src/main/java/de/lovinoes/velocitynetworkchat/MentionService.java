package de.lovinoes.velocitynetworkchat;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import de.lovinoes.velocitynetworkutilities.api.VelocityNetworkAPI;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Highlights @name mentions in a chat message and pings whoever was named.
 *
 * A mention only counts when the name resolves to a player who is online, who the sender is
 * allowed to see, and who is actually going to receive this message.
 *
 * The visibility rule stops mentions being used to probe for vanished staff: without it you
 * could type "@name" and learn they are online from whether it turned yellow.
 *
 * The recipient rule stops ghost pings. Channels scoped to one server, and messages from a
 * vanished sender, only reach part of the network, so naming someone outside that set would
 * ping them for a message they never see.
 */
public final class MentionService {

    /** Minecraft usernames: 3-16 characters of letters, digits and underscores. */
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([A-Za-z0-9_]{3,16})");

    private final ProxyServer proxyServer;
    private final MentionSettings settings;
    private final String highlightFormat;
    private final Sound sound;

    public MentionService(ProxyServer proxyServer, MentionSettings settings, String highlightFormat, Sound sound) {
        this.proxyServer = proxyServer;
        this.settings = settings;
        this.highlightFormat = highlightFormat;
        this.sound = sound;
    }

    /**
     * Result of scanning a message: the message with mentions styled, and who to ping.
     */
    public record Result(Component message, Set<Player> mentioned) {
    }

    /**
     * @param recipientFilter the same test the broadcast uses to decide who receives this
     *                        message, so a mention can never reach someone the message does not.
     */
    public Result process(Player sender, Component message, Predicate<Player> recipientFilter) {
        Set<Player> mentioned = new LinkedHashSet<>();

        Component highlighted = message.replaceText(builder -> builder
                .match(MENTION_PATTERN)
                .replacement((matchResult, textBuilder) -> {
                    String name = matchResult.group(1);
                    Player target = resolve(sender, name, recipientFilter);
                    if (target == null) {
                        // Not a real, visible, reachable player: leave the text exactly as typed.
                        return textBuilder;
                    }
                    mentioned.add(target);
                    // Use the player's real name so casing is normalised in the highlight.
                    return ChatColorParser.parse(highlightFormat,
                            Placeholder.unparsed("name", target.getUsername()));
                }));

        return new Result(highlighted, mentioned);
    }

    /**
     * Plays the ping to everyone mentioned, skipping the sender and anyone who opted out.
     *
     * Sound.Emitter.self() is not optional here. Velocity only implements
     * playSound(Sound, Emitter); the no-argument playSound(Sound) it inherits from Audience is a
     * default method whose body is literally "return", so calling it plays nothing at all.
     */
    public void playMentionSounds(Player sender, Set<Player> mentioned) {
        if (sound == null) {
            return;
        }
        for (Player target : mentioned) {
            if (target.getUniqueId().equals(sender.getUniqueId())) {
                continue;
            }
            if (settings.isSoundEnabled(target.getUniqueId())) {
                target.playSound(sound, Sound.Emitter.self());
            }
        }
    }

    private Player resolve(Player sender, String name, Predicate<Player> recipientFilter) {
        return proxyServer.getPlayer(name)
                .filter(target -> canSee(sender, target))
                .filter(recipientFilter)
                .orElse(null);
    }

    private boolean canSee(Player sender, Player target) {
        return !VelocityNetworkAPI.get().isVanished(target.getUniqueId())
                || sender.hasPermission(VelocityNetworkAPI.get().seeVanishedPermission())
                || sender.getUniqueId().equals(target.getUniqueId());
    }
}
