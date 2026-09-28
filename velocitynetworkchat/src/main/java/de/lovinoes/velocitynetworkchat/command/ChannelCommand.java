package de.lovinoes.velocitynetworkchat.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import de.lovinoes.velocitynetworkchat.ChannelManager;
import de.lovinoes.velocitynetworkchat.ChatChannel;
import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import de.lovinoes.networkutilitiescommon.config.YamlConfig;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

public final class ChannelCommand implements SimpleCommand {

    private final ChannelManager channelManager;
    private final String channelKey;
    private final YamlConfig messages;

    public ChannelCommand(ChannelManager channelManager, String channelKey, YamlConfig messages) {
        this.channelManager = channelManager;
        this.channelKey = channelKey;
        this.messages = messages;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(ChatColorParser.parse(messages.getString("commands.players-only", "")));
            return;
        }
        ChatChannel channel = channelManager.byKey(channelKey).orElseThrow();
        channelManager.setActiveChannel(player.getUniqueId(), channel.key());
        player.sendMessage(ChatColorParser.parse(messages.getString("commands.channel-switched", ""),
                Placeholder.unparsed("channel", channel.displayName())));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        ChatChannel channel = channelManager.byKey(channelKey).orElse(null);
        return channel == null || channel.permission().isEmpty() || invocation.source().hasPermission(channel.permission());
    }
}
