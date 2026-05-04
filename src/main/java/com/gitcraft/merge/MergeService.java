package com.gitcraft.merge;

import com.gitcraft.GitCraft;
import com.gitcraft.commit.CommitService;
import com.gitcraft.config.GitCraftConfig;
import com.gitcraft.database.BranchDao;
import com.gitcraft.database.BranchRecord;
import com.gitcraft.database.CommitDao;
import com.gitcraft.database.CommitRecord;
import com.gitcraft.database.HeadDao;
import com.gitcraft.database.HeadRecord;
import com.gitcraft.diff.DiffResult;
import com.gitcraft.diff.GhostBlock;
import com.gitcraft.diff.GhostBlockManager;
import com.gitcraft.diff.GhostType;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.ClipboardLoader;
import com.gitcraft.util.Messages;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Orchestrates /gitcraft merge. Threading rules per CLAUDE.md:
 *   - Command parse + guard: main thread.
 *   - DB lookups + clipboard IO + three-way compute: async.
 *   - World mutation (auto-apply, accept, abort restore) + ghost spawn: main thread (EditSession).
 */
public final class MergeService {

    /** Hard cap on how many commits we'll walk when looking for a common ancestor. */
    private static final int ANCESTOR_WALK_CAP = 10_000;

    public enum Side { OURS, THEIRS }

    private final GitCraft plugin;
    private final SelectionManager selectionManager;
    private final CommitDao commitDao;
    private final BranchDao branchDao;
    private final HeadDao headDao;
    private final GhostBlockManager ghostBlockManager;
    private final MergeManager mergeManager;
    private final CommitService commitService;
    private final GitCraftConfig config;

    public MergeService(GitCraft plugin,
                        SelectionManager selectionManager,
                        CommitDao commitDao,
                        BranchDao branchDao,
                        HeadDao headDao,
                        GhostBlockManager ghostBlockManager,
                        MergeManager mergeManager,
                        CommitService commitService,
                        GitCraftConfig config) {
        this.plugin = plugin;
        this.selectionManager = selectionManager;
        this.commitDao = commitDao;
        this.branchDao = branchDao;
        this.headDao = headDao;
        this.ghostBlockManager = ghostBlockManager;
        this.mergeManager = mergeManager;
        this.commitService = commitService;
        this.config = config;
    }

    // ===== START =====

    public void startMerge(Player player, String sourceBranchName) {
        UUID playerId = player.getUniqueId();
        if (mergeManager.has(playerId)) {
            player.sendMessage(Messages.MERGE_ALREADY_IN_PROGRESS);
            return;
        }
        Selection sel = selectionManager.get(playerId).orElse(null);
        if (sel == null || sel.branchId() == null || sel.repoId() == null) {
            player.sendMessage(Messages.MERGE_NO_REPO);
            return;
        }
        long repoId = sel.repoId();
        long targetBranchId = sel.branchId();
        String targetBranchName = sel.branchName();
        if (sourceBranchName.equalsIgnoreCase(targetBranchName)) {
            player.sendMessage(Messages.MERGE_SAME_BRANCH);
            return;
        }
        player.sendMessage(Messages.MERGE_STARTED);
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> doStartAsync(playerId, repoId, targetBranchId, targetBranchName, sourceBranchName));
    }

    private void doStartAsync(UUID playerId, long repoId,
                              long targetBranchId, String targetBranchName,
                              String sourceBranchName) {
        try {
            Optional<BranchRecord> sourceOpt = branchDao.findByRepoAndName(repoId, sourceBranchName);
            if (sourceOpt.isEmpty()) {
                sendOnMain(playerId, String.format(Messages.MERGE_BRANCH_NOT_FOUND, sourceBranchName));
                return;
            }
            BranchRecord sourceBranch = sourceOpt.get();

            Long targetHeadId = resolveHeadCommitId(playerId, repoId, targetBranchId);
            if (targetHeadId == null) {
                sendOnMain(playerId, Messages.MERGE_TARGET_EMPTY);
                return;
            }
            Long sourceHeadId = commitDao.findLatestIdByBranch(sourceBranch.id()).orElse(null);
            if (sourceHeadId == null) {
                sendOnMain(playerId, Messages.MERGE_SOURCE_EMPTY);
                return;
            }

            // Stale-base detection: HEAD on target branch may have moved between selection load
            // and now. Warn but don't block.
            Long latestTargetTip = commitDao.findLatestIdByBranch(targetBranchId).orElse(null);
            boolean staleBase = latestTargetTip != null && !latestTargetTip.equals(targetHeadId);

            // Batch-load every commit's parent links for every branch in this repo so the
            // ancestor walk runs in memory.
            List<BranchRecord> repoBranches = branchDao.findByRepo(repoId);
            List<Long> branchIds = new ArrayList<>(repoBranches.size());
            for (BranchRecord b : repoBranches) branchIds.add(b.id());
            Map<Long, CommitDao.ParentLink> links = commitDao.findParentLinksByBranches(branchIds);

            Long baseId = findCommonAncestor(targetHeadId, sourceHeadId, links);
            if (baseId == null) {
                sendOnMain(playerId, Messages.MERGE_UNRELATED);
                return;
            }

            CommitRecord base   = commitDao.findById(baseId).orElse(null);
            CommitRecord target = commitDao.findById(targetHeadId).orElse(null);
            CommitRecord source = commitDao.findById(sourceHeadId).orElse(null);
            if (base == null || target == null || source == null) {
                sendOnMain(playerId, Messages.MERGE_DB_ERROR);
                return;
            }

            if (!base.worldUuid().equals(target.worldUuid())
                    || !base.worldUuid().equals(source.worldUuid())) {
                sendOnMain(playerId, String.format(Messages.MERGE_CROSS_WORLD,
                        target.worldName(), source.worldName()));
                return;
            }

            Clipboard baseClip   = ClipboardLoader.load(base.schemPath());
            Clipboard oursClip   = ClipboardLoader.load(target.schemPath());
            Clipboard theirsClip = ClipboardLoader.load(source.schemPath());

            int minX = min3(base.minX(), target.minX(), source.minX());
            int minY = min3(base.minY(), target.minY(), source.minY());
            int minZ = min3(base.minZ(), target.minZ(), source.minZ());
            int maxX = max3(base.maxX(), target.maxX(), source.maxX());
            int maxY = max3(base.maxY(), target.maxY(), source.maxY());
            int maxZ = max3(base.maxZ(), target.maxZ(), source.maxZ());

            ThreeWayDiff.Result result = ThreeWayDiff.compute(
                    baseClip, oursClip, theirsClip,
                    minX, minY, minZ, maxX, maxY, maxZ);

            if (result.isNoOp()) {
                sendOnMain(playerId, Messages.MERGE_ALREADY_UP_TO_DATE);
                return;
            }

            // Hand off to main thread for world mutation + ghost spawn + session persist.
            final UUID worldUuid = base.worldUuid();
            final String worldName = base.worldName();
            final long sourceBranchId = sourceBranch.id();
            final long targetHeadFinal = targetHeadId;
            final long sourceHeadFinal = sourceHeadId;
            final Long baseIdFinal = baseId;
            final boolean staleBaseFinal = staleBase;

            Bukkit.getScheduler().runTask(plugin, () -> finalizeStartOnMain(
                    playerId, repoId,
                    targetBranchId, targetBranchName,
                    sourceBranchId, sourceBranchName,
                    targetHeadFinal, sourceHeadFinal, baseIdFinal,
                    worldUuid, worldName,
                    minX, minY, minZ, maxX, maxY, maxZ,
                    result, staleBaseFinal));

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Merge DB error", e);
            sendOnMain(playerId, String.format(Messages.MERGE_DB_FAILED, safe(e.getMessage())));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Merge schematic load failed", e);
            sendOnMain(playerId, String.format(Messages.MERGE_IO_FAILED, safe(e.getMessage())));
        }
    }

    private void finalizeStartOnMain(UUID playerId, long repoId,
                                     long targetBranchId, String targetBranchName,
                                     long sourceBranchId, String sourceBranchName,
                                     long targetHeadId, long sourceHeadId, Long baseId,
                                     UUID worldUuid, String worldName,
                                     int minX, int minY, int minZ,
                                     int maxX, int maxY, int maxZ,
                                     ThreeWayDiff.Result result, boolean staleBase) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;

        World world = Bukkit.getWorld(worldUuid);
        if (world == null) {
            player.sendMessage(String.format(Messages.MERGE_WORLD_GONE, worldName));
            return;
        }

        // Snapshot pre-merge world for autoApplied + conflict positions (rollback on abort).
        Map<BlockVector3, BlockState> preMerge = new HashMap<>();
        for (BlockVector3 pos : result.autoApplied().keySet()) {
            preMerge.put(pos, captureWorldState(world, pos));
        }
        for (BlockVector3 pos : result.conflicts().keySet()) {
            preMerge.put(pos, captureWorldState(world, pos));
        }

        // Apply auto-changes via a single EditSession.
        try (EditSession edit = WorldEdit.getInstance().newEditSessionBuilder()
                .world(BukkitAdapter.adapt(world))
                .maxBlocks(-1)
                .build()) {
            for (Map.Entry<BlockVector3, BlockState> e : result.autoApplied().entrySet()) {
                edit.setBlock(e.getKey(), e.getValue());
            }
        } catch (WorldEditException e) {
            plugin.getLogger().log(Level.WARNING, "Merge auto-apply failed", e);
            player.sendMessage(Messages.MERGE_WE_ERROR);
            return;
        }

        MergeSession session = new MergeSession(
                playerId, repoId,
                targetBranchId, targetBranchName,
                sourceBranchId, sourceBranchName,
                targetHeadId, sourceHeadId, baseId,
                worldUuid, worldName,
                result.autoApplied(),
                result.conflicts(),
                preMerge);
        mergeManager.put(session);

        if (staleBase) {
            player.sendMessage(Messages.MERGE_STALE_BASE_WARN);
        }

        // No conflicts → fast-forward: auto-finalize the merge commit.
        if (result.conflicts().isEmpty()) {
            player.sendMessage(String.format(Messages.MERGE_FAST_FORWARD,
                    sourceBranchName, targetBranchName, result.autoApplied().size()));
            continueMerge(player, null);
            return;
        }

        // Spawn purple ghosts for conflicts.
        DiffResult ghostResult = buildConflictGhosts(result.conflicts(), world);
        ghostBlockManager.show(player, ghostResult, world);

        player.sendMessage(String.format(Messages.MERGE_RESOLVING,
                sourceBranchName, targetBranchName,
                result.autoApplied().size(), result.conflicts().size()));
    }

    // ===== ACCEPT ours|theirs =====

    public void accept(Player player, Side side) {
        UUID playerId = player.getUniqueId();
        MergeSession session = mergeManager.get(playerId).orElse(null);
        if (session == null) {
            player.sendMessage(Messages.MERGE_NONE);
            return;
        }
        session.touch();
        World world = Bukkit.getWorld(session.worldUuid());
        if (world == null) {
            player.sendMessage(String.format(Messages.MERGE_WORLD_GONE, session.worldName()));
            return;
        }

        try (EditSession edit = WorldEdit.getInstance().newEditSessionBuilder()
                .world(BukkitAdapter.adapt(world))
                .maxBlocks(-1)
                .build()) {
            for (Conflict c : session.conflicts().values()) {
                BlockState chosen = side == Side.OURS ? c.ours() : c.theirs();
                if (chosen == null) chosen = BlockTypes.AIR.getDefaultState();
                edit.setBlock(c.pos(), chosen);
                session.resolutions().put(c.pos(), chosen);
            }
        } catch (WorldEditException e) {
            plugin.getLogger().log(Level.WARNING, "Merge accept failed", e);
            player.sendMessage(Messages.MERGE_WE_ERROR);
            return;
        }

        ghostBlockManager.clear(player);
        player.sendMessage(String.format(Messages.MERGE_ACCEPTED,
                session.conflicts().size(), side.name().toLowerCase(Locale.ROOT)));
    }

    // ===== ABORT =====

    public void abort(Player player) {
        UUID playerId = player.getUniqueId();
        MergeSession session = mergeManager.get(playerId).orElse(null);
        if (session == null) {
            player.sendMessage(Messages.MERGE_NONE);
            return;
        }
        World world = Bukkit.getWorld(session.worldUuid());
        if (world == null) {
            // Drop session anyway; we can't restore.
            mergeManager.remove(playerId);
            ghostBlockManager.clear(player);
            player.sendMessage(String.format(Messages.MERGE_WORLD_GONE, session.worldName()));
            return;
        }

        try (EditSession edit = WorldEdit.getInstance().newEditSessionBuilder()
                .world(BukkitAdapter.adapt(world))
                .maxBlocks(-1)
                .build()) {
            for (Map.Entry<BlockVector3, BlockState> e : session.preMergeWorld().entrySet()) {
                edit.setBlock(e.getKey(), e.getValue());
            }
        } catch (WorldEditException e) {
            plugin.getLogger().log(Level.WARNING, "Merge abort rollback failed", e);
            player.sendMessage(Messages.MERGE_WE_ERROR);
            return;
        }

        ghostBlockManager.clear(player);
        mergeManager.remove(playerId);
        player.sendMessage(Messages.MERGE_ABORTED);
    }

    // ===== CONTINUE =====

    public void continueMerge(Player player, String overrideMessage) {
        UUID playerId = player.getUniqueId();
        MergeSession session = mergeManager.get(playerId).orElse(null);
        if (session == null) {
            player.sendMessage(Messages.MERGE_NONE);
            return;
        }
        if (!session.allConflictsResolved()) {
            int unresolved = session.conflicts().size() - session.resolutions().size();
            player.sendMessage(String.format(Messages.MERGE_UNRESOLVED, unresolved));
            return;
        }
        World world = Bukkit.getWorld(session.worldUuid());
        if (world == null) {
            player.sendMessage(String.format(Messages.MERGE_WORLD_GONE, session.worldName()));
            return;
        }

        // Compute commit region from the union bbox of pre-merge snapshot positions
        // (== union of base/target/source). Fall back to target bbox if empty (shouldn't happen).
        BBox bbox = unionBBox(session);

        BlockVector3 pos1 = BlockVector3.at(bbox.minX, bbox.minY, bbox.minZ);
        BlockVector3 pos2 = BlockVector3.at(bbox.maxX, bbox.maxY, bbox.maxZ);

        String message = overrideMessage != null && !overrideMessage.isBlank()
                ? overrideMessage
                : "Merge branch '" + session.sourceBranchName() + "' into " + session.targetBranchName();

        Path schemPath = config.schematicsDir()
                .resolve(String.valueOf(session.targetBranchId()))
                .resolve(UUID.randomUUID() + ".schem");

        // Hand off to CommitService, which writes schem + inserts row + updates HEAD.
        commitService.commitAsync(
                playerId, player.getName(),
                session.targetBranchId(), session.repoId(),
                message, world, pos1, pos2, schemPath,
                session.targetHeadCommitId(),
                session.sourceHeadCommitId());

        // Drop session immediately — the commit is in flight, but world state reflects merge result
        // and another merge cannot start meaningfully without first checking the commit landed.
        // CommitService logs DB failures; the player will get a separate failure message and can retry.
        ghostBlockManager.clear(player);
        mergeManager.remove(playerId);
        player.sendMessage(Messages.MERGE_FINALIZING);
    }

    // ===== STATUS =====

    public void status(Player player) {
        MergeSession s = mergeManager.get(player.getUniqueId()).orElse(null);
        if (s == null) {
            player.sendMessage(Messages.MERGE_NONE);
            return;
        }
        int unresolved = s.conflicts().size() - s.resolutions().size();
        player.sendMessage(String.format(Messages.MERGE_STATUS,
                s.sourceBranchName(), s.targetBranchName(),
                s.autoApplied().size(), s.conflicts().size(), unresolved));
    }

    // ===== Helpers =====

    private Long resolveHeadCommitId(UUID playerId, long repoId, long branchId) throws SQLException {
        Optional<HeadRecord> headOpt = headDao.findByPlayerAndRepo(playerId, repoId);
        Long commitId = headOpt.map(HeadRecord::commitId).orElse(null);
        if (commitId == null) {
            commitId = commitDao.findLatestIdByBranch(branchId).orElse(null);
        }
        return commitId;
    }

    /** BFS both DAGs (parent + merge_parent), return first commit id reachable from both. */
    private Long findCommonAncestor(long target, long source, Map<Long, CommitDao.ParentLink> links) {
        Set<Long> targetAncestors = collectAncestors(target, links);
        if (targetAncestors.contains(source)) return source;

        Deque<Long> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(source);
        visited.add(source);
        int walked = 0;

        while (!queue.isEmpty() && walked++ < ANCESTOR_WALK_CAP) {
            long cur = queue.poll();
            if (targetAncestors.contains(cur)) return cur;
            CommitDao.ParentLink link = links.get(cur);
            if (link == null) continue;
            if (link.parentCommitId() != null && visited.add(link.parentCommitId())) {
                queue.add(link.parentCommitId());
            }
            if (link.mergeParentCommitId() != null && visited.add(link.mergeParentCommitId())) {
                queue.add(link.mergeParentCommitId());
            }
        }
        return null;
    }

    private Set<Long> collectAncestors(long start, Map<Long, CommitDao.ParentLink> links) {
        Set<Long> out = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(start);
        out.add(start);
        int walked = 0;
        while (!queue.isEmpty() && walked++ < ANCESTOR_WALK_CAP) {
            long cur = queue.poll();
            CommitDao.ParentLink link = links.get(cur);
            if (link == null) continue;
            if (link.parentCommitId() != null && out.add(link.parentCommitId())) {
                queue.add(link.parentCommitId());
            }
            if (link.mergeParentCommitId() != null && out.add(link.mergeParentCommitId())) {
                queue.add(link.mergeParentCommitId());
            }
        }
        return out;
    }

    private DiffResult buildConflictGhosts(Map<BlockVector3, Conflict> conflicts, World world) {
        List<GhostBlock> ghosts = new ArrayList<>(conflicts.size());
        for (Conflict c : conflicts.values()) {
            BlockState display = c.theirs();
            if (display == null || isAir(display)) display = c.ours();
            if (display == null || isAir(display)) display = BlockTypes.STONE.getDefaultState();
            BlockData data = BukkitAdapter.adapt(display);
            ghosts.add(new GhostBlock(c.pos(), GhostType.CONFLICT, null, data));
        }
        return new DiffResult(ghosts);
    }

    private static boolean isAir(BlockState state) {
        BlockType type = state.getBlockType();
        return type == BlockTypes.AIR || type == BlockTypes.CAVE_AIR || type == BlockTypes.VOID_AIR;
    }

    private static BlockState captureWorldState(World world, BlockVector3 pos) {
        BlockData data = world.getBlockAt(pos.x(), pos.y(), pos.z()).getBlockData();
        return BukkitAdapter.adapt(data);
    }

    private record BBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}

    private BBox unionBBox(MergeSession s) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockVector3 p : s.preMergeWorld().keySet()) {
            if (p.x() < minX) minX = p.x();
            if (p.y() < minY) minY = p.y();
            if (p.z() < minZ) minZ = p.z();
            if (p.x() > maxX) maxX = p.x();
            if (p.y() > maxY) maxY = p.y();
            if (p.z() > maxZ) maxZ = p.z();
        }
        if (minX == Integer.MAX_VALUE) {
            // No-op merge shouldn't reach continue, but be defensive.
            minX = minY = minZ = 0;
            maxX = maxY = maxZ = 0;
        }
        return new BBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static int min3(int a, int b, int c) { return Math.min(a, Math.min(b, c)); }
    private static int max3(int a, int b, int c) { return Math.max(a, Math.max(b, c)); }

    private void sendOnMain(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }

    private static String safe(String s) { return s == null ? "(no detail)" : s; }
}
