package com.gitcraft;

import com.gitcraft.commands.GitCraftCommand;
import com.gitcraft.config.GitCraftConfig;
import com.gitcraft.export.SchematicExporter;
import com.gitcraft.listeners.WandListener;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.util.logging.Level;

public final class GitCraft extends JavaPlugin {

    private GitCraftConfig config;
    private SelectionManager selectionManager;

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

        SchematicExporter exporter = new SchematicExporter(this);

        getServer().getPluginManager().registerEvents(
                new WandListener(this, selectionManager), this);

        PluginCommand cmd = getCommand("gitcraft");
        if (cmd != null) {
            GitCraftCommand executor = new GitCraftCommand(this, selectionManager, exporter);
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
        getLogger().info("GitCraft disabled.");
    }

    public GitCraftConfig gitCraftConfig() {
        return config;
    }

    public SelectionManager selectionManager() {
        return selectionManager;
    }
}
