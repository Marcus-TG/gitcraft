-- GitCraft schema. Idempotent. Run on every plugin enable via SchemaMigrator.
-- All schema changes happen here; do not CREATE TABLE elsewhere.

CREATE TABLE IF NOT EXISTS commits (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT    NOT NULL,
    player_name TEXT    NOT NULL,
    region_name TEXT    NOT NULL,
    message     TEXT,
    schem_path  TEXT    NOT NULL,
    created_at  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_commits_player ON commits(player_uuid);
CREATE INDEX IF NOT EXISTS idx_commits_region ON commits(region_name);

CREATE TABLE IF NOT EXISTS schema_version (
    version    INTEGER PRIMARY KEY,
    applied_at INTEGER NOT NULL
);
