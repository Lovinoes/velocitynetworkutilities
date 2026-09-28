package de.lovinoes.velocitynetworkmoderation.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import de.lovinoes.velocitynetworkmoderation.Messages;
import de.lovinoes.velocitynetworkmoderation.punishment.Punishment;
import de.lovinoes.velocitynetworkmoderation.punishment.PunishmentManager;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Stops a muted player talking, in chat and through the commands that are really chat.
 *
 * Priority matters here and is not decoration. Velocity sorts handlers by priority descending,
 * so a larger number runs earlier, and VelocityNetworkChat's own listener sits at 100. This runs
 * above it so the message is stopped before that listener formats and broadcasts it. That
 * listener in turn skips anything already denied, so the two agree without either depending on
 * the other being installed.
 */
public final class MuteListener {

    /** Above VelocityNetworkChat's 100, so a muted message never reaches formatting. */
    public static final short PRIORITY = 200;

    private final PunishmentManager punishments;
    private final Messages messages;
    private final Set<String> blockedCommands;

    public MuteListener(PunishmentManager punishments, Messages messages, List<String> blockedCommands) {
        this.punishments = punishments;
        this.messages = messages;
        this.blockedCommands = Set.copyOf(blockedCommands.stream()
                .map(command -> command.toLowerCase(Locale.ROOT))
                .toList());
    }

    @SuppressWarnings("deprecation")
    @Subscribe(priority = PRIORITY)
    public void onChat(PlayerChatEvent event) {
        Optional<Punishment> mute = punishments.muteFor(event.getPlayer().getUniqueId());
        if (mute.isEmpty()) {
            return;
        }
        // Denying a signed chat message is only safe because SignedVelocity is a hard
        // requirement of this suite, exactly as it is for VelocityNetworkChat.
        event.setResult(PlayerChatEvent.ChatResult.denied());
        event.getPlayer().sendMessage(
                messages.screen("screens.mute", messages.placeholders(mute.get(), System.currentTimeMillis())));
    }

    /**
     * A mute that only covers chat is barely a mute: /msg, /r and any other talking command
     * remain wide open. Which commands count is config, because only the operator knows what
     * else is installed.
     */
    @Subscribe(priority = PRIORITY)
    public void onCommand(CommandExecuteEvent event) {
        if (blockedCommands.isEmpty() || !(event.getCommandSource() instanceof Player player)) {
            return;
        }
        if (!blockedCommands.contains(rootCommand(event.getCommand()))) {
            return;
        }
        Optional<Punishment> mute = punishments.muteFor(player.getUniqueId());
        if (mute.isEmpty()) {
            return;
        }
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        player.sendMessage(
                messages.screen("screens.mute", messages.placeholders(mute.get(), System.currentTimeMillis())));
    }

    /**
     * getCommand() is the command without its leading slash, arguments and all. Only the first
     * word identifies it, and it is lowercased so the config does not have to guess at casing.
     *
     * A namespace is dropped: "/minecraft:me hi" is /me, and without this a muted player could
     * talk through any listed command just by putting "minecraft:" in front of it.
     */
    static String rootCommand(String command) {
        String root = typedRoot(command);
        int colon = root.lastIndexOf(':');
        return colon < 0 ? root : root.substring(colon + 1);
    }

    /** The first word exactly as typed apart from case, namespace and all. */
    static String typedRoot(String command) {
        String trimmed = command.stripLeading();
        int space = trimmed.indexOf(' ');
        String root = space < 0 ? trimmed : trimmed.substring(0, space);
        return root.toLowerCase(Locale.ROOT);
    }
}
