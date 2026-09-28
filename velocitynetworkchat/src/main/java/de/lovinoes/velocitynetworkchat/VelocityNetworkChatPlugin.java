package de.lovinoes.velocitynetworkchat;

import com.google.inject.Inject;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.velocitynetworkchat.command.ChannelCommand;
import de.lovinoes.velocitynetworkchat.command.MentionSoundCommand;
import de.lovinoes.velocitynetworkchat.command.PlayerActionsCommand;
import de.lovinoes.velocitynetworkchat.command.MessageCommand;
import de.lovinoes.velocitynetworkchat.command.ReplyCommand;
import de.lovinoes.velocitynetworkchat.command.SwitchChannelCommand;
import de.lovinoes.velocitynetworkchat.listener.ChatListener;
import de.lovinoes.velocitynetworkchat.listener.SessionCleanupListener;
import de.lovinoes.velocitynetworkchat.backend.BackendBridge;
import de.lovinoes.velocitynetworkchat.backend.Protocol;
import de.lovinoes.velocitynetworkchat.showcase.ShowcaseRenderer;
import de.lovinoes.velocitynetworkchat.showcase.ShowcaseService;
import de.lovinoes.networkutilitiescommon.chat.ChatSettingsDao;
import de.lovinoes.networkutilitiescommon.config.Language;
import de.lovinoes.networkutilitiescommon.config.YamlConfig;
import de.lovinoes.networkutilitiescommon.sound.ConfiguredSound;
import de.lovinoes.networkutilitiescommon.vanish.VanishPermissions;
import de.lovinoes.velocitynetworkutilities.api.VelocityNetworkAPI;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;

@Plugin(
        id = "velocitynetworkchat",
        name = "VelocityNetworkChat",
        version = "1.0.0",
        authors = {"Lovinoes"},
        dependencies = {
                @Dependency(id = "velocitynetworkutilities"),
                @Dependency(id = "velocitynetworkvanish", optional = true)
        }
)
public final class VelocityNetworkChatPlugin {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private BackendBridge bridge;

    @Inject
    public VelocityNetworkChatPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        YamlConfig config = YamlConfig.load(dataDirectory, "config.yml", getClass().getClassLoader());
        // Wording comes from the language file chosen in config.yml; config.yml holds layout.
        YamlConfig messages = Language.load(dataDirectory, config, getClass().getClassLoader());

        ChannelManager channelManager = new ChannelManager(config, messages);
        String vaultFallback = config.getString("vault.prefix-placeholder-fallback", "");
        VaultDataCache vaultDataCache = new VaultDataCache(VelocityNetworkAPI.get().vaultCache(), vaultFallback);
        String colorPermission = config.getString("chat.color-permission", "velocitynetworkchat.color");
        String seeVanishedPermission = VanishPermissions.SEE_VANISHED;

        // The bridge is also the channel's only listener: it answers nothing a client sends on it
        // and passes nothing through, which is what ChannelGuard does for the other channels.
        bridge = new BackendBridge(proxyServer, logger,
                config.getString("messaging.channel", "network:chat"),
                Math.max(100, config.getLong("messaging.backend-timeout-ms", 1000)));
        bridge.start();
        proxyServer.getEventManager().register(this, bridge);
        SenderQueue senderQueue = new SenderQueue();
        AntiSpam antiSpam = new AntiSpam(
                config.getLong("anti-spam.cooldown-ms", 1500),
                config.getLong("anti-spam.repeat-window-ms", 10000),
                config.getBoolean("anti-spam.private-messages", true),
                config.getString("anti-spam.bypass-permission", "velocitynetworkchat.antispam.bypass"),
                messages, System::currentTimeMillis);

        PlaceholderResolver placeholderResolver = new PlaceholderResolver(logger);

        ChatSettingsDao chatSettingsDao = new ChatSettingsDao(VelocityNetworkAPI.get().database(),
                config.getString("mentions.table", "chat_settings"));
        MentionSettings mentionSettings = new MentionSettings(chatSettingsDao);
        mentionSettings.loadPersistedState();

        MentionService mentionService = new MentionService(
                proxyServer,
                mentionSettings,
                messages.getString("mentions.highlight-format", "<yellow>@<name></yellow>"),
                ConfiguredSound.fromConfig(config, "mentions.sound",
                        "minecraft:block.note_block.pling", "MASTER", 0.4, 1.2));

        PlayerActions playerActions = new PlayerActions(
                config.getBoolean("player-actions.enabled", true),
                config.getString("player-actions.command", "chatactions"),
                messages.getString("player-actions.name-hover", ""),
                messages.getString("player-actions.line-format", "<actions>"),
                config.getString("player-actions.separator", " "),
                PlayerActions.readActions(config.get("player-actions.actions"),
                        messages.get("player-actions.actions"),
                        System.getLogger(PlayerActions.class.getName())));

        ShowcaseRenderer showcaseRenderer = new ShowcaseRenderer(messages,
                ShowcaseRenderer.click(config, "showcase.item.click"),
                ShowcaseRenderer.click(config, "showcase.inventory.click"),
                ShowcaseRenderer.click(config, "showcase.position.click"),
                config.getInt("showcase.inventory.max-entries", 41));
        ShowcaseService showcaseService = new ShowcaseService(bridge, showcaseRenderer, messages,
                config.getBoolean("showcase.enabled", true),
                config.getLong("showcase.cooldown-ms", 3000),
                config.getString("showcase.cooldown-bypass-permission", "velocitynetworkchat.showcase.bypasscooldown"),
                config.getInt("showcase.max-per-message", 3),
                List.of(
                        showcaseKind(config, "item", Protocol.ITEM),
                        showcaseKind(config, "inventory", Protocol.INVENTORY),
                        showcaseKind(config, "position", Protocol.POSITION)));

        proxyServer.getEventManager().register(this, new ChatListener(proxyServer, channelManager, vaultDataCache,
                bridge, mentionService, showcaseService, placeholderResolver, senderQueue, colorPermission,
                messages.getString("no-channel-permission", ""),
                playerActions, antiSpam));

        CommandManager commandManager = proxyServer.getCommandManager();

        if (playerActions.isEnabled()) {
            CommandMeta actionsMeta = commandManager.metaBuilder(playerActions.commandName())
                    .plugin(this)
                    .build();
            commandManager.register(actionsMeta, new PlayerActionsCommand(playerActions));
        }

        CommandMeta mentionSoundMeta = commandManager.metaBuilder("mentionsound")
                .aliases("pingsound", "pingtoggle")
                .plugin(this)
                .build();
        commandManager.register(mentionSoundMeta, new MentionSoundCommand(mentionSettings,
                messages.getString("mentions.messages.sound-enabled", ""),
                messages.getString("mentions.messages.sound-disabled", ""),
                messages.getString("commands.players-only", "")));

        channelManager.channels().values().forEach(channel -> {
            if (channel.commandTriggers().isEmpty()) {
                return;
            }
            String primary = channel.commandTriggers().get(0);
            String[] aliases = channel.commandTriggers().subList(1, channel.commandTriggers().size()).toArray(new String[0]);
            CommandMeta meta = commandManager.metaBuilder(primary).aliases(aliases).plugin(this).build();
            commandManager.register(meta, new ChannelCommand(channelManager, channel.key(), messages));
        });

        CommandMeta channelSwitchMeta = commandManager.metaBuilder("ch").aliases("channel").plugin(this).build();
        commandManager.register(channelSwitchMeta, new SwitchChannelCommand(channelManager, messages));

        String messagePermission = config.getString("private-message.permission", "velocitynetworkchat.msg");
        PrivateMessageManager privateMessageManager = new PrivateMessageManager(
                proxyServer,
                seeVanishedPermission,
                colorPermission,
                messages.getString("private-message.outgoing-format", ""),
                messages.getString("private-message.incoming-format", ""),
                messages.getString("private-message.not-found-format", ""),
                messages.getString("private-message.no-reply-target-format", ""),
                mentionService, showcaseService, senderQueue, antiSpam);

        LiteralCommandNode<CommandSource> msgNode = MessageCommand.create(proxyServer, privateMessageManager, messagePermission, seeVanishedPermission,
                messages.getString("commands.players-only", ""));
        BrigadierCommand msgCommand = new BrigadierCommand(msgNode);
        CommandMeta msgMeta = commandManager.metaBuilder(msgCommand)
                .aliases("tell", "w", "whisper", "pm", "m")
                .plugin(this)
                .build();
        commandManager.register(msgMeta, msgCommand);

        CommandMeta replyMeta = commandManager.metaBuilder("r").aliases("reply").plugin(this).build();
        commandManager.register(replyMeta, new ReplyCommand(privateMessageManager, messagePermission, messages));

        proxyServer.getEventManager().register(this, new SessionCleanupListener(channelManager, privateMessageManager,
                showcaseService, senderQueue, antiSpam));

        logger.info("VelocityNetworkChat initialized with {} channels.", channelManager.channels().size());
    }

    /**
     * A showcase kind from config.yml: whether it is on, the tokens that ask for it, and who may
     * use it. An empty permission means everyone.
     */
    private static ShowcaseService.Kind showcaseKind(YamlConfig config, String key, int part) {
        String path = "showcase." + key;
        return new ShowcaseService.Kind(part,
                config.getBoolean(path + ".enabled", true),
                config.getStringList(path + ".tokens"),
                config.getString(path + ".permission", ""));
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (bridge != null) {
            bridge.stop();
        }
    }
}
