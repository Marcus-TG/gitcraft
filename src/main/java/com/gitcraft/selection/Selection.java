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
    private String name;

    public synchronized void setName(String name) {
        this.name = name;
    }

    public synchronized String name() {
        return name;
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
