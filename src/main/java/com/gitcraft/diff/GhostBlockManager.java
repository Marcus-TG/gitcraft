package com.gitcraft.diff;

import com.gitcraft.GitCraft;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Color;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GhostBlockManager implements Listener {

    private static final Color COLOR_ADDED    = Color.LIME;
    private static final Color COLOR_REMOVED  = Color.RED;
    private static final Color COLOR_CHANGED  = Color.YELLOW;
    private static final Color COLOR_CONFLICT = Color.fromRGB(170, 0, 255);

    // Shrink 0.5% per face and nudge inward so the ghost sits just inside the real block
    private static final Transformation GHOST_TRANSFORM = new Transformation(
            new Vector3f(0.005f, 0.005f, 0.005f),
            new Quaternionf(),
            new Vector3f(0.99f, 0.99f, 0.99f),
            new Quaternionf()
    );

    private final GitCraft plugin;
    private final Map<UUID, List<Entity>> activeGhosts = new ConcurrentHashMap<>();

    public GhostBlockManager(GitCraft plugin) {
        this.plugin = plugin;
    }

    /** Must be called on the main thread. Replaces any existing diff for this player. */
    public void show(Player player, DiffResult result, World world) {
        clear(player);

        List<Entity> entities = new ArrayList<>(result.totalCount());
        UUID playerId = player.getUniqueId();

        for (GhostBlock ghost : result.ghosts()) {
            BlockVector3 pos = ghost.worldPos();
            org.bukkit.Location loc = new org.bukkit.Location(world, pos.x(), pos.y(), pos.z());

            BlockData blockData = ghost.type() == GhostType.REMOVED
                    ? ghost.beforeBlock()
                    : ghost.afterBlock();

            Color glowColor = switch (ghost.type()) {
                case ADDED    -> COLOR_ADDED;
                case REMOVED  -> COLOR_REMOVED;
                case CHANGED  -> COLOR_CHANGED;
                case CONFLICT -> COLOR_CONFLICT;
            };

            BlockDisplay entity = world.spawn(loc, BlockDisplay.class, e -> {
                e.setBlock(blockData);
                e.setTransformation(GHOST_TRANSFORM);
                e.setGlowing(true);
                e.setGlowColorOverride(glowColor);
                e.setPersistent(false);
                e.setVisibleByDefault(false);
            });
            player.showEntity(plugin, entity);
            entities.add(entity);
        }

        activeGhosts.put(playerId, entities);
    }

    /** Must be called on the main thread. */
    public void clear(Player player) {
        List<Entity> ghosts = activeGhosts.remove(player.getUniqueId());
        if (ghosts != null) {
            ghosts.forEach(Entity::remove);
        }
    }

    /** Must be called on the main thread (from onDisable). */
    public void clearAll() {
        activeGhosts.values().forEach(list -> list.forEach(Entity::remove));
        activeGhosts.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }
}
