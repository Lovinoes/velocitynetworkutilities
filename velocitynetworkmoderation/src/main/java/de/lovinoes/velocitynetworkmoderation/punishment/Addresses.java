package de.lovinoes.velocitynetworkmoderation.punishment;

import com.velocitypowered.api.proxy.Player;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/** How a player's address is read, in the one place that decides it. */
public final class Addresses {

    private Addresses() {
    }

    /**
     * getAddress() is null for an address that was never resolved, and getHostString() is then
     * the literal that was supplied. Reaching for the first without the second throws, and both
     * callers here sit inside a catch that treats a throw as a lookup failure, so it would
     * quietly stop bans applying rather than showing up as an error.
     */
    public static String of(Player player) {
        InetSocketAddress remote = player.getRemoteAddress();
        InetAddress resolved = remote.getAddress();
        return resolved != null ? resolved.getHostAddress() : remote.getHostString();
    }
}
