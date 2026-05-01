package com.gitcraft.export;

import com.gitcraft.GitCraft;
import com.gitcraft.util.Messages;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Async schematic export pipeline. WorldEdit copy + file write happen off the main thread.
 * Player feedback is re-scheduled onto the main thread before sending.
 */
public final class SchematicExporter {

    private final GitCraft plugin;

    public SchematicExporter(GitCraft plugin) {
        this.plugin = plugin;
    }

    public void exportAsync(UUID playerId, World bukkitWorld, BlockVector3 pos1, BlockVector3 pos2, Path target) {
        // adapt() is main-thread-safe; called here on the main thread.
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Files.createDirectories(target.getParent());

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

                sendOnMain(playerId, String.format(Messages.EXPORT_SUCCESS, target.toString()));
                plugin.getLogger().info("Exported schematic: " + target);

            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Schematic IO error", e);
                sendOnMain(playerId, String.format(Messages.EXPORT_IO_FAILED, e.getMessage()));
            } catch (WorldEditException e) {
                plugin.getLogger().log(Level.WARNING, "WorldEdit export error", e);
                sendOnMain(playerId, String.format(Messages.EXPORT_WE_FAILED, e.getMessage()));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Unexpected export error", e);
                sendOnMain(playerId, String.format(Messages.EXPORT_WE_FAILED, e.getMessage()));
            }
        });
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

    private void sendOnMain(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) {
                p.sendMessage(message);
            }
        });
    }
}
