package io.canvasmc.canvas.fakechunks.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

public final class CanvasPacketHandler extends ChannelOutboundHandlerAdapter {

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
        super.write(ctx, msg, promise);
    }
}
