package de.lovinoes.networkutilitiescommon.vault;

import java.util.UUID;

public record VaultCacheRecord(UUID uuid, String username, String primaryGroup, String prefix, String suffix, long updatedAt) {
}
