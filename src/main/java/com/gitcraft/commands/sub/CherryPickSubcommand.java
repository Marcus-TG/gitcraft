package com.gitcraft.commands.sub;

import com.gitcraft.merge.CherryPickService;
import com.gitcraft.merge.CherryPickService.Side;
import com.gitcraft.util.Messages;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CherryPickSubcommand implements Subcommand {

    private static final String PERMISSION = "gitcraft.cherrypick";

    private final CherryPickService cherryPickService;

    public CherryPickSubcommand(CherryPickService cherryPickService) {
        this.cherryPickService = cherryPickService;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Messages.NO_PERMISSION);
            return;
        }
        if (args.length == 0) {
            player.sendMessage(Messages.CHERRYPICK_USAGE);
            return;
        }
        String head = args[0].toLowerCase(Locale.ROOT);
        switch (head) {
            case "abort"    -> cherryPickService.abort(player);
            case "status"   -> cherryPickService.status(player);
            case "continue" -> {
                String msg = args.length >= 2 ? joinFrom(args, 1) : null;
                cherryPickService.continueCherryPick(player, msg);
            }
            case "accept" -> {
                if (args.length < 2) {
                    player.sendMessage(Messages.CHERRYPICK_ACCEPT_USAGE);
                    return;
                }
                String sideArg = args[1].toLowerCase(Locale.ROOT);
                Side side = switch (sideArg) {
                    case "ours"   -> Side.OURS;
                    case "theirs" -> Side.THEIRS;
                    default       -> null;
                };
                if (side == null) {
                    player.sendMessage(Messages.CHERRYPICK_ACCEPT_USAGE);
                    return;
                }
                cherryPickService.accept(player, side);
            }
            default -> {
                if (args.length != 1) {
                    player.sendMessage(Messages.CHERRYPICK_USAGE);
                    return;
                }
                long commitId;
                try {
                    commitId = Long.parseLong(args[0]);
                } catch (NumberFormatException e) {
                    player.sendMessage(Messages.CHERRYPICK_INVALID_ID);
                    return;
                }
                if (commitId <= 0) {
                    player.sendMessage(Messages.CHERRYPICK_INVALID_ID);
                    return;
                }
                cherryPickService.startCherryPick(player, commitId);
            }
        }
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> all = List.of("abort", "accept", "continue", "status");
            List<String> out = new ArrayList<>();
            for (String s : all) if (s.startsWith(prefix)) out.add(s);
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

    private static String joinFrom(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
