package com.gitcraft.merge;

import com.gitcraft.diff.DiffResult;
import com.gitcraft.diff.GhostBlock;
import com.gitcraft.diff.GhostType;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared helpers used by both MergeService and CherryPickService. */
public final class MergeOps {

    private MergeOps() {}

    public static int min3(int a, int b, int c) { return Math.min(a, Math.min(b, c)); }
    public static int max3(int a, int b, int c) { return Math.max(a, Math.max(b, c)); }

    public static boolean isAir(BlockState state) {
        BlockType type = state.getBlockType();
        return type == BlockTypes.AIR || type == BlockTypes.CAVE_AIR || type == BlockTypes.VOID_AIR;
    }

    /** Capture the current world state at {@code pos} as a WorldEdit BlockState. Main thread only. */
    public static BlockState captureWorldState(World world, BlockVector3 pos) {
        BlockData data = world.getBlockAt(pos.x(), pos.y(), pos.z()).getBlockData();
        return BukkitAdapter.adapt(data);
    }

    /** Build purple ghost blocks for every conflict position. */
    public static DiffResult buildConflictGhosts(Map<BlockVector3, Conflict> conflicts) {
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

    /** Hop to the main thread to send a chat message to a player; no-op if offline. */
    public static void sendOnMain(Plugin plugin, UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }

    public static String safe(String s) { return s == null ? "(no detail)" : s; }

    /** Bounding box union over a set of positions. */
    public static BBox unionBBox(Iterable<BlockVector3> positions) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (BlockVector3 p : positions) {
            any = true;
            if (p.x() < minX) minX = p.x();
            if (p.y() < minY) minY = p.y();
            if (p.z() < minZ) minZ = p.z();
            if (p.x() > maxX) maxX = p.x();
            if (p.y() > maxY) maxY = p.y();
            if (p.z() > maxZ) maxZ = p.z();
        }
        if (!any) return new BBox(0, 0, 0, 0, 0, 0);
        return new BBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public record BBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}
}
