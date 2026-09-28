package de.lovinoes.networkutilitiescommon.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.lovinoes.networkutilitiescommon.config.DatabaseType;
import de.lovinoes.networkutilitiescommon.config.YamlConfig;

import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

public final class DatabaseManager implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(DatabaseManager.class.getName());

    private final DatabaseType type;
    private final HikariDataSource dataSource;
    private final ExecutorService executor;

    public DatabaseManager(YamlConfig config, Path dataDirectory) {
        this(config, dataDirectory, "database");
    }

    public DatabaseManager(YamlConfig config, Path dataDirectory, String configRoot) {
        this.type = config.getEnum(configRoot + ".type", DatabaseType.MARIADB);
        this.executor = Executors.newFixedThreadPool(
                Math.max(2, config.getInt(configRoot + ".pool.maximum-pool-size", 10)),
                runnable -> {
                    Thread thread = new Thread(runnable, "NetworkUtilities-DB");
                    thread.setDaemon(true);
                    return thread;
                });
        this.dataSource = buildDataSource(config, dataDirectory, configRoot);
    }

    private HikariDataSource buildDataSource(YamlConfig config, Path dataDirectory, String configRoot) {
        HikariConfig hikariConfig = new HikariConfig();
        // Hikari rejects a pool of zero or fewer by throwing, which would stop the core plugin
        // loading and take every plugin that depends on it with it.
        int maximumPoolSize = Math.max(1, config.getInt(configRoot + ".pool.maximum-pool-size", 10));
        hikariConfig.setMaximumPoolSize(maximumPoolSize);
        hikariConfig.setMinimumIdle(Math.min(maximumPoolSize,
                Math.max(0, config.getInt(configRoot + ".pool.minimum-idle", 2))));
        hikariConfig.setConnectionTimeout(config.getLong(configRoot + ".pool.connection-timeout-ms", 8000));
        hikariConfig.setIdleTimeout(config.getLong(configRoot + ".pool.idle-timeout-ms", 600000));
        hikariConfig.setMaxLifetime(config.getLong(configRoot + ".pool.max-lifetime-ms", 1800000));

        switch (type) {
            case MARIADB, MYSQL -> {
                String driverClass = type == DatabaseType.MARIADB
                        ? "org.mariadb.jdbc.Driver"
                        : "com.mysql.cj.jdbc.Driver";
                String host = config.getString(configRoot + ".host", "127.0.0.1");
                int port = config.getInt(configRoot + ".port", 3306);
                String name = config.getString(configRoot + ".name", "velocity_network");
                String parameters = config.getString(configRoot + ".parameters", "");
                String scheme = type == DatabaseType.MARIADB ? "mariadb" : "mysql";
                hikariConfig.setDriverClassName(driverClass);
                hikariConfig.setJdbcUrl("jdbc:" + scheme + "://" + host + ":" + port + "/" + name + "?" + parameters);
                hikariConfig.setUsername(config.getString(configRoot + ".username", "root"));
                hikariConfig.setPassword(config.getString(configRoot + ".password", ""));
            }
            case SQLITE -> {
                Path file = dataDirectory.resolve(config.getString(configRoot + ".sqlite.file", "data.db")).toAbsolutePath();
                file.getParent().toFile().mkdirs();
                hikariConfig.setDriverClassName("org.sqlite.JDBC");
                hikariConfig.setJdbcUrl("jdbc:sqlite:" + file);
                hikariConfig.setMaximumPoolSize(1);
            }
        }

        return new HikariDataSource(hikariConfig);
    }

    public DatabaseType type() {
        return type;
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * The returned future completes exceptionally exactly as before on failure (callers that
     * chain on it, or call join()/get(), still see the original exception). This only adds a
     * guaranteed log line, so a caller that never attaches a continuation (a fire-and-forget
     * write) doesn't fail completely silently.
     */
    public <T> CompletableFuture<T> execute(Function<Connection, T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = connection()) {
                return task.apply(connection);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, executor).whenComplete((result, throwable) -> {
            if (throwable != null) {
                LOGGER.log(Level.ERROR, "Database operation failed", throwable);
            }
        });
    }

    public CompletableFuture<Void> executeVoid(java.util.function.Consumer<Connection> task) {
        return execute(connection -> {
            task.accept(connection);
            return null;
        });
    }

    @Override
    public void close() {
        executor.shutdown();
        dataSource.close();
    }
}
