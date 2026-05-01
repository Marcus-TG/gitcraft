package com.gitcraft.config;

import com.gitcraft.GitCraft;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class GitCraftConfig {

    private final GitCraft plugin;
    private Material wandMaterial;
    private Path schematicsDir;

    public GitCraftConfig(GitCraft plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration cfg = plugin.getConfig();

        String wandName = cfg.getString("selection.wand-material", "WOODEN_AXE");
        Material parsed = Material.matchMaterial(wandName);
        if (parsed == null) {
            plugin.getLogger().warning("Invalid selection.wand-material '" + wandName + "', defaulting to WOODEN_AXE.");
            parsed = Material.WOODEN_AXE;
        }
        this.wandMaterial = parsed;

        String schemPath = cfg.getString("storage.schematics-path", "schematics");
        Path p = Paths.get(schemPath);
        this.schematicsDir = p.isAbsolute() ? p : plugin.getDataFolder().toPath().resolve(p);
    }

    public Material wandMaterial() {
        return wandMaterial;
    }

    public Path schematicsDir() {
        return schematicsDir;
    }
}
