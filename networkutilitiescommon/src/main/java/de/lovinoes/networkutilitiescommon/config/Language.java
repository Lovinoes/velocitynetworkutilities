package de.lovinoes.networkutilitiescommon.config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Loads a plugin's messages from a language file, chosen by the {@code language} option in its
 * config.
 *
 * Every bundled language is written into {@code <plugin>/languages/} on start, not just the one
 * in use, so the choices are visible without unpacking a jar and switching is one line rather
 * than a hunt. Each is then kept up to date by the same migration that maintains config.yml, so
 * a new message appears in every language at once without losing wording you have changed.
 *
 * Messages live here rather than in config.yml so that translating is a matter of one file, and
 * config.yml is left holding only settings.
 */
public final class Language {

    /**
     * The code becomes part of both a file path and a resource path, so it is restricted to the
     * shape of a locale. Without this, a language of "../../secrets" would read and write
     * wherever it pointed.
     */
    private static final Pattern VALID_CODE = Pattern.compile("[a-z]{2,3}-[a-z]{2,4}(?:-[a-z0-9]{1,16})?");

    /** Lists the languages a plugin ships, so all of them can be written out. */
    private static final String INDEX_RESOURCE = "languages/available.yml";

    public static final String FALLBACK = "en-us";

    private static final System.Logger LOGGER = System.getLogger(Language.class.getName());

    private Language() {
    }

    /**
     * @param config the plugin's own config, read for its {@code language} option
     * @return the messages for the chosen language, falling back to {@value #FALLBACK} when the
     *         option is malformed or names a language that is neither bundled nor written by the
     *         operator themselves
     */
    public static YamlConfig load(Path dataDirectory, YamlConfig config, ClassLoader resourceLoader) {
        // Everything the plugin ships, so the operator can read and edit any of them.
        for (String bundled : bundledLanguages(resourceLoader)) {
            try {
                YamlConfig.load(dataDirectory, path(bundled), path(bundled), resourceLoader);
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Could not write the bundled language " + bundled + ".", e);
            }
        }

        String requested = config.getString("language", FALLBACK).trim().toLowerCase(Locale.ROOT);
        String code = requested;

        if (!VALID_CODE.matcher(code).matches()) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "language is ''{0}'', which is not a locale code such as en-us. Using {1}.",
                    requested, FALLBACK);
            code = FALLBACK;
        } else if (!isAvailable(code, dataDirectory, resourceLoader)) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "There is no language file for ''{0}''. Using {1}. To add one, copy "
                            + "languages/{1}.yml, translate it, and save it as languages/{0}.yml.",
                    requested, FALLBACK);
            code = FALLBACK;
        }

        return YamlConfig.load(dataDirectory, path(code), path(code), resourceLoader);
    }

    /**
     * Read from a small index the plugin ships, because a classloader cannot be asked to list
     * the contents of a folder inside a jar. A plugin without one still works: only the language
     * actually in use gets written.
     */
    private static List<String> bundledLanguages(ClassLoader resourceLoader) {
        try (InputStream in = resourceLoader.getResourceAsStream(INDEX_RESOURCE)) {
            if (in == null) {
                return List.of();
            }
            Object loaded = new org.yaml.snakeyaml.Yaml().load(in);
            if (!(loaded instanceof java.util.Map<?, ?> root)
                    || !(root.get("languages") instanceof List<?> listed)) {
                return List.of();
            }
            List<String> codes = new ArrayList<>();
            for (Object entry : listed) {
                String code = String.valueOf(entry).trim().toLowerCase(Locale.ROOT);
                if (VALID_CODE.matcher(code).matches()) {
                    codes.add(code);
                }
            }
            return codes;
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not read " + INDEX_RESOURCE + ".", e);
            return List.of();
        }
    }

    /**
     * A language counts as available if this plugin ships it OR the operator has written the
     * file themselves. Requiring it to be bundled would make a translation of your own
     * impossible, which is a strange thing for a translation system to forbid.
     */
    private static boolean isAvailable(String code, Path dataDirectory, ClassLoader resourceLoader) {
        return resourceLoader.getResource(path(code)) != null
                || Files.isRegularFile(dataDirectory.resolve(path(code)));
    }

    private static String path(String code) {
        return "languages/" + code + ".yml";
    }
}
