package io.canvasmc.canvas;

import ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom;
import io.canvasmc.canvas.configuration.ConfigurationProvider;
import io.canvasmc.canvas.configuration.Part;
import io.canvasmc.canvas.configuration.Resolver;
import io.canvasmc.canvas.configuration.Style;
import io.canvasmc.canvas.configuration.Validator;
import io.canvasmc.canvas.simd.SIMDDetection;
import io.canvasmc.canvas.tick.AffinitySchedulerThreadPool;
import io.canvasmc.canvas.util.FasterRandomSource;
import io.canvasmc.canvas.util.version.ApiClient;
import io.canvasmc.canvas.util.version.CanvasVersionFetcher;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.TickRegions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.random.RandomGeneratorFactory;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GlobalConfiguration extends Part {

    private static final Path CONFIG_PATH = Path.of("config/canvas-server.yml").toAbsolutePath().normalize();
    private static final String BROADCAST_PERMISSION = "canvas.broadcasting.reciever";

    protected static final int CHAR_LIM = 90;

    public static final Logger LOGGER = LoggerFactory.getLogger("CanvasMC");

    public static final int INFO = 0;
    public static final int WARN = 1;
    public static final int ERROR = 2;

    public static GlobalConfiguration INSTANCE;
    public static GlobalConfiguration get() { return INSTANCE; }
    private static ApiClient.BuildStatus BUILD_STATUS = ApiClient.BuildStatus.UNKNOWN;
    private static boolean ENABLE_FASTER_RANDOM = true;

    static {
        reload();
    }

    public static void reload() {
        LOGGER.info("Loading Canvas server configuration");
        ConfigurationProvider.buildSolidConfiguration(
            CONFIG_PATH,
            GlobalConfiguration::new,
            CHAR_LIM,
            new Resolver<>() {
                @Override
                public void onDiffAdd(final String fullyQualifiedName) {
                    LOGGER.info("Added new server-wide configuration option: \"{}\"", fullyQualifiedName);
                }

                @Override
                public void onDiffRemove(final String fullyQualifiedName) {
                    LOGGER.warn("Server-wide configuration option \"{}\" no longer exists and is now removed.", fullyQualifiedName);
                }

                @Override
                public void onFinishLoad(final GlobalConfiguration instance) {

                    postLoad(instance);

                    CompletableFuture.supplyAsync(() -> {
                        ApiClient.BuildStatus buildStatus = ApiClient.BuildStatus.UNKNOWN;
                        ServerBuildInfo buildInfo = ServerBuildInfo.buildInfo();
                        int buildNum = buildInfo.buildNumber().orElse(-1);
                        if (buildNum == -1) {
                            buildStatus = ApiClient.BuildStatus.LOCAL;
                        }
                        else {
                            try {
                                buildStatus = CanvasVersionFetcher.CLIENT.getBuild(buildNum).buildStatus();
                            } catch (Throwable ignored) {
                            }
                        }
                        return buildStatus;
                    }).thenAccept(buildStatus -> RegionizedServer.getInstance().addTask(() -> {
                        BUILD_STATUS = buildStatus;
                        switch (buildStatus) {
                            case UNKNOWN -> broadcast("Running unknown build channel, proceed with caution", WARN);
                            case EXPERIMENTAL ->
                                broadcast("Running a beta build, there may be bugs, proceed with caution!", WARN);
                            case LOCAL ->
                                broadcast("You are running a development version of Canvas, which may not be production-ready, be very careful!", WARN);
                        }
                    }));
                }
            },
            Style.create()
                .literal("CanvasMC Global Configuration").endLine()
                .blank()
                .wordWrap(
                    "This is the global server configuration file provided by CanvasMC. Options in this configuration apply to the entire server,",
                    "and cannot be overridden per-world. You are free to modify, add, or remove comments."
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
    }

    private static void postLoad(final GlobalConfiguration configuration) {
        INSTANCE = configuration;

        // validate the configuration so users don't end up doing a stupid
        Validator.validateObject(configuration);
        io.canvasmc.canvas.util.DABConfig.initDabEntities();
        io.canvasmc.canvas.async.AsyncPlayerDataSaving.init(); // Canvas - Async playerdata saving

        if (TickRegions.started) {

            // if this is a reload, we may have things that need to be taken into effect now
            // for example, 1.8 combat delay configs may be updated, so we conduct updates

            final PlayerList playerList = MinecraftServer.getServer().getPlayerList();

            for (final ServerPlayer player : playerList.players) {
                // update all info with player, covers 1.8 combat config
                playerList.sendAllPlayerInfo(player);
            }
        }
        else {

            // this is only for startup-specific things, and should not contain post actions
            // that should be run on reload too. anything for reload and startup should be below

            try {
                RandomGeneratorFactory.of("Xoroshiro128PlusPlus");
            } catch (Throwable throwable) {
                broadcast("Canvas' faster random impl is not supported by your VM, falling back to legacy random", WARN);
                ENABLE_FASTER_RANDOM = false;
            }

            // SIMD actions
            try {
                SIMDDetection.isEnabled = SIMDDetection.canEnable(ComponentLogger.logger("CanvasMC"));
            } catch (NoClassDefFoundError | Exception ignored) {
                ignored.printStackTrace();
            }

            if (SIMDDetection.isEnabled) {
                LOGGER.info("SIMD operations detected as functional. Will replace some operations with faster versions.");
            }
            else {
                LOGGER.warn("SIMD operations are available for your server, but are not configured!");
                LOGGER.warn("To enable additional optimizations, add \"--add-modules=jdk.incubator.vector\" to your startup flags, BEFORE the \"-jar\".");
                LOGGER.warn("If you have already added this flag, then SIMD operations are not supported on your JVM or CPU.");
                LOGGER.warn("Debug: Java: {}, test run: {}", System.getProperty("java.version"), SIMDDetection.testRun);
            }
        }

        broadcast("Using " + configuration.regionScheduler.defaultTickRate + " as default tick rate", INFO);

        // Log Cleaner
        final Path logsDirectoryPath = Path.of("logs");
        if (configuration.logs.enableLogCleaner && Files.exists(logsDirectoryPath) && !TickRegions.started) {
            final Instant now = Instant.now();
            final Instant adjustedInstantToThresh = now.minus(configuration.logs.length, configuration.logs.unit);
            final int[] amountRemoved = {0};

            try {
                java.util.stream.Stream<Path> stream = Files.walk(logsDirectoryPath, 1);
                stream.filter(p -> !p.equals(logsDirectoryPath)).forEach(path -> {
                    if (Files.isRegularFile(path)) {
                        try {
                            final Instant lastModified = Files.getLastModifiedTime(path).toInstant();
                            if (lastModified.isBefore(adjustedInstantToThresh) && !path.getFileName().toString().equalsIgnoreCase("latest.log")) {
                                Files.delete(path);
                                amountRemoved[0]++;
                            }
                        } catch (IOException ioe) {
                            broadcast("Unable to determine if file " + path.getFileName() + " should be removed because: " + ioe.getMessage(), ERROR);
                        }
                    }
                });
            } catch (IOException ioe) {
                broadcast("Failed to walk logs directory: " + ioe.getMessage(), ERROR);
            }

            if (amountRemoved[0] > 0) {
                broadcast("Log cleaner removed " + amountRemoved[0] + " old log files", INFO);
            }
        }

        // Apply region format setting
        io.canvasmc.canvas.region.RegionFormatFactory.setCurrentFormat(configuration.regionFormat);
        if (configuration.regionFormat != io.canvasmc.canvas.region.EnumRegionFormat.MCA) {
            broadcast("Using region format: " + configuration.regionFormat.name(), INFO);
        }
    }

    public static GlobalConfiguration getInstance() {
        return INSTANCE;
    }

    public static ApiClient.BuildStatus getBuildStatus() {
        return BUILD_STATUS;
    }

    public static @NonNull RandomSource createFastRandom() {
        return ENABLE_FASTER_RANDOM ? new FasterRandomSource(RandomSupport.generateUniqueSeed()) : new SimpleThreadUnsafeRandom(RandomSupport.generateUniqueSeed());
    }

    public static void broadcast(String msg, int severity) {
        if (TickRegions.started) {
            final MutableComponent literal = Component.literal(msg);

            switch (severity) {
                case WARN -> literal.withStyle(ChatFormatting.YELLOW);
                case ERROR -> literal.withStyle(ChatFormatting.RED);
            }

            // players might be in the server, try and send msg to people with perms

            for (final ServerPlayer entityPlayer : MinecraftServer.getServer().getPlayerList().players) {
                if (entityPlayer.getBukkitEntity().hasPermission(BROADCAST_PERMISSION)) {
                    entityPlayer.sendSystemMessage(literal);
                }
            }
        }

        // send to console
        switch (severity) {
            case INFO -> LOGGER.info(msg);
            case WARN -> LOGGER.warn(msg);
            case ERROR -> LOGGER.error(msg);
        }
    }

    public RegionScheduler regionScheduler = new RegionScheduler();
    public static class RegionScheduler extends Part {

        {
            option("affinityScheduler")
                .docs(
                    "The AFFINITY scheduler configuration provided by Canvas. To make these options take effect, change",
                    "the \"threaded-regions.scheduler\" option in \"paper-global.yml\" to \"AFFINITY\""
                );
        }

        public AffinityScheduler affinityScheduler = new AffinityScheduler();
        public static class AffinityScheduler extends Part {

            {
                option("stealThresholdMillis")
                    .docs(
                        Style.wrap(
                            "The maximum time (in milliseconds) a thread will delay executing scheduled tasks before allowing other threads to steal them."
                        )
                        .blank()
                        .literal("Note: Smaller values reduce task deadline latency, but increase the likelihood of task stealing between threads")
                    ).greaterThanOrEqualTo(0.0F);

                option("runTasksBufferMillis")
                    .docs(
                        Style.wrap(
                            "The buffer time (in milliseconds) before the tick deadline to stop executing mid-tick tasks.",
                            "Ensures runTick() starts on time at the deadline."
                        )
                        .blank()
                        .literal("Default: 0.1ms. Higher values are safer, lower values get more work done")
                    ).greaterThanOrEqualTo(0.0F);

                option("enableWorkStealing")
                    .docs(
                        "Enable work stealing / task thread affinity. This option attempts to keep tasks on the same thread for better performance.",
                        "When enabled, if a task exceeds the deadline set by \"stealThresholdMillis\",",
                        "other tick threads can take over the task execution."
                    );

                option("enableMidTickTasks").docs("Enable the affinity scheduler to execute mid-tick tasks while waiting for the current tick deadline");
                option("tickRegionAffinity")
                    .docs("The thread affinity of the Canvas AFFINITY scheduler. Use this option to bind region scheduler threads to CPU cores")
                    .greaterThanOrEqualTo(0.0F);

                option("enableAffinitySchedulerCpuAffinity").docs("Enable binding AFFINITY region scheduler threads to CPU cores");
            }

            public long stealThresholdMillis = AffinitySchedulerThreadPool.DEFAULT_STEAL_THRESH_MILLIS;
            public double runTasksBufferMillis = AffinitySchedulerThreadPool.DEFAULT_RUN_TASKS_BUFFER_MILLIS;
            public boolean enableWorkStealing = true;
            public boolean enableMidTickTasks = true;
            public int[] tickRegionAffinity = new int[0];
            public boolean enableAffinitySchedulerCpuAffinity = false;
        }

        {
            option("overloadedLogMillis")
                .docs(
                    "The time interval between the end of a region tick and the start of the next. If exceeded, the server will log a scheduler overload warning.",
                    "This helps determine if more threads need to be allocated, or helps identify missed deadline issues"
                ).greaterThan(0.0F);

            option("defaultTickRate")
                .docs(
                    "The default tick rate of the scheduler. Vanilla is 20. The game will run faster or slower depending on the value you set.",
                    "Note: This option is generally only for debugging purposes or custom environments that require this change"
                ).greaterThan(0.0F);

            option("guardSeverity")
                .docs(
                    Style.wrap(
                        "Canvas introduces additional tick thread checks to help detect plugin issues. This option determines the strictness of the new guard mechanism"
                    ).defineEnum(GuardSeverity.class, (severity) -> {
                        return switch (severity) {
                            case LOG -> "Only log a warning to the console, but continue the operation";
                            case THROW -> "Throw an exception, which may crash the server. Suitable for ensuring correctness";
                            case SILENT -> "Do not output any information and do not perform any action";
                        };
                    })
                );
        }

        public long overloadedLogMillis = 5_000L;
        public float defaultTickRate = 20.0F;
        public GuardSeverity guardSeverity = GuardSeverity.THROW;

        public enum GuardSeverity {
            SILENT,
            LOG,
            THROW
        }
    }

    public ChunkSystem chunkSystem = new ChunkSystem();
    public static class ChunkSystem extends Part {

        {
            option("threadPriority").between(Thread.MIN_PRIORITY, Thread.MAX_PRIORITY);
            option("fluidPostProcessingAlgorithm")
                .docs(
                    Style.wrap(
                        "World generation creates a large number of unnecessary fluid post-processing tasks,",
                        "which can overload the server and cause lag when generating new chunks.",
                        "Depending on the selected algorithm, this can help reduce lag and improve chunk generation performance"
                    ).defineEnum(FluidPostProcessingMode.class, (mode) -> {
                        return switch (mode) {
                            case VANILLA -> "Normal post-processing algorithm, processes everything";
                            case DISABLED -> "Completely disable fluid post-processing";
                            case FILTERED -> "C2ME algorithm, filters unnecessary post-processing tasks";
                        };
                    })
                );

            option("makeFluidPostProcessScheduledTick")
                .docs(
                    "Enabling this option converts fluid post-processing to scheduled ticks,",
                    "which helps mitigate MSPT spikes during chunk generation"
                );
            option("endBiomeCacheSize").greaterThan(0.0F);
            option("structureOptimizations").docs(
                "These options are ported from the mod StructureLayoutOptimizer, https://modrinth.com/mod/structure-layout-optimizer",
                "Used to optimize the generation of jigsaw structures and NBT fragments"
            );
        }

        public int threadPriority = Thread.NORM_PRIORITY;
        public FluidPostProcessingMode fluidPostProcessingAlgorithm = FluidPostProcessingMode.VANILLA;

        public enum FluidPostProcessingMode {
            VANILLA,
            DISABLED,
            FILTERED
        }

        public boolean makeFluidPostProcessScheduledTick = false;
        public boolean optimizeAquifer = false;
        public boolean useEndBiomeCache = false;
        public int endBiomeCacheSize = 1024;
        public boolean optimizeBeardifier = false;
        public boolean optimizeNoiseGeneration = false;

        public StructureGen structureOptimizations = new StructureGen();
        public static class StructureGen extends Part {

            {
                option("deduplicateShuffledTemplatePoolElementList").docs(
                    Style.wrap(
                        "Whether to use an alternative strategy to make structure layout generation faster than the default template pool weight optimization.",
                        "This alternative strategy works by changing the list of elements collected from the template pool",
                        "to one that does not contain duplicate entries."
                    )
                    .blank()
                    .wordWrap(
                        "Enabling this option can provide additional performance gains from high-weight template pool structures,",
                        "but will lose consistency with vanilla seeds in structure layout"
                    )
                );
            }

            public boolean deduplicateShuffledTemplatePoolElementList = false;
            public boolean enable = false;
        }
    }

    // TODO - check these on minecraft updates
    public UpstreamFixes vanillaFixes = new UpstreamFixes();
    public static class UpstreamFixes extends Part {

        {
            option("pearlDuplication")
                .docs(
                    "Vanilla has a bug where flying ender pearls are duplicated when the server shuts down.",
                    "When the \"restoreVanillaEnderPearlBehavior\" option is also enabled, this option can fix the issue."
                );
        }

        public boolean mc298464 = false;
        public boolean mc223153 = false;
        public boolean mc200418 = false;
        public boolean mc94054 = false;
        public boolean mc245394 = false;
        public boolean mc227337 = false;
        public boolean mc221257 = false;
        public boolean mc206922 = false;
        public boolean mc155509 = false;
        public boolean mc132878 = false;
        public boolean mc121706 = false;
        public boolean mc119754 = false;
        public boolean mc100991 = false;
        public boolean mc30391 = false;
        public boolean mc183990 = false;
        public boolean mc136249 = false;
        public boolean mc231743 = false;
        public boolean mc258859 = false;
        public boolean pearlDuplication = false;
        public boolean updateSuppressionCrashFix = false;
    }

    public Networking networking = new Networking();
    public static class Networking extends Part {

        {
            option("filterVelocityPacket")
                .docs(
                    "ClientboundSetEntityMotionPacket (entity velocity packet) typically consumes significant network bandwidth,",
                    "up to 60% on large production servers. This option filters unnecessary packets,",
                    "while maintaining vanilla visual behavior"
                );
            option("filterMovePackets").docs("Filter useless move packets that don't need to be sent");

            option("alternativePlayerListTick").docs("Bucket players to distribute evenly in playerlist tick");
            option("playerInfoSendInterval")
                .docs(
                    "If alternative playerlist tick is enabled, this option controls each bucket's",
                    "tick interval (in ticks)"
                ).greaterThan(0.0F);
            option("asyncProtocolSwitch")
                .docs(
                    "Make protocol switching asynchronous during login, reducing global region blocking,",
                    "which can improve performance during the login and configuration phase when players join"
                );

            option("maximumPacketBytes")
                .docs(
                    "The maximum number of bytes the server sends to a player in a single packet. If exceeded, the player will be kicked"
                ).greaterThan(0.0F);
            option("disablePaperPacketOverflowContainerFix")
                .docs(
                    "Disable Paper's overflow fallback for large container packets sent to the client. This means",
                    "if container data is too large, players attempting to open containers whose contents exceed the maximum packet byte size",
                    "will be kicked"
                );
            option("packetTooLargeDisconnectReason")
                .docs(
                    "The disconnect reason sent to the client when the server attempts to send",
                    "a packet that exceeds the maximum packet size"
                );

            option("particleThrottling")
                .docs(
                    "Limit the number of particle packets sent to each player per tick. When enabled, particle packets",
                    "exceeding the configured limit per tick will be silently dropped. Can reduce",
                    "network overhead on servers with heavy particle usage"
                );
            option("particleThrottleLimit")
                .docs(
                    "The maximum number of particle packets allowed per player per tick when particle throttling is enabled"
                ).greaterThan(0.0F);
        }

        public boolean filterVelocityPacket = false;
        public boolean filterMovePackets = false;
        public boolean alternativePlayerListTick = false;
        public int playerInfoSendInterval = 600;
        public boolean asyncProtocolSwitch = false;
        public int maximumPacketBytes = 8388608;
        public boolean disablePaperPacketOverflowContainerFix = false;
        public String packetTooLargeDisconnectReason = "Clientbound packet exceeded max packet bytes";
        public boolean purpurAlternativeKeepalive = false;
        public boolean particleThrottling = false;
        public int particleThrottleLimit = 20;
    }

    {
        option("serverModName").docs("The server mod name displayed in the server list and client info").word();
        option("restoreVanillaEnderPearlBehavior").docs("Restore and fix vanilla ender pearl behavior broken by Folia");

        option("displayWorldLoadScreenForPortaling")
            .docs(
                "Folia's teleport rewrite causes the client to not display the world loading screen correctly,",
                "instead showing a blank void. When enabled, Canvas will display the correct world loading screen"
            );
        option("displayWorldLoadScreenForTeleporting")
            .docs(
                "Similar to displayWorldLoadScreenForPortaling, but for cross-region teleports (e.g. /tppos command).",
                "When enabled, Canvas will display the correct world loading screen during cross-region teleports"
            );
        option("cacheMinecraft2BukkitEntityTypeConversion").docs("Whether to cache the expensive CraftEntityType#minecraftToBukkit call");
        option("tileEntitySnapshotCreation").docs("Enable creating block entity snapshots when getting block state");
        option("allowLegacyScheduler").docs("Allow legacy Bukkit scheduler operations for compatibility with plugins that don't support Folia (e.g. MythicMobs)");

        option("defaultRespawnDimensionKey")
            .docs(
                "The default respawn dimension for the server. This can help servers that need to change the respawn dimension for",
                "configuration reasons, such as needing to teleport players to a \"spawn\" world.",
                "This option also applies to end portals and nether portals, replacing the overworld.",
                "For example, the target dimension for entities teleported from the nether will be set to this"
            ).identifier(); // TODO - object mapping?
    }

    public String serverModName = ServerBuildInfo.buildInfo().brandName();
    public boolean restoreVanillaEnderPearlBehavior = false;
    public boolean displayWorldLoadScreenForPortaling = true;
    public boolean displayWorldLoadScreenForTeleporting = true;
    public boolean cacheMinecraft2BukkitEntityTypeConversion = false;
    public boolean tileEntitySnapshotCreation = false;
    public boolean allowLegacyScheduler = true; // Canvas - allow legacy scheduler for plugin compatibility
    public String defaultRespawnDimensionKey = Level.OVERWORLD.identifier().toString();

    public static @NonNull ResourceKey<@NonNull Level> fetchRespawnDimensionKey() {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(GlobalConfiguration.getInstance().defaultRespawnDimensionKey));
    }

    public PurpurContainers purpurContainers = new PurpurContainers();
    public static class PurpurContainers extends Part {

        {
            option("barrelRows").docs("The number of rows for the barrel block").between(1, 6);
            option("enderChestSixRows").docs("Whether to use 6 rows for player ender chests instead of the default 3");
            option("enderChestPermissionRows")
                .docs(
                    Style.wrap("Whether to use a permission-based system to define the size of each player's ender chest")
                        .literal("Available permissions").endLine()
                        .literal(" - purpur.enderchest.rows.six").endLine()
                        .literal(" - purpur.enderchest.rows.five").endLine()
                        .literal(" - purpur.enderchest.rows.four").endLine()
                        .literal(" - purpur.enderchest.rows.three").endLine()
                        .literal(" - purpur.enderchest.rows.two").endLine()
                        .literal(" - purpur.enderchest.rows.one").endLine()
                );
            option("enderChestPersistHiddenRows").docs("Whether items should still be kept in slots that have become inaccessible due to permission restrictions");
        }

        public int barrelRows = 3;
        public boolean enderChestSixRows = false;
        public boolean enderChestPermissionRows = false;
        public boolean enderChestPersistHiddenRows = true;
    }

    public boolean blacklistNonPlayerEntitiesFromEnteringNetherPortals = false;
    public boolean blacklistNonPlayerEntitiesFromEnteringEndPortals = false;
    public boolean blacklistNonPlayerEntitiesFromEnteringGatewayPortals = false;

    public Performance performance = new Performance();
    public static class Performance extends Part {

        {
            option("skipEntityMoveIfMovementIsZero")
                .docs(
                    "When enabled, skips entity move processing when the movement vector is zero and the bounding box hasn't changed.",
                    "Can improve performance on servers with a large number of entities."
                );
            option("skipNegligiblePlanarMovementMultiplication")
                .docs(
                    "When enabled, skips planar movement multiplication when the movement value is negligible",
                    "and the block speed factor is effectively 1.0. Reduces unnecessary Vec3 object allocations."
                );
            option("fasterChunkSerialization")
                .docs(
                    "Uses an optimized chunk serialization strategy based on Lithium palette compression.",
                    "Reduces object allocations and improves chunk save/network performance."
                );
            option("equipmentTracking")
                .docs(
                    "Lithium-style equipment change tracking. Skips unnecessary enchantment ticks",
                    "and equipment change detection for entities whose equipment hasn't changed."
                );
            option("throttleInactiveGoalSelectorTick")
                .docs(
                    "Throttle AI goal selector ticking for entities in inactive chunks.",
                    "The goal selector no longer executes every inactive tick, but only once every 20 inactive ticks.",
                    "Ported from Pufferfish, adapted by Spring."
                );
            option("reduceEntityAllocations")
                .docs(
                    "Cache the lambda used in AttributeMap.getInstance to reduce object allocations.",
                    "Java allocates a new lambda instance on each call, even if the captured fields are the same.",
                    "Ported from Pufferfish, adapted by Spring."
                );
            option("removeTickGuardLambda")
                .docs(
                    "Remove lambda allocation in entity tick guards by inlining try-catch.",
                    "Avoids allocating a method reference on every entity tick.",
                    "Ported from Pufferfish, adapted by Spring."
                );
            option("cacheClimbingCheckForActivation")
                .docs(
                    "Cache climbing check results per block position for entity activation range checks.",
                    "Avoids recomputing the expensive onClimbable() check when the position hasn't changed.",
                    "Ported from Pufferfish, adapted by Spring."
                );
            option("optimizeSunBurnTick")
                .docs(
                    "Optimize sun burn tick checks by caching eye block position and reordering check logic",
                    "to exit early before executing expensive operations.",
                    "Ported from Gale, adapted by Spring."
                );
            option("onlyTickItemsInHand")
                .docs(
                    "When enabled, only ticks/updates items in the main hand and offhand instead of the entire inventory.",
                    "Reduces per-tick inventory iteration overhead. May affect items that rely on inventory ticking",
                    "(e.g. compass pointing, clock updates) when not held in hand.",
                    "Ported from Leaf."
                );
            option("reduceSensorWork")
                .docs(
                    "When enabled, skips mob sensing ticks on certain ticks based on entity ID to reduce sensor work.",
                    "Sensing determines what entities/mobs are nearby and is expensive for large mob counts.",
                    "This spreads the load by using entity ID-based throttling.",
                    "Ported from Petal/Bloom."
                );
        }

        public boolean skipEntityMoveIfMovementIsZero = false;
        public boolean skipNegligiblePlanarMovementMultiplication = false;
        public boolean fasterChunkSerialization = false;
        public boolean equipmentTracking = false;
        public boolean throttleInactiveGoalSelectorTick = false;
        public boolean reduceEntityAllocations = false;
        public boolean removeTickGuardLambda = false;
        public boolean cacheClimbingCheckForActivation = false;
        public boolean optimizeSunBurnTick = false;
        public boolean onlyTickItemsInHand = false;
        public boolean reduceSensorWork = false;
    }

    // Canvas start - DAB (Dynamic Activation of Brains)
    {
        option("dab")
            .docs(
                Style.wrap(
                    "Dynamic Activation of Brains (DAB) reduces AI processing for distant entities.",
                    "Entities beyond the start distance have their AI tick rate reduced based on distance,",
                    "significantly improving performance with large entity counts.",
                    "Ported from Pufferfish/Folia."
                )
            );
    }

    public DAB dab = new DAB();
    public static class DAB extends Part {

        {
            option("enabled").docs("Whether DAB is enabled");
            option("startDistance").docs("Distance in blocks from player at which DAB begins to reduce entity AI tick rate").greaterThan(0.0F);
            option("maxTickFreq").docs("Maximum AI tick interval (in ticks) for the most distant entities").greaterThan(0.0F);
            option("activationDistMod").docs("Distance modifier controlling how aggressively AI tick rate scales with distance").greaterThan(0.0F);
            option("dontEnableIfInWater").docs("Whether to keep entities in water fully active regardless of distance");
            option("blacklistedEntities").docs("List of entity type names that should never be deactivated by DAB");
        }

        public boolean enabled = true;
        public int startDistance = 12;
        public int maxTickFreq = 20;
        public int activationDistMod = 8;
        public boolean dontEnableIfInWater = false;
        public java.util.List<String> blacklistedEntities = new java.util.ArrayList<>(java.util.Arrays.asList(
            "villager",
            "axolotl",
            "hoglin",
            "zombified_piglin",
            "goat"
        ));
    }

    // Canvas start - Optimized Powered Rails
    {
        option("optimizedPoweredRails")
            .docs(
                Style.wrap(
                    "Optimizes powered rail computation by caching and limiting rail activation range.",
                    "Reduces redundant block updates and recalculation for powered rails,",
                    "especially beneficial for servers with extensive rail networks."
                )
            );
    }

    public OptimizedPoweredRails optimizedPoweredRails = new OptimizedPoweredRails();
    public static class OptimizedPoweredRails extends Part {

        {
            option("enabled").docs("Whether optimized powered rails is enabled");
            option("railActivationRange").docs("Maximum distance (in blocks) that a powered rail can activate other rails").greaterThan(0.0F);
        }

        public boolean enabled = true;
        public int railActivationRange = 8; // Vanilla signal distance (PoweredRailBlock recursionCount >= 8)
    }

    // Canvas start - Async Player Data Save
    {
        option("asyncPlayerDataSave")
            .docs(
                Style.wrap(
                    "Saves player data asynchronously to reduce global region tick pressure.",
                    "When enabled, player data serialization and disk I/O are offloaded,",
                    "preserving server responsiveness during player saves and disconnects."
                )
            );
    }

    public AsyncPlayerDataSave asyncPlayerDataSave = new AsyncPlayerDataSave();
    public static class AsyncPlayerDataSave extends Part {

        {
            option("enabled").docs("Whether async player data saving is enabled");
        }

        public boolean enabled = true;
    }

    // Canvas end - Performance-related config sections

    {
        option("regionFormat")
            .docs(
                Style.wrap(
                    "The region file format for world storage. The Linear format can reduce disk usage by about 50%",
                    "and improve chunk load/save speed. MCA is the standard vanilla Anvil format."
                ).defineEnum(io.canvasmc.canvas.region.EnumRegionFormat.class, (mode) -> {
                    return switch (mode) {
                        case MCA -> "Standard Anvil format (vanilla default)";
                        case LINEAR_V2 -> "Linear v2 format - 50% less disk usage, faster I/O";
                    };
                })
            );
    }

    {
        option("regionCompressionLevel")
            .docs(
                "Zstd compression level for the Linear chunk format. Effective only when LINEAR_V2 is selected.",
                "Range: 1 (fastest, low compression) to 22 (slowest, high compression), default value of 3 is recommended.",
                "Higher compression levels save more disk space, but slightly increase CPU overhead during writes."
            )
            .between(1, 22);
    }

    public int regionCompressionLevel = 3;

    public io.canvasmc.canvas.region.EnumRegionFormat regionFormat = io.canvasmc.canvas.region.EnumRegionFormat.MCA;

    public Chat chat = new Chat();
    public static class Chat extends Part {

        {
            option("disableChatReporting").docs("Disable Minecraft chat signatures to prevent chat reporting");
            option("disableChatVerificationOrder").docs("Disable Minecraft chat verification ordering");
        }

        public boolean disableChatReporting = false;
        public boolean disableChatVerificationOrder = false;
    }

    public Logs logs = new Logs();
    public static class Logs extends Part {

        {
            option("enableLogCleaner").docs("Automatically delete old log files in the \"logs\" directory");
            option("length").docs("The number of time units after which a log file is marked for deletion");
            option("unit").docs("The time unit type used for comparing file age with current time");
        }

        public boolean enableLogCleaner = false;
        public long length = 30;
        public ChronoUnit unit = ChronoUnit.DAYS;
    }

    public LogToConsole logToConsole = new LogToConsole();
    public static class LogToConsole extends Part {

        {
            option("invalidStatistics").docs("Whether to log error messages for invalid statistics");
            option("emptyMessageWarning").docs("Whether to log warning messages for players sending empty messages");
            option("ignoredAdvancements").docs("Whether to log warning messages for ignored advancements");
            option("setBlockInFarChunk").docs("Whether to log warning messages for calling setBlock in far chunks");
            option("unrecognizedRecipes").docs("Whether to log error messages for unrecognized recipes");
            option("expiredMessageWarning").docs("Whether to log warning messages for expired messages");
            option("notSecureMarker").docs("Whether to log \"Not Secure\" marker for chat messages");
            option("nullIdDisconnections").docs("Whether to log disconnections with null ID");
        }

        public boolean invalidStatistics = true;
        public boolean emptyMessageWarning = true;
        public boolean ignoredAdvancements = true;
        public boolean setBlockInFarChunk = true;
        public boolean unrecognizedRecipes = true;
        public boolean expiredMessageWarning = true;
        public boolean notSecureMarker = true;
        public boolean nullIdDisconnections = true;
    }

}
