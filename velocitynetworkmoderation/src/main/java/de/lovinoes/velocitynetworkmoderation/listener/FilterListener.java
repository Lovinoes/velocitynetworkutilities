package de.lovinoes.velocitynetworkmoderation.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.velocitynetworkmoderation.filter.ChatFilter;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs the word filter over chat and over the commands that are really chat.
 *
 * The priority sits between MuteListener (200) and VelocityNetworkChat (100): a muted player's
 * message is already stopped and not filtered as well, and the chat plugin receives the message
 * already censored.
 *
 * Censoring means changing what the player sent, and that is only done where it is safe:
 * <ul>
 *   <li>chat, when VelocityNetworkChat is installed. It never passes chat on to a backend; it
 *       denies the original and sends its own copy, so the altered text is only ever read on the
 *       proxy.</li>
 *   <li>a command the proxy itself runs for this player, such as /msg. The proxy executes the
 *       altered command itself.</li>
 * </ul>
 * Anywhere else the altered text would travel on to a backend, and a 1.19.1+ client's signed
 * message cannot be altered on the way without the player being disconnected. There a word that
 * would be censored blocks the message instead, so it is never simply let through.
 */
public final class FilterListener {

    /** Below MuteListener, above VelocityNetworkChat's 100. */
    public static final short PRIORITY = 150;

    private final ProxyServer proxyServer;
    private final ChatFilter filter;
    private final Set<String> wholeCommands;
    private final Set<String> targetCommands;

    /**
     * @param wholeCommands  commands whose every argument is checked, such as /r and /me
     * @param targetCommands commands whose first argument is a player name, left unchecked so
     *                       nobody is unable to message a player with an unlucky name
     */
    public FilterListener(ProxyServer proxyServer, ChatFilter filter, List<String> wholeCommands,
                          List<String> targetCommands) {
        this.proxyServer = proxyServer;
        this.filter = filter;
        this.wholeCommands = lowerCase(wholeCommands);
        this.targetCommands = lowerCase(targetCommands);
    }

    private static Set<String> lowerCase(List<String> commands) {
        return commands.stream()
                .map(command -> command.strip().toLowerCase(Locale.ROOT))
                .map(command -> command.startsWith("/") ? command.substring(1) : command)
                .filter(command -> !command.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @SuppressWarnings("deprecation")
    @Subscribe(priority = PRIORITY)
    public void onChat(PlayerChatEvent event) {
        if (!event.getResult().isAllowed()) {
            return;
        }
        Player player = event.getPlayer();
        String message = event.getResult().getMessage().orElse(event.getMessage());
        boolean mayCensor = proxyServer.getPluginManager().isLoaded("velocitynetworkchat");

        ChatFilter.Outcome outcome = filter.apply(player, message, mayCensor, null);
        switch (outcome.kind()) {
            case BLOCKED -> event.setResult(PlayerChatEvent.ChatResult.denied());
            case CENSORED -> event.setResult(PlayerChatEvent.ChatResult.message(outcome.text()));
            case CLEAN -> {
            }
        }
    }

    @Subscribe(priority = PRIORITY)
    public void onCommand(CommandExecuteEvent event) {
        if (!event.getResult().isAllowed() || !(event.getCommandSource() instanceof Player player)) {
            return;
        }
        String command = event.getResult().getCommand().orElse(event.getCommand());
        String root = MuteListener.rootCommand(command);

        int skip;
        if (targetCommands.contains(root)) {
            skip = 1;
        } else if (wholeCommands.contains(root)) {
            skip = 0;
        } else {
            return;
        }

        int textStart = textStart(command, skip);
        if (textStart >= command.length()) {
            return;
        }
        String text = command.substring(textStart);
        if (text.isBlank()) {
            return;
        }

        // The alias as typed, namespace included, because that is what the proxy looks up. A
        // namespaced or unknown alias, or one this player may not use, is not run by the proxy
        // and would be passed to the backend, so it cannot be censored.
        boolean mayCensor = proxyServer.getCommandManager().hasCommand(MuteListener.typedRoot(command), player);

        ChatFilter.Outcome outcome = filter.apply(player, text, mayCensor, root);
        switch (outcome.kind()) {
            case BLOCKED -> event.setResult(CommandExecuteEvent.CommandResult.denied());
            case CENSORED -> event.setResult(CommandExecuteEvent.CommandResult.command(
                    command.substring(0, textStart) + outcome.text()));
            case CLEAN -> {
            }
        }
    }

    /**
     * Where the checked text starts: after the command itself and {@code skip} arguments, at the
     * space before the next one, so censoring keeps the command's spacing exactly as typed.
     */
    static int textStart(String command, int skip) {
        int position = command.length() - command.stripLeading().length();
        // Past the command name.
        while (position < command.length() && command.charAt(position) != ' ') {
            position++;
        }
        for (int i = 0; i < skip; i++) {
            while (position < command.length() && command.charAt(position) == ' ') {
                position++;
            }
            while (position < command.length() && command.charAt(position) != ' ') {
                position++;
            }
        }
        return position;
    }
}
