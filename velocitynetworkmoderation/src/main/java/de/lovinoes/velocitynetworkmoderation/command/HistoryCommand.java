package de.lovinoes.velocitynetworkmoderation.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.playeruuidcachevelocity.PlayerCacheAPI;
import de.lovinoes.playeruuidcachevelocity.PlayerRecord;
import de.lovinoes.velocitynetworkmoderation.Messages;
import de.lovinoes.velocitynetworkmoderation.punishment.Punishment;
import de.lovinoes.velocitynetworkmoderation.punishment.PunishmentDao;
import de.lovinoes.velocitynetworkmoderation.punishment.PunishmentManager;
import de.lovinoes.velocitynetworkutilities.util.PlayerSuggestions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Shows everything recorded against a player, newest first, including punishments that have
 * been lifted or have expired. With the clear permission it can also delete entries:
 *
 * <pre>
 * /history &lt;player&gt;                   show the history
 * /history &lt;player&gt; clear             ask to delete everything that can be deleted
 * /history &lt;player&gt; clear confirm     delete it
 * /history &lt;player&gt; remove &lt;id&gt;       delete one entry
 * </pre>
 *
 * A ban or mute still in force is never deleted: its row is what keeps the player banned or
 * muted. Lift it first, then it can go.
 */
public final class HistoryCommand implements SimpleCommand {

    /**
     * The words after the player name, from config.yml. Single lowercase words, matched without
     * regard to case.
     */
    public record Words(String clear, String confirm, String remove) {
    }

    private final ProxyServer proxyServer;
    private final PunishmentManager punishments;
    private final Messages messages;
    private final String permission;
    private final String clearPermission;
    private final String seeVanishedPermission;
    private final int limit;
    private final Words words;

    public HistoryCommand(ProxyServer proxyServer, PunishmentManager punishments, Messages messages,
                           String permission, String clearPermission, String seeVanishedPermission, int limit,
                           Words words) {
        this.words = words;
        this.proxyServer = proxyServer;
        this.punishments = punishments;
        this.messages = messages;
        this.permission = permission;
        this.clearPermission = clearPermission;
        this.seeVanishedPermission = seeVanishedPermission;
        this.limit = limit;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        boolean mayClear = mayClear(source);

        if (args.length == 1) {
            withPlayer(source, args[0], record -> show(source, record));
        } else if (mayClear && args.length == 2 && args[1].equalsIgnoreCase(words.clear())) {
            withPlayer(source, args[0], record -> askToClear(source, invocation.alias(), record));
        } else if (mayClear && args.length == 3 && args[1].equalsIgnoreCase(words.clear())
                && args[2].equalsIgnoreCase(words.confirm())) {
            withPlayer(source, args[0], record -> clear(source, record));
        } else if (mayClear && args.length == 3 && args[1].equalsIgnoreCase(words.remove()) && parseId(args[2]) > 0) {
            long id = parseId(args[2]);
            withPlayer(source, args[0], record -> remove(source, record, id));
        } else {
            // Without the clear permission the subcommands do not exist, so neither does their usage.
            source.sendMessage(messages.line(mayClear ? "commands.history.usage-edit" : "commands.history.usage",
                    Placeholder.unparsed("clear", words.clear()),
                    Placeholder.unparsed("confirm", words.confirm()),
                    Placeholder.unparsed("remove", words.remove())));
        }
    }

    private boolean mayClear(CommandSource source) {
        return !clearPermission.isEmpty() && source.hasPermission(clearPermission);
    }

    /** A history id: a positive number, optionally written with the # the history shows. */
    private static long parseId(String raw) {
        String digits = raw.startsWith("#") ? raw.substring(1) : raw;
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Looks the player up, then runs {@code action}. Every failure on the way is reported. */
    private void withPlayer(CommandSource source, String target, Consumer<PlayerRecord> action) {
        PlayerCacheAPI.get().resolve(target).thenAccept(record -> {
            if (record.isEmpty()) {
                source.sendMessage(messages.line("errors.player-not-found", Placeholder.unparsed("target", target)));
                return;
            }
            action.accept(record.get());
        }).exceptionally(throwable -> {
            lookupFailed(source, target);
            return null;
        });
    }

    private void lookupFailed(CommandSource source, String target) {
        source.sendMessage(messages.line("errors.lookup-failed", Placeholder.unparsed("target", target)));
    }

    private void show(CommandSource source, PlayerRecord record) {
        // One more than shown, only to know whether older entries were left out.
        punishments.history(record.uuid(), limit + 1).thenAccept(found -> {
            String player = record.username();
            if (found.isEmpty()) {
                source.sendMessage(messages.line("commands.history.empty", Placeholder.unparsed("player", player)));
                return;
            }
            boolean more = found.size() > limit;
            List<Punishment> entries = more ? found.subList(0, limit) : found;
            long now = System.currentTimeMillis();

            source.sendMessage(messages.line("commands.history.header",
                    Placeholder.unparsed("player", player),
                    Placeholder.unparsed("count", String.valueOf(entries.size()))));
            for (Punishment entry : entries) {
                source.sendMessage(entryLine(entry, now));
            }
            if (more) {
                messages.optionalScreen("commands.history.more", Placeholder.unparsed("limit", String.valueOf(limit)))
                        .ifPresent(source::sendMessage);
            }
        }).exceptionally(throwable -> {
            lookupFailed(source, record.username());
            return null;
        });
    }

    /** Says how many entries a clear would delete, with a button that confirms it. */
    private void askToClear(CommandSource source, String alias, PlayerRecord record) {
        punishments.countHistory(record.uuid()).thenAccept(count -> {
            String player = record.username();
            if (count.clearable() == 0) {
                source.sendMessage(messages.line("commands.history.nothing-to-clear",
                        Placeholder.unparsed("player", player)));
                return;
            }
            // Built here rather than in the language file: a click tag cannot take a placeholder,
            // and the command has to name this player and the alias actually used.
            String confirm = "/" + alias.toLowerCase(Locale.ROOT) + " " + player + " " + words.clear() + " " + words.confirm();
            Component button = messages.line("commands.history.clear-button")
                    .clickEvent(ClickEvent.runCommand(confirm))
                    .hoverEvent(HoverEvent.showText(Component.text(confirm)));
            source.sendMessage(messages.line("commands.history.clear-confirm",
                    Placeholder.unparsed("player", player),
                    Placeholder.unparsed("count", String.valueOf(count.clearable())),
                    Placeholder.unparsed("kept", String.valueOf(count.total() - count.clearable())),
                    Placeholder.component("button", button)));
        }).exceptionally(throwable -> {
            lookupFailed(source, record.username());
            return null;
        });
    }

    private void clear(CommandSource source, PlayerRecord record) {
        punishments.clearHistory(record.uuid()).thenAccept(result -> {
            String player = record.username();
            if (result.removed() == 0) {
                source.sendMessage(messages.line("commands.history.nothing-to-clear",
                        Placeholder.unparsed("player", player)));
                return;
            }
            TagResolver[] placeholders = {
                    Placeholder.unparsed("player", player),
                    Placeholder.unparsed("count", String.valueOf(result.removed())),
                    Placeholder.unparsed("kept", String.valueOf(result.kept())),
                    Placeholder.unparsed("operator", punishments.operatorName(source))
            };
            source.sendMessage(messages.line("commands.history.cleared", placeholders));
            if (result.kept() > 0) {
                messages.optionalScreen("commands.history.cleared-kept", placeholders).ifPresent(source::sendMessage);
            }
            log("commands.history.cleared-log", source, placeholders);
        }).exceptionally(throwable -> {
            source.sendMessage(messages.line("errors.save-failed"));
            return null;
        });
    }

    private void remove(CommandSource source, PlayerRecord record, long id) {
        punishments.removeHistoryEntry(record.uuid(), id).thenAccept(result -> {
            TagResolver[] placeholders = {
                    Placeholder.unparsed("player", record.username()),
                    Placeholder.unparsed("id", String.valueOf(id)),
                    Placeholder.unparsed("operator", punishments.operatorName(source))
            };
            switch (result) {
                case REMOVED -> {
                    source.sendMessage(messages.line("commands.history.removed", placeholders));
                    log("commands.history.removed-log", source, placeholders);
                }
                case IN_FORCE -> source.sendMessage(messages.line("commands.history.in-force", placeholders));
                case NOT_FOUND -> source.sendMessage(messages.line("commands.history.no-such-entry", placeholders));
            }
        }).exceptionally(throwable -> {
            source.sendMessage(messages.line("errors.save-failed"));
            return null;
        });
    }

    /** Deleting history is logged to the console, which is the record of who did what. */
    private void log(String path, CommandSource source, TagResolver[] placeholders) {
        if (source instanceof ConsoleCommandSource) {
            return;
        }
        messages.optionalScreen(path, placeholders).ifPresent(proxyServer.getConsoleCommandSource()::sendMessage);
    }

    /**
     * One line per entry, with the details on hover. Only bans and mutes get a status: a kick or
     * a warning is a single moment, never in force, so calling it "active" would be wrong.
     */
    private Component entryLine(Punishment entry, long now) {
        TagResolver[] placeholders = messages.placeholders(entry, now);
        String format = entry.type().isRevocable() ? "commands.history.entry" : "commands.history.entry-simple";
        Component line = messages.line(format, placeholders);

        Component hover = messages.optionalScreen("commands.history.hover", placeholders).orElse(Component.empty());
        if (entry.type().isTemporal()) {
            hover = append(hover, messages.optionalScreen("commands.history.hover-duration", placeholders));
        }
        if (entry.revokedAt() > 0) {
            hover = append(hover, messages.optionalScreen("commands.history.hover-revoked", placeholders));
        }
        return hover.equals(Component.empty()) ? line : line.hoverEvent(HoverEvent.showText(hover));
    }

    private static Component append(Component hover, Optional<Component> part) {
        if (part.isEmpty()) {
            return hover;
        }
        return hover.equals(Component.empty())
                ? part.get()
                : hover.append(Component.newline()).append(part.get());
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String partial = args.length == 1 ? args[0] : "";
            return CompletableFuture.completedFuture(
                    PlayerSuggestions.matching(proxyServer, invocation.source(), partial, seeVanishedPermission));
        }
        if (!mayClear(invocation.source())) {
            return CompletableFuture.completedFuture(List.of());
        }
        if (args.length == 2) {
            return CompletableFuture.completedFuture(startingWith(List.of(words.clear(), words.remove()), args[1]));
        }
        if (args.length == 3 && args[1].equalsIgnoreCase(words.clear())) {
            return CompletableFuture.completedFuture(startingWith(List.of(words.confirm()), args[2]));
        }
        return CompletableFuture.completedFuture(List.of());
    }

    private static List<String> startingWith(List<String> options, String partial) {
        String lower = partial.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lower)).toList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(permission);
    }
}
