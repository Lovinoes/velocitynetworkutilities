package de.lovinoes.velocitynetworkutilities;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.networkutilitiescommon.config.MessagingType;
import de.lovinoes.networkutilitiescommon.config.YamlConfig;
import de.lovinoes.networkutilitiescommon.database.DatabaseManager;
import de.lovinoes.networkutilitiescommon.messaging.NetworkMessagingProvider;
import de.lovinoes.networkutilitiescommon.messaging.RedisMessagingProvider;
import de.lovinoes.networkutilitiescommon.vault.VaultCacheDao;
import de.lovinoes.velocitynetworkutilities.api.VelocityNetworkAPI;
import de.lovinoes.velocitynetworkutilities.messaging.PluginMessagingProvider;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "velocitynetworkutilities",
        name = "VelocityNetworkUtilities",
        version = "1.0.0",
        authors = {"Lovinoes"}
)
public final class VelocityNetworkUtilitiesPlugin {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;

    private DatabaseManager databaseManager;
    private NetworkMessagingProvider messagingProvider;
    private VelocityNetworkAPI networkAPI;

    @Inject
    public VelocityNetworkUtilitiesPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        YamlConfig config = YamlConfig.load(dataDirectory, "config.yml", getClass().getClassLoader());

        this.databaseManager = new DatabaseManager(config, dataDirectory);
        logger.info("Database pool initialized ({} backend)", databaseManager.type());

        MessagingType messagingType = config.getEnum("messaging.provider", MessagingType.PLUGIN_MESSAGING);
        this.messagingProvider = messagingType == MessagingType.REDIS
                ? new RedisMessagingProvider(config, "messaging.redis")
                : new PluginMessagingProvider();
        this.messagingProvider.start();
        logger.info("Messaging provider active: {}", messagingType);

        String vaultCacheTable = config.getString("vault.table", "network_vault_cache");
        VaultCacheDao vaultCacheDao = new VaultCacheDao(databaseManager, vaultCacheTable);
        this.networkAPI = new VelocityNetworkAPI(databaseManager, messagingProvider, vaultCacheDao);

        logger.info("VelocityNetworkUtilities core initialized.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (messagingProvider != null) {
            messagingProvider.stop();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        logger.info("VelocityNetworkUtilities shut down cleanly.");
    }

    public VelocityNetworkAPI networkAPI() {
        return networkAPI;
    }
}
