# GitCraft — Phase 1 Implementation Spec

**Scope (per README + CLAUDE.md):** Region selection + export selected region to `.schem`. No SQLite, no commit metadata, no backend. Those land in Phase 2+.

**Goal of Phase 1:** A player can mark two corner points in the world and run a command that writes a Sponge `.schem` v3 file of that cuboid region to disk via the WorldEdit API. Nothing more.

---

## 1. Reference review — Blockbase (`/tmp/blockbase`)

Reviewed. Limited applicability:

- Blockbase is **Fabric 1.18.2**, not Paper. Mod loader, mappings, and event APIs differ.
- Blockbase tracks **per-block changes** (place/break events into a `CopyOnWriteArrayList`). GitCraft uses **whole-region snapshots**. Different data model.
- No region selection. No WorldEdit integration. No schematic export anywhere in the codebase.
- Useful patterns to borrow:
  - `CopyOnWriteArrayList` for thread-safe in-memory state shared across threads.
  - Single-class command dispatcher (`BlockbaseCommands.java`) keyed by subcommand string — fine for our scale.
  - Clear separation between "tracker/state" class and "command surface" class.

Nothing else from Blockbase informs Phase 1 directly. Selection and export are designed from scratch against the Paper + WorldEdit APIs.

---

## 2. In-memory selection model

### 2.1 What a selection is

A **selection** = one player + one world (UUID) + two `BlockVector3` corner points (pos1, pos2). Both corners must be set and must be in the same world before export is allowed.

Per-player. Each player owns exactly one active selection at a time. New click on a corner overwrites the previous value for that corner.

Selections are **transient** — RAM only, no persistence. Cleared on player quit and on plugin disable. Surviving restarts is not in scope for Phase 1.

### 2.2 `Selection` class (immutable-ish value holder)

Lives at `com.gitcraft.selection.Selection`. Fields:

```
UUID worldId          // world UUID at time of last corner set
BlockVector3 pos1     // null until set
BlockVector3 pos2     // null until set
```

`BlockVector3` = `com.sk89q.worldedit.math.BlockVector3` (WorldEdit type). Using WorldEdit's vector type from the start avoids a translation layer when we hand off to `CuboidRegion` for export.

Methods:
- `setPos1(World world, BlockVector3 v)` / `setPos2(...)` — if `worldId` differs from the new world, both corners reset and the new corner is stored against the new world. Prevents cross-world selections.
- `boolean isComplete()` — both corners set.
- `CuboidRegion toRegion()` — throws if not complete; returns `new CuboidRegion(pos1, pos2)`. WorldEdit normalizes min/max internally, so corner order doesn't matter.

### 2.3 `SelectionManager`

Lives at `com.gitcraft.selection.SelectionManager`. Single instance, held by `GitCraft` main class.

```
ConcurrentHashMap<UUID, Selection> selections   // keyed by player UUID
```

`ConcurrentHashMap` rather than `CopyOnWriteArrayList` — selections are looked up by player UUID on every wand click, so map access dominates. Concurrency matters because the export task runs async and may read the selection while the main thread mutates it (see §5 threading).

API:
- `getOrCreate(UUID player)` — main-thread call from listeners.
- `get(UUID player)` — returns `Optional<Selection>`, used by export.
- `clear(UUID player)` — on player quit.
- `clearAll()` — on plugin disable.

### 2.4 The wand

Wand item: **wooden axe** (`Material.WOODEN_AXE`). Same convention WorldEdit uses; players coming from WE will have muscle memory. Configurable in `config.yml` under `selection.wand-material` so we can swap it later without touching code.

Interaction rules:
- **Left-click block** with wand → set pos1.
- **Right-click block** with wand → set pos2.
- Player must hold the wand in main hand. Off-hand clicks ignored.
- Player must have used `/gitcraft select` at least once this session — explained in §3. This avoids hijacking every wooden axe interaction globally.

Feedback message format (sent to the clicking player, on main thread): `"Pos 1 set: (x, y, z)"` / `"Pos 2 set: (x, y, z)"`. All strings come from a `Messages` constants class per CLAUDE.md style rules.

---

## 3. Commands

Single root command `/gitcraft` with subcommand dispatch. Registered via `plugin.yml`.

### 3.1 Phase 1 subcommands

| Subcommand | Purpose |
|---|---|
| `/gitcraft select` | Enables wand mode for the player. Gives them a wooden axe if they don't have one. Marks the player as "selecting" so wand clicks register. |
| `/gitcraft pos1` / `/gitcraft pos2` | Manual corner set at the player's current standing block. Backup for players without a wand or for precise placement. |
| `/gitcraft clear` | Wipes the player's current selection. |
| `/gitcraft export <name>` | Phase 1 export trigger. Writes `<name>.schem` to the configured schematic directory. Phase 2 replaces this with `/gitcraft commit <msg>`. |

`commit`, `push`, `pull`, `log` are **out of scope** for Phase 1 — they appear in later phases per README.

### 3.2 Command class layout

- `com.gitcraft.commands.GitCraftCommand implements CommandExecutor, TabCompleter` — the single registered executor. Routes on `args[0]` to subcommand handler classes.
- `com.gitcraft.commands.sub.SelectSubcommand`, `Pos1Subcommand`, `Pos2Subcommand`, `ClearSubcommand`, `ExportSubcommand` — one class per subcommand, each with `execute(Player, String[])` and `tabComplete(...)`. Keeps each class small per CLAUDE.md.
- All subcommands require `sender instanceof Player`. Console use is rejected with a message — selection is inherently per-player.

### 3.3 Permissions

Single permission node `gitcraft.use`, default `true`. Phase 1 doesn't need finer granularity. Declared in `plugin.yml`.

### 3.4 Tab completion

Top-level: completes against the five subcommands above. `/gitcraft export <TAB>` returns nothing in Phase 1 (no saved schems to suggest yet — that's Phase 2 territory once the DB exists).

---

## 4. WorldEdit API — schematic export

### 4.1 Dependency

WorldEdit-Bukkit added as a `compileOnly` Gradle dependency. Runtime presence is required and asserted in `onEnable` via `Bukkit.getPluginManager().getPlugin("WorldEdit") != null`; if absent, log error and disable self.

Maven coords (EngineHub repo): `com.sk89q.worldedit:worldedit-bukkit:7.3.x` — exact version pinned to whatever ships with the latest WorldEdit release compatible with Paper 1.21.4. Confirmed against EngineHub Javadoc before committing the version.

### 4.2 Classes used

All under `com.sk89q.worldedit.*` unless noted:

| Class | Role |
|---|---|
| `BukkitAdapter` | `BukkitAdapter.adapt(org.bukkit.World)` → `com.sk89q.worldedit.world.World`. Bridge from Paper world to WorldEdit world. |
| `BlockVector3` | Corner points (already used in `Selection`). |
| `regions.CuboidRegion` | The selection as a WorldEdit region. Built from the two `BlockVector3` corners + WE world. |
| `extent.clipboard.BlockArrayClipboard` | In-memory clipboard sized to the region. |
| `function.operation.ForwardExtentCopy` | The copy operation: source extent (the world) → destination extent (the clipboard). |
| `function.operation.Operations` | `Operations.complete(copy)` runs the copy synchronously on the calling thread. |
| `EditSession` + `WorldEdit.getInstance().newEditSessionBuilder()...build()` | Source extent for the copy. Built with the WE world, no actor, no max-blocks limit. |
| `extent.clipboard.io.BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC` | Output format. **v3, not v2** — v3 is current and is what modern WorldEdit writes by default. v2 stays readable. |
| `extent.clipboard.io.ClipboardWriter` | Obtained from the format; writes the clipboard to an `OutputStream`. |

### 4.3 Block-entity exclusion

CLAUDE.md: block entities (chests, signs, spawners, …) are excluded — we capture structure, not contents.

WorldEdit's default `ForwardExtentCopy` copies tile-entity NBT. To strip it, set `copy.setCopyingEntities(false)` (entities like item frames, paintings) **and** filter block-entity NBT from the clipboard before write. The simplest approach: after the copy completes, walk the clipboard and for each `BaseBlock` with NBT, replace it with a NBT-less variant via `clipboard.setBlock(pos, baseBlock.toBaseBlock(null))` — confirm exact API shape against the 7.3.x Javadoc before implementation. If the API has changed, fall back to using a custom extent that drops NBT during copy.

Decision flagged for the user: confirm before writing code that "structure only" means signs come back blank, chests come back empty, etc. That is what CLAUDE.md says, but worth a sanity check.

### 4.4 Output path

Read from `config.yml` → `schematics.directory`. Default: `plugins/GitCraft/schematics/`. Path resolved relative to the plugin data folder if not absolute.

Filename: `<name>.schem` where `<name>` is the export argument. Validated against `^[a-zA-Z0-9_-]{1,64}$` to keep filesystem-safe. Existing file → fail with a clear message; no overwrite in Phase 1.

### 4.5 The export pipeline (sequence)

1. Player runs `/gitcraft export myhouse`.
2. Main-thread handler:
   - Look up player's `Selection`.
   - Validate: complete, world still loaded, name format OK, target file doesn't exist.
   - Snapshot the two `BlockVector3` corners and the world reference into local variables.
   - Capture the player's current world via `BukkitAdapter.adapt(player.getWorld())` on the main thread (the adapt call itself is cheap and main-thread-safe).
   - Hand off to async via `Bukkit.getScheduler().runTaskAsynchronously`.
3. **Async:**
   - Build `CuboidRegion`, `BlockArrayClipboard`, `EditSession`, `ForwardExtentCopy`.
   - **CAVEAT:** WorldEdit's read path through `EditSession` reads world chunks. Reading chunks off the main thread is normally unsafe in vanilla Bukkit. Paper has `World.getChunkAtAsync` and async-safe chunk access in some paths, and WorldEdit-Bukkit's adapter is generally documented as supporting async edits, but this needs confirmation against the specific WE 7.3.x + Paper 1.21.4 combo before relying on it. **If async chunk reads are unsafe**, the alternative is: read blocks on the main thread into a pre-allocated `BlockArrayClipboard` in chunks of N blocks per tick (region snapshotting via `runTaskTimer`), then write the file async. This is more code; we only build it if the simpler async path proves unsafe. Flagging for user decision.
   - Run `Operations.complete(copy)`.
   - Strip block-entity NBT (§4.3).
   - Open `FileOutputStream` to target path, get `ClipboardWriter` from `SPONGE_V3_SCHEMATIC`, write, close.
   - Log success / failure via `plugin.getLogger()`.
4. Schedule a main-thread task with `runTask` to message the player with the result. **Never message from the async thread** — Bukkit's player API is main-thread-only.

### 4.6 Errors

All caught in the async block. `IOException` → "Failed to write schematic: <msg>". `WorldEditException` → "Schematic export failed: <msg>". Stack trace logged at `WARNING` via `plugin.getLogger()`.

---

## 5. Threading summary

| Operation | Thread |
|---|---|
| Wand click event handler | Main |
| Selection mutation (`SelectionManager` writes) | Main |
| Selection read | Main, **and** async (export reads it) — hence `ConcurrentHashMap` and immutable snapshot of corners taken on main thread before handoff |
| Command dispatch | Main |
| WorldEdit copy + file write | Async (subject to §4.5 caveat) |
| Player chat feedback after async work | Main (re-scheduled via `runTask`) |

Filesystem creation of `plugins/GitCraft/schematics/` on first run: also async, triggered from `onEnable` via `runTaskAsynchronously`. Per CLAUDE.md, even directory creation is FS work and stays off the main thread.

---

## 6. File / class plan

```
src/main/
├── java/com/gitcraft/
│   ├── GitCraft.java                  # onEnable: load config, register listener+command, init SelectionManager. onDisable: clear selections.
│   ├── config/
│   │   └── GitCraftConfig.java        # Typed access to config.yml values (wand material, schem dir).
│   ├── selection/
│   │   ├── Selection.java
│   │   └── SelectionManager.java
│   ├── listeners/
│   │   └── WandListener.java          # PlayerInteractEvent — thin: route wand clicks to SelectionManager.
│   ├── commands/
│   │   ├── GitCraftCommand.java
│   │   └── sub/
│   │       ├── SelectSubcommand.java
│   │       ├── Pos1Subcommand.java
│   │       ├── Pos2Subcommand.java
│   │       ├── ClearSubcommand.java
│   │       └── ExportSubcommand.java
│   ├── export/
│   │   └── SchematicExporter.java     # The async pipeline in §4.5. One public method: export(Player, Selection, String name, Callback).
│   └── util/
│       └── Messages.java              # All user-facing strings as constants.
└── resources/
    ├── plugin.yml                     # name, main, version, api-version: 1.21, depend: [WorldEdit], commands, permissions
    └── config.yml                     # selection.wand-material, schematics.directory
```

No `database/` package yet — that's Phase 2. No `api/` — that's Phase 3+.

---

## 7. Build / `build.gradle` deltas

- Java 21 toolchain.
- `paper-api 1.21.4-R0.1-SNAPSHOT` `compileOnly`.
- WorldEdit-Bukkit 7.3.x `compileOnly` from EngineHub maven (`https://maven.enginehub.org/repo/`).
- ShadowJar **not needed** in Phase 1 — no third-party libs are bundled; WorldEdit is provided by the server.

---

## 8. Acceptance criteria for Phase 1

Phase 1 is done when all of the following pass on a real Paper 1.21.4 server with WorldEdit installed:

1. `/gitcraft select` gives a wooden axe and enables wand mode.
2. Left/right clicks set pos1/pos2 with chat feedback showing coords.
3. `/gitcraft pos1` and `/gitcraft pos2` set corners at the player's standing block.
4. `/gitcraft clear` clears the selection.
5. `/gitcraft export <name>` writes `<name>.schem` to the configured directory.
6. The resulting file opens in WorldEdit (`//schematic load <name>` + `//paste`) and reproduces the captured cuboid.
7. Block entities (chests/signs) come back **structurally present but contentless** — empty chests, blank signs.
8. Cross-world selections are blocked.
9. No main-thread DB or FS work — verified by inspection (no DB in Phase 1; FS work confined to async tasks).
10. Plugin disables cleanly with no errors when WorldEdit is missing.

---

## 9. Open questions for user

1. **§4.3** — Confirm "structure only" is the intent: chests come back empty, signs blank, spawners reset to default (pig)? Or do we want a configurable toggle from day one?
2. **§4.5** — OK to attempt the simple async path first (WE copy + write off-thread), and only fall back to the chunked main-thread snapshot if testing shows chunk-read crashes? Or prefer the safer chunked approach upfront?
3. **§3.1** — Is `/gitcraft export` an acceptable Phase 1 command name, knowing it'll be replaced by `/gitcraft commit` in Phase 2? Alternative: skip a user-facing export command entirely in Phase 1 and ship `/gitcraft commit` directly with a stubbed DB write — but that crosses the phase boundary.
4. **§4.1** — Pin to a specific WorldEdit-Bukkit 7.3.x release? Latest stable at time of writing, or a known-good version you've tested?
