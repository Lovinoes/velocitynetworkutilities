package de.lovinoes.velocitynetworkmoderation.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.playeruuidcachevelocity.PlayerCacheAPI;
import de.lovinoes.velocitynetworkmoderation.Messages;
import de.lovinoes.velocitynetworkmoderation.punishment.Punishment;
import de.lovinoes.velocitynetworkmoderation.punishment.PunishmentManager;
import de.lovinoes.velocitynetworkutilities.util.PlayerSuggestions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Shows everything ever recorded against a player, newest first, including punishments that
 * have been lifted or have expired.
 *
 * A history that quietly hid lifted entries would be worse than useless: the whole point of
 * looking one up is to see the pattern, and a lifted ban is part of the pattern.
 */
public final class HistoryCommand implements SimpleCommand {

    private final ProxyServer proxyServer;
    private final PunishmentManager punishments;
    private final Messages messages;
    private final String permission;
    private final String seeVanishedPermission;
    private final int limit;

    public HistoryCommand(ProxyServer proxyServer, PunishmentManager punishments, Messages messages,
                           String permission, String seeVanishedPermission, int limit) {
        this.proxyServer = proxyServer;
        this.punishments = punishments;
        this.messages = messages;
        this.permission = permission;
        this.seeVanishedPermission = seeVanishedPermission;
        this.limit = limit;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length != 1) {
            invocation.source().sendMessage(messages.line("commands.history.usage"));
            return;
        }
        String target = args[0];

        PlayerCacheAPI.get().findByUsername(target).thenAccept(record -> {
            if (record.isEmpty()) {
                invocation.source().sendMessage(messages.line("errors.player-not-found",
                        Placeholder.unparsed("target", target)));
                return;
            }
            // One more than shown, only to know whether older entries were left out.
            punishments.history(record.get().uuid(), limit + 1).thenAccept(found -> {
                String player = record.get().username();
                if (found.isEmpty()) {
                    invocation.source().sendMessage(messages.line("commands.history.empty",
                            Placeholder.unparsed("player", player)));
                    return;
                }
                boolean more = found.size() > limit;
                List<Punishment> entries = more ? found.subList(0, limit) : found;
                long now = System.currentTimeMillis();

                invocation.source().sendMessage(messages.line("commands.history.header",
                        Placeholder.unparsed("player", player),
                        Placeholder.unparsed("count", String.valueOf(entries.size()))));
                for (Punishment entry : entries) {
                    invocation.source().sendMessage(entryLine(entry, now));
                }
                if (more) {
                    messages.optionalScreen("commands.history.more",
                                    Placeholder.unparsed("limit", String.valueOf(limit)))
                            .ifPresent(invocation.source()::sendMessage);
                }
            }).exceptionally(throwable -> {
                invocation.source().sendMessage(messages.line("errors.lookup-failed",
                        Placeholder.unparsed("target", target)));
                return null;
            });
        }).exceptionally(throwable -> {
            invocation.source().sendMessage(messages.line("errors.lookup-failed",
                    Placeholder.unparsed("target", target)));
            return null;
        });
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
        if (entry.type().isRevocable() && !entry.active()) {
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
        if (args.length > 1) {
            return CompletableFuture.completedFuture(List.of());
        }
        String partial = args.length == 1 ? args[0] : "";
        return CompletableFuture.completedFuture(
                PlayerSuggestions.matching(proxyServer, invocation.source(), partial, seeVanishedPermission));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(permission);
    }
}
