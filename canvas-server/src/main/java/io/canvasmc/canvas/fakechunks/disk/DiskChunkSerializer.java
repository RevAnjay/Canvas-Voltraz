package io.canvasmc.canvas.fakechunks.disk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

public final class DiskChunkSerializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiskChunkSerializer.class);

    private DiskChunkSerializer() {}

    public static LevelChunk parseChunkFromNbt(byte[] nbtBytes, ServerLevel level, int chunkX, int chunkZ) {
        if (nbtBytes == null || nbtBytes.length < 2) {
            return null;
        }

        CompoundTag rootTag;
        try {
            rootTag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(nbtBytes)));
        } catch (Exception e) {
            LOGGER.warn("Failed to parse NBT for chunk [{}, {}]: {}", chunkX, chunkZ, e.getMessage());
            return null;
        }

        try {
            SerializableChunkData chunkData = SerializableChunkData.parse(level, level.palettedContainerFactory(), rootTag);
            if (chunkData != null) {
                net.minecraft.world.level.chunk.storage.RegionStorageInfo storageInfo = new net.minecraft.world.level.chunk.storage.RegionStorageInfo(level.dimension().identifier().getPath(), level.dimension(), "chunk");
                ChunkAccess chunkAccess = chunkData.read(level, level.getPoiManager(), storageInfo, new net.minecraft.world.level.ChunkPos(chunkX, chunkZ));
                if (chunkAccess instanceof LevelChunk levelChunk) {
                    return levelChunk;
                } else if (chunkAccess instanceof ImposterProtoChunk imposter) {
                    return imposter.getWrapped();
                } else if (chunkAccess != null) {
                    LOGGER.debug("ChunkAccess for [{}, {}] is type {} (status={}), not LevelChunk", chunkX, chunkZ, chunkAccess.getClass().getName(), chunkAccess.getPersistedStatus());
                }
            } else {
                LOGGER.warn("SerializableChunkData.parse returned null for [{}, {}]", chunkX, chunkZ);
            }
            return null;
        } catch (Exception e) {
            LOGGER.warn("Failed to deserialize SerializableChunkData for chunk [{}, {}]: {}", chunkX, chunkZ, e.getMessage());
            return null;
        }
    }
}
