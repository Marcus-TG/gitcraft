package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.database.BranchDao;
import com.gitcraft.database.BranchRecord;
import com.gitcraft.database.CommitDao;
import com.gitcraft.database.CommitRecord;
import com.gitcraft.database.RepoDao;
import com.gitcraft.database.RepoRecord;
import com.gitcraft.diff.DiffResult;
import com.gitcraft.diff.DiffService;
import com.gitcraft.diff.GhostBlockManager;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class DiffSubcommand implements Subcommand {

    private static final String PERMISSION          = "gitcraft.diff";
    private static final long   CONFIRM_TTL_MS      = 30_000L;
    private static final int    LARGE_DIFF_THRESHOLD = 500;

    private final GitCraft plugin;
    private final SelectionManager manager;
    private final CommitDao commitDao;
    private final BranchDao branchDao;
    private final RepoDao repoDao;
    private final DiffService diffService;
    private final GhostBlockManager ghostBlockManager;

    // TODO: Pending entries are not evicted on disconnect. 30s TTL is sufficient for now.
    private final ConcurrentHashMap<UUID, PendingDiff> pendingDiffs = new ConcurrentHashMap<>();

    private record PendingDiff(long commitAId, long commitBId, DiffResult result, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    public DiffSubcommand(GitCraft plugin, SelectionManager manager,
                          CommitDao commitDao, BranchDao branchDao, RepoDao repoDao,
                          DiffService diffService, GhostBlockManager ghostBlockManager) {
        this.plugin            = plugin;
        this.manager           = manager;
        this.commitDao         = commitDao;
        this.branchDao         = branchDao;
        this.repoDao           = repoDao;
        this.diffService       = diffService;
        this.ghostBlockManager = ghostBlockManager;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Messages.NO_PERMISSION);
            return;
        }

        // /gitcraft diff clear
        if (args.length == 1 && "clear".equalsIgnoreCase(args[0])) {
            ghostBlockManager.clear(player);
            player.sendMessage(Messages.DIFF_CLEARED);
            return;
        }

        // /gitcraft diff <id1> <id2>
        if (args.length == 2) {
            long id1, id2;
            try {
                id1 = Long.parseLong(args[0]);
                id2 = Long.parseLong(args[1]);
            } catch (NumberFormatException e) {
                player.sendMessage(Messages.DIFF_INVALID_ID);
                return;
            }
            if (id1 <= 0 || id2 <= 0) {
                player.sendMessage(Messages.DIFF_INVALID_ID);
                return;
            }
            if (id1 == id2) {
                player.sendMessage(Messages.DIFF_SAME_COMMIT);
                return;
            }
            UUID playerId = player.getUniqueId();
            Bukkit.getScheduler().runTaskAsynchronously(plugin,
                    () -> runExplicitDiff(playerId, id1, id2));
            return;
        }

        if (args.length > 2) {
            player.sendMessage(Messages.DIFF_USAGE);
            return;
        }

        // /gitcraft diff and /gitcraft diff <id> both require an active repo
        Selection sel = manager.get(player.getUniqueId()).orElse(null);
        if (sel == null || sel.branchId() == null) {
            player.sendMessage(Messages.DIFF_NO_REPO);
            return;
        }
        long branchId = sel.branchId();
        long repoId   = sel.repoId();

        UUID playerId = player.getUniqueId();

        // /gitcraft diff <id>
        if (args.length == 1) {
            long targetId;
            try {
                targetId = Long.parseLong(args[0]);
            } catch (NumberFormatException e) {
                player.sendMessage(Messages.DIFF_INVALID_ID);
                return;
            }
            if (targetId <= 0) {
                player.sendMessage(Messages.DIFF_INVALID_ID);
                return;
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin,
                    () -> runHeadVsIdDiff(playerId, branchId, repoId, targetId));
            return;
        }

        // /gitcraft diff (no args)
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> runLastTwoDiff(playerId, branchId));
    }

    // --- Async resolution ---

    private void runLastTwoDiff(UUID playerId, long branchId) {
        List<CommitRecord> commits;
        try {
            commits = commitDao.findByBranch(branchId, 2, 0);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Diff DB lookup failed", e);
            sendOnMain(playerId, String.format(Messages.DIFF_DB_FAILED, safe(e.getMessage())));
            return;
        }
        if (commits.size() < 2) {
            sendOnMain(playerId, Messages.DIFF_NEED_TWO_COMMITS);
            return;
        }
        // findByBranch returns DESC order: index 0 is newer (after), index 1 is older (before)
        dispatchDiff(playerId, commits.get(1), commits.get(0));
    }

    private void runHeadVsIdDiff(UUID playerId, long branchId, long activeRepoId, long targetId) {
        CommitRecord head;
        CommitRecord target;
        try {
            Optional<Long> headIdOpt = commitDao.findLatestIdByBranch(branchId);
            if (headIdOpt.isEmpty()) {
                sendOnMain(playerId, Messages.DIFF_NO_HEAD);
                return;
            }
            Optional<CommitRecord> headOpt = commitDao.findById(headIdOpt.get());
            if (headOpt.isEmpty()) {
                sendOnMain(playerId, Messages.DIFF_NO_HEAD);
                return;
            }
            head = headOpt.get();

            Optional<CommitRecord> targetOpt = commitDao.findById(targetId);
            if (targetOpt.isEmpty()) {
                sendOnMain(playerId, String.format(Messages.DIFF_NOT_FOUND, targetId));
                return;
            }
            target = targetOpt.get();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Diff DB lookup failed", e);
            sendOnMain(playerId, String.format(Messages.DIFF_DB_FAILED, safe(e.getMessage())));
            return;
        }

        if (head.id().equals(target.id())) {
            sendOnMain(playerId, Messages.DIFF_SAME_COMMIT);
            return;
        }

        if (!sameRepo(target.branchId(), activeRepoId)) {
            sendOnMain(playerId, Messages.DIFF_CROSS_REPO);
            return;
        }

        // target = before, head = after (HEAD is the current/newer state)
        dispatchDiff(playerId, target, head);
    }

    private void runExplicitDiff(UUID playerId, long id1, long id2) {
        CommitRecord commitA;
        CommitRecord commitB;
        try {
            Optional<CommitRecord> aOpt = commitDao.findById(id1);
            if (aOpt.isEmpty()) {
                sendOnMain(playerId, String.format(Messages.DIFF_NOT_FOUND, id1));
                return;
            }
            commitA = aOpt.get();

            Optional<CommitRecord> bOpt = commitDao.findById(id2);
            if (bOpt.isEmpty()) {
                sendOnMain(playerId, String.format(Messages.DIFF_NOT_FOUND, id2));
                return;
            }
            commitB = bOpt.get();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Diff DB lookup failed", e);
            sendOnMain(playerId, String.format(Messages.DIFF_DB_FAILED, safe(e.getMessage())));
            return;
        }

        long repoIdA;
        long repoIdB;
        try {
            Optional<BranchRecord> branchA = branchDao.findById(commitA.branchId());
            Optional<BranchRecord> branchB = branchDao.findById(commitB.branchId());
            if (branchA.isEmpty() || branchB.isEmpty()) {
                sendOnMain(playerId, String.format(Messages.DIFF_DB_FAILED, "branch not found"));
                return;
            }
            repoIdA = branchA.get().repoId();
            repoIdB = branchB.get().repoId();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Diff branch lookup failed", e);
            sendOnMain(playerId, String.format(Messages.DIFF_DB_FAILED, safe(e.getMessage())));
            return;
        }

        if (repoIdA != repoIdB) {
            sendOnMain(playerId, Messages.DIFF_CROSS_REPO);
            return;
        }

        try {
            Optional<RepoRecord> repoOpt = repoDao.findById(repoIdA);
            if (repoOpt.isEmpty() || !repoOpt.get().ownerUuid().equals(playerId)) {
                sendOnMain(playerId, Messages.DIFF_NOT_OWNER);
                return;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Diff repo ownership lookup failed", e);
            sendOnMain(playerId, String.format(Messages.DIFF_DB_FAILED, safe(e.getMessage())));
            return;
        }

        dispatchDiff(playerId, commitA, commitB);
    }

    // --- Diff dispatch (still async) ---

    private void dispatchDiff(UUID playerId, CommitRecord commitA, CommitRecord commitB) {
        if (!commitA.worldUuid().equals(commitB.worldUuid())) {
            sendOnMain(playerId, String.format(Messages.DIFF_CROSS_WORLD,
                    commitA.worldName(), commitB.worldName()));
            return;
        }

        PendingDiff pending = pendingDiffs.get(playerId);
        if (pending != null && !pending.isExpired()
                && pending.commitAId() == commitA.id()
                && pending.commitBId() == commitB.id()) {
            pendingDiffs.remove(playerId);
            spawnDiff(playerId, pending.result(), commitA.worldUuid(), commitA.worldName());
            return;
        }

        DiffResult result;
        try {
            result = diffService.compute(commitA, commitB);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Diff schematic load failed", e);
            sendOnMain(playerId, String.format(Messages.DIFF_IO_FAILED, safe(e.getMessage())));
            return;
        }

        if (result.totalCount() == 0) {
            sendOnMain(playerId, Messages.DIFF_NO_CHANGES);
            return;
        }

        if (result.totalCount() > LARGE_DIFF_THRESHOLD) {
            pendingDiffs.put(playerId, new PendingDiff(
                    commitA.id(), commitB.id(), result,
                    System.currentTimeMillis() + CONFIRM_TTL_MS));
            sendOnMain(playerId, String.format(Messages.DIFF_LARGE_WARN, result.totalCount()));
            return;
        }

        spawnDiff(playerId, result, commitA.worldUuid(), commitA.worldName());
    }

    private void spawnDiff(UUID playerId, DiffResult result, UUID worldUuid, String worldName) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p == null || !p.isOnline()) return;

            World world = Bukkit.getWorld(worldUuid);
            if (world == null) {
                p.sendMessage(String.format(Messages.DIFF_WORLD_GONE, worldName));
                return;
            }

            ghostBlockManager.show(p, result, world);
            p.sendMessage(String.format(Messages.DIFF_SUCCESS, result.totalCount()));
        });
    }

    // --- Helpers ---

    private boolean sameRepo(long branchId, long activeRepoId) {
        try {
            Optional<BranchRecord> branch = branchDao.findById(branchId);
            return branch.isPresent() && branch.get().repoId() == activeRepoId;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Branch lookup failed during repo check", e);
            return false;
        }
    }

    private void sendOnMain(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> sendNow(playerId, message));
    }

    private void sendNow(UUID playerId, String message) {
        Player p = Bukkit.getPlayer(playerId);
        if (p != null && p.isOnline()) p.sendMessage(message);
    }

    private static String safe(String s) {
        return s == null ? "(no detail)" : s;
    }
}
