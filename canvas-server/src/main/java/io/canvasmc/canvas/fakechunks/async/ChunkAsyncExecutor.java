package io.canvasmc.canvas.fakechunks.async;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChunkAsyncExecutor {

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
    private static final int WORKERS = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
        WORKERS,
        WORKERS,
        0L,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(WORKERS * 32),
        new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "Canvas-FakeChunkWorker-" + THREAD_COUNTER.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        },
        // DiscardOldest drops stale distant chunk build requests when flooded instead of stalling the calling thread
        new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    private ChunkAsyncExecutor() {}

    public static ExecutorService get() {
        return EXECUTOR;
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }
}
