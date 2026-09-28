package de.lovinoes.velocitynetworkchat.showcase;

import com.velocitypowered.api.proxy.Player;
import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import de.lovinoes.networkutilitiescommon.config.YamlConfig;
import de.lovinoes.velocitynetworkchat.backend.BackendBridge;
import de.lovinoes.velocitynetworkchat.backend.Protocol;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces [item], [inv] and [pos] in a player's message with what they are holding, carrying
 * and standing on.
 *
 * The data lives on the player's backend server, so a message containing a token costs one round
 * trip to it. A message without one costs nothing and stays synchronous. Tokens only ever come
 * from what the player typed; nothing in a format or an item's own text is searched.
 */
public final class ShowcaseService {

    /** A kind of showcase: its bit in the protocol, and who may use it. */
    public record Kind(int part, boolean enabled, List<String> tokens, String permission) {
    }

    private final BackendBridge bridge;
    private final ShowcaseRenderer renderer;
    private final YamlConfig language;
    private final boolean enabled;
    private final long cooldownMillis;
    private final String cooldownBypassPermission;
    private final int maxPerMessage;

    /** Lower-cased token to the kind it asks for. */
    private final Map<String, Kind> byToken = new LinkedHashMap<>();
    private final Pattern pattern;

    private final Map<UUID, Long> lastUsed = new ConcurrentHashMap<>();

    public ShowcaseService(BackendBridge bridge, ShowcaseRenderer renderer, YamlConfig language, boolean enabled,
                           long cooldownMillis, String cooldownBypassPermission, int maxPerMessage,
                           List<Kind> kinds) {
        this.bridge = bridge;
        this.renderer = renderer;
        this.language = language;
        this.cooldownMillis = Math.max(0, cooldownMillis);
        this.cooldownBypassPermission = cooldownBypassPermission == null ? "" : cooldownBypassPermission.trim();
        this.maxPerMessage = Math.max(1, maxPerMessage);

        for (Kind kind : kinds) {
            if (!kind.enabled()) {
                continue;
            }
            for (String token : kind.tokens()) {
                String key = token.trim().toLowerCase(Locale.ROOT);
                // The first kind to claim a token keeps it, so a token listed twice is not
                // silently moved from one kind to the other.
                if (!key.isEmpty()) {
                    byToken.putIfAbsent(key, kind);
                }
            }
        }
        this.enabled = enabled && !byToken.isEmpty();
        this.pattern = compile(byToken.keySet());
    }

    /**
     * Longest first, so that of two tokens where one begins the other, the longer one wins rather
     * than the shorter matching part of it. Every token is quoted, so brackets and dots in a
     * token are taken literally, not as regex.
     */
    private static Pattern compile(java.util.Set<String> tokens) {
        if (tokens.isEmpty()) {
            return null;
        }
        List<String> sorted = new ArrayList<>(tokens);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        List<String> quoted = new ArrayList<>(sorted.size());
        for (String token : sorted) {
            quoted.add(Pattern.quote(token));
        }
        return Pattern.compile(String.join("|", quoted), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * @return the message with every usable token replaced, the message unchanged when there are
     *         none, or empty when the sender is still on cooldown. Empty means the message must
     *         not be sent at all: sending it with the tokens left as typed would still put every
     *         attempt in front of everyone, which is exactly the spam the cooldown is there to
     *         stop. The sender is told why. Never completes exceptionally: anything else that goes
     *         wrong leaves the tokens as typed, so the message itself is still delivered.
     */
    public CompletableFuture<Optional<Component>> apply(Player sender, Component message) {
        if (!enabled) {
            return CompletableFuture.completedFuture(Optional.of(message));
        }

        int parts = requestedParts(sender, PlainTextComponentSerializer.plainText().serialize(message));
        if (parts == 0) {
            return CompletableFuture.completedFuture(Optional.of(message));
        }

        if (onCooldown(sender)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        String server = sender.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("");
        return bridge.showcase(sender, parts)
                .thenApply(snapshot -> Optional.of(replace(sender, server, message, snapshot)))
                .exceptionally(failure -> Optional.of(message));
    }

    /** Which parts this player asked for and may use, as protocol bits. */
    public int requestedParts(Player sender, String plain) {
        int parts = 0;
        Matcher matcher = pattern.matcher(plain);
        while (matcher.find()) {
            Kind kind = byToken.get(matcher.group().toLowerCase(Locale.ROOT));
            if (kind != null && may(sender, kind)) {
                parts |= kind.part();
            }
        }
        return parts;
    }

    private static boolean may(Player sender, Kind kind) {
        return kind.permission().isEmpty() || sender.hasPermission(kind.permission());
    }

    /**
     * Records the use when it is allowed. Asking only counts when there is something to ask, so a
     * player chatting normally is never put on cooldown. A blocked attempt is not recorded, so
     * spamming does not push the end of the cooldown further out.
     */
    private boolean onCooldown(Player sender) {
        if (cooldownMillis == 0) {
            return false;
        }
        if (!cooldownBypassPermission.isEmpty() && sender.hasPermission(cooldownBypassPermission)) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long previous = lastUsed.get(sender.getUniqueId());
        if (previous != null && now - previous < cooldownMillis) {
            String template = language.getString("showcase.cooldown", "");
            if (!template.isBlank()) {
                long seconds = Math.max(1, (cooldownMillis - (now - previous) + 999) / 1000);
                sender.sendMessage(ChatColorParser.parse(template,
                        Placeholder.unparsed("seconds", String.valueOf(seconds))));
            }
            return true;
        }
        lastUsed.put(sender.getUniqueId(), now);
        return false;
    }

    public void forget(UUID player) {
        lastUsed.remove(player);
    }

    public Component replace(Player sender, String server, Component message, Optional<Protocol.Showcase> snapshot) {
        Map<Integer, Component> rendered = new java.util.HashMap<>();
        snapshot.ifPresent(answer -> {
            if (answer.answered(Protocol.ITEM)) {
                rendered.put(Protocol.ITEM, renderer.item(sender.getUsername(), server, answer.held()));
            }
            if (answer.answered(Protocol.INVENTORY)) {
                rendered.put(Protocol.INVENTORY, renderer.inventory(sender.getUsername(), server, answer.inventory()));
            }
            if (answer.answered(Protocol.POSITION) && answer.position() != null) {
                rendered.put(Protocol.POSITION, renderer.position(sender.getUsername(), server, answer.position()));
            }
        });

        String unavailable = language.getString("showcase.unavailable", "");
        AtomicInteger replaced = new AtomicInteger();

        return message.replaceText(builder -> builder
                .match(pattern)
                .replacement((match, typed) -> {
                    Kind kind = byToken.get(match.group().toLowerCase(Locale.ROOT));
                    if (kind == null || !may(sender, kind)) {
                        return typed;
                    }
                    // Past the cap a token stays as typed. Each one can carry a large hover, and
                    // that hover goes to every recipient, so fifty of them in one message is not
                    // a showcase, it is a way to flood everyone's connection.
                    if (replaced.get() >= maxPerMessage) {
                        return typed;
                    }
                    Component shown = rendered.get(kind.part());
                    if (shown == null) {
                        if (unavailable.isBlank()) {
                            return typed;
                        }
                        shown = ChatColorParser.parse(unavailable, Placeholder.unparsed("token", match.group()));
                    }
                    replaced.incrementAndGet();
                    return shown;
                }));
    }
}
