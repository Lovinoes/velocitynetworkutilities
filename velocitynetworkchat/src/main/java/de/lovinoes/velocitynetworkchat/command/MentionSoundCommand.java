package de.lovinoes.velocitynetworkchat.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import de.lovinoes.velocitynetworkchat.MentionSettings;

/** Toggles whether this player hears a sound when someone mentions them in chat. */
public final class MentionSoundCommand implements SimpleCommand {

    private final MentionSettings settings;
    private final String enabledMessage;
    private final String disabledMessage;
    private final String playersOnlyMessage;

    public MentionSoundCommand(MentionSettings settings, String enabledMessage, String disabledMessage,
                               String playersOnlyMessage) {
        this.settings = settings;
        this.enabledMessage = enabledMessage;
        this.disabledMessage = disabledMessage;
        this.playersOnlyMessage = playersOnlyMessage;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(ChatColorParser.parse(playersOnlyMessage));
            return;
        }
        boolean enabled = settings.toggleSound(player.getUniqueId());
        player.sendMessage(ChatColorParser.parse(enabled ? enabledMessage : disabledMessage));
    }
}
