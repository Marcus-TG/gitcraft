package com.gitcraft.git;

import com.gitcraft.database.CommitDao;
import com.gitcraft.database.CommitGitShaDao;
import com.gitcraft.database.CommitRecord;
import com.gitcraft.database.RemoteRecord;
import com.gitcraft.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GitPushService {

    private final CommitMapper mapper;
    private final Logger logger;

    public GitPushService(CommitMapper mapper, Logger logger) {
        this.mapper = mapper;
        this.logger = logger;
    }

    public void pushAsync(Plugin plugin, UUID playerId, UUID ownerUuid, String ownerName,
                          long branchId, String branchName,
                          RemoteRecord remote, String accessToken,
                          CommitDao commitDao, CommitGitShaDao shaDao,
                          GitRepoManager gitRepoManager, Path schematicsDir) {

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                doPush(plugin, playerId, ownerUuid, ownerName, branchId, branchName,
                        remote, accessToken, commitDao, shaDao, gitRepoManager, schematicsDir);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Push failed", e);
                send(plugin, playerId, String.format(Messages.PUSH_FAILED, safe(e.getMessage())));
            }
        });
    }

    private void doPush(Plugin plugin, UUID playerId, UUID ownerUuid, String ownerName,
                        long branchId, String branchName,
                        RemoteRecord remote, String accessToken,
                        CommitDao commitDao, CommitGitShaDao shaDao,
                        GitRepoManager gitRepoManager, Path schematicsDir) throws Exception {

        // Resolve repo name from the remote's repo_id — we receive it via ownerName + branchName
        // enough to reconstruct the working tree via ownerUuid + we pass repo name from caller.
        // (The caller passes ownerName here as the repo name for simplicity — see PushSubcommand.)
        try (Repository repo = gitRepoManager.openOrInit(ownerUuid, ownerName);
             Git git = new Git(repo)) {

            GitRepoManager.checkoutOrCreateBranch(git, branchName);

            // Record pre-push HEAD so we can roll back if remote push fails
            ObjectId prePushHead = repo.resolve("HEAD");

            List<Long> unpushedIds = shaDao.findUnpushedCommitIds(branchId, remote.id());
            if (unpushedIds.isEmpty()) {
                send(plugin, playerId, String.format(Messages.PUSH_NOTHING_TO_PUSH, remote.name()));
                return;
            }

            send(plugin, playerId,
                    String.format(Messages.PUSH_IN_PROGRESS, unpushedIds.size(), remote.name(), branchName));

            // Build Git commits in memory — don't record SHAs until remote push succeeds
            List<long[]> commitIdAndSha = new ArrayList<>(); // [commitId, sha as placeholder via list]
            List<String> shas = new ArrayList<>();

            for (long commitId : unpushedIds) {
                Optional<String> existingSha = shaDao.findAnyShaForCommit(commitId);
                if (existingSha.isPresent()) {
                    // This commit was already pushed to another remote — reuse the Git SHA
                    commitIdAndSha.add(new long[]{commitId});
                    shas.add(existingSha.get());
                    continue;
                }

                CommitRecord record = commitDao.findById(commitId).orElseThrow(
                        () -> new IllegalStateException("Commit " + commitId + " not found"));

                Path schemPath = Path.of(record.schemPath());

                // Resolve merge parent SHA if this is a merge commit
                String mergeParentSha = null;
                if (record.mergeParentCommitId() != null) {
                    mergeParentSha = shaDao.findAnyShaForCommit(record.mergeParentCommitId()).orElse(null);
                }

                String sha = mapper.createGitCommit(git, record, branchName, schemPath, mergeParentSha);
                commitIdAndSha.add(new long[]{commitId});
                shas.add(sha);
            }

            // Push to remote — all Git commits are now local
            gitRepoManager.setRemoteUrl(repo, remote.name(), remote.url());
            Iterable<PushResult> results = git.push()
                    .setRemote(remote.url())
                    .setCredentialsProvider(GitRepoManager.credentials(accessToken))
                    .call();

            // Check for rejection
            for (PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    RemoteRefUpdate.Status status = update.getStatus();
                    if (status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD) {
                        rollback(git, prePushHead);
                        send(plugin, playerId,
                                String.format(Messages.PUSH_REJECTED_NOT_FAST_FORWARD, remote.name()));
                        return;
                    }
                    if (status == RemoteRefUpdate.Status.REJECTED_OTHER_REASON) {
                        String msg = update.getMessage();
                        rollback(git, prePushHead);
                        if (msg != null && msg.contains("not found")) {
                            send(plugin, playerId, Messages.PUSH_REJECTED_NOT_FOUND);
                        } else {
                            send(plugin, playerId, String.format(Messages.PUSH_FAILED, safe(msg)));
                        }
                        return;
                    }
                }
            }

            // Push succeeded — now record SHAs
            for (int i = 0; i < commitIdAndSha.size(); i++) {
                shaDao.insert(commitIdAndSha.get(i)[0], remote.id(), shas.get(i));
            }

            send(plugin, playerId,
                    String.format(Messages.PUSH_SUCCESS, unpushedIds.size(), remote.name(), branchName));
        }
    }

    private void rollback(Git git, ObjectId prePushHead) {
        if (prePushHead == null) return;
        try {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(prePushHead.name()).call();
        } catch (GitAPIException e) {
            logger.log(Level.WARNING, "Failed to roll back local JGit branch after push failure", e);
        }
    }

    private static void send(Plugin plugin, UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }

    private static String safe(String s) { return s == null ? "(no detail)" : s; }
}
