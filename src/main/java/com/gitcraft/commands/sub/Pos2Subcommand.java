package com.gitcraft.commands.sub;

import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class Pos2Subcommand implements Subcommand {

    private final SelectionManager manager;

    public Pos2Subcommand(SelectionManager manager) {
        this.manager = manager;
    }

    @Override
    public void execute(Player player, String[] args) {
        Location loc = player.getLocation();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        Selection sel = manager.getOrCreate(player.getUniqueId());
        sel.setPos2(player.getWorld(), BlockVector3.at(x, y, z));
        player.sendMessage(String.format(Messages.POS2_SET, x, y, z));
    }
}
