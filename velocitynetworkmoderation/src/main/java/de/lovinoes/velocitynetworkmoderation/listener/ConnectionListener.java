package de.lovinoes.velocitynetworkmoderation.listener;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import de.lovinoes.velocitynetworkmoderation.Messages;
import de.lovinoes.velocitynetworkmoderation.punishment.Punishment;
import de.lovinoes.velocitynetworkmoderation.punishment.Addresses;
import de.lovinoes.velocitynetworkmoderation.punishment.PunishmentManager;

import java.util.Optional;

/**
 * Turns bans away at the door, and loads a player's mute while they connect.
 *
 * LoginEvent is the right place: the player has been authenticated, so their real UUID is known,
 * and denying it shows them the reason rather than dropping the connection. Velocity runs event
 * handlers asynchronously by default, so the database lookups here do not hold up the netty
 * thread, and doing them inline means a ban placed anywhere is in force on the very next login
 * with nothing to invalidate.
 */
public final class ConnectionListener {

    private final PunishmentManager punishments;
    private final Messages messages;
    private final System.Logger logger;

    public ConnectionListener(PunishmentManager punishments, Messages messages, System.Logger logger) {
        this.punishments = punishments;
        this.messages = messages;
        this.logger = logger;
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        String address = Addresses.of(player);
        long now = System.currentTimeMillis();

        try {
            Optional<Punishment> ban = punishments.findActiveBan(player.getUniqueId()).join();
            if (ban.isPresent()) {
                deny(event, ban.get(), "screens.ban", now);
                return;
            }

            Optional<Punishment> ipBan = punishments.findActiveIpBan(address).join();
            if (ipBan.isPresent()) {
                deny(event, ipBan.get(), "screens.ip-ban", now);
                return;
            }

            punishments.dao()
                    .recordAddress(player.getUniqueId(), player.getUsername(), address, now)
                    .join();
            punishments.loadMute(player.getUniqueId()).join();
        } catch (RuntimeException e) {
            // The database being unreachable must not lock the whole network out. Letting the
            // player in unpunished is the lesser failure: a ban that briefly does not apply is
            // recoverable, a server nobody can join is not. It is logged loudly either way.
            logger.log(System.Logger.Level.ERROR,
                    "Could not check punishments for " + player.getUsername()
                            + "; allowing the connection. Bans are NOT being enforced until this is fixed.", e);
        }
    }

    private void deny(LoginEvent event, Punishment punishment, String screenPath, long now) {
        event.setResult(ResultedEvent.ComponentResult.denied(
                messages.screen(screenPath, messages.placeholders(punishment, now))));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        punishments.forget(event.getPlayer().getUniqueId());
    }
}
