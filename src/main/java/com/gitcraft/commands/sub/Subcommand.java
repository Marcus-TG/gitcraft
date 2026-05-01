package com.gitcraft.commands.sub;

import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public interface Subcommand {

    void execute(Player player, String[] args);

    default List<String> tabComplete(Player player, String[] args) {
        return Collections.emptyList();
    }
}
