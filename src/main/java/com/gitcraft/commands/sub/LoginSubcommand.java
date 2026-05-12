package com.gitcraft.commands.sub;

import com.gitcraft.database.GitHubTokenDao;
import com.gitcraft.github.GitHubAuthService;
import com.gitcraft.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;

public final class LoginSubcommand implements Subcommand {

    private final Plugin plugin;
    private final GitHubAuthService authService;
    private final GitHubTokenDao tokenDao;

    public LoginSubcommand(Plugin plugin, GitHubAuthService authService, GitHubTokenDao tokenDao) {
        this.plugin = plugin;
        this.authService = authService;
        this.tokenDao = tokenDao;
    }

    @Override
    public void execute(Player player, String[] args) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (tokenDao.findByPlayer(playerId).isPresent()) {
                    sendOnMain(playerId, Messages.GITHUB_ALREADY_LOGGED_IN);
                    return;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Login DB check failed", e);
            }
            authService.startAuthFlow(player, tokenDao, plugin);
        });
    }

    private void sendOnMain(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }
}
