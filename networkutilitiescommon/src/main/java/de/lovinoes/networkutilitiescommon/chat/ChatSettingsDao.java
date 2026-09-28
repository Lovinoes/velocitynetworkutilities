package de.lovinoes.networkutilitiescommon.chat;

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
 * Per-player chat preferences that need to survive a relog.
 *
 * Only players who have turned their mention sound OFF get a row. The default is on, so storing
 * the exceptions keeps the table to a handful of rows and lets the whole opt-out list be loaded
 * once at startup instead of querying on every join.
 */
public final class ChatSettingsDao {

    private final DatabaseManager databaseManager;
    private final String table;

    public ChatSettingsDao(DatabaseManager databaseManager, String table) {
        this.databaseManager = databaseManager;
        this.table = table;
        createSchema();
    }

    private void createSchema() {
        databaseManager.executeVoid(connection -> {
            String ddl = "CREATE TABLE IF NOT EXISTS " + table + " (uuid VARCHAR(36) PRIMARY KEY)";
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(ddl);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }).join();
    }

    /** UUIDs of everyone who has turned their mention sound off. */
    public CompletableFuture<Set<UUID>> loadMentionSoundDisabled() {
        return databaseManager.execute(connection -> {
            Set<UUID> disabled = new HashSet<>();
            String sql = "SELECT uuid FROM " + table;
            try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
                while (resultSet.next()) {
                    disabled.add(UUID.fromString(resultSet.getString("uuid")));
                }
                return disabled;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<Void> setMentionSoundDisabled(UUID uuid, boolean disabled) {
        return databaseManager.executeVoid(connection -> {
            String sql = disabled
                    ? switch (databaseManager.type()) {
                        case SQLITE -> "INSERT INTO " + table + " (uuid) VALUES (?) ON CONFLICT(uuid) DO NOTHING";
                        case MARIADB, MYSQL -> "INSERT IGNORE INTO " + table + " (uuid) VALUES (?)";
                    }
                    : "DELETE FROM " + table + " WHERE uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
