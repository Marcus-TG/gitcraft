package com.gitcraft.database;

import java.util.UUID;

/** Row in the {@code repos} table. {@code id} is null on insert (auto-assigned by SQLite). */
public record RepoRecord(Long id, UUID ownerUuid, String name, long createdAt) {}
