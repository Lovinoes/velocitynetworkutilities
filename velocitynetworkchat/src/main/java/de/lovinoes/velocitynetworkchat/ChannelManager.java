package de.lovinoes.velocitynetworkchat;

import de.lovinoes.networkutilitiescommon.config.YamlConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class ChannelManager {

    private static final System.Logger LOGGER = System.getLogger(ChannelManager.class.getName());

    private final Map<String, ChatChannel> channels = new LinkedHashMap<>();
    private final Map<UUID, String> activeChannel = new ConcurrentHashMap<>();
    private final String defaultChannelKey;

    /**
     * @param messages the language file, which holds each channel's display name and the format
     *                 its messages are written in. Those are the parts a translator changes; the
     *                 permissions, triggers, scope and range stay in config.yml because they are
     *                 the same whatever language the server speaks.
     */
    public ChannelManager(YamlConfig config, YamlConfig messages) {
        Map<String, Object> channelsSection = config.getSection("channels");
        for (Map.Entry<String, Object> entry : channelsSection.entrySet()) {
            String key = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> definition = (Map<String, Object>) entry.getValue();
            String upper = key.toUpperCase();
            channels.put(upper, new ChatChannel(
                    upper,
                    messages.getString("channels." + upper + ".display-name", key),
                    messages.getString("channels." + upper + ".format", "<player>: <message>"),
                    String.valueOf(definition.getOrDefault("permission", "")),
                    readPermissionOf(definition),
                    castList(definition.get("command-triggers")),
                    String.valueOf(definition.getOrDefault("prefix-trigger", "")),
                    scopeOf(key, definition.getOrDefault("scope", "NETWORK")),
                    definition.get("distance") instanceof Number n ? n.intValue() : 0
            ));
        }

        // "@" starts a mention, so a channel claiming it as a prefix trigger would capture every
        // message beginning with one: staff would see "@name hello" as staff chat, and everyone
        // else would have the message denied for lack of permission. The trigger is dropped
        // rather than obeyed, because silently eating chat is the worse failure.
        channels.replaceAll((key, channel) -> {
            if (!"@".equals(channel.prefixTrigger())) {
                return channel;
            }
            LOGGER.log(System.Logger.Level.WARNING,
                    "Channel {0} uses \"@\" as its prefix-trigger, which collides with @name mentions. "
                            + "The trigger has been disabled; use another character such as \"#\" and restart.",
                    key);
            return channel.withoutPrefixTrigger();
        });

        if (channels.isEmpty()) {
            throw new IllegalStateException(
                    "VelocityNetworkChat has no channels configured under 'channels:' in config.yml. "
                            + "Chat cannot function with zero channels; add at least one before restarting.");
        }

        String configuredDefault = config.getString("default-channel", "GLOBAL").toUpperCase();
        if (channels.containsKey(configuredDefault)) {
            this.defaultChannelKey = configuredDefault;
        } else {
            this.defaultChannelKey = channels.keySet().iterator().next();
            LOGGER.log(System.Logger.Level.WARNING,
                    "default-channel '{0}' does not match any configured channel; falling back to '{1}'.",
                    configuredDefault, defaultChannelKey);
        }
    }

    /**
     * Who may read the channel. Absent means "the same people who may write into it", which is
     * the safe way round: a new channel added to a config without thinking about reading is
     * private, not accidentally public. Set it to "" to let everyone read a channel only some
     * may write into, which is what an ordinary public channel is.
     */
    private static String readPermissionOf(Map<String, Object> definition) {
        Object configured = definition.get("read-permission");
        return configured != null
                ? String.valueOf(configured)
                : String.valueOf(definition.getOrDefault("permission", ""));
    }

    /**
     * NETWORK is the safe fallback for a misspelled scope: it delivers the message to everyone
     * rather than to nobody, and it needs no backend plugin to work. Throwing here would stop
     * the whole chat plugin loading over one typo in one channel.
     */
    private static ChatChannel.ChannelScope scopeOf(String channelKey, Object raw) {
        String text = String.valueOf(raw).trim();
        for (ChatChannel.ChannelScope scope : ChatChannel.ChannelScope.values()) {
            if (scope.name().equalsIgnoreCase(text)) {
                return scope;
            }
        }
        LOGGER.log(System.Logger.Level.WARNING,
                "Channel {0} has scope {1}, which is not NETWORK or SERVER. Using NETWORK.",
                channelKey, text);
        return ChatChannel.ChannelScope.NETWORK;
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object raw) {
        return raw instanceof List ? (List<String>) raw : List.of();
    }

    public Optional<ChatChannel> byKey(String key) {
        return Optional.ofNullable(channels.get(key.toUpperCase()));
    }

    public Optional<ChatChannel> byCommandTrigger(String label) {
        return channels.values().stream()
                .filter(channel -> channel.commandTriggers().contains(label.toLowerCase()))
                .findFirst();
    }

    /**
     * The channel a message's prefix routes it into, among only the channels the sender may
     * write in. For anyone else the prefix is not a trigger at all, just the first character of
     * an ordinary message: a player without staff permission who types "#hello" says "#hello" in
     * their own channel, and learns nothing about a channel they cannot use. A prefix with
     * nothing after it is ordinary text too, since it cannot be a message for the channel.
     */
    public Optional<ChatChannel> byPrefixTrigger(String message, Predicate<ChatChannel> mayWrite) {
        return channels.values().stream()
                .filter(channel -> !channel.prefixTrigger().isEmpty()
                        && message.startsWith(channel.prefixTrigger())
                        && !message.substring(channel.prefixTrigger().length()).isBlank())
                .filter(mayWrite)
                .findFirst();
    }

    public ChatChannel activeChannel(UUID playerId) {
        String key = activeChannel.getOrDefault(playerId, defaultChannelKey);
        return channels.getOrDefault(key, channels.get(defaultChannelKey));
    }

    public void setActiveChannel(UUID playerId, String key) {
        activeChannel.put(playerId, key.toUpperCase());
    }

    public void clearActiveChannel(UUID playerId) {
        activeChannel.remove(playerId);
    }

    public Map<String, ChatChannel> channels() {
        return channels;
    }
}
