package de.lovinoes.velocitynetworkmoderation.punishment;

import java.util.UUID;

/**
 * One recorded punishment.
 *
 * Nothing is ever deleted: lifting a ban clears {@link #active()} and records who lifted it and
 * when, so a player's history stays complete and a lifted punishment can still be shown.
 *
 * @param victimUuid    null for an IP ban that was placed on a bare address
 * @param victimAddress the banned address for an IP ban, or the address the player was on when
 *                      punished, kept so an IP ban can be traced back to what prompted it
 * @param operatorUuid  null when the console issued it
 * @param expiresAt     epoch millis, or {@link #PERMANENT} for one that never expires
 * @param revokedAt     epoch millis, or 0 if this was never lifted
 */
public record Punishment(
        long id,
        PunishmentType type,
        UUID victimUuid,
        String victimName,
        String victimAddress,
        UUID operatorUuid,
        String operatorName,
        String reason,
        long createdAt,
        long expiresAt,
        boolean active,
        String revokedBy,
        long revokedAt
) {

    public static final long PERMANENT = 0L;

    public boolean isPermanent() {
        return expiresAt == PERMANENT;
    }

    /**
     * Expiry is decided here from the clock rather than by a background task that flips a
     * column. A task can be late, can miss a tick, or can simply not have run yet on a proxy
     * that just started, and any of those would keep a player banned past their time.
     */
    public boolean isExpired(long now) {
        return !isPermanent() && now >= expiresAt;
    }

    /** True only while this is still in force: not lifted, and not past its expiry. */
    public boolean isCurrentlyInForce(long now) {
        return active && !isExpired(now);
    }

    public long remainingMillis(long now) {
        return isPermanent() ? Long.MAX_VALUE : Math.max(0, expiresAt - now);
    }
}
