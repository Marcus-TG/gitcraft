package com.gitcraft;

import org.bukkit.plugin.java.JavaPlugin;

public final class GitCraft extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("GitCraft enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("GitCraft disabled.");
    }
}
