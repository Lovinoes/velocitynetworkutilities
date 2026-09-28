package de.lovinoes.velocitynetworkutilities.util;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.velocitynetworkutilities.api.VelocityNetworkAPI;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Shared tab-completion helper: online player names matching a partial argument, with
 * vanished players hidden unless the requester holds the see-vanished permission.
 */
public final class PlayerSuggestions {

    private PlayerSuggestions() {
    }

    public static List<String> matching(ProxyServer proxyServer, CommandSource requester, String partial, String seeVanishedPermission) {
        String lowerPartial = partial.toLowerCase(Locale.ROOT);
        boolean canSeeVanished = requester.hasPermission(seeVanishedPermission);
        return proxyServer.getAllPlayers().stream()
                .filter(player -> canSeeVanished || !VelocityNetworkAPI.get().isVanished(player.getUniqueId())
                        || (requester instanceof Player self && self.getUniqueId().equals(player.getUniqueId())))
                .map(Player::getUsername)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lowerPartial))
                .collect(Collectors.toList());
    }
}
