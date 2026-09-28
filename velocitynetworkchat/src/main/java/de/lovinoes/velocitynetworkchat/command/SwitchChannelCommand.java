package de.lovinoes.velocitynetworkchat.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import de.lovinoes.velocitynetworkchat.ChannelManager;
import de.lovinoes.velocitynetworkchat.ChatChannel;
import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import de.lovinoes.networkutilitiescommon.config.YamlConfig;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Generic /ch <channel> and /channel <channel> dispatcher, on top of the per-channel shortcut
 * commands (/g, /l, /sc, ...) each channel's own command-triggers already register.
 *
 * A channel the player may not write in is treated exactly like one that does not exist, in the
 * reply and in tab completion alike, so a private channel such as staff chat stays invisible to
 * everyone outside it.
 */
public final class SwitchChannelCommand implements SimpleCommand {

    private final ChannelManager channelManager;
    private final YamlConfig messages;

    public SwitchChannelCommand(ChannelManager channelManager, YamlConfig messages) {
        this.channelManager = channelManager;
        this.messages = messages;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(ChatColorParser.parse(messages.getString("commands.players-only", "")));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 1) {
            player.sendMessage(ChatColorParser.parse(messages.getString("commands.channel-usage", "")));
            return;
        }
        Optional<ChatChannel> channel = channelManager.byKey(args[0]).filter(found -> mayWrite(player, found));
        if (channel.isEmpty()) {
            player.sendMessage(ChatColorParser.parse(messages.getString("commands.channel-unknown", ""),
                    Placeholder.unparsed("channel", args[0])));
            return;
        }
        ChatChannel target = channel.get();
        channelManager.setActiveChannel(player.getUniqueId(), target.key());
        player.sendMessage(ChatColorParser.parse(messages.getString("commands.channel-switched", ""),
                Placeholder.unparsed("channel", target.displayName())));
    }

    private static boolean mayWrite(Player player, ChatChannel channel) {
        return channel.permission().isEmpty() || player.hasPermission(channel.permission());
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length > 1) {
            return CompletableFuture.completedFuture(List.of());
        }
        String partial = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        List<String> suggestions = channelManager.channels().values().stream()
                .filter(channel -> channel.permission().isEmpty() || invocation.source().hasPermission(channel.permission()))
                .map(ChatChannel::key)
                .filter(key -> key.toLowerCase(Locale.ROOT).startsWith(partial))
                .collect(Collectors.toList());
        return CompletableFuture.completedFuture(suggestions);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
