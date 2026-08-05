package io.canvasmc.canvas.fakechunks;

import io.canvasmc.canvas.GlobalConfiguration;
import io.canvasmc.canvas.WorldConfig;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class CanvasFakeChunkManager {

    private static final CanvasFakeChunkManager INSTANCE = new CanvasFakeChunkManager();
    public static CanvasFakeChunkManager get() { return INSTANCE; }

    private final Map<UUID, PlayerFakeChunkSession> sessions = new ConcurrentHashMap<>();

    private CanvasFakeChunkManager() {}

    public void tickPlayer(ServerPlayer player) {
        if (player == null || player.hasDisconnected()) {
            return;
        }

        GlobalConfiguration globalConfig = GlobalConfiguration.get();
        if (!globalConfig.fakeChunks.enabled) {
            removePlayer(player);
            return;
        }

        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        WorldConfig worldConfig = WorldConfig.forWorld(level);
        if (!worldConfig.fakeChunks.enabled) {
            removePlayer(player);
            return;
        }

        int maxDist = worldConfig.fakeChunks.maxViewDistance > 0 
            ? worldConfig.fakeChunks.maxViewDistance 
            : globalConfig.fakeChunks.maxViewDistance;

        PlayerFakeChunkSession session = sessions.computeIfAbsent(player.getUUID(), uuid -> new PlayerFakeChunkSession());
        session.tick(player, level, maxDist, globalConfig.fakeChunks.maxSendingRatePerTick);
    }

    public void removePlayer(ServerPlayer player) {
        if (player != null) {
            PlayerFakeChunkSession session = sessions.remove(player.getUUID());
            if (session != null) {
                session.clear();
            }
        }
    }

    public void invalidateChunk(UUID worldId, int chunkX, int chunkZ) {
        long key = ChunkKeyCodec.pack(chunkX, chunkZ);
        FakeChunkCache.get().invalidate(worldId, key);
    }

    public static final class PlayerFakeChunkSession {
        private final LongSet sentChunks = new LongOpenHashSet();

        public void tick(ServerPlayer player, ServerLevel level, int maxRadius, int maxRate) {
            int playerChunkX = player.chunkPosition().x;
            int playerChunkZ = player.chunkPosition().z;
            int serverViewDist = level.serverLevelData.canvas$distanceConfig.viewDistanceOrDefault();

            int sentCount = 0;

            for (int r = serverViewDist + 1; r <= maxRadius && sentCount < maxRate; r++) {
                for (int x = -r; x <= r && sentCount < maxRate; x++) {
                    for (int z = -r; z <= r && sentCount < maxRate; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != r) {
                            continue;
                        }
                        int targetX = playerChunkX + x;
                        int targetZ = playerChunkZ + z;
                        long key = ChunkKeyCodec.pack(targetX, targetZ);

                        if (!sentChunks.contains(key)) {
                            ClientboundLevelChunkWithLightPacket packet = FakeChunkCache.get().getOrBuild(level, targetX, targetZ);
                            if (packet != null) {
                                player.connection.send(packet);
                                sentChunks.add(key);
                                sentCount++;
                            }
                        }
                    }
                }
            }

            if (sentChunks.size() > 4096) {
                sentChunks.removeIf((long k) -> {
                    int cx = ChunkKeyCodec.unpackX(k);
                    int cz = ChunkKeyCodec.unpackZ(k);
                    return Math.max(Math.abs(cx - playerChunkX), Math.abs(cz - playerChunkZ)) > maxRadius + 4;
                });
            }
        }

        public void clear() {
            sentChunks.clear();
        }
    }
}
