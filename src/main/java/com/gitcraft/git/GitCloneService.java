package com.gitcraft.git;

import com.gitcraft.database.BranchDao;
import com.gitcraft.database.BranchRecord;
import com.gitcraft.database.CommitDao;
import com.gitcraft.database.CommitGitShaDao;
import com.gitcraft.database.CommitRecord;
import com.gitcraft.database.Database;
import com.gitcraft.database.HeadDao;
import com.gitcraft.database.HeadRecord;
import com.gitcraft.database.RemoteDao;
import com.gitcraft.database.RemoteRecord;
import com.gitcraft.database.RepoDao;
import com.gitcraft.database.RepoRecord;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.BranchConstants;
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
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GitCloneService {

    private final CommitMapper mapper;
    private final Logger logger;

    public GitCloneService(CommitMapper mapper, Logger logger) {
        this.mapper = mapper;
        this.logger = logger;
    }

    public void cloneAsync(Plugin plugin, UUID playerId,
                           String remoteUrl, String localRepoName, String accessToken,
                           Database database, RepoDao repoDao, BranchDao branchDao,
                           CommitDao commitDao, HeadDao headDao,
                           CommitGitShaDao shaDao, RemoteDao remoteDao,
                           GitRepoManager gitRepoManager, Path schematicsDir,
                           SelectionManager selectionManager, BlockVector3 pasteOrigin) {

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                doClone(plugin, playerId, remoteUrl, localRepoName, accessToken,
                        database, repoDao, branchDao, commitDao, headDao, shaDao, remoteDao,
                        gitRepoManager, schematicsDir, selectionManager, pasteOrigin);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Clone failed", e);
                send(plugin, playerId, String.format(Messages.CLONE_FAILED, safe(e.getMessage())));
            }
        });
    }

    private void doClone(Plugin plugin, UUID playerId,
                         String remoteUrl, String localRepoName, String accessToken,
                         Database database, RepoDao repoDao, BranchDao branchDao,
                         CommitDao commitDao, HeadDao headDao,
                         CommitGitShaDao shaDao, RemoteDao remoteDao,
                         GitRepoManager gitRepoManager, Path schematicsDir,
                         SelectionManager selectionManager, BlockVector3 pasteOrigin) throws Exception {

        // Reject if a stale git working tree exists for this repo name
        Path workDir = gitRepoManager.workingTreePath(playerId, localRepoName);
        if (Files.exists(workDir.resolve(".git"))) {
            send(plugin, playerId,
                    String.format(Messages.CLONE_GIT_DIR_EXISTS, localRepoName, localRepoName));
            return;
        }

        send(plugin, playerId, String.format(Messages.CLONE_IN_PROGRESS, remoteUrl));

        Files.createDirectories(workDir);

        Git git = Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(workDir.toFile())
                .setCredentialsProvider(GitRepoManager.credentials(accessToken))
                .call();

        Repository jgitRepo = git.getRepository();

        // Determine remote branches
        List<Ref> remoteBranches = git.branchList()
                .setListMode(ListBranchCommand.ListMode.REMOTE)
                .call();

        // All DB writes in one transaction — roll back and delete working tree on failure
        Connection conn = database.connection();
        boolean wasAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);

        long repoId, remoteId;
        CommitRecord lastDefaultBranchRecord = null;
        Path lastDefaultSchemPath = null;
        long defaultBranchId = -1;

        try {
            long now = System.currentTimeMillis();
            repoId = repoDao.insert(new RepoRecord(null, playerId, localRepoName, now));
            remoteId = remoteDao.insert(new RemoteRecord(null, repoId, "origin", remoteUrl));
            gitRepoManager.setRemoteUrl(jgitRepo, "origin", remoteUrl);

            // Shared sha→localId map across all branches
            Map<String, Long> shaToLocal = new HashMap<>();

            // Process default branch first so its commits are in the map for cross-branch lookups
            String defaultBranchName = guessDefaultBranch(jgitRepo, remoteBranches);
            List<String> branchNames = orderedBranchNames(remoteBranches, defaultBranchName);

            int totalCommits = 0;

            for (String branchName : branchNames) {
                long branchId = branchDao.insert(
                        new BranchRecord(null, repoId, branchName, now, null));

                if (branchName.equals(defaultBranchName)) defaultBranchId = branchId;

                org.eclipse.jgit.lib.ObjectId branchTip =
                        jgitRepo.resolve("refs/remotes/origin/" + branchName);
                if (branchTip == null) continue;

                List<RevCommit> commits = new ArrayList<>();
                try (RevWalk rw = new RevWalk(jgitRepo)) {
                    rw.markStart(rw.parseCommit(branchTip));
                    for (RevCommit rc : rw) {
                        if (shaToLocal.containsKey(rc.name())) break; // already imported via another branch
                        commits.add(rc);
                    }
                }

                // Process oldest first
                List<RevCommit> ordered = commits.reversed();

                try (RevWalk rw = new RevWalk(jgitRepo)) {
                    for (RevCommit rc : ordered) {
                        rw.parseBody(rc);

                        Map<String, String> meta = mapper.readMetadataFromCommit(jgitRepo, rc, rw);
                        UUID playerUuid = parseUuid(meta.get("player_uuid"), playerId);
                        String playerName = orDefault(meta.get("player_name"), "unknown");
                        String message    = meta.get("message");
                        String worldName  = orDefault(meta.get("world_name"), "world");
                        UUID worldUuid    = resolveWorldUuid(worldName, parseUuid(meta.get("world_uuid"), null));
                        long createdAt    = parseLong(meta.get("created_at"), now);
                        int minX = parseInt(meta.get("min_x")); int minY = parseInt(meta.get("min_y")); int minZ = parseInt(meta.get("min_z"));
                        int maxX = parseInt(meta.get("max_x")); int maxY = parseInt(meta.get("max_y")); int maxZ = parseInt(meta.get("max_z"));

                        Long parentLocalId = null;
                        Long mergeParentLocalId = null;
                        RevCommit[] parents = rc.getParents();
                        if (parents.length > 0) parentLocalId = shaToLocal.get(parents[0].name());
                        if (parents.length > 1) mergeParentLocalId = shaToLocal.get(parents[1].name());

                        String schemInTree = meta.get(CommitMapper.SCHEM_PATH_KEY);
                        if (schemInTree == null || schemInTree.isBlank()) {
                            throw new IOException("Missing schem_path in gitcraft.json for " + rc.name());
                        }
                        Path targetSchemPath = schematicsDir.resolve(String.valueOf(branchId))
                                .resolve(UUID.randomUUID() + ".schem");

                        mapper.extractSchematic(jgitRepo, rc, rw, schemInTree, targetSchemPath);

                        String schemPathStr = targetSchemPath != null ? targetSchemPath.toString() : "";

                        CommitRecord record = new CommitRecord(
                                null, parentLocalId, mergeParentLocalId, null,
                                branchId, playerUuid != null ? playerUuid : playerId,
                                playerName, message, schemPathStr, createdAt,
                                worldUuid, worldName, minX, minY, minZ, maxX, maxY, maxZ);

                        long newLocalId = commitDao.insert(record);
                        shaDao.insert(newLocalId, remoteId, rc.name());
                        shaToLocal.put(rc.name(), newLocalId);
                        totalCommits++;

                        if (branchName.equals(defaultBranchName)) {
                            lastDefaultBranchRecord = new CommitRecord(
                                    newLocalId, parentLocalId, mergeParentLocalId, null,
                                    branchId, record.playerUuid(), playerName, message,
                                    schemPathStr, createdAt, worldUuid, worldName,
                                    minX, minY, minZ, maxX, maxY, maxZ);
                            lastDefaultSchemPath = targetSchemPath;
                        }
                    }
                }
            }

            // Point HEAD at the tip of the default branch
            Long headCommitId = lastDefaultBranchRecord != null ? lastDefaultBranchRecord.id() : null;
            if (defaultBranchId >= 0) {
                headDao.upsert(new HeadRecord(playerId, repoId, defaultBranchId, headCommitId));
            }

            conn.commit();

            final long finalRepoId = repoId;
            final long finalBranchId = defaultBranchId;
            final String finalDefaultBranch = defaultBranchName;
            final CommitRecord finalRecord = lastDefaultBranchRecord;
            final Path finalSchemPath = lastDefaultSchemPath;
            final int finalCount = totalCommits;
            final BlockVector3 finalPasteOrigin = pasteOrigin;

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(playerId);
                if (p == null || !p.isOnline()) return;

                // Set player selection to the cloned repo
                Selection sel = selectionManager.getOrCreate(playerId);
                sel.setRepoId(finalRepoId);
                sel.setRepoName(localRepoName);
                sel.setBranchId(finalBranchId);
                sel.setBranchName(finalDefaultBranch);

                if (finalRecord != null && finalSchemPath != null) {
                    pasteSchematic(p, finalRecord, finalSchemPath, selectionManager, finalPasteOrigin);
                }

                p.sendMessage(String.format(Messages.CLONE_SUCCESS, localRepoName, finalCount));
            });

        } catch (Exception e) {
            conn.rollback();
            // Clean up the cloned git directory since the import failed
            try { deleteDirectory(workDir); } catch (IOException ignored) {}
            throw e;
        } finally {
            conn.setAutoCommit(wasAutoCommit);
            git.close();
        }
    }

    private void pasteSchematic(Player p, CommitRecord record, Path schemPath,
                                SelectionManager selectionManager, BlockVector3 pasteOrigin) {
        World world = Bukkit.getWorld(record.worldUuid());
        if (world == null) world = Bukkit.getWorld(record.worldName());
        if (world == null) return;

        Clipboard clipboard;
        try {
            clipboard = ClipboardLoader.load(schemPath);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to load schematic for clone paste", e);
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
            logger.log(Level.WARNING, "Clone paste failed", e);
        }
    }

    private String guessDefaultBranch(Repository repo, List<Ref> remoteBranches) throws IOException {
        // Try HEAD symbolic ref first
        org.eclipse.jgit.lib.Ref headRef = repo.findRef("refs/remotes/origin/HEAD");
        if (headRef != null && headRef.getTarget() != null) {
            String target = headRef.getTarget().getName();
            String prefix = "refs/remotes/origin/";
            if (target.startsWith(prefix)) return target.substring(prefix.length());
        }
        // Fallback: prefer "main", then "master", then first in list
        for (String preferred : new String[]{BranchConstants.DEFAULT_BRANCH, "master"}) {
            for (Ref r : remoteBranches) {
                if (r.getName().endsWith("/" + preferred)) return preferred;
            }
        }
        if (!remoteBranches.isEmpty()) {
            String name = remoteBranches.get(0).getName();
            int slash = name.lastIndexOf('/');
            return slash >= 0 ? name.substring(slash + 1) : name;
        }
        return BranchConstants.DEFAULT_BRANCH;
    }

    private List<String> orderedBranchNames(List<Ref> remoteBranches, String defaultBranchName) {
        List<String> names = new ArrayList<>();
        names.add(defaultBranchName); // default first
        for (Ref r : remoteBranches) {
            String name = r.getName();
            int slash = name.lastIndexOf('/');
            String branchName = slash >= 0 ? name.substring(slash + 1) : name;
            if (!branchName.equals(defaultBranchName) && !branchName.equals("HEAD")) {
                names.add(branchName);
            }
        }
        return names;
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
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
