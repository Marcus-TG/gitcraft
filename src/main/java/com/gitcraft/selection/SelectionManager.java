package com.gitcraft.selection;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SelectionManager {

    private final ConcurrentHashMap<UUID, Selection> selections = new ConcurrentHashMap<>();

    /** Tracks players who have run /gitcraft select this session. Wand clicks are ignored otherwise. */
    private final Set<UUID> selectingPlayers = ConcurrentHashMap.newKeySet();

    public Selection getOrCreate(UUID player) {
        return selections.computeIfAbsent(player, k -> new Selection());
    }

    public Optional<Selection> get(UUID player) {
        return Optional.ofNullable(selections.get(player));
    }

    public void clear(UUID player) {
        selections.remove(player);
        selectingPlayers.remove(player);
    }

    public void clearAll() {
        selections.clear();
        selectingPlayers.clear();
    }

    public void enableSelecting(UUID player) {
        selectingPlayers.add(player);
    }

    public boolean isSelecting(UUID player) {
        return selectingPlayers.contains(player);
    }
}
