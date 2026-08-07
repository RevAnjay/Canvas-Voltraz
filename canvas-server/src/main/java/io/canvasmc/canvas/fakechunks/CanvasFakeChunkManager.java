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
        boolean globalEnabled = globalConfig != null && globalConfig.fakeChunks != null && globalConfig.fakeChunks.enabled;

        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        WorldConfig worldConfig = WorldConfig.forWorld(level);
        boolean worldEnabled = worldConfig != null && worldConfig.fakeChunks != null && worldConfig.fakeChunks.enabled;

        if (!globalEnabled) {
            removePlayer(player);
            return;
        }

        if (!worldEnabled) {
            removePlayer(player);
            return;
        }

        if (!player.canvas$hasFakeChunksPermission) {
            removePlayer(player);
            return;
        }

        io.canvasmc.canvas.fakechunks.netty.CanvasChannelInjector.inject(player);

        int maxDist = worldConfig.fakeChunks.maxViewDistance > 0 
            ? worldConfig.fakeChunks.maxViewDistance 
            : globalConfig.fakeChunks.maxViewDistance;

        try {
            org.bukkit.entity.Player bukkitPlayer = player.getBukkitEntity();
            if (bukkitPlayer != null) {
                int clientDist = bukkitPlayer.getClientViewDistance();
                if (clientDist > 0) {
                    maxDist = Math.min(maxDist, clientDist);
                }
            }
        } catch (Throwable ignored) {}

        if (player.connection != null && player.connection.connection != null && player.connection.connection.channel != null) {
            io.netty.channel.Channel channel = player.connection.connection.channel;
            channel.attr(io.canvasmc.canvas.fakechunks.netty.CanvasPacketHandler.PLAYER_UUID_KEY).set(player.getUUID());
            channel.attr(io.canvasmc.canvas.fakechunks.netty.CanvasPacketHandler.MAX_RADIUS_KEY).set(maxDist);
        }

        PlayerFakeChunkSession session = sessions.computeIfAbsent(player.getUUID(), uuid -> new PlayerFakeChunkSession());
        session.tick(player, level, maxDist, globalConfig.fakeChunks.maxSendingRatePerTick);
    }

    public PlayerFakeChunkSession getSession(UUID uuid) {
        return sessions.get(uuid);
    }

    public void resetSession(UUID uuid) {
        PlayerFakeChunkSession session = sessions.get(uuid);
        if (session != null) {
            session.clear();
        }
    }

    public void removePlayer(ServerPlayer player) {
        if (player != null) {
            io.canvasmc.canvas.fakechunks.netty.CanvasChannelInjector.uninject(player);
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
        private final LongSet pendingBuilds = new LongOpenHashSet();
        private final LongSet serverChunks = new LongOpenHashSet();
        private int lastSentRadius = -1;
        private long lastSentCenterKey = ChunkKeyCodec.pack(Integer.MIN_VALUE, Integer.MIN_VALUE);
        private int lastPlayerX;
        private int lastPlayerZ;

        public void onServerChunkAdd(int chunkX, int chunkZ) {
            long key = ChunkKeyCodec.pack(chunkX, chunkZ);
            serverChunks.add(key);
            sentChunks.remove(key);
        }

        public boolean onServerChunkRemove(int chunkX, int chunkZ, int maxRadius) {
            long key = ChunkKeyCodec.pack(chunkX, chunkZ);
            serverChunks.remove(key);
            if (sentChunks.contains(key)) {
                return true;
            }
            if (Math.max(Math.abs(chunkX - lastPlayerX), Math.abs(chunkZ - lastPlayerZ)) <= maxRadius) {
                return true;
            }
            return false;
        }

        public boolean isSent(long key) {
            return sentChunks.contains(key);
        }

        public void tick(ServerPlayer player, ServerLevel level, int maxRadius, int maxRate) {
            int playerChunkX = player.chunkPosition().x;
            int playerChunkZ = player.chunkPosition().z;
            this.lastPlayerX = playerChunkX;
            this.lastPlayerZ = playerChunkZ;
            int serverViewDist = level.serverLevelData.canvas$distanceConfig.viewDistanceOrDefault();

            long currentCenterKey = ChunkKeyCodec.pack(playerChunkX, playerChunkZ);

            if (lastSentRadius != maxRadius) {
                lastSentRadius = maxRadius;
                player.connection.send(new io.canvasmc.canvas.fakechunks.netty.CanvasBypassPacket(
                    new net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket(maxRadius)
                ));
            }

            if (lastSentCenterKey != currentCenterKey) {
                lastSentCenterKey = currentCenterKey;
                player.connection.send(new io.canvasmc.canvas.fakechunks.netty.CanvasBypassPacket(
                    new net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket(playerChunkX, playerChunkZ)
                ));
            }

            int sentCount = 0;
            long[] offsets = io.canvasmc.canvas.fakechunks.planner.ChunkPlannerService.radiusIterationList(maxRadius);

            for (long offset : offsets) {
                if (sentCount >= maxRate) {
                    break;
                }
                int relX = ChunkKeyCodec.unpackX(offset);
                int relZ = ChunkKeyCodec.unpackZ(offset);
                int dist = Math.max(Math.abs(relX), Math.abs(relZ));

                if (dist <= serverViewDist || dist > maxRadius) {
                    continue;
                }

                int targetX = playerChunkX + relX;
                int targetZ = playerChunkZ + relZ;
                long key = ChunkKeyCodec.pack(targetX, targetZ);

                if (sentChunks.contains(key) || pendingBuilds.contains(key) || serverChunks.contains(key)) {
                    continue;
                }

                ClientboundLevelChunkWithLightPacket cached = FakeChunkCache.get().getIfCached(level, targetX, targetZ);
                if (cached != null) {
                    player.connection.send(cached);
                    sentChunks.add(key);
                    sentCount++;
                } else {
                    pendingBuilds.add(key);
                    FakeChunkCache.get().getOrBuildAsync(level, targetX, targetZ).thenAccept(packet -> {
                        level.getServer().execute(() -> {
                            pendingBuilds.remove(key);
                            if (packet != null && !player.hasDisconnected() && player.level() == level) {
                                player.connection.send(packet);
                                sentChunks.add(key);
                            }
                        });
                    });
                    sentCount++;
                }
            }

            if (sentChunks.size() > 4096) {
                sentChunks.removeIf((long k) -> {
                    int cx = ChunkKeyCodec.unpackX(k);
                    int cz = ChunkKeyCodec.unpackZ(k);
                    boolean out = Math.max(Math.abs(cx - playerChunkX), Math.abs(cz - playerChunkZ)) > maxRadius + 4;
                    if (out) {
                        player.connection.send(new io.canvasmc.canvas.fakechunks.netty.CanvasBypassPacket(
                            new net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket(new net.minecraft.world.level.ChunkPos(cx, cz))
                        ));
                    }
                    return out;
                });
            }
        }

        public void clear() {
            sentChunks.clear();
            pendingBuilds.clear();
            serverChunks.clear();
        }
    }
}
