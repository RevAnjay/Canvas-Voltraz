package io.canvasmc.canvas;

import io.canvasmc.canvas.configuration.ConfigurationProvider;
import io.canvasmc.canvas.configuration.Part;
import io.canvasmc.canvas.configuration.Resolver;
import io.canvasmc.canvas.configuration.Style;
import io.canvasmc.canvas.configuration.Validator;
import io.canvasmc.canvas.util.CanonicalReference;
import io.papermc.paper.adventure.PaperAdventure;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorldConfig extends Part {

    public static final String DEFAULT_TPSBAR_FORMAT =
        "<gradient:blue:aqua><b>TPS:</b></gradient> <tps>  <dark_gray>-</dark_gray>  " +
            "<gradient:blue:aqua><b>MSPT:</b></gradient> <mspt>  <dark_gray>-</dark_gray>  " +
            "<gradient:blue:aqua><b>Util:</b></gradient> <util>  <dark_gray>-</dark_gray>  " +
            "<gradient:blue:aqua><b>Players:</b></gradient> <players>";
    public static final String DEFAULT_RAMBAR_FORMAT =
        "<gradient:green:dark_green><b>RAM:</b></gradient> <used>/<xmx> <dark_gray>(</dark_gray><percent><dark_gray>%)</dark_gray>";

    private static final Logger LOGGER = LoggerFactory.getLogger("CanvasWorlds");

    private static final Path BASE_FILE = Path.of("config/canvas-worlds.yml").toAbsolutePath().normalize();

    private static final Object2ObjectOpenHashMap<ResourceKey<Level>, WorldConfig> WORLD_CONFIGS = new Object2ObjectOpenHashMap<>();
    private static WorldConfig DEFAULT;
    private static boolean initialized = false;

    public static @NonNull WorldConfig getDefaults() {
        if (DEFAULT == null) {
            // During early bootstrap (Blocks.<clinit>), we can't load the full config
            // because MinecraftServer isn't initialized yet. Return a bare default.
            DEFAULT = new WorldConfig();
        }
        return DEFAULT;
    }

    public static @NonNull WorldConfig forWorld(final @NonNull Level level) {
        if (level instanceof ServerLevel serverLevel) {
            final ResourceKey<Level> key = serverLevel.dimension();
            final WorldConfig config = WORLD_CONFIGS.get(key);
            if (config != null) return config;
        }
        // fall back to defaults
        return getDefaults();
    }

    public static void init() {
        if (!initialized) {
            initialized = true;
            GlobalConfiguration.getInstance(); // preload global
            reload();
            // fallback: if reload didn't set DEFAULT, create a bare instance
            if (DEFAULT == null) {
                DEFAULT = new WorldConfig();
            }
        }
    }

    public static void reload() {
        ConfigurationProvider.buildSolidConfiguration(
            BASE_FILE,
            WorldConfig::new,
            GlobalConfiguration.CHAR_LIM,
            new Resolver<>() {
                @Override
                public void onDiffAdd(final String fullyQualifiedName) {
                    LOGGER.info("Added new world configuration option, \"{}\"", fullyQualifiedName);
                }

                @Override
                public void onDiffRemove(final String fullyQualifiedName) {
                    LOGGER.warn("World configuration option \"{}\" no longer exists and is now removed.", fullyQualifiedName);
                }

                @Override
                public void onFinishLoad(final WorldConfig instance) {
                    Validator.validateObject(instance);
                    DEFAULT = instance;
                }
            },
            Style.create()
                .literal("CanvasMC World Default Configuration").endLine()
                .blank()
                .wordWrap(
                    "These are the default values for the CanvasMC per-world configuration file.",
                    "Each option can be overridden in a patch variant in the respective dimension folder. You are",
                    "free to modify, add, or remove comments."
                ).endLine()
                .blank()
                .wordWrap(
                    "You can use the \"/canvas reload\" command to refresh this configuration at runtime, but it is not recommended during normal operation",
                    "as it may cause unexpected crashes or unintended behavior."
                ).endLine()
                .blank()
                .wordWrap(
                    "The default values of all options in this configuration are set for upstream compatibility rather than performance optimization.",
                    "You need to do some manual configuration to get some of the performance improvements provided by Canvas."
                ).endLine()
                .blank()
                .wordWrap(
                    "If you have questions about any configuration options, please contact us in our Discord"
                ).endLine()
                .literal("https://canvasmc.io/discord")
                .compile(60)
        );

        // on reload, if the server started, we need to swap out the configs
        try {
            if (MinecraftServer.getServer() != null) {
                for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
                    level.reloadCanvasConfig();
                }
            }
        } catch (Throwable ignored) {
            // server not started yet
        }
    }

    public static WorldConfig buildForLevel(final @NonNull ServerLevel level, final ResourceKey<Level> dimension) {
        // ensure base config file exists before building patch
        if (!java.nio.file.Files.exists(BASE_FILE)) {
            // First time: create the base config file
            init();
        } else if (!initialized) {
            init();
        }

        final WorldConfig[] result = new WorldConfig[1];

        ConfigurationProvider.buildPatchableConfiguration(
            MinecraftServer.getServer().storageSource.getDimensionPath(dimension)
                .resolve("canvas-patch.yml"),
            BASE_FILE,
            WorldConfig::new,
            instance -> {
                LOGGER.info("Loaded Canvas config patch for level {}", dimension.identifier());
                result[0] = instance;
                instance.onLoad(level);
                WORLD_CONFIGS.put(dimension, instance);
            },
            Style.create()
                .literal("Configuration patch file for world " + dimension.identifier()).endLine()
                .blank()
                .wordWrap(
                    "This configuration file can be used to override the default configuration values",
                    "defined in \"/config/canvas-worlds.yml\""
                ).endLine()
                .blank()
                .wordWrap(
                    "To override a value, simply copy the same option path and override its value. Treat the values in this file",
                    "as default value replacements exclusive to this world"
                )
                .compile(60)
        );

        return result[0];
    }

    private void onLoad(final @NonNull ServerLevel level) {
        Validator.validateObject(this);

        final EntityType<?>[] entityTypes = entities.projectiles.loadChunks.stream()
            .map(Identifier::parse)
            .map(BuiltInRegistries.ENTITY_TYPE::getValue)
            .toList().toArray(new EntityType<?>[0]);

        if (entityTypes.length > 0) {
            LOGGER.info("Set {} projectile types to load chunks in {}", entityTypes.length, level.dimension().identifier().toDebugFileName());
        }

        entities.projectiles.compiledPredicate.setValue((projectile) -> {
            for (final EntityType<?> entityType : entityTypes) {
                if (projectile.getType() == entityType) return true;
            }
            return false;
        });

        if (blocks.spawner.minSpawnDelay > blocks.spawner.maxSpawnDelay) {
            throw new IllegalArgumentException("min-spawn-delay must be less than or equal to max spawn delay");
        }
    }

    {
        option("regionBars").docs("Region resource bar configuration. You can use the \"/regionbar\" command to toggle these bars for players");
    }

    public RegionBars regionBars = new RegionBars();
    public static class RegionBars extends Part {

        {
            option("enableTpsBar").docs("Enable Canvas's regionized TPS bar implementation.");
            option("tpsBarFormat")
                .docs(
                    "The MiniMessage format line for the TPS bar. Placeholders are <tps>, <mspt>, <util>, and <players>.",
                    "Legacy markers (%tps%, %mspt%, %util%, %players%) are also supported and auto-converted."
                ).greedyString();
            option("enableRamBar").docs("Enable Canvas's regionized RAM bar implementation.");
            option("ramBarFormat")
                .docs(
                    "The MiniMessage format line for the RAM bar. Placeholders are <used>, <xmx>, <percent>.",
                    "Legacy markers (%used%, %xmx%, %percent%) are also supported and auto-converted."
                ).greedyString();
        }

        public boolean enableTpsBar = true;
        public String tpsBarFormat = DEFAULT_TPSBAR_FORMAT;

        public boolean enableRamBar = true;
        public String ramBarFormat = DEFAULT_RAMBAR_FORMAT;
    }

    public Visuals visuals = new Visuals();
    public static class Visuals extends Part {

        {
            option("particles")
                .docs(
                    "Unless otherwise explicitly stated, all options are unnecessary packets sent to the client,",
                    "and can be safely disabled without deviating from vanilla behavior"
                );
        }

        public boolean hideFlamesOnEntitiesWithFireResistance = false;
        public boolean hideFlamesOnEntitiesWithInvisibility = false;

        public Particles particles = new Particles();
        public static class Particles extends Part {

            {
                option("disableFallParticles").docs("Note: Enabling this option breaks vanilla visual compatibility");
                option("disableNewCombatParticles").docs("Note: Enabling this option breaks vanilla visual compatibility");
            }

            public boolean disableSprintParticles = false;
            public boolean disableFallParticles = false;
            public boolean disableDeathParticles = false;
            public boolean disableEffectParticles = false;
            public boolean disableWaterSplashParticles = false;
            public boolean disableBubbleColumnParticles = false;
            public boolean disableNewCombatParticles = false;
        }

        {
            option("dontTrackPlayersInEntityTracking").docs("When enabled, players will not be able to see other players in this world");
        }

        public boolean dontTrackPlayersInEntityTracking = false;
    }

    {
        option("chainEndCrystalExplosions").docs("When enabled, end crystal explosions will be chained rather than all executing in the same tick");
        option("disableSnowLightChecks").docs("Disable snow light checks, making snow never melt");
        option("disableGrassLightChecks").docs("Disable grass block light checks, making grass spread even in darkness");
    }

    public boolean chainEndCrystalExplosions = false;
    public boolean disableSnowLightChecks = false;
    public boolean disableGrassLightChecks = false;

    public Farming farming = new Farming();
    public static class Farming extends Part {

        {
            option("disableFarmlandTrampling").docs("Prevent falls from trampling farmland back into dirt");
            option("cropsIgnoreLightCheck").docs("Make crops ignore light requirements when planted");
        }

        public boolean farmlandAlwaysMoist = false;
        public boolean disableLeafDecay = false;
        public boolean cropsIgnoreLightCheck = false;
        public boolean disableFarmlandTrampling = false;
        public boolean sugarCaneBonemealable = false;
        public boolean netherWartBonemealable = false;
    }

    public Entities entities = new Entities();
    public static class Entities extends Part {

        {
            option("fastOrbs")
                .docs(
                    "Remove experience orb pickup delay and use a faster merge system. Very useful for dense xp farms.",
                    "This option changes how experience orbs merge, allowing a single orb to contain unlimited merged experience",
                    "and be collected immediately, much faster than vanilla. This option also fixes the \"ghost orb\" issue,",
                    "because the system increases orb value instead of increasing orb count"
                );

            option("entityCollisionMode")
                .docs(
                    Style.wrap("Entity collision mode for the server")
                        .defineEnum(EntityCollisionMode.class, (mode) -> {
                            return switch (mode) {
                                case VANILLA -> "Default, all entities collide";
                                case ONLY_PUSHABLE_PLAYERS_SMALL ->
                                    "Only players can be pushed by entities, with a smaller search range";
                                case ONLY_PUSHABLE_PLAYERS_LARGE ->
                                    "Only players can be pushed by entities, with a normal search radius";
                                case NO_COLLISIONS -> "Completely disable entity collisions";
                            };
                        })
                );
        }

        public boolean fastOrbs = false;

        public ItemEntities itemEntities = new ItemEntities();
        public static class ItemEntities extends Part {

            {
                option("itemEntityVelocityOnDeathFactor")
                    .docs(
                        "Velocity multiplier for item entities dropped on death. Smaller values reduce spread range;",
                        "larger values increase spread range"
                    ).greaterThanOrEqualTo(0.0F);
                option("itemEntitiesWaitTwoSecondsForMergeCheckAlways")
                    .docs(
                        "Item entity merge check interval during tick is usually 2 seconds (unless the item is moving,",
                        "in which case it is 2 ticks). This option forces the interval to always be 2 ticks,",
                        "reducing how often item entities check for merging"
                    );
            }

            public boolean itemEntitiesImmuneToExplosions = false;
            public boolean itemEntitiesImmuneToLightning = false;
            public double itemEntityVelocityOnDeathFactor = 1.0D;
            public boolean itemEntitiesWaitTwoSecondsForMergeCheckAlways = false;
        }

        public EntityCollisionMode entityCollisionMode = EntityCollisionMode.VANILLA;
        public enum EntityCollisionMode {
            VANILLA,
            ONLY_PUSHABLE_PLAYERS_LARGE,
            ONLY_PUSHABLE_PLAYERS_SMALL,
            NO_COLLISIONS;

            private static final EntityCollisionMode[] VALUES = values();
            private final int id;

            EntityCollisionMode() {
                this.id = ordinal();
            }

            public static EntityCollisionMode fromOrdinal(int ordinal) {
                if (ordinal < 0 || ordinal >= VALUES.length) {
                    return VANILLA;
                }
                return VALUES[ordinal];
            }

            public int getId() {
                return this.id;
            }

            public boolean onlyPlayersPushable() {
                return id == ONLY_PUSHABLE_PLAYERS_LARGE.id || id == ONLY_PUSHABLE_PLAYERS_SMALL.id;
            }

            public boolean allEntitiesCanBePushed() {
                return id == VANILLA.id;
            }

            public boolean noCollisions() {
                return id == NO_COLLISIONS.id;
            }

            public boolean isLargePushRange() {
                return id == ONLY_PUSHABLE_PLAYERS_LARGE.id;
            }
        }

        public Projectiles projectiles = new Projectiles();
        public static class Projectiles extends Part {

            {
                option("loadChunks").docs("Specify which projectiles should load chunks while moving. Only effective when thrown by players");
                option("crossRegionRedirectableProjectileDeflection")
                    .docs(
                        Style.wrap(
                            "Restores vanilla behavior where arrows hitting redirectable projectiles (such as wind charges and fireballs) redirect them,",
                            "supporting cross-region threading."
                        )
                        .blank()
                        .wordWrap(
                            "Recommended to set \"max-arrow-despawn-invulnerability: disabled\" in paper-world-defaults.yml",
                            "to prevent arrows from despawning"
                        )
                    );
            }

            public int maxProjectileChunkLoadsPerTick = 10;
            public int maxProjectileChunkLoadsPerProjectileBeforeRemoval = 10;
            public List<String> loadChunks = new ArrayList<>();
            public boolean crossRegionRedirectableProjectileDeflection = false;

            private final CanonicalReference<Predicate<Projectile>> compiledPredicate = new CanonicalReference<>();

            public Predicate<Projectile> getDoesProjectileLoadChunksOverridePredicate() {
                return compiledPredicate.value();
            }
        }

        {
            option("skeletonAimAccuracy").docs("Defines skeleton archery inaccuracy. 14 is the vanilla value; higher values are less accurate, lower values are more accurate");
            option("villagers")
                .docs(
                    "Villager related options. Options that reduce POI search range shrink the search radius (in blocks)",
                    "from 48 to 16, which can improve tick duration with almost no deviation from vanilla behavior,",
                    "but prevents villagers from acquiring POIs 17-48 blocks away"
                );
        }

        public double skeletonAimAccuracy = 14.0D;

        public Villagers villagers = new Villagers();
        public static class Villagers extends Part {

            {
                option("villagerAcquirePoiTasksLoadChunks")
                    .docs("Whether villagers are allowed to load unloaded chunks to locate POIs");
            }

            {
                option("villagerSmartHibernation")
                    .docs(
                        "When enabled, villagers completely surrounded by solid blocks and not trading",
                        "will skip brain ticking and other AI processing.",
                        "Significantly reduces tick duration for villager farms with large numbers of enclosed villagers."
                    );
            }

            public boolean villagerAcquirePoiTasksLoadChunks = true;
            public boolean reduceJobSitePoiSearchRange = false;
            public boolean reduceHomePoiSearchRange = false;
            public boolean reduceMeetingPointPoiSearchRange = false;
            public boolean villagerSmartHibernation = false;
        }

        public boolean experienceOrbsAreFireResistant = false; // Canvas - fire res orbs
        public boolean allowUnsafeTeleportation = false; // Luminol - allow unsafe teleportation (sand duping, etc.)
    }

    public Combat combat = new Combat();
    public static class Combat extends Part {

        {
            option("restoreOldAttackDelayMechanics").docs("Restore 1.8 attack delay mechanics");
            option("imitateSwordBlocking").docs("Restore 1.8 sword blocking mechanics. May not work on clients <1.21.4");
        }

        public boolean restoreOldAttackDelayMechanics = false;
        public boolean imitateSwordBlocking = false;

        public Mace mace = new Mace();
        public static class Mace extends Part {

            {
                option("ignoreFallDistance").docs("Remove mace fall distance multiplier");
                option("fallDistanceLimit").docs("Threshold where fall distance scaling stops applying for mace bonus damage");
            }

            public boolean ignoreFallDistance = false;
            public double fallDistanceLimit = -1.0D;
        }

        {
            option("criticalHitMultiplier").docs("Configure damage multiplier for critical hits");
            option("removeRedDeathAnimation").docs("Remove red death animation when entities are killed");
            option("useLegacyBlastProtection").docs("Restore pre-1.21 blast protection logic");
        }

        public boolean disableSweepingEdge = false;
        public boolean disableCritsWhileSprinting = false;
        public boolean allowFishingRodsToPullEntities = true;
        public float criticalHitMultiplier = 1.5F;
        public boolean removeRedDeathAnimation = false;
        public boolean useLegacyBlastProtection = false;
        public boolean snowballCanKnockbackPlayers = false;
        public boolean eggCanKnockbackPlayers = false;
    }

    public Blocks blocks = new Blocks();
    public static class Blocks extends Part {

        {
            option("spawner")
                .docs(
                    Style.create().wordWrap(
                        "All integer options in this section take effect only when creating spawner instances.",
                        "This is because plugins can also modify these values at runtime via the Paper API.",
                        "Existing spawners will not apply this configuration; it takes effect only for newly generated spawners."
                    ).blank()
                    .literal("Relevant options include:")
                    .wordWrap(
                        "\"min-spawn-delay\", \"max-spawn-delay\", \"spawn-count\", \"max-nearby-entities\",",
                        "\"required-player-range\", \"spawn-range\""
                    )
                );

            option("optimizeDropperTransfer")
                .docs(
                    "When droppers push items into containers, pre-check if slots are available.",
                    "If no slot is available, return early (circuit breaker pattern) and use zero-copy item movement",
                    "to avoid event system overhead. Improves performance for hopper-heavy structures."
                );
        }

        public boolean chestsCanOpenWithFullBlockAbove = false;
        public boolean fullChiseledBookShelvesCountAsValidEnchantPowerSources = false;
        public boolean optimizeDropperTransfer = false;

        public Spawner spawner = new Spawner();
        public static class Spawner extends Part {

            {
                option("minSpawnDelay").docs("Minimum delay between spawner spawns")
                    .greaterThanOrEqualTo(0.0F);
                option("maxSpawnDelay").docs("Maximum delay between spawner spawns")
                    .greaterThanOrEqualTo(0.0F);
                option("spawnCount").docs("Number of entities spawned per spawner cycle")
                    .greaterThanOrEqualTo(0.0F);
                option("maxNearbyEntities").docs("Maximum number of nearby entities before spawner stops ticking")
                    .greaterThanOrEqualTo(0.0F);
                option("requiredPlayerRange").docs("Player proximity range required for spawner activation")
                    .greaterThanOrEqualTo(0.0F);
                option("spawnRange").docs("Maximum position range for spawning entities")
                    .greaterThanOrEqualTo(0.0F);
                option("disableMaxNearbyEntitiesCheck").docs("Disable spawner maximum nearby entity check");
                option("spawnedEntitiesHaveNoCollision").docs("Disable collisions for entities spawned by spawners");
            }

            public int minSpawnDelay = 200;
            public int maxSpawnDelay = 800;
            public int spawnCount = 4;
            public int maxNearbyEntities = 6;
            public int requiredPlayerRange = 16;
            public int spawnRange = 4;
            public boolean disableMaxNearbyEntitiesCheck = false;
            public boolean spawnedEntitiesHaveNoCollision = false;
        }
    }

    {
        option("waypointUpdateScale")
            .docs(
                "Controls decay rate of the Canvas waypoint system based on distance between players.",
                "You can read how the new system works and adjust this configuration here:",
                "https://docs.canvasmc.io/canvas/info/waypoints/"
            );
        option("disableCriterionTrigger").docs("Disable all criterion triggers. Advancements will not function properly!");
        option("cactusCheckSurvivalBeforeGrowth").docs("Check survival capability before cactus growth. Can significantly optimize cactus farms");
        option("enableSuffocationOptimization")
            .docs(
                "Optimizes suffocation checks by selectively skipping checks while preserving vanilla visual behavior"
            );
    }

    public double waypointUpdateScale = 4000.0D;
    public boolean disableCriterionTrigger = false;
    public boolean cactusCheckSurvivalBeforeGrowth = false;
    public boolean enableSuffocationOptimization = false;

    public Sleeping sleeping = new Sleeping();
    public static class Sleeping extends Part {

        {
            option("sleepSkippingNight")
                .docs(
                    "Action bar message displayed when skipping the night.",
                    "Use \"default\" for vanilla message, or leave empty to disable"
                );
            option("sleepingPlayersPercent")
                .docs(
                    "Action bar message displayed when players sleep.",
                    "Use \"default\" for vanilla message, or leave empty to disable. You can use \"<count>\"",
                    "as placeholder for current sleeping player count, and \"<total>\"",
                    "as placeholder for total required sleeping players"
                );
            option("sleepNotPossible")
                .docs(
                    "Action bar message displayed when players try to sleep but \"players_sleeping_percentage\"",
                    "gamerule is set above 100. Use \"default\" for vanilla message, or leave empty to disable"
                );
        }

        private String sleepSkippingNight = "default";
        private String sleepingPlayersPercent = "default";
        private String sleepNotPossible = "default";

        public boolean sleepSkippingNightDisabled() {
            return sleepSkippingNight.isBlank();
        }

        public boolean sleepingPlayersPercentDisabled() {
            return sleepingPlayersPercent.isBlank();
        }

        public boolean sleepNotPossibleDisabled() {
            return sleepNotPossible.isBlank();
        }

        public Component getSleepSkippingNight() {
            if (sleepSkippingNightDisabled()) {
                return null;
            }

            final Component message;
            if (sleepSkippingNight.equalsIgnoreCase("default")) {
                message = Component.translatable("sleep.skipping_night");
            }
            else {
                message = PaperAdventure.asVanilla(MiniMessage.miniMessage().deserialize(sleepSkippingNight));
            }

            return message;
        }

        public Component getSleepingPlayersPercent(int amountSleeping, int sleepersNeeded) {
            if (sleepingPlayersPercentDisabled()) {
                return null;
            }

            final Component message;
            if (sleepingPlayersPercent.equalsIgnoreCase("default")) {
                message = Component.translatable("sleep.players_sleeping", amountSleeping, sleepersNeeded);
            }
            else {
                message = PaperAdventure.asVanilla(MiniMessage.miniMessage().deserialize(sleepingPlayersPercent,
                    Placeholder.parsed("count", Integer.toString(amountSleeping)),
                    Placeholder.parsed("total", Integer.toString(sleepersNeeded))));
            }

            return message;
        }

        public Component getSleepNotPossible() {
            if (sleepNotPossibleDisabled()) {
               return null;
            }

            final Component message;
            if (sleepNotPossible.equalsIgnoreCase("default")) {
                message = Component.translatable("sleep.not_possible");
            }
            else {
                message = PaperAdventure.asVanilla(MiniMessage.miniMessage().deserialize(sleepNotPossible));
            }

            return message;
        }

        public boolean sleepIgnoresNearbyMobs = false;
        public boolean rainStopsAfterSleep = true;
        public boolean thunderStopsAfterSleep = true;
    }

}
