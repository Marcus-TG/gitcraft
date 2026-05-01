package com.gitcraft.util;

public final class Messages {

    private Messages() {}

    public static final String PLAYER_ONLY = "This command can only be run by a player.";
    public static final String NO_PERMISSION = "You don't have permission to use GitCraft.";
    public static final String UNKNOWN_SUBCOMMAND = "Unknown subcommand. Try: select, pos1, pos2, clear, export.";

    public static final String SELECT_ENABLED = "Selection mode enabled. Left-click pos1, right-click pos2.";
    public static final String SELECT_WAND_GIVEN = "A selection wand has been added to your inventory.";

    public static final String POS1_SET = "Pos 1 set: (%d, %d, %d)";
    public static final String POS2_SET = "Pos 2 set: (%d, %d, %d)";
    public static final String CLEARED = "Selection cleared.";

    public static final String NO_SELECTION = "You have no active selection. Use /gitcraft select.";
    public static final String INCOMPLETE_SELECTION = "Selection incomplete. Set both pos1 and pos2.";
    public static final String CROSS_WORLD = "Both corners must be in the same world.";
    public static final String WORLD_NOT_LOADED = "The selection's world is not loaded.";

    public static final String EXPORT_USAGE = "Usage: /gitcraft export <name>";
    public static final String EXPORT_INVALID_NAME = "Invalid name. Allowed: letters, digits, _ and -, up to 64 chars.";
    public static final String EXPORT_FILE_EXISTS = "A schematic with that name already exists.";
    public static final String EXPORT_STARTED = "Exporting selection to %s.schem ...";
    public static final String EXPORT_SUCCESS = "Exported to %s";
    public static final String EXPORT_IO_FAILED = "Failed to write schematic: %s";
    public static final String EXPORT_WE_FAILED = "Schematic export failed: %s";

    public static final String WORLDEDIT_MISSING = "WorldEdit plugin not found. GitCraft requires WorldEdit to be installed.";
}
