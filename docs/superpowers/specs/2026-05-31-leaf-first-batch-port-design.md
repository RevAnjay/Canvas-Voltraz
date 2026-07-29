# Leaf First Batch Low-Risk Patch Port Design

## Background

The current project is Canvas-PixelHavenFork `ver/1.21.11`, based on Folia's region-threading model. Leaf `ver/1.21.11` is a Paper fork with a large number of patches, containing extensive changes related to async operations, parallelism, trackers, and world ticking.

This design document covers only the first batch of low-risk porting candidates. The goal is not a complete migration of Leaf, but selecting patches that have no threading model assumptions, are localized, and easy to verify item-by-item, serving as the foundation for future porting work.

## Goals

Port the first batch of low-risk Leaf patches, prioritizing the following benefits:

- Skip constructing and dispatching certain Bukkit events when there are no listeners, reducing hot-path allocations.
- Introduce localized micro-optimizations to reduce redundant queries, iterators, and string allocations.
- Introduce explicit vanilla bugfixes to lower memory leak or crash risks.

Each patch must be independently implemented, verified, and committed.

## Non-Goals

This batch explicitly does NOT cover:

- `Async Pathfinding`.
- `Async Mob Spawning`.
- `Async Chunk Sender`.
- `Async Playerdata Saving`.
- `Multithreaded Tracker`.
- `Parallel World Ticking`.
- Complete porting of Leaf, Gale, or Purpur configuration systems.
- Any patch that alters Folia region ownership semantics.

These patches require separate dedicated designs and cannot directly adopt Leaf's implementations.

## Proposed Strategy

Adopt a "small patch queue" approach: each Leaf patch is independently evaluated, ported, and verified. Implementation targets generated source files first, followed by rebuilding patch files through the project's patch system.

This batch contains 8 candidates:

1. `0256-Skip-BlockPhysicsEvent-if-no-listeners.patch`
2. `0264-Skip-PreCreatureSpawnEvent-if-no-listeners.patch`
3. `0311-Skip-VehicleEntityCollisionEvent-if-no-listeners.patch`
4. `0214-Optimise-MobEffectUtil-getDigSpeedAmplification.patch`
5. `0190-Remove-iterators-from-Inventory.patch`
6. `0021-Cache-namespacedKey-toString-and-hash.patch`
7. `0313-Fix-MC-301114-Combat-Tracker-memory-leak.patch`
8. `0322-fix-skeleton-horse-trap-NPE.patch`

## File Boundaries

### Minecraft/NMS Patches

The following patches modify generated sources under `canvas-server/src/minecraft/java/` and are eventually rebuilt into `canvas-server/minecraft-patches/base/`:

- `net/minecraft/world/level/redstone/NeighborUpdater.java`
- `net/minecraft/world/level/NaturalSpawner.java`
- `net/minecraft/world/entity/vehicle/boat/AbstractBoat.java`
- `net/minecraft/world/entity/vehicle/minecart/AbstractMinecart.java`
- `net/minecraft/world/entity/vehicle/minecart/NewMinecartBehavior.java`
- `net/minecraft/world/entity/vehicle/minecart/OldMinecartBehavior.java`
- `net/minecraft/world/effect/MobEffectUtil.java`
- `net/minecraft/world/entity/player/Inventory.java`
- `net/minecraft/world/damagesource/CombatTracker.java`
- `net/minecraft/world/entity/animal/equine/SkeletonHorse.java`
- `net/minecraft/world/entity/animal/equine/SkeletonTrapGoal.java`

### API Patches

The following patches modify Paper API generated sources in `canvas-api` or API patches, eventually rebuilt into `canvas-api/paper-patches/base/`:

- `org/bukkit/NamespacedKey.java`

### Canvas Own Code

If fixing `CombatTracker` memory leak requires introducing a bounded list, Canvas utility classes should be created in preference to referencing Leaf package names:

- `canvas-server/src/main/java/io/canvasmc/canvas/util/collection/EvictingRingList.java`

If the implementation can be completed using existing JDK collections, this class will not be added.

## Configuration Strategy

The first batch adds no new configuration options by default for the following reasons:

- Event skipping patches take effect only when there are no listeners, preserving behavior when listeners exist.
- `MobEffectUtil`, `Inventory`, and `NamespacedKey` are localized equivalent optimizations.
- `SkeletonHorse` fix is a bugfix.

The `CombatTracker` memory leak fix requires capping historical entries. To avoid introducing Leaf's configuration system, a fixed upper limit is used, requiring verification against Paper PRs or vanilla upstream for reasonable defaults. If configuration is mandatory, it will be placed in `GlobalConfiguration.vanillaFixes`, enabled by default, using Leaf's upper limit or Paper PR values.

## Threading Model Constraints

Implementations must strictly follow these rules:

- No new async world/entity/chunk access.
- Do not relax `TickThread.ensureTickThread` or Folia ownership checks.
- Do not introduce Leaf's world-level tick thread assumptions.
- All event invocations remain at their original execution points, skipping event object creation and `callEvent` only when no listeners are present.
- Do not cache `getRegisteredListeners().length` results into cross-tick, cross-thread global state.

## Testing and Verification

Each patch requires at minimum:

1. `./gradlew applyAllPatches` succeeds.
2. After modifying generated sources, submit fixups and rebuild patches following the Canvas patch workflow.
3. `./gradlew :canvas-server:compileJava` succeeds.
4. If API is modified, run `./gradlew :canvas-api:compileJava`.
5. Run `./gradlew createMojmapPublisherJar` at the end of the batch.

API or utility classes that can have unit tests should include them. NMS behavior patches focus primarily on compilation and minimal runtime verification, as the workspace lacks a stable NMS unit testing harness.

## Risks and Mitigation

| Risk | Mitigation |
|---|---|
| Leaf patch already included in current Canvas or upstream | Grep target sources and patches before each task to verify existing logic |
| Event skipping changes plugin semantics | Skip only when `HandlerList` has no registered listeners, preserving original flow otherwise |
| `CombatTracker` fix introduces Leaf package name or config system | Use Canvas package name or JDK implementations without referencing Leaf config classes |
| API patch conflicts with Paper/Canvas existing changes | Process `NamespacedKey` independently, compiling API before proceeding |
| NMS patch rebuild fails | Follow CLAUDE.md workflow to modify generated sources and rebuild, never edit `.patch` files directly |

## Acceptance Criteria

Completion of this batch requires:

- Each of the 8 candidate patches ported has an independent commit.
- Skipped candidates must state the reason in commit notes or documentation (e.g. "already exists" or "conflicts with Folia").
- No introduction of Leaf, Gale, or Purpur configuration frameworks.
- No introduction of new async world/entity/chunk access.
- `./gradlew :canvas-server:compileJava` passes.
- When API is involved, `./gradlew :canvas-api:compileJava` passes.
- At batch completion, `./gradlew createMojmapPublisherJar` passes, or if failing, the failure reason is explicit and unrelated to this batch.

## Self-Check Results

- Placeholder check: No `TODO`, `TBD`, or `future implementation` placeholders.
- Scope check: Limited strictly to the first batch of 8 low-risk patches; no high-risk async or threading model patches included.
- Consistency check: Configuration strategy, threading model constraints, and acceptance criteria are fully consistent in forbidding Leaf config systems and async world access.
