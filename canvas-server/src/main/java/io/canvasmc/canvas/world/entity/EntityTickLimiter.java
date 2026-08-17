package io.canvasmc.canvas.world.entity;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.world.entity.Entity;

/**
 * Per-region entity tick limiter (tick-only variant of Kaiiju's throttle).
 *
 * <p>When the count of a particular entity type in a tick region exceeds its limit, entities of
 * that type are ticked on a staggered rotation instead of every entity every tick. The throttle is
 * purely a tick-skip: excess entities are NEVER removed, only ticked less frequently. Players and
 * removed entities are always exempt.</p>
 *
 * <p>Ports Kaiiju's {@code dev.kaiijumc.kaiiju.KaiijuEntityThrottler} but drops the removal path
 * (which could otherwise be weaponised to delete other players' entities).</p>
 */
public final class EntityTickLimiter {
    private static final class TickInfo {
        int currentTick;
        int continueFrom;
        int toTick;
    }

    private final Object2ObjectOpenHashMap<Class<?>, TickInfo> typeTickInfoMap = new Object2ObjectOpenHashMap<>();
    private final int defaultLimit;

    public EntityTickLimiter(int defaultLimit) {
        this.defaultLimit = Math.max(0, defaultLimit);
    }

    /** Must be called once per region tick, before iterating ticking entities. */
    public void tickLimiterStart() {
        for (final TickInfo tickInfo : typeTickInfoMap.values()) {
            tickInfo.currentTick = 0;
        }
    }

    /** Returns true when the entity should be skipped this tick (ticked less frequently). */
    public boolean tickLimiterShouldSkip(Entity entity) {
        if (entity.isRemoved() || entity instanceof net.minecraft.server.level.ServerPlayer) {
            return false;
        }
        final int limit = this.defaultLimit;
        if (limit <= 0) {
            return false;
        }

        final TickInfo tickInfo = this.typeTickInfoMap.computeIfAbsent(entity.getClass(), key -> {
            final TickInfo newInfo = new TickInfo();
            newInfo.toTick = limit;
            return newInfo;
        });

        tickInfo.currentTick++;

        // Within the allowed window: tick normally.
        if (tickInfo.currentTick < tickInfo.continueFrom) {
            return true; // staggered wait window
        }
        if (tickInfo.currentTick - tickInfo.continueFrom < tickInfo.toTick) {
            return false; // within this batch window, tick it
        }
        // Past the batch window for this round: skip until continueFrom catches up.
        return true;
    }

    /** Must be called once per region tick, after iterating ticking entities. */
    public void tickLimiterFinish() {
        for (final TickInfo tickInfo : typeTickInfoMap.values()) {
            int additional = 0;
            int nextContinueFrom = tickInfo.continueFrom + tickInfo.toTick;
            if (nextContinueFrom >= tickInfo.currentTick) {
                additional = this.defaultLimit - (tickInfo.currentTick - tickInfo.continueFrom);
                nextContinueFrom = 0;
            }
            tickInfo.continueFrom = nextContinueFrom;
            tickInfo.toTick = this.defaultLimit + additional;
        }
    }
}
