# Canvas 1.21.11 Upstream Update Backup Instructions

## Backup Contents

### Added Files (`backup/src/`)
- `GlobalConfiguration.java` — New global configuration system (YAML, replacing old Config.java)
- `WorldConfig.java` — Per-world configuration system
- `configuration/` — YAML configuration framework (11 files)
- `TickGuard.java` — Region thread safety checks (replacing AsyncCatcher)
- `CanonicalReference.java` — Disposable reference utility
- `UpdateSuppressionException.java` — Update suppression exception capture
- `Util.java` — Updated version (added gradient features)
- `lithium/` — Lithium ports (HashPalette, Equipment Tracking)

### Modified Build Files (`backup/`)
- `build.gradle.kts` — Added Aliyun mirror, canvasMavenPublicUrl
- `settings.gradle.kts` — Added Aliyun mirror
- `CLAUDE.md` — Project documentation

## Post Re-clone Steps

1. Copy files in `backup/src/` to `canvas-server/src/main/java/io/canvasmc/canvas/`
2. Overwrite `build.gradle.kts` and `settings.gradle.kts` in the project root with the files in `backup/`
3. Place `backup/CLAUDE.md` into the project root
4. Run `./gradlew applyAllPatches` (proxy required for GitHub access, port 7897 SOCKS5)
5. Run `./gradlew rebuildAllServerPatches` to rebuild patches
6. Run `./gradlew createMojmapPublisherJar` to build

## Upstream Updates Completed (Needs Re-application)

### From Canvas 26.1.2
- Config system rewrite (JSON5 → YAML)
- Global configuration + per-world configuration separation
- TickGuard replacing AsyncCatcher
- Purpur Alternative Keepalive
- Region Tick Guards
- Remove MinecraftServer tickables + Fix GUI

### From Spring-for-LeavesMC
- Performance Optimizations: Throttle goal selectors, reduce entity allocations, remove lambdas, cache climbing checks, optimize sun burn, skip zero-move entities, faster chunk serialization, Lithium equipment tracking
- Bug Fixes: Update suppression crash capture, dropper leak fix, falling block dupe fix, portal event fix, chunk reload detection fix

## Important: Proxy Configuration
```bash
git config --global http.proxy socks5h://127.0.0.1:7897
git config --global https.proxy socks5h://127.0.0.1:7897
```
