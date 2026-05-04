package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.database.BranchDao;
import com.gitcraft.database.BranchRecord;
import com.gitcraft.database.CommitDao;
import com.gitcraft.database.HeadDao;
import com.gitcraft.database.HeadRecord;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;
import com.gitcraft.database.CommitRecord;

public final class BranchSubcommand implements Subcommand {

    private static final String PERMISSION = "gitcraft.branch";
    private static final Pattern VALID_NAME = Pattern.compile("[a-zA-Z0-9_\\-]+");
    private static final int MAX_NAME_LEN = 64;
    private static final int LIST_MESSAGE_PREVIEW = 40;

    private final GitCraft plugin;
    private final SelectionManager manager;
    private final CommitDao commitDao;
    private final BranchDao branchDao;
    private final HeadDao headDao;

    public BranchSubcommand(GitCraft plugin, SelectionManager manager,
                             CommitDao commitDao, BranchDao branchDao, HeadDao headDao) {
        this.plugin = plugin;
        this.manager = manager;
        this.commitDao = commitDao;
        this.branchDao = branchDao;
        this.headDao = headDao;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Messages.NO_PERMISSION);
            return;
        }
        Selection sel = manager.get(player.getUniqueId()).orElse(null);
        if (sel == null) {
            player.sendMessage(Messages.NO_SELECTION);
            return;
        }
        Long repoId = sel.repoId();
        if (repoId == null) {
            player.sendMessage(Messages.BRANCH_NO_REPO);
            return;
        }

        if (args.length < 1) {
            UUID pid = player.getUniqueId();
            long repoIdL = repoId;
            String repoName = sel.repoName();
            Long currentBranchId = sel.branchId();
            Bukkit.getScheduler().runTaskAsynchronously(plugin,
                    () -> listBranches(pid, repoIdL, repoName, currentBranchId));
            return;
        }

        String branchName = args[0];
        if (branchName.isEmpty() || branchName.length() > MAX_NAME_LEN
                || !VALID_NAME.matcher(branchName).matches()) {
            player.sendMessage(Messages.BRANCH_INVALID_NAME);
            return;
        }

        String currentBranchName = sel.branchName();
        if (branchName.equals(currentBranchName)) {
            player.sendMessage(String.format(Messages.BRANCH_ALREADY_ON, branchName));
            return;
        }

        UUID playerId = player.getUniqueId();
        long repoIdSnap = repoId;
        Long currentBranchId = sel.branchId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> createBranch(playerId, repoIdSnap, branchName, currentBranchId));
    }

    private void createBranch(UUID playerId, long repoId, String branchName, Long currentBranchId) {
        try {
            Long forkCommitId = null;
            if (currentBranchId != null) {
                forkCommitId = commitDao.findLatestIdByBranch(currentBranchId).orElse(null);
            }

            Optional<BranchRecord> existing = branchDao.findByRepoAndName(repoId, branchName);
            if (existing.isPresent()) {
                sendOnMain(playerId, String.format(Messages.BRANCH_NAME_TAKEN, branchName));
                return;
            }

            long newBranchId = branchDao.insert(
                    new BranchRecord(null, repoId, branchName, System.currentTimeMillis(), forkCommitId));
            headDao.upsert(new HeadRecord(playerId, repoId, newBranchId, null));

            final long branchIdSnap = newBranchId;
            Bukkit.getScheduler().runTask(plugin,
                    () -> applyOnMain(playerId, branchIdSnap, branchName));

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Branch creation failed", e);
            sendOnMain(playerId, Messages.BRANCH_DB_ERROR);
        }
    }

    private void applyOnMain(UUID playerId, long newBranchId, String branchName) {
        Player p = Bukkit.getPlayer(playerId);
        if (p == null || !p.isOnline()) return;

        Selection sel = manager.getOrCreate(playerId);
        sel.setBranchId(newBranchId);
        sel.setBranchName(branchName);
        p.sendMessage(String.format(Messages.BRANCH_CREATED, branchName));
        plugin.getLogger().info("Branch '" + branchName + "' created by " + playerId);
    }

    private void listBranches(UUID playerId, long repoId, String repoName, Long currentBranchId) {
        try {
            List<BranchRecord> branches = branchDao.findByRepo(repoId);
            List<String> rows = new ArrayList<>();
            for (BranchRecord b : branches) {
                List<CommitRecord> latest = commitDao.findByBranch(b.id(), 1, 0);
                String msg;
                if (latest.isEmpty()) {
                    msg = Messages.BRANCH_LIST_NO_COMMITS;
                } else {
                    String m = latest.get(0).message();
                    if (m == null) m = "";
                    if (m.length() > LIST_MESSAGE_PREVIEW) {
                        m = m.substring(0, LIST_MESSAGE_PREVIEW - 1) + "…";
                    }
                    msg = m;
                }
                String marker = (currentBranchId != null && currentBranchId == b.id()) ? "*" : " ";
                rows.add(String.format(Messages.BRANCH_LIST_ROW, marker, b.name(), msg));
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(playerId);
                if (p == null || !p.isOnline()) return;
                if (rows.isEmpty()) {
                    p.sendMessage(Messages.BRANCH_LIST_EMPTY);
                    return;
                }
                p.sendMessage(String.format(Messages.BRANCH_LIST_HEADER,
                        repoName == null ? ("#" + repoId) : repoName));
                for (String row : rows) p.sendMessage(row);
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Branch list failed", e);
            sendOnMain(playerId, String.format(Messages.BRANCH_LIST_DB_ERROR,
                    e.getMessage() == null ? "(no detail)" : e.getMessage()));
        }
    }

    private void sendOnMain(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }
}
