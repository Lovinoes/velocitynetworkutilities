package de.lovinoes.velocitynetworkmoderation;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lovinoes.networkutilitiescommon.config.Language;
import de.lovinoes.networkutilitiescommon.config.YamlConfig;
import de.lovinoes.networkutilitiescommon.vanish.VanishPermissions;
import de.lovinoes.velocitynetworkmoderation.command.HistoryCommand;
import de.lovinoes.velocitynetworkmoderation.command.PunishCommand;
import de.lovinoes.velocitynetworkmoderation.command.RevokeCommand;
import de.lovinoes.velocitynetworkmoderation.filter.ChatFilter;
import de.lovinoes.velocitynetworkmoderation.filter.WordFilter;
import de.lovinoes.velocitynetworkmoderation.listener.ConnectionListener;
import de.lovinoes.velocitynetworkmoderation.listener.FilterListener;
import de.lovinoes.velocitynetworkmoderation.listener.MuteListener;
import de.lovinoes.velocitynetworkmoderation.punishment.DurationParser;
import de.lovinoes.velocitynetworkmoderation.punishment.Punishment;
import de.lovinoes.velocitynetworkmoderation.punishment.PunishmentDao;
import de.lovinoes.velocitynetworkmoderation.punishment.PunishmentManager;
import de.lovinoes.velocitynetworkmoderation.punishment.PunishmentType;
import de.lovinoes.velocitynetworkutilities.api.VelocityNetworkAPI;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "velocitynetworkmoderation",
        name = "VelocityNetworkModeration",
        version = "1.0.0",
        authors = {"Lovinoes"},
        dependencies = {
                @Dependency(id = "velocitynetworkutilities"),
                @Dependency(id = "playeruuidcachevelocity"),
                @Dependency(id = "velocitynetworkvanish", optional = true),
                @Dependency(id = "velocitynetworkchat", optional = true)
        }
)
public final class VelocityNetworkModerationPlugin {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;

    private PunishmentManager punishmentManager;

    @Inject
    public VelocityNetworkModerationPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        YamlConfig config = YamlConfig.load(dataDirectory, "config.yml", getClass().getClassLoader());
        System.Logger moduleLogger = System.getLogger(getClass().getName());

        // Wording comes from the language file chosen in config.yml; config.yml holds settings.
        YamlConfig language = Language.load(dataDirectory, config, getClass().getClassLoader());

        Messages messages = new Messages(language,
                config.getString("date-format", "dd.MM.yyyy HH:mm 'Uhr'"),
                language.getString("words.permanent", "permanent"),
                language.getString("words.console", "Console"));

        PunishmentDao dao = new PunishmentDao(VelocityNetworkAPI.get().database(),
                config.getString("tables.punishments", "network_punishments"),
                config.getString("tables.addresses", "network_player_addresses"));

        punishmentManager = new PunishmentManager(proxyServer, dao, messages,
                config.getString("permissions.exempt", "velocitynetworkmoderation.exempt"), moduleLogger);
        punishmentManager.start();

        proxyServer.getEventManager().register(this,
                new ConnectionListener(punishmentManager, messages, moduleLogger));
        proxyServer.getEventManager().register(this,
                new MuteListener(punishmentManager, messages, config.getStringList("mute.blocked-commands")));

        if (config.getBoolean("filter.enabled", true)) {
            registerFilter(config, messages, moduleLogger);
        }

        registerCommands(config, messages);

        logger.info("VelocityNetworkModeration initialized.");
    }

    /**
     * The chat filter: its word list from filter.yml, and everything else from config.yml. A
     * category or punishment written wrongly is skipped with a warning naming it, rather than
     * stopping the plugin, which would take mutes and bans down with it.
     */
    private void registerFilter(YamlConfig config, Messages messages, System.Logger moduleLogger) {
        YamlConfig wordList = YamlConfig.load(dataDirectory, "filter.yml", getClass().getClassLoader());
        WordFilter words = WordFilter.compile(filterCategories(wordList), wordList.getStringList("allowed"),
                moduleLogger);

        ChatFilter filter = new ChatFilter(proxyServer, messages, words,
                config.getString("filter.censor-character", "*"),
                config.getString("filter.bypass-permission", "velocitynetworkmoderation.filter.bypass"),
                config.getString("filter.notify-permission", "velocitynetworkmoderation.filter.notify"),
                filterTiers(config), System::currentTimeMillis, moduleLogger);

        proxyServer.getEventManager().register(this, new FilterListener(proxyServer, filter,
                config.getStringList("filter.commands"), config.getStringList("filter.commands-with-target")));
        // Offences are otherwise only trimmed when the same player offends again.
        proxyServer.getScheduler().buildTask(this, filter::prune).repeat(5, TimeUnit.MINUTES).schedule();

        logger.info("Chat filter active with {} entries.", words.size());
    }

    private List<WordFilter.Category> filterCategories(YamlConfig wordList) {
        List<WordFilter.Category> categories = new ArrayList<>();
        for (Map.Entry<String, Object> entry : wordList.getSection("categories").entrySet()) {
            String name = entry.getKey();
            if (!(entry.getValue() instanceof Map<?, ?> section)) {
                logger.warn("filter.yml: category '{}' is not a section with action, counts and words; skipping it.",
                        name);
                continue;
            }
            // Absent means on, so a category written without the switch still does something.
            if (Boolean.FALSE.equals(section.get("enabled"))) {
                continue;
            }
            String rawAction = String.valueOf(section.get("action")).strip();
            WordFilter.Action action;
            if ("block".equalsIgnoreCase(rawAction)) {
                action = WordFilter.Action.BLOCK;
            } else if ("censor".equalsIgnoreCase(rawAction)) {
                action = WordFilter.Action.CENSOR;
            } else {
                // Block, not censor: a typo must not quietly make a category more lenient.
                logger.warn("filter.yml: category '{}' has action '{}', which is not censor or block. Using block.",
                        name, rawAction);
                action = WordFilter.Action.BLOCK;
            }
            boolean counts = !(section.get("counts") instanceof Boolean flag) || flag;
            List<String> entries = new ArrayList<>();
            if (section.get("words") instanceof List<?> list) {
                for (Object word : list) {
                    if (word != null) {
                        entries.add(String.valueOf(word));
                    }
                }
            }
            categories.add(new WordFilter.Category(name, action, counts, entries));
        }
        return categories;
    }

    private List<ChatFilter.Tier> filterTiers(YamlConfig config) {
        List<ChatFilter.Tier> tiers = new ArrayList<>();
        if (!(config.get("filter.punishments") instanceof List<?> list)) {
            return tiers;
        }
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> section)) {
                logger.warn("filter.punishments entry {} is not a section with violations, within and commands; "
                        + "skipping it.", i + 1);
                continue;
            }
            int violations = section.get("violations") instanceof Number number ? number.intValue() : 0;
            OptionalLong within = DurationParser.parse(String.valueOf(section.get("within")));
            if (violations < 1 || within.isEmpty() || within.getAsLong() == Punishment.PERMANENT) {
                logger.warn("filter.punishments entry {} needs violations of 1 or more and a within such as 10m; "
                        + "skipping it.", i + 1);
                continue;
            }
            List<String> commands = new ArrayList<>();
            if (section.get("commands") instanceof List<?> raw) {
                for (Object command : raw) {
                    if (command != null && !String.valueOf(command).isBlank()) {
                        commands.add(String.valueOf(command));
                    }
                }
            }
            if (commands.isEmpty()) {
                logger.warn("filter.punishments entry {} has no commands; skipping it.", i + 1);
                continue;
            }
            tiers.add(new ChatFilter.Tier(violations, within.getAsLong(), List.copyOf(commands)));
        }
        return tiers;
    }

    private void registerCommands(YamlConfig config, Messages messages) {
        CommandManager commandManager = proxyServer.getCommandManager();
        String seeVanished = VanishPermissions.SEE_VANISHED;
        String broadcastPermission = config.getString("permissions.see-broadcasts", "");
        // Clamped: a negative length would make substring throw, and a zero one would store
        // every reason as empty. Neither is what anybody typing a number here meant.
        int maxReason = Math.max(16, config.getInt("max-reason-length", 255));

        register(commandManager, config, messages, PunishmentType.BAN, "ban", seeVanished, broadcastPermission, maxReason);
        register(commandManager, config, messages, PunishmentType.IP_BAN, "banip", seeVanished, broadcastPermission, maxReason);
        register(commandManager, config, messages, PunishmentType.MUTE, "mute", seeVanished, broadcastPermission, maxReason);
        register(commandManager, config, messages, PunishmentType.KICK, "kick", seeVanished, broadcastPermission, maxReason);
        register(commandManager, config, messages, PunishmentType.WARN, "warn", seeVanished, broadcastPermission, maxReason);

        registerRevoke(commandManager, config, messages, PunishmentType.BAN, "unban", seeVanished, broadcastPermission);
        registerRevoke(commandManager, config, messages, PunishmentType.IP_BAN, "unbanip", seeVanished, broadcastPermission);
        registerRevoke(commandManager, config, messages, PunishmentType.MUTE, "unmute", seeVanished, broadcastPermission);

        String historyName = config.getString("commands.history.name", "history");
        CommandMeta historyMeta = commandManager.metaBuilder(historyName)
                .aliases(config.getStringList("commands.history.aliases").toArray(String[]::new))
                .plugin(this)
                .build();
        commandManager.register(historyMeta, new HistoryCommand(proxyServer, punishmentManager, messages,
                config.getString("permissions.history", "velocitynetworkmoderation.history"),
                config.getString("permissions.history-clear", "velocitynetworkmoderation.history.clear"),
                seeVanished, Math.max(1, config.getInt("history-limit", 25)), historyWords(config)));
    }

    /**
     * The words after /history &lt;player&gt;. Each has to be one word, and clear and remove must
     * differ, or the command could not tell them apart; anything else falls back with a warning.
     */
    private HistoryCommand.Words historyWords(YamlConfig config) {
        String clear = historyWord(config, "clear", "clear");
        String confirm = historyWord(config, "confirm", "confirm");
        String remove = historyWord(config, "remove", "remove");
        if (clear.equals(remove)) {
            logger.warn("commands.history.clear and commands.history.remove are both '{}'. Using clear and remove.",
                    clear);
            clear = "clear";
            remove = "remove";
        }
        return new HistoryCommand.Words(clear, confirm, remove);
    }

    private String historyWord(YamlConfig config, String key, String fallback) {
        String word = config.getString("commands.history." + key, fallback).strip().toLowerCase(Locale.ROOT);
        if (word.isEmpty() || word.contains(" ")) {
            logger.warn("commands.history.{} is '{}', which is not a single word. Using {}.", key, word, fallback);
            return fallback;
        }
        return word;
    }

    private void register(CommandManager commandManager, YamlConfig config, Messages messages, PunishmentType type,
                           String key, String seeVanished, String broadcastPermission, int maxReason) {
        String name = config.getString("commands." + key + ".name", key);
        // A blank default means permanent, which is what a bare /ban should be. A bad value is
        // reported rather than silently becoming permanent, since that is the harsher outcome.
        String rawDefault = config.getString("commands." + key + ".default-duration", "");
        long defaultDuration = Punishment.PERMANENT;
        if (!rawDefault.isBlank()) {
            var parsed = DurationParser.parse(rawDefault);
            if (parsed.isPresent()) {
                defaultDuration = parsed.getAsLong();
            } else {
                logger.warn("commands.{}.default-duration is '{}', which is not a duration. Using permanent.",
                        key, rawDefault);
            }
        }

        CommandMeta meta = commandManager.metaBuilder(name)
                .aliases(config.getStringList("commands." + key + ".aliases").toArray(String[]::new))
                .plugin(this)
                .build();
        commandManager.register(meta, new PunishCommand(proxyServer, punishmentManager, messages, type, key,
                config.getString("permissions." + key, "velocitynetworkmoderation." + key),
                seeVanished, broadcastPermission,
                config.getString("silent-flag", "-s"),
                config.getString("permissions.see-silent", "velocitynetworkmoderation.seesilent"),
                durationSuggestions(config), defaultDuration, maxReason));
    }

    private void registerRevoke(CommandManager commandManager, YamlConfig config, Messages messages,
                                 PunishmentType type, String key, String seeVanished, String broadcastPermission) {
        String name = config.getString("commands." + key + ".name", key);
        List<String> aliases = config.getStringList("commands." + key + ".aliases");
        CommandMeta meta = commandManager.metaBuilder(name)
                .aliases(aliases.toArray(String[]::new))
                .plugin(this)
                .build();
        commandManager.register(meta, new RevokeCommand(proxyServer, punishmentManager, messages, type, key,
                config.getString("permissions." + key, "velocitynetworkmoderation." + key),
                seeVanished, broadcastPermission,
                config.getString("silent-flag", "-s"),
                config.getString("permissions.see-silent", "velocitynetworkmoderation.seesilent")));
    }

    /**
     * Offered in the duration position. Purely a convenience: anything DurationParser accepts
     * can still be typed, these are just the ones worth one keystroke.
     */
    private static List<String> durationSuggestions(YamlConfig config) {
        List<String> configured = config.getStringList("duration-suggestions");
        return configured.isEmpty()
                ? List.of("10m", "30m", "1h", "6h", "12h", "1d", "3d", "7d", "14d", "30d", "perm")
                : configured;
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (punishmentManager != null) {
            punishmentManager.stop();
        }
    }
}
