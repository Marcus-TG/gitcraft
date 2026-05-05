package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.database.BranchDao;
import com.gitcraft.database.BranchRecord;
import com.gitcraft.database.HeadDao;
import com.gitcraft.database.HeadRecord;
import com.gitcraft.database.StashDao;
import com.gitcraft.database.StashRecord;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * /gitcraft stash               — push current selection state onto the player's per-repo stack.
 * /gitcraft stash pop           — restore the latest stashed state for the current repo (LIFO).
 * /gitcraft stash list          — list stashes for the current repo.
 *
 * Stash captures coords + repo/branch context only. No schematic; if the world changes between
 * stash and pop, the popped selection re-references whatever blocks live there now (per spec OQ14).
 */
public final class StashSubcommand implements Subcommand {

    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final List<String> ACTIONS = List.of("pop", "list");

    private final GitCraft plugin;
    private final SelectionManager manager;
    private final StashDao stashDao;
    private final BranchDao branchDao;
    private final HeadDao headDao;

    public StashSubcommand(GitCraft plugin, SelectionManager manager, StashDao stashDao,
                           BranchDao branchDao, HeadDao headDao) {
        this.plugin = plugin;
        this.manager = manager;
        this.stashDao = stashDao;
        this.branchDao = branchDao;
        this.headDao = headDao;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("pop")) {
            handlePop(player);
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            handleList(player);
            return;
        }
        handlePush(player, args);
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return ACTIONS.stream().filter(a -> a.startsWith(prefix)).toList();
        }
        return Collections.emptyList();
    }

    // ---- push ----------------------------------------------------------------

    private void handlePush(Player player, String[] args) {
        Selection sel = manager.get(player.getUniqueId()).orElse(null);
        if (sel == null) {
            player.sendMessage(Messages.NO_SELECTION);
            return;
        }
        Long repoId = sel.repoId();
        Long branchId = sel.branchId();
        if (repoId == null || branchId == null) {
            player.sendMessage(Messages.STASH_NO_REPO);
            return;
        }
        if (!sel.isComplete()) {
            player.sendMessage(Messages.INCOMPLETE_SELECTION);
            return;
        }

        UUID worldId = sel.worldId();
        World world = plugin.getServer().getWorld(worldId);
        if (world == null) {
            player.sendMessage(Messages.WORLD_NOT_LOADED);
            return;
        }

        String message = args.length == 0 ? null : String.join(" ", args).trim();
        if (message != null) {
            if (message.isEmpty()) message = null;
            else if (message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0) {
                player.sendMessage(Messages.STASH_INVALID_MESSAGE);
                return;
            } else if (message.length() > MAX_MESSAGE_LENGTH) {
                player.sendMessage(Messages.STASH_MESSAGE_TOO_LONG);
                return;
            }
        }

        BlockVector3 p1 = sel.pos1();
        BlockVector3 p2 = sel.pos2();
        int minX = Math.min(p1.x(), p2.x());
        int minY = Math.min(p1.y(), p2.y());
        int minZ = Math.min(p1.z(), p2.z());
        int maxX = Math.max(p1.x(), p2.x());
        int maxY = Math.max(p1.y(), p2.y());
        int maxZ = Math.max(p1.z(), p2.z());

        UUID playerId = player.getUniqueId();
        String worldName = world.getName();
        String finalMessage = message;
        long createdAt = System.currentTimeMillis();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long id = stashDao.insert(new StashRecord(
                        0L, playerId, repoId, branchId, worldId, worldName,
                        minX, minY, minZ, maxX, maxY, maxZ, finalMessage, createdAt));
                sendOnMain(playerId, String.format(Messages.STASH_PUSHED, id));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Stash insert failed", e);
                sendOnMain(playerId, String.format(Messages.STASH_DB_FAILED, safe(e.getMessage())));
            }
        });
    }

    // ---- pop -----------------------------------------------------------------

    private void handlePop(Player player) {
        Selection sel = manager.get(player.getUniqueId()).orElse(null);
        if (sel == null || sel.repoId() == null) {
            player.sendMessage(Messages.STASH_NO_REPO);
            return;
        }
        long repoId = sel.repoId();
        UUID playerId = player.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<StashRecord> latest = stashDao.peekLatest(playerId, repoId);
                if (latest.isEmpty()) {
                    sendOnMain(playerId, Messages.STASH_EMPTY);
                    return;
                }
                StashRecord r = latest.get();

                Optional<BranchRecord> branchOpt = branchDao.findById(r.branchId());
                if (branchOpt.isEmpty()) {
                    // Branch deleted while stash existed (FK CASCADE should have killed the row,
                    // but be defensive in case PRAGMA was off at delete time).
                    stashDao.deleteById(r.id());
                    sendOnMain(playerId, Messages.STASH_BRANCH_GONE);
                    return;
                }
                String branchName = branchOpt.get().name();

                Bukkit.getScheduler().runTask(plugin, () -> applyPopOnMain(playerId, r, branchName));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Stash pop lookup failed", e);
                sendOnMain(playerId, String.format(Messages.STASH_DB_FAILED, safe(e.getMessage())));
            }
        });
    }

    /**
     * Main-thread apply: verify world still loaded BEFORE deleting the stash, install Selection,
     * then dispatch the DELETE + heads upsert async. If the world is gone, leave the stash row
     * in place so the player can try again later (spec OQ7).
     */
    private void applyPopOnMain(UUID playerId, StashRecord r, String branchName) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;

        World world = Bukkit.getWorld(r.worldUuid());
        if (world == null) {
            player.sendMessage(String.format(Messages.STASH_WORLD_GONE, r.worldName()));
            return;
        }

        Selection selection = manager.getOrCreate(playerId);
        // Preserve repoName if already set on the active selection — stash row doesn't carry it.
        Selection prior = manager.get(playerId).orElse(null);
        String repoName = prior != null ? prior.repoName() : null;

        selection.setRepoId(r.repoId());
        if (repoName != null) selection.setRepoName(repoName);
        selection.setBranchId(r.branchId());
        selection.setBranchName(branchName);
        selection.setPos1(world, BlockVector3.at(r.minX(), r.minY(), r.minZ()));
        selection.setPos2(world, BlockVector3.at(r.maxX(), r.maxY(), r.maxZ()));
        manager.enableSelecting(playerId);

        // Persist new HEAD branch + delete stash row, async.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                headDao.upsert(new HeadRecord(playerId, r.repoId(), r.branchId(), null));
                stashDao.deleteById(r.id());
                sendOnMain(playerId, String.format(Messages.STASH_POPPED,
                        r.id(), branchName,
                        r.minX(), r.minY(), r.minZ(),
                        r.maxX(), r.maxY(), r.maxZ()));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Stash pop persist failed", e);
                sendOnMain(playerId, String.format(Messages.STASH_DB_FAILED, safe(e.getMessage())));
            }
        });
    }

    // ---- list ----------------------------------------------------------------

    private void handleList(Player player) {
        Selection sel = manager.get(player.getUniqueId()).orElse(null);
        if (sel == null || sel.repoId() == null) {
            player.sendMessage(Messages.STASH_NO_REPO);
            return;
        }
        long repoId = sel.repoId();
        UUID playerId = player.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<StashRecord> rows = stashDao.listForPlayerRepo(playerId, repoId);
                if (rows.isEmpty()) {
                    sendOnMain(playerId, Messages.STASH_EMPTY);
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(playerId);
                    if (p == null || !p.isOnline()) return;
                    p.sendMessage(String.format(Messages.STASH_LIST_HEADER, rows.size()));
                    for (StashRecord r : rows) {
                        String msg = r.message() == null ? "(no message)" : r.message();
                        p.sendMessage(String.format(Messages.STASH_LIST_ROW,
                                r.id(), r.worldName(),
                                r.minX(), r.minY(), r.minZ(),
                                r.maxX(), r.maxY(), r.maxZ(),
                                msg));
                    }
                });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Stash list failed", e);
                sendOnMain(playerId, String.format(Messages.STASH_DB_FAILED, safe(e.getMessage())));
            }
        });
    }

    // ---- helpers -------------------------------------------------------------

    private void sendOnMain(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }

    private static String safe(String s) {
        return s == null ? "(no detail)" : s;
    }

    @SuppressWarnings("unused")
    private static String[] tail(String[] args) {
        return args.length <= 1 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
    }
}
