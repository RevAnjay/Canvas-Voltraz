package io.canvasmc.canvas.async;

import io.canvasmc.canvas.GlobalConfiguration;
import net.minecraft.util.Util;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AsyncPlayerDataSaving {

    public static ExecutorService IO_POOL = null;

    private AsyncPlayerDataSaving() {
    }

    public static void init() {
        if (IO_POOL == null) {
            IO_POOL = new ThreadPoolExecutor(
                1,
                1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new com.google.common.util.concurrent.ThreadFactoryBuilder()
                    .setPriority(Thread.NORM_PRIORITY - 2)
                    .setNameFormat("Canvas IO Thread")
                    .setUncaughtExceptionHandler(Util::onThreadException)
                    .build(),
                new ThreadPoolExecutor.DiscardPolicy()
            );
        }
    }

    public static void shutdown() {
        if (IO_POOL != null) {
            net.minecraft.server.MinecraftServer.LOGGER.info("Waiting for player I/O executor to shutdown...");
            IO_POOL.shutdown();
            try {
                IO_POOL.awaitTermination(60L, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }
    }

    public static Optional<Future<?>> submit(Runnable runnable) {
        if (GlobalConfiguration.getInstance() == null || GlobalConfiguration.getInstance().asyncPlayerDataSave == null || !GlobalConfiguration.getInstance().asyncPlayerDataSave.enabled) {
            runnable.run();
            return Optional.empty();
        } else {
            if (IO_POOL == null) {
                init();
            }
            return Optional.of(IO_POOL.submit(runnable));
        }
    }
}
