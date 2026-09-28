package de.lovinoes.velocitynetworkvanish.event;

import com.velocitypowered.api.proxy.Player;

public final class PlayerVanishStateChangeEvent {

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
}
