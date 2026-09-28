package de.lovinoes.velocitynetworkplayerinfo.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.playeruuidcachevelocity.PlayerCacheAPI;
import de.lovinoes.playeruuidcachevelocity.PlayerRecord;
import de.lovinoes.networkutilitiescommon.chat.ChatColorParser;
import de.lovinoes.networkutilitiescommon.config.Dates;
import de.lovinoes.velocitynetworkutilities.api.VelocityNetworkAPI;
import de.lovinoes.velocitynetworkutilities.util.PlayerSuggestions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Renders the lines configured under pinfo.lines, in exactly the order they appear there.
 *
 * Nothing about the layout is fixed in code: lines can be reordered, removed, duplicated, or
 * left blank for spacing. The only rule is that a line naming a placeholder which has no value
 * for this player is skipped rather than printed with a hole in it, which is what makes an
 * offline-only line such as <last_seen> sit happily in the same list as the rest.
 */
public final class PlayerInfoCommand implements SimpleCommand {

    /**
     * Every placeholder the command can supply. A line is only ever skipped because of a name in
     * this set, so MiniMessage tags and unknown tags in a format are left completely alone.
     */
    private static final List<String> KNOWN_PLACEHOLDERS =
            List.of("player", "uuid", "first_login", "last_login", "last_seen", "status", "server");

    private final ProxyServer proxyServer;
    private final String usePermission;
    private final String seeVanishedPermission;
    private final DateTimeFormatter dateFormatter;
    private final List<String> lines;
    private final String onlineFormat;
    private final String offlineFormat;
    private final String notFoundLine;
    private final String usageLine;
    private final String lookupFailedLine;
    private final String copyUuidTooltip;

    public PlayerInfoCommand(ProxyServer proxyServer, String usePermission, String seeVanishedPermission,
                              String datePattern, List<String> lines, String onlineFormat, String offlineFormat,
                              String notFoundLine, String usageLine, String lookupFailedLine,
                              String copyUuidTooltip) {
        this.proxyServer = proxyServer;
        this.usePermission = usePermission;
        this.seeVanishedPermission = seeVanishedPermission;
        this.dateFormatter = Dates.formatter(datePattern, "dd.MM.yyyy HH:mm", Locale.GERMANY);
        this.lines = List.copyOf(lines);
        this.onlineFormat = onlineFormat;
        this.offlineFormat = offlineFormat;
        this.notFoundLine = notFoundLine;
        this.usageLine = usageLine;
        this.lookupFailedLine = lookupFailedLine;
        this.copyUuidTooltip = copyUuidTooltip;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        String targetName = args.length > 0
                ? args[0]
                : (invocation.source() instanceof Player self ? self.getUsername() : null);

        if (targetName == null) {
            invocation.source().sendMessage(ChatColorParser.parse(usageLine));
            return;
        }

        PlayerCacheAPI.get().findByUsername(targetName).thenAccept(record -> {
            if (record.isEmpty()) {
                invocation.source().sendMessage(
                        ChatColorParser.parse(notFoundLine, Placeholder.unparsed("target", targetName)));
                return;
            }
            render(invocation, record.get());
        }).exceptionally(throwable -> {
            invocation.source().sendMessage(ChatColorParser.parse(lookupFailedLine,
                    Placeholder.unparsed("target", targetName)));
            return null;
        });
    }

    private void render(Invocation invocation, PlayerRecord player) {
        // A vanished player counts as offline to anyone who cannot see them, which is the whole
        // point of vanish. The viewer's permission therefore decides which branch they get.
        boolean vanished = VelocityNetworkAPI.get().isVanished(player.uuid());
        boolean canSeeVanished = invocation.source().hasPermission(seeVanishedPermission);
        var online = proxyServer.getPlayer(player.uuid());
        boolean showOnline = online.isPresent() && (!vanished || canSeeVanished);

        String uuid = player.uuid().toString();
        Component name = clickableName(player.username(), uuid, copyUuidTooltip);

        Map<String, Component> values = new LinkedHashMap<>();
        values.put("player", name);
        values.put("uuid", Component.text(uuid));
        values.put("first_login", Component.text(dateFormatter.format(Instant.ofEpochMilli(player.firstJoin()))));
        values.put("last_login", Component.text(dateFormatter.format(Instant.ofEpochMilli(player.lastLogin()))));

        if (showOnline) {
            String server = online.get().getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName())
                    .orElse("");
            values.put("server", Component.text(server));
            values.put("status", ChatColorParser.parse(onlineFormat,
                    Placeholder.component("player", name),
                    Placeholder.unparsed("server", server)));
        } else {
            values.put("status", ChatColorParser.parse(offlineFormat,
                    Placeholder.component("player", name)));
            long lastSeen = lastSeenFor(player, online.isPresent() && vanished && !canSeeVanished);
            // last_logout stays 0 for anyone who has never had a disconnect recorded, so the
            // value is left out entirely rather than printing 01.01.1970. Any line using it is
            // then skipped. It is deliberately unavailable while the player is shown as online,
            // where "last seen" would just be their previous session.
            if (lastSeen > 0) {
                values.put("last_seen",
                        Component.text(dateFormatter.format(Instant.ofEpochMilli(lastSeen))));
            }
        }

        TagResolver[] resolvers = values.entrySet().stream()
                .map(entry -> (TagResolver) Placeholder.component(entry.getKey(), entry.getValue()))
                .toArray(TagResolver[]::new);

        for (String line : lines) {
            if (skip(line, values.keySet())) {
                continue;
            }
            invocation.source().sendMessage(ChatColorParser.parse(line, resolvers));
        }
    }

    /**
     * The player's name, carrying a click that copies their UUID and a hover explaining it.
     *
     * The name is built here rather than left to the format string because MiniMessage does not
     * resolve a placeholder used inside a tag's argument: writing
     * &lt;click:copy_to_clipboard:'&lt;uuid&gt;'&gt; in config would copy the literal text
     * "&lt;uuid&gt;". Attaching the event to the component sidesteps that entirely, and the
     * surrounding format still colours and styles the name as normal.
     *
     * An empty tooltip turns the whole thing off and gives back a plain name, since a click with
     * nothing to hint at it is a feature nobody discovers.
     */
    static Component clickableName(String username, String uuid, String tooltip) {
        Component name = Component.text(username);
        if (tooltip.isBlank()) {
            return name;
        }
        return name.clickEvent(ClickEvent.copyToClipboard(uuid))
                .hoverEvent(HoverEvent.showText(ChatColorParser.parse(tooltip,
                        Placeholder.unparsed("player", username),
                        Placeholder.unparsed("uuid", uuid))));
    }

    /**
     * The timestamp to present as "last seen".
     *
     * For anyone genuinely offline this is simply their recorded logout. For a vanished player
     * being shown as offline it is not, and the difference is a vanish leak: they are mid
     * session, so their recorded logout belongs to the session BEFORE the one they are in, and
     * lands earlier than their last login. A viewer reading "last login 21:40, last seen 19:02,
     * status offline" is looking at a logout that happened before the login, which is
     * impossible for a player who really left, and says plainly that they are online right now.
     *
     * Raising it to the login time removes the contradiction: the player reads as having
     * logged in and left again, which is an ordinary thing to have done. It is the closest
     * honest answer available, since the moment they vanished is not recorded anywhere.
     *
     * This deliberately lives at the display layer rather than being written into the player
     * cache when someone vanishes. It keys off exactly the same check that decides to show them
     * as offline, so every route into that state is covered by construction, including
     * connecting while already vanished, which no vanish-time hook would see.
     */
    private static long lastSeenFor(PlayerRecord player, boolean hiddenByVanish) {
        return hiddenByVanish ? Math.max(player.lastLogout(), player.lastLogin()) : player.lastLogout();
    }

    /**
     * A line is dropped when it names a placeholder this player has no value for. Only names in
     * {@link #KNOWN_PLACEHOLDERS} count, so a MiniMessage tag such as &lt;gray&gt; is never
     * mistaken for a missing value.
     */
    static boolean skip(String line, Set<String> available) {
        for (String name : KNOWN_PLACEHOLDERS) {
            if (!available.contains(name) && line.contains("<" + name + ">")) {
                return true;
            }
        }
        return false;
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
        return invocation.source().hasPermission(usePermission);
    }
}
