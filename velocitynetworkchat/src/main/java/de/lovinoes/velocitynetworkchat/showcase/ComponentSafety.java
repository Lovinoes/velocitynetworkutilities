package de.lovinoes.velocitynetworkchat.showcase;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEventSource;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import de.lovinoes.velocitynetworkchat.backend.Protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes a component that came from an item safe to put in chat.
 *
 * An item's name and lore are whatever its creator made them. In survival that is plain text, but
 * a creative-mode client can give an item a name carrying a run_command click. Shown in chat, that
 * name becomes a button that runs the command as whoever clicks it. So every click, hover and
 * insertion is stripped before an item's text goes anywhere near a chat line, however deeply it
 * is nested and including inside translation arguments.
 */
public final class ComponentSafety {

    /**
     * Far beyond anything a real item has, and shallow enough that a deliberately nested name
     * cannot exhaust the stack while it is being walked.
     */
    private static final int MAX_DEPTH = 32;

    private ComponentSafety() {
    }

    /**
     * The backend sends JSON when it fits and plain text always. JSON that does not parse, from
     * a newer game version or simply corrupt, falls back to the plain text instead of losing the
     * line.
     */
    public static Component parse(Protocol.Line line) {
        if (!line.json().isEmpty()) {
            try {
                return GsonComponentSerializer.gson().deserialize(line.json());
            } catch (RuntimeException ignored) {
                // Fall through to the plain text, which the backend always sends.
            }
        }
        return Component.text(line.plain());
    }

    /** Keeps colours and decorations, removes anything that acts. */
    public static Component inert(Component component) {
        return walk(component, false, 0);
    }

    /**
     * Removes every style as well, so the text takes on the colour of whatever it is placed in.
     * Translatable text stays translatable: "Diamond Sword" still shows in the reader's language.
     */
    public static Component unstyled(Component component) {
        return walk(component, true, 0);
    }

    private static Component walk(Component component, boolean dropStyle, int depth) {
        Style style = dropStyle
                ? Style.empty()
                : component.style()
                        .clickEvent((ClickEvent<?>) null)
                        .hoverEvent((HoverEventSource<?>) null)
                        .insertion(null);
        Component result = component.style(style);

        if (depth >= MAX_DEPTH) {
            // Deeper than any real item goes. Keep the text of this level, not its descendants.
            return result.children(List.of());
        }

        if (result instanceof TranslatableComponent translatable && !translatable.arguments().isEmpty()) {
            List<TranslationArgument> arguments = new ArrayList<>();
            for (TranslationArgument argument : translatable.arguments()) {
                arguments.add(argument.value() instanceof Component nested
                        ? TranslationArgument.component(walk(nested, dropStyle, depth + 1))
                        : argument);
            }
            result = translatable.arguments(arguments);
        }

        List<Component> children = new ArrayList<>(result.children().size());
        for (Component child : result.children()) {
            children.add(walk(child, dropStyle, depth + 1));
        }
        return result.children(children);
    }
}
