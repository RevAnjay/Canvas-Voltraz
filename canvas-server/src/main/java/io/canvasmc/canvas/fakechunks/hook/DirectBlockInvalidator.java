package io.canvasmc.canvas.fakechunks.hook;

import io.canvasmc.canvas.fakechunks.CanvasFakeChunkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class DirectBlockInvalidator {

    private DirectBlockInvalidator() {}

    public static void onBlockChanged(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        CanvasFakeChunkManager.get().invalidateChunk(level.getWorld().getUID(), chunkX, chunkZ);
    }
}
