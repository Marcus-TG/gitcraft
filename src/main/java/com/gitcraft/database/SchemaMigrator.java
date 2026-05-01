package com.gitcraft.database;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs database migrations once on plugin enable. Idempotent — safe to invoke
 * on every startup. Schema lives in the classpath resource {@code database/schema.sql}.
 */
public final class SchemaMigrator {

    private static final String SCHEMA_RESOURCE = "/database/schema.sql";
    private static final int CURRENT_VERSION = 1;

    /**
     * Pulls the connection from {@code database} on every call so the strongly-held
     * field is the source of truth — never cache a {@link Connection} across calls.
     */
    public void migrate(Database database) throws SQLException, IOException {
        Connection conn = database.connection();
        if (conn == null || conn.isClosed()) {
            throw new SQLException("Database connection is not open. Call Database.open() first.");
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
