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
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class BranchSubcommand implements Subcommand {

    private static final String PERMISSION = "gitcraft.branch";
    private static final Pattern VALID_NAME = Pattern.compile("[a-zA-Z0-9_\\-]+");
    private static final int MAX_NAME_LEN = 64;

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
        if (args.length < 1) {
            player.sendMessage(Messages.BRANCH_USAGE);
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
            headDao.upsert(new HeadRecord(playerId, repoId, newBranchId));

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

    private void sendOnMain(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }
}
