package com.gitcraft.diff;

public enum GhostType {
    ADDED,
    REMOVED,
    CHANGED,
    /** Merge conflict: both sides changed the position differently. Renders the "theirs" block in purple. */
    CONFLICT
}
