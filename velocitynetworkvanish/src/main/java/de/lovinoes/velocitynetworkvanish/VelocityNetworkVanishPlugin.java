package de.lovinoes.velocitynetworkvanish;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.velocitynetworkutilities.api.VelocityNetworkAPI;
import de.lovinoes.velocitynetworkutilities.util.ChannelGuard;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import de.lovinoes.networkutilitiescommon.config.Language;
import de.lovinoes.networkutilitiescommon.config.YamlConfig;
import de.lovinoes.networkutilitiescommon.sound.ConfiguredSound;
import de.lovinoes.networkutilitiescommon.vanish.NetworkVanishDao;
import de.lovinoes.networkutilitiescommon.vanish.VanishPermissions;
import de.lovinoes.velocitynetworkvanish.command.VanishCommand;
import de.lovinoes.velocitynetworkvanish.listener.VanishListener;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@Plugin(
        id = "velocitynetworkvanish",
        name = "VelocityNetworkVanish",
        version = "1.0.0",
        description = "Network-wide vanish.",
        url = "https://lovinoes.de",
        authors = {"Lovinoes"},
        dependencies = {@Dependency(id = "velocitynetworkutilities")}
)
public final class VelocityNetworkVanishPlugin {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private VanishBroadcastChannel broadcastChannel;

    @Inject
    public VelocityNetworkVanishPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        YamlConfig config = YamlConfig.load(dataDirectory, "config.yml", getClass().getClassLoader());
        // Messages come from the language file chosen in config.yml; config.yml holds settings.
        YamlConfig messages = Language.load(dataDirectory, config, getClass().getClassLoader());

        String table = config.getString("table.name", "network_vanish_state");
        String vanishPermission = config.getString("permission.vanish", "velocitynetworkvanish.vanish");
        String seeVanishedPermission = config.getString("permission.see-vanished", VanishPermissions.SEE_VANISHED);
        // Shared, so chat, moderation and player info follow this setting instead of the default.
        VelocityNetworkAPI.get().setSeeVanishedPermission(seeVanishedPermission);
        Set<String> protectedCommands = new HashSet<>(config.getStringList("protected-commands"));
        String enabledMessage = messages.getString("messages.vanish-enabled", "");
        String disabledMessage = messages.getString("messages.vanish-disabled", "");

        String broadcastChannelName = config.getString("broadcast-channel", "network:vanish");
        broadcastChannel = new VanishBroadcastChannel(proxyServer, logger, broadcastChannelName);
        broadcastChannel.start();
        // Without this a client could write to the channel itself and the proxy would pass it to
        // the backend as though it had come from here. See ChannelGuard.
        proxyServer.getEventManager().register(this,
                new ChannelGuard(MinecraftChannelIdentifier.from(broadcastChannelName)));

        NetworkVanishDao vanishDao = new NetworkVanishDao(VelocityNetworkAPI.get().database(), table);
        VanishManager vanishManager = new VanishManager(proxyServer, this, proxyServer.getEventManager(), vanishDao,
                broadcastChannel, seeVanishedPermission,
                messages.getString("messages.fake-join", ""),
                messages.getString("messages.fake-leave", ""),
                ConfiguredSound.fromConfig(config, "sound.vanish",
                        "minecraft:block.amethyst_block.break", "MASTER", 0.35, 1.5),
                ConfiguredSound.fromConfig(config, "sound.unvanish",
                        "minecraft:block.amethyst_block.chime", "MASTER", 0.35, 1.2));
        vanishManager.loadPersistedState();

        proxyServer.getEventManager().register(this, new VanishListener(proxyServer, vanishManager, protectedCommands,
                seeVanishedPermission, messages.getString("messages.target-not-found", "")));

        CommandManager commandManager = proxyServer.getCommandManager();
        CommandMeta vanishMeta = commandManager.metaBuilder("vanish").aliases("v").plugin(this).build();
        commandManager.register(vanishMeta,
                new VanishCommand(vanishManager, vanishPermission, enabledMessage, disabledMessage, false,
                        messages.getString("messages.players-only", "")));

        // Same toggle, but announces nothing.
        CommandMeta silentVanishMeta = commandManager.metaBuilder("silentvanish")
                .aliases("svanish", "sv")
                .plugin(this)
                .build();
        commandManager.register(silentVanishMeta, new VanishCommand(vanishManager, vanishPermission,
                messages.getString("messages.silent-vanish-enabled", ""),
                messages.getString("messages.silent-vanish-disabled", ""),
                true, messages.getString("messages.players-only", "")));

        logger.info("VelocityNetworkVanish initialized.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (broadcastChannel != null) {
            broadcastChannel.stop();
        }
    }
}
