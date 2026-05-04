package com.gitcraft.merge;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory state for one player's in-progress merge. Lives in MergeManager.
 * Not persisted — server restart drops it (world keeps mid-merge state, like Git).
 */
public final class MergeSession {

    private final UUID playerId;
    private final long repoId;

    private final long targetBranchId;
    private final String targetBranchName;
    private final long sourceBranchId;
    private final String sourceBranchName;

    private final long targetHeadCommitId;
    private final long sourceHeadCommitId;
    private final Long baseCommitId;

    private final UUID worldUuid;
    private final String worldName;

    private final Map<BlockVector3, BlockState> autoApplied;
    private final Map<BlockVector3, Conflict>   conflicts;
    /** Pre-merge world snapshot for autoApplied + conflict positions; used by abort to roll back. */
    private final Map<BlockVector3, BlockState> preMergeWorld;
    /** Resolutions chosen via accept ours|theirs. Conflict pos -> chosen state (may be air). */
    private final Map<BlockVector3, BlockState> resolutions;

    private final long startedAt;
    private volatile long lastTouchedAt;

    public MergeSession(UUID playerId, long repoId,
                        long targetBranchId, String targetBranchName,
                        long sourceBranchId, String sourceBranchName,
                        long targetHeadCommitId, long sourceHeadCommitId,
                        Long baseCommitId,
                        UUID worldUuid, String worldName,
                        Map<BlockVector3, BlockState> autoApplied,
                        Map<BlockVector3, Conflict>   conflicts,
                        Map<BlockVector3, BlockState> preMergeWorld) {
        this.playerId = playerId;
        this.repoId = repoId;
        this.targetBranchId = targetBranchId;
        this.targetBranchName = targetBranchName;
        this.sourceBranchId = sourceBranchId;
        this.sourceBranchName = sourceBranchName;
        this.targetHeadCommitId = targetHeadCommitId;
        this.sourceHeadCommitId = sourceHeadCommitId;
        this.baseCommitId = baseCommitId;
        this.worldUuid = worldUuid;
        this.worldName = worldName;
        this.autoApplied = autoApplied;
        this.conflicts = conflicts;
        this.preMergeWorld = preMergeWorld;
        this.resolutions = new HashMap<>();
        this.startedAt = System.currentTimeMillis();
        this.lastTouchedAt = startedAt;
    }

    public void touch() { this.lastTouchedAt = System.currentTimeMillis(); }

    public UUID playerId() { return playerId; }
    public long repoId() { return repoId; }
    public long targetBranchId() { return targetBranchId; }
    public String targetBranchName() { return targetBranchName; }
    public long sourceBranchId() { return sourceBranchId; }
    public String sourceBranchName() { return sourceBranchName; }
    public long targetHeadCommitId() { return targetHeadCommitId; }
    public long sourceHeadCommitId() { return sourceHeadCommitId; }
    public Long baseCommitId() { return baseCommitId; }
    public UUID worldUuid() { return worldUuid; }
    public String worldName() { return worldName; }
    public Map<BlockVector3, BlockState> autoApplied() { return autoApplied; }
    public Map<BlockVector3, Conflict> conflicts() { return conflicts; }
    public Map<BlockVector3, BlockState> preMergeWorld() { return preMergeWorld; }
    public Map<BlockVector3, BlockState> resolutions() { return resolutions; }
    public long startedAt() { return startedAt; }
    public long lastTouchedAt() { return lastTouchedAt; }

    public boolean allConflictsResolved() {
        return resolutions.keySet().containsAll(conflicts.keySet());
    }
}
