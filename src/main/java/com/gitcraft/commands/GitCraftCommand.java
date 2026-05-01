package com.gitcraft.commands;

import com.gitcraft.GitCraft;
import com.gitcraft.commands.sub.ClearSubcommand;
import com.gitcraft.commands.sub.CommitSubcommand;
import com.gitcraft.commands.sub.Pos1Subcommand;
import com.gitcraft.commands.sub.Pos2Subcommand;
import com.gitcraft.commands.sub.SelectSubcommand;
import com.gitcraft.commands.sub.Subcommand;
import com.gitcraft.commit.CommitService;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class GitCraftCommand implements CommandExecutor, TabCompleter {

    private final Map<String, Subcommand> subs = new LinkedHashMap<>();

    public GitCraftCommand(GitCraft plugin, SelectionManager manager, CommitService commitService) {
        subs.put("select", new SelectSubcommand(plugin, manager));
        subs.put("pos1",   new Pos1Subcommand(manager));
        subs.put("pos2",   new Pos2Subcommand(manager));
        subs.put("clear",  new ClearSubcommand(manager));
        subs.put("commit", new CommitSubcommand(plugin, manager, commitService));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Messages.PLAYER_ONLY);
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Messages.UNKNOWN_SUBCOMMAND);
            return true;
        }
        Subcommand sub = subs.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null) {
            sender.sendMessage(Messages.UNKNOWN_SUBCOMMAND);
            return true;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        sub.execute(player, rest);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return Collections.emptyList();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return subs.keySet().stream()
                    .filter(k -> k.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        Subcommand sub = subs.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null) return Collections.emptyList();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return sub.tabComplete(player, rest);
    }
}
