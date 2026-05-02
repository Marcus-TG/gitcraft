-- GitCraft schema v6. Idempotent. Run on every plugin enable via SchemaMigrator.
-- All schema changes happen here; do not CREATE TABLE elsewhere.
-- v4: repos/branches/heads added; commits.region_name replaced by branch_id (destructive migration).
-- v5: branches.fork_commit_id added to preserve commit graph lineage across branch boundaries.
-- v6: heads.commit_id added to track which specific commit HEAD points at within a branch.

CREATE TABLE IF NOT EXISTS repos (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_uuid  TEXT    NOT NULL,
    name        TEXT    NOT NULL,
    created_at  INTEGER NOT NULL,
    UNIQUE(owner_uuid, name)
);

CREATE TABLE IF NOT EXISTS branches (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    repo_id        INTEGER NOT NULL REFERENCES repos(id),
    name           TEXT    NOT NULL,
    created_at     INTEGER NOT NULL,
    fork_commit_id INTEGER REFERENCES commits(id),
    UNIQUE(repo_id, name)
);

CREATE TABLE IF NOT EXISTS heads (
    player_uuid TEXT    NOT NULL,
    repo_id     INTEGER NOT NULL REFERENCES repos(id),
    branch_id   INTEGER NOT NULL REFERENCES branches(id),
    commit_id   INTEGER          REFERENCES commits(id),
    PRIMARY KEY (player_uuid, repo_id)
);

CREATE TABLE IF NOT EXISTS commits (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    branch_id        INTEGER NOT NULL REFERENCES branches(id),
    player_uuid      TEXT    NOT NULL,
    player_name      TEXT    NOT NULL,
    message          TEXT,
    schem_path       TEXT    NOT NULL,
    created_at       INTEGER NOT NULL,
    world_uuid       TEXT    NOT NULL,
    world_name       TEXT    NOT NULL,
    min_x            INTEGER NOT NULL,
    min_y            INTEGER NOT NULL,
    min_z            INTEGER NOT NULL,
    max_x            INTEGER NOT NULL,
    max_y            INTEGER NOT NULL,
    max_z            INTEGER NOT NULL,
    parent_commit_id INTEGER
);

CREATE INDEX IF NOT EXISTS idx_commits_branch ON commits(branch_id);
CREATE INDEX IF NOT EXISTS idx_commits_player ON commits(player_uuid);
CREATE INDEX IF NOT EXISTS idx_commits_world  ON commits(world_uuid);

CREATE TABLE IF NOT EXISTS schema_version (
    version    INTEGER PRIMARY KEY,
    applied_at INTEGER NOT NULL
);
