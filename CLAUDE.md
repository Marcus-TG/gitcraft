# GitCraft — Claude Code Constitution

You are building **GitCraft**, a Paper Minecraft plugin + self-hosted web backend that brings Git-like version control to Minecraft builds. This document is your source of truth. Follow it strictly. Do not deviate from these constraints without explicit instruction from the user.

---

## Role & Scope

- You write code. The architectural decisions have already been made.
- Do not suggest alternative architectures, platforms, or tech choices unless asked.
- When in doubt, do the simpler thing. This is a side project — working beats clever.

---

## Hard Constraints

### Platform

- **Paper API only.** No Spigot, Bukkit, Fabric, or Forge patterns.
- Target: **Paper 1.21.4**, **Java 21**
- Build tool: **Gradle** (not Maven, ever)
- If you're unsure whether an API is Paper-specific, check Paper's Javadoc — do not guess

### Threading — The Most Important Rule

- **Never touch the database or filesystem from the main thread.**
- Event handlers fire on the main thread. They must be thin: capture data, hand off, return.
- All SQLite reads and writes must be dispatched via Paper's async scheduler:

  ```java
  Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
      // database work here
  });
  ```

- Violations cause server lag. This will not be obvious at low player counts and will be a nightmare to debug later. Get it right from the start.

### Schematics

- Format: `.schem` — **Sponge Schematic format (v2/v3)**. Not `.schematic` (legacy MCEdit). Not `.nbt`. Not anything else.
- Schematic export uses the **WorldEdit API** as a library dependency. Do not write a custom schematic serializer.
- Block entities (chests, spawners, signs, etc.) are **excluded** from schematics unless explicitly told otherwise. We capture structure, not contents.

### Database

- **SQLite only**, embedded. No separate database container, no network database.
- All schema definitions live in a single migration file. No inline `CREATE TABLE` scattered through the codebase.
- Player identity is always stored as **UUID**, never username. Usernames change.

### GitHub Integration (Phase 4)

- **JGit only** — pure Java Git library. No native `git` binary, no shell exec.
- Bundled in the JAR via **shadowJar** (`com.gradleup.shadow`). Relocation is intentionally omitted: Shadow 8.3.5 relocation hits an ASM/Java 21 incompatibility here, and JGit/Bouncy Castle/JSch are not present on Paper's server classpath, so relocation is not required in practice.
- **HTTPS transport only.** No SSH. GitHub tokens are used as the password with username literal `"token"` (`UsernamePasswordCredentialsProvider`).
- **The GitHub repo must already exist** before `/gitcraft push`. JGit cannot create repos. If the push returns a 404, tell the player to create the repo on GitHub first.
- **SHA is recorded only after a successful push** — never speculatively. This ensures unpushed commits retry cleanly.
- `commit_git_shas` is keyed `(commit_id, remote_id)` — one SHA per commit per remote.
- **JGit must be on the correct branch ref** before writing files and committing. Call `git.checkout().setName(branchName).call()` (create if absent) — writing files to the branch directory is not sufficient.
- **Pull/clone DB changes must be wrapped in a transaction.** Partial imports (some commits in, some not) are worse than a clean failure.
- **Parent IDs on pull/clone** are reconstructed by walking commits oldest-first and maintaining a `Map<String gitSha, Long localId>` as rows are inserted. Do not rely on the `commit_id` field inside `gitcraft.json` — that is the original server's local ID, which will differ here.
- **Merge commits** (both `parent_commit_id` and `merge_parent_commit_id` set): when pushing, if both parents have SHAs in `commit_git_shas`, create a real Git merge commit with two parents. Preserves DAG shape on GitHub.
- All JGit network operations (clone, fetch, push) are blocking and must run via `runTaskAsynchronously`. Never call them on the main thread.
- GitHub OAuth device flow uses `java.net.http.HttpClient` (Java 11+, no extra dependency). The OAuth client ID is a constant in `GitHubAuthService`; do not expose it as a config field.
- Token scope requested: `repo` (covers both public and private repos). Not configurable — always request `repo`.

---

## Project Structure

```
gitcraft/
├── build.gradle
├── settings.gradle
├── CLAUDE.md
└── src/main/
    ├── java/com/gitcraft/
    │   ├── GitCraft.java          ← Main plugin class (onEnable/onDisable only)
    │   ├── listeners/             ← Event handlers — thin, no logic
    │   ├── selection/             ← Selection.java, SelectionManager.java
    │   ├── database/              ← SQLite connection, schema, DAOs
    │   ├── commands/              ← /gitcraft subcommands
    │   ├── export/                ← Schematic generation via WorldEdit API
    │   ├── commit/                ← CommitService pipeline
    │   ├── diff/                  ← DiffService, GhostBlockManager
    │   ├── merge/                 ← MergeService, CherryPickService, OpManager
    │   ├── github/                ← OAuth device flow (GitHubAuthService)
    │   └── git/                   ← JGit wrappers (GitRepoManager, CommitMapper,
    │                                  GitPushService, GitPullService, GitCloneService)
    └── resources/
        ├── plugin.yml
        └── config.yml
```

Do not create files outside this structure without asking first.

---

## Data Model

### What we track (and what we don't)

GitCraft is **not** a block-change audit log. We do not record every block placement and break.

A "commit" in GitCraft is:

- A `.schem` snapshot of a player-selected region
- Metadata: who committed (player UUID), when (Unix timestamp in milliseconds), commit message
- Stored and versioned via Gitea on the backend (Phase 5)

### Core SQLite Schema (current: v9 → target: v10)

Repos are per-player. Identity is `(owner_uuid, name)`. The first branch is always `main`.
Schematic files are stored under `<schematicsDir>/<branchId>/<uuid>.schem`.
JGit working trees live under `<gitDir>/<ownerUuid>/<repoName>/`.

The authoritative schema lives in `src/main/resources/database/schema.sql`.
Schema changes are managed by `SchemaMigrator`. The `commits` table is append-only — do not modify committed rows.

**v9 tables (existing):** `repos`, `branches`, `heads`, `commits` (with `parent_commit_id`, `merge_parent_commit_id`, `cherry_pick_source_id`, `fork_commit_id` on branches), `stashes`

**v10 additions (Phase 4):**

```sql
-- GitHub remotes registered for a repo
CREATE TABLE IF NOT EXISTS remotes (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    repo_id  INTEGER NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
    name     TEXT    NOT NULL DEFAULT 'origin',
    url      TEXT    NOT NULL,
    UNIQUE(repo_id, name)
);

-- Per-player GitHub OAuth tokens
CREATE TABLE IF NOT EXISTS github_tokens (
    player_uuid  TEXT    NOT NULL PRIMARY KEY,
    access_token TEXT    NOT NULL,
    scope        TEXT    NOT NULL,
    created_at   INTEGER NOT NULL
);

-- Maps local GitCraft commit IDs to Git SHAs, per remote.
-- A commit with no row here is unpushed to that remote.
-- SHA is inserted only after a successful push — never speculatively.
CREATE TABLE IF NOT EXISTS commit_git_shas (
    commit_id INTEGER NOT NULL REFERENCES commits(id) ON DELETE CASCADE,
    remote_id INTEGER NOT NULL REFERENCES remotes(id) ON DELETE CASCADE,
    git_sha   TEXT    NOT NULL,
    PRIMARY KEY (commit_id, remote_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_commit_git_shas_sha ON commit_git_shas(git_sha);
```

---

## Scope

**Current phase: 4 of 5.** Do not implement features beyond the current phase. Full roadmap lives in README.md.

When a phase is complete, update this file before starting the next one.

---

## Commands (eventual target — implement per phase)

```
/gitcraft init <name>            ← starts region wand selection
/gitcraft open <name>            ← opens an existing region for editing
/gitcraft commit <msg>           ← exports region + saves commit metadata
/gitcraft log [branch] [page]    ← lists commits for your active repo
/gitcraft reset <id>             ← pastes the target commit's schematic into the world, history unchanged
/gitcraft reset <id> --hard      ← pastes the schematic and permanently deletes all commits after the target ID
/gitcraft branch [name]          ← list branches or create a new branch
/gitcraft checkout <branch>      ← switch to a branch and restore its HEAD schematic
/gitcraft diff [id1 id2]         ← visualize block differences as ghost blocks
/gitcraft merge <branch>         ← three-way merge with interactive conflict resolution
/gitcraft cherry-pick <id>       ← apply a single commit onto the current branch
/gitcraft stash [pop|list]       ← save/restore selection state
/gitcraft repos                  ← list all repos you own

/gitcraft login                  ← GitHub OAuth device flow authentication
/gitcraft logout                 ← remove stored GitHub token
/gitcraft remote add <name> <url>   ← register a GitHub remote for the active repo
/gitcraft remote list               ← list remotes for the active repo
/gitcraft remote remove <name>      ← remove a remote
/gitcraft push [remote]          ← push local commits to GitHub
/gitcraft pull [remote]          ← fetch + import remote commits, restore latest schematic
/gitcraft clone <url> <name>     ← clone a GitHub repo into a new local GitCraft repo
```

---

## Infrastructure Context

- Plugin runs in a **Docker container** (Paper server), managed via **Portainer**
- Schematics are stored on **TrueNAS** — mount path will be provided when relevant
- **GitHub is the remote version control backbone** (Phase 4). No custom REST API container. No Gitea.
- JGit working trees live under `config.yml → github.git-dir` (default: `plugins/GitCraft/git/`)
- Do not hardcode paths — all paths go in `config.yml`

---

## Code Style

- Keep classes small and single-purpose
- Comments should explain *why*, not *what*
- No magic numbers — named constants only
- No `System.out.println` — use Paper's logger: `plugin.getLogger().info(...)`
- All user-facing strings go through a messages config or constant class — no hardcoded chat text

---

## When You're Unsure

1. Do the simpler thing
2. Leave a `// TODO:` comment explaining the uncertainty
3. Do not make architectural decisions unilaterally — flag it for the user
