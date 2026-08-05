package io.canvasmc.canvas.fakechunks;

public final class ChunkKeyCodec {

    private ChunkKeyCodec() {}

    public static long pack(int x, int z) {
        return (((long) x) & 0xFFFFFFFFL) | ((((long) z) & 0xFFFFFFFFL) << 32);
    }

    public static int unpackX(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    public static int unpackZ(long key) {
        return (int) (key >>> 32);
    }
}
