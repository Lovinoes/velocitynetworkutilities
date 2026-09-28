package de.lovinoes.velocitynetworkmoderation.punishment;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads durations the way a moderator types them: 7d, 30m, 1d12h, perm.
 *
 * Deliberately strict. A command like "/ban Notch griefing" has to be able to tell that
 * "griefing" is a reason and not a botched duration, and it does that by asking this parser and
 * getting nothing back. So anything that is not entirely a duration is rejected outright,
 * rather than parsing a leading number and discarding the rest.
 */
public final class DurationParser {

    /** A run of number+unit pairs and nothing else, so "7d" matches but "7dgrief" does not. */
    private static final Pattern DURATION = Pattern.compile("(?:(\\d+)(mo|[smhdwy]))+");
    private static final Pattern PART = Pattern.compile("(\\d+)(mo|[smhdwy])");

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final long WEEK = 7 * DAY;
    /** Calendar months vary; 30 days is the convention moderators expect from "1mo". */
    private static final long MONTH = 30 * DAY;
    private static final long YEAR = 365 * DAY;

    private DurationParser() {
    }

    /**
     * @return the duration in milliseconds, empty if this is not a duration at all, or
     *         {@link Punishment#PERMANENT} for one of the permanent words.
     */
    public static OptionalLong parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OptionalLong.empty();
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (isPermanent(text)) {
            return OptionalLong.of(Punishment.PERMANENT);
        }
        if (!DURATION.matcher(text).matches()) {
            return OptionalLong.empty();
        }

        long total = 0;
        Matcher parts = PART.matcher(text);
        while (parts.find()) {
            long amount;
            try {
                amount = Long.parseLong(parts.group(1));
            } catch (NumberFormatException e) {
                // A number too large for a long. Treating it as "not a duration" is wrong here,
                // since it plainly was meant as one, so it becomes permanent instead of
                // silently turning into part of the reason.
                return OptionalLong.of(Punishment.PERMANENT);
            }
            long unit = unitMillis(parts.group(2));
            // Saturate rather than wrap. A wrapped total would land in the past and expire the
            // punishment immediately, which is the exact opposite of what was asked for.
            if (amount > (Long.MAX_VALUE - total) / unit) {
                return OptionalLong.of(Punishment.PERMANENT);
            }
            total += amount * unit;
        }
        // "0d" is not a duration anyone means; treat it as no duration at all so it falls
        // through to being part of the reason rather than expiring instantly.
        return total == 0 ? OptionalLong.empty() : OptionalLong.of(total);
    }

    public static boolean isPermanent(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.equals("perm") || lower.equals("permanent") || lower.equals("forever");
    }

    private static long unitMillis(String unit) {
        return switch (unit) {
            case "s" -> SECOND;
            case "m" -> MINUTE;
            case "h" -> HOUR;
            case "d" -> DAY;
            case "w" -> WEEK;
            case "mo" -> MONTH;
            case "y" -> YEAR;
            // Unreachable: the pattern only matches the units above.
            default -> throw new IllegalStateException("Unhandled duration unit: " + unit);
        };
    }

    /** Renders a remaining duration for display, largest unit first, e.g. "2d 3h 15m". */
    public static String describe(long millis) {
        if (millis <= 0) {
            return "0s";
        }
        StringBuilder text = new StringBuilder();
        millis = appendUnit(text, millis, DAY, "d");
        millis = appendUnit(text, millis, HOUR, "h");
        millis = appendUnit(text, millis, MINUTE, "m");
        appendUnit(text, millis, SECOND, "s");
        return text.isEmpty() ? "0s" : text.toString().trim();
    }

    private static long appendUnit(StringBuilder text, long millis, long unit, String suffix) {
        long amount = millis / unit;
        if (amount > 0) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(amount).append(suffix);
        }
        return millis % unit;
    }
}
