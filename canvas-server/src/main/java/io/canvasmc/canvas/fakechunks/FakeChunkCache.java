package io.canvasmc.canvas.fakechunks;

import io.canvasmc.canvas.GlobalConfiguration;
import java.util.UUID;
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

    public ClientboundLevelChunkWithLightPacket getOrBuild(ServerLevel level, int chunkX, int chunkZ) {
        long key = ChunkKeyCodec.pack(chunkX, chunkZ);
        ConcurrentMap<Long, CacheEntry> cache = getOrCreateCache(level.getWorld().getUID());
        long now = System.currentTimeMillis();

        CacheEntry existing = cache.get(key);
        if (existing != null && existing.expireAtMs > now) {
            return existing.packet;
        }

        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null || !chunk.isLoaded()) {
            return null;
        }

        ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
        long ttlMs = GlobalConfiguration.get().fakeChunks.cacheTtlSeconds * 1000L;
        cache.put(key, new CacheEntry(packet, now + ttlMs));
        return packet;
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
