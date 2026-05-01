package com.gitcraft.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Data access for the {@code commits} table. All methods must run on the async scheduler.
 */
public final class CommitDao {

    private static final String INSERT_SQL =
            "INSERT INTO commits(player_uuid, player_name, region_name, message, schem_path, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)";

    private final Database database;

    public CommitDao(Database database) {
        this.database = database;
    }

    public long insert(CommitRecord r) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(
                INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.playerUuid().toString());
            ps.setString(2, r.playerName());
            ps.setString(3, r.regionName());
            ps.setString(4, r.message());
            ps.setString(5, r.schemPath());
            ps.setLong(6, r.createdAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated id returned for inserted commit row.");
            }
        }
    }
}
