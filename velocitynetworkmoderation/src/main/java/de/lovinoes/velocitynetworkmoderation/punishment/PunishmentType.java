package de.lovinoes.velocitynetworkmoderation.punishment;

import java.util.Locale;
import java.util.Optional;

/**
 * The kinds of punishment this plugin records.
 *
 * Two properties decide how each one behaves everywhere else, so they live here rather than
 * being re-derived by every command and listener:
 *
 * <ul>
 *   <li>{@link #isRevocable()} separates a standing state that can be lifted (a ban, a mute)
 *       from a one-off act that is only ever history (a kick, a warning). Lifting a kick is
 *       meaningless, so /unban must refuse it rather than silently doing nothing.</li>
 *   <li>{@link #isTemporal()} says whether a duration means anything. A warning does not
 *       expire, so accepting "7d" on one would quietly discard it.</li>
 * </ul>
 */
public enum PunishmentType {

    BAN(true, true),
    IP_BAN(true, true),
    MUTE(true, true),
    KICK(false, false),
    WARN(false, false);

    private final boolean revocable;
    private final boolean temporal;

    PunishmentType(boolean revocable, boolean temporal) {
        this.revocable = revocable;
        this.temporal = temporal;
    }

    /** Whether this can be lifted, and therefore whether it has an active/inactive state. */
    public boolean isRevocable() {
        return revocable;
    }

    /** Whether a duration applies. A non-temporal punishment is a moment, not a period. */
    public boolean isTemporal() {
        return temporal;
    }

    /** Whether this one stops a player connecting. */
    public boolean blocksLogin() {
        return this == BAN || this == IP_BAN;
    }

    public String lowerName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<PunishmentType> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (PunishmentType type : values()) {
            if (type.name().equalsIgnoreCase(raw)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
