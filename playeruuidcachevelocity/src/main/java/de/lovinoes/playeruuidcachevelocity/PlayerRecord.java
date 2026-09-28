package de.lovinoes.playeruuidcachevelocity;

import java.util.UUID;

public record PlayerRecord(UUID uuid, String username, long firstJoin, long lastLogin, long lastLogout) {
}
