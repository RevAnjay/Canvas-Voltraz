package io.canvasmc.canvas.fakechunks.disk;

import io.canvasmc.canvas.fakechunks.async.ChunkAsyncExecutor;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public final class DiskChunkReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiskChunkReader.class);

    private DiskChunkReader() {}

    public static byte[] readNbtFromDisk(ServerLevel level, int chunkX, int chunkZ) {
        World world = level.getWorld();
        if (world == null) {
            return null;
        }

        File worldFolder = world.getWorldFolder();
        if (!worldFolder.exists()) {
            return null;
        }

        return RegionFileReader.readChunkBytes(worldFolder, chunkX, chunkZ);
    }
}
