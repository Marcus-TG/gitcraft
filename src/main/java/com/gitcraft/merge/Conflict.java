package com.gitcraft.merge;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;

/**
 * One disputed block position in a three-way merge.
 * Any of {@code base}, {@code ours}, {@code theirs} may be air; equality is checked
 * by the caller against the relevant air-detection rule (see {@link com.gitcraft.diff.DiffService}).
 */
public record Conflict(
        BlockVector3 pos,
        BlockState base,
        BlockState ours,
        BlockState theirs
) {}
