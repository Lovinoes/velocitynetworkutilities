package de.lovinoes.papernetworkchat;

import org.bukkit.plugin.java.JavaPlugin;

public final class PaperNetworkChatPlugin extends JavaPlugin {

    private String channel;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.channel = getConfig().getString("messaging.channel", "network:chat");

        // Incoming for the proxy's questions, outgoing for the answers. Bukkit refuses to send on
        // a channel that was never registered for outgoing use, so both are needed.
        getServer().getMessenger().registerIncomingPluginChannel(this, channel,
                new ProxyRequestListener(this, channel));
        getServer().getMessenger().registerOutgoingPluginChannel(this, channel);

        getLogger().info("PaperNetworkChat initialized, answering the proxy on '" + channel + "'.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this, channel);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, channel);
    }
}
