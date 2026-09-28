package de.lovinoes.playeruuidcachevelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import de.lovinoes.playeruuidcachevelocity.PlayerCacheAPI;

public final class PlayerConnectionListener {

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        var player = event.getPlayer();
        PlayerCacheAPI.get().recordLogin(player.getUniqueId(), player.getUsername());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        PlayerCacheAPI.get().recordLogout(event.getPlayer().getUniqueId());
    }
}
