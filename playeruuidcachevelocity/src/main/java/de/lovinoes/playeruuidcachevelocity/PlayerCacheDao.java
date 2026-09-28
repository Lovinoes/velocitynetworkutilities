package de.lovinoes.playeruuidcachevelocity;

import de.lovinoes.networkutilitiescommon.database.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PlayerCacheDao {

    private final DatabaseManager databaseManager;
    private final String table;

    public PlayerCacheDao(DatabaseManager databaseManager, String table) {
        this.databaseManager = databaseManager;
        this.table = table;
        createSchema();
    }

    private void createSchema() {
        databaseManager.executeVoid(connection -> {
            String ddl = "CREATE TABLE IF NOT EXISTS " + table + " (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "username VARCHAR(16) NOT NULL, " +
                    "first_join BIGINT NOT NULL, " +
                    "last_login BIGINT NOT NULL, " +
                    "last_logout BIGINT NOT NULL DEFAULT 0)";
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(ddl);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }).join();
    }

    public CompletableFuture<Optional<PlayerRecord>> findByUuid(UUID uuid) {
        return databaseManager.execute(connection -> {
            String sql = "SELECT uuid, username, first_join, last_login, last_logout FROM " + table + " WHERE uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                return mapSingle(statement);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<Optional<PlayerRecord>> findByUsername(String username) {
        return databaseManager.execute(connection -> {
            String sql = "SELECT uuid, username, first_join, last_login, last_logout FROM " + table + " WHERE username = ? ORDER BY last_login DESC LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, username);
                return mapSingle(statement);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Optional<PlayerRecord> mapSingle(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return Optional.empty();
            }
            return Optional.of(new PlayerRecord(
                    UUID.fromString(resultSet.getString("uuid")),
                    resultSet.getString("username"),
                    resultSet.getLong("first_join"),
                    resultSet.getLong("last_login"),
                    resultSet.getLong("last_logout")
            ));
        }
    }

    public CompletableFuture<Void> recordLogin(UUID uuid, String username, long timestamp) {
        return databaseManager.executeVoid(connection -> {
            String upsert = switch (databaseManager.type()) {
                case SQLITE -> "INSERT INTO " + table + " (uuid, username, first_join, last_login, last_logout) VALUES (?, ?, ?, ?, 0) " +
                        "ON CONFLICT(uuid) DO UPDATE SET username = excluded.username, last_login = excluded.last_login";
                case MARIADB, MYSQL -> "INSERT INTO " + table + " (uuid, username, first_join, last_login, last_logout) VALUES (?, ?, ?, ?, 0) " +
                        "ON DUPLICATE KEY UPDATE username = VALUES(username), last_login = VALUES(last_login)";
            };
            try (PreparedStatement statement = connection.prepareStatement(upsert)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, username);
                statement.setLong(3, timestamp);
                statement.setLong(4, timestamp);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<Void> recordLogout(UUID uuid, long timestamp) {
        return databaseManager.executeVoid(connection -> {
            String sql = "UPDATE " + table + " SET last_logout = ? WHERE uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, timestamp);
                statement.setString(2, uuid.toString());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
