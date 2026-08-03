# Canvas 1.21.11 (PixelHaven Fork)

A community-maintained fork based on [CanvasMC](https://github.com/CraftCanvasMC/Canvas), running on Minecraft 1.21.11.

Canvas is a high-performance Folia fork designed to provide a stable and efficient regionized multithreading environment for large-scale servers.

## Fork Features

Building upon vanilla Canvas, this fork ports extensive optimizations and bugfixes from multiple upstream projects, alongside several custom features:

### Config System Rewrite
- Migrated from JSON5 (jankson) to **YAML** (snakeyaml) format
- **Global Configuration** (`config/canvas-server.yml`): Server-wide settings, non-overridable per dimension
- **World Configuration** (`config/canvas-worlds.yml`): Supports per-world overrides (per-dimension `canvas-patch.yml`)
- Automatic kebab-case field naming with runtime reload support

### Updates from Canvas 26.1.2
| Feature | Description |
|---------|-------------|
| **Purpur Alternative Keepalive** | Timestamp-based keepalive mechanism to resolve accidental kick issues in high-latency environments |
| **Region Tick Guards** | Replaces Spigot AsyncCatcher with region-aware thread safety checks, supporting SILENT/LOG/THROW severity levels |
| **Remove MinecraftServer tickables** | Cleans up deprecated code and fixes server GUI non-updating bug |
| **TickGuard Utility Class** | Configurable region thread safety checking framework |

### Optimizations from Spring-for-LeavesMC (14 Patches)
**Performance Optimizations:**
- AI Goal Selector throttling (skips 19 out of 20 ticks in inactive chunks)
- Reduced entity object allocations (cached lambdas)
- Removed hot-path lambda allocations
- Optimized sunburn checks (early exit)
- Skip move() processing for zero-movement entities
- Skip negligible planar movement multiplication
- Faster chunk serialization (Lithium port)

**Bug Fixes:**
- Update suppression crash capture (essential for tech servers)
- Hopper minecart works normally without players nearby
- Falling block entity duplication fix
- Nether portal exit event logic fix
- Chunk reload detector fix
- `preventMovingIntoUnloadedChunks` config fix

### Optimizations & Features Ported from Leaf / Petal / Gale / Lithium
| Feature / Optimization | Description | Source / Ref |
|-----------------------|-------------|--------------|
| **Virtual Thread Support** | Support for Bukkit, Folia Async Schedulers, Chat Executor, and Download Pool | Leaf / Loom |
| **Async Player Data Saving** | Shutdown-safe asynchronous player data saving pipeline | Leaf |
| **DAB (Dynamic Activation of Brains)** | Configurable entity brain activation throttling | Leaf / Pufferfish |
| **Optimized Powered Rails** | Fast redstone propagation and wiring optimization for powered rails (Leaf 0224) | Leaf |
| **Throttle Natural Mob Spawning** | Throttles failed natural mob spawn attempts per category to boost tick rates | Leaf 0229 / Paper PR |
| **BinaryGoalSet AI Navigation** | Replaces GoalSelector set with high-performance `BinaryGoalSet` | Leaf |
| **Line-of-Sight Cache Expiry** | Optimized entity line-of-sight check caching with tick expiration | Leaf |
| **Fast Bit Radix Sort** | Fast bit radix sorting for distance-based entity sorting | Leaf |
| **Netty Transport & `io_uring`** | Configurable Netty transport layer with native `io_uring` support | Leaf / Netty |
| **Biome Zoom Seed Caching** | Caches biome zoom seed obfuscation on world options and player spawn info | Leaf 0174 |
| **Only Tick Items in Hand** | Skips item ticking when not held in main or off hand | Leaf |
| **Remove Streams in Hotpaths** | Replaced Stream API with indexed loops in `BlockBehaviour` blockstate cache & Trial Spawner | Leaf 0129 / 0174 |
| **Lithium Explosion & Fast Allocations** | Fast explosion damage calculator, cached empty arrays & `VALUES_ARRAY` | Lithium / Petal |
| **Reduce Sensor & Block Packet Work** | Reduces entity sensor work frequency and block destruction packet allocations | Petal / Leaf |
| **VarLong & Entity Distance Optimizations** | Fast VarLong size calculations & optimized `distanceToSqr` checks (Leaf 0163) | Leaf |
| **BlockEntityType#isValid Optimization** | Fast lookup for valid block entities without array iterations (Leaf 0208) | Leaf 0208 |
| **Short-Circuit `isOnFire()` Checks** | Early exit on fire tick checks for non-flammable entities | Leaf |
| **Lobotomize Stuck Villagers** | Replaced smart hibernation with lobotomization for stuck villagers | Leaf / Purpur |

### Features from Luminol
| Feature | Description |
|---------|-------------|
| **Linear Region Format** | Custom region file format using LZ4 compression + ZSTD buckets, reducing disk usage by ~50% with faster I/O |
| **Region Compression Level Config** | `regionCompressionLevel` option, adjustable ZSTD levels 1-22 (default 3) |
| **Folia Bug Fixes** | 8 Folia region-threading fixes: volatile references, POI scanning, entity AI/memory safety, leashes, tamed animal teleports, Ender Dragon part sync, movement event race condition protection |

### Optimizations from LeafMC (First Batch, 10+ Patches)
| Patch | Description |
|-------|-------------|
| **Skip Events Without Listeners** | BlockPhysicsEvent / PreCreatureSpawnEvent / VehicleEntityCollisionEvent skipped when no listeners are present |
| **CombatTracker Memory Leak Fix** | MC-301114 fix using EvictingRingList to cap combat logs (10,240 entry limit) |
| **MobEffectUtil Dig Speed Optimization** | Avoids duplicate `getEffect` after `hasEffect` with single lookup |
| **Inventory Iterator Removal** | Hot paths converted from iterators to indexed loops, reducing object allocations |
| **NamespacedKey Caching** | Cached `toString()` and `hashCode()` results |
| **SkeletonHorse Trap Fix** | Prevents ConcurrentModificationException during trap goal iteration |

## Build Instructions

### Requirements

- Java 21+
- Git

### Fast Compilation Verification

```bash
./gradlew :canvas-server:compileJava
```

### Build Runnable Server JAR

```bash
./gradlew createMojmapPublisherJar
```

Built JAR output location: `canvas-server/build/libs/canvas-paperclip-*.jar`.

### Patch Management Workflow

```bash
# 1. Apply all patches to source
./gradlew applyAllPatches

# 2. Edit code... (Minecraft NMS source in canvas-server/src/minecraft/java/)

# 3. Rebuild patches
./gradlew rebuildAllServerPatches
```

> **Note**: Do NOT edit `.patch` files directly in `canvas-server/minecraft-patches/`. Always edit source code in `canvas-server/src/minecraft/java/` and run `rebuildAllServerPatches`.

## Configuration Quick Reference

### Global Configuration (`config/canvas-server.yml`)
- `regionScheduler.affinityScheduler.*` — AFFINITY scheduler configuration
- `chunkSystem.fluidPostProcessingAlgorithm` — Fluid post-processing algorithm (VANILLA / DISABLED / FILTERED)
- `regionFormat` — Region storage format (MCA / LINEAR_V2)
- `regionCompressionLevel` — Linear compression level (1-22)
- `networking.filterVelocityPacket` — Entity velocity packet filtering
- `networking.particleThrottling` — Particle packet throttling
- `networking.nettyTransportType` — Configurable Netty transport (DEFAULT / EPOLL / KQUEUE / IO_URING)
- `performance.asyncPlayerDataSaving.*` — Shutdown-safe async player data saving

### World Configuration (`config/canvas-worlds.yml`)
- `regionBars.*` — Regionized TPS / RAM bossbar
- `visuals.particles.*` — Particle packet toggles
- `entities.entityCollisionMode` — Entity collision mode (VANILLA / ONLY_PUSHABLE_PLAYERS_SMALL / ONLY_PUSHABLE_PLAYERS_LARGE / NO_COLLISIONS)
- `entities.fastOrbs` — Fast XP orb merging & pickup
- `entities.spawning.throttle.*` — Throttle natural mob spawning per category
- `blocks.spawner.*` — Mob spawner parameter tuning
- `farming.*` — Farmland / crop / leaf decay settings
- `performance.dab.*` — Dynamic Activation of Brains settings
- `performance.optimizedPoweredRails` — Fast powered rail propagation toggle

## Credits & Upstream Projects

- [CanvasMC](https://github.com/CraftCanvasMC/Canvas) - Base project
- [Folia](https://github.com/PaperMC/Folia) - Regionized multithreading Minecraft server
- [Paper](https://github.com/PaperMC/Paper) - High performance Minecraft server
- [Leaf](https://github.com/Winds-Studio/Leaf) - Performance & feature optimizations
- [Luminol](https://github.com/LuminolMC/Luminol) - Linear format & Folia fixes
- [Spring-for-LeavesMC](https://github.com/LeavesMC/Leaves) - Performance & bugfix ports
- [StructureLayoutOptimizer](https://modrinth.com/mod/structure-layout-optimizer) - Structure generation optimization
