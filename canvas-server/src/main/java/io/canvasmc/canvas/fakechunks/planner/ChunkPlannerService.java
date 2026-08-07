package io.canvasmc.canvas.fakechunks.planner;

import io.canvasmc.canvas.fakechunks.ChunkKeyCodec;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

public final class ChunkPlannerService {

    public static final int MAX_CHUNK_DISTANCE = 128;
    private static final int MAX_RADIUS_INDEX = MAX_CHUNK_DISTANCE + 2;
    private static final long[][] RADIUS_ITERATION_LIST = new long[MAX_RADIUS_INDEX + 1][];

    static {
        for (int radius = 0; radius < RADIUS_ITERATION_LIST.length; radius++) {
            final int r = radius;
            List<Integer> range = IntStream.rangeClosed(-r, r).boxed().toList();
            RADIUS_ITERATION_LIST[r] = range.stream()
                .flatMap(x -> range.stream().map(z -> ChunkKeyCodec.pack(x, z)))
                .filter(key -> {
                    int x = ChunkKeyCodec.unpackX(key);
                    int z = ChunkKeyCodec.unpackZ(key);
                    return Math.max(Math.abs(x), Math.abs(z)) <= r;
                })
                .sorted(Comparator.comparingInt(key -> {
                    int x = ChunkKeyCodec.unpackX(key);
                    int z = ChunkKeyCodec.unpackZ(key);
                    return x * x + z * z;
                }))
                .mapToLong(Long::longValue)
                .toArray();
        }
    }

    private ChunkPlannerService() {}

    public static long[] radiusIterationList(int radius) {
        int index = Math.clamp(radius, 0, MAX_RADIUS_INDEX);
        return RADIUS_ITERATION_LIST[index];
    }
}
