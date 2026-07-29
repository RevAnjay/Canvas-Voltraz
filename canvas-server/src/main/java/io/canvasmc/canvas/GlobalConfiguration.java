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
                .literal("CanvasMC 全局配置").endLine()
                .blank()
                .wordWrap(
                    "这是 CanvasMC 提供的全局服务器配置文件。此配置中的选项作用于整个服务器，",
                    "且无法按世界覆盖。你可以自由修改、添加或删除注释。"
                ).endLine()
                .blank()
                .wordWrap(
                    "你可以使用 \"/canvas reload\" 命令在运行时刷新此配置，但不建议在正式运行",
                    "期间执行此操作，因为这可能导致意外崩溃或非预期行为。"
                ).endLine()
                .blank()
                .wordWrap(
                    "此配置中所有选项的默认值都是为了上游兼容性而非性能优化而设定的。",
                    "你需要进行一些手动配置才能获得 Canvas 提供的部分性能提升。"
                ).endLine()
                .blank()
                .wordWrap(
                    "如果你对某些配置选项有疑问，请在我们的 Discord 中联系"
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
                    "Canvas 提供的 AFFINITY 调度器配置。要使这些选项生效，请将",
                    "\"paper-global.yml\" 中的 \"threaded-regions.scheduler\" 选项更改为 \"AFFINITY\""
                );
        }

        public AffinityScheduler affinityScheduler = new AffinityScheduler();
        public static class AffinityScheduler extends Part {

            {
                option("stealThresholdMillis")
                    .docs(
                        Style.wrap(
                            "线程在允许其他线程窃取任务执行之前，延迟执行已调度任务的最大等待时间（毫秒）。"
                        )
                        .blank()
                        .literal("注意：较小的值可减少任务截止延迟，但会增加线程间任务窃取的可能性")
                    ).greaterThanOrEqualTo(0.0F);

                option("runTasksBufferMillis")
                    .docs(
                        Style.wrap(
                            "在 tick 截止时间前停止执行中间任务的缓冲时间（毫秒）。",
                            "确保 runTick() 能在截止时间准时开始执行。"
                        )
                        .blank()
                        .literal("默认值: 0.1ms，值越高越安全，值越低则完成更多工作")
                    ).greaterThanOrEqualTo(0.0F);

                option("enableWorkStealing")
                    .docs(
                        "启用工作窃取/任务线程亲和性。此选项会尝试将任务保持在同一线程上执行以提高性能。",
                        "启用后，如果任务超过 \"stealThresholdMillis\" 所设定的截止时间，",
                        "其他 tick 线程可以接管该任务执行。"
                    );

                option("enableMidTickTasks").docs("启用亲和性调度器在等待当前 tick 截止时间期间执行中间任务");
                option("tickRegionAffinity")
                    .docs("Canvas 提供的 AFFINITY 调度器的线程亲和性。使用此选项可以将区域调度器的线程绑定到 CPU 核心")
                    .greaterThanOrEqualTo(0.0F);

                option("enableAffinitySchedulerCpuAffinity").docs("启用将 AFFINITY 区域调度器线程绑定到 CPU 核心");
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
                    "区域 tick 结束到下次开始之间的时间间隔，超过此时长服务器将记录调度器过载的警告。",
                    "这有助于判断是否需要分配更多线程，或帮助识别截止时间未达的问题"
                ).greaterThan(0.0F);

            option("defaultTickRate")
                .docs(
                    "调度器的默认 tick 速率。原版为 20，游戏运行速度会根据你调整的值变快或变慢。",
                    "注意：此选项通常仅用于调试目的或需要此变更的自定义环境"
                ).greaterThan(0.0F);

            option("guardSeverity")
                .docs(
                    Style.wrap(
                        "Canvas 引入了额外的 tick 线程检查来帮助发现插件问题。此选项决定新防护机制的严格程度"
                    ).defineEnum(GuardSeverity.class, (severity) -> {
                        return switch (severity) {
                            case LOG -> "仅在控制台记录警告，但继续执行操作";
                            case THROW -> "抛出异常，可能导致服务器崩溃。适合用于确保正确性";
                            case SILENT -> "不输出任何信息也不执行任何操作";
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
                        "世界生成过程中会创建大量不必要的流体后处理任务，",
                        "这可能导致服务器过载并在生成新区块时产生卡顿。",
                        "根据所选算法，这可以帮助减少卡顿并提升区块生成时的性能"
                    ).defineEnum(FluidPostProcessingMode.class, (mode) -> {
                        return switch (mode) {
                            case VANILLA -> "正常后处理算法，处理所有内容";
                            case DISABLED -> "完全禁用流体后处理";
                            case FILTERED -> "C2ME 算法，过滤不必要的后处理任务";
                        };
                    })
                );

            option("makeFluidPostProcessScheduledTick")
                .docs(
                    "启用此选项会将流体后处理转为定时 tick，",
                    "有助于缓解区块生成期间的 MSPT 尖刺问题"
                );
            option("endBiomeCacheSize").greaterThan(0.0F);
            option("structureOptimizations").docs(
                "这些选项移植自模组 StructureLayoutOptimizer, https://modrinth.com/mod/structure-layout-optimizer",
                "用于优化拼图结构和 NBT 片段的生成"
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
                        "是否使用替代策略使结构布局生成比默认模板池权重优化更快。",
                        "此替代策略通过将结构从模板池中收集的片段列表",
                        "更改为不包含重复条目来实现。"
                    )
                    .blank()
                    .wordWrap(
                        "启用此选项可以从高权重模板池结构中获得额外性能提升，",
                        "但会失去与原版种子在结构布局上的一致性"
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
                    "原版存在一个 bug：飞行中的末影珍珠在服务器关闭时会被复制。",
                    "当同时启用 \"restoreVanillaEnderPearlBehavior\" 选项时，此选项可修复该问题。"
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
                    "ClientboundSetEntityMotionPacket（实体速度包）通常会消耗大量网络带宽，",
                    "在大型正式服务器上可高达 60%。此选项过滤不必要的数据包，",
                    "同时保持原版视觉效果"
                );
            option("filterMovePackets").docs("过滤不需要发送的无用移动数据包");

            option("alternativePlayerListTick").docs("将玩家分桶以在 playerlist tick 中均匀分布");
            option("playerInfoSendInterval")
                .docs(
                    "如果启用了替代 playerlist tick，此选项控制每个桶",
                    "被 tick 的间隔（tick 数）"
                ).greaterThan(0.0F);
            option("asyncProtocolSwitch")
                .docs(
                    "使登录期间的协议切换变为异步，减少全局区域阻塞，",
                    "可提升玩家加入时的登录和配置阶段性能"
                );

            option("maximumPacketBytes")
                .docs(
                    "服务器向玩家发送单个数据包的最大字节数，超过此值将踢出玩家"
                ).greaterThan(0.0F);
            option("disablePaperPacketOverflowContainerFix")
                .docs(
                    "禁用 Paper 对发送到客户端的大型容器数据包的溢出回退机制。这意味着",
                    "如果容器数据过大，玩家尝试打开内容超过最大数据包字节大小",
                    "的容器时将被踢出"
                );
            option("packetTooLargeDisconnectReason")
                .docs(
                    "当服务器尝试发送超过最大数据包大小的数据包时，",
                    "发送给客户端的断开连接原因"
                );

            option("particleThrottling")
                .docs(
                    "限制每 tick 向每个玩家发送的粒子数据包。启用后，超过",
                    "每 tick 配置限制的粒子数据包将被静默丢弃。可减少",
                    "粒子使用密集服务器的网络开销"
                );
            option("particleThrottleLimit")
                .docs(
                    "启用粒子限流时，每个玩家每 tick 允许的最大粒子数据包数量"
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
        option("serverModName").docs("在服务器列表和客户端信息中显示的服务器 mod 名称").word();
        option("restoreVanillaEnderPearlBehavior").docs("恢复并修复被 Folia 破坏的原版末影珍珠行为");

        option("displayWorldLoadScreenForPortaling")
            .docs(
                "Folia 的传送重写导致客户端无法正确显示世界加载画面，",
                "而是显示空白虚空。启用此选项后，Canvas 将显示正确的世界加载画面"
            );
        option("displayWorldLoadScreenForTeleporting")
            .docs(
                "类似于 displayWorldLoadScreenForPortaling，但适用于跨区域传送（如 /tppos 命令）。",
                "启用此选项后，Canvas 将在跨区域传送时显示正确的世界加载画面"
            );
        option("cacheMinecraft2BukkitEntityTypeConversion").docs("是否缓存开销较大的 CraftEntityType#minecraftToBukkit 调用");
        option("tileEntitySnapshotCreation").docs("启用在获取方块状态时创建方块实体快照");
        option("allowLegacyScheduler").docs("允许传统 Bukkit 调度器操作，用于兼容不支持 Folia 的插件（如 MythicMobs）");

        option("defaultRespawnDimensionKey")
            .docs(
                "服务器的默认重生维度。这可以帮助需要出于配置原因将重生维度更改为",
                "不同世界的服务器，例如需要将玩家传送到 \"spawn\" 世界等场景。",
                "此选项也适用于末地传送门和下界传送门，用以替代主世界，",
                "例如从下界传送的实体的目标维度将被设定为此处"
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
            option("barrelRows").docs("桶方块的行数").between(1, 6);
            option("enderChestSixRows").docs("是否为玩家末影箱使用 6 行，而非默认的 3 行");
            option("enderChestPermissionRows")
                .docs(
                    Style.wrap("是否使用基于权限的系统来定义每个玩家末影箱的大小")
                        .literal("可用权限").endLine()
                        .literal(" - purpur.enderchest.rows.six").endLine()
                        .literal(" - purpur.enderchest.rows.five").endLine()
                        .literal(" - purpur.enderchest.rows.four").endLine()
                        .literal(" - purpur.enderchest.rows.three").endLine()
                        .literal(" - purpur.enderchest.rows.two").endLine()
                        .literal(" - purpur.enderchest.rows.one").endLine()
                );
            option("enderChestPersistHiddenRows").docs("即使某些槽位因权限限制变得不可访问，物品是否仍应保留在这些槽位中");
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
                    "启用后，当移动向量为零且碰撞箱未变化时跳过实体移动处理。",
                    "可在拥有大量实体的服务器上提升性能。"
                );
            option("skipNegligiblePlanarMovementMultiplication")
                .docs(
                    "启用后，当移动值可忽略不计且方块速度因子实际为 1.0 时，",
                    "跳过平面移动乘法运算。减少不必要的 Vec3 对象分配。"
                );
            option("fasterChunkSerialization")
                .docs(
                    "使用基于 Lithium 调色板压缩的优化区块序列化策略。",
                    "减少对象分配，提升区块保存/网络传输性能。"
                );
            option("equipmentTracking")
                .docs(
                    "Lithium 风格的装备变更追踪。跳过未变更装备的实体",
                    "不必要的附魔 tick 和装备变更检测。"
                );
            option("throttleInactiveGoalSelectorTick")
                .docs(
                    "限制非活跃区块中实体的 AI 目标选择器 tick。",
                    "目标选择器不再每个非活跃 tick 都执行，而是每 20 个非活跃 tick 才执行一次。",
                    "移植自 Pufferfish，经 Spring 适配。"
                );
            option("reduceEntityAllocations")
                .docs(
                    "缓存 AttributeMap.getInstance 中使用的 lambda 以减少对象分配。",
                    "Java 每次调用都会分配一个新的 lambda 实例，即使捕获的字段相同。",
                    "移植自 Pufferfish，经 Spring 适配。"
                );
            option("removeTickGuardLambda")
                .docs(
                    "通过内联 try-catch 移除实体 tick 防护中的 lambda 分配。",
                    "避免每次实体 tick 时分配一个方法引用。",
                    "移植自 Pufferfish，经 Spring 适配。"
                );
            option("cacheClimbingCheckForActivation")
                .docs(
                    "为实体激活范围检查缓存每个方块位置的攀爬检查结果。",
                    "避免在位置未变化时重新计算开销较大的 onClimbable() 检查。",
                    "移植自 Pufferfish，经 Spring 适配。"
                );
            option("optimizeSunBurnTick")
                .docs(
                    "通过缓存眼部方块位置和重排检查顺序来优化日晒 tick 检查，",
                    "以便在执行高开销操作前尽早退出。",
                    "移植自 Gale，经 Spring 适配。"
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
    }

    {
        option("regionFormat")
            .docs(
                Style.wrap(
                    "用于世界存储的区域文件格式。Linear 格式可减少约 50% 的磁盘使用量",
                    "并提升区块加载/保存速度。MCA 是标准的原版 Anvil 格式。"
                ).defineEnum(io.canvasmc.canvas.region.EnumRegionFormat.class, (mode) -> {
                    return switch (mode) {
                        case MCA -> "标准 Anvil 格式（原版默认）";
                        case LINEAR_V2 -> "Linear v2 格式 - 磁盘使用减少 50%，更快的 I/O";
                    };
                })
            );
    }

    {
        option("regionCompressionLevel")
            .docs(
                "Linear 区块格式的 Zstd 压缩等级。仅在选择 LINEAR_V2 格式时生效。",
                "范围: 1（最快，低压缩）到 22（最慢，高压缩），推荐默认值 3。",
                "更高的压缩等级可节省更多磁盘空间，但会略微增加写入时 CPU 开销。"
            )
            .between(1, 22);
    }

    public int regionCompressionLevel = 3;

    public io.canvasmc.canvas.region.EnumRegionFormat regionFormat = io.canvasmc.canvas.region.EnumRegionFormat.MCA;

    public Chat chat = new Chat();
    public static class Chat extends Part {

        {
            option("disableChatReporting").docs("禁用 Minecraft 聊天签名以防止玩家聊天举报");
            option("disableChatVerificationOrder").docs("禁用 Minecraft 聊天验证排序");
        }

        public boolean disableChatReporting = false;
        public boolean disableChatVerificationOrder = false;
    }

    public Logs logs = new Logs();
    public static class Logs extends Part {

        {
            option("enableLogCleaner").docs("自动删除 \"logs\" 目录中的旧日志文件");
            option("length").docs("日志文件被标记为删除的时间单位数量");
            option("unit").docs("用于比较文件年龄与当前时间的时间单位类型");
        }

        public boolean enableLogCleaner = false;
        public long length = 30;
        public ChronoUnit unit = ChronoUnit.DAYS;
    }

    public LogToConsole logToConsole = new LogToConsole();
    public static class LogToConsole extends Part {

        {
            option("invalidStatistics").docs("是否记录无效统计数据的错误日志");
            option("emptyMessageWarning").docs("是否记录玩家发送空消息的警告日志");
            option("ignoredAdvancements").docs("是否记录被忽略的进度的警告日志");
            option("setBlockInFarChunk").docs("是否记录在远距离区块中调用 setBlock 的警告日志");
            option("unrecognizedRecipes").docs("是否记录无法识别的配方的错误日志");
            option("expiredMessageWarning").docs("是否记录过期消息的警告日志");
            option("notSecureMarker").docs("是否记录聊天消息的 \"Not Secure\" 标记");
            option("nullIdDisconnections").docs("是否记录 ID 为 null 的断开连接日志");
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

    public DAB dab = new DAB();
    public static class DAB extends Part {
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

    public OptimizedPoweredRails optimizedPoweredRails = new OptimizedPoweredRails();
    public static class OptimizedPoweredRails extends Part {
        public boolean enabled = true;
        public int railActivationRange = 8; // Vanilla signal distance (PoweredRailBlock recursionCount >= 8)
    }

    public AsyncPlayerDataSave asyncPlayerDataSave = new AsyncPlayerDataSave();
    public static class AsyncPlayerDataSave extends Part {
        public boolean enabled = true;
    }

}
