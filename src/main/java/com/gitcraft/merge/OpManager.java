package com.gitcraft.merge;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player slot for an in-progress {@link Op} (merge or cherry-pick). Replaces the
 * old MergeManager and gives free mutual exclusion across both kinds — only one
 * conflict-resolving operation can be active per player at a time.
 *
 * Listener clears the in-memory entry on quit (does NOT roll the world back —
 * matches Git's working-tree semantics).
 */
public final class OpManager implements Listener {

    /** Auto-expire sessions after this many ms of inactivity. */
    public static final long INACTIVITY_TTL_MS = 10L * 60L * 1000L;

    private final ConcurrentHashMap<UUID, Op> ops = new ConcurrentHashMap<>();

    public Optional<Op> get(UUID playerId) {
        Op op = ops.get(playerId);
        if (op == null) return Optional.empty();
        if (System.currentTimeMillis() - op.lastTouchedAt() > INACTIVITY_TTL_MS) {
            ops.remove(playerId, op);
            return Optional.empty();
        }
        return Optional.of(op);
    }

    public Optional<MergeSession> getMerge(UUID playerId) {
        return get(playerId)
                .filter(op -> op.kind() == OpKind.MERGE)
                .map(op -> (MergeSession) op);
    }

    public Optional<CherryPickSession> getCherryPick(UUID playerId) {
        return get(playerId)
                .filter(op -> op.kind() == OpKind.CHERRY_PICK)
                .map(op -> (CherryPickSession) op);
    }

    public void put(Op op) {
        ops.put(op.playerId(), op);
    }

    public void remove(UUID playerId) {
        ops.remove(playerId);
    }

    /** True if any op (merge or cherry-pick) is in flight for this player. */
    public boolean has(UUID playerId) {
        return get(playerId).isPresent();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ops.remove(event.getPlayer().getUniqueId());
    }
}
