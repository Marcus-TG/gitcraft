package com.gitcraft.database;

import java.util.UUID;

/**
 * Row in the {@code stashes} table. Snapshot of a player's selection state
 * (region corners + repo/branch context) without a schematic — coords only,
 * per project decision (see CLAUDE.md OQ14 in stash spec).
 */
public record StashRecord(
        long id,
        UUID playerUuid,
        long repoId,
        long branchId,
        UUID worldUuid,
        String worldName,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        String message,
        long createdAt
) {}
