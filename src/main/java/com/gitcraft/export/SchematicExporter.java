package com.gitcraft.export;

import com.gitcraft.GitCraft;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockState;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Synchronous WorldEdit-backed schematic writer. Callers are responsible for
 * dispatching this onto the async scheduler — never invoke from the main thread.
 */
public final class SchematicExporter {

    @SuppressWarnings("unused")
    private final GitCraft plugin;

    public SchematicExporter(GitCraft plugin) {
        this.plugin = plugin;
    }

    /**
     * Captures the world-space cuboid {@code worldPos1..worldPos2} and writes it into a
     * repo-space clipboard ({@code repoPos1..repoPos2}), then writes a Sponge V3
     * {@code .schem} to {@code target}. Block-entity NBT is stripped — structure only.
     *
     * <p>When the repo has no offset set, callers pass the same values for both world and
     * repo positions, which produces a clipboard at the original world coordinates.
     */
    public void writeSchematic(com.sk89q.worldedit.world.World weWorld,
                               BlockVector3 worldPos1,
                               BlockVector3 worldPos2,
                               BlockVector3 repoPos1,
                               BlockVector3 repoPos2,
                               Path target) throws IOException, WorldEditException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        CuboidRegion worldRegion = new CuboidRegion(weWorld, worldPos1, worldPos2);
        CuboidRegion repoRegion  = new CuboidRegion(repoPos1, repoPos2);
        BlockArrayClipboard clipboard = new BlockArrayClipboard(repoRegion);
        clipboard.setOrigin(repoRegion.getMinimumPoint());

        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .build()) {

            // Copy from world-space region into repo-space clipboard. The fourth arg
            // (repoPos1 = worldPos1 - offset) shifts each block's stored position so
            // the clipboard's coordinate system is repo-space.
            ForwardExtentCopy copy = new ForwardExtentCopy(
                    session, worldRegion, clipboard, repoRegion.getMinimumPoint());
            copy.setCopyingEntities(false);
            Operations.complete(copy);
        }

        stripBlockEntityNbt(clipboard);

        try (OutputStream out = Files.newOutputStream(target);
             ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(out)) {
            writer.write(clipboard);
        }
    }

    /** Delegates to the full overload with identical world and repo positions (offset = 0). */
    public void writeSchematic(com.sk89q.worldedit.world.World weWorld,
                               BlockVector3 pos1,
                               BlockVector3 pos2,
                               Path target) throws IOException, WorldEditException {
        writeSchematic(weWorld, pos1, pos2, pos1, pos2, target);
    }

    /**
     * Replace every BaseBlock with its NBT-less BlockState. CLAUDE.md: capture structure, not contents.
     * Result: chests come back empty, signs blank, spawners reset to default.
     */
    private void stripBlockEntityNbt(Clipboard clipboard) throws WorldEditException {
        for (BlockVector3 pos : clipboard.getRegion()) {
            BlockState state = clipboard.getBlock(pos);
            clipboard.setBlock(pos, state);
        }
    }
}
