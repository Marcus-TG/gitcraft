package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.database.CommitDao;
import com.gitcraft.database.CommitRecord;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class OpenSubcommand implements Subcommand {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final GitCraft plugin;
    private final SelectionManager manager;
    private final CommitDao commitDao;

    public OpenSubcommand(GitCraft plugin, SelectionManager manager, CommitDao commitDao) {
        this.plugin = plugin;
        this.manager = manager;
        this.commitDao = commitDao;
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

    private void lookupAndDispatch(UUID playerId, String name) {
        List<CommitRecord> rows;
        try {
            rows = commitDao.findByRegion(name, 1, 0);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Open lookup failed", e);
            sendOnMain(playerId, String.format(Messages.OPEN_DB_FAILED, safe(e.getMessage())));
            return;
        }

        if (rows.isEmpty()) {
            sendOnMain(playerId, String.format(Messages.OPEN_NOT_FOUND, name));
            return;
        }

        CommitRecord record = rows.get(0);
        Bukkit.getScheduler().runTask(plugin, () -> applyOnMain(playerId, name, record));
    }

    private void applyOnMain(UUID playerId, String name, CommitRecord record) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;

        World world = Bukkit.getWorld(record.worldUuid());
        if (world == null) {
            player.sendMessage(String.format(Messages.OPEN_WORLD_GONE, record.worldName()));
            return;
        }

        Selection selection = manager.getOrCreate(playerId);
        BlockVector3 min = BlockVector3.at(record.minX(), record.minY(), record.minZ());
        BlockVector3 max = BlockVector3.at(record.maxX(), record.maxY(), record.maxZ());
        selection.setPos1(world, min);
        selection.setPos2(world, max);
        selection.setName(name);

        manager.enableSelecting(playerId);

        Material wand = plugin.gitCraftConfig().wandMaterial();
        if (!player.getInventory().contains(wand)) {
            player.getInventory().addItem(new ItemStack(wand));
            player.sendMessage(Messages.SELECT_WAND_GIVEN);
        }

        player.sendMessage(String.format(Messages.OPEN_RESTORED,
                name, record.id(),
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
