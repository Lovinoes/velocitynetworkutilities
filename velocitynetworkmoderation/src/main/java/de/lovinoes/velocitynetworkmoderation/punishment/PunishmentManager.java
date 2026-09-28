package de.lovinoes.velocitynetworkmoderation.punishment;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.networkutilitiescommon.messaging.MessageListener;
import de.lovinoes.velocitynetworkmoderation.Messages;
import de.lovinoes.velocitynetworkutilities.api.VelocityNetworkAPI;
import net.kyori.adventure.text.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place punishments are created, lifted and enforced.
 *
 * Bans are read straight from the database on every login. That is one indexed query per
 * connection, and in exchange a ban placed on another proxy, or written by hand, is in force
 * immediately with no cache to go stale.
 *
 * Mutes cannot afford that: they are consulted on every chat message. So the mute in force
 * against each ONLINE player is held in memory, loaded once during their login and dropped when
 * they disconnect, which bounds it by the player count. Changes made on another proxy would not
 * be seen, so every mute and unmute is also published on the shared messaging channel and the
 * receiving proxy refreshes just that player.
 */
public final class PunishmentManager {

    private static final String MUTE_TOPIC = "moderation:mute";

    private final ProxyServer proxyServer;
    private final PunishmentDao dao;
    private final Messages messages;
    private final String exemptPermission;
    private final System.Logger logger;

    /** Mute in force per online player. Absent means "not loaded or not muted". */
    private final Map<UUID, Punishment> activeMutes = new ConcurrentHashMap<>();

    private final MessageListener muteListener = (topic, payload) ->
            refreshMute(new String(payload, StandardCharsets.UTF_8));

    public PunishmentManager(ProxyServer proxyServer, PunishmentDao dao, Messages messages,
                              String exemptPermission, System.Logger logger) {
        this.proxyServer = proxyServer;
        this.dao = dao;
        this.messages = messages;
        this.exemptPermission = exemptPermission;
        this.logger = logger;
    }

    public void start() {
        VelocityNetworkAPI.get().messaging().subscribe(MUTE_TOPIC, muteListener);
    }

    public void stop() {
        VelocityNetworkAPI.get().messaging().unsubscribe(MUTE_TOPIC, muteListener);
    }

    public PunishmentDao dao() {
        return dao;
    }

    /**
     * Someone with the exempt permission cannot be punished at all.
     *
     * Checked against the online player rather than the name, so it only protects people who are
     * actually connected. That is the honest limit of it: an offline player's permissions are
     * not knowable from the proxy.
     */
    public boolean isExempt(UUID victim) {
        return proxyServer.getPlayer(victim)
                .map(player -> player.hasPermission(exemptPermission))
                .orElse(false);
    }

    public CompletableFuture<Punishment> punish(PunishmentType type, UUID victimUuid, String victimName,
                                                 String victimAddress, CommandSource operator, String reason,
                                                 long durationMillis) {
        long now = System.currentTimeMillis();
        long expiresAt = durationMillis == Punishment.PERMANENT
                ? Punishment.PERMANENT
                : saturatingExpiry(now, durationMillis);

        Punishment punishment = new Punishment(0, type, victimUuid, victimName, victimAddress,
                operatorUuid(operator), operatorName(operator), reason, now, expiresAt,
                type.isRevocable(), null, 0);

        return dao.insert(punishment).thenApply(stored -> {
            if (stored.type() == PunishmentType.MUTE && victimUuid != null) {
                activeMutes.put(victimUuid, stored);
                publishMuteChange(victimUuid);
            }
            return stored;
        });
    }

    /**
     * An expiry far enough in the future to overflow a long is treated as permanent rather than
     * wrapping into the past, which would expire the punishment the instant it was made.
     */
    private static long saturatingExpiry(long now, long durationMillis) {
        long expiry = now + durationMillis;
        return expiry < now ? Punishment.PERMANENT : expiry;
    }

    public CompletableFuture<Optional<Punishment>> revoke(PunishmentType type, UUID victim, CommandSource operator) {
        return dao.revoke(type, victim, operatorName(operator), System.currentTimeMillis())
                .thenApply(lifted -> {
                    if (type == PunishmentType.MUTE) {
                        activeMutes.remove(victim);
                        publishMuteChange(victim);
                    }
                    return lifted;
                });
    }

    public CompletableFuture<Optional<Punishment>> revokeIpBan(String address, CommandSource operator) {
        return dao.revokeIpBan(address, operatorName(operator), System.currentTimeMillis());
    }

    public CompletableFuture<Optional<Punishment>> findActiveBan(UUID victim) {
        return dao.findActive(PunishmentType.BAN, victim, System.currentTimeMillis());
    }

    public CompletableFuture<Optional<Punishment>> findActiveIpBan(String address) {
        return dao.findActiveIpBan(address, System.currentTimeMillis());
    }

    public CompletableFuture<List<Punishment>> history(UUID victim, int limit) {
        return dao.history(victim, limit);
    }

    /** Loads a player's mute into memory. Called during login, where a query is already due. */
    public CompletableFuture<Void> loadMute(UUID victim) {
        return dao.findActive(PunishmentType.MUTE, victim, System.currentTimeMillis())
                .thenAccept(found -> found.ifPresentOrElse(
                        mute -> activeMutes.put(victim, mute),
                        () -> activeMutes.remove(victim)));
    }

    public void forget(UUID victim) {
        activeMutes.remove(victim);
    }

    /**
     * The mute stopping this player from talking, if any.
     *
     * Expiry is re-checked on every call, so a mute that runs out while the player is connected
     * stops applying at the right moment rather than at their next login.
     */
    public Optional<Punishment> muteFor(UUID victim) {
        Punishment mute = activeMutes.get(victim);
        if (mute == null) {
            return Optional.empty();
        }
        if (!mute.isCurrentlyInForce(System.currentTimeMillis())) {
            activeMutes.remove(victim);
            return Optional.empty();
        }
        return Optional.of(mute);
    }

    /** Disconnects a player who has just been banned, with the same screen a login would show. */
    public void enforceBan(Punishment ban, Component screen) {
        if (ban.victimUuid() == null) {
            return;
        }
        proxyServer.getPlayer(ban.victimUuid()).ifPresent(player -> player.disconnect(screen));
    }

    /**
     * @param permissionRequired when false an empty permission means everyone, which is what an
     *                           ordinary public announcement wants. When true an empty
     *                           permission means nobody, so a silent punishment with no
     *                           see-silent permission configured reaches no player at all.
     *                           The console is told either way, because it is the audit log.
     */
    public void broadcast(Component message, String permission, boolean permissionRequired) {
        for (Player player : proxyServer.getAllPlayers()) {
            boolean visible = permissionRequired
                    ? !permission.isEmpty() && player.hasPermission(permission)
                    : permission.isEmpty() || player.hasPermission(permission);
            if (visible) {
                player.sendMessage(message);
            }
        }
        proxyServer.getConsoleCommandSource().sendMessage(message);
    }

    private void publishMuteChange(UUID victim) {
        VelocityNetworkAPI.get().messaging()
                .publish(MUTE_TOPIC, victim.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Another proxy changed someone's mute. Only players connected here matter, and only their
     * own entry is reloaded.
     */
    private void refreshMute(String rawUuid) {
        UUID victim;
        try {
            victim = UUID.fromString(rawUuid.trim());
        } catch (IllegalArgumentException e) {
            logger.log(System.Logger.Level.WARNING, "Ignoring a malformed mute update: {0}", rawUuid);
            return;
        }
        if (proxyServer.getPlayer(victim).isEmpty()) {
            activeMutes.remove(victim);
            return;
        }
        loadMute(victim).exceptionally(throwable -> {
            logger.log(System.Logger.Level.WARNING,
                    "Could not refresh the mute for " + victim + " after an update from another proxy.", throwable);
            return null;
        });
    }

    public String operatorName(CommandSource source) {
        return source instanceof Player player ? player.getUsername() : messages.consoleName();
    }

    public UUID operatorUuid(CommandSource source) {
        return source instanceof Player player ? player.getUniqueId() : null;
    }
}
