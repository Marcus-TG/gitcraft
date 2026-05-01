package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class SelectSubcommand implements Subcommand {

    private final GitCraft plugin;
    private final SelectionManager manager;

    public SelectSubcommand(GitCraft plugin, SelectionManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public void execute(Player player, String[] args) {
        manager.enableSelecting(player.getUniqueId());

        Material wand = plugin.gitCraftConfig().wandMaterial();
        if (!player.getInventory().contains(wand)) {
            player.getInventory().addItem(new ItemStack(wand));
            player.sendMessage(Messages.SELECT_WAND_GIVEN);
        }
        player.sendMessage(Messages.SELECT_ENABLED);
    }
}
