# Canvas 1.21.11 (PixelHaven Fork)

Canvas is a Minecraft server fork based on Folia (Paper's regionized multithreading fork), providing performance optimizations, configuration extensions, and API enhancements.

This branch (`ver/1.21.11`) is a 1.21.11 version fork maintained by PixelHaven. Upstream Canvas mainline has migrated to `ver/26.1.2`.

## Project Architecture

### Patch System

Canvas uses the paperweight-weaver patch system to modify upstream code in layers:

```
Vanilla Minecraft → Paper Patches → Folia Patches → Canvas Patches
```

**Patch Directory Structure:**

| Directory | Purpose | Count |
|-----------|---------|-------|
| `canvas-server/minecraft-patches/base/` | Modifies Minecraft sources (NMS) | 22 |
| `canvas-server/paper-patches/base/` | Modifies Paper/Folia server code | 13 |
| `canvas-api/paper-patches/base/` | Modifies API layer code | 4 |

Patches are applied in numerical order. After modifying patches, run `rebuildAllServerPatches` to regenerate them.

### Source Directories

**Canvas Custom Code (`canvas-server/src/main/java/io/canvasmc/canvas/`):**

| Directory | Description |
|-----------|-------------|
| `GlobalConfiguration.java` | Global configuration (YAML, `config/canvas-server.yml`) |
| `WorldConfig.java` | Per-world configuration (YAML, `config/canvas-worlds.yml` + per-dimension `canvas-patch.yml`) |
| `configuration/` | YAML config framework (ConfigurationProvider, Part, Style, Resolver, Validator, NodeDiff, Token) |
| `command/sub/` | Command implementations (reload, tpsbar, world-distance, set-max-players) |
| `tick/` | Scheduler related (AffinitySchedulerThreadPool, SchedulerUtil, ScheduledHandleTickState) |
| `util/` | Utility classes (TickGuard, CanonicalReference, Util, FasterRandomSource) |
| `world/entity/` | EnderPearls management |
| `world/waypoints/` | Waypoint system |
| `world/chunk/` | BalancedChunkSystem |
| `spark/` | Spark profiler integration |

**Canvas API (`canvas-api/src/main/java/io/canvasmc/canvas/`):**

| Directory | Description |
|-----------|-------------|
| `event/` | Custom events (PlayerPostRespawnAsyncEvent, etc.) |
| `region/` | Regionized API |
| `simd/` | SIMD detection |

### Generated Directories (Do Not Edit Directly)

| Directory | Description |
|-----------|-------------|
| `paper-server/` | Server code after Paper patches applied |
| `paper-api/` | API code after Paper patches applied |
| `folia-server/` | Server code after Folia patches applied |
| `folia-api/` | API code after Folia patches applied |

### Configuration System

Configuration migrated from JSON5 (old `Config.java`) to YAML (`GlobalConfiguration` + `WorldConfig`).

**GlobalConfiguration** (Global, cannot be overridden per-world):
- `regionScheduler.*` — Scheduler configuration (affinity, tick rate, guard severity)
- `chunkSystem.*` — Chunk system (thread priority, fluid processing, structure optimization)
- `networking.*` — Networking (packet filtering, keepalive, protocol switching)
- `vanillaFixes.*` — MC bugfix toggles
- `chat.*` — Chat reporting disable
- `purpurContainers.*` — Container row configuration
- `combat.*` — Combat configuration (partially migrated to WorldConfig)

**WorldConfig** (Per-world, overridable in per-dimension `canvas-patch.yml`):
- `regionBars.*` — TPS/RAM bar
- `visuals.*` — Particle and flame rendering
- `entities.*` — Collision mode, projectiles, XP orbs, skeleton accuracy
- `combat.*` — Attack delay, crits, sweeping edge, sword blocking
- `blocks.spawner.*` — Mob spawner configuration
- `farming.*` — Farming (farmland, crops, leaf decay)
- `sleeping.*` — Sleep configuration

## Build Instructions

### Requirements

- Java 21+
- Git

### Build Server JAR

```bash
# Build complete server JAR (for deployment)
./gradlew createMojmapPublisherJar
```

Artifacts are located in `canvas-server/build/libs/`.

### Development Workflow

```bash
# 1. Apply all patches (first time or after patch update)
./gradlew applyAllPatches

# 2. Edit source code...
#    - Canvas custom code: edit canvas-server/src/ or canvas-api/src/ directly
#    - Minecraft source: edit canvas-server/src/minecraft/java/
#    - Paper/Folia code: edit paper-server/src/ or folia-server/src/

# 3. Rebuild patches (convert source changes to .patch files)
./gradlew rebuildAllServerPatches

# 4. Verification build
./gradlew createMojmapPublisherJar

# 5. Test launch
java -Xmx2G -jar canvas-server/build/libs/canvas-paperclip-*.jar --nogui
```

### Common Gradle Tasks

| Task | Description |
|------|-------------|
| `applyAllPatches` | Apply all patches to source |
| `rebuildAllServerPatches` | Rebuild all server patches |
| `rebuildMinecraftBasePatches` | Rebuild Minecraft base patches only |
| `rebuildMinecraftSourcePatches` | Rebuild Minecraft source patches only |
| `rebuildServerBasePatches` | Rebuild Paper/Folia base patches |
| `createMojmapPublisherJar` | Build full server JAR |
| `:canvas-server:compileJava` | Compile server only (fast verification) |

### Proper Way to Modify Patches

1. `./gradlew applyAllPatches` — Apply patches to working directory
2. Edit files in `canvas-server/src/minecraft/java/` or `paper-server/src/`
3. `./gradlew rebuildAllServerPatches` — Regenerate `.patch` files from working directory
4. Commit changes to `.patch` files

**Do NOT** edit `.patch` files directly; modify source code and rebuild instead.

## Key Config Reference Mapping

Reference for migrating from old `Config.INSTANCE` to the new system:

```
Config.INSTANCE.scheduler.*           → GlobalConfiguration.getInstance().regionScheduler.affinityScheduler.*
Config.INSTANCE.chunks.*              → GlobalConfiguration.getInstance().chunkSystem.*
Config.INSTANCE.networking.*          → GlobalConfiguration.getInstance().networking.*
Config.INSTANCE.fixes.*               → GlobalConfiguration.getInstance().vanillaFixes.*
Config.INSTANCE.enableNoChatReports   → GlobalConfiguration.getInstance().chat.disableChatReporting
Config.INSTANCE.containers.*          → GlobalConfiguration.getInstance().purpurContainers.*
Config.INSTANCE.fetchRespawnDimensionKey() → GlobalConfiguration.fetchRespawnDimensionKey()

# Migrated to per-world config:
Config.INSTANCE.particles.*           → WorldConfig.getDefaults().visuals.particles.*
Config.INSTANCE.combat.*              → WorldConfig.getDefaults().combat.*
Config.INSTANCE.spawner.*             → WorldConfig.getDefaults().blocks.spawner.*
Config.INSTANCE.entityCollisionMode   → WorldConfig.getDefaults().entities.entityCollisionMode
Config.INSTANCE.fastOrbs              → WorldConfig.getDefaults().entities.fastOrbs
Config.INSTANCE.projectiles.*         → WorldConfig.getDefaults().entities.projectiles.*
```

## Git Workflow

- **Main Branch**: `ver/1.21.11`
- **Upstream Remote**: `origin` → `https://github.com/Holywuya/Canvas-PixelHavenFork.git`
- **Upstream Canvas**: `upstream` → `https://github.com/CraftCanvasMC/Canvas.git` (`ver/26.1.2`)
- **Folia Base**: `foliaCommit = 3ef0ba66b20599d24f235ac795865047c29c5eb4`

## Important Notes

- Mainland China builds require Aliyun Maven mirror configuration (configured in `build.gradle.kts` and `settings.gradle.kts`)
- Files in `canvas-server/src/minecraft/java/` are generated by patches; after editing, patches must be rebuilt
- `paper-server/`, `folia-server/` etc. are generated directories; do not edit directly
- Configuration system uses YAML (snakeyaml), field names auto-convert to kebab-case
- `WorldConfig` uses lazy initialization; `getDefaults()` returns default instance prior to server start
