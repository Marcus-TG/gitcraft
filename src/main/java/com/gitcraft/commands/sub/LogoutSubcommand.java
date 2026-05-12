package com.gitcraft.commands.sub;

import com.gitcraft.database.GitHubTokenDao;
import com.gitcraft.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;

public final class LogoutSubcommand implements Subcommand {

    private final Plugin plugin;
    private final GitHubTokenDao tokenDao;

    public LogoutSubcommand(Plugin plugin, GitHubTokenDao tokenDao) {
        this.plugin = plugin;
        this.tokenDao = tokenDao;
    }

    @Override
    public void execute(Player player, String[] args) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                tokenDao.deleteByPlayer(playerId);
                sendOnMain(playerId, Messages.GITHUB_LOGGED_OUT);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Logout DB failed", e);
                sendOnMain(playerId, "Failed to remove token: " + e.getMessage());
            }
        });
    }

    private void sendOnMain(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }
}
