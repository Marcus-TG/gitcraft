package com.gitcraft.commands.sub;

import com.gitcraft.database.BranchDao;
import com.gitcraft.database.CommitDao;
import com.gitcraft.database.CommitGitShaDao;
import com.gitcraft.database.Database;
import com.gitcraft.database.GitHubTokenDao;
import com.gitcraft.database.GitHubTokenRecord;
import com.gitcraft.database.HeadDao;
import com.gitcraft.database.RemoteDao;
import com.gitcraft.database.RepoDao;
import com.gitcraft.git.GitCloneService;
import com.gitcraft.git.GitRepoManager;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class CloneSubcommand implements Subcommand {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final Plugin plugin;
    private final SelectionManager selectionManager;
    private final Database database;
    private final GitHubTokenDao tokenDao;
    private final RepoDao repoDao;
    private final BranchDao branchDao;
    private final CommitDao commitDao;
    private final HeadDao headDao;
    private final CommitGitShaDao shaDao;
    private final RemoteDao remoteDao;
    private final GitCloneService cloneService;
    private final GitRepoManager gitRepoManager;
    private final Path schematicsDir;

    public CloneSubcommand(Plugin plugin, SelectionManager selectionManager, Database database,
                           GitHubTokenDao tokenDao, RepoDao repoDao, BranchDao branchDao,
                           CommitDao commitDao, HeadDao headDao, CommitGitShaDao shaDao,
                           RemoteDao remoteDao, GitCloneService cloneService,
                           GitRepoManager gitRepoManager, Path schematicsDir) {
        this.plugin = plugin;
        this.selectionManager = selectionManager;
        this.database = database;
        this.tokenDao = tokenDao;
        this.repoDao = repoDao;
        this.branchDao = branchDao;
        this.commitDao = commitDao;
        this.headDao = headDao;
        this.shaDao = shaDao;
        this.remoteDao = remoteDao;
        this.cloneService = cloneService;
        this.gitRepoManager = gitRepoManager;
        this.schematicsDir = schematicsDir;
    }

    @Override
    public void execute(Player player, String[] args) {
        boolean here = Arrays.asList(args).contains("--here");
        List<String> positional = Arrays.stream(args)
                .filter(a -> !a.equals("--here"))
                .collect(Collectors.toList());

        if (positional.size() < 2) {
            player.sendMessage(Messages.CLONE_USAGE);
            return;
        }

        String url      = positional.get(0);
        String repoName = positional.get(1);
        UUID playerId   = player.getUniqueId();

        BlockVector3 pasteOrigin = here
                ? BlockVector3.at(player.getLocation().getBlockX(),
                                  player.getLocation().getBlockY(),
                                  player.getLocation().getBlockZ())
                : null;

        if (!url.startsWith("https://")) {
            player.sendMessage(Messages.CLONE_INVALID_URL);
            return;
        }
        if (!NAME_PATTERN.matcher(repoName).matches()) {
            player.sendMessage(Messages.CLONE_INVALID_NAME);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                GitHubTokenRecord token = tokenDao.findByPlayer(playerId).orElse(null);
                if (token == null) {
                    sendOnMain(playerId, Messages.CLONE_NO_TOKEN);
                    return;
                }

                // Check for name collision
                if (repoDao.findByOwnerAndName(playerId, repoName).isPresent()) {
                    sendOnMain(playerId,
                            String.format(Messages.CLONE_REPO_NAME_TAKEN, repoName));
                    return;
                }

                cloneService.cloneAsync(plugin, playerId, url, repoName, token.accessToken(),
                        database, repoDao, branchDao, commitDao, headDao, shaDao, remoteDao,
                        gitRepoManager, schematicsDir, selectionManager, pasteOrigin);

            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Clone guard DB failed", e);
                sendOnMain(playerId, String.format(Messages.CLONE_FAILED, e.getMessage()));
            }
        });
    }

    private void sendOnMain(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }
}
