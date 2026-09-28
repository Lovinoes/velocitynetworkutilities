package de.lovinoes.velocitynetworkmoderation.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.playeruuidcachevelocity.PlayerCacheAPI;
import de.lovinoes.velocitynetworkmoderation.Messages;
import de.lovinoes.velocitynetworkmoderation.punishment.Punishment;
import de.lovinoes.velocitynetworkmoderation.punishment.PunishmentManager;
import de.lovinoes.velocitynetworkutilities.util.PlayerSuggestions;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.List;
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
            punishments.history(record.get().uuid(), limit).thenAccept(entries -> {
                long now = System.currentTimeMillis();
                invocation.source().sendMessage(messages.line("commands.history.header",
                        Placeholder.unparsed("player", record.get().username()),
                        Placeholder.unparsed("count", String.valueOf(entries.size()))));
                if (entries.isEmpty()) {
                    invocation.source().sendMessage(messages.line("commands.history.empty",
                            Placeholder.unparsed("player", record.get().username())));
                    return;
                }
                for (Punishment entry : entries) {
                    invocation.source().sendMessage(
                            messages.line("commands.history.entry", messages.placeholders(entry, now)));
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
