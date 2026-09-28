package de.lovinoes.velocitynetworkchat;

import com.velocitypowered.api.proxy.Player;
import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import de.lovinoes.networkutilitiescommon.config.YamlConfig;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Stops a player flooding chat: a minimum gap between their messages, and the same message not
 * again to the same place within a while. Covers channel chat and, when configured, private
 * messages, which share one timer so spam cannot simply move from one to the other.
 *
 * A message that is refused is not sent at all and is not remembered either, so hammering the
 * key does not push the end of the cooldown further out.
 */
public final class AntiSpam {

    public enum Verdict { ALLOWED, TOO_FAST, REPEATED }

    /** The last message that was let through: when, where to, and what it said once normalised. */
    private record Last(long at, String context, String text) {
    }

    private final long cooldownMillis;
    private final long repeatWindowMillis;
    private final boolean privateMessages;
    private final String bypassPermission;
    private final YamlConfig language;
    private final LongSupplier clock;

    private final Map<UUID, Last> last = new ConcurrentHashMap<>();

    public AntiSpam(long cooldownMillis, long repeatWindowMillis, boolean privateMessages, String bypassPermission,
                    YamlConfig language, LongSupplier clock) {
        this.cooldownMillis = Math.max(0, cooldownMillis);
        this.repeatWindowMillis = Math.max(0, repeatWindowMillis);
        this.privateMessages = privateMessages;
        this.bypassPermission = bypassPermission == null ? "" : bypassPermission.trim();
        this.language = language;
        this.clock = clock;
    }

    /** Whether a channel message may be sent. Tells the player why when it may not. */
    public boolean allowChat(Player sender, String channelKey, String message) {
        return allow(sender, "channel:" + channelKey, message);
    }

    /** Whether a private message may be sent. Tells the player why when it may not. */
    public boolean allowPrivate(Player sender, UUID target, String message) {
        if (!privateMessages) {
            return true;
        }
        return allow(sender, "private:" + target, message);
    }

    private boolean allow(Player sender, String context, String message) {
        if (cooldownMillis == 0 && repeatWindowMillis == 0) {
            return true;
        }
        if (!bypassPermission.isEmpty() && sender.hasPermission(bypassPermission)) {
            return true;
        }
        long now = clock.getAsLong();
        Verdict verdict = check(sender.getUniqueId(), context, message, now);
        if (verdict == Verdict.ALLOWED) {
            return true;
        }

        String template = language.getString(
                verdict == Verdict.TOO_FAST ? "anti-spam.too-fast" : "anti-spam.repeated", "");
        if (!template.isBlank()) {
            Last previous = last.get(sender.getUniqueId());
            long left = previous == null ? 0 : cooldownMillis - (now - previous.at());
            long seconds = Math.max(1, (left + 999) / 1000);
            sender.sendMessage(ChatColorParser.parse(template, Placeholder.unparsed("seconds", String.valueOf(seconds))));
        }
        return false;
    }

    /**
     * Decides and, when allowed, records in one atomic step, so two messages from the same player
     * arriving on two threads at once cannot both slip through against the same previous one.
     */
    public Verdict check(UUID player, String context, String message, long now) {
        String text = normalise(message);
        Verdict[] verdict = {Verdict.ALLOWED};
        last.compute(player, (id, previous) -> {
            if (previous != null) {
                long since = now - previous.at();
                if (cooldownMillis > 0 && since >= 0 && since < cooldownMillis) {
                    verdict[0] = Verdict.TOO_FAST;
                    return previous;
                }
                if (repeatWindowMillis > 0 && since >= 0 && since < repeatWindowMillis
                        && previous.context().equals(context) && previous.text().equals(text)) {
                    verdict[0] = Verdict.REPEATED;
                    return previous;
                }
            }
            return new Last(now, context, text);
        });
        return verdict[0];
    }

    /**
     * Case, spacing and punctuation do not make a message new: "hi", "HI" and "h i !" are the
     * same message. A message that is nothing but punctuation is compared as typed instead, or
     * "???" and "!!!" would count as repeats of each other.
     */
    static String normalise(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        StringBuilder letters = new StringBuilder(lower.length());
        lower.codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(letters::appendCodePoint);
        return letters.isEmpty() ? lower.strip() : letters.toString();
    }

    public void forget(UUID player) {
        last.remove(player);
    }
}
