package com.gitcraft.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Data access for the {@code stashes} table. All methods must run on the async scheduler. */
public final class StashDao {

    private static final String INSERT_SQL =
            "INSERT INTO stashes(player_uuid, repo_id, branch_id, world_uuid, world_name, " +
                    "min_x, min_y, min_z, max_x, max_y, max_z, message, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_COLUMNS =
            "id, player_uuid, repo_id, branch_id, world_uuid, world_name, " +
                    "min_x, min_y, min_z, max_x, max_y, max_z, message, created_at";

    private static final String SELECT_LATEST_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM stashes " +
                    "WHERE player_uuid = ? AND repo_id = ? " +
                    "ORDER BY created_at DESC, id DESC LIMIT 1";

    private static final String LIST_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM stashes " +
                    "WHERE player_uuid = ? AND repo_id = ? " +
                    "ORDER BY created_at DESC, id DESC";

    private static final String DELETE_BY_ID_SQL =
            "DELETE FROM stashes WHERE id = ?";

    private final Database database;

    public StashDao(Database database) {
        this.database = database;
    }

    public long insert(StashRecord r) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(
                INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.playerUuid().toString());
            ps.setLong(2, r.repoId());
            ps.setLong(3, r.branchId());
            ps.setString(4, r.worldUuid().toString());
            ps.setString(5, r.worldName());
            ps.setInt(6, r.minX());
            ps.setInt(7, r.minY());
            ps.setInt(8, r.minZ());
            ps.setInt(9, r.maxX());
            ps.setInt(10, r.maxY());
            ps.setInt(11, r.maxZ());
            ps.setString(12, r.message());
            ps.setLong(13, r.createdAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("No generated id returned for inserted stash row.");
            }
        }
    }

    public Optional<StashRecord> peekLatest(UUID playerUuid, long repoId) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(SELECT_LATEST_SQL)) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, repoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<StashRecord> listForPlayerRepo(UUID playerUuid, long repoId) throws SQLException {
        List<StashRecord> out = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(LIST_SQL)) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, repoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        }
        return out;
    }

    /** Delete by id. Caller decides when to delete (e.g. after a successful pop restore). */
    public boolean deleteById(long id) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(DELETE_BY_ID_SQL)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Atomically read-and-delete the latest stash for (player, repo). Wrapped in a
     * transaction so two concurrent pops from the same player don't both observe the
     * same row before either DELETE runs.
     */
    public Optional<StashRecord> popLatestAtomic(UUID playerUuid, long repoId) throws SQLException {
        Connection conn = database.connection();
        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            Optional<StashRecord> latest = peekLatest(playerUuid, repoId);
            if (latest.isEmpty()) {
                conn.commit();
                return Optional.empty();
            }
            try (PreparedStatement ps = conn.prepareStatement(DELETE_BY_ID_SQL)) {
                ps.setLong(1, latest.get().id());
                ps.executeUpdate();
            }
            conn.commit();
            return latest;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prevAuto);
        }
    }

    private StashRecord map(ResultSet rs) throws SQLException {
        return new StashRecord(
                rs.getLong("id"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getLong("repo_id"),
                rs.getLong("branch_id"),
                UUID.fromString(rs.getString("world_uuid")),
                rs.getString("world_name"),
                rs.getInt("min_x"),
                rs.getInt("min_y"),
                rs.getInt("min_z"),
                rs.getInt("max_x"),
                rs.getInt("max_y"),
                rs.getInt("max_z"),
                rs.getString("message"),
                rs.getLong("created_at")
        );
    }
}
