package de.lovinoes.velocitynetworkchat;

import de.lovinoes.networkutilitiescommon.vault.VaultCacheDao;
import de.lovinoes.networkutilitiescommon.vault.VaultCacheRecord;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-blocking read-through cache over network_vault_cache. Chat formatting never waits on a
 * database round trip: a lookup returns what is cached, or the fallback, and refreshes in the
 * background when the cached value is older than {@link #REFRESH_AFTER_MS}.
 *
 * At most one query per player is ever in flight, and a player with no row is remembered as
 * having none, so a busy chat does not turn into a query per message.
 */
public final class VaultDataCache {

    private static final long REFRESH_AFTER_MS = 30_000;

    /** @param record null when the player has no row */
    private record Entry(VaultCacheRecord record, long fetchedAt) {
    }

    private final VaultCacheDao vaultCacheDao;
    private final String fallback;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();

    public VaultDataCache(VaultCacheDao vaultCacheDao, String fallback) {
        this.vaultCacheDao = vaultCacheDao;
        this.fallback = fallback;
    }

    public String getPrefix(UUID uuid) {
        VaultCacheRecord record = lookup(uuid);
        return record != null ? record.prefix() : fallback;
    }

    public String getSuffix(UUID uuid) {
        VaultCacheRecord record = lookup(uuid);
        return record != null ? record.suffix() : fallback;
    }

    public String getGroup(UUID uuid) {
        VaultCacheRecord record = lookup(uuid);
        return record != null ? record.primaryGroup() : fallback;
    }

    /** Loads a player ahead of their first message, so it already carries their prefix. */
    public void preload(UUID uuid) {
        refresh(uuid);
    }

    public void forget(UUID uuid) {
        entries.remove(uuid);
    }

    private VaultCacheRecord lookup(UUID uuid) {
        Entry entry = entries.get(uuid);
        if (entry == null || System.currentTimeMillis() - entry.fetchedAt() > REFRESH_AFTER_MS) {
            refresh(uuid);
        }
        return entry != null ? entry.record() : null;
    }

    private void refresh(UUID uuid) {
        if (!loading.add(uuid)) {
            return;
        }
        vaultCacheDao.find(uuid).whenComplete((record, failure) -> {
            if (failure == null) {
                entries.put(uuid, new Entry(record.orElse(null), System.currentTimeMillis()));
            }
            loading.remove(uuid);
        });
    }
}
