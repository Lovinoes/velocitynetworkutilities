package de.lovinoes.networkutilitiescommon.sound;

import de.lovinoes.networkutilitiescommon.config.YamlConfig;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

import java.lang.System.Logger.Level;
import java.util.Locale;

/**
 * Builds an Adventure {@link Sound} from a config section, tolerating anything an operator might
 * reasonably get wrong.
 *
 * A section looks like:
 * <pre>
 * sound:
 *   enabled: true
 *   key: "minecraft:block.note_block.pling"
 *   source: MASTER
 *   volume: 0.5
 *   pitch: 1.2
 * </pre>
 *
 * Returns null (meaning "play nothing") when disabled, when the key is blank, or when the key or
 * source is invalid. An invalid value is logged once at load time rather than throwing, so a typo
 * in a sound name can never stop chat or vanish from working.
 */
public final class ConfiguredSound {

    private static final System.Logger LOGGER = System.getLogger(ConfiguredSound.class.getName());

    private ConfiguredSound() {
    }

    public static Sound fromConfig(YamlConfig config, String path, String defaultKey, String defaultSource,
                                    double defaultVolume, double defaultPitch) {
        if (!config.getBoolean(path + ".enabled", true)) {
            return null;
        }
        String key = config.getString(path + ".key", defaultKey);
        String source = config.getString(path + ".source", defaultSource);
        float volume = (float) config.getDouble(path + ".volume", defaultVolume);
        float pitch = (float) config.getDouble(path + ".pitch", defaultPitch);
        return build(key, source, volume, pitch, path);
    }

    private static Sound build(String key, String source, float volume, float pitch, String path) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return Sound.sound(Key.key(key), parseSource(source), volume, pitch);
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Invalid sound at '" + path + "' (key '" + key + "', source '" + source
                    + "'); no sound will play for it: " + e.getMessage());
            return null;
        }
    }

    private static Sound.Source parseSource(String source) {
        return Sound.Source.valueOf(source.toUpperCase(Locale.ROOT));
    }
}
