package de.lovinoes.velocitynetworkplayerinfo;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.velocitynetworkplayerinfo.command.PlayerInfoCommand;
import de.lovinoes.velocitynetworkplayerinfo.listener.JoinLeaveListener;
import de.lovinoes.networkutilitiescommon.config.Language;
import de.lovinoes.networkutilitiescommon.config.YamlConfig;
import de.lovinoes.velocitynetworkutilities.api.VelocityNetworkAPI;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;

@Plugin(
        id = "velocitynetworkplayerinfo",
        name = "VelocityNetworkPlayerInfo",
        version = "1.0.0",
        description = "Network-wide join and leave messages and player lookup.",
        url = "https://lovinoes.de",
        authors = {"Lovinoes"},
        dependencies = {
                @Dependency(id = "velocitynetworkutilities"),
                @Dependency(id = "playeruuidcachevelocity"),
                @Dependency(id = "velocitynetworkvanish", optional = true)
        }
)
public final class VelocityNetworkPlayerInfoPlugin {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;

    @Inject
    public VelocityNetworkPlayerInfoPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        YamlConfig config = YamlConfig.load(dataDirectory, "config.yml", getClass().getClassLoader());
        // Every message comes from the language file chosen in config.yml. config.yml itself
        // holds only settings, so switching language is one line rather than a rewrite.
        YamlConfig messages = Language.load(dataDirectory, config, getClass().getClassLoader());

        String joinFormat = messages.getString("broadcast.join", "");
        String leaveFormat = messages.getString("broadcast.leave", "");
        proxyServer.getEventManager().register(this, new JoinLeaveListener(proxyServer, joinFormat, leaveFormat, VelocityNetworkAPI.get().seeVanishedPermission()));

        String usePermission = config.getString("permission.use", "velocitynetworkplayerinfo.use");
        String datePattern = config.getString("date-format", "dd.MM.yyyy HH:mm 'Uhr'");
        String notFoundLine = messages.getString("pinfo.not-found", "");
        String onlineFormat = messages.getString("pinfo.status.online", "");
        String offlineFormat = messages.getString("pinfo.status.offline", "");
        String copyUuidTooltip = messages.getString("pinfo.copy-uuid-tooltip", "");

        List<String> lines = messages.getStringList("pinfo.lines");
        if (lines.isEmpty()) {
            // An empty list would print nothing at all, which looks exactly like the command
            // being broken. Say what happened and show something useful meanwhile.
            lines = List.of("<#C1C1C1><player></#C1C1C1>", "<status>");
            logger.warn("The language file has no 'pinfo.lines' list, so /pinfo is showing a bare "
                    + "minimum. Add a 'pinfo.lines' list to it to choose what the command prints.");
        }

        PlayerInfoCommand command = new PlayerInfoCommand(proxyServer, usePermission, VelocityNetworkAPI.get().seeVanishedPermission(),
                datePattern, lines, onlineFormat, offlineFormat, notFoundLine,
                messages.getString("pinfo.usage", ""), messages.getString("pinfo.lookup-failed", ""), copyUuidTooltip);

        CommandManager commandManager = proxyServer.getCommandManager();
        CommandMeta pinfoMeta = commandManager.metaBuilder("pinfo")
                .aliases("playerinfo", "seen", "ui", "userinfo")
                .plugin(this)
                .build();
        commandManager.register(pinfoMeta, command);

        logger.info("VelocityNetworkPlayerInfo initialized.");
    }
}
