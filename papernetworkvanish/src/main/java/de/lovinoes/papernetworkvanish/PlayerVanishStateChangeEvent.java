package de.lovinoes.papernetworkvanish;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on this backend server whenever a player's vanish state changes, after
 * hidePlayer/showPlayer has already been applied to every local viewer. This is the standard
 * Bukkit extensibility point for any other backend plugin on this server that wants to react to
 * vanish state without depending on this plugin directly. There is no way for us to guarantee
 * that a specific third-party plugin (HuskHomes, TAB, ...) listens for it, since that depends
 * entirely on whether that plugin's own maintainers chose to hook into it.
 */
public final class PlayerVanishStateChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final boolean vanished;

    public PlayerVanishStateChangeEvent(Player player, boolean vanished) {
        this.player = player;
        this.vanished = vanished;
    }

    public Player getPlayer() {
        return player;
    }

    public boolean isVanished() {
        return vanished;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
