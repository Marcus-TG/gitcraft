package com.gitcraft.git;

import com.gitcraft.database.BranchRecord;
import com.gitcraft.database.CommitDao;
import com.gitcraft.database.CommitGitShaDao;
import com.gitcraft.database.CommitRecord;
import com.gitcraft.database.Database;
import com.gitcraft.database.HeadDao;
import com.gitcraft.database.HeadRecord;
import com.gitcraft.database.RemoteRecord;
import com.gitcraft.database.RepoRecord;
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
import org.bukkit.plugin.Plugin;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GitPullService {

    private final CommitMapper mapper;
    private final Logger logger;

    public GitPullService(CommitMapper mapper, Logger logger) {
        this.mapper = mapper;
        this.logger = logger;
    }

    public void pullAsync(Plugin plugin, UUID playerId,
                          RepoRecord repo, BranchRecord branch,
                          RemoteRecord remote, String accessToken,
                          Database database, CommitDao commitDao, CommitGitShaDao shaDao,
                          HeadDao headDao, GitRepoManager gitRepoManager,
                          Path schematicsDir, SelectionManager selectionManager,
                          BlockVector3 pasteOrigin) {

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                doPull(plugin, playerId, repo, branch, remote, accessToken,
                        database, commitDao, shaDao, headDao, gitRepoManager, schematicsDir,
                        selectionManager, pasteOrigin);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Pull failed", e);
                send(plugin, playerId, String.format(Messages.PULL_FAILED, safe(e.getMessage())));
            }
        });
    }

    private void doPull(Plugin plugin, UUID playerId,
                        RepoRecord repo, BranchRecord branch,
                        RemoteRecord remote, String accessToken,
                        Database database, CommitDao commitDao, CommitGitShaDao shaDao,
                        HeadDao headDao, GitRepoManager gitRepoManager,
                        Path schematicsDir, SelectionManager selectionManager,
                        BlockVector3 pasteOrigin) throws Exception {

        send(plugin, playerId, String.format(Messages.PULL_IN_PROGRESS, remote.name()));

        try (Repository jgitRepo = gitRepoManager.openOrInit(repo.ownerUuid(), repo.name());
             Git git = new Git(jgitRepo)) {

            gitRepoManager.setRemoteUrl(jgitRepo, remote.name(), remote.url());
            git.fetch()
                    .setRemote(remote.url())
                    .setCredentialsProvider(GitRepoManager.credentials(accessToken))
                    .call();

            // Find the tip of the remote branch
            ObjectId remoteTip = jgitRepo.resolve("refs/remotes/" + remote.name() + "/" + branch.name());
            if (remoteTip == null) {
                send(plugin, playerId, Messages.PULL_NOTHING_TO_PULL);
                return;
            }

            // Find the newest SHA we already know about for this remote+branch
            Optional<String> knownTipSha = shaDao.findLatestShaForBranchAndRemote(branch.id(), remote.id());

            // Pre-seed the sha→localId map from DB (covers commits from previous pulls)
            Map<String, Long> shaToLocal = shaDao.findAllShasForRemote(remote.id());

            // Collect new commits oldest-first (walk from tip back to the known boundary)
            List<RevCommit> newCommits = new ArrayList<>();
            try (RevWalk rw = new RevWalk(jgitRepo)) {
                rw.markStart(rw.parseCommit(remoteTip));
                if (knownTipSha.isPresent()) {
                    ObjectId boundary = jgitRepo.resolve(knownTipSha.get());
                    if (boundary != null) rw.markUninteresting(rw.parseCommit(boundary));
                }
                for (RevCommit rc : rw) {
                    if (knownTipSha.isPresent() && rc.name().equals(knownTipSha.get())) break;
                    newCommits.add(rc);
                }
            }

            if (newCommits.isEmpty()) {
                send(plugin, playerId, Messages.PULL_NOTHING_TO_PULL);
                return;
            }

            // Process oldest first
            List<RevCommit> ordered = newCommits.reversed();

            // All DB writes in a single transaction
            Connection conn = database.connection();
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            CommitRecord lastRecord = null;
            Path lastSchemPath = null;

            try (RevWalk rw = new RevWalk(jgitRepo)) {
                for (RevCommit rc : ordered) {
                    rw.parseBody(rc);

                    Map<String, String> meta = mapper.readMetadataFromCommit(jgitRepo, rc, rw);
                    UUID playerUuid = parseUuid(meta.get("player_uuid"), playerId);
                    String playerName  = orDefault(meta.get("player_name"), "unknown");
                    String message     = meta.get("message");
                    String worldName   = orDefault(meta.get("world_name"), "world");
                    UUID worldUuid     = resolveWorldUuid(worldName, parseUuid(meta.get("world_uuid"), null));
                    long createdAt     = parseLong(meta.get("created_at"), System.currentTimeMillis());
                    int minX = parseInt(meta.get("min_x")); int minY = parseInt(meta.get("min_y")); int minZ = parseInt(meta.get("min_z"));
                    int maxX = parseInt(meta.get("max_x")); int maxY = parseInt(meta.get("max_y")); int maxZ = parseInt(meta.get("max_z"));

                    // Resolve parents from Git DAG, not from JSON integers
                    Long parentLocalId = null;
                    Long mergeParentLocalId = null;
                    RevCommit[] parents = rc.getParents();
                    if (parents.length > 0) parentLocalId = shaToLocal.get(parents[0].name());
                    if (parents.length > 1) mergeParentLocalId = shaToLocal.get(parents[1].name());

                    String schemInTree = meta.get(CommitMapper.SCHEM_PATH_KEY);
                    if (schemInTree == null || schemInTree.isBlank()) {
                        throw new IOException("Missing schem_path in gitcraft.json for " + rc.name());
                    }
                    Path targetSchemPath = schematicsDir
                            .resolve(String.valueOf(branch.id()))
                            .resolve(UUID.randomUUID() + ".schem");

                    mapper.extractSchematic(jgitRepo, rc, rw, schemInTree, targetSchemPath);

                    String schemPathStr = targetSchemPath != null ? targetSchemPath.toString() : "";

                    CommitRecord record = new CommitRecord(
                            null, parentLocalId, mergeParentLocalId, null,
                            branch.id(), playerUuid != null ? playerUuid : playerId,
                            playerName, message, schemPathStr, createdAt,
                            worldUuid, worldName, minX, minY, minZ, maxX, maxY, maxZ);

                    long newLocalId = commitDao.insert(record);
                    shaDao.insert(newLocalId, remote.id(), rc.name());
                    shaToLocal.put(rc.name(), newLocalId);

                    lastRecord = new CommitRecord(
                            newLocalId, parentLocalId, mergeParentLocalId, null,
                            branch.id(), record.playerUuid(), playerName, message,
                            schemPathStr, createdAt, worldUuid, worldName,
                            minX, minY, minZ, maxX, maxY, maxZ);
                    lastSchemPath = targetSchemPath;
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(wasAutoCommit);
            }

            // Update HEAD
            if (lastRecord != null) {
                headDao.upsert(new HeadRecord(playerId, repo.id(), branch.id(), lastRecord.id()));
            }

            final int count = ordered.size();
            final CommitRecord finalRecord = lastRecord;
            final Path finalSchemPath = lastSchemPath;
            final BlockVector3 finalPasteOrigin = pasteOrigin;

            // Restore latest schematic on main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(playerId);
                if (p == null || !p.isOnline()) return;

                if (finalRecord != null && finalSchemPath != null) {
                    pasteSchematic(p, finalRecord, finalSchemPath, selectionManager, finalPasteOrigin);
                }
                p.sendMessage(String.format(Messages.PULL_SUCCESS, count, remote.name(), branch.name()));
            });
        }
    }

    // Paste schematic on main thread (same pattern as CheckoutSubcommand)
    private void pasteSchematic(Player p, CommitRecord record, Path schemPath,
                                SelectionManager selectionManager, BlockVector3 pasteOrigin) {
        World world = Bukkit.getWorld(record.worldUuid());
        if (world == null) world = Bukkit.getWorld(record.worldName());
        if (world == null) return;

        Clipboard clipboard;
        try {
            clipboard = ClipboardLoader.load(schemPath);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to load schematic for pull paste", e);
            return;
        }

        BlockVector3 to = pasteOrigin != null
                ? pasteOrigin
                : BlockVector3.at(record.minX(), record.minY(), record.minZ());
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

            Selection sel = selectionManager.getOrCreate(p.getUniqueId());
            if (pasteOrigin != null) {
                int dx = record.maxX() - record.minX();
                int dy = record.maxY() - record.minY();
                int dz = record.maxZ() - record.minZ();
                sel.setPos1(world, pasteOrigin);
                sel.setPos2(world, BlockVector3.at(pasteOrigin.getX() + dx, pasteOrigin.getY() + dy, pasteOrigin.getZ() + dz));
            } else {
                sel.setPos1(world, BlockVector3.at(record.minX(), record.minY(), record.minZ()));
                sel.setPos2(world, BlockVector3.at(record.maxX(), record.maxY(), record.maxZ()));
            }
        } catch (WorldEditException e) {
            logger.log(Level.WARNING, "Pull paste failed", e);
        }
    }

    // ---- helpers ----
    private static UUID resolveWorldUuid(String worldName, UUID sourceUuid) {
        World w = Bukkit.getWorld(worldName);
        if (w != null) return w.getUID();
        return sourceUuid != null ? sourceUuid : UUID.randomUUID();
    }

    private static UUID parseUuid(String s, UUID fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return UUID.fromString(s); } catch (Exception e) { return fallback; }
    }
    private static long parseLong(String s, long fallback) {
        if (s == null) return fallback;
        try { return Long.parseLong(s); } catch (Exception e) { return fallback; }
    }
    private static int parseInt(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
    private static String orDefault(String s, String def) { return (s == null || s.isBlank()) ? def : s; }
    private static void send(Plugin plugin, UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }
    private static String safe(String s) { return s == null ? "(no detail)" : s; }
}
