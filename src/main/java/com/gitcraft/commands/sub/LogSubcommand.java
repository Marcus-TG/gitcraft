package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.database.CommitDao;
import com.gitcraft.database.CommitRecord;
import com.gitcraft.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public final class LogSubcommand implements Subcommand {

    private static final int PAGE_SIZE = 10;
    private static final int MESSAGE_PREVIEW_LENGTH = 40;
    private static final String PERMISSION = "gitcraft.log";

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final GitCraft plugin;
    private final CommitDao commitDao;

    public LogSubcommand(GitCraft plugin, CommitDao commitDao) {
        this.plugin = plugin;
        this.commitDao = commitDao;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Messages.NO_PERMISSION);
            return;
        }
        if (args.length < 1) {
            player.sendMessage(Messages.LOG_USAGE);
            return;
        }

        String region = args[0];
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                player.sendMessage(Messages.LOG_INVALID_PAGE);
                return;
            }
            if (page < 1) {
                player.sendMessage(Messages.LOG_INVALID_PAGE);
                return;
            }
        }

        final int finalPage = page;
        final UUID playerId = player.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int total = commitDao.countByRegion(region);
                List<CommitRecord> rows = commitDao.findByRegion(
                        region, PAGE_SIZE, (finalPage - 1) * PAGE_SIZE);
                replyOnMain(playerId, region, finalPage, total, rows);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Log query failed", e);
                sendOnMain(playerId, String.format(Messages.LOG_DB_FAILED, safe(e.getMessage())));
            }
        });
    }

    private void replyOnMain(UUID playerId, String region, int page, int total, List<CommitRecord> rows) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p == null || !p.isOnline()) return;

            if (total == 0) {
                p.sendMessage(String.format(Messages.LOG_EMPTY, region));
                return;
            }

            int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            if (rows.isEmpty()) {
                p.sendMessage(String.format(Messages.LOG_PAGE_OUT_OF_RANGE, page, totalPages));
                return;
            }

            p.sendMessage(String.format(Messages.LOG_HEADER, region, page, totalPages, total));
            for (CommitRecord r : rows) {
                p.sendMessage(formatRow(r));
            }
            if (page < totalPages) {
                p.sendMessage(String.format(Messages.LOG_FOOTER_NEXT, region, page + 1));
            }
        });
    }

    private String formatRow(CommitRecord r) {
        String when = TS_FORMAT.format(Instant.ofEpochMilli(r.createdAt()));
        String msg = r.message() == null ? "" : r.message();
        if (msg.length() > MESSAGE_PREVIEW_LENGTH) {
            msg = msg.substring(0, MESSAGE_PREVIEW_LENGTH - 1) + "…";
        }
        return String.format("#%d  %s  %s  \"%s\"", r.id(), when, r.playerName(), msg);
    }

    private void sendOnMain(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) p.sendMessage(message);
        });
    }

    private static String safe(String s) {
        return s == null ? "(no detail)" : s;
    }
}
