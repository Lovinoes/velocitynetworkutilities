package de.lovinoes.velocitynetworkvanish.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import de.lovinoes.velocitynetworkvanish.VanishManager;
import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;

/**
 * Toggles vanish. Registered twice: once as /vanish, which announces a fake leave or join so the
 * rest of the network sees a normal-looking disconnect, and once as /silentvanish, which toggles
 * without announcing anything.
 */
public final class VanishCommand implements SimpleCommand {

    private final VanishManager vanishManager;
    private final String vanishPermission;
    private final String enabledMessage;
    private final String disabledMessage;
    private final boolean silent;
    private final String playersOnlyMessage;

    public VanishCommand(VanishManager vanishManager, String vanishPermission, String enabledMessage,
                          String disabledMessage, boolean silent, String playersOnlyMessage) {
        this.playersOnlyMessage = playersOnlyMessage;
        this.vanishManager = vanishManager;
        this.vanishPermission = vanishPermission;
        this.enabledMessage = enabledMessage;
        this.disabledMessage = disabledMessage;
        this.silent = silent;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(ChatColorParser.parse(playersOnlyMessage));
            return;
        }
        boolean vanished = !vanishManager.isVanished(player.getUniqueId());
        vanishManager.setVanished(player, vanished, silent);

        // A blank confirmation would arrive as an empty chat line, which reads as the command
        // half working. Saying nothing at all is the better failure, and "" is also how an
        // operator turns the confirmation off.
        String confirmation = vanished ? enabledMessage : disabledMessage;
        if (!confirmation.isBlank()) {
            player.sendMessage(ChatColorParser.parse(confirmation));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(vanishPermission);
    }
}
