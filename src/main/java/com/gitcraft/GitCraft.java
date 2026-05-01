package com.gitcraft;

import com.gitcraft.commands.GitCraftCommand;
import com.gitcraft.commit.CommitService;
import com.gitcraft.config.GitCraftConfig;
import com.gitcraft.database.CommitDao;
import com.gitcraft.database.Database;
import com.gitcraft.database.SchemaMigrator;
import com.gitcraft.export.SchematicExporter;
import com.gitcraft.listeners.WandListener;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.logging.Level;

public final class GitCraft extends JavaPlugin {

    private GitCraftConfig config;
    private SelectionManager selectionManager;
    private Database database;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (Bukkit.getPluginManager().getPlugin("WorldEdit") == null) {
            getLogger().severe(Messages.WORLDEDIT_MISSING);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.config = new GitCraftConfig(this);
        this.config.load();

        this.selectionManager = new SelectionManager();

        // DB open + migrate runs on the main thread — but only once at enable, never per tick.
        // SQLite open on local disk is sub-ms; acceptable here, would NOT be inside an event handler.
        this.database = new Database(this);
        try {
            database.open();
            new SchemaMigrator().migrate(database);
        } catch (SQLException | IOException e) {
            getLogger().log(Level.SEVERE, "Failed to initialize SQLite database; disabling plugin.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        SchematicExporter exporter = new SchematicExporter(this);
        CommitDao commitDao = new CommitDao(database);
        CommitService commitService = new CommitService(this, exporter, commitDao);

        getServer().getPluginManager().registerEvents(
                new WandListener(this, selectionManager), this);

        PluginCommand cmd = getCommand("gitcraft");
        if (cmd != null) {
            GitCraftCommand executor = new GitCraftCommand(this, selectionManager, commitService, commitDao);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        } else {
            getLogger().warning("Command 'gitcraft' not declared in plugin.yml.");
        }

        // Per CLAUDE.md: even directory creation is FS work — keep off main thread.
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Files.createDirectories(config.schematicsDir());
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Failed to create schematics directory", e);
            }
        });

        getLogger().info("GitCraft enabled.");
    }

    @Override
    public void onDisable() {
        if (selectionManager != null) {
            selectionManager.clearAll();
        }
        if (database != null) {
            database.close();
        }
        getLogger().info("GitCraft disabled.");
    }

    public GitCraftConfig gitCraftConfig() {
        return config;
    }

    public SelectionManager selectionManager() {
        return selectionManager;
    }
}
