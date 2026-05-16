package com.gitcraft.database;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs database migrations once on plugin enable. Idempotent — safe to invoke
 * on every startup. Schema lives in the classpath resource {@code database/schema.sql}.
 *
 * v1-v3 → v4: drops {@code commits} (destructive — region_name replaced by branch_id)
 *             and lets schema.sql recreate repos, branches, heads, and commits.
 * v4    → v5: adds {@code branches.fork_commit_id} to preserve commit graph lineage
 *             across branch boundaries.
 * v5    → v6: adds {@code heads.commit_id} to track which specific commit HEAD points at
 *             within a branch, rather than always resolving to MAX(id).
 * v6    → v7: adds {@code commits.merge_parent_commit_id} — second parent for merge commits.
 * v7    → v8: adds {@code stashes} table — per-(player, repo) LIFO stack of saved selections.
 *             New table only; schema.sql's {@code CREATE TABLE IF NOT EXISTS} handles both
 *             fresh installs and upgrades, so no explicit ALTER block is needed here.
 * v8    → v9: adds {@code commits.cherry_pick_source_id} — informational pointer to the
 *             commit that was cherry-picked. Metadata only; not part of the parent DAG.
 * v9    → v10: adds {@code remotes}, {@code github_tokens}, and {@code commit_git_shas} tables
 *              for GitHub integration. New tables only; no ALTER needed.
 * v10   → v11: adds {@code repos.origin_offset_{x,y,z,set}} — stable repo-space origin for
 *              coordinate translation. Four ALTER TABLE statements; safe to run on fresh installs
 *              (duplicate-column errors suppressed).
 * v11   → v12: scopes {@code commit_git_shas} uniqueness to {@code (remote_id, git_sha)}.
 *              Drops the global {@code git_sha} unique index so the same SHA can exist in
 *              multiple local repos (each with its own remote row), matching real Git behaviour.
 */
public final class SchemaMigrator {

    private static final String SCHEMA_RESOURCE = "/database/schema.sql";
    private static final int CURRENT_VERSION = 12;

    /**
     * Pulls the connection from {@code database} on every call so the strongly-held
     * field is the source of truth — never cache a {@link Connection} across calls.
     */
    public void migrate(Database database) throws SQLException, IOException {
        Connection conn = database.connection();
        if (conn == null || conn.isClosed()) {
            throw new SQLException("Database connection is not open. Call Database.open() first.");
        }

        ensureSchemaVersionTable(conn);
        int existing = readMaxVersion(conn);

        if (existing < 4) {
            // Pre-v4: drop commits so schema.sql can recreate with branch_id.
            // repos/branches/heads don't exist yet — DROP IF EXISTS is a safe no-op.
            try (Statement st = conn.createStatement()) {
                st.execute("DROP INDEX IF EXISTS idx_commits_region");
                st.execute("DROP INDEX IF EXISTS idx_commits_player");
                st.execute("DROP INDEX IF EXISTS idx_commits_world");
                st.execute("DROP TABLE IF EXISTS commits");
            }
        }

        // Always run schema.sql — idempotent CREATE TABLE IF NOT EXISTS statements.
        String sql = stripLineComments(readResource(SCHEMA_RESOURCE));
        try (Statement st = conn.createStatement()) {
            for (String stmt : sql.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        }

        if (existing < 5) {
            // v4 → v5: add fork_commit_id to branches. On fresh installs schema.sql already
            // includes the column, so suppress the "duplicate column name" error.
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE branches ADD COLUMN fork_commit_id INTEGER REFERENCES commits(id)");
            } catch (SQLException e) {
                if (!e.getMessage().toLowerCase().contains("duplicate column name")) throw e;
            }
        }

        if (existing < 6) {
            // v5 → v6: add commit_id to heads. On fresh installs schema.sql already includes
            // the column, so suppress the "duplicate column name" error.
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE heads ADD COLUMN commit_id INTEGER REFERENCES commits(id)");
            } catch (SQLException e) {
                if (!e.getMessage().toLowerCase().contains("duplicate column name")) throw e;
            }
        }

        if (existing < 7) {
            // v6 → v7: add merge_parent_commit_id to commits. Fresh installs already have it
            // via schema.sql; suppress duplicate-column error.
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE commits ADD COLUMN merge_parent_commit_id INTEGER");
            } catch (SQLException e) {
                if (!e.getMessage().toLowerCase().contains("duplicate column name")) throw e;
            }
        }

        if (existing < 9) {
            // v8 → v9: add cherry_pick_source_id to commits. Fresh installs already have it
            // via schema.sql; suppress duplicate-column error.
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE commits ADD COLUMN cherry_pick_source_id INTEGER");
            } catch (SQLException e) {
                if (!e.getMessage().toLowerCase().contains("duplicate column name")) throw e;
            }
        }

        // v9 → v10: new tables (remotes, github_tokens, commit_git_shas + index).
        // schema.sql CREATE TABLE IF NOT EXISTS handles both fresh installs and upgrades;
        // no explicit ALTER blocks needed here.

        if (existing < 11) {
            // v10 → v11: add origin_offset columns to repos. Fresh installs already have them
            // via schema.sql; suppress duplicate-column errors.
            String[] alters = {
                "ALTER TABLE repos ADD COLUMN origin_offset_x   INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE repos ADD COLUMN origin_offset_y   INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE repos ADD COLUMN origin_offset_z   INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE repos ADD COLUMN origin_offset_set INTEGER NOT NULL DEFAULT 0"
            };
            for (String alter : alters) {
                try (Statement st = conn.createStatement()) {
                    st.execute(alter);
                } catch (SQLException e) {
                    if (!e.getMessage().toLowerCase().contains("duplicate column name")) throw e;
                }
            }
        }

        if (existing < 12) {
            // v11 → v12: replace the global git_sha unique index with a (remote_id, git_sha)
            // scoped index so the same SHA can exist across independent local repos.
            try (Statement st = conn.createStatement()) {
                st.execute("DROP INDEX IF EXISTS idx_commit_git_shas_sha");
            }
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_commit_git_shas_remote_sha ON commit_git_shas(remote_id, git_sha)");
            }
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO schema_version(version, applied_at) VALUES (?, ?)")) {
            ps.setInt(1, CURRENT_VERSION);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private void ensureSchemaVersionTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (" +
                    "version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
        }
    }

    private int readMaxVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private String stripLineComments(String sql) {
        return Stream.of(sql.split("\n", -1))
                .map(line -> {
                    int idx = line.indexOf("--");
                    return idx < 0 ? line : line.substring(0, idx);
                })
                .collect(Collectors.joining("\n"));
    }

    private String readResource(String path) throws IOException {
        try (InputStream in = SchemaMigrator.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Schema resource not found: " + path);
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return br.lines().collect(Collectors.joining("\n"));
            }
        }
    }
}
