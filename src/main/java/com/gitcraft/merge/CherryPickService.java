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
import com.gitcraft.database.RepoDao;
import com.gitcraft.database.RepoRecord;
import com.gitcraft.diff.DiffResult;
import com.gitcraft.diff.GhostBlockManager;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.ClipboardLoader;
import com.gitcraft.util.Messages;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Orchestrates /gitcraft cherry-pick. Mirrors {@link MergeService} but applies the
 * patch introduced by a single commit onto the active branch HEAD.
 *
 * Three-way mapping:
 *   base   = parent(picked) — empty clipboard if picked is a root commit
 *   ours   = active branch HEAD's schematic
 *   theirs = picked commit's schematic
 *
 * Threading rules per CLAUDE.md (identical to MergeService):
 *   - Command parse + guard: main thread.
 *   - DB lookups + clipboard IO + three-way compute: async.
 *   - World mutation + ghost spawn: main thread (EditSession).
 */
public final class CherryPickService {

    public enum Side { OURS, THEIRS }

    private final GitCraft plugin;
    private final SelectionManager selectionManager;
    private final CommitDao commitDao;
    private final BranchDao branchDao;
    private final HeadDao headDao;
    private final RepoDao repoDao;
    private final GhostBlockManager ghostBlockManager;
    private final OpManager opManager;
    private final CommitService commitService;
    private final GitCraftConfig config;

    public CherryPickService(GitCraft plugin,
                             SelectionManager selectionManager,
                             CommitDao commitDao,
                             BranchDao branchDao,
                             HeadDao headDao,
                             RepoDao repoDao,
                             GhostBlockManager ghostBlockManager,
                             OpManager opManager,
                             CommitService commitService,
                             GitCraftConfig config) {
        this.plugin = plugin;
        this.selectionManager = selectionManager;
        this.commitDao = commitDao;
        this.branchDao = branchDao;
        this.headDao = headDao;
        this.repoDao = repoDao;
        this.ghostBlockManager = ghostBlockManager;
        this.opManager = opManager;
        this.commitService = commitService;
        this.config = config;
    }

    // ===== START =====

    public void startCherryPick(Player player, long sourceCommitId) {
        UUID playerId = player.getUniqueId();
        if (opManager.has(playerId)) {
            player.sendMessage(Messages.CHERRYPICK_ALREADY_IN_PROGRESS);
            return;
        }
        Selection sel = selectionManager.get(playerId).orElse(null);
        if (sel == null || sel.branchId() == null || sel.repoId() == null) {
            player.sendMessage(Messages.CHERRYPICK_NO_REPO);
            return;
        }
        long repoId = sel.repoId();
        long targetBranchId = sel.branchId();
        String targetBranchName = sel.branchName();
        player.sendMessage(Messages.CHERRYPICK_STARTED);
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> doStartAsync(playerId, repoId, targetBranchId, targetBranchName, sourceCommitId));
    }

    private void doStartAsync(UUID playerId, long repoId,
                              long targetBranchId, String targetBranchName,
                              long sourceCommitId) {
        try {
            CommitRecord cherry = commitDao.findById(sourceCommitId).orElse(null);
            if (cherry == null) {
                MergeOps.sendOnMain(plugin, playerId,
                        String.format(Messages.CHERRYPICK_COMMIT_NOT_FOUND, sourceCommitId));
                return;
            }

            // Cross-repo refusal: cherry must belong to the same repo as the active branch.
            BranchRecord cherryBranch = branchDao.findById(cherry.branchId()).orElse(null);
            if (cherryBranch == null || cherryBranch.repoId() != repoId) {
                MergeOps.sendOnMain(plugin, playerId,
                        String.format(Messages.CHERRYPICK_COMMIT_NOT_IN_REPO, sourceCommitId));
                return;
            }

            // Resolve the active branch HEAD — needed as both 'ours' and parent of new commit.
            Long targetHeadId = resolveHeadCommitId(playerId, repoId, targetBranchId);
            if (targetHeadId == null) {
                MergeOps.sendOnMain(plugin, playerId, Messages.CHERRYPICK_HEAD_EMPTY);
                return;
            }

            if (targetHeadId == sourceCommitId) {
                MergeOps.sendOnMain(plugin, playerId, Messages.CHERRYPICK_SELF);
                return;
            }

            // Stale-base detection.
            Long latestTargetTip = commitDao.findLatestIdByBranch(targetBranchId).orElse(null);
            boolean staleBase = latestTargetTip != null && !latestTargetTip.equals(targetHeadId);

            // Already-applied short-circuit: if cherry is an ancestor of HEAD (via parent +
            // merge_parent walk), the patch is already in this branch's history. Note that
            // cherry_pick_source_id is intentionally ignored here — picks are independent
            // commits, so the same commit can be picked again onto a divergent branch.
            List<BranchRecord> repoBranches = branchDao.findByRepo(repoId);
            List<Long> branchIds = new ArrayList<>(repoBranches.size());
            for (BranchRecord b : repoBranches) branchIds.add(b.id());
            Map<Long, CommitDao.ParentLink> links = commitDao.findParentLinksByBranches(branchIds);
            Set<Long> headAncestors = MergeService.collectAncestors(targetHeadId, links);
            if (headAncestors.contains(sourceCommitId)) {
                MergeOps.sendOnMain(plugin, playerId,
                        String.format(Messages.CHERRYPICK_ALREADY_APPLIED, sourceCommitId));
                return;
            }

            // Resolve base = parent(cherry). Null is allowed — root commit, treat as empty clip.
            CommitRecord base = null;
            if (cherry.parentCommitId() != null) {
                base = commitDao.findById(cherry.parentCommitId()).orElse(null);
                if (base == null) {
                    MergeOps.sendOnMain(plugin, playerId, Messages.CHERRYPICK_DB_ERROR);
                    return;
                }
            }
            CommitRecord ours = commitDao.findById(targetHeadId).orElse(null);
            if (ours == null) {
                MergeOps.sendOnMain(plugin, playerId, Messages.CHERRYPICK_DB_ERROR);
                return;
            }

            // Cross-world refusal.
            UUID worldUuid = ours.worldUuid();
            String worldName = ours.worldName();
            if (!worldUuid.equals(cherry.worldUuid())
                    || (base != null && !worldUuid.equals(base.worldUuid()))) {
                MergeOps.sendOnMain(plugin, playerId, String.format(Messages.CHERRYPICK_CROSS_WORLD,
                        ours.worldName(), cherry.worldName()));
                return;
            }

            Clipboard oursClip   = ClipboardLoader.load(ours.schemPath());
            Clipboard theirsClip = ClipboardLoader.load(cherry.schemPath());
            // Empty clipboard for root-commit base — every position outside its (degenerate)
            // region resolves to AIR via ThreeWayDiff.blockAt, which is exactly the semantics
            // we want for "nothing existed before".
            Clipboard baseClip = base != null
                    ? ClipboardLoader.load(base.schemPath())
                    : emptyClipboard();

            int minX, minY, minZ, maxX, maxY, maxZ;
            if (base != null) {
                minX = MergeOps.min3(base.minX(), ours.minX(), cherry.minX());
                minY = MergeOps.min3(base.minY(), ours.minY(), cherry.minY());
                minZ = MergeOps.min3(base.minZ(), ours.minZ(), cherry.minZ());
                maxX = MergeOps.max3(base.maxX(), ours.maxX(), cherry.maxX());
                maxY = MergeOps.max3(base.maxY(), ours.maxY(), cherry.maxY());
                maxZ = MergeOps.max3(base.maxZ(), ours.maxZ(), cherry.maxZ());
            } else {
                minX = Math.min(ours.minX(), cherry.minX());
                minY = Math.min(ours.minY(), cherry.minY());
                minZ = Math.min(ours.minZ(), cherry.minZ());
                maxX = Math.max(ours.maxX(), cherry.maxX());
                maxY = Math.max(ours.maxY(), cherry.maxY());
                maxZ = Math.max(ours.maxZ(), cherry.maxZ());
            }

            ThreeWayDiff.Result result = ThreeWayDiff.compute(
                    baseClip, oursClip, theirsClip,
                    minX, minY, minZ, maxX, maxY, maxZ);

            if (result.isNoOp()) {
                MergeOps.sendOnMain(plugin, playerId, Messages.CHERRYPICK_NO_OP);
                return;
            }

            RepoRecord repo = repoDao.findById(repoId).orElse(null);
            int ox = repo != null ? repo.effectiveOffsetX() : 0;
            int oy = repo != null ? repo.effectiveOffsetY() : 0;
            int oz = repo != null ? repo.effectiveOffsetZ() : 0;

            final Long baseIdFinal = base != null ? base.id() : null;
            final long targetHeadFinal = targetHeadId;
            final boolean staleBaseFinal = staleBase;
            final String cherryMessage = cherry.message();
            final int oxFinal = ox, oyFinal = oy, ozFinal = oz;

            Bukkit.getScheduler().runTask(plugin, () -> finalizeStartOnMain(
                    playerId, repoId,
                    targetBranchId, targetBranchName,
                    sourceCommitId, baseIdFinal, targetHeadFinal,
                    cherryMessage, worldUuid, worldName,
                    oxFinal, oyFinal, ozFinal,
                    result, staleBaseFinal));

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Cherry-pick DB error", e);
            MergeOps.sendOnMain(plugin, playerId,
                    String.format(Messages.CHERRYPICK_DB_FAILED, MergeOps.safe(e.getMessage())));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Cherry-pick schematic load failed", e);
            MergeOps.sendOnMain(plugin, playerId,
                    String.format(Messages.CHERRYPICK_IO_FAILED, MergeOps.safe(e.getMessage())));
        }
    }

    private void finalizeStartOnMain(UUID playerId, long repoId,
                                     long targetBranchId, String targetBranchName,
                                     long sourceCommitId, Long baseCommitId, long targetHeadId,
                                     String sourceMessage,
                                     UUID worldUuid, String worldName,
                                     int ox, int oy, int oz,
                                     ThreeWayDiff.Result result, boolean staleBase) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;

        World world = Bukkit.getWorld(worldUuid);
        if (world == null) {
            player.sendMessage(String.format(Messages.CHERRYPICK_WORLD_GONE, worldName));
            return;
        }

        // Map keys are repo-space; world I/O adds the offset.
        Map<BlockVector3, BlockState> preMerge = new HashMap<>();
        for (BlockVector3 pos : result.autoApplied().keySet()) {
            preMerge.put(pos, MergeOps.captureWorldState(world, pos.add(ox, oy, oz)));
        }
        for (BlockVector3 pos : result.conflicts().keySet()) {
            preMerge.put(pos, MergeOps.captureWorldState(world, pos.add(ox, oy, oz)));
        }

        try (EditSession edit = WorldEdit.getInstance().newEditSessionBuilder()
                .world(BukkitAdapter.adapt(world))
                .maxBlocks(-1)
                .build()) {
            for (Map.Entry<BlockVector3, BlockState> e : result.autoApplied().entrySet()) {
                edit.setBlock(e.getKey().add(ox, oy, oz), e.getValue());
            }
        } catch (WorldEditException e) {
            plugin.getLogger().log(Level.WARNING, "Cherry-pick auto-apply failed", e);
            player.sendMessage(Messages.CHERRYPICK_WE_ERROR);
            return;
        }

        CherryPickSession session = new CherryPickSession(
                playerId, repoId,
                targetBranchId, targetBranchName,
                sourceCommitId, baseCommitId, targetHeadId,
                sourceMessage,
                worldUuid, worldName,
                ox, oy, oz,
                result.autoApplied(),
                result.conflicts(),
                preMerge);
        opManager.put(session);

        if (staleBase) {
            player.sendMessage(Messages.CHERRYPICK_STALE_BASE_WARN);
        }

        if (result.conflicts().isEmpty()) {
            player.sendMessage(String.format(Messages.CHERRYPICK_FAST_APPLY,
                    sourceCommitId, targetBranchName, result.autoApplied().size()));
            continueCherryPick(player, null);
            return;
        }

        DiffResult ghostResult = MergeOps.buildConflictGhosts(result.conflicts());
        ghostBlockManager.show(player, ghostResult, world, ox, oy, oz);

        player.sendMessage(String.format(Messages.CHERRYPICK_RESOLVING,
                sourceCommitId, targetBranchName,
                result.autoApplied().size(), result.conflicts().size()));
    }

    // ===== ACCEPT =====

    public void accept(Player player, Side side) {
        UUID playerId = player.getUniqueId();
        CherryPickSession session = opManager.getCherryPick(playerId).orElse(null);
        if (session == null) {
            player.sendMessage(Messages.CHERRYPICK_NONE);
            return;
        }
        session.touch();
        World world = Bukkit.getWorld(session.worldUuid());
        if (world == null) {
            player.sendMessage(String.format(Messages.CHERRYPICK_WORLD_GONE, session.worldName()));
            return;
        }

        int ox = session.ox(), oy = session.oy(), oz = session.oz();
        try (EditSession edit = WorldEdit.getInstance().newEditSessionBuilder()
                .world(BukkitAdapter.adapt(world))
                .maxBlocks(-1)
                .build()) {
            for (Conflict c : session.conflicts().values()) {
                BlockState chosen = side == Side.OURS ? c.ours() : c.theirs();
                if (chosen == null) chosen = BlockTypes.AIR.getDefaultState();
                edit.setBlock(c.pos().add(ox, oy, oz), chosen);
                session.resolutions().put(c.pos(), chosen);
            }
        } catch (WorldEditException e) {
            plugin.getLogger().log(Level.WARNING, "Cherry-pick accept failed", e);
            player.sendMessage(Messages.CHERRYPICK_WE_ERROR);
            return;
        }

        ghostBlockManager.clear(player);
        player.sendMessage(String.format(Messages.CHERRYPICK_ACCEPTED,
                session.conflicts().size(), side.name().toLowerCase(Locale.ROOT)));
    }

    // ===== ABORT =====

    public void abort(Player player) {
        UUID playerId = player.getUniqueId();
        CherryPickSession session = opManager.getCherryPick(playerId).orElse(null);
        if (session == null) {
            player.sendMessage(Messages.CHERRYPICK_NONE);
            return;
        }
        World world = Bukkit.getWorld(session.worldUuid());
        if (world == null) {
            opManager.remove(playerId);
            ghostBlockManager.clear(player);
            player.sendMessage(String.format(Messages.CHERRYPICK_WORLD_GONE, session.worldName()));
            return;
        }

        int ox = session.ox(), oy = session.oy(), oz = session.oz();
        try (EditSession edit = WorldEdit.getInstance().newEditSessionBuilder()
                .world(BukkitAdapter.adapt(world))
                .maxBlocks(-1)
                .build()) {
            for (Map.Entry<BlockVector3, BlockState> e : session.preMergeWorld().entrySet()) {
                edit.setBlock(e.getKey().add(ox, oy, oz), e.getValue());
            }
        } catch (WorldEditException e) {
            plugin.getLogger().log(Level.WARNING, "Cherry-pick abort rollback failed", e);
            player.sendMessage(Messages.CHERRYPICK_WE_ERROR);
            return;
        }

        ghostBlockManager.clear(player);
        opManager.remove(playerId);
        player.sendMessage(Messages.CHERRYPICK_ABORTED);
    }

    // ===== CONTINUE =====

    public void continueCherryPick(Player player, String overrideMessage) {
        UUID playerId = player.getUniqueId();
        CherryPickSession session = opManager.getCherryPick(playerId).orElse(null);
        if (session == null) {
            player.sendMessage(Messages.CHERRYPICK_NONE);
            return;
        }
        // TODO: same gap as merge — manual block placement does not satisfy
        // allConflictsResolved(); only `accept ours|theirs` populates resolutions.
        if (!session.allConflictsResolved()) {
            int unresolved = session.conflicts().size() - session.resolutions().size();
            player.sendMessage(String.format(Messages.CHERRYPICK_UNRESOLVED, unresolved));
            return;
        }
        World world = Bukkit.getWorld(session.worldUuid());
        if (world == null) {
            player.sendMessage(String.format(Messages.CHERRYPICK_WORLD_GONE, session.worldName()));
            return;
        }

        MergeOps.BBox bbox = MergeOps.unionBBox(session.preMergeWorld().keySet());
        int ox = session.ox(), oy = session.oy(), oz = session.oz();
        BlockVector3 pos1 = BlockVector3.at(bbox.minX() + ox, bbox.minY() + oy, bbox.minZ() + oz);
        BlockVector3 pos2 = BlockVector3.at(bbox.maxX() + ox, bbox.maxY() + oy, bbox.maxZ() + oz);

        String message = overrideMessage != null && !overrideMessage.isBlank()
                ? overrideMessage
                : composeDefaultMessage(session.sourceCommitId(), session.sourceMessage());

        Path schemPath = config.schematicsDir()
                .resolve(String.valueOf(session.targetBranchId()))
                .resolve(UUID.randomUUID() + ".schem");

        commitService.commitAsync(
                playerId, player.getName(),
                session.targetBranchId(), session.repoId(),
                message, world, pos1, pos2, schemPath,
                session.targetHeadCommitId(),
                null,
                session.sourceCommitId());

        ghostBlockManager.clear(player);
        opManager.remove(playerId);
        player.sendMessage(Messages.CHERRYPICK_FINALIZING);
    }

    // ===== STATUS =====

    public void status(Player player) {
        CherryPickSession s = opManager.getCherryPick(player.getUniqueId()).orElse(null);
        if (s == null) {
            player.sendMessage(Messages.CHERRYPICK_NONE);
            return;
        }
        int unresolved = s.conflicts().size() - s.resolutions().size();
        player.sendMessage(String.format(Messages.CHERRYPICK_STATUS,
                s.sourceCommitId(), s.targetBranchName(),
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

    /** Mirrors Git: "Cherry-pick <id>: <msg> (cherry picked from commit <id>)", capped at 500. */
    private static String composeDefaultMessage(long sourceId, String sourceMessage) {
        String body = sourceMessage == null ? "" : sourceMessage;
        String full = "Cherry-pick " + sourceId + ": " + body
                + " (cherry picked from commit " + sourceId + ")";
        if (full.length() <= 500) return full;
        // Truncate the source-message body to fit 500-char ceiling.
        String prefix = "Cherry-pick " + sourceId + ": ";
        String suffix = " (cherry picked from commit " + sourceId + ")";
        int room = 500 - prefix.length() - suffix.length();
        if (room < 0) {
            // Pathologically long ids — fall back to a minimal message.
            return ("Cherry-pick " + sourceId).substring(0, Math.min(500, ("Cherry-pick " + sourceId).length()));
        }
        String trimmed = body.length() > room ? body.substring(0, room) : body;
        return prefix + trimmed + suffix;
    }

    /**
     * Empty stand-in clipboard for root-commit cherry-pick (no parent). Region is degenerate
     * so {@link ThreeWayDiff} sees AIR for every queried position, which models "nothing
     * existed before this commit".
     */
    private static Clipboard emptyClipboard() {
        BlockVector3 origin = BlockVector3.ZERO;
        return new BlockArrayClipboard(new CuboidRegion(origin, origin));
    }
}
