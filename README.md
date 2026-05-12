# GitCraft

Git-like version control for Minecraft builds. A Paper plugin paired with a self-hosted backend that lets players snapshot, share, and version regions of their world.

## What it is

GitCraft treats player-selected regions as commits. Each commit captures the structure of a region as a `.schem` file along with metadata (author UUID, timestamp, message). Snapshots are stored locally and can be pushed to GitHub via JGit — giving builds the same `commit / branch / push / pull / clone` workflow developers expect from source control.

GitCraft is **not** a block-change audit log. It does not track every block placed or broken. It captures whole-region snapshots on demand.

## Tech stack

| Layer              | Choice                                        |
|--------------------|-----------------------------------------------|
| Plugin platform    | Paper API (Paper 1.21.4)                      |
| Language           | Java 21                                       |
| Build tool         | Gradle + shadowJar (`com.gradleup.shadow`)    |
| Schematic format   | Sponge Schematic (`.schem`, v2/v3)            |
| Schematic I/O      | WorldEdit API (library dependency)            |
| Local storage      | SQLite (embedded)                             |
| Remote VCS         | GitHub (via JGit — pure Java, no native git)  |
| Git auth           | GitHub OAuth device flow → HTTPS token        |
| Deployment         | Docker + Portainer                            |

## Phase roadmap

| Phase | Status | What gets built |
|-------|--------|----------------|
| 1 | ✅ Done | Region selection + export selected region to `.schem` |
| 2 | ✅ Done | `/gitcraft commit` — attaches player UUID + timestamp, saves schem locally |
| 3 | ✅ Done | Branches, `/gitcraft log`, `/gitcraft reset`, merge, cherry-pick, diff, stash |
| 4 | **In progress** | GitHub integration — login (OAuth device flow), remote, push, pull, clone via JGit |
| 5 | Planned | Web UI for browsing and forking builds |

Current phase is tracked in `CLAUDE.md`. Do not implement features beyond the current phase without bumping it there first.

## Project layout

```
gitcraft/
├── build.gradle
├── settings.gradle
├── CLAUDE.md
└── src/main/
    ├── java/com/gitcraft/
    │   ├── GitCraft.java          # Main plugin class
    │   ├── listeners/             # Event handlers (thin, no logic)
    │   ├── database/              # SQLite connection, schema, DAOs
    │   ├── commands/              # /gitcraft subcommands
    │   ├── export/                # Schematic generation via WorldEdit
    │   ├── commit/                # CommitService pipeline
    │   ├── diff/                  # DiffService, GhostBlockManager
    │   ├── merge/                 # MergeService, CherryPickService, OpManager
    │   ├── github/                # OAuth device flow
    │   └── git/                   # JGit wrappers (push, pull, clone, commit mapping)
    └── resources/
        ├── plugin.yml
        └── config.yml
```

## Prerequisites

- **JDK 21** (Temurin or equivalent)
- **Gradle 8+** (or use the included wrapper, `./gradlew`)
- **Paper 1.21.4** server for local testing
- **WorldEdit** plugin installed on that test server (runtime dependency)
- A pre-created GitHub repo for push testing. JGit cannot create repos, so the GitHub repo for a build must exist before the first push.

## Build

```bash
./gradlew shadowJar
```

The shadow JAR (with JGit and SQLite bundled) lands in `build/libs/`. The plain `jar` task is not used — always use `shadowJar` or `build` (which depends on it).

## Run locally

1. Set up a local Paper 1.21.4 test server (any directory outside this repo).
2. Install WorldEdit into the test server's `plugins/` folder.
3. Copy the built JAR into `plugins/`:
   ```bash
   cp build/libs/gitcraft-*.jar /path/to/paper-server/plugins/
   ```
4. Start the server:
   ```bash
   cd /path/to/paper-server
   java -Xmx2G -jar paper-1.21.4.jar nogui
   ```
5. On first launch the plugin generates `plugins/GitCraft/config.yml` and the SQLite database. Stop the server, edit config as needed, restart.

For iterative development, symlink the JAR instead of copying:

```bash
ln -sf "$(pwd)/build/libs/gitcraft-0.1.0.jar" /path/to/paper-server/plugins/gitcraft.jar
```

Then `./gradlew build` + `/reload confirm` (or full restart) picks up changes.

## Configuration

All runtime paths live in `config.yml`. Do not hardcode paths in source. Schematic storage path will eventually point at a TrueNAS mount in production; locally it defaults to `plugins/GitCraft/schematics/`.

For Phase 4, GitCraft also stores local JGit working trees under:

```yaml
github:
  git-dir: git     # Local JGit working trees (default: plugins/GitCraft/git/)
```

## Threading rules (read before contributing)

The hard rule: **never touch the database or filesystem from the main thread.** Event handlers fire on the main thread and must hand off to the async scheduler:

```java
Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
    // db / file work here
});
```

Violations cause server lag that won't show up at low player counts and will be painful to debug later. Get it right from the start. Full constraints are in `CLAUDE.md`.

## Contributing

Read `CLAUDE.md` first — it is the source of truth for architecture, scope, and style. Stay within the current phase. When unsure, do the simpler thing and leave a `// TODO:` for the user to review.

## License

MIT — see [LICENSE](LICENSE) for details.
