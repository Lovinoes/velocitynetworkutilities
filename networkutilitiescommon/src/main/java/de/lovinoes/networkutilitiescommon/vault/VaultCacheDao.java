package de.lovinoes.networkutilitiescommon.vault;

import de.lovinoes.networkutilitiescommon.database.DatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Shared network_vault_cache table. Vault only runs on backend servers, so this table is the
 * bridge: an external permission-plugin hook populates uuid, primary_group, prefix and suffix,
 * and VelocityNetworkChat reads from it to render MiniMessage placeholders on the proxy.
 */
public final class VaultCacheDao {

    private final DatabaseManager databaseManager;
    private final String table;

    public VaultCacheDao(DatabaseManager databaseManager, String table) {
        this.databaseManager = databaseManager;
        this.table = table;
        createSchema();
    }

    private void createSchema() {
        databaseManager.executeVoid(connection -> {
            String ddl = "CREATE TABLE IF NOT EXISTS " + table + " (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "username VARCHAR(16) NOT NULL, " +
                    "primary_group VARCHAR(64) NOT NULL DEFAULT 'default', " +
                    "prefix VARCHAR(128) NOT NULL DEFAULT '', " +
                    "suffix VARCHAR(128) NOT NULL DEFAULT '', " +
                    "updated_at BIGINT NOT NULL)";
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(ddl);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }).join();
    }

    public CompletableFuture<Void> upsert(UUID uuid, String username, String primaryGroup, String prefix, String suffix) {
        return databaseManager.executeVoid(connection -> {
            String upsert = switch (databaseManager.type()) {
                case SQLITE -> "INSERT INTO " + table + " (uuid, username, primary_group, prefix, suffix, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(uuid) DO UPDATE SET username = excluded.username, primary_group = excluded.primary_group, " +
                        "prefix = excluded.prefix, suffix = excluded.suffix, updated_at = excluded.updated_at";
                case MARIADB, MYSQL -> "INSERT INTO " + table + " (uuid, username, primary_group, prefix, suffix, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE username = VALUES(username), primary_group = VALUES(primary_group), " +
                        "prefix = VALUES(prefix), suffix = VALUES(suffix), updated_at = VALUES(updated_at)";
            };
            try (PreparedStatement statement = connection.prepareStatement(upsert)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, username);
                statement.setString(3, primaryGroup);
                statement.setString(4, prefix);
                statement.setString(5, suffix);
                statement.setLong(6, System.currentTimeMillis());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<Optional<VaultCacheRecord>> find(UUID uuid) {
        return databaseManager.execute(connection -> {
            String sql = "SELECT uuid, username, primary_group, prefix, suffix, updated_at FROM " + table + " WHERE uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new VaultCacheRecord(
                            UUID.fromString(resultSet.getString("uuid")),
                            resultSet.getString("username"),
                            resultSet.getString("primary_group"),
                            resultSet.getString("prefix"),
                            resultSet.getString("suffix"),
                            resultSet.getLong("updated_at")
                    ));
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
