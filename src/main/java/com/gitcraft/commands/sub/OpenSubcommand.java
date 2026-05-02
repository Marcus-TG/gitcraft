package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.database.BranchDao;
import com.gitcraft.database.BranchRecord;
import com.gitcraft.database.CommitDao;
import com.gitcraft.database.CommitRecord;
import com.gitcraft.database.HeadDao;
import com.gitcraft.database.HeadRecord;
import com.gitcraft.database.RepoDao;
import com.gitcraft.database.RepoRecord;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.BranchConstants;
import com.gitcraft.util.Messages;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class OpenSubcommand implements Subcommand {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final GitCraft plugin;
    private final SelectionManager manager;
    private final CommitDao commitDao;
    private final RepoDao repoDao;
    private final BranchDao branchDao;
    private final HeadDao headDao;

    public OpenSubcommand(GitCraft plugin, SelectionManager manager, CommitDao commitDao,
                          RepoDao repoDao, BranchDao branchDao, HeadDao headDao) {
        this.plugin = plugin;
        this.manager = manager;
        this.commitDao = commitDao;
        this.repoDao = repoDao;
        this.branchDao = branchDao;
        this.headDao = headDao;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(Messages.OPEN_USAGE);
            return;
        }
        String name = args[0];
        if (!NAME_PATTERN.matcher(name).matches()) {
            player.sendMessage(Messages.SELECT_INVALID_NAME);
            return;
        }

        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> lookupAndDispatch(playerId, name));
    }

    private void lookupAndDispatch(UUID playerId, String repoName) {
        try {
            Optional<RepoRecord> repoOpt = repoDao.findByOwnerAndName(playerId, repoName);
            if (repoOpt.isEmpty()) {
                sendOnMain(playerId, String.format(Messages.OPEN_REPO_NOT_FOUND, repoName, repoName));
                return;
            }
            long repoId = repoOpt.get().id();

            Optional<BranchRecord> branchOpt = branchDao.findByRepoAndName(repoId, BranchConstants.DEFAULT_BRANCH);
            if (branchOpt.isEmpty()) {
                // Shouldn't happen — init always creates main
                sendOnMain(playerId, String.format(Messages.OPEN_DB_FAILED, "main branch missing for repo " + repoName));
                return;
            }
            long branchId = branchOpt.get().id();

            // TODO: future schema version should persist bbox in branches/heads so open works without a prior commit
            List<CommitRecord> rows = commitDao.findByBranch(branchId, 1, 0);
            if (rows.isEmpty()) {
                sendOnMain(playerId, String.format(Messages.OPEN_NO_COMMITS, repoName, BranchConstants.DEFAULT_BRANCH));
                return;
            }

            headDao.upsert(new HeadRecord(playerId, repoId, branchId));

            CommitRecord record = rows.get(0);
            Bukkit.getScheduler().runTask(plugin, () -> applyOnMain(playerId, repoName, repoId, branchId, record));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Open lookup failed", e);
            sendOnMain(playerId, String.format(Messages.OPEN_DB_FAILED, safe(e.getMessage())));
        }
    }

    private void applyOnMain(UUID playerId, String repoName, long repoId, long branchId, CommitRecord record) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;

        World world = Bukkit.getWorld(record.worldUuid());
        if (world == null) {
            player.sendMessage(String.format(Messages.OPEN_WORLD_GONE, record.worldName()));
            return;
        }

        Selection selection = manager.getOrCreate(playerId);
        selection.setRepoId(repoId);
        selection.setRepoName(repoName);
        selection.setBranchId(branchId);
        selection.setBranchName(BranchConstants.DEFAULT_BRANCH);
        selection.setPos1(world, BlockVector3.at(record.minX(), record.minY(), record.minZ()));
        selection.setPos2(world, BlockVector3.at(record.maxX(), record.maxY(), record.maxZ()));

        manager.enableSelecting(playerId);

        Material wand = plugin.gitCraftConfig().wandMaterial();
        if (!player.getInventory().contains(wand)) {
            player.getInventory().addItem(new ItemStack(wand));
            player.sendMessage(Messages.SELECT_WAND_GIVEN);
        }

        player.sendMessage(String.format(Messages.OPEN_RESTORED,
                repoName, record.id(),
                record.minX(), record.minY(), record.minZ(),
                record.maxX(), record.maxY(), record.maxZ()));
    }

    private void sendOnMain(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }

    private static String safe(String s) {
        return s == null ? "(no detail)" : s;
    }
}
