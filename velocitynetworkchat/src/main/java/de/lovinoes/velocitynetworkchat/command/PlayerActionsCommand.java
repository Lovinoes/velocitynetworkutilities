package de.lovinoes.velocitynetworkchat.command;

import com.velocitypowered.api.command.SimpleCommand;
import de.lovinoes.velocitynetworkchat.PlayerActions;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Backs the click on a player's name in chat by printing the row of buttons.
 *
 * Players never type this. It exists because a click event can only run a command, and a row of
 * clickable buttons has to arrive as a chat message.
 *
 * It deliberately offers no completions: suggesting it would invite people to type it by hand,
 * and there is nothing useful to complete.
 */
public final class PlayerActionsCommand implements SimpleCommand {

    private final PlayerActions playerActions;

    public PlayerActionsCommand(PlayerActions playerActions) {
        this.playerActions = playerActions;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        // Anyone can type this command with anything after it, so a name that is not a name is
        // simply ignored. Saying nothing is right here: there is no legitimate way to get here
        // with a bad argument, and an error message would only be a reply to someone probing.
        if (args.length != 1 || !PlayerActions.isValidName(args[0])) {
            return;
        }
        // Empty when this player may use none of the buttons, in which case nothing is sent.
        playerActions.buildLine(invocation.source(), args[0])
                .ifPresent(line -> invocation.source().sendMessage(line));
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        return CompletableFuture.completedFuture(List.of());
    }
}
