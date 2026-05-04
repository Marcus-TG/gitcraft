# /gitcraft merge — Implementation Spec

Status: design only. No code yet. Phase 3.

## Goal

Combine source branch `<branch>` into player's currently checked-out branch (target). Three-way merge against common ancestor. Auto-apply non-conflicting block changes. Surface conflicts as purple ghost blocks for manual resolve. Finalize as a normal commit on the target branch.

## User-facing commands

```
/gitcraft merge <branch>          start merge: source -> active branch
/gitcraft merge accept ours       bulk-resolve all conflicts to target side
/gitcraft merge accept theirs     bulk-resolve all conflicts to source side
/gitcraft merge abort             cancel in-progress merge
/gitcraft merge continue          finalize merge -> creates merge commit
/gitcraft merge status            show counts: auto-applied, conflicts pending
```

Note: prompt said `merge accept <branch>`. Renamed to `accept ours|theirs` because "branch" is ambiguous mid-merge — player needs to pick a side, not a branch. **OQ1**: confirm rename.

## State machine

Per-player `MergeSession` lives in memory only (`MergeManager`, `ConcurrentHashMap<UUID, MergeSession>`). No persistence — server restart aborts merge. Player quit clears session (PlayerQuitEvent).

States:
- `NONE` — no merge active
- `RESOLVING` — auto-applies done, ghosts shown, awaiting accept/continue/abort
- (no `FINALIZING` state — `continue` runs synchronously through async pipeline then clears session)

## MergeSession fields

```
UUID         playerId
long         repoId
long         targetBranchId         // active branch at merge start
String       targetBranchName
long         sourceBranchId
String       sourceBranchName
long         targetHeadCommitId     // snapshot of HEAD at start
long         sourceHeadCommitId     // snapshot of source HEAD at start
Long         baseCommitId           // common ancestor; null if unrelated histories
UUID         worldUuid              // pinned from base/target
String       worldName
BoundingBox  region                 // union bbox of base/target/source
Map<BlockVector3, BlockData> autoApplied   // pos -> resolved block (informational, also for abort rollback)
Map<BlockVector3, Conflict>  conflicts     // pos -> conflict
Map<BlockVector3, BlockData> resolutions   // pos -> chosen block (filled by accept and by future per-block resolve)
long         startedAt              // for TTL / stale detection
```

`Conflict`:
```
BlockVector3 pos
BlockData    base       // null if absent in base
BlockData    ours       // target side; null if removed
BlockData    theirs     // source side; null if removed
```

## Conflict definition

For each position in union(base, target, source) bbox:

| base | ours (target) | theirs (source) | result |
|------|---------------|-----------------|--------|
| X    | X             | X               | nothing (no change) |
| X    | Y             | X               | apply Y (target-only change) |
| X    | X             | Y               | apply Y (source-only change) |
| X    | Y             | Y               | apply Y (both changed identically) |
| X    | Y             | Z               | **CONFLICT** (Y != Z, both changed) |
| —    | Y             | Y               | apply Y (both added identically) |
| —    | Y             | Z               | **CONFLICT** (both added differently) |
| X    | —             | —               | apply removal |
| X    | —             | X               | apply removal (target deleted, source untouched) |
| X    | X             | —               | apply removal (source deleted, target untouched) |
| X    | —             | Z (Z!=X)        | **CONFLICT** (delete vs modify) |
| X    | Y (Y!=X)      | —               | **CONFLICT** (modify vs delete) |

"—" = air at that position. Treat AIR/CAVE_AIR/VOID_AIR uniformly (same rule as `DiffService.isAir`).

Equality: WorldEdit `BlockState.equals` (covers block type + properties). Block entities excluded per CLAUDE.md.

## Algorithm (start)

Triggered async from `MergeSubcommand`:

1. **Guard checks (main thread → fail fast → handoff async):**
   - Permission `gitcraft.merge`.
   - Selection has active repo + branch.
   - No existing `MergeSession` for player (must abort first).
   - `<branch>` arg present.

2. **Async load (DB + schematic IO):**
   - Resolve `sourceBranch` by `(repoId, name)` via `BranchDao.findByRepoAndName`. Fail if not found, same as target, etc.
   - Resolve `targetHead` via `HeadDao.findByPlayerAndRepo` → commitId, fall back to `commitDao.findLatestIdByBranch(targetBranchId)`. Fail if branch empty.
   - `sourceHead = commitDao.findLatestIdByBranch(sourceBranchId)`. Fail if empty.
   - **Find common ancestor**:
     - Walk parent chain from `targetHead` via repeated `commitDao.findById(parent)`. Collect IDs into a `LinkedHashSet` (preserves order; cap depth — see OQ2).
     - Walk parent chain from `sourceHead`, return first id that appears in target's set. That's `baseCommitId`.
     - If no intersection → "unrelated histories" error. Abort. (Could later support "no base" merge by treating base as empty world; out of scope now.)
     - **OQ2**: walk depth cap. Suggest 10_000. Need a fast path — repeatedly hitting DB per parent is N queries. Possible optimization: `SELECT id, parent_commit_id FROM commits WHERE branch_id IN (?,?)` once, walk in memory. Defer to perf tuning.
   - Load three commit records (base, target, source). Validate same `world_uuid` across all three; abort with cross-world error otherwise.
   - Load three clipboards via existing `loadClipboard` pattern (copy from `DiffService` or extract a shared loader — see OQ3).

3. **Compute three-way diff (still async):**
   - Compute union bbox: min/max across base, target, source min/max corners.
   - Iterate every (x,y,z) in bbox:
     - `bState = blockAt(baseClip, pos)`, `oState`, `tState` likewise.
     - Apply table above. Build `autoApplied` map for non-conflict, non-noop positions; build `conflicts` map for conflicts.
   - If `conflicts.isEmpty()` and `autoApplied.isEmpty()` → "Already up to date." Don't create session. Don't commit.
   - If `conflicts.isEmpty()` and `autoApplied` non-empty → fast-forward path: still go through `MergeSession` but immediately produce a merge commit (skip RESOLVING; treat as if user ran `continue`). Or simpler: still hand to player to confirm via `continue`. **OQ4**: auto-finalize when no conflicts, or always require confirmation? Suggest auto-finalize (matches Git's fast-forward).

4. **Apply auto-changes to world (main thread):**
   - Use a single `EditSession` (WorldEdit), `ignoreAirBlocks=false`, `maxBlocks=-1`.
   - For each `autoApplied` entry, set the block. (Removals are AIR sets.)
   - Important: this MUTATES the live world before commit. Abort must roll this back — store pre-merge block states for these positions in `MergeSession` (extra field `Map<BlockVector3, BlockData> preMergeWorld`). **OQ5**: alternative is to not mutate world until `continue`, only show ghosts for everything (auto + conflicts). Less surprising to the player, but harder to "see" the result. Recommend mutate-on-start with rollback on abort, matching Git's working-tree behavior.

5. **Spawn ghosts for conflicts (main thread):**
   - Reuse `GhostBlockManager`. Ghost color for conflict = **purple** — add `GhostType.CONFLICT` and a new color constant (`Color.PURPLE` / `Color.fromRGB(170, 0, 255)`).
   - Ghost shows `theirs` block (or `ours` if `theirs` is air; if both air it shouldn't be a conflict). **OQ6**: show "theirs" only? Show alternating? Recommend single ghost showing `theirs` so player sees the *incoming* change overlaid on `ours` (already in world post auto-apply means... wait — see below).
   - Subtlety: after auto-apply step 4, what's in world at conflict positions? It's still `ours` (target side) because we didn't auto-resolve conflicts. Ghost shows `theirs` overlay.

6. **Persist `MergeSession`** in `MergeManager`. Send summary message: "Merging <source> into <target>: N auto-applied, M conflicts. /gitcraft merge accept ours|theirs, /gitcraft merge continue, /gitcraft merge abort."

## Algorithm (accept ours|theirs)

Main-thread guard → async resolve.

- For each `Conflict` in session: write resolution into `resolutions` map (`ours.ours` or `conflict.theirs`; null = AIR).
- Main thread: replace world blocks at conflict positions with chosen side. Clear ghosts. Update session — clear `conflicts` map (or keep but mark resolved).
- Send "All M conflicts resolved to <side>. Run /gitcraft merge continue to commit."
- Do NOT auto-continue — let player inspect.

## Algorithm (continue)

Main thread → guard (must have session, no remaining unresolved conflicts) → async.

- If `conflicts` non-empty and not all in `resolutions` → reject ("N conflicts unresolved").
- The world already reflects merged state (auto-apply at start + accept after). So just commit:
  - Use union bbox as commit region.
  - Reuse `CommitService.commitAsync` BUT with custom parent: pass two parents? Current schema only stores one `parent_commit_id`. **OQ7**: store only `targetHead` as parent (lossy — loses merge graph), or extend schema to add `merge_parent_commit_id` (nullable). Recommend schema v7 adding the second parent. Until then, store `targetHead` as parent and stash source ref in commit message (e.g., prefix `Merge branch 'source' into target\n\n<msg>`).
  - Commit message: auto-generated ("Merge branch '<source>' into <target>"). Allow optional `/gitcraft merge continue <msg>` override. **OQ8**.
- After commit success: clear `MergeSession`, clear ghosts, update HEAD via existing CommitService flow.

## Algorithm (abort)

Main thread.

- Restore world from `preMergeWorld` map (single EditSession).
- Restore conflict positions to `ours` (already in world unless `accept` ran — accept may have changed conflict positions, so include them in preMergeWorld up-front).
- Clear ghosts. Drop session. Confirm "Merge aborted."

## Algorithm (status)

Synchronous. Read session, print counts + branch names + commit ids.

## File layout

Create per CLAUDE.md structure:
```
src/main/java/com/gitcraft/merge/
├── MergeManager.java       // ConcurrentHashMap<UUID, MergeSession>
├── MergeSession.java
├── Conflict.java
├── MergeService.java       // start/accept/continue/abort orchestration
└── ThreeWayDiff.java       // pure compute given three clipboards
src/main/java/com/gitcraft/commands/sub/
└── MergeSubcommand.java    // arg parsing + dispatch to MergeService
```

`GhostType.CONFLICT` added to existing enum. `GhostBlockManager` extended with purple color mapping.

## Wiring

`GitCraft.onEnable`:
- Construct `MergeManager`, `MergeService(plugin, manager, commitDao, branchDao, headDao, repoDao, ghostBlockManager, commitService)`.
- Register `MergeSubcommand` in `GitCraftCommand`.
- Register `MergeManager` as listener for `PlayerQuitEvent` (clear session, no rollback — leaves world in mid-merge state, matches Git working tree).

`plugin.yml`: add `gitcraft.merge` permission.
`Messages.java`: add MERGE_* strings.

## Threading recap (per CLAUDE.md hard rule)

| Step | Thread |
|------|--------|
| Command parse, guard | main |
| DB lookups, schematic load, three-way compute | async |
| World mutation (auto-apply, accept, abort restore) | main (EditSession) |
| Ghost spawn/clear | main |
| `MergeManager` map ops | safe from any (ConcurrentHashMap) |

## Edge cases

- **Region drift**: target HEAD's bbox != source HEAD's bbox. Handled by union bbox; cells outside a clip's region treated as AIR (matches DiffService).
- **Same branch**: `merge <activeBranchName>` → reject "cannot merge branch into itself".
- **Empty source/target**: rejected before three-way diff.
- **Unrelated histories**: no common ancestor — reject. (Future: allow with empty base.)
- **Player offline mid-async**: skip world mutation; session lingers until quit listener clears or TTL. **OQ9**: TTL needed? Suggest 10 min.
- **Concurrent commit during merge**: another player commits to source/target between start and continue. We snapshotted commit IDs at start, so the merge commit's parent is `targetHead` from start time, not current HEAD. Acceptable — matches Git "stale base". Could detect and warn. **OQ10**.
- **Server restart mid-merge**: session lost, world left mid-merge. Player can manually fix or run a future `/gitcraft reset`. Document this clearly.
- **WorldEdit paste scope**: never use `ClipboardHolder.createPaste` for merge — we want surgical per-block sets, not full region overwrite.

## Open questions (consolidated)

- **OQ1**: rename `merge accept <branch>` → `merge accept ours|theirs`. Confirm.
- **OQ2**: parent-walk depth cap and DB query strategy (per-row vs batch select).
- **OQ3**: extract shared `ClipboardLoader` from `DiffService` / `CheckoutSubcommand` duplication.
- **OQ4**: when zero conflicts, auto-finalize the merge or still require `/merge continue`?
- **OQ5**: mutate world on `start` (with rollback on abort) vs hold all changes as ghosts until `continue`.
- **OQ6**: conflict ghost rendering — `theirs` only, or alternating both, or split.
- **OQ7**: schema v7 add `merge_parent_commit_id`, or keep single parent and encode in message.
- **OQ8**: allow custom message via `merge continue <msg>`.
- **OQ9**: session TTL on disconnect / inactivity.
- **OQ10**: stale-base detection if HEAD moved during merge.
- **OQ11**: per-block manual resolve command (e.g. `/gitcraft merge pick ours|theirs` while looking at a ghost) — out of scope for v1?
- **OQ12**: tab completion strategy for `<branch>` arg (need `BranchDao.findNamesByRepo`?).
