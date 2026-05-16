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
                          GitRepoManager gitRepoManager, Path schematicsDir,
                          boolean force) {

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                doPush(plugin, playerId, ownerUuid, ownerName, branchId, branchName,
                        remote, accessToken, commitDao, shaDao, gitRepoManager, schematicsDir, force);
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
                        GitRepoManager gitRepoManager, Path schematicsDir,
                        boolean force) throws Exception {

        // (The caller passes ownerName here as the repo name — see PushSubcommand.)
        try (Repository repo = gitRepoManager.openOrInit(ownerUuid, ownerName);
             Git git = new Git(repo)) {

            logger.info("push lookup: repoId=" + remote.repoId() + " remoteId=" + remote.id()
                    + " remoteName=" + remote.name() + " branchId=" + branchId);
            List<Long> unpushedIds = shaDao.findUnpushedCommitIds(branchId, remote.id());

            String branchStartSha = findBranchStartSha(repo, branchName, unpushedIds, commitDao, shaDao);
            GitRepoManager.checkoutOrCreateBranch(git, branchName, branchStartSha);

            ObjectId prePushHead = repo.resolve("HEAD");

            // Align local JGit branch to the current DB tip before force-pushing.
            // After a hard-reset, the local JGit ref may still point at deleted commits;
            // resetting to the known tip SHA prevents them from being re-pushed.
            String alignedTipSha = null;
            if (force) {
                Long tipCommitId = commitDao.findLatestIdByBranch(branchId).orElse(null);
                if (tipCommitId != null) {
                    alignedTipSha = shaDao.findAnyShaForCommit(tipCommitId).orElse(null);
                    if (alignedTipSha != null) {
                        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(alignedTipSha).call();
                    }
                }
            }
            if (unpushedIds.isEmpty()) {
                if (!force || alignedTipSha == null) {
                    send(plugin, playerId, force
                            ? Messages.PUSH_FORCE_NOTHING
                            : String.format(Messages.PUSH_NOTHING_TO_PUSH, remote.name()));
                    return;
                }
                // force=true + alignedTipSha != null: fall through to force-update the remote to the current tip
            }

            send(plugin, playerId, force
                    ? String.format(Messages.PUSH_FORCE_STARTED, remote.name(), branchName)
                    : String.format(Messages.PUSH_IN_PROGRESS, unpushedIds.size(), remote.name(), branchName));

            List<long[]> commitIds = new ArrayList<>();
            List<String> shas = new ArrayList<>();

            for (long commitId : unpushedIds) {
                Optional<String> existingSha = shaDao.findAnyShaForCommit(commitId);
                if (existingSha.isPresent()) {
                    commitIds.add(new long[]{commitId});
                    shas.add(existingSha.get());
                    continue;
                }

                CommitRecord record = commitDao.findById(commitId).orElseThrow(
                        () -> new IllegalStateException("Commit " + commitId + " not found"));

                Path schemPath = Path.of(record.schemPath());

                String mergeParentSha = null;
                if (record.mergeParentCommitId() != null) {
                    mergeParentSha = shaDao.findAnyShaForCommit(record.mergeParentCommitId()).orElse(null);
                }

                String sha = mapper.createGitCommit(git, record, branchName, schemPath, mergeParentSha);
                commitIds.add(new long[]{commitId});
                shas.add(sha);
            }

            // When force-updating with 0 new commits, shas is empty — use the aligned tip as expected.
            String expectedTipSha = shas.isEmpty() ? alignedTipSha : shas.get(shas.size() - 1);

            gitRepoManager.setRemoteUrl(repo, remote.name(), remote.url());
            Iterable<PushResult> results = git.push()
                    .setRemote(remote.url())
                    .setCredentialsProvider(GitRepoManager.credentials(accessToken))
                    .setForce(force)
                    .call();

            for (PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    RemoteRefUpdate.Status status = update.getStatus();
                    logger.info("push status: " + status + " for " + update.getRemoteName());
                    if (status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD) {
                        // On normal push, REJECTED_NONFASTFORWARD can be a false positive:
                        // GitHub may have accepted the commits while JGit's local state was stale.
                        // On force push, NFF means GitHub refused the update (e.g., branch protection).
                        if (!force && remoteTipMatchesAfterFetch(git, repo, remote, branchName, accessToken, expectedTipSha)) {
                            recordShas(commitIds, shas, remote, shaDao);
                            send(plugin, playerId,
                                    String.format(Messages.PUSH_SUCCESS, unpushedIds.size(), remote.name(), branchName));
                            logger.info("push nff recovered: recorded " + commitIds.size() + " SHA(s)");
                        } else {
                            rollback(git, prePushHead);
                            send(plugin, playerId, force
                                    ? String.format(Messages.PUSH_FAILED, "remote rejected force-push (branch may be protected)")
                                    : String.format(Messages.PUSH_REJECTED_NOT_FAST_FORWARD, remote.name()));
                        }
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

            // Normal success path.
            recordShas(commitIds, shas, remote, shaDao);
            send(plugin, playerId,
                    String.format(Messages.PUSH_SUCCESS, unpushedIds.size(), remote.name(), branchName));
        }
    }

    private String findBranchStartSha(Repository repo, String branchName, List<Long> unpushedIds,
                                      CommitDao commitDao, CommitGitShaDao shaDao)
            throws IOException, SQLException {
        if (GitRepoManager.branchExists(repo, branchName) || unpushedIds.isEmpty()) {
            return null;
        }
        CommitRecord firstUnpushed = commitDao.findById(unpushedIds.get(0)).orElse(null);
        if (firstUnpushed == null || firstUnpushed.parentCommitId() == null) {
            return null;
        }
        return shaDao.findAnyShaForCommit(firstUnpushed.parentCommitId()).orElse(null);
    }

    /**
     * Fetches from the remote (updating tracking refs) then checks whether
     * {@code refs/remotes/<remoteName>/<branchName>} equals {@code expectedTipSha}.
     * Returns true only when both are non-null and equal — used to recover from a false
     * REJECTED_NONFASTFORWARD where the remote actually accepted our commits.
     */
    private boolean remoteTipMatchesAfterFetch(Git git, Repository repo, RemoteRecord remote,
                                               String branchName, String accessToken,
                                               String expectedTipSha) throws GitAPIException, IOException {
        git.fetch()
                .setRemote(remote.name())
                .setCredentialsProvider(GitRepoManager.credentials(accessToken))
                .call();
        ObjectId remoteTip = repo.resolve("refs/remotes/" + remote.name() + "/" + branchName);
        String remoteTipSha = remoteTip != null ? remoteTip.name() : null;
        logger.info("push nff recovery: expectedTipSha=" + expectedTipSha + " remoteTipSha=" + remoteTipSha);
        return expectedTipSha != null && expectedTipSha.equals(remoteTipSha);
    }

    /** Records commit→SHA rows after a successful push (normal or recovered). */
    private void recordShas(List<long[]> commitIds, List<String> shas,
                            RemoteRecord remote, CommitGitShaDao shaDao) throws SQLException {
        for (int i = 0; i < commitIds.size(); i++) {
            long cid = commitIds.get(i)[0];
            String sha = shas.get(i);
            int rows = shaDao.insert(cid, remote.id(), sha);
            if (rows == 0) {
                logger.warning("push sha record: row already existed for commitId=" + cid
                        + " remoteId=" + remote.id() + " sha=" + sha
                        + " — possible future duplicate push");
            }
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
