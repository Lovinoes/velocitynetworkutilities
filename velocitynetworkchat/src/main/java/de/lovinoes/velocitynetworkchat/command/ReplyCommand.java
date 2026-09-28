package de.lovinoes.velocitynetworkchat.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import de.lovinoes.velocitynetworkchat.PrivateMessageManager;
import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import de.lovinoes.networkutilitiescommon.config.YamlConfig;

import java.util.Arrays;

public final class ReplyCommand implements SimpleCommand {

    private final PrivateMessageManager privateMessageManager;
    private final String usagePermission;
    private final YamlConfig messages;

    public ReplyCommand(PrivateMessageManager privateMessageManager, String usagePermission, YamlConfig messages) {
        this.privateMessageManager = privateMessageManager;
        this.usagePermission = usagePermission;
        this.messages = messages;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player sender)) {
            invocation.source().sendMessage(ChatColorParser.parse(messages.getString("commands.players-only", "")));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 1) {
            sender.sendMessage(ChatColorParser.parse(messages.getString("commands.reply-usage", "")));
            return;
        }
        String message = String.join(" ", Arrays.asList(args));
        privateMessageManager.reply(sender, message);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(usagePermission);
    }
}
