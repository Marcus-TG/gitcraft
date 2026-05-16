package com.gitcraft.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

public final class CommitGitShaDao {

    private static final Logger log = Logger.getLogger(CommitGitShaDao.class.getName());

    private final Database database;

    public CommitGitShaDao(Database database) {
        this.database = database;
    }

    /** Inserts with OR IGNORE. Returns 1 if inserted, 0 if silently skipped due to constraint. */
    public int insert(long commitId, long remoteId, String gitSha) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO commit_git_shas(commit_id, remote_id, git_sha) VALUES (?, ?, ?)")) {
            ps.setLong(1, commitId);
            ps.setLong(2, remoteId);
            ps.setString(3, gitSha);
            return ps.executeUpdate();
        }
    }

    /**
     * Inserts without OR IGNORE — throws SQLException on any constraint violation.
     * Use in clone/pull import paths so a duplicate is caught and the transaction rolled back
     * instead of leaving commits silently untracked.
     */
    public void strictInsert(long commitId, long remoteId, String gitSha) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO commit_git_shas(commit_id, remote_id, git_sha) VALUES (?, ?, ?)")) {
            ps.setLong(1, commitId);
            ps.setLong(2, remoteId);
            ps.setString(3, gitSha);
            ps.executeUpdate();
        }
    }

    public Optional<String> findShaByCommitAndRemote(long commitId, long remoteId) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT git_sha FROM commit_git_shas WHERE commit_id = ? AND remote_id = ?")) {
            ps.setLong(1, commitId);
            ps.setLong(2, remoteId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString(1));
            }
        }
        return Optional.empty();
    }

    /** Returns any existing SHA for this commit regardless of which remote it was pushed to. */
    public Optional<String> findAnyShaForCommit(long commitId) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT git_sha FROM commit_git_shas WHERE commit_id = ? LIMIT 1")) {
            ps.setLong(1, commitId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString(1));
            }
        }
        return Optional.empty();
    }

    public Optional<Long> findCommitIdBySha(long remoteId, String gitSha) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT commit_id FROM commit_git_shas WHERE remote_id = ? AND git_sha = ?")) {
            ps.setLong(1, remoteId);
            ps.setString(2, gitSha);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getLong(1));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns commit IDs on the given branch that have NOT been pushed to the given remote,
     * ordered ascending by commit id (oldest first).
     */
    public List<Long> findUnpushedCommitIds(long branchId, long remoteId) throws SQLException {
        Connection conn = database.connection();

        int totalOnBranch = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM commits WHERE branch_id = ?")) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) totalOnBranch = rs.getInt(1);
            }
        }

        int shaRows = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM commit_git_shas s JOIN commits c ON c.id = s.commit_id " +
                "WHERE c.branch_id = ? AND s.remote_id = ?")) {
            ps.setLong(1, branchId);
            ps.setLong(2, remoteId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) shaRows = rs.getInt(1);
            }
        }

        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT c.id FROM commits c " +
                "LEFT JOIN commit_git_shas s ON s.commit_id = c.id AND s.remote_id = ? " +
                "WHERE c.branch_id = ? AND s.git_sha IS NULL " +
                "ORDER BY c.id ASC")) {
            ps.setLong(1, remoteId);
            ps.setLong(2, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
        }

        log.info("findUnpushedCommitIds branchId=" + branchId + " remoteId=" + remoteId
                + " totalOnBranch=" + totalOnBranch + " shaRows=" + shaRows
                + " unpushed=" + ids.size());

        return ids;
    }

    /**
     * Returns the git_sha of the most-recently-pushed commit on the given branch to the given
     * remote, or empty if nothing has been pushed yet.
     */
    public Optional<String> findLatestShaForBranchAndRemote(long branchId, long remoteId) throws SQLException {
        Connection conn = database.connection();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT s.git_sha FROM commit_git_shas s " +
                "JOIN commits c ON c.id = s.commit_id " +
                "WHERE c.branch_id = ? AND s.remote_id = ? " +
                "ORDER BY c.id DESC LIMIT 1")) {
            ps.setLong(1, branchId);
            ps.setLong(2, remoteId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString(1));
            }
        }
        return Optional.empty();
    }

    /**
     * Pre-seeds the sha-to-local-id map used during pull/clone walks.
     * Returns all known {git_sha → local commit_id} pairs for the given remote.
     */
    public Map<String, Long> findAllShasForRemote(long remoteId) throws SQLException {
        Connection conn = database.connection();
        Map<String, Long> map = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT git_sha, commit_id FROM commit_git_shas WHERE remote_id = ?")) {
            ps.setLong(1, remoteId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) map.put(rs.getString(1), rs.getLong(2));
            }
        }
        return map;
    }
}
