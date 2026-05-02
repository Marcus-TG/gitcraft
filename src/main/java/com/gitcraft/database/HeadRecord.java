package com.gitcraft.database;

import java.util.UUID;

/** Row in the {@code heads} table. Tracks which branch each player is currently editing per repo. */
public record HeadRecord(UUID playerUuid, long repoId, long branchId) {}
