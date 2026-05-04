package com.gitcraft.merge;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure three-way diff: base vs ours vs theirs across the union bounding box.
 *
 * Conservative auto-apply rule: a position auto-applies ONLY when theirs introduces a
 * block at a previously empty position — base is air, ours is air, theirs is non-air.
 * Any other change that would mutate the existing world (ours) is a CONFLICT, including:
 * delete-on-theirs where ours has a block, modify-on-theirs where ours has a block,
 * and any divergent change between ours and theirs.
 *
 * AIR/CAVE_AIR/VOID_AIR are treated as "empty"; outside-clip positions are also empty.
 */
public final class ThreeWayDiff {

    private ThreeWayDiff() {}

    public static Result compute(Clipboard base,
                                 Clipboard ours,
                                 Clipboard theirs,
                                 int minX, int minY, int minZ,
                                 int maxX, int maxY, int maxZ) {
        Map<BlockVector3, BlockState> autoApplied = new HashMap<>();
        Map<BlockVector3, Conflict>   conflicts   = new HashMap<>();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockVector3 pos = BlockVector3.at(x, y, z);
                    BlockState b = blockAt(base,   pos);
                    BlockState o = blockAt(ours,   pos);
                    BlockState t = blockAt(theirs, pos);

                    boolean ourChanged   = !equal(b, o);
                    boolean theirChanged = !equal(b, t);

                    if (!theirChanged) {
                        // theirs matches base — nothing incoming. World keeps ours.
                        continue;
                    }
                    if (ourChanged && equal(o, t)) {
                        // both sides converged on the same result; world already has it.
                        continue;
                    }

                    // theirs differs from base. The only safe auto-apply is a pure addition
                    // into empty space: base air + ours air + theirs non-air.
                    if (isAir(b) && isAir(o) && !isAir(t)) {
                        autoApplied.put(pos, t);
                        continue;
                    }

                    // Any other case mutates a position where ours holds a block (or where
                    // ours and theirs disagree). Treat as conflict for the player to resolve.
                    conflicts.put(pos, new Conflict(pos, b, o, t));
                }
            }
        }

        return new Result(autoApplied, conflicts);
    }

    private static BlockState blockAt(Clipboard clip, BlockVector3 pos) {
        if (!clip.getRegion().contains(pos)) {
            return BlockTypes.AIR.getDefaultState();
        }
        return clip.getBlock(pos);
    }

    /** Air-aware equality: treats AIR / CAVE_AIR / VOID_AIR as the same empty state. */
    private static boolean equal(BlockState a, BlockState b) {
        boolean airA = isAir(a);
        boolean airB = isAir(b);
        if (airA && airB) return true;
        if (airA != airB) return false;
        return a.equals(b);
    }

    private static boolean isAir(BlockState state) {
        BlockType type = state.getBlockType();
        return type == BlockTypes.AIR || type == BlockTypes.CAVE_AIR || type == BlockTypes.VOID_AIR;
    }

    public record Result(
            Map<BlockVector3, BlockState> autoApplied,
            Map<BlockVector3, Conflict>   conflicts
    ) {
        public boolean isNoOp() { return autoApplied.isEmpty() && conflicts.isEmpty(); }
    }
}
