package com.gitcraft.database;

import com.gitcraft.GitCraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the single shared SQLite connection for the plugin's lifetime.
 * SQLite serializes writers internally; all DB calls in the plugin happen on
 * the async scheduler, so a single connection is sufficient.
 */
public final class Database {

    private final GitCraft plugin;
    private Connection connection;

    public Database(GitCraft plugin) {
        this.plugin = plugin;
    }

    public void open() throws SQLException, IOException {
        Path dbPath = plugin.gitCraftConfig().databaseFile();
        Path parent = dbPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        this.connection = DriverManager.getConnection(url);

        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA foreign_keys=ON;");
            st.execute("PRAGMA synchronous=NORMAL;");
        }
    }

    /**
     * Main-thread access is a bug. Call only from inside
     * {@code Bukkit.getScheduler().runTaskAsynchronously(...)}.
     */
    public Connection connection() {
        return connection;
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().warning("Error closing SQLite connection: " + e.getMessage());
            }
            connection = null;
        }
    }
}
