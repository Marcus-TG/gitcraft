package com.gitcraft.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

public final class GitHubTokenDao {

    private final Database database;

    public GitHubTokenDao(Database database) {
        this.database = database;
    }

    public void upsert(GitHubTokenRecord r) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO github_tokens(player_uuid, access_token, scope, created_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, r.playerUuid().toString());
            ps.setString(2, r.accessToken());
            ps.setString(3, r.scope());
            ps.setLong(4, r.createdAt());
            ps.executeUpdate();
        }
    }

    public Optional<GitHubTokenRecord> findByPlayer(UUID playerUuid) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT player_uuid, access_token, scope, created_at FROM github_tokens WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new GitHubTokenRecord(
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("access_token"),
                            rs.getString("scope"),
                            rs.getLong("created_at")));
                }
            }
        }
        return Optional.empty();
    }

    public void deleteByPlayer(UUID playerUuid) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM github_tokens WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            ps.executeUpdate();
        }
    }
}
