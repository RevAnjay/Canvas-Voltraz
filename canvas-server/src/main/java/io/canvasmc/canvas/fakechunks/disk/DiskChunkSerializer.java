package io.canvasmc.canvas.fakechunks.disk;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
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
            int sectionsCount = level.getSectionsCount();
            int minSectionY = level.getMinSectionY();
            PalettedContainerFactory factory = level.palettedContainerFactory();
            LevelChunkSection[] sections = new LevelChunkSection[sectionsCount];

            var sectionTagsOpt = rootTag.getList(SerializableChunkData.SECTIONS_TAG);
            if (sectionTagsOpt.isPresent()) {
                ListTag sectionTags = sectionTagsOpt.get();
                for (int i = 0; i < sectionTags.size(); i++) {
                    var sectionTagOpt = sectionTags.getCompound(i);
                    if (sectionTagOpt.isEmpty()) continue;
                    CompoundTag sectionTag = sectionTagOpt.get();

                    var yOpt = sectionTag.getByte("Y");
                    if (yOpt.isEmpty()) continue;
                    int y = yOpt.get().intValue();
                    int sectionIndex = y - minSectionY;

                    if (sectionIndex >= 0 && sectionIndex < sectionsCount) {
                        PalettedContainer<BlockState> blocks;
                        if (sectionTag.get("block_states") instanceof CompoundTag blockTag) {
                            blocks = factory.blockStatesContainerCodec().parse(NbtOps.INSTANCE, blockTag)
                                    .result()
                                    .orElseGet(factory::createForBlockStates);
                        } else {
                            blocks = factory.createForBlockStates();
                        }

                        PalettedContainer<Holder<Biome>> biomes =
                                sectionTag.get("biomes") instanceof CompoundTag biomeTag
                                ? factory.biomeContainerRWCodec().parse(NbtOps.INSTANCE, biomeTag)
                                        .result()
                                        .orElseGet(factory::createForBiomes)
                                : factory.createForBiomes();

                        sections[sectionIndex] = new LevelChunkSection(blocks, biomes);
                    }
                }
            }

            for (int i = 0; i < sectionsCount; i++) {
                if (sections[i] == null) {
                    sections[i] = new LevelChunkSection(
                        factory.createForBlockStates(),
                        factory.createForBiomes()
                    );
                }
            }

            return new LevelChunk(
                level,
                new ChunkPos(chunkX, chunkZ),
                UpgradeData.EMPTY,
                new LevelChunkTicks<>(),
                new LevelChunkTicks<>(),
                0L,
                sections,
                null,
                null
            );
        } catch (Exception e) {
            try {
                SerializableChunkData chunkData = SerializableChunkData.parse(level, level.palettedContainerFactory(), rootTag);
                if (chunkData != null) {
                    net.minecraft.world.level.chunk.storage.RegionStorageInfo storageInfo = new net.minecraft.world.level.chunk.storage.RegionStorageInfo(level.dimension().identifier().getPath(), level.dimension(), "chunk");
                    ChunkAccess chunkAccess = chunkData.read(level, level.getPoiManager(), storageInfo, new net.minecraft.world.level.ChunkPos(chunkX, chunkZ));
                    if (chunkAccess instanceof LevelChunk levelChunk) {
                        return levelChunk;
                    } else if (chunkAccess instanceof ImposterProtoChunk imposter) {
                        return imposter.getWrapped();
                    }
                }
            } catch (Exception fallbackErr) {
                LOGGER.warn("Failed to deserialize chunk [{}, {}]: {}", chunkX, chunkZ, fallbackErr.getMessage());
            }
            return null;
        }
    }
}
