package de.lovinoes.networkutilitiescommon.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class YamlConfig {

    private static final System.Logger LOGGER = System.getLogger(YamlConfig.class.getName());

    private final Map<String, Object> data;

    private YamlConfig(Map<String, Object> data) {
        this.data = data;
    }

    public static YamlConfig load(Path dataDirectory, String fileName, ClassLoader resourceLoader) {
        return load(dataDirectory, fileName, fileName, resourceLoader);
    }

    /**
     * @param fileName     where the file lives under the plugin's data directory, which may be
     *                     in a subfolder such as languages/de-de.yml
     * @param resourcePath the bundled copy inside the jar, which is usually the same path but
     *                     need not be
     */
    public static YamlConfig load(Path dataDirectory, String fileName, String resourcePath,
                                   ClassLoader resourceLoader) {
        try {
            Files.createDirectories(dataDirectory);
            Path target = dataDirectory.resolve(fileName);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            boolean created = false;
            if (!Files.exists(target)) {
                try (InputStream in = resourceLoader.getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        throw new IllegalStateException("Missing bundled resource: " + resourcePath);
                    }
                    Files.copy(in, target);
                }
                created = true;
            }

            // A file just written from the jar is already current, so only an existing one is
            // worth reconciling against the shipped defaults.
            if (!created) {
                migrate(target, resourcePath, resourceLoader);
            }

            try (InputStream in = Files.newInputStream(target)) {
                Yaml yaml = new Yaml();
                Map<String, Object> loaded = yaml.load(in);
                return new YamlConfig(loaded != null ? loaded : new LinkedHashMap<>());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config " + fileName, e);
        }
    }

    /**
     * Adds options introduced by a plugin update and drops ones it no longer reads, so nobody
     * has to diff their config against a changelog after upgrading.
     *
     * Failing to migrate is never a reason to fail to start: the existing file is still
     * perfectly loadable, so a problem here is logged and the original is used as-is.
     */
    private static void migrate(Path target, String fileName, ClassLoader resourceLoader) {
        try (InputStream defaults = resourceLoader.getResourceAsStream(fileName)) {
            if (defaults == null) {
                return;
            }
            ConfigMigrator.Result result = ConfigMigrator.migrate(target, defaults);
            if (!result.changed()) {
                return;
            }
            if (!result.added().isEmpty()) {
                LOGGER.log(System.Logger.Level.INFO, "{0}: added {1} new option(s) from this version: {2}",
                        fileName, result.added().size(), String.join(", ", result.added()));
            }
            if (!result.updated().isEmpty()) {
                LOGGER.log(System.Logger.Level.INFO,
                        "{0}: updated {1} option(s) that were still at the previous default: {2}",
                        fileName, result.updated().size(), String.join(", ", result.updated()));
            }
            if (!result.removed().isEmpty()) {
                LOGGER.log(System.Logger.Level.INFO, "{0}: removed {1} option(s) this version no longer reads: {2}",
                        fileName, result.removed().size(), String.join(", ", result.removed()));
            }
            LOGGER.log(System.Logger.Level.INFO, "{0}: the previous file was kept as {0}.bak", fileName);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Could not update " + fileName + " to this version's options; using it unchanged.", e);
        }
    }

    @SuppressWarnings("unchecked")
    public Object get(String dottedPath) {
        String[] parts = dottedPath.split("\\.");
        Object current = data;
        for (String part : parts) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(part);
        }
        return current;
    }

    public String getString(String path, String def) {
        Object value = get(path);
        return value != null ? String.valueOf(value) : def;
    }

    public int getInt(String path, int def) {
        Object value = get(path);
        return value instanceof Number number ? number.intValue() : def;
    }

    public long getLong(String path, long def) {
        Object value = get(path);
        return value instanceof Number number ? number.longValue() : def;
    }

    public double getDouble(String path, double def) {
        Object value = get(path);
        return value instanceof Number number ? number.doubleValue() : def;
    }

    public boolean getBoolean(String path, boolean def) {
        Object value = get(path);
        return value instanceof Boolean bool ? bool : def;
    }

    /**
     * Every element is turned into a string rather than cast to one. A list written as
     * [10, 30] parses to numbers, and casting that to List&lt;String&gt; succeeds silently
     * before failing much later, somewhere with no clue about which config line caused it.
     */
    public List<String> getStringList(String path) {
        Object value = get(path);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element != null) {
                strings.add(String.valueOf(element));
            }
        }
        return strings;
    }

    /**
     * An enum value, falling back with a warning rather than throwing.
     *
     * A typo in one of these used to stop the plugin loading, which for the core module takes
     * the whole suite with it. Naming the bad value and carrying on with the default is far
     * more use to whoever has to fix it.
     */
    public <E extends Enum<E>> E getEnum(String path, E fallback) {
        String raw = getString(path, fallback.name()).trim();
        for (E candidate : fallback.getDeclaringClass().getEnumConstants()) {
            if (candidate.name().equalsIgnoreCase(raw)) {
                return candidate;
            }
        }
        LOGGER.log(System.Logger.Level.WARNING,
                "{0} is ''{1}'', which is not one of {2}. Using {3}.",
                path, raw, Arrays.toString(fallback.getDeclaringClass().getEnumConstants()), fallback);
        return fallback;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getSection(String path) {
        Object value = get(path);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }
}
