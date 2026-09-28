package de.lovinoes.velocitynetworkvanish;

import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import de.lovinoes.networkutilitiescommon.messaging.MessageListener;
import de.lovinoes.networkutilitiescommon.vanish.NetworkVanishDao;
import de.lovinoes.velocitynetworkutilities.api.VelocityNetworkAPI;
import de.lovinoes.velocitynetworkvanish.event.PlayerVanishStateChangeEvent;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class VanishManager {

    private static final String VANISH_UPDATE_TOPIC = "vanish:update";
    private static final long TAB_LIST_RESYNC_DELAY_MS = 350;

    private final ProxyServer proxyServer;
    private final Object pluginInstance;
    private final EventManager eventManager;
    private final NetworkVanishDao vanishDao;
    private final VanishBroadcastChannel broadcastChannel;
    private final String seeVanishedPermission;
    private final String fakeJoinFormat;
    private final String fakeLeaveFormat;
    private final Sound vanishSound;
    private final Sound unvanishSound;
    private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();

    /**
     * Last seen username of each vanished player, so the snapshot can keep carrying them while
     * they are offline. Without it a snapshot could only describe players the proxy can resolve
     * right now, which is what made a vanished player visible as they left and again as they
     * came back.
     */
    private final Map<UUID, String> lastKnownNames = new ConcurrentHashMap<>();

    public VanishManager(ProxyServer proxyServer, Object pluginInstance, EventManager eventManager, NetworkVanishDao vanishDao,
                          VanishBroadcastChannel broadcastChannel, String seeVanishedPermission,
                          String fakeJoinFormat, String fakeLeaveFormat, Sound vanishSound, Sound unvanishSound) {
        this.proxyServer = proxyServer;
        this.pluginInstance = pluginInstance;
        this.eventManager = eventManager;
        this.vanishDao = vanishDao;
        this.broadcastChannel = broadcastChannel;
        this.seeVanishedPermission = seeVanishedPermission;
        this.fakeJoinFormat = fakeJoinFormat;
        this.fakeLeaveFormat = fakeLeaveFormat;
        this.vanishSound = vanishSound;
        this.unvanishSound = unvanishSound;
    }

    public void loadPersistedState() {
        vanishDao.loadVanishedPlayers().thenAccept(uuids -> {
            vanishedPlayers.addAll(uuids);
            uuids.forEach(uuid -> VelocityNetworkAPI.get().setVanished(uuid, true));
        });
    }

    public boolean isVanished(UUID uuid) {
        return vanishedPlayers.contains(uuid);
    }

    public void toggleVanish(Player player) {
        setVanished(player, !isVanished(player.getUniqueId()), false);
    }

    /**
     * @param silent when true nothing is announced. That is the only difference between
     *               /silentvanish and /vanish; the rest of the toggle is identical.
     */
    public void setVanished(Player player, boolean vanished, boolean silent) {
        UUID uuid = player.getUniqueId();
        if (vanished) {
            vanishedPlayers.add(uuid);
            rememberName(player);
        } else {
            vanishedPlayers.remove(uuid);
            lastKnownNames.remove(uuid);
        }
        VelocityNetworkAPI.get().setVanished(uuid, vanished);
        vanishDao.setVanished(uuid, vanished);
        syncTabListForAllViewers(player, vanished);
        pushVanishUpdate(uuid, vanished, player.getUsername());
        broadcastChannel.broadcastSnapshot(vanishedSnapshot());

        if (!silent) {
            announcePresence(player, vanished);
        }
        playToggleSound(player, vanished);

        eventManager.fireAndForget(new PlayerVanishStateChangeEvent(player, vanished));
    }

    /**
     * Vanishing announces a leave and unvanishing announces a join, so to everyone else it looks
     * like an ordinary disconnect and reconnect.
     *
     * Anyone holding the see-vanished permission is skipped: they can still see the player, so
     * telling them somebody "left" would only be confusing. The player toggling is skipped too,
     * since they already get their own confirmation message.
     */
    private void announcePresence(Player player, boolean vanished) {
        String format = vanished ? fakeLeaveFormat : fakeJoinFormat;
        if (format == null || format.isBlank()) {
            return;
        }
        Component message = ChatColorParser.parse(format, Placeholder.unparsed("player", player.getUsername()));
        for (Player recipient : proxyServer.getAllPlayers()) {
            if (recipient.getUniqueId().equals(player.getUniqueId())
                    || recipient.hasPermission(seeVanishedPermission)) {
                continue;
            }
            recipient.sendMessage(message);
        }
    }

    /**
     * Sound.Emitter.self() is not optional here. Velocity only implements
     * playSound(Sound, Emitter); the no-argument playSound(Sound) it inherits from Audience is a
     * default method whose body is literally "return", so calling it plays nothing at all.
     */
    private void playToggleSound(Player player, boolean vanished) {
        Sound sound = vanished ? vanishSound : unvanishSound;
        if (sound != null) {
            player.playSound(sound, Sound.Emitter.self());
        }
    }

    /**
     * Every vanished player, online or not, by last known name.
     *
     * Dropping the offline ones looks harmless and is not. A backend treats the snapshot as the
     * whole truth and reveals anyone missing from it, so a snapshot sent while a vanished player
     * disconnects tells their server to show them, and they flash into view for the moment
     * before the disconnect completes. Worse, the backend then no longer knows they are
     * vanished, so when they come back it cannot hide them at join and has to wait to be told
     * again, which is the second flash.
     *
     * Keeping them costs nothing. An offline player has no entity to hide, and their name being
     * filtered out of completions while they are away is correct: they are still vanished.
     */
    private Map<UUID, String> vanishedSnapshot() {
        return snapshotOf(vanishedPlayers,
                uuid -> proxyServer.getPlayer(uuid).map(Player::getUsername).orElse(null),
                lastKnownNames);
    }

    /**
     * @param onlineName the player's current name, or null if the proxy cannot resolve them.
     *                   Preferred over the remembered one so a name change is picked up.
     */
    static Map<UUID, String> snapshotOf(Set<UUID> vanished, Function<UUID, String> onlineName,
                                         Map<UUID, String> lastKnownNames) {
        Map<UUID, String> snapshot = new LinkedHashMap<>();
        for (UUID uuid : vanished) {
            String name = onlineName.apply(uuid);
            if (name == null) {
                name = lastKnownNames.get(uuid);
            }
            // A player vanished in a previous session who has not been seen since this proxy
            // started has no name yet. They are offline, so there is nothing to hide or filter,
            // and they join the snapshot the moment they connect.
            if (name != null) {
                snapshot.put(uuid, name);
            }
        }
        return snapshot;
    }

    /**
     * Names are only kept for players who are actually vanished, and dropped as soon as they are
     * not, so this cannot grow beyond the number of vanished players.
     */
    private void rememberName(Player player) {
        lastKnownNames.put(player.getUniqueId(), player.getUsername());
    }

    /**
     * Tells the other proxies, which keep their own list of who is vanished. Without it, two
     * proxies sharing a backend send it snapshots that disagree, and the one that does not know
     * reveals the player there. The name travels along because backends filter completions by
     * name.
     */
    private void pushVanishUpdate(UUID uuid, boolean vanished, String name) {
        String payload = uuid + "|" + vanished + "|" + name;
        VelocityNetworkAPI.get().messaging().publish(VANISH_UPDATE_TOPIC, payload.getBytes(StandardCharsets.UTF_8));
    }

    private final MessageListener updateListener =
            (topic, payload) -> onRemoteUpdate(new String(payload, StandardCharsets.UTF_8));

    public void start() {
        VelocityNetworkAPI.get().messaging().subscribe(VANISH_UPDATE_TOPIC, updateListener);
    }

    public void stop() {
        VelocityNetworkAPI.get().messaging().unsubscribe(VANISH_UPDATE_TOPIC, updateListener);
    }

    /**
     * Another proxy vanished or unvanished someone. Every proxy also hears its own updates back,
     * and those change nothing here, which is exactly how they are told apart and ignored.
     */
    void onRemoteUpdate(String raw) {
        String[] parts = raw.split("\\|", 3);
        UUID uuid;
        try {
            uuid = UUID.fromString(parts[0].trim());
        } catch (IllegalArgumentException e) {
            return;
        }
        if (parts.length < 2) {
            return;
        }
        boolean vanished = Boolean.parseBoolean(parts[1].trim());
        String name = parts.length > 2 ? parts[2].trim() : "";

        boolean changed = vanished ? vanishedPlayers.add(uuid) : vanishedPlayers.remove(uuid);
        if (!changed) {
            return;
        }
        if (!vanished) {
            lastKnownNames.remove(uuid);
        } else if (!name.isEmpty()) {
            lastKnownNames.put(uuid, name);
        }
        VelocityNetworkAPI.get().setVanished(uuid, vanished);
        proxyServer.getPlayer(uuid).ifPresent(player -> syncTabListForAllViewers(player, vanished));
        broadcastChannel.broadcastSnapshot(vanishedSnapshot());
    }

    /**
     * Velocity rebuilds a player's TabList on every backend server switch (and on the initial
     * proxy join), which would otherwise re-surface vanished players. Called from both
     * PostLoginEvent and ServerConnectedEvent, this re-applies vanish state in both directions:
     * it hides every vanished player from the connecting player's rebuilt view, and if that
     * player is themselves vanished, it re-hides them from everyone else who lacks permission.
     * Velocity sometimes finishes populating the default TabList entries slightly after the event
     * fires, so this runs once immediately and again on a short delay to catch that race.
     *
     * The backend server they just joined also gets a fresh snapshot, since it may never have
     * been told about any of the currently vanished players.
     */
    public void handleServerSwitch(Player player) {
        resync(player);
        proxyServer.getScheduler().buildTask(pluginInstance, () -> resync(player))
                .delay(TAB_LIST_RESYNC_DELAY_MS, TimeUnit.MILLISECONDS)
                .schedule();
    }

    /**
     * A vanished player leaving changes nothing a backend needs to be told about, so nothing is
     * broadcast here. They are still vanished, and their server is about to lose them anyway.
     *
     * Sending a snapshot at this point is exactly what used to expose them: it would have to
     * either still list them, which is what happens now by simply not sending, or leave them out
     * and have their server reveal them for the instant before the disconnect lands.
     *
     * Their name is kept so the snapshot can still carry them while they are away, which is what
     * lets their own server hide them the moment they rejoin instead of after a round trip.
     */
    public void handleDisconnect(Player player) {
        if (isVanished(player.getUniqueId())) {
            rememberName(player);
        }
    }

    private void resync(Player player) {
        if (proxyServer.getPlayer(player.getUniqueId()).isEmpty()) {
            return;
        }
        applyTabListForViewer(player);
        if (isVanished(player.getUniqueId())) {
            rememberName(player);
            syncTabListForAllViewers(player, true);
        }
        broadcastChannel.sendSnapshotToServerOf(player, vanishedSnapshot());
    }

    public void applyTabListForViewer(Player viewer) {
        if (viewer.hasPermission(seeVanishedPermission)) {
            return;
        }
        TabList tabList = viewer.getTabList();
        for (UUID vanishedUuid : vanishedPlayers) {
            if (viewer.getUniqueId().equals(vanishedUuid)) {
                continue;
            }
            if (tabList.getEntry(vanishedUuid).isPresent()) {
                tabList.removeEntry(vanishedUuid);
            }
        }
    }

    private static boolean onSameServer(Player one, Player other) {
        return one.getCurrentServer().isPresent() && other.getCurrentServer().isPresent()
                && one.getCurrentServer().get().getServerInfo().equals(other.getCurrentServer().get().getServerInfo());
    }

    private void syncTabListForAllViewers(Player target, boolean vanished) {
        for (Player viewer : proxyServer.getAllPlayers()) {
            if (viewer.getUniqueId().equals(target.getUniqueId())) {
                continue;
            }
            if (viewer.hasPermission(seeVanishedPermission)) {
                continue;
            }
            TabList tabList = viewer.getTabList();
            if (vanished) {
                if (tabList.getEntry(target.getUniqueId()).isPresent()) {
                    tabList.removeEntry(target.getUniqueId());
                }
            } else if (onSameServer(viewer, target) && tabList.getEntry(target.getUniqueId()).isEmpty()) {
                // Only for viewers on the same server: a tab list shows the players of its own
                // server, and adding the entry everywhere would put them in every tab list on
                // the network. Their own server usually re-adds it first when it shows them.
                tabList.addEntry(TabListEntry.builder()
                        .tabList(tabList)
                        .profile(target.getGameProfile())
                        .displayName(Component.text(target.getUsername()))
                        .latency((int) target.getPing())
                        .gameMode(0)
                        .build());
            }
        }
    }
}
