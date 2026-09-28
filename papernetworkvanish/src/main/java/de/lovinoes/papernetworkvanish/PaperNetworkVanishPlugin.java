package de.lovinoes.papernetworkvanish;

import org.bukkit.plugin.java.JavaPlugin;

public final class PaperNetworkVanishPlugin extends JavaPlugin {

    /** Kept in step with the permission declared in paper-plugin.yml. */
    private static final String DECLARED_SEE_PERMISSION = "velocitynetworkvanish.see";

    private String channel;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.channel = getConfig().getString("channel", "network:vanish");
        String seeVanishedPermission = getConfig().getString("see-vanished-permission", DECLARED_SEE_PERMISSION);

        if (!DECLARED_SEE_PERMISSION.equals(seeVanishedPermission)) {
            getLogger().warning("see-vanished-permission is set to '" + seeVanishedPermission
                    + "', but '" + DECLARED_SEE_PERMISSION + "' is the node declared in paper-plugin.yml with a false"
                    + " default. An undeclared Bukkit permission defaults to OP, so unless your permissions"
                    + " plugin defines '" + seeVanishedPermission + "' with a false default, every operator on"
                    + " this server will be able to see vanished players.");
        }

        VanishStateListener listener = new VanishStateListener(this, seeVanishedPermission);
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, channel, listener);

        getLogger().info("PaperNetworkVanish initialized, listening on '" + channel
                + "', see-vanished permission '" + seeVanishedPermission + "'.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this, channel);
    }
}
