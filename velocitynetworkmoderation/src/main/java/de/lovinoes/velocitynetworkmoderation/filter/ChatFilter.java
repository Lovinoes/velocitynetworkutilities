package de.lovinoes.velocitynetworkmoderation.filter;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.velocitynetworkmoderation.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * What happens when a player uses a listed word: the message is censored or blocked, staff are
 * told, and repeat offences run the configured punishment commands.
 *
 * Offences are remembered per player in memory for as long as the longest punishment window,
 * across reconnects, so leaving and rejoining does not wipe the slate.
 */
public final class ChatFilter {

    public enum Kind { CLEAN, CENSORED, BLOCKED }

    /** What to do with the message. {@code text} is the censored text, only for CENSORED. */
    public record Outcome(Kind kind, String text) {
        static final Outcome CLEAN = new Outcome(Kind.CLEAN, null);
        static final Outcome BLOCKED = new Outcome(Kind.BLOCKED, null);
    }

    /**
     * Run {@code commands} when a player's counted offences within {@code withinMillis} reach
     * exactly {@code violations}. Exactly, so each tier fires once as the count climbs past it
     * rather than again on every offence after.
     */
    public record Tier(int violations, long withinMillis, List<String> commands) {
    }

    private final ProxyServer proxyServer;
    private final Messages messages;
    private final WordFilter words;
    private final String mask;
    private final String bypassPermission;
    private final String notifyPermission;
    private final List<Tier> tiers;
    private final long longestWindow;
    private final LongSupplier clock;
    private final System.Logger logger;

    private final Map<UUID, Deque<Long>> offences = new ConcurrentHashMap<>();

    public ChatFilter(ProxyServer proxyServer, Messages messages, WordFilter words, String mask,
                      String bypassPermission, String notifyPermission, List<Tier> tiers,
                      LongSupplier clock, System.Logger logger) {
        this.proxyServer = proxyServer;
        this.messages = messages;
        this.words = words;
        this.mask = mask;
        this.bypassPermission = bypassPermission == null ? "" : bypassPermission.trim();
        this.notifyPermission = notifyPermission == null ? "" : notifyPermission.trim();
        this.tiers = List.copyOf(tiers);
        this.longestWindow = tiers.stream().mapToLong(Tier::withinMillis).max().orElse(0);
        this.clock = clock;
        this.logger = logger;
    }

    public boolean exempt(Player player) {
        return !bypassPermission.isEmpty() && player.hasPermission(bypassPermission);
    }

    /**
     * @param text      what the player wrote; for a command, only the part being checked
     * @param mayCensor false where changing the text is not safe, which turns a censor into a
     *                  block, so a listed word is never simply let through
     * @param command   the command it was typed in, or null for chat
     */
    public Outcome apply(Player player, String text, boolean mayCensor, String command) {
        if (exempt(player)) {
            return Outcome.CLEAN;
        }
        WordFilter.Result result = words.check(text);
        if (result.isClean()) {
            return Outcome.CLEAN;
        }

        boolean blocked = result.blocks() || !mayCensor;
        notifyStaff(player, text, result, command);

        if (blocked) {
            tell(player, "filter.blocked");
        } else {
            tell(player, "filter.censored");
        }
        if (result.counts()) {
            recordOffence(player);
        }
        return blocked ? Outcome.BLOCKED : new Outcome(Kind.CENSORED, WordFilter.censor(text, result.hits(), mask));
    }

    private void tell(Player player, String path) {
        if (!messages.isBlank(path)) {
            player.sendMessage(messages.line(path));
        }
    }

    private void notifyStaff(Player player, String text, WordFilter.Result result, String command) {
        String path = command == null ? "filter.notify-chat" : "filter.notify-command";
        if (messages.isBlank(path)) {
            return;
        }
        TagResolver[] placeholders = {
                Placeholder.unparsed("player", player.getUsername()),
                // Unparsed: what the player typed is shown exactly, never interpreted as tags.
                Placeholder.unparsed("message", text.strip()),
                Placeholder.unparsed("category", String.join(", ", result.categories())),
                Placeholder.unparsed("command", command == null ? "" : command)
        };
        Component notice = messages.line(path, placeholders);
        // The console is the log, so it always hears, whatever the notify permission says.
        proxyServer.getConsoleCommandSource().sendMessage(notice);
        if (notifyPermission.isEmpty()) {
            return;
        }
        for (Player online : proxyServer.getAllPlayers()) {
            if (online.hasPermission(notifyPermission)) {
                online.sendMessage(notice);
            }
        }
    }

    /**
     * Records the offence and works out which tiers it completes in one atomic step per player,
     * so two offences arriving together cannot both see the same count and both fire a tier, or
     * both miss it.
     */
    private void recordOffence(Player player) {
        if (tiers.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        List<Tier> reached = new ArrayList<>();
        offences.compute(player.getUniqueId(), (id, times) -> {
            Deque<Long> list = times != null ? times : new ArrayDeque<>();
            list.addLast(now);
            while (!list.isEmpty() && now - list.peekFirst() >= longestWindow) {
                list.removeFirst();
            }
            for (Tier tier : tiers) {
                long within = list.stream().filter(at -> now - at < tier.withinMillis()).count();
                if (within == tier.violations()) {
                    reached.add(tier);
                }
            }
            return list.isEmpty() ? null : list;
        });
        for (Tier tier : reached) {
            punish(player, tier);
        }
    }

    private void punish(Player player, Tier tier) {
        // Only the name, the id and a reason from the language file ever go into the command.
        // The message itself never does: it is the player's own text and would let them write
        // arguments into a command the console runs.
        String reason = messages.plain("filter.punishment-reason", "").replace('\n', ' ').replace('\r', ' ').strip();
        for (String template : tier.commands()) {
            String command = template
                    .replace("<player>", player.getUsername())
                    .replace("<uuid>", player.getUniqueId().toString())
                    .replace("<reason>", reason)
                    .strip();
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            if (command.isEmpty()) {
                continue;
            }
            String toRun = command;
            proxyServer.getCommandManager().executeAsync(proxyServer.getConsoleCommandSource(), toRun)
                    .whenComplete((ran, failure) -> {
                        if (failure != null) {
                            logger.log(System.Logger.Level.WARNING,
                                    "Filter punishment command '" + toRun + "' failed.", failure);
                        } else if (!Boolean.TRUE.equals(ran)) {
                            logger.log(System.Logger.Level.WARNING,
                                    "Filter punishment command ''{0}'' is not a command the proxy knows. "
                                            + "Check filter.punishments in config.yml.", toRun);
                        }
                    });
        }
    }

    /** Drops offences older than every window, and players with none left. */
    public void prune() {
        long now = clock.getAsLong();
        for (UUID id : List.copyOf(offences.keySet())) {
            offences.computeIfPresent(id, (key, times) -> {
                while (!times.isEmpty() && now - times.peekFirst() >= longestWindow) {
                    times.removeFirst();
                }
                return times.isEmpty() ? null : times;
            });
        }
    }

    /** How many offences are remembered for a player, for tests and diagnostics. */
    public int offenceCount(UUID player) {
        // Read under the same per-key lock the writes take, never straight off the map.
        int[] count = {0};
        offences.computeIfPresent(player, (id, times) -> {
            count[0] = times.size();
            return times;
        });
        return count[0];
    }
}
