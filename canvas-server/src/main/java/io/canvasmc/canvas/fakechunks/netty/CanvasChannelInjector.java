package io.canvasmc.canvas.fakechunks.netty;

import io.netty.channel.Channel;
import net.minecraft.server.level.ServerPlayer;

public final class CanvasChannelInjector {

    public static final String HANDLER_NAME = "canvas_packet_handler";
    public static final String UNWRAP_HANDLER_NAME = "canvas_bypass_unwrap";

    private CanvasChannelInjector() {}

    public static void inject(ServerPlayer player) {
        if (player.connection == null || player.connection.connection == null) {
            return;
        }

        Channel channel = player.connection.connection.channel;
        if (channel == null || !channel.isActive()) {
            return;
        }

        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(HANDLER_NAME) == null && channel.pipeline().get("packet_handler") != null) {
                channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new CanvasPacketHandler());
            }
            if (channel.pipeline().get(UNWRAP_HANDLER_NAME) == null && channel.pipeline().get("encoder") != null) {
                channel.pipeline().addBefore("encoder", UNWRAP_HANDLER_NAME, new CanvasBypassUnwrapHandler());
            }
        });
    }

    public static void uninject(ServerPlayer player) {
        if (player.connection == null || player.connection.connection == null) {
            return;
        }

        Channel channel = player.connection.connection.channel;
        if (channel == null) {
            return;
        }

        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }
            if (channel.pipeline().get(UNWRAP_HANDLER_NAME) != null) {
                channel.pipeline().remove(UNWRAP_HANDLER_NAME);
            }
        });
    }
}
