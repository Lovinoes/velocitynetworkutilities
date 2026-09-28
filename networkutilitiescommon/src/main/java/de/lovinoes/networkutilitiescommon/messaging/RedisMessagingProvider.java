package de.lovinoes.networkutilitiescommon.messaging;

import de.lovinoes.networkutilitiescommon.config.YamlConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cross-process pub/sub via Redis. Works identically on the Velocity proxy and Paper backends,
 * used to synchronize state across multiple proxy instances or between proxy and backend servers.
 */
public final class RedisMessagingProvider implements NetworkMessagingProvider {

    private static final System.Logger LOGGER = System.getLogger(RedisMessagingProvider.class.getName());
    private static final long RECONNECT_DELAY_MS = 5000;

    private final Map<String, List<MessageListener>> listeners = new ConcurrentHashMap<>();
    private final JedisPool jedisPool;
    private final String channelPrefix;
    private final ExecutorService subscriberExecutor;
    private JedisPubSub pubSub;

    public RedisMessagingProvider(YamlConfig config, String configRoot) {
        String host = config.getString(configRoot + ".host", "127.0.0.1");
        int port = config.getInt(configRoot + ".port", 6379);
        String password = config.getString(configRoot + ".password", "");
        int database = config.getInt(configRoot + ".database", 0);
        this.channelPrefix = config.getString(configRoot + ".channel-prefix", "vnu");

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        this.jedisPool = password == null || password.isBlank()
                ? new JedisPool(poolConfig, host, port, 5000)
                : new JedisPool(poolConfig, host, port, 5000, password, database);

        this.subscriberExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "NetworkUtilities-Redis-Subscriber");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start() {
        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                String topic = channel.substring(channelPrefix.length() + 1);
                byte[] payload = message.getBytes(StandardCharsets.UTF_8);
                listeners.getOrDefault(topic, List.of()).forEach(listener -> listener.onMessage(topic, payload));
            }
        };
        subscriberExecutor.submit(this::subscribeLoop);
    }

    /**
     * jedis.psubscribe(...) blocks for as long as the subscription stays connected and returns
     * (or throws) the moment it drops. Without a loop here, a single network blip would kill
     * cross-proxy sync silently and permanently until the proxy restarts.
     */
    private void subscribeLoop() {
        while (!subscriberExecutor.isShutdown()) {
            try (var jedis = jedisPool.getResource()) {
                jedis.psubscribe(pubSub, channelPrefix + ".*");
            } catch (JedisConnectionException e) {
                if (subscriberExecutor.isShutdown()) {
                    return;
                }
                LOGGER.log(Level.WARNING, "Lost connection to Redis, reconnecting in " + RECONNECT_DELAY_MS + "ms", e);
                sleepBeforeReconnect();
            } catch (RuntimeException e) {
                if (subscriberExecutor.isShutdown()) {
                    return;
                }
                LOGGER.log(Level.ERROR, "Redis subscriber failed unexpectedly, reconnecting in " + RECONNECT_DELAY_MS + "ms", e);
                sleepBeforeReconnect();
            }
        }
    }

    private void sleepBeforeReconnect() {
        try {
            Thread.sleep(RECONNECT_DELAY_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        if (pubSub != null) {
            pubSub.punsubscribe();
        }
        subscriberExecutor.shutdownNow();
        jedisPool.close();
        listeners.clear();
    }

    @Override
    public void publish(String topic, byte[] payload) {
        try (var jedis = jedisPool.getResource()) {
            jedis.publish(channelPrefix + "." + topic, new String(payload, StandardCharsets.UTF_8));
        }
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
     * Must tolerate a topic that is no longer there. stop() clears every topic, and the plugins
     * that depend on this one usually unsubscribe after it has stopped. The old
     * getOrDefault(topic, List.of()).remove(...) then called remove on an immutable list, which
     * throws even when there is nothing to remove.
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
