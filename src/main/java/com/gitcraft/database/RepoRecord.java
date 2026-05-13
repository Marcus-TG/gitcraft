package com.gitcraft.database;

import java.util.UUID;

/** Row in the {@code repos} table. {@code id} is null on insert (auto-assigned by SQLite). */
public record RepoRecord(Long id, UUID ownerUuid, String name, long createdAt,
                         int originOffsetX, int originOffsetY, int originOffsetZ,
                         boolean originOffsetSet) {

    /** Returns the offset only when it has been explicitly set; 0 otherwise. */
    public int effectiveOffsetX() { return originOffsetSet ? originOffsetX : 0; }
    public int effectiveOffsetY() { return originOffsetSet ? originOffsetY : 0; }
    public int effectiveOffsetZ() { return originOffsetSet ? originOffsetZ : 0; }
}
