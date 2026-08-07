package io.canvasmc.canvas.fakechunks;

import io.canvasmc.canvas.GlobalConfiguration;
import io.canvasmc.canvas.fakechunks.async.ChunkAsyncExecutor;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

public final class FakeChunkCache {

    private static final FakeChunkCache INSTANCE = new FakeChunkCache();
    public static FakeChunkCache get() { return INSTANCE; }

    private final ConcurrentMap<UUID, ConcurrentMap<Long, CacheEntry>> worldCaches = new ConcurrentHashMap<>();

    private FakeChunkCache() {}

    private static final class CacheEntry {
        final ClientboundLevelChunkWithLightPacket packet;
        final long expireAtMs;

        CacheEntry(ClientboundLevelChunkWithLightPacket packet, long expireAtMs) {
            this.packet = packet;
            this.expireAtMs = expireAtMs;
        }
    }

    private ConcurrentMap<Long, CacheEntry> getOrCreateCache(UUID worldId) {
        return worldCaches.computeIfAbsent(worldId, id -> new ConcurrentHashMap<>());
    }

    public ClientboundLevelChunkWithLightPacket getIfCached(ServerLevel level, int chunkX, int chunkZ) {
        long key = ChunkKeyCodec.pack(chunkX, chunkZ);
        ConcurrentMap<Long, CacheEntry> cache = getOrCreateCache(level.getWorld().getUID());
        long now = System.currentTimeMillis();

        CacheEntry existing = cache.get(key);
        if (existing != null && existing.expireAtMs > now) {
            return existing.packet;
        }
        return null;
    }

    public CompletableFuture<ClientboundLevelChunkWithLightPacket> getOrBuildAsync(ServerLevel level, int chunkX, int chunkZ) {
        ClientboundLevelChunkWithLightPacket cached = getIfCached(level, chunkX, chunkZ);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                ClientboundLevelChunkWithLightPacket packet = null;

                if (chunk != null) {
                    packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
                    packet.setReady(true);
                } else {
                    System.out.println("[FakeChunks Debug] Reading NBT from disk for chunk (" + chunkX + ", " + chunkZ + ")...");
                    byte[] nbtBytes = io.canvasmc.canvas.fakechunks.disk.DiskChunkReader.readNbtFromDisk(level, chunkX, chunkZ);
                    if (nbtBytes != null) {
                        System.out.println("[FakeChunks Debug] NBT read successful (" + nbtBytes.length + " bytes) for chunk (" + chunkX + ", " + chunkZ + "). Parsing chunk...");
                        LevelChunk deserializedChunk = io.canvasmc.canvas.fakechunks.disk.DiskChunkSerializer.parseChunkFromNbt(nbtBytes, level, chunkX, chunkZ);
                        if (deserializedChunk != null) {
                            packet = new ClientboundLevelChunkWithLightPacket(deserializedChunk, level.getLightEngine(), null, null);
                            packet.setReady(true);
                            System.out.println("[FakeChunks Debug] Chunk parse successful for (" + chunkX + ", " + chunkZ + ")");
                        } else {
                            System.out.println("[FakeChunks Debug] Chunk parse returned null for (" + chunkX + ", " + chunkZ + ")");
                        }
                    } else {
                        System.out.println("[FakeChunks Debug] NBT read returned null for (" + chunkX + ", " + chunkZ + "), attempting async fallback load...");
                        try {
                            org.bukkit.Chunk bChunk = level.getWorld().getChunkAtAsync(chunkX, chunkZ, false).get();
                            if (bChunk instanceof org.bukkit.craftbukkit.CraftChunk craftChunk) {
                                net.minecraft.world.level.chunk.ChunkAccess access = craftChunk.getHandle(net.minecraft.world.level.chunk.status.ChunkStatus.FULL);
                                if (access instanceof LevelChunk loadedChunk) {
                                    packet = new ClientboundLevelChunkWithLightPacket(loadedChunk, level.getLightEngine(), null, null);
                                    packet.setReady(true);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }

                if (packet != null) {
                    long key = ChunkKeyCodec.pack(chunkX, chunkZ);
                    long ttlMs = GlobalConfiguration.get().fakeChunks.cacheTtlSeconds * 1000L;
                    ConcurrentMap<Long, CacheEntry> cache = getOrCreateCache(level.getWorld().getUID());
                    cache.put(key, new CacheEntry(packet, System.currentTimeMillis() + ttlMs));
                }
                return packet;
            } catch (Throwable t) {
                System.err.println("[FakeChunks Error] Failed to getOrBuildAsync for chunk [" + chunkX + ", " + chunkZ + "]: " + t.getMessage());
                t.printStackTrace();
                return null;
            }
        }, ChunkAsyncExecutor.get());
    }

    public void invalidate(UUID worldId, long chunkKey) {
        ConcurrentMap<Long, CacheEntry> cache = worldCaches.get(worldId);
        if (cache != null) {
            cache.remove(chunkKey);
        }
    }

    public void invalidateWorld(UUID worldId) {
        ConcurrentMap<Long, CacheEntry> cache = worldCaches.remove(worldId);
        if (cache != null) {
            cache.clear();
        }
    }
}
