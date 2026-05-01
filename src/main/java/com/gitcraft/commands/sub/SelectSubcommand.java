package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.regex.Pattern;

public final class SelectSubcommand implements Subcommand {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final GitCraft plugin;
    private final SelectionManager manager;

    public SelectSubcommand(GitCraft plugin, SelectionManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (args.length >= 1) {
            String name = args[0];
            if (!NAME_PATTERN.matcher(name).matches()) {
                player.sendMessage(Messages.SELECT_INVALID_NAME);
                return;
            }
            manager.getOrCreate(player.getUniqueId()).setName(name);
            player.sendMessage(String.format(Messages.SELECT_NAMED, name));
        }

        manager.enableSelecting(player.getUniqueId());

        Material wand = plugin.gitCraftConfig().wandMaterial();
        if (!player.getInventory().contains(wand)) {
            player.getInventory().addItem(new ItemStack(wand));
            player.sendMessage(Messages.SELECT_WAND_GIVEN);
        }
        player.sendMessage(Messages.SELECT_ENABLED);
    }
}
