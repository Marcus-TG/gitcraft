package com.gitcraft.commands.sub;

import com.gitcraft.util.Messages;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HelpSubcommand implements Subcommand {

    private static final Map<String, String[]> TOPICS = Map.ofEntries(
            Map.entry("init", new String[] {Messages.HELP_INIT_USAGE, Messages.HELP_INIT_DESC}),
            Map.entry("open", new String[] {Messages.HELP_OPEN_USAGE, Messages.HELP_OPEN_DESC}),
            Map.entry("pos1", new String[] {Messages.HELP_POS1_USAGE, Messages.HELP_POS1_DESC}),
            Map.entry("pos2", new String[] {Messages.HELP_POS2_USAGE, Messages.HELP_POS2_DESC}),
            Map.entry("clear", new String[] {Messages.HELP_CLEAR_USAGE, Messages.HELP_CLEAR_DESC}),
            Map.entry("commit", new String[] {Messages.HELP_COMMIT_USAGE, Messages.HELP_COMMIT_DESC}),
            Map.entry("log", new String[] {Messages.HELP_LOG_USAGE, Messages.HELP_LOG_DESC}),
            Map.entry("reset", new String[] {Messages.HELP_RESET_USAGE, Messages.HELP_RESET_DESC}),
            Map.entry("branch", new String[] {Messages.HELP_BRANCH_USAGE, Messages.HELP_BRANCH_DESC}),
            Map.entry("checkout", new String[] {Messages.HELP_CHECKOUT_USAGE, Messages.HELP_CHECKOUT_DESC}),
            Map.entry("diff", new String[] {Messages.HELP_DIFF_USAGE, Messages.HELP_DIFF_DESC}),
            Map.entry("merge", new String[] {Messages.HELP_MERGE_USAGE, Messages.HELP_MERGE_DESC}),
            Map.entry("cherry-pick", new String[] {Messages.HELP_CHERRYPICK_USAGE, Messages.HELP_CHERRYPICK_DESC}),
            Map.entry("stash", new String[] {Messages.HELP_STASH_USAGE, Messages.HELP_STASH_DESC}),
            Map.entry("repos", new String[] {Messages.HELP_REPOS_USAGE, Messages.HELP_REPOS_DESC}),
            Map.entry("login", new String[] {Messages.HELP_LOGIN_USAGE, Messages.HELP_LOGIN_DESC}),
            Map.entry("logout", new String[] {Messages.HELP_LOGOUT_USAGE, Messages.HELP_LOGOUT_DESC}),
            Map.entry("remote", new String[] {Messages.HELP_REMOTE_USAGE, Messages.HELP_REMOTE_DESC}),
            Map.entry("push", new String[] {Messages.HELP_PUSH_USAGE, Messages.HELP_PUSH_DESC}),
            Map.entry("pull", new String[] {Messages.HELP_PULL_USAGE, Messages.HELP_PULL_DESC}),
            Map.entry("clone", new String[] {Messages.HELP_CLONE_USAGE, Messages.HELP_CLONE_DESC})
    );

    @Override
    public void execute(Player player, String[] args) {
        if (args.length == 0) {
            for (String line : Messages.HELP_OVERVIEW) {
                player.sendMessage(line);
            }
            return;
        }

        String topic = normalize(args[0]);
        String[] lines = TOPICS.get(topic);
        if (lines == null) {
            player.sendMessage(String.format(Messages.HELP_UNKNOWN_TOPIC, args[0]));
            return;
        }

        player.sendMessage(lines[0]);
        player.sendMessage(lines[1]);
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length != 1) {
            return List.of();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String topic : TOPICS.keySet()) {
            if (topic.startsWith(prefix)) {
                out.add(topic);
            }
        }
        if ("cherrypick".startsWith(prefix)) {
            out.add("cherrypick");
        }
        return out;
    }

    private String normalize(String topic) {
        String normalized = topic.toLowerCase(Locale.ROOT);
        if ("cherrypick".equals(normalized)) {
            return "cherry-pick";
        }
        return normalized;
    }
}
