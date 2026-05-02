package com.gitcraft.diff;

import com.gitcraft.database.CommitRecord;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public final class DiffService {

    private final Logger log;

    public DiffService(Logger log) {
        this.log = log;
    }

    public DiffResult compute(CommitRecord before, CommitRecord after) throws IOException {
        Clipboard clipBefore = loadClipboard(before.schemPath());
        Clipboard clipAfter  = loadClipboard(after.schemPath());

        // TODO(OQ1): Validate clipboard coordinate space at runtime.
        // Expected: clip.getRegion().getMinimumPoint() == BlockVector3.at(commit.minX, minY, minZ).
        // If this warning fires, all getBlock calls need a manual offset correction:
        //   clip.getBlock(worldPos.subtract(clipMin).add(BlockVector3.at(commit.minX, minY, minZ)))
        BlockVector3 expectedMin = BlockVector3.at(before.minX(), before.minY(), before.minZ());
        BlockVector3 actualMin   = clipBefore.getRegion().getMinimumPoint();
        if (!expectedMin.equals(actualMin)) {
            log.warning("[GitCraft diff] Clipboard origin mismatch for commit " + before.id()
                    + ": expected " + expectedMin + " but got " + actualMin
                    + ". Block positions in diff output may be incorrect.");
        }

        int minX = Math.min(before.minX(), after.minX());
        int minY = Math.min(before.minY(), after.minY());
        int minZ = Math.min(before.minZ(), after.minZ());
        int maxX = Math.max(before.maxX(), after.maxX());
        int maxY = Math.max(before.maxY(), after.maxY());
        int maxZ = Math.max(before.maxZ(), after.maxZ());

        List<GhostBlock> ghosts = new ArrayList<>();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockVector3 pos   = BlockVector3.at(x, y, z);
                    BlockState   stateB = getBlockAt(clipBefore, pos);
                    BlockState   stateA = getBlockAt(clipAfter, pos);

                    boolean airB = isAir(stateB);
                    boolean airA = isAir(stateA);

                    if (airB && airA) continue;

                    if (airB) {
                        ghosts.add(new GhostBlock(pos, GhostType.ADDED,
                                null, BukkitAdapter.adapt(stateA)));
                    } else if (airA) {
                        ghosts.add(new GhostBlock(pos, GhostType.REMOVED,
                                BukkitAdapter.adapt(stateB), null));
                    } else if (!stateB.equals(stateA)) {
                        ghosts.add(new GhostBlock(pos, GhostType.CHANGED,
                                BukkitAdapter.adapt(stateB),
                                BukkitAdapter.adapt(stateA)));
                    }
                }
            }
        }

        return new DiffResult(ghosts);
    }

    private BlockState getBlockAt(Clipboard clip, BlockVector3 pos) {
        if (!clip.getRegion().contains(pos)) {
            return BlockTypes.AIR.getDefaultState();
        }
        return clip.getBlock(pos);
    }

    private static boolean isAir(BlockState state) {
        BlockType type = state.getBlockType();
        return type == BlockTypes.AIR || type == BlockTypes.CAVE_AIR || type == BlockTypes.VOID_AIR;
    }

    private Clipboard loadClipboard(String schemPath) throws IOException {
        File file = Paths.get(schemPath).toFile();
        if (!file.exists()) {
            throw new IOException("Schematic file missing: " + schemPath);
        }
        ClipboardFormat fmt = ClipboardFormats.findByFile(file);
        if (fmt == null) {
            throw new IOException("Unrecognized schematic format: " + schemPath);
        }
        try (InputStream in = Files.newInputStream(file.toPath());
             ClipboardReader reader = fmt.getReader(in)) {
            return reader.read();
        }
    }
}
