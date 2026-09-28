package de.lovinoes.networkutilitiescommon.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders text that may mix legacy ampersand codes (&c, &l, &#ff0000) and native MiniMessage
 * tags (<red>, <bold>, <gradient:...>) in the same string.
 *
 * A previous version of this converted legacy codes into a Component first, then re-serialized
 * that Component back to MiniMessage source before a final deserialize pass. That round trip is
 * broken: MiniMessage's serializer escapes any literal '<' and '>' characters it finds in plain
 * text content (correctly, since a Component's text content isn't source code), so any native
 * <tag> the player typed came back escaped and was never parsed. Converting legacy codes with a
 * plain string substitution instead, before MiniMessage ever sees the text, avoids that: both
 * syntaxes end up as MiniMessage source in a single pass with nothing to escape.
 */
public final class ChatColorParser {

    private static final Pattern LEGACY_HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern LEGACY_CODE = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    private static final Map<Character, String> LEGACY_TAGS = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"),
            Map.entry('f', "white"), Map.entry('k', "obfuscated"), Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"), Map.entry('n', "underlined"), Map.entry('o', "italic"),
            Map.entry('r', "reset")
    );

    private ChatColorParser() {
    }

    /**
     * Parses text that may contain legacy codes, MiniMessage tags, or both, resolving any
     * placeholder tags supplied. Falls back to displaying the raw, unparsed text if the result
     * isn't valid MiniMessage syntax (for example, a player's message that happens to contain an
     * unmatched '<'), so malformed input can never crash message delivery.
     */
    public static Component parse(String rawMessage, TagResolver... resolvers) {
        String withNativeTags = convertLegacyToMiniMessage(rawMessage);
        try {
            return MiniMessage.miniMessage().deserialize(withNativeTags, resolvers);
        } catch (ParsingException e) {
            return Component.text(rawMessage);
        }
    }

    /**
     * Renders text as plain, unstyled content: neither legacy codes nor MiniMessage tags are
     * parsed. Used for chat input from players without color permission.
     */
    public static Component plain(String rawMessage) {
        return Component.text(rawMessage);
    }

    private static String convertLegacyToMiniMessage(String input) {
        StringBuilder afterHex = new StringBuilder();
        Matcher hexMatcher = LEGACY_HEX.matcher(input);
        while (hexMatcher.find()) {
            hexMatcher.appendReplacement(afterHex, Matcher.quoteReplacement("<#" + hexMatcher.group(1) + ">"));
        }
        hexMatcher.appendTail(afterHex);

        StringBuilder result = new StringBuilder();
        Matcher codeMatcher = LEGACY_CODE.matcher(afterHex.toString());
        while (codeMatcher.find()) {
            String tag = LEGACY_TAGS.get(Character.toLowerCase(codeMatcher.group(1).charAt(0)));
            codeMatcher.appendReplacement(result, Matcher.quoteReplacement("<" + tag + ">"));
        }
        codeMatcher.appendTail(result);
        return result.toString();
    }
}
