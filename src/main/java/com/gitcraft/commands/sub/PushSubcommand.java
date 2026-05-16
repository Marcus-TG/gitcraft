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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class PushSubcommand implements Subcommand {

    private static final String PERMISSION_FORCE = "gitcraft.push.force";
    private static final long   CONFIRM_TTL_MS   = 30_000L;

    private final Plugin plugin;
    private final SelectionManager selectionManager;
    private final GitHubTokenDao tokenDao;
    private final RemoteDao remoteDao;
    private final CommitDao commitDao;
    private final CommitGitShaDao shaDao;
    private final GitPushService pushService;
    private final GitRepoManager gitRepoManager;
    private final Path schematicsDir;

    // TODO: Entries are not evicted on disconnect. 30s TTL is sufficient for now.
    private final ConcurrentHashMap<UUID, PendingForcePush> pending = new ConcurrentHashMap<>();

    record PushArgs(String remoteName, boolean force) {
        static PushArgs parse(String[] args) {
            boolean force = false;
            String remote = null;
            for (String a : args) {
                if ("--force".equals(a)) {
                    if (force) return null;  // duplicate --force → invalid
                    force = true;
                } else if (remote == null) {
                    remote = a;
                } else {
                    return null;             // >1 non-flag arg → invalid
                }
            }
            return new PushArgs(remote == null ? "origin" : remote, force);
        }
    }

    private record PendingForcePush(long repoId, long branchId, String remoteName, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
        boolean matches(long r, long b, String rm) {
            return repoId == r && branchId == b && remoteName.equals(rm);
        }
    }

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

        PushArgs parsed = PushArgs.parse(args);
        if (parsed == null) {
            player.sendMessage(Messages.PUSH_USAGE);
            return;
        }

        String remoteName = parsed.remoteName();
        boolean force     = parsed.force();
        long repoId       = sel.repoId();
        long branchId     = sel.branchId();
        String branchName = sel.branchName();
        String repoName   = sel.repoName();
        UUID playerId     = player.getUniqueId();
        UUID ownerUuid    = playerId;

        if (force) {
            if (!player.hasPermission(PERMISSION_FORCE)) {
                player.sendMessage(Messages.NO_PERMISSION);
                return;
            }

            PendingForcePush existing = pending.get(playerId);
            if (existing != null && !existing.isExpired() && existing.matches(repoId, branchId, remoteName)) {
                pending.remove(playerId);
                player.sendMessage(String.format(Messages.PUSH_FORCE_STARTED, remoteName, branchName));
                scheduleAsync(plugin, playerId, ownerUuid, repoName, branchId, branchName,
                        repoId, remoteName, true);
            } else {
                pending.put(playerId, new PendingForcePush(repoId, branchId, remoteName,
                        System.currentTimeMillis() + CONFIRM_TTL_MS));
                player.sendMessage(String.format(Messages.PUSH_FORCE_WARN, remoteName, branchName, remoteName));
            }
            return;
        }

        scheduleAsync(plugin, playerId, ownerUuid, repoName, branchId, branchName,
                repoId, remoteName, false);
    }

    private void scheduleAsync(Plugin plugin, UUID playerId, UUID ownerUuid, String repoName,
                               long branchId, String branchName, long repoId,
                               String remoteName, boolean force) {
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
                        commitDao, shaDao, gitRepoManager, schematicsDir, force);

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
