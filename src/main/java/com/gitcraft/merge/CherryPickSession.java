package com.gitcraft.merge;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory state for one player's in-progress cherry-pick. Mirrors {@link MergeSession}
 * but the "source" is a single picked commit rather than a branch tip.
 */
public final class CherryPickSession implements Op {

    private final UUID playerId;
    private final long repoId;

    private final long targetBranchId;
    private final String targetBranchName;

    /** The commit that was picked. */
    private final long sourceCommitId;
    /** Parent of the picked commit, or null if the picked commit is a root. */
    private final Long baseCommitId;
    /** Active branch HEAD at start of cherry-pick — used as the new commit's parent. */
    private final long targetHeadCommitId;

    /** Picked commit's message — used to compose the default cherry-pick commit message. */
    private final String sourceMessage;

    private final UUID worldUuid;
    private final String worldName;

    /** Repo-space → world-space offset. All positions in the maps below are repo-space. */
    private final int ox;
    private final int oy;
    private final int oz;

    private final Map<BlockVector3, BlockState> autoApplied;
    private final Map<BlockVector3, Conflict>   conflicts;
    private final Map<BlockVector3, BlockState> preMergeWorld;
    private final Map<BlockVector3, BlockState> resolutions;

    private final long startedAt;
    private volatile long lastTouchedAt;

    public CherryPickSession(UUID playerId, long repoId,
                             long targetBranchId, String targetBranchName,
                             long sourceCommitId, Long baseCommitId,
                             long targetHeadCommitId, String sourceMessage,
                             UUID worldUuid, String worldName,
                             int ox, int oy, int oz,
                             Map<BlockVector3, BlockState> autoApplied,
                             Map<BlockVector3, Conflict>   conflicts,
                             Map<BlockVector3, BlockState> preMergeWorld) {
        this.playerId = playerId;
        this.repoId = repoId;
        this.targetBranchId = targetBranchId;
        this.targetBranchName = targetBranchName;
        this.sourceCommitId = sourceCommitId;
        this.baseCommitId = baseCommitId;
        this.targetHeadCommitId = targetHeadCommitId;
        this.sourceMessage = sourceMessage;
        this.worldUuid = worldUuid;
        this.worldName = worldName;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.autoApplied = autoApplied;
        this.conflicts = conflicts;
        this.preMergeWorld = preMergeWorld;
        this.resolutions = new HashMap<>();
        this.startedAt = System.currentTimeMillis();
        this.lastTouchedAt = startedAt;
    }

    public void touch() { this.lastTouchedAt = System.currentTimeMillis(); }

    @Override public UUID playerId() { return playerId; }
    @Override public OpKind kind() { return OpKind.CHERRY_PICK; }
    @Override public long lastTouchedAt() { return lastTouchedAt; }

    public long repoId() { return repoId; }
    public long targetBranchId() { return targetBranchId; }
    public String targetBranchName() { return targetBranchName; }
    public long sourceCommitId() { return sourceCommitId; }
    public Long baseCommitId() { return baseCommitId; }
    public long targetHeadCommitId() { return targetHeadCommitId; }
    public String sourceMessage() { return sourceMessage; }
    public UUID worldUuid() { return worldUuid; }
    public String worldName() { return worldName; }
    public int ox() { return ox; }
    public int oy() { return oy; }
    public int oz() { return oz; }
    public Map<BlockVector3, BlockState> autoApplied() { return autoApplied; }
    public Map<BlockVector3, Conflict> conflicts() { return conflicts; }
    public Map<BlockVector3, BlockState> preMergeWorld() { return preMergeWorld; }
    public Map<BlockVector3, BlockState> resolutions() { return resolutions; }
    public long startedAt() { return startedAt; }

    public boolean allConflictsResolved() {
        return resolutions.keySet().containsAll(conflicts.keySet());
    }
}
