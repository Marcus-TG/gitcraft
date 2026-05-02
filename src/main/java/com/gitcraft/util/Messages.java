package com.gitcraft.util;

public final class Messages {

    private Messages() {}

    public static final String PLAYER_ONLY = "This command can only be run by a player.";
    public static final String NO_PERMISSION = "You don't have permission to use GitCraft.";
    public static final String UNKNOWN_SUBCOMMAND = "Unknown subcommand. Try: init, open, pos1, pos2, clear, commit, log, reset.";

    public static final String SELECT_ENABLED = "Selection mode enabled. Left-click pos1, right-click pos2.";
    public static final String SELECT_WAND_GIVEN = "A selection wand has been added to your inventory.";
    public static final String SELECT_INVALID_NAME = "Invalid region name. Allowed: letters, digits, _ and -, up to 64 chars.";
    public static final String SELECT_NAMED = "Selection labeled: %s";

    public static final String POS1_SET = "Pos 1 set: (%d, %d, %d)";
    public static final String POS2_SET = "Pos 2 set: (%d, %d, %d)";
    public static final String CLEARED = "Selection cleared.";

    public static final String NO_SELECTION = "You have no active selection. Use /gitcraft init.";
    public static final String INCOMPLETE_SELECTION = "Selection incomplete. Set both pos1 and pos2.";
    public static final String CROSS_WORLD = "Both corners must be in the same world.";
    public static final String WORLD_NOT_LOADED = "The selection's world is not loaded.";

    public static final String OPEN_USAGE = "Usage: /gitcraft open <name>";
    public static final String OPEN_NOT_FOUND = "No commits found for region '%s'.";
    public static final String OPEN_DB_FAILED = "Failed to look up region: %s";
    public static final String OPEN_WORLD_GONE = "World '%s' is no longer loaded.";
    public static final String OPEN_RESTORED = "Opened region '%s' from commit %d. Pos1 (%d, %d, %d) Pos2 (%d, %d, %d)";

    public static final String COMMIT_USAGE = "Usage: /gitcraft commit <msg>";
    public static final String COMMIT_NO_REGION_NAME = "No region name set. Run /gitcraft init <name> first.";
    public static final String COMMIT_EMPTY_MESSAGE = "Commit message cannot be empty.";
    public static final String COMMIT_INVALID_MESSAGE = "Commit message cannot contain newlines.";
    public static final String COMMIT_MESSAGE_TOO_LONG = "Commit message exceeds 500 characters.";
    public static final String COMMIT_STARTED = "Committing selection...";
    public static final String COMMIT_SUCCESS = "Commit %d saved (region=%s).";
    public static final String COMMIT_DB_FAILED = "Failed to save commit metadata: %s";
    public static final String COMMIT_IO_FAILED = "Failed to write schematic: %s";
    public static final String COMMIT_WE_FAILED = "Schematic build failed: %s";

    public static final String LOG_USAGE = "Usage: /gitcraft log <region> [page]";
    public static final String LOG_INVALID_PAGE = "Page must be a positive integer.";
    public static final String LOG_HEADER = "Commits for '%s' — page %d/%d (%d total):";
    public static final String LOG_FOOTER_NEXT = "More — /gitcraft log %s %d";
    public static final String LOG_EMPTY = "No commits found for region '%s'.";
    public static final String LOG_PAGE_OUT_OF_RANGE = "Page %d out of range (max %d).";
    public static final String LOG_DB_FAILED = "Failed to read commit history: %s";

    public static final String RESET_USAGE = "Usage: /gitcraft reset <id> [--hard]";
    public static final String RESET_INVALID_ID = "Invalid commit id. Must be a positive number.";
    public static final String RESET_STARTED = "Resetting to commit...";
    public static final String RESET_NOT_FOUND = "Commit %d not found.";
    public static final String RESET_NOT_OWNER = "You can only reset your own commits.";
    public static final String RESET_FILE_MISSING = "Schematic file missing on disk: %s";
    public static final String RESET_BAD_FORMAT = "Schematic file format not recognized.";
    public static final String RESET_WORLD_GONE = "World '%s' is no longer loaded.";
    public static final String RESET_SUCCESS = "Reset to commit %d (region=%s, %d blocks).";
    public static final String RESET_DB_FAILED = "Failed to look up commit: %s";
    public static final String RESET_IO_FAILED = "Failed to read schematic: %s";
    public static final String RESET_WE_FAILED = "Reset paste failed: %s";
    public static final String RESET_HARD_WARN = "WARNING: --hard will permanently delete all commits after #%d in that region. Run the same command again within 30 seconds to confirm.";
    public static final String RESET_HARD_STARTED = "Executing hard reset...";
    public static final String RESET_HARD_SUCCESS = "Hard reset to commit %d (region=%s, %d blocks pasted, %d commit(s) deleted).";

    public static final String WORLDEDIT_MISSING = "WorldEdit plugin not found. GitCraft requires WorldEdit to be installed.";
}
