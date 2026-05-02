package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.database.BranchDao;
import com.gitcraft.database.BranchRecord;
import com.gitcraft.database.CommitDao;
import com.gitcraft.database.CommitRecord;
import com.gitcraft.database.HeadDao;
import com.gitcraft.database.HeadRecord;
import com.gitcraft.diff.GhostBlockManager;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
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

public final class CheckoutSubcommand implements Subcommand {

    private static final String PERMISSION = "gitcraft.checkout";

    private final GitCraft plugin;
    private final SelectionManager manager;
    private final CommitDao commitDao;
    private final BranchDao branchDao;
    private final HeadDao headDao;
    private final GhostBlockManager ghostBlockManager;

    public CheckoutSubcommand(GitCraft plugin, SelectionManager manager,
                               CommitDao commitDao, BranchDao branchDao, HeadDao headDao,
                               GhostBlockManager ghostBlockManager) {
        this.plugin = plugin;
        this.manager = manager;
        this.commitDao = commitDao;
        this.branchDao = branchDao;
        this.headDao = headDao;
        this.ghostBlockManager = ghostBlockManager;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Messages.NO_PERMISSION);
            return;
        }
        if (args.length < 1) {
            player.sendMessage(Messages.CHECKOUT_USAGE);
            return;
        }

        Selection sel = manager.get(player.getUniqueId()).orElse(null);
        if (sel == null) {
            player.sendMessage(Messages.NO_SELECTION);
            return;
        }
        Long repoId = sel.repoId();
        if (repoId == null) {
            player.sendMessage(Messages.CHECKOUT_NO_REPO);
            return;
        }

        String targetBranchName = args[0];
        if (targetBranchName.equals(sel.branchName())) {
            player.sendMessage(String.format(Messages.CHECKOUT_ALREADY_ON, targetBranchName));
            return;
        }

        UUID playerId = player.getUniqueId();
        long repoIdSnap = repoId;

        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> doCheckout(playerId, repoIdSnap, targetBranchName));
    }

    private void doCheckout(UUID playerId, long repoId, String targetBranchName) {
        try {
            Optional<BranchRecord> branchOpt = branchDao.findByRepoAndName(repoId, targetBranchName);
            if (branchOpt.isEmpty()) {
                sendOnMain(playerId, String.format(Messages.CHECKOUT_BRANCH_NOT_FOUND, targetBranchName));
                return;
            }
            long targetBranchId = branchOpt.get().id();

            Optional<Long> latestIdOpt = commitDao.findLatestIdByBranch(targetBranchId);
            if (latestIdOpt.isEmpty()) {
                headDao.upsert(new HeadRecord(playerId, repoId, targetBranchId, null));
                final long branchIdSnap = targetBranchId;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(playerId);
                    if (p == null || !p.isOnline()) return;
                    Selection sel = manager.getOrCreate(playerId);
                    sel.setBranchId(branchIdSnap);
                    sel.setBranchName(targetBranchName);
                    p.sendMessage(String.format(Messages.CHECKOUT_EMPTY_BRANCH, targetBranchName));
                });
                return;
            }

            CommitRecord record = commitDao.findById(latestIdOpt.get()).orElse(null);
            if (record == null) {
                sendOnMain(playerId, Messages.CHECKOUT_DB_ERROR);
                return;
            }

            Clipboard clipboard = loadClipboard(playerId, record);
            if (clipboard == null) return;

            // TODO: No ownership check — checkout is read-only and future collaboration
            //       should allow any branch participant to checkout freely.
            headDao.upsert(new HeadRecord(playerId, repoId, targetBranchId, latestIdOpt.get()));

            final long branchIdSnap = targetBranchId;
            final CommitRecord rec = record;
            final Clipboard cb = clipboard;
            Bukkit.getScheduler().runTask(plugin,
                    () -> applyOnMain(playerId, branchIdSnap, targetBranchName, rec, cb));

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Checkout DB error", e);
            sendOnMain(playerId, Messages.CHECKOUT_DB_ERROR);
        }
    }

    private void applyOnMain(UUID playerId, long targetBranchId, String targetBranchName,
                              CommitRecord record, Clipboard clipboard) {
        Player p = Bukkit.getPlayer(playerId);
        if (p == null || !p.isOnline()) return;

        Selection sel = manager.getOrCreate(playerId);
        sel.setBranchId(targetBranchId);
        sel.setBranchName(targetBranchName);

        World world = Bukkit.getWorld(record.worldUuid());
        if (world == null) {
            sel.clearRegion();
            p.sendMessage(String.format(Messages.CHECKOUT_WORLD_GONE,
                    targetBranchName, record.worldName()));
            return;
        }

        sel.setPos1(world, BlockVector3.at(record.minX(), record.minY(), record.minZ()));
        sel.setPos2(world, BlockVector3.at(record.maxX(), record.maxY(), record.maxZ()));

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
            ghostBlockManager.clear(p);
            p.sendMessage(String.format(Messages.CHECKOUT_SUCCESS, targetBranchName, changed));
            plugin.getLogger().info("Checkout to '" + targetBranchName + "' by " + playerId
                    + " — " + changed + " blocks");
        } catch (WorldEditException e) {
            plugin.getLogger().log(Level.WARNING, "Checkout paste failed", e);
            p.sendMessage(Messages.CHECKOUT_WE_ERROR);
        }
    }

    private Clipboard loadClipboard(UUID playerId, CommitRecord record) {
        Path schemPath = Paths.get(record.schemPath());
        if (!Files.exists(schemPath)) {
            sendOnMain(playerId, Messages.CHECKOUT_FILE_MISSING);
            return null;
        }
        try {
            File file = schemPath.toFile();
            ClipboardFormat fmt = ClipboardFormats.findByFile(file);
            if (fmt == null) {
                sendOnMain(playerId, Messages.CHECKOUT_FILE_CORRUPT);
                return null;
            }
            try (InputStream in = Files.newInputStream(schemPath);
                 ClipboardReader reader = fmt.getReader(in)) {
                return reader.read();
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read schematic " + schemPath, e);
            sendOnMain(playerId, Messages.CHECKOUT_IO_ERROR);
            return null;
        }
    }

    private void sendOnMain(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }
}
