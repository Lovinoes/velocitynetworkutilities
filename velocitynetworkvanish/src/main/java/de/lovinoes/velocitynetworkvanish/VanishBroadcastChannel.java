package de.lovinoes.velocitynetworkvanish;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;

/**
 * Pushes the vanish state to the PaperNetworkVanish companion plugin on backend servers.
 *
 * Two things drive the design here:
 *
 * 1. It sends a complete snapshot of every online vanished player rather than incremental
 *    add/remove deltas. A snapshot is self-healing: a backend server that restarts, or one that
 *    missed a message because nobody was connected to carry it, gets fully corrected by the next
 *    snapshot instead of drifting out of sync forever.
 * 2. It sends that snapshot to EVERY backend server, not just the server the vanished player is
 *    on. Entity hiding only matters on their own server, but cross-server plugins (HuskHomes's
 *    /tpa target completion, for one) suggest players from the whole network, so every server
 *    needs the vanished name list to filter its own suggestions.
 *
 * Note that a plugin message can only reach a backend server through a player connected to it,
 * so a server with nobody on it cannot be told anything. That is harmless: with no players there,
 * there is nothing to hide and no one to complete a name, and the server gets a fresh snapshot as
 * soon as the first player connects to it.
 */
public final class VanishBroadcastChannel {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final ChannelIdentifier channelIdentifier;

    public VanishBroadcastChannel(ProxyServer proxyServer, Logger logger, String channelName) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.channelIdentifier = MinecraftChannelIdentifier.from(channelName);
    }

    public void start() {
        proxyServer.getChannelRegistrar().register(channelIdentifier);
    }

    public void stop() {
        proxyServer.getChannelRegistrar().unregister(channelIdentifier);
    }

    /** Sends the snapshot to every backend server that currently has at least one player on it. */
    public void broadcastSnapshot(Map<UUID, String> vanishedPlayers) {
        byte[] payload = frame(vanishedPlayers);
        for (RegisteredServer server : proxyServer.getAllServers()) {
            server.getPlayersConnected().stream()
                    .findAny()
                    .flatMap(Player::getCurrentServer)
                    .ifPresent(connection -> send(connection, payload));
        }
    }

    /** Sends the snapshot to just the server this player is currently connected to. */
    public void sendSnapshotToServerOf(Player player, Map<UUID, String> vanishedPlayers) {
        player.getCurrentServer().ifPresent(connection -> send(connection, frame(vanishedPlayers)));
    }

    private void send(ServerConnection connection, byte[] payload) {
        String serverName = connection.getServerInfo().getName();
        if (!connection.sendPluginMessage(channelIdentifier, payload)) {
            logger.warn("Backend server '{}' did not accept the vanish state on channel {}. "
                    + "Is PaperNetworkVanish installed and running there?", serverName, channelIdentifier.getId());
        }
    }

    private byte[] frame(Map<UUID, String> vanishedPlayers) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(vanishedPlayers.size());
            for (Map.Entry<UUID, String> entry : vanishedPlayers.entrySet()) {
                out.writeUTF(entry.getKey().toString());
                out.writeUTF(entry.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }
}
