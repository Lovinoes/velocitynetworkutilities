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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Plugin(
        id = "velocitynetworkutilities",
        name = "VelocityNetworkUtilities",
        version = "1.0.0",
        description = "Shared database and messaging for the network plugins.",
        url = "https://lovinoes.de",
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

        moveLegacySqliteFile(config);
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

    /**
     * Last of all shutdown handlers: the other plugins save and unsubscribe in theirs, and need
     * the database and messaging still open while they do.
     */
    @Subscribe(priority = Short.MIN_VALUE)
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (messagingProvider != null) {
            messagingProvider.stop();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        logger.info("VelocityNetworkUtilities shut down cleanly.");
    }

    /**
     * The old default, "plugins/VelocityNetworkUtilities/data.db", was resolved inside this
     * plugin's folder and so ended up nested a folder too deep. The default is now "data.db". A
     * database still at the old place is moved to the configured one before it is opened, so
     * nobody starts over with an empty database. It is never moved over an existing file.
     */
    private void moveLegacySqliteFile(YamlConfig config) {
        if (!"SQLITE".equalsIgnoreCase(config.getString("database.type", "").strip())) {
            return;
        }
        Path target = dataDirectory.resolve(config.getString("database.sqlite.file", "data.db"))
                .toAbsolutePath().normalize();
        Path legacy = dataDirectory.resolve("plugins/VelocityNetworkUtilities/data.db").toAbsolutePath().normalize();
        if (target.equals(legacy) || Files.exists(target) || !Files.exists(legacy)) {
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            // SQLite may keep its journal beside the file; it belongs with the database.
            for (String suffix : new String[] {"", "-wal", "-shm", "-journal"}) {
                Path from = legacy.resolveSibling(legacy.getFileName() + suffix);
                if (Files.exists(from)) {
                    Files.move(from, target.resolveSibling(target.getFileName() + suffix));
                }
            }
            logger.info("Moved the SQLite database from {} to {}.", legacy, target);
        } catch (IOException e) {
            logger.error("Could not move the SQLite database from {} to {}. Move it by hand, or set "
                    + "database.sqlite.file back to plugins/VelocityNetworkUtilities/data.db.", legacy, target, e);
        }
    }

    public VelocityNetworkAPI networkAPI() {
        return networkAPI;
    }
}
