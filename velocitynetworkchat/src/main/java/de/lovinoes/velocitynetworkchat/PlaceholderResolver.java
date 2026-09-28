package de.lovinoes.velocitynetworkchat;

import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.william278.papiproxybridge.api.PlaceholderAPI;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves PlaceholderAPI placeholders (%player_name%, %vault_rank%, ...) in a format string by
 * asking the backend server through PAPIProxyBridge.
 *
 * Four things shape how this is used:
 *
 * 1. It is optional. PAPIProxyBridge is compileOnly, so if the plugin is not installed the API
 *    class simply is not there at runtime. Availability is probed once at startup by trying to
 *    load the class rather than by looking up a plugin id, so a rename on their side cannot
 *    silently switch this off, and the result is logged either way.
 * 2. It is asynchronous, because resolving means a round trip to the backend. Callers therefore
 *    only pay that cost when a format actually contains a '%', which {@link #needsResolving} is
 *    for. A format with no PlaceholderAPI placeholders stays entirely synchronous.
 * 3. The API instance is created once and reused. createInstance() hands back a fresh object
 *    with its own cache, so building one per message would throw that cache away every time.
 * 4. A value is never read as MiniMessage. Values can come from players (a nickname, a team
 *    name), so each placeholder is resolved on its own and put back as a component that keeps
 *    its colours but can never become a click, hover or insertion. Pasting the values into the
 *    format instead would let a nickname carry a command for whoever clicks it.
 *
 * Only format strings from config are ever resolved, never a player's own message: running
 * placeholders over player input would let anyone print another player's placeholder values just
 * by typing them in chat.
 */
public final class PlaceholderResolver {

    private static final long RESOLVE_TIMEOUT_SECONDS = 2;

    /** A PlaceholderAPI placeholder: %something% with no spaces or tag brackets inside. */
    private static final Pattern TOKEN = Pattern.compile("%[^%\\s<>]+%");

    /** The format with each placeholder replaced by a tag, and the tags' values. */
    public record Resolved(String format, TagResolver[] placeholders) {

        static Resolved unchanged(String format) {
            return new Resolved(format, new TagResolver[0]);
        }
    }

    private final Logger logger;
    private final Bridge bridge;

    public PlaceholderResolver(Logger logger) {
        this.logger = logger;
        this.bridge = probeAvailability() ? new Bridge() : null;
        if (bridge != null) {
            logger.info("PAPIProxyBridge detected: PlaceholderAPI placeholders in chat formats will be resolved.");
        } else {
            logger.info("PAPIProxyBridge not installed: any %placeholder% in a chat format is left as written. "
                    + "Install it on the proxy and every backend server to enable them.");
        }
    }

    private static boolean probeAvailability() {
        try {
            Class.forName("net.william278.papiproxybridge.api.PlaceholderAPI");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    public boolean isAvailable() {
        return bridge != null;
    }

    /** True only when resolving is both possible and actually necessary for this text. */
    public boolean needsResolving(String text) {
        return bridge != null && TOKEN.matcher(text).find();
    }

    /**
     * Resolves placeholders for the given player. Never completes exceptionally: a placeholder
     * that fails or times out stays as written, so a chat message is still delivered.
     */
    public CompletableFuture<Resolved> resolve(String format, UUID player) {
        if (!needsResolving(format)) {
            return CompletableFuture.completedFuture(Resolved.unchanged(format));
        }
        Map<String, CompletableFuture<String>> values = new LinkedHashMap<>();
        Matcher matcher = TOKEN.matcher(format);
        while (matcher.find()) {
            values.computeIfAbsent(matcher.group(), token -> resolveOne(token, player));
        }
        return CompletableFuture.allOf(values.values().toArray(CompletableFuture[]::new))
                // A timeout completes on the JDK's single shared delay thread; what follows
                // delivers the message, which does not belong there.
                .thenApplyAsync(ignored -> assemble(format, values));
    }

    private CompletableFuture<String> resolveOne(String token, UUID player) {
        try {
            // Capped: a backend that never answers must not hold this message, and with it every
            // later message from the same player, which waits its turn behind it.
            return bridge.format(token, player)
                    .completeOnTimeout(token, RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(throwable -> {
                        logger.warn("Could not resolve {}, leaving it as written.", token, throwable);
                        return token;
                    })
                    .thenApply(value -> value == null ? token : value);
        } catch (RuntimeException | LinkageError e) {
            logger.warn("PAPIProxyBridge failed while resolving {}, leaving it as written.", token, e);
            return CompletableFuture.completedFuture(token);
        }
    }

    /**
     * Each placeholder becomes a tag carrying its value as a component. One written inside
     * another tag, such as the colour in {@code <color:%my_color%>}, cannot be a component, so
     * its value goes in as text with everything that could end the tag taken out.
     */
    static Resolved assemble(String format, Map<String, CompletableFuture<String>> values) {
        StringBuilder out = new StringBuilder(format.length());
        List<TagResolver> placeholders = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(format);
        int last = 0;
        while (matcher.find()) {
            out.append(format, last, matcher.start());
            String value = values.get(matcher.group()).join();
            if (insideTag(format, matcher.start())) {
                out.append(value.replaceAll("[<>'\"\\\\]", ""));
            } else {
                String name = "papi_" + placeholders.size();
                placeholders.add(Placeholder.component(name, ChatColorParser.parseUntrusted(value)));
                out.append('<').append(name).append('>');
            }
            last = matcher.end();
        }
        out.append(format, last, format.length());
        return new Resolved(out.toString(), placeholders.toArray(TagResolver[]::new));
    }

    /** Whether this position is between a tag's opening '<' and its closing '>'. */
    private static boolean insideTag(String text, int position) {
        return text.lastIndexOf('<', position) > text.lastIndexOf('>', position);
    }

    /**
     * Kept as its own class so the PAPIProxyBridge types are only loaded when the plugin is
     * actually installed. Holding a PlaceholderAPI field on the outer class could trigger a
     * NoClassDefFoundError just by loading it on a proxy without the bridge.
     */
    private static final class Bridge {
        private final PlaceholderAPI api = PlaceholderAPI.createInstance();

        CompletableFuture<String> format(String text, UUID player) {
            return api.formatPlaceholders(text, player);
        }
    }
}
