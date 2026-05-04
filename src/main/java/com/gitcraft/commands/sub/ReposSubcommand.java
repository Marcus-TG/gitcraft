package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.database.BranchDao;
import com.gitcraft.database.RepoDao;
import com.gitcraft.database.RepoRecord;
import com.gitcraft.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public final class ReposSubcommand implements Subcommand {

    private static final String PERMISSION = "gitcraft.use";

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private final GitCraft plugin;
    private final RepoDao repoDao;
    private final BranchDao branchDao;

    public ReposSubcommand(GitCraft plugin, RepoDao repoDao, BranchDao branchDao) {
        this.plugin = plugin;
        this.repoDao = repoDao;
        this.branchDao = branchDao;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Messages.NO_PERMISSION);
            return;
        }
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> listRepos(playerId));
    }

    private void listRepos(UUID playerId) {
        try {
            List<RepoRecord> repos = repoDao.findByOwner(playerId);
            List<String> rows = new ArrayList<>();
            for (RepoRecord r : repos) {
                int branchCount = branchDao.countByRepo(r.id());
                String when = DATE_FORMAT.format(Instant.ofEpochMilli(r.createdAt()));
                rows.add(String.format(Messages.REPOS_ROW, r.name(), branchCount, when));
            }
            int total = repos.size();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(playerId);
                if (p == null || !p.isOnline()) return;
                if (total == 0) {
                    p.sendMessage(Messages.REPOS_EMPTY);
                    return;
                }
                p.sendMessage(String.format(Messages.REPOS_HEADER, total));
                for (String row : rows) p.sendMessage(row);
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Repos list failed", e);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(playerId);
                if (p == null || !p.isOnline()) return;
                p.sendMessage(String.format(Messages.REPOS_DB_ERROR,
                        e.getMessage() == null ? "(no detail)" : e.getMessage()));
            });
        }
    }
}
