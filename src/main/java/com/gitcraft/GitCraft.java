package com.gitcraft;

import com.gitcraft.commands.GitCraftCommand;
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
import com.gitcraft.database.SchemaMigrator;
import com.gitcraft.database.StashDao;
import com.gitcraft.diff.DiffService;
import com.gitcraft.diff.GhostBlockManager;
import com.gitcraft.export.SchematicExporter;
import com.gitcraft.git.CommitMapper;
import com.gitcraft.git.GitCloneService;
import com.gitcraft.git.GitPullService;
import com.gitcraft.git.GitPushService;
import com.gitcraft.git.GitRepoManager;
import com.gitcraft.github.GitHubAuthService;
import com.gitcraft.listeners.WandListener;
import com.gitcraft.merge.CherryPickService;
import com.gitcraft.merge.MergeService;
import com.gitcraft.merge.OpManager;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.logging.Level;

public final class GitCraft extends JavaPlugin {

    private GitCraftConfig config;
    private SelectionManager selectionManager;
    private Database database;
    private GhostBlockManager ghostBlockManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (Bukkit.getPluginManager().getPlugin("WorldEdit") == null) {
            getLogger().severe(Messages.WORLDEDIT_MISSING);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.config = new GitCraftConfig(this);
        this.config.load();

        this.selectionManager = new SelectionManager();

        // DB open + migrate runs on the main thread — but only once at enable, never per tick.
        // SQLite open on local disk is sub-ms; acceptable here, would NOT be inside an event handler.
        this.database = new Database(this);
        try {
            database.open();
            new SchemaMigrator().migrate(database);
            getLogger().info("Schema migrated to v11.");
        } catch (SQLException | IOException e) {
            getLogger().log(Level.SEVERE, "Failed to initialize SQLite database; disabling plugin.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        SchematicExporter exporter = new SchematicExporter(this);
        CommitDao commitDao = new CommitDao(database);
        RepoDao repoDao = new RepoDao(database);
        BranchDao branchDao = new BranchDao(database);
        HeadDao headDao = new HeadDao(database);
        StashDao stashDao = new StashDao(database);
        RemoteDao remoteDao = new RemoteDao(database);
        GitHubTokenDao tokenDao = new GitHubTokenDao(database);
        CommitGitShaDao shaDao = new CommitGitShaDao(database);
        DiffService diffService = new DiffService(getLogger());
        this.ghostBlockManager = new GhostBlockManager(this);
        CommitService commitService = new CommitService(this, exporter, commitDao, branchDao, headDao,
                ghostBlockManager, repoDao);
        OpManager opManager = new OpManager();
        MergeService mergeService = new MergeService(this, selectionManager, commitDao, branchDao,
                headDao, repoDao, ghostBlockManager, opManager, commitService, config);
        CherryPickService cherryPickService = new CherryPickService(this, selectionManager, commitDao,
                branchDao, headDao, repoDao, ghostBlockManager, opManager, commitService, config);

        CommitMapper commitMapper = new CommitMapper();
        GitRepoManager gitRepoManager = new GitRepoManager(config.gitDir());
        GitHubAuthService authService = new GitHubAuthService();
        GitPushService pushService = new GitPushService(commitMapper, getLogger());
        GitPullService pullService = new GitPullService(commitMapper, getLogger());
        GitCloneService cloneService = new GitCloneService(commitMapper, getLogger());

        getServer().getPluginManager().registerEvents(
                new WandListener(this, selectionManager), this);
        getServer().getPluginManager().registerEvents(ghostBlockManager, this);
        getServer().getPluginManager().registerEvents(opManager, this);

        PluginCommand cmd = getCommand("gitcraft");
        if (cmd != null) {
            GitCraftCommand executor = new GitCraftCommand(
                    this, selectionManager, commitService, commitDao, repoDao, branchDao, headDao,
                    stashDao, diffService, ghostBlockManager, mergeService, cherryPickService,
                    opManager, database, tokenDao, remoteDao, shaDao,
                    pushService, pullService, cloneService,
                    authService, gitRepoManager, config);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        } else {
            getLogger().warning("Command 'gitcraft' not declared in plugin.yml.");
        }

        // Even directory creation is filesystem work, so keep it off the main thread.
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Files.createDirectories(config.schematicsDir());
                Files.createDirectories(config.gitDir());
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Failed to create plugin directories", e);
            }
        });

        getLogger().info("GitCraft enabled.");
    }

    @Override
    public void onDisable() {
        if (ghostBlockManager != null) {
            ghostBlockManager.clearAll();
        }
        if (selectionManager != null) {
            selectionManager.clearAll();
        }
        if (database != null) {
            database.close();
        }
        getLogger().info("GitCraft disabled.");
    }

    public GitCraftConfig gitCraftConfig() {
        return config;
    }

    public SelectionManager selectionManager() {
        return selectionManager;
    }
}
