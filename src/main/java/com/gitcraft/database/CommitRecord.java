package com.gitcraft.database;

import java.util.UUID;

/**
 * Row in the {@code commits} table. {@code id} is null on insert (auto-assigned by SQLite).
 * {@code parentCommitId} is null for the first commit in a region.
 * World UUID + min/max corners are required to deterministically restore a snapshot
 * to its original location.
 */
public record CommitRecord(
        Long id,
        Long parentCommitId,
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
