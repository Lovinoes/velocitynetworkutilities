package de.lovinoes.velocitynetworkmoderation.filter;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Finds listed words in a message, however they are dressed up, and says exactly where they are
 * in the original text so they can be censored in place.
 *
 * <h2>What an entry catches</h2>
 * Every entry is matched without regard to case or accents, through common letter swaps
 * ({@code 4 @} for a, {@code 3} for e, {@code 1 !} for i, {@code 0} for o, {@code 5 $} for s,
 * {@code 7 +} for t, and look-alike Cyrillic letters), through stretched letters
 * ({@code fuuuuck}), through punctuation or colour codes inside the word ({@code f.u.c.k},
 * {@code &cfuck}), invisible characters, and letters typed with spaces between them
 * ({@code f u c k}).
 *
 * <h2>Where</h2>
 * By default an entry must be a whole word, so "ass" never fires on "class" or "passage". That
 * is what keeps a filter from becoming the joke it usually is. A {@code *} at the end lets the
 * word continue ({@code wank*} catches "wanker"), at the start lets something come before it,
 * and at both ends matches it anywhere at all ({@code *fuck*}, for stems with no innocent use).
 * Words in the allowed list are never flagged, for the rare real word a wildcard would catch,
 * such as "Scunthorpe".
 *
 * An entry starting with {@code regex:} is a Java regular expression, run as it is against the
 * lower-cased message with punctuation turned into spaces. It gets none of the help above.
 *
 * <h2>Safety</h2>
 * Built-in entries compile to patterns that cannot backtrack badly: neighbouring letters never
 * share a look-alike, so every repetition can be possessive. A regex entry is someone's own
 * pattern and could be slow, so it runs against a clock and is dropped, with a warning, the
 * first time it takes too long.
 *
 * Instances are immutable apart from that one-way drop and safe to share between threads.
 */
public final class WordFilter {

    public enum Action { CENSOR, BLOCK }

    /** A named group of words and what happens when one of them is used. */
    public record Category(String name, Action action, boolean counts, List<String> words) {
    }

    /** One listed word found in a message, as a range of the original text. */
    public record Hit(Category category, String entry, int start, int end) {
    }

    /** Everything found in a message. */
    public record Result(List<Hit> hits) {

        public boolean isClean() {
            return hits.isEmpty();
        }

        /** Whether any category that was hit blocks rather than censors. */
        public boolean blocks() {
            return hits.stream().anyMatch(hit -> hit.category().action() == Action.BLOCK);
        }

        /** Whether any category that was hit counts towards automatic punishments. */
        public boolean counts() {
            return hits.stream().anyMatch(hit -> hit.category().counts());
        }

        /** The names of the categories hit, each once, in the order first hit. */
        public List<String> categories() {
            Set<String> names = new LinkedHashSet<>();
            hits.forEach(hit -> names.add(hit.category().name()));
            return List.copyOf(names);
        }
    }

    /**
     * @param wholeWord for a wildcard entry: widen each hit to the whole word it sits in, so
     *                  "wank*" stars out all of "wanker" and "*fuck*" all of "motherfucker",
     *                  rather than leaving "****er" for everyone to read
     */
    private record Compiled(Category category, String entry, Pattern pattern, boolean userRegex, boolean wholeWord) {
    }

    /** How long one user regex may run against one message before it is taken out. */
    private static final long REGEX_BUDGET_NANOS = 50_000_000L;

    /** Characters kept inside words because people use them in place of letters. */
    private static final String LEET_SYMBOLS = "@$!+";

    private static final Map<Character, String> LOOK_ALIKES = Map.ofEntries(
            Map.entry('a', "a4@"), Map.entry('b', "b8"), Map.entry('e', "e3"), Map.entry('g', "g9"),
            Map.entry('i', "i1!"), Map.entry('o', "o0"), Map.entry('s', "s5$"), Map.entry('t', "t7+"),
            Map.entry('z', "z2"));

    /** Cyrillic and Greek letters that look exactly like Latin ones, mapped to those. */
    private static final Map<Character, Character> HOMOGLYPHS = Map.ofEntries(
            Map.entry('а', 'a'), Map.entry('е', 'e'), Map.entry('о', 'o'), Map.entry('р', 'p'),
            Map.entry('с', 'c'), Map.entry('у', 'y'), Map.entry('х', 'x'), Map.entry('і', 'i'),
            Map.entry('ј', 'j'), Map.entry('ѕ', 's'), Map.entry('к', 'k'), Map.entry('м', 'm'),
            Map.entry('н', 'h'), Map.entry('т', 't'), Map.entry('в', 'b'),
            Map.entry('ο', 'o'), Map.entry('α', 'a'), Map.entry('ε', 'e'), Map.entry('ι', 'i'),
            Map.entry('κ', 'k'), Map.entry('ν', 'v'), Map.entry('ρ', 'p'), Map.entry('τ', 't'),
            Map.entry('υ', 'u'), Map.entry('χ', 'x'));

    /** Legacy colour and format codes: &c, §l, &#a1b2c3. */
    private static final Pattern LEGACY_CODE = Pattern.compile("[&§](?:#[0-9a-fA-F]{6}|[0-9a-fk-orxA-FK-ORX])");

    private final List<Compiled> entries;
    private final Set<String> allowed;
    private final System.Logger logger;
    private final Set<String> droppedRegexes = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private WordFilter(List<Compiled> entries, Set<String> allowed, System.Logger logger) {
        this.entries = entries;
        this.allowed = allowed;
        this.logger = logger;
    }

    /**
     * Compiles every entry once. A malformed regex entry, or one that is empty once cleaned, is
     * skipped with a warning naming it, rather than taking the whole filter down.
     */
    public static WordFilter compile(Collection<Category> categories, Collection<String> allowedWords,
                                     System.Logger logger) {
        List<Compiled> compiled = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Category category : categories) {
            for (String raw : category.words()) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String entry = raw.strip();
                // The same entry listed twice, even in two categories, is checked once, and the
                // first category to list it decides what it does.
                if (!seen.add(entry.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                try {
                    Compiled result = compileEntry(category, entry);
                    if (result != null) {
                        compiled.add(result);
                    } else {
                        logger.log(System.Logger.Level.WARNING,
                                "Filter entry ''{0}'' in {1} has no letters or digits once cleaned; skipping it.",
                                entry, category.name());
                    }
                } catch (PatternSyntaxException e) {
                    logger.log(System.Logger.Level.WARNING,
                            "Filter entry ''{0}'' in {1} is not a valid regular expression; skipping it. {2}",
                            entry, category.name(), e.getDescription());
                }
            }
        }

        Set<String> allowedSet = new HashSet<>();
        for (String word : allowedWords) {
            if (word != null && !word.isBlank()) {
                allowedSet.add(lettersOnly(fold(word)));
            }
        }
        return new WordFilter(List.copyOf(compiled), Set.copyOf(allowedSet), logger);
    }

    public int size() {
        return entries.size();
    }

    private static Compiled compileEntry(Category category, String entry) {
        if (entry.regionMatches(true, 0, "regex:", 0, 6)) {
            String regex = entry.substring(6);
            if (regex.isBlank()) {
                return null;
            }
            return new Compiled(category, entry, Pattern.compile(regex), true, false);
        }

        boolean openStart = entry.startsWith("*");
        boolean openEnd = entry.endsWith("*") && entry.length() > 1;
        String body = entry.substring(openStart ? 1 : 0, entry.length() - (openEnd ? 1 : 0));

        // The entry goes through the same folding as a message, so an entry written with an
        // accent or a capital matches exactly what the same word in a message becomes.
        String folded = fold(body);
        StringBuilder cleaned = new StringBuilder();
        folded.codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp)) {
                cleaned.appendCodePoint(cp);
            } else if (Character.isWhitespace(cp) && !cleaned.isEmpty() && cleaned.charAt(cleaned.length() - 1) != ' ') {
                cleaned.append(' ');
            }
        });
        String word = cleaned.toString().strip();
        if (word.isEmpty()) {
            return null;
        }

        StringBuilder regex = new StringBuilder();
        if (!openStart) {
            regex.append("(?<![\\p{L}\\p{N}])");
        }
        int i = 0;
        while (i < word.length()) {
            char c = word.charAt(i);
            int run = 1;
            while (i + run < word.length() && word.charAt(i + run) == c) {
                run++;
            }
            if (c == ' ') {
                // Between the words of a phrase: a space or nothing, since "killyourself" and
                // "kill.yourself" (which becomes "killyourself") are the same phrase.
                regex.append(" ?+");
            } else {
                regex.append(characterClass(c));
                // At least as many as the entry has, and any more: "fuuuck" is still "fuck",
                // but "as" is not "ass". Possessive, since neighbouring classes never overlap.
                regex.append(run == 1 ? "++" : "{" + run + ",}+");
            }
            i += run;
        }
        if (!openEnd) {
            regex.append("(?![\\p{L}\\p{N}])");
        }
        return new Compiled(category, entry, Pattern.compile(regex.toString()), false, openStart || openEnd);
    }

    private static String characterClass(char c) {
        String alikes = LOOK_ALIKES.get(c);
        if (alikes == null) {
            return Pattern.quote(String.valueOf(c));
        }
        StringBuilder cls = new StringBuilder("[");
        for (char alike : alikes.toCharArray()) {
            // Inside a class only these need escaping; quoting keeps "$" and "+" literal.
            if (alike == '\\' || alike == ']' || alike == '[' || alike == '^' || alike == '-' || alike == '&') {
                cls.append('\\');
            }
            cls.append(alike);
        }
        return cls.append(']').toString();
    }

    /**
     * Lower case, no accents, look-alike letters replaced. Used for entries and allowed words;
     * messages go through {@link #views} instead, which does the same while keeping track of
     * where each character came from.
     */
    static String fold(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> appendFolded(out, cp));
        return out.toString();
    }

    private static void appendFolded(StringBuilder out, int codePoint) {
        String decomposed = Normalizer.normalize(new String(Character.toChars(codePoint)), Normalizer.Form.NFKD);
        String lower = decomposed.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                continue;
            }
            Character latin = HOMOGLYPHS.get(c);
            out.append(latin != null ? latin : c);
        }
    }

    private static String lettersOnly(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().filter(Character::isLetterOrDigit).forEach(out::appendCodePoint);
        return out.toString();
    }

    /**
     * A normalised copy of the message, and for each of its characters the range of the original
     * it came from, so a match can be mapped back and censored exactly where it was typed.
     */
    record View(String text, int[] from, int[] to) {
    }

    /**
     * Three readings of the same message, each catching what the others miss:
     * <ul>
     *   <li>punctuation as spaces: "<red>fuck</red>" is "red fuck red"</li>
     *   <li>punctuation removed: "f.u.c.k" is "fuck"</li>
     *   <li>that, with single letters typed apart joined up again: "f u c k" is "fuck"</li>
     * </ul>
     * Colour codes and invisible characters are removed from all three.
     */
    static List<View> views(String message) {
        boolean[] code = new boolean[message.length()];
        Matcher codes = LEGACY_CODE.matcher(message);
        while (codes.find()) {
            for (int i = codes.start(); i < codes.end(); i++) {
                code[i] = true;
            }
        }

        Builder spaced = new Builder(message.length());
        Builder joined = new Builder(message.length());
        StringBuilder folded = new StringBuilder(4);

        for (int i = 0; i < message.length(); ) {
            int cp = message.codePointAt(i);
            int next = i + Character.charCount(cp);
            if (code[i] || Character.getType(cp) == Character.FORMAT) {
                i = next;
                continue;
            }
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                spaced.space(i, next);
                joined.space(i, next);
                i = next;
                continue;
            }
            folded.setLength(0);
            appendFolded(folded, cp);
            for (int k = 0; k < folded.length(); k++) {
                char c = folded.charAt(k);
                if (Character.isLetterOrDigit(c) || LEET_SYMBOLS.indexOf(c) >= 0) {
                    spaced.add(c, i, next);
                    joined.add(c, i, next);
                } else if (Character.isWhitespace(c)) {
                    spaced.space(i, next);
                    joined.space(i, next);
                } else {
                    // Punctuation: a word break in one reading, nothing at all in the other.
                    spaced.space(i, next);
                }
            }
            i = next;
        }

        View joinedView = joined.build();
        return List.of(spaced.build(), joinedView, joinSingleLetters(joinedView));
    }

    /** Collects a normalised string along with where each character came from. */
    private static final class Builder {
        private final StringBuilder text;
        private final List<int[]> ranges;

        Builder(int capacity) {
            text = new StringBuilder(capacity);
            ranges = new ArrayList<>(capacity);
        }

        void add(char c, int from, int to) {
            text.append(c);
            ranges.add(new int[] {from, to});
        }

        /** One space for any run of breaks, and none at the start. */
        void space(int from, int to) {
            if (text.isEmpty() || text.charAt(text.length() - 1) == ' ') {
                return;
            }
            add(' ', from, to);
        }

        View build() {
            int length = text.length();
            if (length > 0 && text.charAt(length - 1) == ' ') {
                text.setLength(length - 1);
                ranges.remove(length - 1);
            }
            int[] from = new int[ranges.size()];
            int[] to = new int[ranges.size()];
            for (int k = 0; k < ranges.size(); k++) {
                from[k] = ranges.get(k)[0];
                to[k] = ranges.get(k)[1];
            }
            return new View(text.toString(), from, to);
        }
    }

    /**
     * "f u c k" becomes "fuck": any run of three or more words that are each a single character
     * is joined. Three, so that ordinary "a" and "I" between longer words are left alone.
     */
    static View joinSingleLetters(View view) {
        String[] words = view.text().split(" ", -1);
        if (words.length < 3) {
            return view;
        }
        StringBuilder text = new StringBuilder(view.text().length());
        List<Integer> kept = new ArrayList<>(view.text().length());
        int position = 0;
        for (int w = 0; w < words.length; w++) {
            String word = words[w];
            for (int k = 0; k < word.length(); k++) {
                text.append(word.charAt(k));
                kept.add(position + k);
            }
            position += word.length();
            if (w == words.length - 1) {
                break;
            }
            boolean joinWithNext = word.length() == 1 && words[w + 1].length() == 1
                    && singleRunLength(words, w) >= 3;
            if (!joinWithNext) {
                text.append(' ');
                kept.add(position);
            }
            position++;
        }
        int[] from = new int[kept.size()];
        int[] to = new int[kept.size()];
        for (int k = 0; k < kept.size(); k++) {
            from[k] = view.from()[kept.get(k)];
            to[k] = view.to()[kept.get(k)];
        }
        return new View(text.toString(), from, to);
    }

    /** How long the run of single-character words containing word {@code w} is. */
    private static int singleRunLength(String[] words, int w) {
        int start = w;
        while (start > 0 && words[start - 1].length() == 1) {
            start--;
        }
        int end = w;
        while (end < words.length - 1 && words[end + 1].length() == 1) {
            end++;
        }
        return end - start + 1;
    }

    /** Every listed word in the message. */
    public Result check(String message) {
        if (message == null || message.isEmpty() || entries.isEmpty()) {
            return new Result(List.of());
        }
        List<View> views = views(message);
        List<Hit> hits = new ArrayList<>();
        Set<String> found = new HashSet<>();

        for (Compiled compiled : entries) {
            if (compiled.userRegex() && droppedRegexes.contains(compiled.entry())) {
                continue;
            }
            // A user regex reads only the first view, as its documentation promises.
            List<View> targets = compiled.userRegex() ? views.subList(0, 1) : views;
            for (View view : targets) {
                try {
                    CharSequence input = compiled.userRegex()
                            ? new Deadline(view.text(), System.nanoTime() + REGEX_BUDGET_NANOS)
                            : view.text();
                    Matcher matcher = compiled.pattern().matcher(input);
                    while (matcher.find()) {
                        if (matcher.end() == matcher.start()) {
                            // A regex that can match nothing would otherwise flag every message.
                            continue;
                        }
                        if (isAllowed(view.text(), matcher.start(), matcher.end())) {
                            continue;
                        }
                        int from = matcher.start();
                        int to = matcher.end();
                        if (compiled.wholeWord()) {
                            // Only over letters and digits, so "fucker!" keeps its "!", and only over
                            // characters that were typed side by side. Where the reading removed
                            // something (punctuation, a colour code), that is where the typed word
                            // ended, so "lol,fuck" stars out "fuck" alone.
                            String text = view.text();
                            while (from > 0 && Character.isLetterOrDigit(text.charAt(from - 1))
                                    && view.to()[from - 1] == view.from()[from]) {
                                from--;
                            }
                            while (to < text.length() && Character.isLetterOrDigit(text.charAt(to))
                                    && view.to()[to - 1] == view.from()[to]) {
                                to++;
                            }
                        }
                        int start = view.from()[from];
                        int end = view.to()[to - 1];
                        if (found.add(compiled.entry() + ":" + start + ":" + end)) {
                            hits.add(new Hit(compiled.category(), compiled.entry(), start, end));
                        }
                    }
                } catch (Deadline.Exceeded e) {
                    if (droppedRegexes.add(compiled.entry())) {
                        logger.log(System.Logger.Level.WARNING,
                                "Filter entry ''{0}'' took too long on a message and is switched off until "
                                        + "the next restart. Simplify the regular expression.", compiled.entry());
                    }
                    break;
                }
            }
        }
        return new Result(List.copyOf(hits));
    }

    /** Whether the whole word a match sits in is on the allowed list. */
    private boolean isAllowed(String text, int start, int end) {
        if (allowed.isEmpty()) {
            return false;
        }
        int from = start;
        while (from > 0 && text.charAt(from - 1) != ' ') {
            from--;
        }
        int to = end;
        while (to < text.length() && text.charAt(to) != ' ') {
            to++;
        }
        return allowed.contains(lettersOnly(text.substring(from, to)));
    }

    /**
     * The message with every hit replaced by the mask, one mask per character typed, spaces kept.
     * Overlapping hits are simply masked once.
     */
    public static String censor(String message, List<Hit> hits, String mask) {
        if (hits.isEmpty()) {
            return message;
        }
        boolean[] masked = new boolean[message.length()];
        for (Hit hit : hits) {
            for (int i = Math.max(0, hit.start()); i < Math.min(message.length(), hit.end()); i++) {
                masked[i] = true;
            }
        }
        StringBuilder out = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); ) {
            int cp = message.codePointAt(i);
            int next = i + Character.charCount(cp);
            if (masked[i] && !Character.isWhitespace(cp)) {
                out.append(mask);
            } else {
                out.appendCodePoint(cp);
            }
            i = next;
        }
        return out.toString();
    }

    /**
     * A string that gives up once its time is spent. The regex engine reads its input only
     * through charAt, so a pattern stuck backtracking hits the clock and stops, instead of
     * holding a chat thread for as long as it likes.
     */
    private static final class Deadline implements CharSequence {

        static final class Exceeded extends RuntimeException {
            private static final long serialVersionUID = 1L;

            Exceeded() {
                super(null, null, false, false);
            }
        }

        private final String text;
        private final long deadline;
        private int reads;

        Deadline(String text, long deadline) {
            this.text = text;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0x3FF) == 0 && System.nanoTime() > deadline) {
                throw new Exceeded();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Deadline(text.substring(start, end), deadline);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
