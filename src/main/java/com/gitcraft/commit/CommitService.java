package com.gitcraft.commit;

import com.gitcraft.GitCraft;
import com.gitcraft.database.BranchDao;
import com.gitcraft.database.BranchRecord;
import com.gitcraft.database.CommitDao;
import com.gitcraft.database.CommitRecord;
import com.gitcraft.database.HeadDao;
import com.gitcraft.database.HeadRecord;
import com.gitcraft.diff.GhostBlockManager;
import com.gitcraft.export.SchematicExporter;
import com.gitcraft.util.Messages;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Owns the async commit pipeline: write schematic, insert metadata row, send feedback.
 * On DB failure after a successful schematic write, best-effort delete the orphan file.
 */
public final class CommitService {

    private final GitCraft plugin;
    private final SchematicExporter exporter;
    private final CommitDao commitDao;
    private final BranchDao branchDao;
    private final HeadDao headDao;
    private final GhostBlockManager ghostBlockManager;

    public CommitService(GitCraft plugin, SchematicExporter exporter,
                         CommitDao commitDao, BranchDao branchDao, HeadDao headDao,
                         GhostBlockManager ghostBlockManager) {
        this.plugin = plugin;
        this.exporter = exporter;
        this.commitDao = commitDao;
        this.branchDao = branchDao;
        this.headDao = headDao;
        this.ghostBlockManager = ghostBlockManager;
    }

    public void commitAsync(UUID playerId,
                            String playerName,
                            long branchId,
                            long repoId,
                            String message,
                            World bukkitWorld,
                            BlockVector3 pos1,
                            BlockVector3 pos2,
                            Path schemPath) {
        // adapt() must run on the main thread.
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);

        // Snapshot world identity + bbox here on the main thread before async handoff.
        UUID worldUuid = bukkitWorld.getUID();
        String worldName = bukkitWorld.getName();
        int minX = Math.min(pos1.x(), pos2.x());
        int minY = Math.min(pos1.y(), pos2.y());
        int minZ = Math.min(pos1.z(), pos2.z());
        int maxX = Math.max(pos1.x(), pos2.x());
        int maxY = Math.max(pos1.y(), pos2.y());
        int maxZ = Math.max(pos1.z(), pos2.z());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean schemWritten = false;
            try {
                exporter.writeSchematic(weWorld, pos1, pos2, schemPath);
                schemWritten = true;

                Long parentCommitId = commitDao.findLatestIdByBranch(branchId).orElse(null);
                if (parentCommitId == null) {
                    // First commit on a newly-created branch — use the fork point as parent
                    // so the commit graph retains lineage back to the source branch.
                    BranchRecord branch = branchDao.findById(branchId).orElse(null);
                    if (branch != null) parentCommitId = branch.forkCommitId();
                }
                long createdAt = System.currentTimeMillis();
                long id = commitDao.insert(new CommitRecord(
                        null, parentCommitId, branchId, playerId, playerName, message,
                        schemPath.toString(), createdAt,
                        worldUuid, worldName, minX, minY, minZ, maxX, maxY, maxZ));

                try {
                    headDao.upsert(new HeadRecord(playerId, repoId, branchId, id));
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Commit " + id + " saved but HEAD update failed", e);
                }

                final long commitId = id;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(playerId);
                    if (p != null && p.isOnline()) {
                        ghostBlockManager.clear(p);
                        p.sendMessage(String.format(Messages.COMMIT_SUCCESS, commitId));
                    }
                });
                plugin.getLogger().info("Commit " + id + " saved (branch=" + branchId
                        + ", player=" + playerName + ")");

            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Schematic IO error", e);
                sendOnMain(playerId, String.format(Messages.COMMIT_IO_FAILED, safe(e.getMessage())));
            } catch (WorldEditException e) {
                plugin.getLogger().log(Level.WARNING, "WorldEdit commit error", e);
                sendOnMain(playerId, String.format(Messages.COMMIT_WE_FAILED, safe(e.getMessage())));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Commit DB insert failed", e);
                if (schemWritten) {
                    deleteOrphan(schemPath);
                }
                sendOnMain(playerId, String.format(Messages.COMMIT_DB_FAILED, safe(e.getMessage())));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Unexpected commit error", e);
                if (schemWritten) {
                    deleteOrphan(schemPath);
                }
                sendOnMain(playerId, String.format(Messages.COMMIT_WE_FAILED, safe(e.getMessage())));
            }
        });
    }

    private void deleteOrphan(Path schemPath) {
        try {
            Files.deleteIfExists(schemPath);
        } catch (IOException io) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to delete orphan schematic at " + schemPath, io);
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

    private static String safe(String s) {
        return s == null ? "(no detail)" : s;
    }
}
