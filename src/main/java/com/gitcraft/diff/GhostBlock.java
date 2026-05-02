package com.gitcraft.diff;

import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.block.data.BlockData;

public record GhostBlock(
        BlockVector3 worldPos,
        GhostType type,
        BlockData beforeBlock,  // null for ADDED
        BlockData afterBlock    // null for REMOVED
) {}
