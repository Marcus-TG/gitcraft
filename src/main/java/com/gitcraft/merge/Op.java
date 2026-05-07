package com.gitcraft.merge;

import java.util.UUID;

/**
 * Common interface for an in-flight conflict-resolving operation (merge or cherry-pick).
 * Lets {@link OpManager} hold a single per-player slot with mutual exclusion across both.
 */
public interface Op {
    UUID playerId();
    OpKind kind();
    long lastTouchedAt();
}
