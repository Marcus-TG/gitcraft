package com.gitcraft.merge;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player MergeSession registry. Listener clears the in-memory entry on quit
 * (does NOT roll the world back — matches Git's working-tree semantics).
 */
public final class MergeManager implements Listener {

    /** Auto-expire sessions after this many ms of inactivity. */
    public static final long INACTIVITY_TTL_MS = 10L * 60L * 1000L;

    private final ConcurrentHashMap<UUID, MergeSession> sessions = new ConcurrentHashMap<>();

    public Optional<MergeSession> get(UUID playerId) {
        MergeSession s = sessions.get(playerId);
        if (s == null) return Optional.empty();
        if (System.currentTimeMillis() - s.lastTouchedAt() > INACTIVITY_TTL_MS) {
            sessions.remove(playerId, s);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    public void put(MergeSession session) {
        sessions.put(session.playerId(), session);
    }

    public void remove(UUID playerId) {
        sessions.remove(playerId);
    }

    public boolean has(UUID playerId) {
        return get(playerId).isPresent();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }
}
