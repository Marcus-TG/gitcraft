package com.gitcraft.database;

import java.util.UUID;

/**
 * Row in the {@code commits} table. {@code id} is null on insert (auto-assigned by SQLite).
 * World UUID + min/max corners are required to deterministically restore a snapshot
 * to its original location.
 */
public record CommitRecord(
        Long id,
        UUID playerUuid,
        String playerName,
        String regionName,
        String message,
        String schemPath,
        long createdAt,
        UUID worldUuid,
        String worldName,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ
) {}
