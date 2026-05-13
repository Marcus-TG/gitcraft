package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.database.CommitDao;
import com.gitcraft.database.CommitRecord;
import com.gitcraft.database.HeadDao;
import com.gitcraft.database.HeadRecord;
import com.gitcraft.database.RepoDao;
import com.gitcraft.database.RepoRecord;
import com.gitcraft.diff.GhostBlockManager;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.ClipboardLoader;
import com.gitcraft.util.Messages;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class ResetSubcommand implements Subcommand {

    private static final String PERMISSION      = "gitcraft.reset";
    private static final String PERMISSION_HARD = "gitcraft.reset.hard";
    private static final long   CONFIRM_TTL_MS  = 30_000L;

    private final GitCraft plugin;
    private final SelectionManager manager;
    private final CommitDao commitDao;
    private final HeadDao headDao;
    private final RepoDao repoDao;
    private final GhostBlockManager ghostBlockManager;

    // TODO: Entries are not evicted on disconnect. 30s TTL is sufficient for now.
    //       Add a PlayerQuitEvent listener calling pending.remove(uuid) if explicit cleanup is needed.
    private final ConcurrentHashMap<UUID, PendingHardReset> pending = new ConcurrentHashMap<>();

    private record PendingHardReset(long commitId, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    public ResetSubcommand(GitCraft plugin, SelectionManager manager, CommitDao commitDao,
                           HeadDao headDao, RepoDao repoDao, GhostBlockManager ghostBlockManager) {
        this.plugin = plugin;
        this.manager = manager;
        this.commitDao = commitDao;
        this.headDao = headDao;
        this.repoDao = repoDao;
        this.ghostBlockManager = ghostBlockManager;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Messages.NO_PERMISSION);
            return;
        }
        if (args.length < 1) {
            player.sendMessage(Messages.RESET_USAGE);
            return;
        }

        long id;
        try {
            id = Long.parseLong(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(Messages.RESET_INVALID_ID);
            return;
        }
        if (id <= 0) {
            player.sendMessage(Messages.RESET_INVALID_ID);
            return;
        }

        Selection sel = manager.get(player.getUniqueId()).orElse(null);
        if (sel == null || sel.branchId() == null) {
            player.sendMessage(Messages.RESET_NO_REPO);
            return;
        }
        long activeBranchId = sel.branchId();
        Long repoIdBoxed = sel.repoId();
        if (repoIdBoxed == null) {
            player.sendMessage(Messages.RESET_NO_REPO);
            return;
        }
        long activeRepoId = repoIdBoxed;

        boolean hard = args.length >= 2 && "--hard".equals(args[1]);

        if (!hard) {
            player.sendMessage(Messages.RESET_STARTED);
            Bukkit.getScheduler().runTaskAsynchronously(plugin,
                    () -> loadAndDispatch(player.getUniqueId(), id, activeBranchId, activeRepoId));
            return;
        }

        if (!player.hasPermission(PERMISSION_HARD)) {
            player.sendMessage(Messages.NO_PERMISSION);
            return;
        }

        UUID playerId = player.getUniqueId();
        PendingHardReset existing = pending.get(playerId);
        if (existing != null && !existing.isExpired() && existing.commitId() == id) {
            pending.remove(playerId);
            player.sendMessage(Messages.RESET_HARD_STARTED);
            Bukkit.getScheduler().runTaskAsynchronously(plugin,
                    () -> loadAndDispatchHard(playerId, id, activeBranchId, activeRepoId));
        } else {
            pending.put(playerId, new PendingHardReset(id, System.currentTimeMillis() + CONFIRM_TTL_MS));
            player.sendMessage(String.format(Messages.RESET_HARD_WARN, id));
        }
    }

    private void loadAndDispatch(UUID playerId, long id, long activeBranchId, long activeRepoId) {
        CommitRecord record;
        int ox = 0, oy = 0, oz = 0;
        try {
            Optional<CommitRecord> opt = commitDao.findById(id);
            if (opt.isEmpty()) {
                sendOnMain(playerId, String.format(Messages.RESET_NOT_FOUND, id));
                return;
            }
            record = opt.get();
            RepoRecord repo = repoDao.findById(activeRepoId).orElse(null);
            if (repo != null) { ox = repo.effectiveOffsetX(); oy = repo.effectiveOffsetY(); oz = repo.effectiveOffsetZ(); }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Reset lookup failed", e);
            sendOnMain(playerId, String.format(Messages.RESET_DB_FAILED, safe(e.getMessage())));
            return;
        }

        if (record.branchId() != activeBranchId) {
            sendOnMain(playerId, String.format(Messages.RESET_WRONG_BRANCH, id));
            return;
        }

        if (!record.playerUuid().equals(playerId)) {
            sendOnMain(playerId, Messages.RESET_NOT_OWNER);
            return;
        }

        Clipboard clipboard = loadClipboard(playerId, record);
        if (clipboard == null) return;

        final int oxF = ox, oyF = oy, ozF = oz;
        Bukkit.getScheduler().runTask(plugin, () -> paste(playerId, record, clipboard, -1, activeRepoId, oxF, oyF, ozF));
    }

    private void loadAndDispatchHard(UUID playerId, long id, long activeBranchId, long activeRepoId) {
        CommitRecord record;
        List<CommitRecord> toDelete;
        int deletedCount;
        int ox = 0, oy = 0, oz = 0;
        try {
            Optional<CommitRecord> opt = commitDao.findById(id);
            if (opt.isEmpty()) {
                sendOnMain(playerId, String.format(Messages.RESET_NOT_FOUND, id));
                return;
            }
            record = opt.get();

            if (record.branchId() != activeBranchId) {
                sendOnMain(playerId, String.format(Messages.RESET_WRONG_BRANCH, id));
                return;
            }

            if (!record.playerUuid().equals(playerId)) {
                sendOnMain(playerId, Messages.RESET_NOT_OWNER);
                return;
            }

            toDelete = commitDao.findNewerThan(id, record.branchId());
            RepoRecord repo = repoDao.findById(activeRepoId).orElse(null);
            if (repo != null) { ox = repo.effectiveOffsetX(); oy = repo.effectiveOffsetY(); oz = repo.effectiveOffsetZ(); }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Hard reset lookup failed", e);
            sendOnMain(playerId, String.format(Messages.RESET_DB_FAILED, safe(e.getMessage())));
            return;
        }

        Clipboard clipboard = loadClipboard(playerId, record);
        if (clipboard == null) return;

        // Move HEAD to the target commit before deleting newer commits. This avoids a FK
        // constraint violation if heads.commit_id points to a commit that is about to be deleted.
        try {
            headDao.upsert(new HeadRecord(playerId, activeRepoId, record.branchId(), record.id()));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Hard reset HEAD update failed", e);
            sendOnMain(playerId, String.format(Messages.RESET_DB_FAILED, safe(e.getMessage())));
            return;
        }

        // TODO: If the server crashes after this DB delete but before the main-thread paste,
        //       the DB loses these commits while the world still shows the newer state.
        //       Acceptable for a side project; the window is sub-millisecond.
        try {
            deletedCount = commitDao.deleteNewerThan(id, record.branchId());
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Hard reset DB delete failed", e);
            sendOnMain(playerId, String.format(Messages.RESET_DB_FAILED, safe(e.getMessage())));
            return;
        }

        for (CommitRecord r : toDelete) {
            try {
                Files.deleteIfExists(Path.of(r.schemPath()));
            } catch (IOException e) {
                plugin.getLogger().warning("Could not delete schematic " + r.schemPath() + ": " + e.getMessage());
            }
        }

        final CommitRecord rec = record;
        final int deleted = deletedCount;
        final int oxF = ox, oyF = oy, ozF = oz;
        Bukkit.getScheduler().runTask(plugin, () -> paste(playerId, rec, clipboard, deleted, activeRepoId, oxF, oyF, ozF));
    }

    private Clipboard loadClipboard(UUID playerId, CommitRecord record) {
        Path schemPath = Paths.get(record.schemPath());
        if (!Files.exists(schemPath)) {
            sendOnMain(playerId, String.format(Messages.RESET_FILE_MISSING, schemPath));
            return null;
        }
        try {
            return ClipboardLoader.load(schemPath);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read schematic " + schemPath, e);
            sendOnMain(playerId, String.format(Messages.RESET_IO_FAILED, safe(e.getMessage())));
            return null;
        }
    }

    private void paste(UUID playerId, CommitRecord record, Clipboard clipboard, int deletedAfter, long repoId,
                       int ox, int oy, int oz) {
        World world = Bukkit.getWorld(record.worldUuid());
        if (world == null) {
            sendNow(playerId, String.format(Messages.RESET_WORLD_GONE, record.worldName()));
            return;
        }

        BlockVector3 to = BlockVector3.at(record.minX() + ox, record.minY() + oy, record.minZ() + oz);

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

            // Update selection to match the world-space footprint.
            Selection sel = manager.getOrCreate(playerId);
            sel.setPos1(world, BlockVector3.at(record.minX() + ox, record.minY() + oy, record.minZ() + oz));
            sel.setPos2(world, BlockVector3.at(record.maxX() + ox, record.maxY() + oy, record.maxZ() + oz));

            int changed = edit.getBlockChangeCount();
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) ghostBlockManager.clear(player);
            if (deletedAfter < 0) {
                // Soft reset: move HEAD to the target commit now that paste succeeded.
                final long targetCommitId = record.id();
                final long branchIdSnap = record.branchId();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        headDao.upsert(new HeadRecord(playerId, repoId, branchIdSnap, targetCommitId));
                    } catch (SQLException e) {
                        plugin.getLogger().log(Level.WARNING, "Failed to update HEAD after soft reset", e);
                    }
                });
                sendNow(playerId, String.format(Messages.RESET_SUCCESS, record.id(), changed));
                plugin.getLogger().info("Reset #" + record.id()
                        + " branch=" + record.branchId() + " blocks=" + changed);
            } else {
                sendNow(playerId, String.format(Messages.RESET_HARD_SUCCESS,
                        record.id(), changed, deletedAfter));
                plugin.getLogger().info("Hard reset #" + record.id()
                        + " branch=" + record.branchId()
                        + " blocks=" + changed + " deleted=" + deletedAfter);
            }
        } catch (WorldEditException e) {
            plugin.getLogger().log(Level.WARNING, "Reset paste failed", e);
            sendNow(playerId, String.format(Messages.RESET_WE_FAILED, safe(e.getMessage())));
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
