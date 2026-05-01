package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.database.CommitDao;
import com.gitcraft.database.CommitRecord;
import com.gitcraft.util.Messages;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public final class RestoreSubcommand implements Subcommand {

    private static final String PERMISSION = "gitcraft.restore";

    private final GitCraft plugin;
    private final CommitDao commitDao;

    public RestoreSubcommand(GitCraft plugin, CommitDao commitDao) {
        this.plugin = plugin;
        this.commitDao = commitDao;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Messages.NO_PERMISSION);
            return;
        }
        if (args.length < 1) {
            player.sendMessage(Messages.RESTORE_USAGE);
            return;
        }

        long id;
        try {
            id = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(Messages.RESTORE_INVALID_ID);
            return;
        }
        if (id <= 0) {
            player.sendMessage(Messages.RESTORE_INVALID_ID);
            return;
        }

        UUID playerId = player.getUniqueId();
        player.sendMessage(Messages.RESTORE_STARTED);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> loadAndDispatch(playerId, id));
    }

    private void loadAndDispatch(UUID playerId, long id) {
        CommitRecord record;
        try {
            Optional<CommitRecord> opt = commitDao.findById(id);
            if (opt.isEmpty()) {
                sendOnMain(playerId, String.format(Messages.RESTORE_NOT_FOUND, id));
                return;
            }
            record = opt.get();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Restore lookup failed", e);
            sendOnMain(playerId, String.format(Messages.RESTORE_DB_FAILED, safe(e.getMessage())));
            return;
        }

        Path schemPath = Paths.get(record.schemPath());
        if (!Files.exists(schemPath)) {
            sendOnMain(playerId, String.format(Messages.RESTORE_FILE_MISSING, schemPath));
            return;
        }

        Clipboard clipboard;
        try {
            File file = schemPath.toFile();
            ClipboardFormat fmt = ClipboardFormats.findByFile(file);
            if (fmt == null) {
                sendOnMain(playerId, Messages.RESTORE_BAD_FORMAT);
                return;
            }
            try (InputStream in = Files.newInputStream(schemPath);
                 ClipboardReader reader = fmt.getReader(in)) {
                clipboard = reader.read();
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read schematic " + schemPath, e);
            sendOnMain(playerId, String.format(Messages.RESTORE_IO_FAILED, safe(e.getMessage())));
            return;
        }

        // Hop to the main thread for the paste — chunk writes belong on the server tick.
        Bukkit.getScheduler().runTask(plugin, () -> paste(playerId, record, clipboard));
    }

    private void paste(UUID playerId, CommitRecord record, Clipboard clipboard) {
        World world = Bukkit.getWorld(record.worldUuid());
        if (world == null) {
            sendNow(playerId, String.format(Messages.RESTORE_WORLD_GONE, record.worldName()));
            return;
        }

        BlockVector3 to = BlockVector3.at(record.minX(), record.minY(), record.minZ());

        try (EditSession edit = WorldEdit.getInstance().newEditSessionBuilder()
                .world(BukkitAdapter.adapt(world))
                .maxBlocks(-1)
                .build()) {
            Operations.complete(
                    new ClipboardHolder(clipboard)
                            .createPaste(edit)
                            .to(to)
                            .ignoreAirBlocks(false)
                            .build());

            int changed = edit.getBlockChangeCount();
            sendNow(playerId, String.format(Messages.RESTORE_SUCCESS,
                    record.id(), record.regionName(), changed));
            plugin.getLogger().info("Restore #" + record.id() + " region=" + record.regionName()
                    + " blocks=" + changed);
        } catch (WorldEditException e) {
            plugin.getLogger().log(Level.WARNING, "Restore paste failed", e);
            sendNow(playerId, String.format(Messages.RESTORE_WE_FAILED, safe(e.getMessage())));
        }
    }

    private void sendOnMain(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> sendNow(playerId, message));
    }

    private void sendNow(UUID playerId, String message) {
        Player p = Bukkit.getPlayer(playerId);
        if (p != null && p.isOnline()) p.sendMessage(message);
    }

    private static String safe(String s) {
        return s == null ? "(no detail)" : s;
    }
}
