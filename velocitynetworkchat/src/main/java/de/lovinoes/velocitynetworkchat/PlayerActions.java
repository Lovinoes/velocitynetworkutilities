package de.lovinoes.velocitynetworkchat;

import com.velocitypowered.api.command.CommandSource;
import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns a player's name in chat into a handle for acting on them.
 *
 * Minecraft has no pop-up menu, so this works in two steps: the name carries a click that runs a
 * hidden command, and that command prints a row of clickable buttons. The row is ordinary chat,
 * which is why the buttons can be clicked at all; a hover tooltip could show the same text but
 * nothing in it would be clickable.
 *
 * Every part is config driven: the buttons, their order, what each one does, the row they sit in
 * and the tooltip on the name itself.
 */
public final class PlayerActions {

    /**
     * A name is only ever reached through a click the client sends back, so it is untrusted
     * input: anyone can type the hidden command with anything after it. Restricting it to the
     * shape of a Minecraft username means nothing surprising can be echoed into chat or spliced
     * into the command a button runs.
     */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /**
     * @param run        true runs the command outright, false only types it into the player's
     *                   chat box so they can finish it, which is what /msg wants.
     * @param permission empty for a button everyone gets. Otherwise only players holding it see
     *                   the button, which is how the moderation buttons stay out of the way of
     *                   ordinary players.
     */
    public record Action(String label, String hover, boolean run, String command, String permission) {

        public boolean isVisibleTo(CommandSource viewer) {
            return permission.isEmpty() || viewer.hasPermission(permission);
        }
    }

    private final boolean enabled;
    private final String commandName;
    private final String nameHover;
    private final String lineFormat;
    private final String separator;
    private final List<Action> actions;

    public PlayerActions(boolean enabled, String commandName, String nameHover, String lineFormat,
                          String separator, List<Action> actions) {
        this.enabled = enabled && !actions.isEmpty();
        this.commandName = commandName;
        this.nameHover = nameHover;
        this.lineFormat = lineFormat;
        this.separator = separator;
        this.actions = List.copyOf(actions);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String commandName() {
        return commandName;
    }

    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    /**
     * The player's name as it should appear in chat: clickable, with a tooltip saying so.
     *
     * Returns a plain name when disabled, or when the name is not one this can safely build a
     * command from, so an unusual name simply loses the buttons rather than breaking the line.
     */
    public Component decorate(String username) {
        Component name = Component.text(username);
        if (!enabled || !isValidName(username)) {
            return name;
        }
        name = name.clickEvent(ClickEvent.runCommand("/" + commandName + " " + username));
        if (!nameHover.isBlank()) {
            name = name.hoverEvent(HoverEvent.showText(
                    ChatColorParser.parse(nameHover, Placeholder.unparsed("player", username))));
        }
        return name;
    }

    /**
     * The row of buttons shown after clicking a name, built for the player who clicked.
     *
     * Filtering happens here rather than when the name is decorated, because a chat message is
     * rendered once and sent to everyone: the name in it cannot differ per reader. The click is
     * the first moment there is a specific viewer to check.
     *
     * @return empty when this viewer may use none of the buttons, so nothing is sent at all
     *         rather than an empty row.
     */
    public Optional<Component> buildLine(CommandSource viewer, String username) {
        List<Action> visible = actions.stream().filter(action -> action.isVisibleTo(viewer)).toList();
        if (visible.isEmpty()) {
            return Optional.empty();
        }

        Component joined = Component.empty();
        Component gap = separator.isEmpty() ? Component.empty() : ChatColorParser.parse(separator);
        boolean first = true;
        for (Action action : visible) {
            if (!first) {
                joined = joined.append(gap);
            }
            joined = joined.append(render(action, username));
            first = false;
        }
        return Optional.of(ChatColorParser.parse(lineFormat,
                Placeholder.unparsed("player", username),
                Placeholder.component("actions", joined)));
    }

    private Component render(Action action, String username) {
        // The command is a plain string, not MiniMessage, so the name is substituted directly.
        // isValidName has already ruled out anything that could change the command's shape.
        String command = action.command().replace("<player>", username);
        Component label = ChatColorParser.parse(action.label(), Placeholder.unparsed("player", username))
                .clickEvent(action.run() ? ClickEvent.runCommand(command) : ClickEvent.suggestCommand(command));
        if (!action.hover().isBlank()) {
            label = label.hoverEvent(HoverEvent.showText(
                    ChatColorParser.parse(action.hover(), Placeholder.unparsed("player", username))));
        }
        return label;
    }

    /**
     * Reads the actions list. A malformed entry is skipped rather than failing startup, since
     * losing one button is a far better outcome than a chat plugin that refuses to load.
     *
     * @param raw  what each button DOES, from config.yml: its type, command and permission
     * @param text what each button SAYS, from the language file: its label and tooltip, keyed by
     *             the same name. Split because the command a button runs is the same in every
     *             language and its wording is not.
     */
    public static List<Action> readActions(Object raw, Object text, System.Logger logger) {
        List<Action> parsed = new ArrayList<>();
        if (!(raw instanceof Map<?, ?> actions)) {
            return parsed;
        }
        Map<?, ?> wording = text instanceof Map<?, ?> map ? map : Map.of();
        for (Map.Entry<?, ?> action : actions.entrySet()) {
            String name = String.valueOf(action.getKey());
            if (!(action.getValue() instanceof Map<?, ?> entry)) {
                logger.log(System.Logger.Level.WARNING,
                        "Skipping the player-actions entry {0}: it is not a section.", name);
                continue;
            }
            Map<?, ?> said = wording.get(name) instanceof Map<?, ?> map ? map : Map.of();
            String label = string(said.get("label"), "");
            String command = string(entry.get("command"), "");
            if (label.isBlank() || command.isBlank()) {
                logger.log(System.Logger.Level.WARNING,
                        "Skipping the player-actions entry {0}: it has no label or no command.", name);
                continue;
            }
            String type = string(entry.get("type"), "run").toLowerCase(Locale.ROOT);
            if (!type.equals("run") && !type.equals("suggest")) {
                logger.log(System.Logger.Level.WARNING,
                        "The player-actions entry {0} has type {1}; expected run or suggest, using run.",
                        name, type);
                type = "run";
            }
            parsed.add(new Action(label, string(said.get("hover"), ""), type.equals("run"), command,
                    string(entry.get("permission"), "")));
        }
        return parsed;
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
