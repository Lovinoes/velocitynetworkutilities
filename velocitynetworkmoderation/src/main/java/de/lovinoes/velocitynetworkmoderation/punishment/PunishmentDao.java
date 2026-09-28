package de.lovinoes.velocitynetworkmoderation.punishment;

import de.lovinoes.networkutilitiescommon.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Stores punishments and the addresses players were last seen on.
 *
 * Lifting a punishment never deletes it: it clears the active flag and records who did it, so a
 * history stays a history. Rows are only deleted on purpose, through /history clear and remove,
 * and never while they are still in force.
 */
public final class PunishmentDao {

    private final DatabaseManager databaseManager;
    private final String punishmentsTable;
    private final String addressesTable;

    public PunishmentDao(DatabaseManager databaseManager, String punishmentsTable, String addressesTable) {
        this.databaseManager = databaseManager;
        this.punishmentsTable = punishmentsTable;
        this.addressesTable = addressesTable;
        createSchema();
    }

    private void createSchema() {
        databaseManager.executeVoid(connection -> {
            // SQLite only auto-assigns a rowid alias for INTEGER PRIMARY KEY, and rejects
            // AUTO_INCREMENT entirely; MySQL and MariaDB need AUTO_INCREMENT and are happy with
            // BIGINT. There is no spelling that satisfies both.
            String idColumn = switch (databaseManager.type()) {
                case SQLITE -> "id INTEGER PRIMARY KEY AUTOINCREMENT";
                case MARIADB, MYSQL -> "id BIGINT AUTO_INCREMENT PRIMARY KEY";
            };
            String punishments = "CREATE TABLE IF NOT EXISTS " + punishmentsTable + " ("
                    + idColumn + ", "
                    + "type VARCHAR(16) NOT NULL, "
                    + "victim_uuid VARCHAR(36), "
                    + "victim_name VARCHAR(16), "
                    // Long enough for IPv6, which is up to 45 characters.
                    + "victim_address VARCHAR(45), "
                    + "operator_uuid VARCHAR(36), "
                    + "operator_name VARCHAR(32) NOT NULL, "
                    + "reason VARCHAR(512) NOT NULL, "
                    + "created_at BIGINT NOT NULL, "
                    + "expires_at BIGINT NOT NULL, "
                    + "active BOOLEAN NOT NULL, "
                    + "revoked_by VARCHAR(32), "
                    + "revoked_at BIGINT NOT NULL)";
            String addresses = "CREATE TABLE IF NOT EXISTS " + addressesTable + " ("
                    + "uuid VARCHAR(36) PRIMARY KEY, "
                    + "username VARCHAR(16) NOT NULL, "
                    + "address VARCHAR(45) NOT NULL, "
                    + "updated_at BIGINT NOT NULL)";

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(punishments);
                statement.executeUpdate(addresses);
                // Every lookup is by victim or by address and filtered on active, so without
                // these a busy network scans the whole table on every single login.
                createIndex(statement, "idx_" + punishmentsTable + "_victim", punishmentsTable, "victim_uuid, active");
                createIndex(statement, "idx_" + punishmentsTable + "_address", punishmentsTable, "victim_address, active");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }).join();
    }

    private void createIndex(Statement statement, String name, String table, String columns) throws SQLException {
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + columns + ")");
    }

    /** @return the stored punishment, with the id the database assigned it. */
    public CompletableFuture<Punishment> insert(Punishment punishment) {
        return databaseManager.execute(connection -> {
            String sql = "INSERT INTO " + punishmentsTable + " (type, victim_uuid, victim_name, victim_address, "
                    + "operator_uuid, operator_name, reason, created_at, expires_at, active, revoked_by, revoked_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement =
                         connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, punishment.type().name());
                setNullableUuid(statement, 2, punishment.victimUuid());
                statement.setString(3, punishment.victimName());
                statement.setString(4, punishment.victimAddress());
                setNullableUuid(statement, 5, punishment.operatorUuid());
                statement.setString(6, punishment.operatorName());
                statement.setString(7, punishment.reason());
                statement.setLong(8, punishment.createdAt());
                statement.setLong(9, punishment.expiresAt());
                statement.setBoolean(10, punishment.active());
                statement.setString(11, punishment.revokedBy());
                statement.setLong(12, punishment.revokedAt());
                statement.executeUpdate();

                try (ResultSet keys = statement.getGeneratedKeys()) {
                    long id = keys.next() ? keys.getLong(1) : 0;
                    return withId(punishment, id);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * The punishment of this type currently in force against this player, if any.
     *
     * Expiry is applied here rather than in SQL so there is one definition of "in force", shared
     * with everything that reads a punishment out of memory.
     */
    public CompletableFuture<Optional<Punishment>> findActive(PunishmentType type, UUID victim, long now) {
        return databaseManager.execute(connection -> {
            String sql = "SELECT * FROM " + punishmentsTable
                    + " WHERE type = ? AND victim_uuid = ? AND active = ? ORDER BY created_at DESC, id DESC";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, type.name());
                statement.setString(2, victim.toString());
                statement.setBoolean(3, true);
                return firstInForce(statement, now);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Every punishment of this type in force against this player, newest first. */
    public CompletableFuture<List<Punishment>> findAllInForce(PunishmentType type, UUID victim, long now) {
        return databaseManager.execute(connection -> {
            String sql = "SELECT * FROM " + punishmentsTable
                    + " WHERE type = ? AND victim_uuid = ? AND active = ? ORDER BY created_at DESC, id DESC";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, type.name());
                statement.setString(2, victim.toString());
                statement.setBoolean(3, true);
                List<Punishment> inForce = new ArrayList<>();
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        Punishment punishment = read(results);
                        if (!punishment.isExpired(now)) {
                            inForce.add(punishment);
                        }
                    }
                }
                return inForce;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** The IP ban currently in force against this address, if any. */
    public CompletableFuture<Optional<Punishment>> findActiveIpBan(String address, long now) {
        return databaseManager.execute(connection -> {
            String sql = "SELECT * FROM " + punishmentsTable
                    + " WHERE type = ? AND victim_address = ? AND active = ? ORDER BY created_at DESC, id DESC";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, PunishmentType.IP_BAN.name());
                statement.setString(2, address);
                statement.setBoolean(3, true);
                return firstInForce(statement, now);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Optional<Punishment> firstInForce(PreparedStatement statement, long now) throws SQLException {
        List<Long> expired = new ArrayList<>();
        Punishment found = null;
        try (ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                Punishment punishment = read(results);
                if (punishment.isExpired(now)) {
                    expired.add(punishment.id());
                } else if (found == null) {
                    found = punishment;
                }
            }
        }
        // Tidy up rows the clock has overtaken, so they stop being scanned. Doing it here keeps
        // the data honest without needing a scheduled sweep, and it is only ever a no-op change
        // in meaning, since isExpired already decided they were over.
        if (!expired.isEmpty()) {
            deactivate(statement.getConnection(), expired);
        }
        return Optional.ofNullable(found);
    }

    private void deactivate(Connection connection, List<Long> ids) throws SQLException {
        String sql = "UPDATE " + punishmentsTable + " SET active = ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (long id : ids) {
                statement.setBoolean(1, false);
                statement.setLong(2, id);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * Lifts EVERY punishment of this type in force against a player, and reports the newest.
     * Lifting only the newest would leave an older one in force: a player muted twice would stay
     * muted after /unmute, with nothing telling staff why.
     */
    public CompletableFuture<Optional<Punishment>> revoke(PunishmentType type, UUID victim, String operator, long now) {
        return revokeAll("type = ? AND victim_uuid = ?",
                statement -> {
                    statement.setString(1, type.name());
                    statement.setString(2, victim.toString());
                }, operator, now);
    }

    /** Lifts every IP ban in force on this address, and reports the newest. */
    public CompletableFuture<Optional<Punishment>> revokeIpBan(String address, String operator, long now) {
        return revokeAll("type = ? AND victim_address = ?",
                statement -> {
                    statement.setString(1, PunishmentType.IP_BAN.name());
                    statement.setString(2, address);
                }, operator, now);
    }

    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    /**
     * @param where the rows to consider, filled in by {@code binder} from parameter 1. Only rows
     *              still in force are lifted: one that already ran out keeps reading as expired.
     */
    private CompletableFuture<Optional<Punishment>> revokeAll(String where, Binder binder, String operator, long now) {
        return databaseManager.execute(connection -> {
            String select = "SELECT * FROM " + punishmentsTable + " WHERE " + where
                    + " AND active = ? ORDER BY created_at DESC, id DESC";
            String update = "UPDATE " + punishmentsTable
                    + " SET active = ?, revoked_by = ?, revoked_at = ? WHERE id = ? AND active = ?";
            try (PreparedStatement query = connection.prepareStatement(select);
                 PreparedStatement lift = connection.prepareStatement(update)) {
                binder.bind(query);
                query.setBoolean(3, true);
                List<Punishment> inForce = new ArrayList<>();
                try (ResultSet results = query.executeQuery()) {
                    while (results.next()) {
                        Punishment punishment = read(results);
                        if (!punishment.isExpired(now)) {
                            inForce.add(punishment);
                        }
                    }
                }
                Punishment newestLifted = null;
                for (Punishment punishment : inForce) {
                    lift.setBoolean(1, false);
                    lift.setString(2, operator);
                    lift.setLong(3, now);
                    lift.setLong(4, punishment.id());
                    // Only a row that is still active, so two moderators lifting at the same
                    // moment cannot both be told they were the one who did it.
                    lift.setBoolean(5, true);
                    if (lift.executeUpdate() > 0 && newestLifted == null) {
                        newestLifted = new Punishment(punishment.id(), punishment.type(), punishment.victimUuid(),
                                punishment.victimName(), punishment.victimAddress(), punishment.operatorUuid(),
                                punishment.operatorName(), punishment.reason(), punishment.createdAt(),
                                punishment.expiresAt(), false, operator, now);
                    }
                }
                return Optional.ofNullable(newestLifted);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Everything ever recorded against a player, newest first. */
    public CompletableFuture<List<Punishment>> history(UUID victim, int limit) {
        return databaseManager.execute(connection -> {
            String sql = "SELECT * FROM " + punishmentsTable
                    + " WHERE victim_uuid = ? ORDER BY created_at DESC, id DESC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, victim.toString());
                statement.setInt(2, limit);
                List<Punishment> punishments = new ArrayList<>();
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        punishments.add(read(results));
                    }
                }
                return punishments;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** How many entries a clear would remove, and how many a player has in total. */
    public record HistoryCount(int clearable, int total) {
    }

    /** What a clear did: entries removed, and entries kept because they are still in force. */
    public record ClearResult(int removed, int kept) {
    }

    public enum RemoveResult { REMOVED, NOT_FOUND, IN_FORCE }

    /**
     * SQL for "still in force": a ban, IP ban or mute that has not been lifted and has not run
     * out. The same rule as {@link Punishment#isCurrentlyInForce} limited to revocable types,
     * since a kick or a warning is over the moment it happens. Such rows are never deleted:
     * deleting an active ban's row would silently unban the player.
     *
     * Takes three parameters, filled by {@link #bindInForce}.
     */
    private static final String IN_FORCE = "(type IN (" + revocableTypes() + ") AND active = ?"
            + " AND (expires_at = ? OR expires_at > ?))";

    private static String revocableTypes() {
        StringBuilder types = new StringBuilder();
        for (PunishmentType type : PunishmentType.values()) {
            if (type.isRevocable()) {
                if (!types.isEmpty()) {
                    types.append(", ");
                }
                types.append('\'').append(type.name()).append('\'');
            }
        }
        return types.toString();
    }

    private static int bindInForce(PreparedStatement statement, int index, long now) throws SQLException {
        statement.setBoolean(index, true);
        statement.setLong(index + 1, Punishment.PERMANENT);
        statement.setLong(index + 2, now);
        return index + 3;
    }

    public CompletableFuture<HistoryCount> countHistory(UUID victim, long now) {
        return databaseManager.execute(connection -> {
            String sql = "SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN " + IN_FORCE + " THEN 0 ELSE 1 END), 0)"
                    + " AS clearable FROM " + punishmentsTable + " WHERE victim_uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int next = bindInForce(statement, 1, now);
                statement.setString(next, victim.toString());
                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    return new HistoryCount(results.getInt("clearable"), results.getInt("total"));
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Deletes every entry of a player's history that is not still in force. */
    public CompletableFuture<ClearResult> clearHistory(UUID victim, long now) {
        return databaseManager.execute(connection -> {
            String delete = "DELETE FROM " + punishmentsTable + " WHERE victim_uuid = ? AND NOT " + IN_FORCE;
            String remaining = "SELECT COUNT(*) FROM " + punishmentsTable + " WHERE victim_uuid = ?";
            try (PreparedStatement deleteStatement = connection.prepareStatement(delete);
                 PreparedStatement countStatement = connection.prepareStatement(remaining)) {
                deleteStatement.setString(1, victim.toString());
                bindInForce(deleteStatement, 2, now);
                int removed = deleteStatement.executeUpdate();

                countStatement.setString(1, victim.toString());
                try (ResultSet results = countStatement.executeQuery()) {
                    results.next();
                    return new ClearResult(removed, results.getInt(1));
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Deletes one entry of a player's history, unless it is still in force. */
    public CompletableFuture<RemoveResult> removeHistoryEntry(UUID victim, long id, long now) {
        return databaseManager.execute(connection -> {
            String delete = "DELETE FROM " + punishmentsTable + " WHERE id = ? AND victim_uuid = ? AND NOT " + IN_FORCE;
            String exists = "SELECT COUNT(*) FROM " + punishmentsTable + " WHERE id = ? AND victim_uuid = ?";
            try (PreparedStatement deleteStatement = connection.prepareStatement(delete)) {
                deleteStatement.setLong(1, id);
                deleteStatement.setString(2, victim.toString());
                bindInForce(deleteStatement, 3, now);
                if (deleteStatement.executeUpdate() > 0) {
                    return RemoveResult.REMOVED;
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            // Nothing deleted: either there is no such entry for this player, or it is in force.
            try (PreparedStatement existsStatement = connection.prepareStatement(exists)) {
                existsStatement.setLong(1, id);
                existsStatement.setString(2, victim.toString());
                try (ResultSet results = existsStatement.executeQuery()) {
                    results.next();
                    return results.getInt(1) > 0 ? RemoveResult.IN_FORCE : RemoveResult.NOT_FOUND;
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<Void> recordAddress(UUID uuid, String username, String address, long now) {
        return databaseManager.executeVoid(connection -> {
            String sql = switch (databaseManager.type()) {
                case SQLITE -> "INSERT INTO " + addressesTable + " (uuid, username, address, updated_at) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET "
                        + "username = excluded.username, address = excluded.address, updated_at = excluded.updated_at";
                case MARIADB, MYSQL -> "INSERT INTO " + addressesTable + " (uuid, username, address, updated_at) "
                        + "VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                        + "username = VALUES(username), address = VALUES(address), updated_at = VALUES(updated_at)";
            };
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, username);
                statement.setString(3, address);
                statement.setLong(4, now);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** The address a player was last seen on, so an IP ban can be placed by name. */
    public CompletableFuture<Optional<String>> lastAddress(UUID uuid) {
        return databaseManager.execute(connection -> {
            String sql = "SELECT address FROM " + addressesTable + " WHERE uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                try (ResultSet results = statement.executeQuery()) {
                    return results.next() ? Optional.of(results.getString("address")) : Optional.<String>empty();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void setNullableUuid(PreparedStatement statement, int index, UUID uuid) throws SQLException {
        statement.setString(index, uuid == null ? null : uuid.toString());
    }

    private static Punishment withId(Punishment punishment, long id) {
        return new Punishment(id, punishment.type(), punishment.victimUuid(), punishment.victimName(),
                punishment.victimAddress(), punishment.operatorUuid(), punishment.operatorName(),
                punishment.reason(), punishment.createdAt(), punishment.expiresAt(), punishment.active(),
                punishment.revokedBy(), punishment.revokedAt());
    }

    private static Punishment read(ResultSet results) throws SQLException {
        return new Punishment(
                results.getLong("id"),
                PunishmentType.parse(results.getString("type")).orElse(PunishmentType.WARN),
                readUuid(results.getString("victim_uuid")),
                results.getString("victim_name"),
                results.getString("victim_address"),
                readUuid(results.getString("operator_uuid")),
                results.getString("operator_name"),
                results.getString("reason"),
                results.getLong("created_at"),
                results.getLong("expires_at"),
                results.getBoolean("active"),
                results.getString("revoked_by"),
                results.getLong("revoked_at"));
    }

    private static UUID readUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            // A hand-edited row should not take the whole lookup down with it.
            return null;
        }
    }
}
