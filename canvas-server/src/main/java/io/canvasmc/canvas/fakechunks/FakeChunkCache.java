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
    private final ConcurrentMap<CacheKey, CompletableFuture<ClientboundLevelChunkWithLightPacket>> builds = new ConcurrentHashMap<>();
    private static final long CLEANUP_INTERVAL_MS = 60_000L;
    private volatile long nextCleanupMs = System.currentTimeMillis() + CLEANUP_INTERVAL_MS;
    private static final int MAX_ENTRIES_PER_WORLD = 4096;

    private FakeChunkCache() {}

    private record CacheKey(UUID worldId, long chunkKey) {}
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

        maybeCleanup(now);

        CacheEntry existing = cache.get(key);
        if (existing != null && existing.expireAtMs > now) {
            return existing.packet;
        }
        return null;
    }

    private void maybeCleanup(long now) {
        if (now < nextCleanupMs) return;
        nextCleanupMs = now + CLEANUP_INTERVAL_MS; // unchecked volatile write, at most one extra sweep per interval
        worldCaches.forEach((worldId, cache) -> {
            cache.values().removeIf(e -> e.expireAtMs <= now);
            if (cache.isEmpty()) worldCaches.remove(worldId, cache); // drops dead worlds
        });
    }
    private static java.util.BitSet createFullSkyLightMask(ServerLevel level) {
        int sectionCount = level.getLightEngine().getLightSectionCount();
        java.util.BitSet mask = new java.util.BitSet(sectionCount);
        mask.set(0, sectionCount);
        return mask;
    }

    public CompletableFuture<ClientboundLevelChunkWithLightPacket> getOrBuildAsync(ServerLevel level, int chunkX, int chunkZ) {
        ClientboundLevelChunkWithLightPacket cached = getIfCached(level, chunkX, chunkZ);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        CacheKey key = new CacheKey(level.getWorld().getUID(), ChunkKeyCodec.pack(chunkX, chunkZ));
        CompletableFuture<ClientboundLevelChunkWithLightPacket> existing = builds.get(key);
        if (existing != null) return existing;

        CompletableFuture<ClientboundLevelChunkWithLightPacket> future = new CompletableFuture<>();
        existing = builds.putIfAbsent(key, future);
        if (existing != null) return existing;
        future.whenComplete((packet, error) -> builds.remove(key, future));
        ChunkAsyncExecutor.get().execute(() -> {
            try {
                java.util.BitSet skyLightMask = createFullSkyLightMask(level);

                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk != null) {
                    ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), skyLightMask, null);
                    packet.setReady(true);
                    cacheAndComplete(level, chunkX, chunkZ, packet, future);
                    return;
                }

                byte[] nbtBytes = io.canvasmc.canvas.fakechunks.disk.DiskChunkReader.readNbtFromDisk(level, chunkX, chunkZ);
                if (nbtBytes != null) {
                    LevelChunk deserializedChunk = io.canvasmc.canvas.fakechunks.disk.DiskChunkSerializer.parseChunkFromNbt(nbtBytes, level, chunkX, chunkZ);
                    if (deserializedChunk != null) {
                        ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(deserializedChunk, level.getLightEngine(), skyLightMask, null);
                        packet.setReady(true);
                        cacheAndComplete(level, chunkX, chunkZ, packet, future);
                        return;
                    }
                }

                level.getWorld().getChunkAtAsync(chunkX, chunkZ, false).thenAcceptAsync(bChunk -> {
                    if (bChunk instanceof org.bukkit.craftbukkit.CraftChunk craftChunk) {
                        net.minecraft.world.level.chunk.ChunkAccess access = craftChunk.getHandle(net.minecraft.world.level.chunk.status.ChunkStatus.FULL);
                        if (access instanceof LevelChunk loadedChunk) {
                            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(loadedChunk, level.getLightEngine(), skyLightMask, null);
                            packet.setReady(true);
                            cacheAndComplete(level, chunkX, chunkZ, packet, future);
                            return;
                        }
                    }
                    future.complete(null);
                }, ChunkAsyncExecutor.get()).exceptionally(err -> {
                    future.complete(null);
                    return null;
                });

            } catch (Throwable t) {
                future.complete(null);
            }
        });

        return future;
    }

    private void cacheAndComplete(ServerLevel level, int chunkX, int chunkZ, ClientboundLevelChunkWithLightPacket packet, CompletableFuture<ClientboundLevelChunkWithLightPacket> future) {
        if (packet != null) {
            long key = ChunkKeyCodec.pack(chunkX, chunkZ);
            long ttlMs = GlobalConfiguration.get().fakeChunks.cacheTtlSeconds * 1000L;
            ConcurrentMap<Long, CacheEntry> cache = getOrCreateCache(level.getWorld().getUID());
            cache.put(key, new CacheEntry(packet, System.currentTimeMillis() + ttlMs));
            // ponytail: arbitrary eviction keeps the hot path lock-free; use bounded LRU only if hit-rate metrics justify its lock.
            while (cache.size() > MAX_ENTRIES_PER_WORLD) {
                var iterator = cache.keySet().iterator();
                if (!iterator.hasNext()) break;
                cache.remove(iterator.next());
            }
        }
        future.complete(packet);
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
        builds.keySet().removeIf(key -> key.worldId().equals(worldId));
    }

    public void clear() {
        worldCaches.clear();
        builds.forEach((key, future) -> future.cancel(false));
        builds.clear();
    }
}
