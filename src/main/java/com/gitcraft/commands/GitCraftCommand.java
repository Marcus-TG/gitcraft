package com.gitcraft.commands;

import com.gitcraft.GitCraft;
import com.gitcraft.commands.sub.BranchSubcommand;
import com.gitcraft.commands.sub.CheckoutSubcommand;
import com.gitcraft.commands.sub.CherryPickSubcommand;
import com.gitcraft.commands.sub.ClearSubcommand;
import com.gitcraft.commands.sub.CloneSubcommand;
import com.gitcraft.commands.sub.CommitSubcommand;
import com.gitcraft.commands.sub.DiffSubcommand;
import com.gitcraft.commands.sub.InitSubcommand;
import com.gitcraft.commands.sub.LogSubcommand;
import com.gitcraft.commands.sub.LoginSubcommand;
import com.gitcraft.commands.sub.LogoutSubcommand;
import com.gitcraft.commands.sub.MergeSubcommand;
import com.gitcraft.commands.sub.OpenSubcommand;
import com.gitcraft.commands.sub.Pos1Subcommand;
import com.gitcraft.commands.sub.Pos2Subcommand;
import com.gitcraft.commands.sub.PullSubcommand;
import com.gitcraft.commands.sub.PushSubcommand;
import com.gitcraft.commands.sub.RemoteSubcommand;
import com.gitcraft.commands.sub.ReposSubcommand;
import com.gitcraft.commands.sub.ResetSubcommand;
import com.gitcraft.commands.sub.StashSubcommand;
import com.gitcraft.commands.sub.Subcommand;
import com.gitcraft.commit.CommitService;
import com.gitcraft.config.GitCraftConfig;
import com.gitcraft.database.BranchDao;
import com.gitcraft.database.CommitDao;
import com.gitcraft.database.CommitGitShaDao;
import com.gitcraft.database.Database;
import com.gitcraft.database.GitHubTokenDao;
import com.gitcraft.database.HeadDao;
import com.gitcraft.database.RemoteDao;
import com.gitcraft.database.RepoDao;
import com.gitcraft.database.StashDao;
import com.gitcraft.diff.DiffService;
import com.gitcraft.diff.GhostBlockManager;
import com.gitcraft.git.GitCloneService;
import com.gitcraft.git.GitPullService;
import com.gitcraft.git.GitPushService;
import com.gitcraft.git.GitRepoManager;
import com.gitcraft.github.GitHubAuthService;
import com.gitcraft.merge.CherryPickService;
import com.gitcraft.merge.MergeService;
import com.gitcraft.merge.OpManager;
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

    public GitCraftCommand(GitCraft plugin, SelectionManager manager, CommitService commitService,
                           CommitDao commitDao, RepoDao repoDao, BranchDao branchDao, HeadDao headDao,
                           StashDao stashDao,
                           DiffService diffService, GhostBlockManager ghostBlockManager,
                           MergeService mergeService, CherryPickService cherryPickService,
                           OpManager opManager, Database database,
                           GitHubTokenDao tokenDao, RemoteDao remoteDao, CommitGitShaDao shaDao,
                           GitPushService pushService, GitPullService pullService, GitCloneService cloneService,
                           GitHubAuthService authService, GitRepoManager gitRepoManager,
                           GitCraftConfig config) {
        subs.put("init",     new InitSubcommand(plugin, manager, repoDao, branchDao, headDao));
        subs.put("open",     new OpenSubcommand(plugin, manager, commitDao, repoDao, branchDao, headDao,
                ghostBlockManager));
        subs.put("pos1",     new Pos1Subcommand(manager));
        subs.put("pos2",     new Pos2Subcommand(manager));
        subs.put("clear",    new ClearSubcommand(manager));
        subs.put("commit",   new CommitSubcommand(plugin, manager, commitService));
        subs.put("log",      new LogSubcommand(plugin, commitDao, repoDao, branchDao));
        subs.put("reset",    new ResetSubcommand(plugin, manager, commitDao, headDao, ghostBlockManager));
        subs.put("branch",   new BranchSubcommand(plugin, manager, commitDao, branchDao, headDao));
        subs.put("checkout", new CheckoutSubcommand(plugin, manager, commitDao, branchDao, headDao,
                ghostBlockManager));
        subs.put("diff",     new DiffSubcommand(plugin, manager, commitDao, branchDao, repoDao, headDao,
                diffService, ghostBlockManager));
        subs.put("merge",    new MergeSubcommand(plugin, manager, branchDao, mergeService));
        subs.put("cherry-pick", new CherryPickSubcommand(cherryPickService));
        subs.put("repos",    new ReposSubcommand(plugin, repoDao, branchDao));
        subs.put("stash",    new StashSubcommand(plugin, manager, stashDao, branchDao, headDao));
        subs.put("login",    new LoginSubcommand(plugin, config.githubClientId(), authService, tokenDao));
        subs.put("logout",   new LogoutSubcommand(plugin, tokenDao));
        subs.put("remote",   new RemoteSubcommand(plugin, manager, remoteDao, gitRepoManager));
        subs.put("push",     new PushSubcommand(plugin, manager, tokenDao, remoteDao, commitDao, shaDao,
                pushService, gitRepoManager, config.schematicsDir()));
        subs.put("pull",     new PullSubcommand(plugin, manager, opManager, database, tokenDao, remoteDao,
                repoDao, branchDao, commitDao, shaDao, headDao, pullService, gitRepoManager,
                config.schematicsDir()));
        subs.put("clone",    new CloneSubcommand(plugin, manager, database, tokenDao, repoDao, branchDao,
                commitDao, headDao, shaDao, remoteDao, cloneService, gitRepoManager,
                config.schematicsDir()));
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
