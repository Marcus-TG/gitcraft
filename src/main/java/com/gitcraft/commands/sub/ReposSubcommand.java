package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.config.GitCraftConfig;
import com.gitcraft.database.BranchDao;
import com.gitcraft.database.RepoDao;
import com.gitcraft.database.RepoDao.DeletedRepoInfo;
import com.gitcraft.database.RepoRecord;
import com.gitcraft.merge.OpManager;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class ReposSubcommand implements Subcommand {

    private static final String PERMISSION = "gitcraft.use";
    private static final long CONFIRM_TTL_MS = 30_000L;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private final GitCraft plugin;
    private final RepoDao repoDao;
    private final BranchDao branchDao;
    private final SelectionManager selectionManager;
    private final OpManager opManager;
    private final GitCraftConfig config;

    private final ConcurrentHashMap<UUID, PendingDelete> pending = new ConcurrentHashMap<>();

    private record PendingDelete(String repoName, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    public ReposSubcommand(GitCraft plugin, RepoDao repoDao, BranchDao branchDao,
                           SelectionManager selectionManager, OpManager opManager,
                           GitCraftConfig config) {
        this.plugin = plugin;
        this.repoDao = repoDao;
        this.branchDao = branchDao;
        this.selectionManager = selectionManager;
        this.opManager = opManager;
        this.config = config;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Messages.NO_PERMISSION);
            return;
        }
        if (args.length == 0) {
            UUID playerId = player.getUniqueId();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> listRepos(playerId));
            return;
        }
        if (args[0].equalsIgnoreCase("delete") && args.length >= 2) {
            handleDelete(player, args[1]);
            return;
        }
        player.sendMessage(Messages.HELP_REPOS_USAGE);
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return "delete".startsWith(prefix) ? List.of("delete") : List.of();
        }
        return List.of();
    }

    // ---- list ----

    private void listRepos(UUID playerId) {
        try {
            List<RepoRecord> repos = repoDao.findByOwner(playerId);
            List<String> rows = new ArrayList<>();
            for (RepoRecord r : repos) {
                int branchCount = branchDao.countByRepo(r.id());
                String when = DATE_FORMAT.format(Instant.ofEpochMilli(r.createdAt()));
                rows.add(String.format(Messages.REPOS_ROW, r.name(), branchCount, when));
            }
            int total = repos.size();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(playerId);
                if (p == null || !p.isOnline()) return;
                if (total == 0) {
                    p.sendMessage(Messages.REPOS_EMPTY);
                    return;
                }
                p.sendMessage(String.format(Messages.REPOS_HEADER, total));
                for (String row : rows) p.sendMessage(row);
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Repos list failed", e);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(playerId);
                if (p == null || !p.isOnline()) return;
                p.sendMessage(String.format(Messages.REPOS_DB_ERROR,
                        e.getMessage() == null ? "(no detail)" : e.getMessage()));
            });
        }
    }

    // ---- delete ----

    private void handleDelete(Player player, String repoName) {
        UUID playerId = player.getUniqueId();

        if (opManager.has(playerId)) {
            player.sendMessage(Messages.REPOS_DELETE_BLOCKED_ACTIVE_OP);
            return;
        }

        PendingDelete existing = pending.get(playerId);
        if (existing != null && !existing.isExpired() && existing.repoName().equals(repoName)) {
            pending.remove(playerId);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> executeDelete(playerId, repoName));
            return;
        }

        pending.put(playerId, new PendingDelete(repoName, System.currentTimeMillis() + CONFIRM_TTL_MS));
        player.sendMessage(String.format(Messages.REPOS_DELETE_WARN, repoName));
    }

    private void executeDelete(UUID playerId, String repoName) {
        Optional<DeletedRepoInfo> result;
        try {
            result = repoDao.deleteOwnedRepo(playerId, repoName);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Repo delete failed for " + repoName, e);
            String detail = e.getMessage() == null ? "(no detail)" : e.getMessage();
            sendOnMain(playerId, String.format(Messages.REPOS_DELETE_DB_ERROR, detail));
            return;
        }

        if (result.isEmpty()) {
            sendOnMain(playerId, String.format(Messages.REPOS_DELETE_NOT_FOUND, repoName));
            return;
        }

        DeletedRepoInfo info = result.get();

        // Filesystem cleanup — log but do not re-open the transaction on failure
        List<String> failedPaths = new ArrayList<>();
        for (long branchId : info.branchIds()) {
            Path schemDir = config.schematicsDir().resolve(String.valueOf(branchId));
            try {
                deleteDirectory(schemDir);
            } catch (IOException e) {
                failedPaths.add(schemDir.toString());
                plugin.getLogger().log(Level.WARNING, "Could not delete schematic dir " + schemDir, e);
            }
        }
        Path gitTree = config.gitDir()
                .resolve(info.ownerUuid().toString())
                .resolve(info.repoName());
        try {
            deleteDirectory(gitTree);
        } catch (IOException e) {
            failedPaths.add(gitTree.toString());
            plugin.getLogger().log(Level.WARNING, "Could not delete git working tree " + gitTree, e);
        }

        long deletedRepoId = info.repoId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) {
                if (failedPaths.isEmpty()) {
                    p.sendMessage(String.format(Messages.REPOS_DELETE_SUCCESS, repoName));
                } else {
                    p.sendMessage(String.format(Messages.REPOS_DELETE_FS_WARN,
                            repoName, String.join(", ", failedPaths)));
                }
            }
            // Clear active selection if it points at the deleted repo
            Optional<Selection> sel = selectionManager.get(playerId);
            if (sel.isPresent() && Long.valueOf(deletedRepoId).equals(sel.get().repoId())) {
                selectionManager.clear(playerId);
            }
        });
    }

    private void sendOnMain(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
    }
}
