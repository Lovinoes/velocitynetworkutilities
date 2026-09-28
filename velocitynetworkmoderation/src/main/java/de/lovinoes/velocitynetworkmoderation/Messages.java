package de.lovinoes.velocitynetworkmoderation;

import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import de.lovinoes.networkutilitiescommon.config.Dates;
import de.lovinoes.networkutilitiescommon.config.YamlConfig;
import de.lovinoes.velocitynetworkmoderation.punishment.DurationParser;
import de.lovinoes.velocitynetworkmoderation.punishment.Punishment;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Every piece of text this plugin shows comes from config, including the multi-line screens a
 * banned or kicked player sees on disconnect.
 *
 * A missing key renders as the key's own path rather than throwing or showing nothing. An
 * operator who mistypes one then sees exactly which line to fix, instead of a blank kick screen
 * that gives no clue what went wrong.
 */
public final class Messages {

    private final YamlConfig language;
    private final DateTimeFormatter dateFormatter;
    private final String permanentWord;
    private final String consoleName;

    public Messages(YamlConfig language, String datePattern, String permanentWord, String consoleName) {
        this.language = language;
        this.dateFormatter = Dates.formatter(datePattern, "dd.MM.yyyy HH:mm", Locale.GERMANY);
        this.permanentWord = permanentWord;
        this.consoleName = consoleName;
    }

    /** A single line from config. */
    public Component line(String path, TagResolver... resolvers) {
        String raw = language.getString(path, "");
        return ChatColorParser.parse(raw.isEmpty() ? "<red>" + path : raw, resolvers);
    }

    public boolean isBlank(String path) {
        return language.getString(path, "").isBlank();
    }

    /**
     * A config value used as plain text rather than as something to display, such as the reason
     * stored on a punishment when the moderator typed none. It goes into the database and is
     * rendered later, so it must not be parsed here.
     */
    public String plain(String path, String fallback) {
        String raw = language.getString(path, fallback);
        return raw.isBlank() ? fallback : raw;
    }

    /**
     * A block of lines from config, joined with newlines. Used for disconnect screens, which
     * are one component containing line breaks rather than several messages.
     *
     * Accepts either a list or a single string, because writing one line as a plain value is
     * the obvious thing to do and failing on it would be needless pedantry.
     */
    public Component screen(String path, TagResolver... resolvers) {
        List<String> lines = language.getStringList(path);
        if (lines.isEmpty()) {
            return line(path, resolvers);
        }
        Component screen = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                screen = screen.append(Component.newline());
            }
            screen = screen.append(ChatColorParser.parse(lines.get(i), resolvers));
        }
        return screen;
    }

    /** The placeholders every punishment message can use. */
    public TagResolver[] placeholders(Punishment punishment, long now) {
        return new TagResolver[] {
                Placeholder.unparsed("id", String.valueOf(punishment.id())),
                Placeholder.unparsed("type", punishment.type().lowerName()),
                Placeholder.unparsed("player", nullSafe(punishment.victimName())),
                Placeholder.unparsed("address", nullSafe(punishment.victimAddress())),
                Placeholder.unparsed("operator", nullSafe(punishment.operatorName())),
                Placeholder.unparsed("reason", nullSafe(punishment.reason())),
                Placeholder.unparsed("created", dateFormatter.format(Instant.ofEpochMilli(punishment.createdAt()))),
                Placeholder.unparsed("expires", punishment.isPermanent()
                        ? permanentWord
                        : dateFormatter.format(Instant.ofEpochMilli(punishment.expiresAt()))),
                Placeholder.unparsed("duration", punishment.isPermanent()
                        ? permanentWord
                        : DurationParser.describe(punishment.remainingMillis(now))),
                Placeholder.unparsed("duration_phrase", durationPhrase(punishment, now)),
                Placeholder.unparsed("revoked_by", nullSafe(punishment.revokedBy())),
                Placeholder.unparsed("revoked_at", punishment.revokedAt() > 0
                        ? dateFormatter.format(Instant.ofEpochMilli(punishment.revokedAt()))
                        : ""),
                Placeholder.unparsed("length", punishment.isPermanent()
                        ? permanentWord
                        : DurationParser.describe(punishment.expiresAt() - punishment.createdAt())),
                // Components, not text: these words carry their own colours in the language file.
                Placeholder.component("status", ChatColorParser.parse(statusWord(punishment, now))),
                Placeholder.component("type_name", ChatColorParser.parse(
                        language.getString("words.types." + punishment.type().lowerName(),
                                punishment.type().lowerName())))
        };
    }

    /**
     * A block of lines like {@link #screen}, or nothing when the key is missing, an empty list or
     * "". For optional parts, where showing the key's path would be wrong.
     */
    public Optional<Component> optionalScreen(String path, TagResolver... resolvers) {
        Object raw = language.get(path);
        boolean nothing = raw == null
                || (raw instanceof List<?> list && list.isEmpty())
                || (!(raw instanceof List<?>) && String.valueOf(raw).isBlank());
        return nothing ? Optional.empty() : Optional.of(screen(path, resolvers));
    }

    /**
     * The duration as a fragment of a sentence rather than a bare value.
     *
     * A broadcast reading "banned for 7d" has to become "banned permanently" rather than
     * "banned for permanent", and only the wording knows that, so both halves are config. The
     * result is inserted as plain text, so style it from the message around it.
     */
    private String durationPhrase(Punishment punishment, long now) {
        if (punishment.isPermanent()) {
            return language.getString("words.permanent-phrase", permanentWord);
        }
        return language.getString("words.duration-phrase", "for <duration>")
                .replace("<duration>", DurationParser.describe(punishment.remainingMillis(now)));
    }

    private String statusWord(Punishment punishment, long now) {
        String key = !punishment.active()
                ? "words.revoked"
                : punishment.isExpired(now) ? "words.expired" : "words.active";
        return language.getString(key, key);
    }

    public String consoleName() {
        return consoleName;
    }

    public String permanentWord() {
        return permanentWord;
    }

    public DateTimeFormatter dateFormatter() {
        return dateFormatter;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
