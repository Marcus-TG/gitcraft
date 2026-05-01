# GitCraft

Git-like version control for Minecraft builds. A Paper plugin paired with a self-hosted backend that lets players snapshot, share, and version regions of their world.

## What it is

GitCraft treats player-selected regions as commits. Each commit captures the structure of a region as a `.schem` file along with metadata (author UUID, timestamp, message). Snapshots are stored locally first, then pushed to a backend that fronts a Gitea repository — giving builds the same `commit / push / pull / fork` workflow developers expect from source control.

GitCraft is **not** a block-change audit log. It does not track every block placed or broken. It captures whole-region snapshots on demand.

## Tech stack

| Layer            | Choice                                  |
|------------------|-----------------------------------------|
| Plugin platform  | Paper API (Paper 1.21.4)                |
| Language         | Java 21                                 |
| Build tool       | Gradle                                  |
| Schematic format | Sponge Schematic (`.schem`, v2/v3)      |
| Schematic I/O    | WorldEdit API (library dependency)      |
| Local storage    | SQLite (embedded)                       |
| Backend (Phase 3+) | REST API in Docker                    |
| Version backbone (Phase 5) | Gitea                         |
| Deployment       | Docker + Portainer                      |

## Phase roadmap

| Phase | What gets built |
|-------|----------------|
| 1 | Region selection + export selected region to `.schem` |
| 2 | `/gitcraft commit` — attaches player UUID + timestamp, saves schem locally |
| 3 | REST API backend container — store and retrieve schematics |
| 4 | `/gitcraft push` / `/gitcraft pull` — in-game commands to talk to the API |
| 5 | Gitea integration + web UI for browsing and forking builds |

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
    │   └── api/                   # REST client (Phase 3+)
    └── resources/
        ├── plugin.yml
        └── config.yml
```

## Prerequisites

- **JDK 21** (Temurin or equivalent)
- **Gradle 8+** (or use the included wrapper, `./gradlew`)
- **Paper 1.21.4** server for local testing
- **WorldEdit** plugin installed on that test server (runtime dependency)
- Git

## Build

```bash
./gradlew build
```

Compiled plugin JAR lands in `build/libs/`.

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
