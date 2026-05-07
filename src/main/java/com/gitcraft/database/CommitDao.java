package com.gitcraft.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for the {@code commits} table. All methods must run on the async scheduler.
 */
public final class CommitDao {

    private static final String INSERT_SQL =
            "INSERT INTO commits(" +
                    "branch_id, player_uuid, player_name, message, schem_path, created_at, " +
                    "world_uuid, world_name, min_x, min_y, min_z, max_x, max_y, max_z, " +
                    "parent_commit_id, merge_parent_commit_id, cherry_pick_source_id" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_COLUMNS =
            "id, parent_commit_id, merge_parent_commit_id, cherry_pick_source_id, " +
                    "branch_id, player_uuid, player_name, " +
                    "message, schem_path, created_at, " +
                    "world_uuid, world_name, min_x, min_y, min_z, max_x, max_y, max_z";

    private static final String FIND_BY_ID_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM commits WHERE id = ?";

    private static final String FIND_BY_BRANCH_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM commits WHERE branch_id = ? " +
                    "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";

    private static final String COUNT_BY_BRANCH_SQL =
            "SELECT COUNT(*) FROM commits WHERE branch_id = ?";

    private static final String FIND_LATEST_ID_BY_BRANCH_SQL =
            "SELECT MAX(id) FROM commits WHERE branch_id = ?";

    private static final String FIND_NEWER_THAN_SQL =
            "SELECT " + SELECT_COLUMNS + " FROM commits WHERE id > ? AND branch_id = ? ORDER BY id ASC";

    private static final String DELETE_NEWER_THAN_SQL =
            "DELETE FROM commits WHERE id > ? AND branch_id = ?";

    private static final String FIND_PARENT_LINKS_BY_BRANCHES_PREFIX =
            "SELECT id, parent_commit_id, merge_parent_commit_id FROM commits WHERE branch_id IN (";

    private final Database database;

    public CommitDao(Database database) {
        this.database = database;
    }

    public long insert(CommitRecord r) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(
                INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, r.branchId());
            ps.setString(2, r.playerUuid().toString());
            ps.setString(3, r.playerName());
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
            ps.setObject(15, r.parentCommitId());
            ps.setObject(16, r.mergeParentCommitId());
            ps.setObject(17, r.cherryPickSourceId());
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

    public List<CommitRecord> findByBranch(long branchId, int limit, int offset) throws SQLException {
        List<CommitRecord> out = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(FIND_BY_BRANCH_SQL)) {
            ps.setLong(1, branchId);
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

    public int countByBranch(long branchId) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(COUNT_BY_BRANCH_SQL)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public Optional<Long> findLatestIdByBranch(long branchId) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(FIND_LATEST_ID_BY_BRANCH_SQL)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long val = rs.getLong(1);
                    return rs.wasNull() ? Optional.empty() : Optional.of(val);
                }
                return Optional.empty();
            }
        }
    }

    public List<CommitRecord> findNewerThan(long targetId, long branchId) throws SQLException {
        List<CommitRecord> out = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(FIND_NEWER_THAN_SQL)) {
            ps.setLong(1, targetId);
            ps.setLong(2, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        }
        return out;
    }

    public int deleteNewerThan(long targetId, long branchId) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(DELETE_NEWER_THAN_SQL)) {
            ps.setLong(1, targetId);
            ps.setLong(2, branchId);
            return ps.executeUpdate();
        }
    }

    /**
     * Bulk-load (id → ParentLink) for every commit on the given branches. Used by merge
     * to walk parent chains in memory without N+1 round trips.
     */
    public Map<Long, ParentLink> findParentLinksByBranches(List<Long> branchIds) throws SQLException {
        if (branchIds.isEmpty()) return Map.of();
        StringBuilder sql = new StringBuilder(FIND_PARENT_LINKS_BY_BRANCHES_PREFIX);
        for (int i = 0; i < branchIds.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append('?');
        }
        sql.append(')');

        Map<Long, ParentLink> out = new HashMap<>();
        try (PreparedStatement ps = database.connection().prepareStatement(sql.toString())) {
            for (int i = 0; i < branchIds.size(); i++) {
                ps.setLong(i + 1, branchIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    long rawP = rs.getLong("parent_commit_id");
                    Long parent = rs.wasNull() ? null : rawP;
                    long rawM = rs.getLong("merge_parent_commit_id");
                    Long mergeParent = rs.wasNull() ? null : rawM;
                    out.put(id, new ParentLink(parent, mergeParent));
                }
            }
        }
        return out;
    }

    /** Lightweight projection of a commit's parent edges for graph walks. */
    public record ParentLink(Long parentCommitId, Long mergeParentCommitId) {}

    private CommitRecord map(ResultSet rs) throws SQLException {
        long rawParent = rs.getLong("parent_commit_id");
        Long parentCommitId = rs.wasNull() ? null : rawParent;
        long rawMerge = rs.getLong("merge_parent_commit_id");
        Long mergeParentCommitId = rs.wasNull() ? null : rawMerge;
        long rawCherry = rs.getLong("cherry_pick_source_id");
        Long cherryPickSourceId = rs.wasNull() ? null : rawCherry;
        return new CommitRecord(
                rs.getLong("id"),
                parentCommitId,
                mergeParentCommitId,
                cherryPickSourceId,
                rs.getLong("branch_id"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("player_name"),
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
