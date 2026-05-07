package com.gitcraft.database;

import java.util.UUID;

/**
 * Row in the {@code commits} table. {@code id} is null on insert (auto-assigned by SQLite).
 * {@code parentCommitId} is null for the first commit in a branch.
 * {@code mergeParentCommitId} is non-null only for merge commits — the source-branch HEAD
 * at merge time. With both parent fields set, the commit graph is a true DAG.
 * {@code cherryPickSourceId} is non-null only for commits created by /gitcraft cherry-pick;
 * it is informational metadata and is NOT part of the parent DAG (ancestor walks ignore it).
 * World UUID + min/max corners are required to deterministically restore a snapshot
 * to its original location.
 */
public record CommitRecord(
        Long id,
        Long parentCommitId,
        Long mergeParentCommitId,
        Long cherryPickSourceId,
        long branchId,
        UUID playerUuid,
        String playerName,
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
