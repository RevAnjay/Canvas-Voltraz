package io.canvasmc.canvas.util;

// Canvas start - reduce array allocations
// Cached empty arrays to avoid unnecessary allocations.
// Ported from JettPack/Gale.
public final class ArrayConstants {
    public static final byte[] emptyByteArray = new byte[0];
    public static final int[] emptyIntArray = new int[0];
    public static final long[] emptyLongArray = new long[0];
    public static final Object[] emptyObjectArray = new Object[0];
    public static final String[] emptyStringArray = new String[0];
    public static final org.bukkit.entity.Entity[] emptyBukkitEntityArray = new org.bukkit.entity.Entity[0];
    public static final net.minecraft.world.entity.Entity[] emptyEntityArray = new net.minecraft.world.entity.Entity[0];
    public static final int[] zeroSingletonIntArray = new int[]{0};

    private ArrayConstants() {}
}
// Canvas end - reduce array allocations
