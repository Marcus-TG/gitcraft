package com.gitcraft.commands.sub;

import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import org.bukkit.entity.Player;

public final class ClearSubcommand implements Subcommand {

    private final SelectionManager manager;

    public ClearSubcommand(SelectionManager manager) {
        this.manager = manager;
    }

    @Override
    public void execute(Player player, String[] args) {
        manager.clear(player.getUniqueId());
        player.sendMessage(Messages.CLEARED);
    }
}
