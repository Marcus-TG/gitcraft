package com.gitcraft.commands.sub;

import com.gitcraft.database.BranchDao;
import com.gitcraft.database.BranchRecord;
import com.gitcraft.database.CommitDao;
import com.gitcraft.database.CommitGitShaDao;
import com.gitcraft.database.Database;
import com.gitcraft.database.GitHubTokenDao;
import com.gitcraft.database.GitHubTokenRecord;
import com.gitcraft.database.HeadDao;
import com.gitcraft.database.RemoteDao;
import com.gitcraft.database.RemoteRecord;
import com.gitcraft.database.RepoDao;
import com.gitcraft.database.RepoRecord;
import com.gitcraft.git.GitPullService;
import com.gitcraft.git.GitRepoManager;
import com.gitcraft.merge.OpManager;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;
import java.util.logging.Level;

public final class PullSubcommand implements Subcommand {

    private final Plugin plugin;
    private final SelectionManager selectionManager;
    private final OpManager opManager;
    private final Database database;
    private final GitHubTokenDao tokenDao;
    private final RemoteDao remoteDao;
    private final RepoDao repoDao;
    private final BranchDao branchDao;
    private final CommitDao commitDao;
    private final CommitGitShaDao shaDao;
    private final HeadDao headDao;
    private final GitPullService pullService;
    private final GitRepoManager gitRepoManager;
    private final Path schematicsDir;

    public PullSubcommand(Plugin plugin, SelectionManager selectionManager, OpManager opManager,
                          Database database,
                          GitHubTokenDao tokenDao, RemoteDao remoteDao,
                          RepoDao repoDao, BranchDao branchDao,
                          CommitDao commitDao, CommitGitShaDao shaDao, HeadDao headDao,
                          GitPullService pullService, GitRepoManager gitRepoManager,
                          Path schematicsDir) {
        this.plugin = plugin;
        this.selectionManager = selectionManager;
        this.opManager = opManager;
        this.database = database;
        this.tokenDao = tokenDao;
        this.remoteDao = remoteDao;
        this.repoDao = repoDao;
        this.branchDao = branchDao;
        this.commitDao = commitDao;
        this.shaDao = shaDao;
        this.headDao = headDao;
        this.pullService = pullService;
        this.gitRepoManager = gitRepoManager;
        this.schematicsDir = schematicsDir;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (opManager.has(player.getUniqueId())) {
            player.sendMessage(Messages.PULL_BLOCKED_ACTIVE_SESSION);
            return;
        }

        Selection sel = selectionManager.get(player.getUniqueId()).orElse(null);
        if (sel == null || sel.repoId() == null || sel.branchId() == null) {
            player.sendMessage(Messages.PULL_NO_REPO);
            return;
        }

        boolean here = Arrays.asList(args).contains("--here");
        String remoteName = Arrays.stream(args)
                .filter(a -> !a.equals("--here"))
                .findFirst().orElse("origin");
        BlockVector3 pasteOrigin = here
                ? BlockVector3.at(player.getLocation().getBlockX(),
                                  player.getLocation().getBlockY(),
                                  player.getLocation().getBlockZ())
                : null;

        long repoId   = sel.repoId();
        long branchId = sel.branchId();
        UUID playerId = player.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                GitHubTokenRecord token = tokenDao.findByPlayer(playerId).orElse(null);
                if (token == null) {
                    sendOnMain(playerId, Messages.PULL_NO_TOKEN);
                    return;
                }

                RemoteRecord remote = remoteDao.findByRepoAndName(repoId, remoteName).orElse(null);
                if (remote == null) {
                    sendOnMain(playerId,
                            String.format(Messages.PULL_NO_REMOTE, remoteName, remoteName));
                    return;
                }

                RepoRecord repo = repoDao.findById(repoId).orElse(null);
                if (repo == null) {
                    sendOnMain(playerId, Messages.PULL_NO_REPO);
                    return;
                }

                BranchRecord branch = branchDao.findById(branchId).orElse(null);
                if (branch == null) {
                    sendOnMain(playerId, Messages.PULL_NO_REPO);
                    return;
                }

                pullService.pullAsync(plugin, playerId, repo, branch, remote,
                        token.accessToken(), database, commitDao, shaDao, headDao, repoDao,
                        gitRepoManager, schematicsDir, selectionManager, pasteOrigin);

            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Pull guard DB failed", e);
                sendOnMain(playerId, String.format(Messages.PULL_FAILED, e.getMessage()));
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
