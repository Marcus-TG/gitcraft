package com.gitcraft.util;

public final class Messages {

    private Messages() {}

    public static final String PLAYER_ONLY = "This command can only be run by a player.";
    public static final String NO_PERMISSION = "You don't have permission to use GitCraft.";
    public static final String UNKNOWN_SUBCOMMAND = "Unknown subcommand. Try: select, pos1, pos2, clear, commit.";

    public static final String SELECT_ENABLED = "Selection mode enabled. Left-click pos1, right-click pos2.";
    public static final String SELECT_WAND_GIVEN = "A selection wand has been added to your inventory.";
    public static final String SELECT_INVALID_NAME = "Invalid region name. Allowed: letters, digits, _ and -, up to 64 chars.";
    public static final String SELECT_NAMED = "Selection labeled: %s";

    public static final String POS1_SET = "Pos 1 set: (%d, %d, %d)";
    public static final String POS2_SET = "Pos 2 set: (%d, %d, %d)";
    public static final String CLEARED = "Selection cleared.";

    public static final String NO_SELECTION = "You have no active selection. Use /gitcraft select.";
    public static final String INCOMPLETE_SELECTION = "Selection incomplete. Set both pos1 and pos2.";
    public static final String CROSS_WORLD = "Both corners must be in the same world.";
    public static final String WORLD_NOT_LOADED = "The selection's world is not loaded.";

    public static final String COMMIT_USAGE = "Usage: /gitcraft commit <msg>";
    public static final String COMMIT_NO_REGION_NAME = "No region name set. Run /gitcraft select <name> first.";
    public static final String COMMIT_EMPTY_MESSAGE = "Commit message cannot be empty.";
    public static final String COMMIT_INVALID_MESSAGE = "Commit message cannot contain newlines.";
    public static final String COMMIT_MESSAGE_TOO_LONG = "Commit message exceeds 500 characters.";
    public static final String COMMIT_STARTED = "Committing selection...";
    public static final String COMMIT_SUCCESS = "Commit %d saved (region=%s).";
    public static final String COMMIT_DB_FAILED = "Failed to save commit metadata: %s";
    public static final String COMMIT_IO_FAILED = "Failed to write schematic: %s";
    public static final String COMMIT_WE_FAILED = "Schematic build failed: %s";

    public static final String WORLDEDIT_MISSING = "WorldEdit plugin not found. GitCraft requires WorldEdit to be installed.";
}
