package com.gitcraft.commands.sub;

import com.gitcraft.database.RemoteDao;
import com.gitcraft.database.RemoteRecord;
import com.gitcraft.database.RepoRecord;
import com.gitcraft.git.GitRepoManager;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class RemoteSubcommand implements Subcommand {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");

    private final Plugin plugin;
    private final SelectionManager selectionManager;
    private final RemoteDao remoteDao;
    private final GitRepoManager gitRepoManager;

    public RemoteSubcommand(Plugin plugin, SelectionManager selectionManager,
                            RemoteDao remoteDao, GitRepoManager gitRepoManager) {
        this.plugin = plugin;
        this.selectionManager = selectionManager;
        this.remoteDao = remoteDao;
        this.gitRepoManager = gitRepoManager;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(Messages.REMOTE_USAGE);
            return;
        }

        Selection sel = selectionManager.get(player.getUniqueId()).orElse(null);
        if (sel == null || sel.repoId() == null) {
            player.sendMessage(Messages.REMOTE_NO_REPO);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "add"    -> handleAdd(player, sel, Arrays.copyOfRange(args, 1, args.length));
            case "list"   -> handleList(player, sel);
            case "remove" -> handleRemove(player, sel, Arrays.copyOfRange(args, 1, args.length));
            default       -> player.sendMessage(Messages.REMOTE_USAGE);
        }
    }

    private void handleAdd(Player player, Selection sel, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Messages.REMOTE_USAGE);
            return;
        }
        String name = args[0];
        String url  = args[1];

        if (!NAME_PATTERN.matcher(name).matches()) {
            player.sendMessage(Messages.REMOTE_INVALID_NAME);
            return;
        }
        if (!url.startsWith("https://")) {
            player.sendMessage(Messages.REMOTE_INVALID_URL);
            return;
        }

        long repoId   = sel.repoId();
        UUID ownerId  = player.getUniqueId();
        String repoName = sel.repoName();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (remoteDao.findByRepoAndName(repoId, name).isPresent()) {
                    sendOnMain(player.getUniqueId(),
                            String.format(Messages.REMOTE_ALREADY_EXISTS, name));
                    return;
                }
                long remoteId = remoteDao.insert(new RemoteRecord(null, repoId, name, url));

                // Sync into JGit config
                if (repoName != null) {
                    try (var jgitRepo = gitRepoManager.openOrInit(ownerId, repoName)) {
                        gitRepoManager.setRemoteUrl(jgitRepo, name, url);
                    } catch (IOException e) {
                        plugin.getLogger().log(Level.WARNING, "Failed to update JGit remote config", e);
                    }
                }

                sendOnMain(player.getUniqueId(),
                        String.format(Messages.REMOTE_ADDED, name, url));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Remote add DB failed", e);
                sendOnMain(player.getUniqueId(),
                        String.format(Messages.REMOTE_DB_FAILED, e.getMessage()));
            }
        });
    }

    private void handleList(Player player, Selection sel) {
        long repoId = sel.repoId();
        String repoName = sel.repoName() != null ? sel.repoName() : "?";
        UUID playerId = player.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<RemoteRecord> remotes = remoteDao.findByRepo(repoId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(playerId);
                    if (p == null || !p.isOnline()) return;
                    if (remotes.isEmpty()) {
                        p.sendMessage(Messages.REMOTE_LIST_EMPTY);
                        return;
                    }
                    p.sendMessage(String.format(Messages.REMOTE_LIST_HEADER, repoName));
                    for (RemoteRecord r : remotes) {
                        p.sendMessage(String.format(Messages.REMOTE_LIST_ENTRY, r.name(), r.url()));
                    }
                });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Remote list DB failed", e);
                sendOnMain(playerId, String.format(Messages.REMOTE_DB_FAILED, e.getMessage()));
            }
        });
    }

    private void handleRemove(Player player, Selection sel, String[] args) {
        if (args.length < 1) {
            player.sendMessage(Messages.REMOTE_USAGE);
            return;
        }
        String name = args[0];
        long repoId = sel.repoId();
        UUID playerId = player.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean deleted = remoteDao.deleteByRepoAndName(repoId, name);
                if (!deleted) {
                    sendOnMain(playerId, String.format(Messages.REMOTE_NOT_FOUND, name));
                } else {
                    sendOnMain(playerId, String.format(Messages.REMOTE_REMOVED, name));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Remote remove DB failed", e);
                sendOnMain(playerId, String.format(Messages.REMOTE_DB_FAILED, e.getMessage()));
            }
        });
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("add", "list", "remove").stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    private void sendOnMain(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }
}
