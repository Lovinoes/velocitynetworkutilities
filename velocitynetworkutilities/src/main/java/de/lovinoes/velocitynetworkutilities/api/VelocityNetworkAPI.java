package de.lovinoes.velocitynetworkutilities.api;

import de.lovinoes.networkutilitiescommon.database.DatabaseManager;
import de.lovinoes.networkutilitiescommon.messaging.NetworkMessagingProvider;
import de.lovinoes.networkutilitiescommon.vanish.VanishPermissions;
import de.lovinoes.networkutilitiescommon.vault.VaultCacheDao;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-module entry point shared by every plugin in the network utilities suite.
 */
public final class VelocityNetworkAPI {

    // Volatile: set once on startup, then read from every thread the other plugins run on.
    private static volatile VelocityNetworkAPI instance;

    private final DatabaseManager databaseManager;
    private final NetworkMessagingProvider messagingProvider;
    private final VaultCacheDao vaultCacheDao;
    private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();
    private volatile String seeVanishedPermission = VanishPermissions.SEE_VANISHED;

    public VelocityNetworkAPI(DatabaseManager databaseManager, NetworkMessagingProvider messagingProvider, VaultCacheDao vaultCacheDao) {
        this.databaseManager = databaseManager;
        this.messagingProvider = messagingProvider;
        this.vaultCacheDao = vaultCacheDao;
        instance = this;
    }

    public static VelocityNetworkAPI get() {
        if (instance == null) {
            throw new IllegalStateException("VelocityNetworkUtilities has not initialized the network API yet");
        }
        return instance;
    }

    public DatabaseManager database() {
        return databaseManager;
    }

    public NetworkMessagingProvider messaging() {
        return messagingProvider;
    }

    public VaultCacheDao vaultCache() {
        return vaultCacheDao;
    }

    /**
     * Who may see vanished players, as configured in VelocityNetworkVanish. Every plugin reads it
     * from here so that changing it there changes it everywhere; without VelocityNetworkVanish
     * it stays the default.
     */
    public String seeVanishedPermission() {
        return seeVanishedPermission;
    }

    public void setSeeVanishedPermission(String permission) {
        if (permission != null && !permission.isBlank()) {
            this.seeVanishedPermission = permission.strip();
        }
    }

    public boolean isVanished(UUID playerId) {
        return vanishedPlayers.contains(playerId);
    }

    public void setVanished(UUID playerId, boolean vanished) {
        if (vanished) {
            vanishedPlayers.add(playerId);
        } else {
            vanishedPlayers.remove(playerId);
        }
    }
}
