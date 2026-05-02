package com.gitcraft.database;

/** Row in the {@code branches} table. {@code id} is null on insert (auto-assigned by SQLite). */
public record BranchRecord(Long id, long repoId, String name, long createdAt) {}
