package com.gitcraft.commands.sub;

import com.gitcraft.GitCraft;
import com.gitcraft.export.SchematicExporter;
import com.gitcraft.selection.Selection;
import com.gitcraft.selection.SelectionManager;
import com.gitcraft.util.Messages;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ExportSubcommand implements Subcommand {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final GitCraft plugin;
    private final SelectionManager manager;
    private final SchematicExporter exporter;

    public ExportSubcommand(GitCraft plugin, SelectionManager manager, SchematicExporter exporter) {
        this.plugin = plugin;
        this.manager = manager;
        this.exporter = exporter;
    }

    @Override
    public void execute(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(Messages.EXPORT_USAGE);
            return;
        }
        String name = args[0];
        if (!NAME_PATTERN.matcher(name).matches()) {
            player.sendMessage(Messages.EXPORT_INVALID_NAME);
            return;
        }

        Selection sel = manager.get(player.getUniqueId()).orElse(null);
        if (sel == null) {
            player.sendMessage(Messages.NO_SELECTION);
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

        // Snapshot corners on main thread before async handoff.
        BlockVector3 pos1 = sel.pos1();
        BlockVector3 pos2 = sel.pos2();

        Path target = plugin.gitCraftConfig().schematicsDir().resolve(name + ".schem");
        if (Files.exists(target)) {
            player.sendMessage(Messages.EXPORT_FILE_EXISTS);
            return;
        }

        player.sendMessage(String.format(Messages.EXPORT_STARTED, name));
        exporter.exportAsync(player.getUniqueId(), world, pos1, pos2, target);
    }
}
