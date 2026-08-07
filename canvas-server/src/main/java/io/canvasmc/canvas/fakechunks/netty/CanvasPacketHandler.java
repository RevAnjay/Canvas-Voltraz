package io.canvasmc.canvas.fakechunks.netty;

import io.canvasmc.canvas.fakechunks.CanvasFakeChunkManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;

public final class CanvasPacketHandler extends ChannelOutboundHandlerAdapter {

    public static final AttributeKey<UUID> PLAYER_UUID_KEY = AttributeKey.valueOf("canvas_player_uuid");
    public static final AttributeKey<Integer> MAX_RADIUS_KEY = AttributeKey.valueOf("canvas_max_radius");

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof CanvasBypassPacket bypass) {
            Object payload = bypass.payload();
            if (payload == null) {
                promise.tryFailure(new NullPointerException("Bypass packet payload is null"));
                return;
            }
            try {
                super.write(ctx, payload, promise);
            } catch (Throwable throwable) {
                ReferenceCountUtil.release(payload);
                throw throwable;
            }
            return;
        }

        if (msg instanceof ClientboundSetChunkCacheRadiusPacket) {
            ReferenceCountUtil.release(msg);
            promise.setSuccess();
            return;
        }

        UUID uuid = ctx.channel().attr(PLAYER_UUID_KEY).get();
        if (uuid != null) {
            CanvasFakeChunkManager.PlayerFakeChunkSession session = CanvasFakeChunkManager.get().getSession(uuid);
            if (session != null) {
                if (msg instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
                    session.onServerChunkAdd(chunkPacket.getX(), chunkPacket.getZ());
                } else if (msg instanceof ClientboundForgetLevelChunkPacket forgetPacket) {
                    Integer maxRadius = ctx.channel().attr(MAX_RADIUS_KEY).get();
                    int r = maxRadius != null ? maxRadius : 32;
                    if (session.onServerChunkRemove(forgetPacket.pos().x, forgetPacket.pos().z, r)) {
                        ReferenceCountUtil.release(msg);
                        promise.setSuccess();
                        return;
                    }
                } else if (msg instanceof ClientboundRespawnPacket || msg instanceof ClientboundLoginPacket) {
                    session.clear();
                }
            }
        }

        super.write(ctx, msg, promise);
    }
}
