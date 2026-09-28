package de.lovinoes.velocitynetworkchat.showcase;

import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import de.lovinoes.networkutilitiescommon.config.YamlConfig;
import de.lovinoes.velocitynetworkchat.backend.Protocol;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns what a backend reported about a player into the chat components that stand in for
 * [item], [inv] and [pos], each with a hover showing the details.
 *
 * Every word and colour comes from the language file. Item text is always passed through
 * ComponentSafety first, and placed with Placeholder.component, never spliced into MiniMessage
 * source, so nothing an item says can be read as formatting or as a command.
 */
public final class ShowcaseRenderer {

    /**
     * What a click on a showcase does, from config.yml.
     *
     * @param action none, suggest, run or copy
     * @param value  the command or text, with &lt;player&gt;, &lt;server&gt;, &lt;world&gt;,
     *               &lt;x&gt;, &lt;y&gt; and &lt;z&gt; replaced. Those are the only values put in
     *               a click, deliberately: an item's name is chosen by whoever made the item and
     *               must never end up inside a command someone else runs.
     */
    public record Click(String action, String value) {

        public static final Click NONE = new Click("none", "");

        static Click of(String action, String value) {
            String normalised = action == null ? "none" : action.trim().toLowerCase(Locale.ROOT);
            return switch (normalised) {
                case "suggest", "run", "copy" -> new Click(normalised, value == null ? "" : value);
                default -> NONE;
            };
        }
    }

    private final YamlConfig language;
    private final Click itemClick;
    private final Click inventoryClick;
    private final Click positionClick;
    private final int maxInventoryEntries;

    public ShowcaseRenderer(YamlConfig language, Click itemClick, Click inventoryClick, Click positionClick,
                            int maxInventoryEntries) {
        this.language = language;
        this.itemClick = itemClick;
        this.inventoryClick = inventoryClick;
        this.positionClick = positionClick;
        this.maxInventoryEntries = Math.max(1, maxInventoryEntries);
    }

    public static Click click(YamlConfig config, String path) {
        return Click.of(config.getString(path + ".type", "none"), config.getString(path + ".value", ""));
    }

    // ---------------------------------------------------------------------------------- item

    /** @param held null for an empty hand */
    public Component item(String player, String server, Protocol.Item held) {
        if (held == null) {
            Component empty = line("showcase.item.empty", common(player, server));
            List<Component> hover = lines("showcase.item.empty-hover", common(player, server));
            return finish(hover.isEmpty() ? empty : empty.hoverEvent(HoverEvent.showText(join(hover))),
                    itemClick, Map.of("player", player, "server", server));
        }

        Component name = ComponentSafety.parse(held.name());
        TagResolver[] resolvers = concat(common(player, server),
                Placeholder.component("name", ComponentSafety.unstyled(name)),
                Placeholder.component("styled_name", ComponentSafety.inert(name)),
                Placeholder.unparsed("amount", String.valueOf(held.amount())),
                Placeholder.unparsed("type", held.material()));

        String formatPath = held.amount() > 1 ? "showcase.item.format-amount" : "showcase.item.format";
        Component shown = line(formatPath, resolvers);

        List<Component> hover = new ArrayList<>();
        for (String template : language.getStringList("showcase.item.hover")) {
            switch (template.trim()) {
                case "<lore>" -> {
                    for (Protocol.Line lore : held.lore()) {
                        hover.add(line("showcase.item.lore-line",
                                Placeholder.component("line", ComponentSafety.inert(ComponentSafety.parse(lore)))));
                    }
                }
                case "<enchantments>" -> {
                    for (Protocol.Line enchantment : held.enchantments()) {
                        hover.add(line("showcase.item.enchantment-line",
                                Placeholder.component("line", ComponentSafety.inert(ComponentSafety.parse(enchantment)))));
                    }
                }
                case "<durability>" -> durability(held).ifPresent(hover::add);
                default -> hover.add(ChatColorParser.parse(template, resolvers));
            }
        }

        if (!hover.isEmpty()) {
            shown = shown.hoverEvent(HoverEvent.showText(join(hover)));
        }
        return finish(shown, itemClick, Map.of("player", player, "server", server));
    }

    /**
     * Unbreakable wins over a damage bar, since it never changes. An item with no maximum damage
     * is not damageable at all, and gets no line.
     */
    private java.util.Optional<Component> durability(Protocol.Item held) {
        if (held.unbreakable()) {
            return optional("showcase.item.unbreakable");
        }
        if (held.maxDamage() <= 0) {
            return java.util.Optional.empty();
        }
        int remaining = Math.max(0, held.maxDamage() - held.damage());
        return optional("showcase.item.durability",
                Placeholder.unparsed("remaining", String.valueOf(remaining)),
                Placeholder.unparsed("max", String.valueOf(held.maxDamage())),
                Placeholder.unparsed("damage", String.valueOf(held.damage())));
    }

    // ----------------------------------------------------------------------------- inventory

    private static final byte[] SECTION_ORDER = {
            Protocol.HOTBAR, Protocol.STORAGE, Protocol.ARMOR, Protocol.OFFHAND};

    public Component inventory(String player, String server, List<Protocol.Entry> entries) {
        TagResolver[] base = common(player, server);
        Component shown = line("showcase.inventory.format", base);

        List<Component> hover = new ArrayList<>(lines("showcase.inventory.header", base));
        int shownEntries = 0;
        int total = 0;

        for (byte section : SECTION_ORDER) {
            List<Protocol.Entry> inSection = new ArrayList<>();
            for (Protocol.Entry entry : entries) {
                if (entry.group() == section) {
                    inSection.add(entry);
                }
            }
            if (inSection.isEmpty()) {
                continue;
            }
            total += inSection.size();

            if (shownEntries >= maxInventoryEntries) {
                continue;
            }
            optional("showcase.inventory.sections." + sectionName(section), base).ifPresent(hover::add);

            for (Protocol.Entry entry : inSection) {
                if (shownEntries >= maxInventoryEntries) {
                    break;
                }
                Component name = ComponentSafety.parse(entry.name());
                hover.add(line("showcase.inventory.entry", concat(base,
                        Placeholder.component("name", ComponentSafety.unstyled(name)),
                        Placeholder.component("styled_name", ComponentSafety.inert(name)),
                        Placeholder.unparsed("amount", String.valueOf(entry.amount())),
                        Placeholder.unparsed("type", entry.material()))));
                shownEntries++;
            }
        }

        if (total == 0) {
            optional("showcase.inventory.empty", base).ifPresent(hover::add);
        } else if (total > shownEntries) {
            optional("showcase.inventory.more", concat(base,
                    Placeholder.unparsed("count", String.valueOf(total - shownEntries)))).ifPresent(hover::add);
        }

        if (!hover.isEmpty()) {
            shown = shown.hoverEvent(HoverEvent.showText(join(hover)));
        }
        return finish(shown, inventoryClick, Map.of("player", player, "server", server));
    }

    private static String sectionName(byte section) {
        return switch (section) {
            case Protocol.HOTBAR -> "hotbar";
            case Protocol.STORAGE -> "storage";
            case Protocol.ARMOR -> "armor";
            default -> "offhand";
        };
    }

    // ------------------------------------------------------------------------------ position

    public Component position(String player, String server, Protocol.Position position) {
        TagResolver[] resolvers = concat(common(player, server),
                Placeholder.unparsed("world", position.world()),
                Placeholder.unparsed("x", String.valueOf(position.x())),
                Placeholder.unparsed("y", String.valueOf(position.y())),
                Placeholder.unparsed("z", String.valueOf(position.z())));

        Component shown = line("showcase.position.format", resolvers);
        List<Component> hover = lines("showcase.position.hover", resolvers);
        if (!hover.isEmpty()) {
            shown = shown.hoverEvent(HoverEvent.showText(join(hover)));
        }
        return finish(shown, positionClick, Map.of(
                "player", player, "server", server, "world", position.world(),
                "x", String.valueOf(position.x()), "y", String.valueOf(position.y()),
                "z", String.valueOf(position.z())));
    }

    // ------------------------------------------------------------------------------- helpers

    private Component finish(Component shown, Click click, Map<String, String> values) {
        if (click.action().equals("none") || click.value().isBlank()) {
            return shown;
        }
        String value = click.value();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            value = value.replace("<" + entry.getKey() + ">", entry.getValue());
        }
        ClickEvent<?> event = switch (click.action()) {
            case "suggest" -> ClickEvent.suggestCommand(value);
            case "run" -> ClickEvent.runCommand(value);
            default -> ClickEvent.copyToClipboard(value);
        };
        return shown.clickEvent(event);
    }

    private static TagResolver[] common(String player, String server) {
        return new TagResolver[] {Placeholder.unparsed("player", player), Placeholder.unparsed("server", server)};
    }

    private static TagResolver[] concat(TagResolver[] base, TagResolver... more) {
        TagResolver[] all = new TagResolver[base.length + more.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    private Component line(String path, TagResolver... resolvers) {
        return ChatColorParser.parse(language.getString(path, ""), resolvers);
    }

    /** A line that is left out entirely when its template is blank. */
    private java.util.Optional<Component> optional(String path, TagResolver... resolvers) {
        String template = language.getString(path, "");
        return template.isBlank()
                ? java.util.Optional.empty()
                : java.util.Optional.of(ChatColorParser.parse(template, resolvers));
    }

    private List<Component> lines(String path, TagResolver... resolvers) {
        List<Component> rendered = new ArrayList<>();
        for (String template : language.getStringList(path)) {
            rendered.add(ChatColorParser.parse(template, resolvers));
        }
        return rendered;
    }

    private static Component join(List<Component> lines) {
        return Component.join(JoinConfiguration.newlines(), lines);
    }
}
