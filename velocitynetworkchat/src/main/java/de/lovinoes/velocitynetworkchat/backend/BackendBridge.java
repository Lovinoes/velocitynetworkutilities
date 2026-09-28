package de.lovinoes.velocitynetworkchat.backend;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asks PaperNetworkChat on a player's backend the things only a backend can know, and matches
 * each answer to the question that prompted it.
 *
 * Every question goes out through the connection of the player it is about, and an answer is
 * only accepted from that same player's connection, so one backend can never answer for a player
 * on another. A question that is not answered in time completes empty; callers treat that as
 * "the backend could not say" and fall back, so a missing or lagging backend never loses a
 * message.
 *
 * This is also the only listener for its channel, and it marks every message on it handled. That
 * keeps a modified client from writing to the channel and having the proxy pass it on to the
 * backend as though it came from here.
 */
public final class BackendBridge {

    private record Pending(UUID player, byte expectedType, CompletableFuture<Object> answer) {
    }

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final ChannelIdentifier channel;
    private final long timeoutMillis;

    private final AtomicLong nextId = new AtomicLong();
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    /** Servers already warned about, so a missing backend plugin logs once rather than per message. */
    private final Set<String> warnedServers = ConcurrentHashMap.newKeySet();

    public BackendBridge(ProxyServer proxyServer, Logger logger, String channelName, long timeoutMillis) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.channel = MinecraftChannelIdentifier.from(channelName);
        this.timeoutMillis = timeoutMillis;
    }

    public void start() {
        proxyServer.getChannelRegistrar().register(channel);
    }

    public void stop() {
        proxyServer.getChannelRegistrar().unregister(channel);
        // Anything still waiting would otherwise only give up at its timeout.
        pending.values().forEach(request -> request.answer().complete(null));
        pending.clear();
    }

    /** Everyone within range of the player on their backend, the player included. */
    public CompletableFuture<Optional<List<UUID>>> nearby(Player player, int range) {
        return ask(player, Protocol.REQUEST_NEARBY, Protocol.RESPONSE_NEARBY, range)
                .thenApply(answer -> answer instanceof NearbyAnswer nearby
                        ? Optional.of(nearby.players())
                        : Optional.<List<UUID>>empty());
    }

    /** @param parts which of Protocol.ITEM, INVENTORY and POSITION to fetch */
    public CompletableFuture<Optional<Protocol.Showcase>> showcase(Player player, int parts) {
        return ask(player, Protocol.REQUEST_SHOWCASE, Protocol.RESPONSE_SHOWCASE, parts)
                .thenApply(answer -> answer instanceof Protocol.Showcase showcase
                        ? Optional.of(showcase)
                        : Optional.<Protocol.Showcase>empty());
    }

    private record NearbyAnswer(List<UUID> players) {
    }

    private CompletableFuture<Object> ask(Player player, byte requestType, byte responseType, int argument) {
        // "? extends": Velocity-CTD declares getCurrentServer that way, and it compiles against both.
        Optional<? extends ServerConnection> connection = player.getCurrentServer();
        if (connection.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        long id = nextId.incrementAndGet();
        CompletableFuture<Object> answer = new CompletableFuture<>();
        pending.put(id, new Pending(player.getUniqueId(), responseType, answer));

        // The entry is removed however the question ends, answered or timed out, so the map
        // only ever holds questions that are genuinely still open.
        answer.completeOnTimeout(null, timeoutMillis, TimeUnit.MILLISECONDS)
                .whenComplete((result, failure) -> pending.remove(id));

        boolean sent = connection.get().sendPluginMessage(channel, Protocol.request(requestType, id, argument));
        if (!sent) {
            answer.complete(null);
        }
        // Async on purpose. A timeout completes on the JDK's single shared delay thread, and what
        // follows here renders a message and sends it to everyone who should see it. None of
        // that belongs on a thread every other timeout in the process is waiting on.
        return answer.thenApplyAsync(result -> {
            if (result == null) {
                warnOnce(connection.get().getServerInfo().getName());
            } else {
                warnedServers.remove(connection.get().getServerInfo().getName());
            }
            return result;
        });
    }

    private void warnOnce(String server) {
        if (warnedServers.add(server)) {
            logger.warn("Server '{}' did not answer on channel {} within {}ms. Is PaperNetworkChat installed "
                            + "and up to date there? Distance-limited chat falls back to the whole server, and "
                            + "[item], [inv] and [pos] stay as typed, until it does.",
                    server, channel.getId(), timeoutMillis);
        }
    }

    /** Last, so that no other plugin can set the result back to forward after this has decided. */
    @Subscribe(priority = -32767)
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(channel)) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        // Only a backend answers. A player on this channel is a client writing to it, which is
        // exactly what marking it handled above is there to stop.
        if (event.getSource() instanceof ServerConnection connection) {
            receive(connection, event.getData());
        }
    }

    private void receive(ServerConnection connection, byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte type = in.readByte();
            long id = in.readLong();

            Pending request = pending.get(id);
            if (request == null) {
                // Already timed out, or never asked. Late answers are expected and harmless.
                return;
            }
            if (request.expectedType() != type
                    || !request.player().equals(connection.getPlayer().getUniqueId())) {
                logger.warn("Server '{}' answered request {} for the wrong player or with the wrong type; ignoring it.",
                        connection.getServerInfo().getName(), id);
                return;
            }

            Object answer = switch (type) {
                case Protocol.RESPONSE_NEARBY -> new NearbyAnswer(Protocol.readNearby(in));
                case Protocol.RESPONSE_SHOWCASE -> Protocol.readShowcase(in);
                default -> null;
            };
            request.answer().complete(answer);
        } catch (IOException | RuntimeException e) {
            logger.warn("Server '{}' sent a malformed answer on {}: {}",
                    connection.getServerInfo().getName(), channel.getId(), e.toString());
        }
    }
}
