package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.database.BranchDao;
import com.gitcraft.merge.MergeService;
import com.gitcraft.merge.MergeService.Side;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

public final class MergeSubcommand implements Subcommand {

    private static final String PERMISSION = "gitcraft.merge";

    private final GitCraft plugin;
    private final SelectionManager selectionManager;
    private final BranchDao branchDao;
    private final MergeService mergeService;

    public MergeSubcommand(GitCraft plugin,
                           SelectionManager selectionManager,
                           BranchDao branchDao,
                           MergeService mergeService) {
        this.plugin = plugin;
        this.selectionManager = selectionManager;
        this.branchDao = branchDao;
        this.mergeService = mergeService;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Messages.NO_PERMISSION);
            return;
        }
        if (args.length == 0) {
            player.sendMessage(Messages.MERGE_USAGE);
            return;
        }
        String head = args[0].toLowerCase(Locale.ROOT);
        switch (head) {
            case "abort"    -> mergeService.abort(player);
            case "status"   -> mergeService.status(player);
            case "continue" -> {
                String msg = args.length >= 2 ? joinFrom(args, 1) : null;
                mergeService.continueMerge(player, msg);
            }
            case "accept" -> {
                if (args.length < 2) {
                    player.sendMessage(Messages.MERGE_ACCEPT_USAGE);
                    return;
                }
                String sideArg = args[1].toLowerCase(Locale.ROOT);
                Side side = switch (sideArg) {
                    case "ours"   -> Side.OURS;
                    case "theirs" -> Side.THEIRS;
                    default       -> null;
                };
                if (side == null) {
                    player.sendMessage(Messages.MERGE_ACCEPT_USAGE);
                    return;
                }
                mergeService.accept(player, side);
            }
            default -> {
                if (args.length != 1) {
                    player.sendMessage(Messages.MERGE_USAGE);
                    return;
                }
                mergeService.startMerge(player, args[0]);
            }
        }
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>(suggestSubs(prefix));
            for (String name : branchNamesForPlayer(player)) {
                if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(name);
            }
            return out;
        }
        if (args.length == 2 && "accept".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>(2);
            if ("ours".startsWith(prefix))   out.add("ours");
            if ("theirs".startsWith(prefix)) out.add("theirs");
            return out;
        }
        return List.of();
    }

    private List<String> suggestSubs(String prefix) {
        List<String> all = List.of("abort", "accept", "continue", "status");
        List<String> out = new ArrayList<>();
        for (String s : all) if (s.startsWith(prefix)) out.add(s);
        return out;
    }

    /** Tab completion only — runs on main thread, swallows DB errors silently. */
    private List<String> branchNamesForPlayer(Player player) {
        UUID id = player.getUniqueId();
        Selection sel = selectionManager.get(id).orElse(null);
        if (sel == null || sel.repoId() == null) return List.of();
        try {
            return branchDao.findNamesByRepo(sel.repoId());
        } catch (SQLException e) {
            plugin.getLogger().log(Level.FINE, "Branch tab completion failed", e);
            return List.of();
        }
    }

    private static String joinFrom(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

}
