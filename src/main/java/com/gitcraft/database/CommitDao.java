package com.gitcraft.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for the {@code commits} table. All methods must run on the async scheduler.
 */
public final class CommitDao {

    private static final String INSERT_SQL =
            "INSERT INTO commits(" +
                    "player_uuid, player_name, region_name, message, schem_path, created_at, " +
                    "world_uuid, world_name, min_x, min_y, min_z, max_x, max_y, max_z" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_COLUMNS =
            "id, player_uuid, player_name, region_name, message, schem_path, created_at, " +
                    "world_uuid, world_name, min_x, min_y, min_z, max_x, max_y, max_z";

    private static final String FIND_BY_ID_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM commits WHERE id = ?";

    private static final String FIND_BY_REGION_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM commits WHERE region_name = ? " +
                    "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";

    private static final String COUNT_BY_REGION_SQL =
            "SELECT COUNT(*) FROM commits WHERE region_name = ?";

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
            ps.setString(7, r.worldUuid().toString());
            ps.setString(8, r.worldName());
            ps.setInt(9, r.minX());
            ps.setInt(10, r.minY());
            ps.setInt(11, r.minZ());
            ps.setInt(12, r.maxX());
            ps.setInt(13, r.maxY());
            ps.setInt(14, r.maxZ());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated id returned for inserted commit row.");
            }
        }
    }

    public Optional<CommitRecord> findById(long id) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(FIND_BY_ID_SQL)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<CommitRecord> findByRegion(String regionName, int limit, int offset) throws SQLException {
        List<CommitRecord> out = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(FIND_BY_REGION_SQL)) {
            ps.setString(1, regionName);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        }
        return out;
    }

    public int countByRegion(String regionName) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(COUNT_BY_REGION_SQL)) {
            ps.setString(1, regionName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private CommitRecord map(ResultSet rs) throws SQLException {
        return new CommitRecord(
                rs.getLong("id"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("player_name"),
                rs.getString("region_name"),
                rs.getString("message"),
                rs.getString("schem_path"),
                rs.getLong("created_at"),
                UUID.fromString(rs.getString("world_uuid")),
                rs.getString("world_name"),
                rs.getInt("min_x"),
                rs.getInt("min_y"),
                rs.getInt("min_z"),
                rs.getInt("max_x"),
                rs.getInt("max_y"),
                rs.getInt("max_z")
        );
    }
}
