package de.lovinoes.velocitynetworkmoderation.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.playeruuidcachevelocity.PlayerCacheAPI;
import de.lovinoes.playeruuidcachevelocity.PlayerRecord;
import de.lovinoes.velocitynetworkmoderation.Messages;
import de.lovinoes.velocitynetworkmoderation.punishment.Addresses;
import de.lovinoes.velocitynetworkmoderation.punishment.DurationParser;
import de.lovinoes.velocitynetworkmoderation.punishment.Punishment;
import de.lovinoes.velocitynetworkmoderation.punishment.PunishmentManager;
import de.lovinoes.velocitynetworkmoderation.punishment.PunishmentType;
import de.lovinoes.velocitynetworkutilities.util.PlayerSuggestions;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One command for every punishment that is handed out: ban, ip-ban, mute, kick and warn.
 *
 * They differ only in the type, so writing five near-identical classes would just be five places
 * for the same bug to live.
 *
 * Syntax is /command &lt;player&gt; [duration] [reason...]. The duration is optional and
 * positional, which needs care: "/ban Notch griefing" must read "griefing" as the reason, not as
 * a mangled duration. DurationParser only accepts a string that is entirely a duration, so a
 * word that is not one falls through to the reason untouched.
 */
public final class PunishCommand implements SimpleCommand {

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
    private final List<String> durationSuggestions;
    private final long defaultDuration;
    private final int maxReasonLength;

    public PunishCommand(ProxyServer proxyServer, PunishmentManager punishments, Messages messages,
                          PunishmentType type, String messageKey, String permission, String seeVanishedPermission,
                          String broadcastPermission, String silentFlag, String silentBroadcastPermission,
                          List<String> durationSuggestions, long defaultDuration, int maxReasonLength) {
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
        this.durationSuggestions = List.copyOf(durationSuggestions);
        this.defaultDuration = defaultDuration;
        this.maxReasonLength = maxReasonLength;
    }

    /** The arguments with the silent flag taken out, and whether it was there. */
    record ParsedArguments(List<String> words, boolean silent) {
    }

    /**
     * Pulls the silent flag out from wherever it appears, so "/ban Notch -s 7d x" and
     * "/ban Notch 7d x -s" both work and neither leaves the flag sitting in the reason.
     *
     * Empty words are dropped too. Velocity splits on spaces without discarding empties, so a
     * trailing space arrives as an empty final argument that is not an argument at all.
     */
    static ParsedArguments stripSilentFlag(String[] arguments, String silentFlag) {
        List<String> words = new ArrayList<>();
        boolean silent = false;
        for (String argument : arguments) {
            if (!silentFlag.isEmpty() && argument.equalsIgnoreCase(silentFlag)) {
                silent = true;
            } else if (!argument.isEmpty()) {
                words.add(argument);
            }
        }
        return new ParsedArguments(words, silent);
    }

    @Override
    public void execute(Invocation invocation) {
        ParsedArguments parsed = stripSilentFlag(invocation.arguments(), silentFlag);
        List<String> words = parsed.words();
        boolean silent = parsed.silent();

        if (words.isEmpty()) {
            invocation.source().sendMessage(messages.line("commands." + messageKey + ".usage"));
            return;
        }

        String[] args = words.toArray(String[]::new);
        String targetName = args[0];
        int reasonStart = 1;
        long duration = defaultDuration;

        if (type.isTemporal() && args.length > 1) {
            OptionalLong parsedDuration = DurationParser.parse(args[1]);
            if (parsedDuration.isPresent()) {
                duration = parsedDuration.getAsLong();
                reasonStart = 2;
            }
        }

        String reason = buildReason(args, reasonStart);
        long finalDuration = duration;
        boolean finalSilent = silent;

        resolve(targetName).thenAccept(record -> {
            if (record == null) {
                invocation.source().sendMessage(messages.line("errors.player-not-found",
                        Placeholder.unparsed("target", targetName)));
                return;
            }
            apply(invocation, record, reason, finalDuration, finalSilent);
        }).exceptionally(throwable -> {
            invocation.source().sendMessage(messages.line("errors.lookup-failed",
                    Placeholder.unparsed("target", targetName)));
            return null;
        });
    }

    private void apply(Invocation invocation, PlayerRecord record, String reason, long duration, boolean silent) {
        if (punishments.isExempt(record.uuid())) {
            invocation.source().sendMessage(messages.line("errors.exempt",
                    Placeholder.unparsed("target", record.username())));
            return;
        }

        addressOf(record.uuid()).thenAccept(address -> {
            if (type == PunishmentType.IP_BAN && address == null) {
                invocation.source().sendMessage(messages.line("errors.no-address",
                        Placeholder.unparsed("target", record.username())));
                return;
            }
            punishments.punish(type, record.uuid(), record.username(), address, invocation.source(),
                            reason, duration)
                    .thenAccept(stored -> announce(invocation, stored, silent))
                    .exceptionally(throwable -> {
                        invocation.source().sendMessage(messages.line("errors.save-failed"));
                        return null;
                    });
        }).exceptionally(throwable -> {
            invocation.source().sendMessage(messages.line("errors.save-failed"));
            return null;
        });
    }

    private void announce(Invocation invocation, Punishment punishment, boolean silent) {
        long now = System.currentTimeMillis();
        var placeholders = messages.placeholders(punishment, now);

        invocation.source().sendMessage(messages.line("commands." + messageKey + ".success", placeholders));
        if (silent && !messages.isBlank("words.silent-notice")) {
            invocation.source().sendMessage(messages.line("words.silent-notice", placeholders));
        }

        // A silent punishment still happens and is still recorded and still shown to the player
        // it lands on. The only thing it changes is who is told about it: the see-silent
        // permission instead of the network, so staff keep their audit trail. The console is
        // told either way, since that is the log.
        String broadcastPath = "broadcast." + messageKey;
        if (!messages.isBlank(broadcastPath)) {
            punishments.broadcast(messages.line(broadcastPath, placeholders),
                    silent ? silentBroadcastPermission : broadcastPermission, silent);
        }

        // A kick has no standing state to enforce later, so the disconnect IS the punishment.
        // A ban needs the player removed now as well as blocked next time.
        if (type == PunishmentType.KICK) {
            disconnect(punishment, "screens.kick", now);
        } else if (type.blocksLogin()) {
            punishments.enforceBan(punishment, messages.screen(
                    type == PunishmentType.IP_BAN ? "screens.ip-ban" : "screens.ban", placeholders));
        } else if (type == PunishmentType.WARN) {
            notifyVictim(punishment, "screens.warn", now);
        } else if (type == PunishmentType.MUTE) {
            notifyVictim(punishment, "screens.mute", now);
        }
    }

    private void disconnect(Punishment punishment, String screen, long now) {
        if (punishment.victimUuid() == null) {
            return;
        }
        proxyServer.getPlayer(punishment.victimUuid()).ifPresent(player ->
                player.disconnect(messages.screen(screen, messages.placeholders(punishment, now))));
    }

    private void notifyVictim(Punishment punishment, String screen, long now) {
        if (punishment.victimUuid() == null) {
            return;
        }
        proxyServer.getPlayer(punishment.victimUuid()).ifPresent(player ->
                player.sendMessage(messages.screen(screen, messages.placeholders(punishment, now))));
    }

    /**
     * The cache, which falls back to Mojang for a name it has never seen. Punishing someone who
     * has never joined is a real need: a moderator may be acting on a report before the player
     * comes back.
     */
    private CompletableFuture<PlayerRecord> resolve(String name) {
        return PlayerCacheAPI.get().resolve(name).thenApply(found -> found.orElse(null));
    }

    /**
     * Asynchronous on purpose, and it must stay that way.
     *
     * This runs inside a callback on a future the database completed, which means it is already
     * on a thread from the database's own fixed pool. Blocking there to wait for another
     * database call occupies one pool thread while needing a second: enough of these at once
     * and every thread in the pool is waiting for a thread in the pool, and the database layer
     * stops answering anyone, proxy wide.
     */
    private CompletableFuture<String> addressOf(UUID uuid) {
        return proxyServer.getPlayer(uuid)
                .map(player -> CompletableFuture.completedFuture(Addresses.of(player)))
                .orElseGet(() -> punishments.dao().lastAddress(uuid)
                        .thenApply(found -> found.orElse(null)));
    }


    private String buildReason(String[] args, int from) {
        String reason = from >= args.length
                ? ""
                : String.join(" ", List.of(args).subList(from, args.length)).trim();
        if (reason.isEmpty()) {
            return messages.plain("words.default-reason", "No reason given");
        }
        // Bounded so a pasted wall of text cannot overflow the column and fail the insert.
        return reason.length() > maxReasonLength ? reason.substring(0, maxReasonLength) : reason;
    }

    /**
     * Velocity splits the arguments on spaces without dropping empties, so the last element is
     * always the word being typed and the count says which position it is. A trailing space
     * therefore gives an extra empty element, which is how "finished the name" is told apart
     * from "still typing the name".
     */
    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        String partial = args.length == 0 ? "" : args[args.length - 1];

        if (args.length <= 1) {
            return CompletableFuture.completedFuture(
                    PlayerSuggestions.matching(proxyServer, invocation.source(), partial, seeVanishedPermission));
        }

        return CompletableFuture.completedFuture(
                suggestionsAfterName(args, type.isTemporal(), durationSuggestions, silentFlag));
    }

    /**
     * What to offer once the name has been typed.
     *
     * The duration only means anything in the second position, because that is the only one the
     * parser inspects, so offering it later would suggest something that silently becomes part
     * of the reason. The silent flag is useful anywhere after the name, but only until it has
     * been typed once.
     */
    static List<String> suggestionsAfterName(String[] args, boolean temporal,
                                              List<String> durationSuggestions, String silentFlag) {
        String partial = args.length == 0 ? "" : args[args.length - 1];
        List<String> options = new ArrayList<>();

        // Position 2 counting the name, allowing for the flag having been typed before it.
        boolean flagAlreadyTyped = !silentFlag.isEmpty()
                && Arrays.stream(args).anyMatch(silentFlag::equalsIgnoreCase);
        int wordsBeforePartial = args.length - 1 - (flagAlreadyTyped ? 1 : 0);
        if (temporal && wordsBeforePartial == 1) {
            options.addAll(durationSuggestions);
        }
        if (!silentFlag.isEmpty() && !flagAlreadyTyped) {
            options.add(silentFlag);
        }

        String lower = partial.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(permission);
    }
}
