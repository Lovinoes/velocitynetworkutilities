package de.lovinoes.velocitynetworkchat;

import java.util.List;

/**
 * @param permission     who may write into this channel
 * @param readPermission who may see what is written. Separate because the two are genuinely
 *                       different questions: a public channel is one anybody can read, and a
 *                       staff channel is one only staff can read. Defaults to
 *                       {@code permission}, so a channel restricted to write is restricted to
 *                       read until someone says otherwise.
 */
public record ChatChannel(
        String key,
        String displayName,
        String format,
        String permission,
        String readPermission,
        List<String> commandTriggers,
        String prefixTrigger,
        ChannelScope scope,
        int distance
) {
    public enum ChannelScope {
        NETWORK,
        SERVER
    }

    /**
     * A SERVER-scoped channel with a positive distance only reaches players within that many
     * blocks, which has to be resolved on the backend server where positions are known.
     * A distance of 0 means everyone on the sender's server, whatever the distance.
     */
    public boolean hasDistanceLimit() {
        return scope == ChannelScope.SERVER && distance > 0;
    }

    /** Same channel with its prefix trigger removed, so only its commands can select it. */
    public ChatChannel withoutPrefixTrigger() {
        return new ChatChannel(key, displayName, format, permission, readPermission, commandTriggers, "",
                scope, distance);
    }
}
