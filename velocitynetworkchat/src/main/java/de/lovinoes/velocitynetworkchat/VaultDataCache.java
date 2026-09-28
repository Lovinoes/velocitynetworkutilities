package de.lovinoes.velocitynetworkchat;

import de.lovinoes.networkutilitiescommon.vault.VaultCacheDao;
import de.lovinoes.networkutilitiescommon.vault.VaultCacheRecord;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-blocking read-through cache over network_vault_cache. Chat formatting never waits on a
 * database round trip: a lookup miss returns the fallback immediately and refreshes
 * asynchronously so the next message has it.
 */
public final class VaultDataCache {

    private final VaultCacheDao vaultCacheDao;
    private final String fallback;
    private final Map<UUID, VaultCacheRecord> records = new ConcurrentHashMap<>();

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

    public void invalidate(UUID uuid) {
        records.remove(uuid);
    }

    private VaultCacheRecord lookup(UUID uuid) {
        refresh(uuid);
        return records.get(uuid);
    }

    private void refresh(UUID uuid) {
        vaultCacheDao.find(uuid).thenAccept(record -> record.ifPresent(value -> records.put(uuid, value)));
    }
}
