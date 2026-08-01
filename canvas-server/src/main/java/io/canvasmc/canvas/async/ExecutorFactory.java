package io.canvasmc.canvas.async;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.canvasmc.canvas.GlobalConfiguration;
import io.papermc.paper.threadedregions.scheduler.FoliaAsyncScheduler;
import net.minecraft.DefaultUncaughtExceptionHandlerWithName;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.jspecify.annotations.NonNull;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ExecutorFactory {

    private static final org.slf4j.Logger DOWNLOAD_LOGGER = LoggerFactory.getLogger("DownloadPool");
    private static final org.slf4j.Logger FOLIA_ASYNC_LOGGER = LoggerFactory.getLogger("FoliaAsyncScheduler");

    public static ExecutorService buildChatExecutor() {
        if (GlobalConfiguration.getInstance().performance.useVirtualThread.asyncChatExecutor) {
            return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                    .name("Async Chat Thread - #", 0)
                    .uncaughtExceptionHandler(new DefaultUncaughtExceptionHandlerWithName(MinecraftServer.LOGGER))
                    .factory()
            );
        }
        return Executors.newCachedThreadPool(
            new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("Async Chat Thread - #%d")
                .setThreadFactory(Executors.defaultThreadFactory())
                .setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandlerWithName(MinecraftServer.LOGGER))
                .build()
        );
    }

    public static ExecutorService buildDownloadPoolExecutor() {
        if (GlobalConfiguration.getInstance().performance.useVirtualThread.downloadPool) {
            return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                    .name("Download-", 0)
                    .uncaughtExceptionHandler((Thread thread, Throwable throwable) -> DOWNLOAD_LOGGER.error("Uncaught exception in thread {}", thread.getName(), throwable))
                    .factory()
            );
        }
        return Executors.newFixedThreadPool(4, new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger();

            @Override
            public Thread newThread(@NonNull Runnable run) {
                Thread ret = new Thread(run);
                ret.setDaemon(true);
                ret.setName("Download-" + this.count.getAndIncrement());
                ret.setUncaughtExceptionHandler((Thread thread, Throwable throwable) -> DOWNLOAD_LOGGER.error("Uncaught exception in thread {}", thread.getName(), throwable));
                return ret;
            }
        });
    }

    public static ExecutorService buildBukkitAsyncSchedulerExecutor() {
        if (GlobalConfiguration.getInstance().performance.useVirtualThread.bukkitAsyncScheduler) {
            return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                    .name("Craft Scheduler Thread - ", 0)
                    .uncaughtExceptionHandler(new DefaultUncaughtExceptionHandlerWithName(LoggerFactory.getLogger("CraftAsyncScheduler")))
                    .factory()
            );
        }
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            4, Integer.MAX_VALUE, 30L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            new ThreadFactoryBuilder().setNameFormat("Craft Scheduler Thread - %1$d").build()
        );
        executor.allowCoreThreadTimeOut(true);
        executor.prestartAllCoreThreads();
        return executor;
    }

    public static ExecutorService buildFoliaAsyncSchedulerExecutor() {
        if (GlobalConfiguration.getInstance().performance.useVirtualThread.foliaAsyncScheduler) {
            return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                    .name("Folia Async Scheduler Thread #", 0)
                    .uncaughtExceptionHandler((final Thread thread, final Throwable thr) -> FOLIA_ASYNC_LOGGER.error("Uncaught exception in thread: {}", thread.getName(), thr))
                    .factory()
            );
        }
        return new ThreadPoolExecutor(Math.max(4, Runtime.getRuntime().availableProcessors() / 2), Integer.MAX_VALUE,
            30L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            new ThreadFactory() {
                private final AtomicInteger idGenerator = new AtomicInteger();

                @Override
                public Thread newThread(final @NonNull Runnable run) {
                    final Thread ret = new Thread(run);
                    ret.setName("Folia Async Scheduler Thread #" + this.idGenerator.getAndIncrement());
                    ret.setPriority(Thread.NORM_PRIORITY - 1);
                    ret.setUncaughtExceptionHandler((final Thread thread, final Throwable thr) -> FOLIA_ASYNC_LOGGER.error("Uncaught exception in thread: {}", thread.getName(), thr));
                    return ret;
                }
            }
        );
    }
}
