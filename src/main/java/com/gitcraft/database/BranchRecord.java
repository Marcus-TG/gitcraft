package com.gitcraft.database;

/**
 * Row in the {@code branches} table. {@code id} is null on insert (auto-assigned by SQLite).
 * {@code forkCommitId} is the HEAD commit of the source branch at branch-creation time;
 * used by CommitService as parent_commit_id fallback for the first commit on a new branch.
 */
public record BranchRecord(Long id, long repoId, String name, long createdAt, Long forkCommitId) {}
