package com.gitcraft.listeners;

import com.gitcraft.GitCraft;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class WandListener implements Listener {

    private final GitCraft plugin;
    private final SelectionManager manager;

    public WandListener(GitCraft plugin, SelectionManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        // Main hand only — ignore off-hand mirrored events.
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        if (!manager.isSelecting(player.getUniqueId())) return;

        Material wand = plugin.gitCraftConfig().wandMaterial();
        if (event.getMaterial() != wand) return;

        Block block = event.getClickedBlock();
        if (block == null) return;

        Action action = event.getAction();
        BlockVector3 v = BlockVector3.at(block.getX(), block.getY(), block.getZ());
        Selection sel = manager.getOrCreate(player.getUniqueId());

        if (action == Action.LEFT_CLICK_BLOCK) {
            sel.setPos1(block.getWorld(), v);
            player.sendMessage(String.format(Messages.POS1_SET, block.getX(), block.getY(), block.getZ()));
            // Cancel so left-click doesn't break the block in survival.
            event.setCancelled(true);
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            sel.setPos2(block.getWorld(), v);
            player.sendMessage(String.format(Messages.POS2_SET, block.getX(), block.getY(), block.getZ()));
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.clear(event.getPlayer().getUniqueId());
    }
}
