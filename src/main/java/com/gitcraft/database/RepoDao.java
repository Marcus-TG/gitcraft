package com.gitcraft.database;

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
                "INSERT INTO repos(owner_uuid, name, created_at) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.ownerUuid().toString());
            ps.setString(2, r.name());
            ps.setLong(3, r.createdAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("No generated id returned for inserted repo row.");
            }
        }
    }

    public Optional<RepoRecord> findByOwnerAndName(UUID ownerUuid, String name) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT id, owner_uuid, name, created_at FROM repos WHERE owner_uuid = ? AND name = ?")) {
            ps.setString(1, ownerUuid.toString());
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<RepoRecord> findById(long id) throws SQLException {
        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT id, owner_uuid, name, created_at FROM repos WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<RepoRecord> findByOwner(UUID ownerUuid) throws SQLException {
        List<RepoRecord> out = new ArrayList<>();
        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT id, owner_uuid, name, created_at FROM repos WHERE owner_uuid = ? ORDER BY created_at ASC, id ASC")) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        }
        return out;
    }

    private RepoRecord map(ResultSet rs) throws SQLException {
        return new RepoRecord(
                rs.getLong("id"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("name"),
                rs.getLong("created_at"));
    }
}
