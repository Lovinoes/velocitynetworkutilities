package de.lovinoes.networkutilitiescommon.config;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Date patterns read from config, where a typo must not stop a plugin loading. */
public final class Dates {

    private static final System.Logger LOGGER = System.getLogger(Dates.class.getName());

    private Dates() {
    }

    /**
     * DateTimeFormatter.ofPattern throws on an invalid pattern, and doing that while a plugin
     * is starting takes the plugin down over one mistyped config line. The fallback keeps it
     * running with dates that are merely not the ones asked for.
     */
    public static DateTimeFormatter formatter(String pattern, String fallbackPattern, Locale locale) {
        try {
            return DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault());
        } catch (IllegalArgumentException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "date-format '" + pattern + "' is not a valid pattern, using '" + fallbackPattern
                            + "' instead: " + e.getMessage());
            return DateTimeFormatter.ofPattern(fallbackPattern, locale).withZone(ZoneId.systemDefault());
        }
    }
}
