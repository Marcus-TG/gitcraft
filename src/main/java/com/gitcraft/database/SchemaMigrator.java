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
 * v1 → v2: drops {@code commits} (Phase 2 dev rows are disposable) so it can be
 * recreated with the world + bounding-box columns required by Phase 3 restore.
 * v2 → v3: adds {@code parent_commit_id} via ALTER TABLE (non-destructive; existing rows get NULL).
 */
public final class SchemaMigrator {

    private static final String SCHEMA_RESOURCE = "/database/schema.sql";
    private static final int CURRENT_VERSION = 3;

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

        if (existing < 2) {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS commits");
            }
        }

        if (existing == 2) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE commits ADD COLUMN parent_commit_id INTEGER");
            }
        }

        String sql = stripLineComments(readResource(SCHEMA_RESOURCE));
        try (Statement st = conn.createStatement()) {
            for (String stmt : sql.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
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
