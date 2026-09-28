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

    /**
     * Like {@link #findByUsername}, but also finds a real Minecraft account that has never been
     * on this network, through Mojang. For acting on a player, such as punishing someone before
     * they return: that player has no history here, so the record carries only their UUID and
     * name, with every time left at 0.
     *
     * Not for showing information about a player, since a record like that has none to show.
     */
    public CompletableFuture<Optional<PlayerRecord>> resolve(String username) {
        return dao.findByUsername(username).thenCompose(record -> {
            if (record.isPresent()) {
                return CompletableFuture.completedFuture(record);
            }
            return mojangLookupService.lookupProfile(username).thenCompose(profile -> {
                if (profile.isEmpty()) {
                    return CompletableFuture.completedFuture(Optional.<PlayerRecord>empty());
                }
                // Known under another name, if they renamed since they were last here.
                return dao.findByUuid(profile.get().uuid()).thenApply(known -> known.or(() -> Optional.of(
                        new PlayerRecord(profile.get().uuid(), profile.get().name(), 0, 0, 0))));
            });
        });
    }

    public CompletableFuture<Void> recordLogin(UUID uuid, String username) {
        return dao.recordLogin(uuid, username, System.currentTimeMillis());
    }

    public CompletableFuture<Void> recordLogout(UUID uuid) {
        return dao.recordLogout(uuid, System.currentTimeMillis());
    }
}
