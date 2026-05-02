package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.database.BranchDao;
import com.gitcraft.database.BranchRecord;
import com.gitcraft.database.HeadDao;
import com.gitcraft.database.HeadRecord;
import com.gitcraft.database.RepoDao;
import com.gitcraft.database.RepoRecord;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.BranchConstants;
import com.gitcraft.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class InitSubcommand implements Subcommand {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final GitCraft plugin;
    private final SelectionManager manager;
    private final RepoDao repoDao;
    private final BranchDao branchDao;
    private final HeadDao headDao;

    public InitSubcommand(GitCraft plugin, SelectionManager manager,
                          RepoDao repoDao, BranchDao branchDao, HeadDao headDao) {
        this.plugin = plugin;
        this.manager = manager;
        this.repoDao = repoDao;
        this.branchDao = branchDao;
        this.headDao = headDao;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(Messages.INIT_USAGE);
            return;
        }
        String name = args[0];
        if (!NAME_PATTERN.matcher(name).matches()) {
            player.sendMessage(Messages.SELECT_INVALID_NAME);
            return;
        }

        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> initAsync(playerId, name));
    }

    private void initAsync(UUID playerId, String name) {
        try {
            if (repoDao.findByOwnerAndName(playerId, name).isPresent()) {
                sendOnMain(playerId, String.format(Messages.INIT_REPO_EXISTS, name, name));
                return;
            }

            long now = System.currentTimeMillis();
            long repoId   = repoDao.insert(new RepoRecord(null, playerId, name, now));
            long branchId = branchDao.insert(new BranchRecord(null, repoId, BranchConstants.DEFAULT_BRANCH, now));
            headDao.upsert(new HeadRecord(playerId, repoId, branchId));

            Bukkit.getScheduler().runTask(plugin, () -> applyOnMain(playerId, name, repoId, branchId));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Init DB failed", e);
            sendOnMain(playerId, String.format(Messages.INIT_DB_FAILED, safe(e.getMessage())));
        }
    }

    private void applyOnMain(UUID playerId, String repoName, long repoId, long branchId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;

        Selection selection = manager.getOrCreate(playerId);
        selection.setRepoId(repoId);
        selection.setRepoName(repoName);
        selection.setBranchId(branchId);
        selection.setBranchName(BranchConstants.DEFAULT_BRANCH);

        manager.enableSelecting(playerId);

        Material wand = plugin.gitCraftConfig().wandMaterial();
        if (!player.getInventory().contains(wand)) {
            player.getInventory().addItem(new ItemStack(wand));
            player.sendMessage(Messages.SELECT_WAND_GIVEN);
        }
        player.sendMessage(String.format(Messages.INIT_REPO_CREATED, repoName));
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
