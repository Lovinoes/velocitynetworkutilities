package de.lovinoes.playeruuidcachevelocity;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PlayerCacheAPI {

    // Volatile: set once on startup, then read from every thread the other plugins run on.
    private static volatile PlayerCacheAPI instance;

    private final PlayerCacheDao dao;
    private final MojangLookupService mojangLookupService;

    PlayerCacheAPI(PlayerCacheDao dao, MojangLookupService mojangLookupService) {
        this.dao = dao;
        this.mojangLookupService = mojangLookupService;
        instance = this;
    }

    public static PlayerCacheAPI get() {
        if (instance == null) {
            throw new IllegalStateException("PlayerUUIDCacheVelocity has not initialized yet");
        }
        return instance;
    }

    public CompletableFuture<Optional<PlayerRecord>> findByUuid(UUID uuid) {
        return dao.findByUuid(uuid);
    }

    public CompletableFuture<Optional<PlayerRecord>> findByUsername(String username) {
        return dao.findByUsername(username).thenCompose(record -> {
            if (record.isPresent()) {
                return CompletableFuture.completedFuture(record);
            }
            return mojangLookupService.lookupUuid(username)
                    .thenCompose(uuid -> uuid.map(dao::findByUuid).orElse(CompletableFuture.completedFuture(Optional.empty())));
        });
    }

    public CompletableFuture<Void> recordLogin(UUID uuid, String username) {
        return dao.recordLogin(uuid, username, System.currentTimeMillis());
    }

    public CompletableFuture<Void> recordLogout(UUID uuid) {
        return dao.recordLogout(uuid, System.currentTimeMillis());
    }
}
