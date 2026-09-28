package de.lovinoes.velocitynetworkmoderation.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.playeruuidcachevelocity.PlayerCacheAPI;
import de.lovinoes.velocitynetworkmoderation.Messages;
import de.lovinoes.velocitynetworkmoderation.punishment.Punishment;
import de.lovinoes.velocitynetworkmoderation.punishment.PunishmentManager;
import de.lovinoes.velocitynetworkmoderation.punishment.PunishmentType;
import de.lovinoes.velocitynetworkutilities.util.PlayerSuggestions;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Lifts a ban, IP ban or mute.
 *
 * An IP ban can be lifted by naming either the address or the player it was placed through,
 * because a moderator lifting one six weeks later will remember the name and not the address.
 */
public final class RevokeCommand implements SimpleCommand {

    private final ProxyServer proxyServer;
    private final PunishmentManager punishments;
    private final Messages messages;
    private final PunishmentType type;
    private final String messageKey;
    private final String permission;
    private final String seeVanishedPermission;
    private final String broadcastPermission;
    private final String silentFlag;
    private final String silentBroadcastPermission;

    public RevokeCommand(ProxyServer proxyServer, PunishmentManager punishments, Messages messages,
                          PunishmentType type, String messageKey, String permission, String seeVanishedPermission,
                          String broadcastPermission, String silentFlag, String silentBroadcastPermission) {
        this.proxyServer = proxyServer;
        this.punishments = punishments;
        this.messages = messages;
        this.type = type;
        this.messageKey = messageKey;
        this.permission = permission;
        this.seeVanishedPermission = seeVanishedPermission;
        this.broadcastPermission = broadcastPermission;
        this.silentFlag = silentFlag;
        this.silentBroadcastPermission = silentBroadcastPermission;
    }

    @Override
    public void execute(Invocation invocation) {
        List<String> words = new ArrayList<>();
        boolean silent = false;
        for (String argument : invocation.arguments()) {
            if (!silentFlag.isEmpty() && argument.equalsIgnoreCase(silentFlag)) {
                silent = true;
            } else if (!argument.isEmpty()) {
                words.add(argument);
            }
        }

        if (words.size() != 1) {
            invocation.source().sendMessage(messages.line("commands." + messageKey + ".usage"));
            return;
        }
        String target = words.get(0);
        boolean finalSilent = silent;

        if (type == PunishmentType.IP_BAN && looksLikeAddress(target)) {
            punishments.revokeIpBan(target, invocation.source())
                    .thenAccept(lifted -> report(invocation, lifted, target, finalSilent))
                    .exceptionally(failure(invocation, target));
            return;
        }

        PlayerCacheAPI.get().findByUsername(target).thenAccept(record -> {
            if (record.isEmpty()) {
                invocation.source().sendMessage(messages.line("errors.player-not-found",
                        Placeholder.unparsed("target", target)));
                return;
            }
            CompletableFuture<Optional<Punishment>> lifting = type == PunishmentType.IP_BAN
                    ? punishments.dao().lastAddress(record.get().uuid()).thenCompose(address -> address
                            .map(value -> punishments.revokeIpBan(value, invocation.source()))
                            .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())))
                    : punishments.revoke(type, record.get().uuid(), invocation.source());

            lifting.thenAccept(lifted -> report(invocation, lifted, record.get().username(), finalSilent))
                    .exceptionally(failure(invocation, target));
        }).exceptionally(failure(invocation, target));
    }

    private void report(Invocation invocation, Optional<Punishment> lifted, String target, boolean silent) {
        if (lifted.isEmpty()) {
            invocation.source().sendMessage(messages.line("errors.nothing-to-revoke",
                    Placeholder.unparsed("target", target),
                    Placeholder.unparsed("type", type.lowerName())));
            return;
        }
        long now = System.currentTimeMillis();
        var placeholders = messages.placeholders(lifted.get(), now);
        invocation.source().sendMessage(messages.line("commands." + messageKey + ".success", placeholders));

        String broadcastPath = "broadcast." + messageKey;
        if (!messages.isBlank(broadcastPath)) {
            punishments.broadcast(messages.line(broadcastPath, placeholders),
                    silent ? silentBroadcastPermission : broadcastPermission, silent);
        }

        if (type == PunishmentType.MUTE && lifted.get().victimUuid() != null
                && !messages.isBlank("screens.unmute")) {
            proxyServer.getPlayer(lifted.get().victimUuid()).ifPresent(player ->
                    player.sendMessage(messages.screen("screens.unmute", placeholders)));
        }
    }

    private java.util.function.Function<Throwable, Void> failure(Invocation invocation, String target) {
        return throwable -> {
            invocation.source().sendMessage(messages.line("errors.lookup-failed",
                    Placeholder.unparsed("target", target)));
            return null;
        };
    }

    /**
     * A Minecraft username can never contain a dot or a colon, so either one means the moderator
     * typed an address rather than a name. That is enough to tell them apart without pretending
     * to validate an address here, which the database lookup does by simply not matching.
     */
    private static boolean looksLikeAddress(String value) {
        return value.indexOf('.') >= 0 || value.indexOf(':') >= 0;
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        String partial = args.length == 0 ? "" : args[args.length - 1];

        if (args.length <= 1) {
            return CompletableFuture.completedFuture(
                    PlayerSuggestions.matching(proxyServer, invocation.source(), partial, seeVanishedPermission));
        }
        if (silentFlag.isEmpty() || Arrays.stream(args).anyMatch(silentFlag::equalsIgnoreCase)) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.completedFuture(
                silentFlag.toLowerCase(Locale.ROOT).startsWith(partial.toLowerCase(Locale.ROOT))
                        ? List.of(silentFlag)
                        : List.of());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(permission);
    }
}
