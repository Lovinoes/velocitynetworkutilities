package de.lovinoes.velocitynetworkplayerinfo.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import de.lovinoes.velocitynetworkutilities.api.VelocityNetworkAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Announces a join only once a player has actually landed on a backend server, and a leave only
 * for someone whose join was announced.
 *
 * PostLoginEvent is deliberately not used: it fires as soon as the proxy has authenticated the
 * connection, before any backend has accepted it. Anyone rejected after that point, most often
 * for running an outdated client, would otherwise produce a join announcement for a player who
 * never arrived, immediately followed by a leave.
 */
public final class JoinLeaveListener {

    private final ProxyServer proxyServer;
    private final String joinFormat;
    private final String leaveFormat;
    private final String seeVanishedPermission;

    /**
     * Who has had a join announced. This is what keeps joins and leaves paired: a connection
     * that never reached a backend is in here, so its disconnect announces nothing.
     *
     * Entries are removed on disconnect, which Velocity fires for every connection that reached
     * the proxy, including the ones that never got a server. Nothing accumulates.
     */
    private final Set<UUID> announced = ConcurrentHashMap.newKeySet();

    public JoinLeaveListener(ProxyServer proxyServer, String joinFormat, String leaveFormat, String seeVanishedPermission) {
        this.proxyServer = proxyServer;
        this.joinFormat = joinFormat;
        this.leaveFormat = leaveFormat;
        this.seeVanishedPermission = seeVanishedPermission;
    }

    /**
     * Fires for every backend connection, including each server switch. getPreviousServer() is
     * null only for the first one, which is the moment the player is genuinely in the game.
     */
    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (event.getPreviousServer() != null) {
            return;
        }
        Player player = event.getPlayer();
        // add() is the guard as well as the record: if two first-connects were ever delivered
        // for one session, only the first announces.
        if (announced.add(player.getUniqueId())) {
            broadcast(player, joinFormat);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        if (announced.remove(player.getUniqueId())) {
            broadcast(player, leaveFormat);
        }
    }

    /**
     * Unprivileged viewers never hear about a vanished player's join/leave, but staff with
     * vanish.see still should, the same way chat delivery is muted per-viewer rather than
     * suppressed for the whole network.
     */
    private void broadcast(Player player, String format) {
        boolean vanished = VelocityNetworkAPI.get().isVanished(player.getUniqueId());
        Component message = ChatColorParser.parse(format, Placeholder.unparsed("player", player.getUsername()));
        for (Player recipient : proxyServer.getAllPlayers()) {
            if (vanished && !recipient.hasPermission(seeVanishedPermission)) {
                continue;
            }
            recipient.sendMessage(message);
        }
        // The console is an operational log, not a player-facing view: it always sees joins
        // and leaves regardless of vanish state, the same way it has every permission.
        proxyServer.getConsoleCommandSource().sendMessage(message);
    }
}
