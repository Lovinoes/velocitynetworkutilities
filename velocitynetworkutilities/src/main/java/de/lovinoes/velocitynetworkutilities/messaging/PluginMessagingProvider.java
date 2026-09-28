package de.lovinoes.velocitynetworkutilities.messaging;

import de.lovinoes.networkutilitiescommon.messaging.MessageListener;
import de.lovinoes.networkutilitiescommon.messaging.NetworkMessagingProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-proxy pub/sub bus. All 5 network utility modules share one Velocity JVM, so publishing
 * here is a same-process dispatch scoped to the velocity:network_utils channel name.
 */
public final class PluginMessagingProvider implements NetworkMessagingProvider {

    private final Map<String, List<MessageListener>> listeners = new ConcurrentHashMap<>();

    @Override
    public void start() {
        // No external transport to open for the in-process bus.
    }

    @Override
    public void stop() {
        listeners.clear();
    }

    @Override
    public void publish(String topic, byte[] payload) {
        MessageListener.deliver(listeners.getOrDefault(topic, List.of()), topic, payload);
    }

    /**
     * The add happens inside compute so it is atomic with the map entry. Adding to the list
     * returned by computeIfAbsent instead would race with unsubscribe dropping that same list
     * once it emptied, and the listener would land in a list nothing reads any more.
     */
    @Override
    public void subscribe(String topic, MessageListener listener) {
        listeners.compute(topic, (key, current) -> {
            List<MessageListener> list = current != null ? current : new CopyOnWriteArrayList<>();
            list.add(listener);
            return list;
        });
    }

    /**
     * Must tolerate a topic that is no longer there. This module is usually shut down before the
     * plugins that depend on it, and stop() clears every topic, so their unsubscribe arrives
     * afterwards. The old getOrDefault(topic, List.of()).remove(...) then called remove on an
     * immutable list, which throws even when there is nothing to remove.
     *
     * A topic left with no listeners is dropped, so the map does not keep empty lists forever.
     */
    @Override
    public void unsubscribe(String topic, MessageListener listener) {
        listeners.computeIfPresent(topic, (key, current) -> {
            current.remove(listener);
            return current.isEmpty() ? null : current;
        });
    }
}
