package de.lovinoes.velocitynetworkchat.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import de.lovinoes.velocitynetworkchat.PrivateMessageManager;
import de.lovinoes.velocitynetworkutilities.util.PlayerSuggestions;

/**
 * /msg, /tell, /w, /whisper, /pm and /m all collide in name with vanilla's own signed chat
 * command (msg/tell/w are aliases of the same built-in). The client validates what it types
 * against its own local grammar expectations for those specific names as part of chat signing,
 * so a SimpleCommand registration (which Velocity turns into an opaque single-argument node
 * for the client) can disagree with what the client expects and get rejected client-side with
 * "Incorrect argument for command at position N" before the keystroke ever reaches the proxy.
 * Building a real Brigadier tree here, with the same word()-then-greedyString() shape vanilla
 * itself uses, avoids that mismatch entirely.
 */
public final class MessageCommand {

    private MessageCommand() {
    }

    public static LiteralCommandNode<CommandSource> create(ProxyServer proxyServer, PrivateMessageManager privateMessageManager,
                                                             String usagePermission, String seeVanishedPermission,
                                                             String playersOnlyMessage) {
        return LiteralArgumentBuilder.<CommandSource>literal("msg")
                .requires(source -> source.hasPermission(usagePermission))
                .then(RequiredArgumentBuilder.<CommandSource, String>argument("target", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            PlayerSuggestions.matching(proxyServer, context.getSource(), builder.getRemaining(), seeVanishedPermission)
                                    .forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("message", StringArgumentType.greedyString())
                                .executes(context -> execute(context, privateMessageManager, playersOnlyMessage))))
                .build();
    }

    private static int execute(CommandContext<CommandSource> context, PrivateMessageManager privateMessageManager,
                               String playersOnlyMessage) {
        if (!(context.getSource() instanceof Player sender)) {
            context.getSource().sendMessage(ChatColorParser.parse(playersOnlyMessage));
            return Command.SINGLE_SUCCESS;
        }
        String targetName = context.getArgument("target", String.class);
        String message = context.getArgument("message", String.class);
        privateMessageManager.sendByName(sender, targetName, message);
        return Command.SINGLE_SUCCESS;
    }
}
