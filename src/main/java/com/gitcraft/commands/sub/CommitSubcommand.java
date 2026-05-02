package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.commit.CommitService;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.UUID;

public final class CommitSubcommand implements Subcommand {

    private static final int MAX_MESSAGE_LENGTH = 500;

    private final GitCraft plugin;
    private final SelectionManager manager;
    private final CommitService commitService;

    public CommitSubcommand(GitCraft plugin, SelectionManager manager, CommitService commitService) {
        this.plugin = plugin;
        this.manager = manager;
        this.commitService = commitService;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(Messages.COMMIT_USAGE);
            return;
        }

        String message = String.join(" ", args).trim();
        if (message.isEmpty()) {
            player.sendMessage(Messages.COMMIT_EMPTY_MESSAGE);
            return;
        }
        if (message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0) {
            player.sendMessage(Messages.COMMIT_INVALID_MESSAGE);
            return;
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            player.sendMessage(Messages.COMMIT_MESSAGE_TOO_LONG);
            return;
        }

        Selection sel = manager.get(player.getUniqueId()).orElse(null);
        if (sel == null) {
            player.sendMessage(Messages.NO_SELECTION);
            return;
        }
        Long branchId = sel.branchId();
        if (branchId == null) {
            player.sendMessage(Messages.COMMIT_NO_BRANCH);
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

        BlockVector3 pos1 = sel.pos1();
        BlockVector3 pos2 = sel.pos2();

        Path schemPath = plugin.gitCraftConfig().schematicsDir()
                .resolve(String.valueOf(branchId))
                .resolve(UUID.randomUUID() + ".schem");

        player.sendMessage(Messages.COMMIT_STARTED);
        commitService.commitAsync(
                player.getUniqueId(),
                player.getName(),
                branchId,
                message,
                world,
                pos1,
                pos2,
                schemPath);
    }
}
