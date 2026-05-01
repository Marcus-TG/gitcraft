package com.gitcraft.database;

import java.util.UUID;

/**
 * Row in the {@code commits} table. {@code id} is null on insert (auto-assigned by SQLite).
 */
public record CommitRecord(
        Long id,
        UUID playerUuid,
        String playerName,
        String regionName,
        String message,
        String schemPath,
        long createdAt
) {}
