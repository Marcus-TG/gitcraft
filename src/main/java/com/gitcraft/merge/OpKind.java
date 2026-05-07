package com.gitcraft.merge;

/** Kind of in-flight conflict-resolving operation tracked by {@link OpManager}. */
public enum OpKind {
    MERGE,
    CHERRY_PICK
}
