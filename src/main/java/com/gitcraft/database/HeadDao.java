package com.gitcraft.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** Data access for the {@code heads} table. All methods must run on the async scheduler. */
public final class HeadDao {

    private final Database database;

    public HeadDao(Database database) {
        this.database = database;
    }

    public void upsert(HeadRecord r) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "INSERT OR REPLACE INTO heads(player_uuid, repo_id, branch_id, commit_id) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, r.playerUuid().toString());
            ps.setLong(2, r.repoId());
            ps.setLong(3, r.branchId());
            ps.setObject(4, r.commitId());
            ps.executeUpdate();
        }
    }

    public Optional<HeadRecord> findByPlayerAndRepo(UUID playerUuid, long repoId) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT player_uuid, repo_id, branch_id, commit_id FROM heads WHERE player_uuid = ? AND repo_id = ?")) {
            ps.setString(1, playerUuid.toString());
            ps.setLong(2, repoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                long rawCommit = rs.getLong("commit_id");
                Long commitId = rs.wasNull() ? null : rawCommit;
                return Optional.of(new HeadRecord(
                        UUID.fromString(rs.getString("player_uuid")),
                        rs.getLong("repo_id"),
                        rs.getLong("branch_id"),
                        commitId));
            }
        }
    }
}
