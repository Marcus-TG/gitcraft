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
     * Captures the cuboid between {@code pos1} and {@code pos2} in {@code weWorld}
     * and writes it as a Sponge V3 {@code .schem} to {@code target}. Block-entity
     * NBT is stripped — structure only.
     */
    public void writeSchematic(com.sk89q.worldedit.world.World weWorld,
                               BlockVector3 pos1,
                               BlockVector3 pos2,
                               Path target) throws IOException, WorldEditException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        CuboidRegion region = new CuboidRegion(weWorld, pos1, pos2);
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        clipboard.setOrigin(region.getMinimumPoint());

        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .build()) {

            ForwardExtentCopy copy = new ForwardExtentCopy(
                    session, region, clipboard, region.getMinimumPoint());
            copy.setCopyingEntities(false);
            Operations.complete(copy);
        }

        stripBlockEntityNbt(clipboard);

        try (OutputStream out = Files.newOutputStream(target);
             ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(out)) {
            writer.write(clipboard);
        }
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
