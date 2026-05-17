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

/** Data access for the {@code repos} table. All methods must run on the async scheduler. */
public final class RepoDao {

    private final Database database;

    public RepoDao(Database database) {
        this.database = database;
    }

    public long insert(RepoRecord r) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "INSERT INTO repos(owner_uuid, name, created_at," +
                " origin_offset_x, origin_offset_y, origin_offset_z, origin_offset_set)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.ownerUuid().toString());
            ps.setString(2, r.name());
            ps.setLong(3, r.createdAt());
            ps.setInt(4, r.originOffsetX());
            ps.setInt(5, r.originOffsetY());
            ps.setInt(6, r.originOffsetZ());
            ps.setInt(7, r.originOffsetSet() ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("No generated id returned for inserted repo row.");
            }
        }
    }

    /**
     * Sets the repo-space origin offset. The {@code AND origin_offset_set=0} guard makes
     * this a no-op if the offset has already been recorded — safe to call speculatively.
     */
    public void setOffset(long repoId, int ox, int oy, int oz) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "UPDATE repos SET origin_offset_x=?, origin_offset_y=?, origin_offset_z=?," +
                " origin_offset_set=1 WHERE id=? AND origin_offset_set=0")) {
            ps.setInt(1, ox);
            ps.setInt(2, oy);
            ps.setInt(3, oz);
            ps.setLong(4, repoId);
            ps.executeUpdate();
        }
    }

    public Optional<RepoRecord> findByOwnerAndName(UUID ownerUuid, String name) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT id, owner_uuid, name, created_at," +
                " origin_offset_x, origin_offset_y, origin_offset_z, origin_offset_set" +
                " FROM repos WHERE owner_uuid = ? AND name = ?")) {
            ps.setString(1, ownerUuid.toString());
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<RepoRecord> findById(long id) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT id, owner_uuid, name, created_at," +
                " origin_offset_x, origin_offset_y, origin_offset_z, origin_offset_set" +
                " FROM repos WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<RepoRecord> findByOwner(UUID ownerUuid) throws SQLException {
        List<RepoRecord> out = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT id, owner_uuid, name, created_at," +
                " origin_offset_x, origin_offset_y, origin_offset_z, origin_offset_set" +
                " FROM repos WHERE owner_uuid = ? ORDER BY created_at ASC, id ASC")) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        }
        return out;
    }

    /**
     * Deletes a repo and all its data in one transaction. Returns the deleted repo's info
     * (branch IDs needed for filesystem cleanup), or empty if not found or not owned by the caller.
     * Must run on the async scheduler.
     */
    public Optional<DeletedRepoInfo> deleteOwnedRepo(UUID ownerUuid, String repoName) throws SQLException {
        Connection conn = database.connection();
        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            long repoId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM repos WHERE owner_uuid = ? AND name = ?")) {
                ps.setString(1, ownerUuid.toString());
                ps.setString(2, repoName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.commit();
                        return Optional.empty();
                    }
                    repoId = rs.getLong("id");
                }
            }

            List<Long> branchIds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM branches WHERE repo_id = ?")) {
                ps.setLong(1, repoId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) branchIds.add(rs.getLong("id"));
                }
            }

            if (!branchIds.isEmpty()) {
                String ph = placeholders(branchIds.size());
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM commit_git_shas WHERE commit_id IN " +
                        "(SELECT id FROM commits WHERE branch_id IN (" + ph + "))")) {
                    for (int i = 0; i < branchIds.size(); i++) ps.setLong(i + 1, branchIds.get(i));
                    ps.executeUpdate();
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM stashes WHERE repo_id = ?")) {
                ps.setLong(1, repoId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM remotes WHERE repo_id = ?")) {
                ps.setLong(1, repoId);
                ps.executeUpdate();
            }

            // heads.commit_id → commits: must delete heads before commits
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM heads WHERE repo_id = ?")) {
                ps.setLong(1, repoId);
                ps.executeUpdate();
            }

            if (!branchIds.isEmpty()) {
                String ph = placeholders(branchIds.size());
                // branches.fork_commit_id → commits: must clear before deleting commits
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE branches SET fork_commit_id = NULL WHERE repo_id = ?")) {
                    ps.setLong(1, repoId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM commits WHERE branch_id IN (" + ph + ")")) {
                    for (int i = 0; i < branchIds.size(); i++) ps.setLong(i + 1, branchIds.get(i));
                    ps.executeUpdate();
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM branches WHERE repo_id = ?")) {
                ps.setLong(1, repoId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM repos WHERE id = ?")) {
                ps.setLong(1, repoId);
                ps.executeUpdate();
            }

            conn.commit();
            return Optional.of(new DeletedRepoInfo(repoId, branchIds, ownerUuid, repoName));
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prevAuto);
        }
    }

    public record DeletedRepoInfo(long repoId, List<Long> branchIds, UUID ownerUuid, String repoName) {}

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }

    private RepoRecord map(ResultSet rs) throws SQLException {
        return new RepoRecord(
                rs.getLong("id"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("name"),
                rs.getLong("created_at"),
                rs.getInt("origin_offset_x"),
                rs.getInt("origin_offset_y"),
                rs.getInt("origin_offset_z"),
                rs.getInt("origin_offset_set") != 0);
    }
}
