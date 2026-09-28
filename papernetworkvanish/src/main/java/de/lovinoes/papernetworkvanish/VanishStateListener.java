package de.lovinoes.papernetworkvanish;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies network vanish state on this backend server.
 *
 * Two separate jobs, and they need different data:
 *
 * 1. Entity hiding, via Player#hidePlayer. This only applies to a vanished player who is
 *    actually on this server, and is what makes the model, armour and nametag disappear
 *    immediately without a rejoin.
 * 2. Name filtering out of command completions. This applies to EVERY vanished player on the
 *    network, not just local ones, because cross-server plugins (HuskHomes's /tpa target list,
 *    for one) suggest players from other servers too. Resolving a name via Bukkit.getPlayer()
 *    would only ever find local players, so the proxy sends the username alongside the UUID and
 *    the names are filtered directly.
 *
 * The proxy sends a complete snapshot rather than deltas, so this listener just replaces its
 * view of the world on each message. That keeps it correct after a restart or a missed message.
 */
public final class VanishStateListener implements Listener, PluginMessageListener {

    private final Plugin plugin;
    private final String seeVanishedPermission;

    /** uuid -> username of every vanished player on the network, not just this server. */
    private final Map<UUID, String> vanishedPlayers = new ConcurrentHashMap<>();

    public VanishStateListener(Plugin plugin, String seeVanishedPermission) {
        this.plugin = plugin;
        this.seeVanishedPermission = seeVanishedPermission;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player carrier, byte[] message) {
        Map<UUID, String> snapshot = new ConcurrentHashMap<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                UUID uuid = UUID.fromString(in.readUTF());
                snapshot.put(uuid, in.readUTF());
            }
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().warning("Malformed vanish snapshot received: " + e.getMessage());
            return;
        }

        Set<UUID> noLongerVanished = new HashSet<>(vanishedPlayers.keySet());
        noLongerVanished.removeAll(snapshot.keySet());

        vanishedPlayers.clear();
        vanishedPlayers.putAll(snapshot);

        // Bukkit's visibility API is main-thread only; a plugin message arrives on a netty
        // thread, so hop back onto the main thread before touching any of it.
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (UUID uuid : noLongerVanished) {
                Player target = Bukkit.getPlayer(uuid);
                if (target != null) {
                    applyVisibility(target, false);
                    Bukkit.getPluginManager().callEvent(new PlayerVanishStateChangeEvent(target, false));
                }
            }
            for (UUID uuid : snapshot.keySet()) {
                Player target = Bukkit.getPlayer(uuid);
                if (target != null) {
                    applyVisibility(target, true);
                    Bukkit.getPluginManager().callEvent(new PlayerVanishStateChangeEvent(target, true));
                }
            }
        });
    }

    /**
     * Suppresses this server's own join and quit lines for a vanished player.
     *
     * The proxy already announces joins and leaves with vanish taken into account, and it
     * announces a fake leave the moment someone vanishes. A backend still broadcasting "x joined
     * the game" when that player reconnects, or "x left the game" when they disconnect,
     * contradicts all of it and says plainly that they were there.
     *
     * HIGHEST rather than MONITOR because this changes the event: MONITOR is for observing a
     * decision that has already been made.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVanishedPlayerJoinMessage(PlayerJoinEvent event) {
        if (vanishedPlayers.containsKey(event.getPlayer().getUniqueId())) {
            event.joinMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVanishedPlayerQuitMessage(PlayerQuitEvent event) {
        if (vanishedPlayers.containsKey(event.getPlayer().getUniqueId())) {
            event.quitMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();

        // The proxy keeps vanished players in the snapshot while they are offline, so this is
        // already known when they reconnect and they are hidden here, before anyone sees them,
        // rather than after a round trip to the proxy and back.
        if (vanishedPlayers.containsKey(joined.getUniqueId())) {
            applyVisibility(joined, true);
        }

        if (!joined.hasPermission(seeVanishedPermission)) {
            for (UUID vanishedId : vanishedPlayers.keySet()) {
                if (vanishedId.equals(joined.getUniqueId())) {
                    continue;
                }
                Player vanishedPlayer = Bukkit.getPlayer(vanishedId);
                if (vanishedPlayer != null) {
                    joined.hidePlayer(plugin, vanishedPlayer);
                }
            }
        }
    }

    /**
     * Paper fires this first, off the main thread, for command completions. Filtering both this
     * and the synchronous TabCompleteEvent below covers whichever path a given plugin's
     * completions travel, since that depends on how it registered its command.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAsyncTabComplete(AsyncTabCompleteEvent event) {
        if (vanishedPlayers.isEmpty() || canSeeVanished(event.getSender())) {
            return;
        }
        event.setCompletions(withoutVanishedNames(event.getCompletions()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTabComplete(TabCompleteEvent event) {
        if (vanishedPlayers.isEmpty() || canSeeVanished(event.getSender())) {
            return;
        }
        event.setCompletions(withoutVanishedNames(event.getCompletions()));
    }

    private boolean canSeeVanished(CommandSender sender) {
        return sender.hasPermission(seeVanishedPermission);
    }

    private List<String> withoutVanishedNames(List<String> completions) {
        Set<String> hidden = new HashSet<>();
        for (String name : vanishedPlayers.values()) {
            hidden.add(name.toLowerCase(Locale.ROOT));
        }
        List<String> filtered = new ArrayList<>(completions.size());
        for (String completion : completions) {
            if (!hidden.contains(completion.toLowerCase(Locale.ROOT))) {
                filtered.add(completion);
            }
        }
        return filtered;
    }

    private void applyVisibility(Player target, boolean vanished) {
        int affected = 0;
        int skippedByPermission = 0;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(target.getUniqueId())) {
                continue;
            }
            if (vanished) {
                if (viewer.hasPermission(seeVanishedPermission)) {
                    skippedByPermission++;
                } else {
                    viewer.hidePlayer(plugin, target);
                    affected++;
                }
            } else {
                viewer.showPlayer(plugin, target);
                affected++;
            }
        }
        plugin.getLogger().info((vanished ? "Hid " : "Revealed ") + target.getName() + " for "
                + affected + " player(s)"
                + (skippedByPermission > 0
                        ? ", skipped " + skippedByPermission + " holding " + seeVanishedPermission
                        : "")
                + ".");
    }
}
