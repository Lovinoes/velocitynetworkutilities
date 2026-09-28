package de.lovinoes.velocitynetworkvanish.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.velocitynetworkvanish.VanishManager;
import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Only intercepts commands implemented by external plugins that have no vanish awareness of
 * their own (tpa/tpahere, e.g. HuskHomes). /msg, /tell, /w, /r and their aliases are NOT
 * handled here: they're native Velocity commands registered directly by VelocityNetworkChat,
 * which does its own vanish check before delivering. Denying a native Minecraft command with
 * signable arguments (which /msg, /tell and /w are, as vanilla signed-chat commands) via
 * CommandExecuteEvent disconnects the player with "tried to deny a command with signable
 * component(s)" - registering the command directly instead of intercepting it is what avoids
 * that crash, since the client never signs an argument tree the proxy has overridden.
 */
public final class VanishListener {

    private final ProxyServer proxyServer;
    private final VanishManager vanishManager;
    private final Set<String> protectedCommands;
    private final String seeVanishedPermission;
    private final String notFoundMessage;

    public VanishListener(ProxyServer proxyServer, VanishManager vanishManager, Set<String> protectedCommands,
                          String seeVanishedPermission, String notFoundMessage) {
        this.proxyServer = proxyServer;
        this.vanishManager = vanishManager;
        this.protectedCommands = protectedCommands.stream()
                .map(command -> command.strip().toLowerCase(Locale.ROOT))
                .map(command -> command.startsWith("/") ? command.substring(1) : command)
                .collect(Collectors.toUnmodifiableSet());
        this.seeVanishedPermission = seeVanishedPermission;
        this.notFoundMessage = notFoundMessage;
    }

    // Negative priority so this runs late, after Velocity has populated the tab list.
    @Subscribe(priority = -100)
    public void onPostLogin(PostLoginEvent event) {
        vanishManager.handleServerSwitch(event.getPlayer());
    }

    @Subscribe(priority = -100)
    public void onServerConnected(ServerConnectedEvent event) {
        vanishManager.handleServerSwitch(event.getPlayer());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        vanishManager.handleDisconnect(event.getPlayer());
    }

    /**
     * Velocity only fires this event for clients on 1.12.2 and older; modern clients complete
     * command arguments through Brigadier, which the proxy has no filtering hook for. Anything
     * on a current client version is filtered by PaperNetworkVanish on the backend instead,
     * where the suggestions are actually produced. This stays for legacy clients only.
     */
    @Subscribe
    public void onTabComplete(TabCompleteEvent event) {
        Player source = event.getPlayer();
        if (source.hasPermission(seeVanishedPermission)) {
            return;
        }
        List<String> suggestions = event.getSuggestions();
        suggestions.removeIf(suggestion -> proxyServer.getPlayer(suggestion)
                .map(target -> vanishManager.isVanished(target.getUniqueId()))
                .orElse(false));
    }

    @Subscribe
    public void onCommandExecute(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player source)) {
            return;
        }
        // Split on any run of spaces: "/tpa  Name" with two spaces must not get past this with an
        // empty first argument while the backend happily reads "Name".
        String[] parts = event.getCommand().strip().split(" +");
        String label = parts[0].toLowerCase(Locale.ROOT);
        // "/huskhomes:tpa Name" is /tpa too. Without dropping the namespace, typing it would
        // walk straight past the protection and find a vanished player.
        int colon = label.lastIndexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }

        if (!protectedCommands.contains(label) || parts.length < 2) {
            return;
        }
        String targetName = parts[1];
        proxyServer.getPlayer(targetName).ifPresent(target -> {
            if (vanishManager.isVanished(target.getUniqueId()) && !source.hasPermission(seeVanishedPermission)) {
                event.setResult(CommandExecuteEvent.CommandResult.denied());
                if (!notFoundMessage.isBlank()) {
                    source.sendMessage(ChatColorParser.parse(notFoundMessage,
                            Placeholder.unparsed("target", targetName)));
                }
            }
        });
    }
}
