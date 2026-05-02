package com.gitcraft.database;

import java.util.UUID;

/** Row in the {@code heads} table. Tracks which branch and commit each player's HEAD points at per repo. */
public record HeadRecord(UUID playerUuid, long repoId, long branchId, Long commitId) {}
