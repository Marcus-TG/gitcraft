-- GitCraft schema. Idempotent. Run on every plugin enable via SchemaMigrator.
-- All schema changes happen here; do not CREATE TABLE elsewhere.
-- Migration to v2 is handled by SchemaMigrator: it drops the v1 commits table
-- before this script runs so the new column set is created cleanly.

CREATE TABLE IF NOT EXISTS commits (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT    NOT NULL,
    player_name TEXT    NOT NULL,
    region_name TEXT    NOT NULL,
    message     TEXT,
    schem_path  TEXT    NOT NULL,
    created_at  INTEGER NOT NULL,
    world_uuid  TEXT    NOT NULL,
    world_name  TEXT    NOT NULL,
    min_x       INTEGER NOT NULL,
    min_y       INTEGER NOT NULL,
    min_z       INTEGER NOT NULL,
    max_x       INTEGER NOT NULL,
    max_y       INTEGER NOT NULL,
    max_z       INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_commits_player ON commits(player_uuid);
CREATE INDEX IF NOT EXISTS idx_commits_region ON commits(region_name);
CREATE INDEX IF NOT EXISTS idx_commits_world  ON commits(world_uuid);

CREATE TABLE IF NOT EXISTS schema_version (
    version    INTEGER PRIMARY KEY,
    applied_at INTEGER NOT NULL
);
