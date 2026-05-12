package com.gitcraft.commands.sub;

import com.gitcraft.database.CommitDao;
import com.gitcraft.database.CommitGitShaDao;
import com.gitcraft.database.GitHubTokenDao;
import com.gitcraft.database.GitHubTokenRecord;
import com.gitcraft.database.RemoteDao;
import com.gitcraft.database.RemoteRecord;
import com.gitcraft.git.GitPushService;
import com.gitcraft.git.GitRepoManager;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;

public final class PushSubcommand implements Subcommand {

    private final Plugin plugin;
    private final SelectionManager selectionManager;
    private final GitHubTokenDao tokenDao;
    private final RemoteDao remoteDao;
    private final CommitDao commitDao;
    private final CommitGitShaDao shaDao;
    private final GitPushService pushService;
    private final GitRepoManager gitRepoManager;
    private final Path schematicsDir;

    public PushSubcommand(Plugin plugin, SelectionManager selectionManager,
                          GitHubTokenDao tokenDao, RemoteDao remoteDao,
                          CommitDao commitDao, CommitGitShaDao shaDao,
                          GitPushService pushService, GitRepoManager gitRepoManager,
                          Path schematicsDir) {
        this.plugin = plugin;
        this.selectionManager = selectionManager;
        this.tokenDao = tokenDao;
        this.remoteDao = remoteDao;
        this.commitDao = commitDao;
        this.shaDao = shaDao;
        this.pushService = pushService;
        this.gitRepoManager = gitRepoManager;
        this.schematicsDir = schematicsDir;
    }

    @Override
    public void execute(Player player, String[] args) {
        Selection sel = selectionManager.get(player.getUniqueId()).orElse(null);
        if (sel == null || sel.repoId() == null || sel.branchId() == null) {
            player.sendMessage(Messages.PUSH_NO_REPO);
            return;
        }

        String remoteName = args.length > 0 ? args[0] : "origin";
        long repoId    = sel.repoId();
        long branchId  = sel.branchId();
        String branchName = sel.branchName();
        String repoName   = sel.repoName();
        UUID playerId     = player.getUniqueId();
        UUID ownerUuid    = playerId; // repos are per-player; owner == player

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                GitHubTokenRecord token = tokenDao.findByPlayer(playerId).orElse(null);
                if (token == null) {
                    sendOnMain(playerId, Messages.PUSH_NO_TOKEN);
                    return;
                }

                RemoteRecord remote = remoteDao.findByRepoAndName(repoId, remoteName).orElse(null);
                if (remote == null) {
                    sendOnMain(playerId,
                            String.format(Messages.PUSH_NO_REMOTE, remoteName, remoteName));
                    return;
                }

                pushService.pushAsync(plugin, playerId, ownerUuid, repoName,
                        branchId, branchName, remote, token.accessToken(),
                        commitDao, shaDao, gitRepoManager, schematicsDir);

            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Push guard DB failed", e);
                sendOnMain(playerId, String.format(Messages.PUSH_FAILED, e.getMessage()));
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
