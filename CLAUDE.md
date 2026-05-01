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
    │   └── api/                   ← REST client for backend (Phase 4+)
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

### Core SQLite Schema (Phase 1–2)

```sql
CREATE TABLE IF NOT EXISTS commits (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT    NOT NULL,
    player_name TEXT    NOT NULL,
    region_name TEXT    NOT NULL,
    message     TEXT,
    schem_path  TEXT    NOT NULL,
    created_at  INTEGER NOT NULL  -- Unix epoch milliseconds
);
```

This schema is append-only. Do not modify committed rows.

---

## Scope

**Current phase: 3 of 5.** Do not implement features beyond the current phase. Full roadmap lives in README.md.

When Phase 1 is complete, the user will update this file before starting the next phase.

---

## Commands (eventual target — implement per phase)

```
/gitcraft init <name>   ← starts region wand selection
/gitcraft open <name>   ← opens an existing region for editing
/gitcraft commit <msg>  ← exports region + saves commit metadata
/gitcraft log           ← lists commits for your regions
/gitcraft restore <id>  ← rolls region back to a prior commit
/gitcraft push          ← uploads schem to backend
/gitcraft pull <id>     ← downloads schem from backend and pastes it
```

---

## Infrastructure Context

- Plugin runs in a **Docker container** (Paper server), managed via **Portainer**
- Schematics are stored on **TrueNAS** — mount path will be provided when relevant
- Backend API will be a separate Docker container (Phase 4)
- Gitea is the eventual version control backbone (Phase 5)
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
