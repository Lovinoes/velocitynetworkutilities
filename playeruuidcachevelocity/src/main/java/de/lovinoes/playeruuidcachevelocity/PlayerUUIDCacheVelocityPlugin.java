package de.lovinoes.playeruuidcachevelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.playeruuidcachevelocity.listener.PlayerConnectionListener;
import de.lovinoes.velocitynetworkutilities.api.VelocityNetworkAPI;
import de.lovinoes.networkutilitiescommon.config.YamlConfig;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Plugin(
        id = "playeruuidcachevelocity",
        name = "PlayerUUIDCacheVelocity",
        version = "1.0.0",
        description = "Player name, UUID and session cache for the network.",
        url = "https://lovinoes.de",
        authors = {"Lovinoes"},
        dependencies = {@Dependency(id = "velocitynetworkutilities")}
)
public final class PlayerUUIDCacheVelocityPlugin {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private final Executor lookupExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "PlayerUUIDCacheVelocity-Mojang-Lookup");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public PlayerUUIDCacheVelocityPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        YamlConfig config = YamlConfig.load(dataDirectory, "config.yml", getClass().getClassLoader());

        String table = config.getString("table.name", "uuid_cache");
        long lookupTimeout = config.getLong("mojang.lookup-timeout-ms", 5000);

        PlayerCacheDao dao = new PlayerCacheDao(VelocityNetworkAPI.get().database(), table);
        MojangLookupService mojangLookupService = new MojangLookupService(lookupExecutor, lookupTimeout);
        new PlayerCacheAPI(dao, mojangLookupService);

        proxyServer.getEventManager().register(this, new PlayerConnectionListener());

        logger.info("PlayerUUIDCacheVelocity initialized with table '{}'.", table);
    }
}
