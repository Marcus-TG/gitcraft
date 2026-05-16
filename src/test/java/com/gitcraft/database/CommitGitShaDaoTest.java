package com.gitcraft.database;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

final class CommitGitShaDaoTest {

    private CommitGitShaDao shaDao;

    @BeforeEach
    void setup() throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            // Disable FK enforcement so we can insert test rows in any order.
            st.execute("PRAGMA foreign_keys=OFF");
        }
        applySchema(conn);
        insertTestData(conn);
        shaDao = new CommitGitShaDao(new Database(conn));
    }

    // ---- tests ----

    @Test
    void findUnpushedExcludesCommitsWithShaRows() throws Exception {
        // Commit 1 has a sha row; commit 2 does not.
        List<Long> unpushed = shaDao.findUnpushedCommitIds(1L, 1L);
        assertEquals(List.of(2L), unpushed);
    }

    @Test
    void insertReturnsZeroOnPkDuplicate() throws Exception {
        // (commit_id=1, remote_id=1) already exists — insert should be silently ignored.
        int rows = shaDao.insert(1L, 1L, "bbb111");
        assertEquals(0, rows);
    }

    @Test
    void strictInsertThrowsOnPkDuplicate() {
        assertThrows(SQLException.class, () -> shaDao.strictInsert(1L, 1L, "ccc222"));
    }

    @Test
    void strictInsertSucceedsAndRemovesFromUnpushed() throws Exception {
        shaDao.strictInsert(2L, 1L, "ddd333");
        List<Long> unpushed = shaDao.findUnpushedCommitIds(1L, 1L);
        assertEquals(List.of(), unpushed);
    }

    /**
     * Simulates the {@code recordShas} recovery path in GitPushService: some commits already have
     * SHA rows (e.g. from a previous partially-recorded push), some do not.
     * All inserts must complete without error, and the "already-exists" rows return 0.
     */
    @Test
    void recordShasRecovery_existingRowsIgnoredNewRowsInserted() throws Exception {
        // Commit 1 already has SHA row "aaa000" (from setup).
        int rowsExisting = shaDao.insert(1L, 1L, "aaa000");
        assertEquals(0, rowsExisting, "already-tracked commit must return 0 (OR IGNORE)");

        // Commit 2 has no row yet — insert should succeed.
        int rowsNew = shaDao.insert(2L, 1L, "fff666");
        assertEquals(1, rowsNew, "new commit must return 1");

        // After recovery, nothing is unpushed.
        List<Long> unpushed = shaDao.findUnpushedCommitIds(1L, 1L);
        assertEquals(List.of(), unpushed);
    }

    @Test
    void sameShaAllowedAcrossDifferentRemotes() throws Exception {
        // remote2 is a second clone of the same GitHub repo into a different local GitCraft repo.
        // The same Git SHA must be storable under a different remote_id.
        shaDao.strictInsert(2L, 2L, "aaa000");  // same SHA as (commit 1, remote 1) in setup
        // No exception expected — cross-remote duplicate is valid.
    }

    @Test
    void sameShaForSameRemoteThrowsWithStrictInsert() {
        // Inserting the same SHA under the same remote_id is a bug and must fail loudly.
        assertThrows(SQLException.class, () -> shaDao.strictInsert(2L, 1L, "aaa000"));
    }

    @Test
    void sameShaForSameRemoteIsIgnoredWithInsert() throws Exception {
        // insert() uses OR IGNORE — returns 0 rather than throwing.
        int rows = shaDao.insert(2L, 1L, "aaa000");
        assertEquals(0, rows);
    }

    @Test
    void findCommitIdByShaIsScopedToRemote() throws Exception {
        // Setup: (commit 1, remote 1) → "aaa000". Add (commit 2, remote 2) → same SHA.
        shaDao.strictInsert(2L, 2L, "aaa000");

        assertEquals(Optional.of(1L), shaDao.findCommitIdBySha(1L, "aaa000"));
        assertEquals(Optional.of(2L), shaDao.findCommitIdBySha(2L, "aaa000"));
        assertEquals(Optional.empty(), shaDao.findCommitIdBySha(1L, "nonexistent"));
    }

    // ---- helpers ----

    private static void applySchema(Connection conn) throws Exception {
        InputStream is = CommitGitShaDaoTest.class.getResourceAsStream("/database/schema.sql");
        assertNotNull(is, "schema.sql not found on classpath");
        String schema = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        // Strip ALL comment lines before splitting by ";" — a semicolon inside a comment line
        // (e.g. "-- schema changes here; do not CREATE TABLE") would otherwise produce stray text.
        // Also create a fresh Statement per chunk: SQLite JDBC 3.46 misbehaves when a single
        // Statement is reused across execute() calls.
        StringBuilder noComments = new StringBuilder();
        for (String line : schema.split("\n")) {
            if (!line.strip().startsWith("--")) noComments.append(line).append('\n');
        }
        for (String stmt : noComments.toString().split(";")) {
            String s = stmt.strip();
            if (!s.isEmpty()) {
                try (Statement st = conn.createStatement()) {
                    st.execute(s);
                }
            }
        }
    }

    private static void insertTestData(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO repos(id, owner_uuid, name, created_at) VALUES (1, '00000000-0000-0000-0000-000000000001', 'testrepo', 0)");
            st.execute("INSERT INTO repos(id, owner_uuid, name, created_at) VALUES (2, '00000000-0000-0000-0000-000000000002', 'testrepo2', 0)");
            st.execute("INSERT INTO remotes(id, repo_id, name, url) VALUES (1, 1, 'origin', 'https://example.com/repo')");
            st.execute("INSERT INTO remotes(id, repo_id, name, url) VALUES (2, 2, 'origin', 'https://example.com/repo')");
            st.execute("INSERT INTO branches(id, repo_id, name, created_at) VALUES (1, 1, 'main', 0)");
            st.execute("INSERT INTO commits(id, branch_id, player_uuid, player_name, message, schem_path, created_at, world_uuid, world_name, min_x, min_y, min_z, max_x, max_y, max_z) VALUES (1, 1, '00000000-0000-0000-0000-000000000001', 'Steve', 'msg', '/path', 0, '11111111-2222-3333-4444-555555555555', 'world', 0,0,0, 10,10,10)");
            st.execute("INSERT INTO commit_git_shas(commit_id, remote_id, git_sha) VALUES (1, 1, 'aaa000')");
            // Commit 2 intentionally has no sha row.
            st.execute("INSERT INTO commits(id, branch_id, player_uuid, player_name, message, schem_path, created_at, world_uuid, world_name, min_x, min_y, min_z, max_x, max_y, max_z) VALUES (2, 1, '00000000-0000-0000-0000-000000000001', 'Steve', 'msg2', '/path2', 1, '11111111-2222-3333-4444-555555555555', 'world', 0,0,0, 10,10,10)");
        }
    }
}
