package com.gitcraft.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RemoteDao {

    private final Database database;

    public RemoteDao(Database database) {
        this.database = database;
    }

    public long insert(RemoteRecord r) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO remotes(repo_id, name, url) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, r.repoId());
            ps.setString(2, r.name());
            ps.setString(3, r.url());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("No generated key for remote insert");
            }
        }
    }

    public Optional<RemoteRecord> findByRepoAndName(long repoId, String name) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, repo_id, name, url FROM remotes WHERE repo_id = ? AND name = ?")) {
            ps.setLong(1, repoId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        }
        return Optional.empty();
    }

    public List<RemoteRecord> findByRepo(long repoId) throws SQLException {
        Connection conn = database.connection();
        List<RemoteRecord> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, repo_id, name, url FROM remotes WHERE repo_id = ? ORDER BY name")) {
            ps.setLong(1, repoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    public boolean deleteByRepoAndName(long repoId, String name) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM remotes WHERE repo_id = ? AND name = ?")) {
            ps.setLong(1, repoId);
            ps.setString(2, name);
            return ps.executeUpdate() > 0;
        }
    }

    private RemoteRecord map(ResultSet rs) throws SQLException {
        return new RemoteRecord(rs.getLong("id"), rs.getLong("repo_id"),
                rs.getString("name"), rs.getString("url"));
    }
}
