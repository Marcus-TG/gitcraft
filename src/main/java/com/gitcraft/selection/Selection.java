package com.gitcraft.selection;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import org.bukkit.World;

import java.util.UUID;

/**
 * Per-player selection state. Two corner points + the world they belong to.
 * Mutated only on the main thread; corner snapshots are taken before async handoff.
 */
public final class Selection {

    private UUID worldId;
    private BlockVector3 pos1;
    private BlockVector3 pos2;
    private Long   repoId;
    private String repoName;
    private Long   branchId;
    private String branchName;

    public synchronized void setRepoId(long repoId)       { this.repoId   = repoId;   }
    public synchronized void setRepoName(String repoName) { this.repoName  = repoName; }
    public synchronized void setBranchId(long branchId)   { this.branchId  = branchId; }
    public synchronized void setBranchName(String name)   { this.branchName = name;    }

    public synchronized Long   repoId()     { return repoId;     }
    public synchronized String repoName()   { return repoName;   }
    public synchronized Long   branchId()   { return branchId;   }
    public synchronized String branchName() { return branchName; }

    public synchronized void clearRegion() {
        pos1 = null;
        pos2 = null;
        worldId = null;
    }

    public synchronized void setPos1(World world, BlockVector3 v) {
        if (worldId != null && !worldId.equals(world.getUID())) {
            // World changed — reset both corners.
            pos2 = null;
        }
        worldId = world.getUID();
        pos1 = v;
    }

    public synchronized void setPos2(World world, BlockVector3 v) {
        if (worldId != null && !worldId.equals(world.getUID())) {
            pos1 = null;
        }
        worldId = world.getUID();
        pos2 = v;
    }

    public synchronized boolean isComplete() {
        return pos1 != null && pos2 != null && worldId != null;
    }

    public synchronized UUID worldId() {
        return worldId;
    }

    public synchronized BlockVector3 pos1() {
        return pos1;
    }

    public synchronized BlockVector3 pos2() {
        return pos2;
    }

    /**
     * Build a CuboidRegion from the two corners. Throws if incomplete.
     * WorldEdit normalizes min/max, so corner order doesn't matter.
     */
    public synchronized CuboidRegion toRegion() {
        if (!isComplete()) {
            throw new IllegalStateException("Selection is not complete.");
        }
        return new CuboidRegion(pos1, pos2);
    }
}
