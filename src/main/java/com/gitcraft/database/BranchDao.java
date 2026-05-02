package com.gitcraft.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/** Data access for the {@code branches} table. All methods must run on the async scheduler. */
public final class BranchDao {

    private final Database database;

    public BranchDao(Database database) {
        this.database = database;
    }

    public long insert(BranchRecord r) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "INSERT INTO branches(repo_id, name, created_at, fork_commit_id) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, r.repoId());
            ps.setString(2, r.name());
            ps.setLong(3, r.createdAt());
            if (r.forkCommitId() != null) ps.setLong(4, r.forkCommitId());
            else ps.setNull(4, java.sql.Types.INTEGER);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("No generated id returned for inserted branch row.");
            }
        }
    }

    public Optional<BranchRecord> findByRepoAndName(long repoId, String name) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT id, repo_id, name, created_at, fork_commit_id FROM branches WHERE repo_id = ? AND name = ?")) {
            ps.setLong(1, repoId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<BranchRecord> findById(long id) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT id, repo_id, name, created_at, fork_commit_id FROM branches WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private BranchRecord map(ResultSet rs) throws SQLException {
        long forkId = rs.getLong("fork_commit_id");
        Long forkCommitId = rs.wasNull() ? null : forkId;
        return new BranchRecord(
                rs.getLong("id"),
                rs.getLong("repo_id"),
                rs.getString("name"),
                rs.getLong("created_at"),
                forkCommitId);
    }
}
