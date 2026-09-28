package de.lovinoes.velocitynetworkchat;

import net.william278.papiproxybridge.api.PlaceholderAPI;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves PlaceholderAPI placeholders (%player_name%, %vault_rank%, ...) in a format string by
 * asking the backend server through PAPIProxyBridge.
 *
 * Three things shape how this is used:
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
 *
 * Only format strings from config are ever resolved, never a player's own message: running
 * placeholders over player input would let anyone print another player's placeholder values just
 * by typing them in chat.
 */
public final class PlaceholderResolver {

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
        return bridge != null && text.indexOf('%') >= 0;
    }

    /**
     * Resolves placeholders for the given player. Never completes exceptionally: on timeout or
     * any other failure the original text is returned so a chat message is still delivered.
     */
    public CompletableFuture<String> resolve(String text, UUID player) {
        if (!needsResolving(text)) {
            return CompletableFuture.completedFuture(text);
        }
        try {
            return bridge.format(text, player)
                    .exceptionally(throwable -> {
                        logger.warn("Could not resolve PlaceholderAPI placeholders, sending the format unresolved.",
                                throwable);
                        return text;
                    });
        } catch (RuntimeException | LinkageError e) {
            logger.warn("PAPIProxyBridge failed while resolving placeholders, sending the format unresolved.", e);
            return CompletableFuture.completedFuture(text);
        }
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
