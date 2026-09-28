package de.lovinoes.networkutilitiescommon.vanish;

import de.lovinoes.networkutilitiescommon.database.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Persists proxy-wide vanish state so it survives proxy restarts and stays consistent across
 * multiple Velocity proxy instances sharing the same database.
 */
public final class NetworkVanishDao {

    private final DatabaseManager databaseManager;
    private final String table;

    public NetworkVanishDao(DatabaseManager databaseManager, String table) {
        this.databaseManager = databaseManager;
        this.table = table;
        createSchema();
    }

    private void createSchema() {
        databaseManager.executeVoid(connection -> {
            String ddl = "CREATE TABLE IF NOT EXISTS " + table + " (uuid VARCHAR(36) PRIMARY KEY, vanished BOOLEAN NOT NULL DEFAULT 0)";
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(ddl);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }).join();
    }

    public CompletableFuture<Set<UUID>> loadVanishedPlayers() {
        return databaseManager.execute(connection -> {
            Set<UUID> vanished = new HashSet<>();
            String sql = "SELECT uuid FROM " + table + " WHERE vanished = 1";
            try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
                while (resultSet.next()) {
                    vanished.add(UUID.fromString(resultSet.getString("uuid")));
                }
                return vanished;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<Boolean> isVanished(UUID uuid) {
        return databaseManager.execute(connection -> {
            String sql = "SELECT vanished FROM " + table + " WHERE uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() && resultSet.getBoolean("vanished");
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<Void> setVanished(UUID uuid, boolean vanished) {
        return databaseManager.executeVoid(connection -> {
            String upsert = switch (databaseManager.type()) {
                case SQLITE -> "INSERT INTO " + table + " (uuid, vanished) VALUES (?, ?) " +
                        "ON CONFLICT(uuid) DO UPDATE SET vanished = excluded.vanished";
                case MARIADB, MYSQL -> "INSERT INTO " + table + " (uuid, vanished) VALUES (?, ?) " +
                        "ON DUPLICATE KEY UPDATE vanished = VALUES(vanished)";
            };
            try (PreparedStatement statement = connection.prepareStatement(upsert)) {
                statement.setString(1, uuid.toString());
                statement.setBoolean(2, vanished);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
