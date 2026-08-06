package io.canvasmc.canvas.fakechunks.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelHandler.Sharable;

@Sharable
public final class CanvasBypassUnwrapHandler extends ChannelOutboundHandlerAdapter {

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof CanvasBypassPacket bypass) {
            super.write(ctx, bypass.payload(), promise);
            return;
        }
        super.write(ctx, msg, promise);
    }
}
