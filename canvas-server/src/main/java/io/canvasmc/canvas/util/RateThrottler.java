package io.canvasmc.canvas.util;

import org.jspecify.annotations.NonNull;

/**
 * A per-tick sliding-window rate counter for a single tick region.
 *
 * <p>Each region tick must call {@link #begin()} before processing entities and {@link #done()}
 * afterwards. While ticking, {@link #increase()} records portal teleportations and
 * {@link #isOutOfRate(int)} reports whether the configured cap has been hit. Region merge/split
 * operations propagate the counters so the budget follows entity ownership.</p>
 *
 * <p>Ports Luminol's {@code me.earthme.luminol.utils.RateThrottler}.</p>
 */
public final class RateThrottler {
    private static final byte STATE_NOT_BEGIN = 0;
    private static final byte STATE_RECORDING = 1;
    private static final byte STATE_DESTROYED = 2;

    private byte status;
    private int recordedThisTick = 0;

    private int totallyTicked = 0;
    private int totalCount = 0;

    private void checkDestroyed() {
        if (this.status == STATE_DESTROYED) {
            throw new IllegalStateException("Already destroyed!");
        }
    }

    public void increase() {
        this.recordedThisTick++;
    }

    public void mergeWith(@NonNull RateThrottler other) {
        this.checkDestroyed();
        other.checkDestroyed();

        this.totallyTicked += other.totallyTicked;
        this.totalCount += other.totalCount;
    }

    public void splitInto(@NonNull RateThrottler other) {
        this.checkDestroyed();
        other.checkDestroyed();

        other.totalCount = this.totalCount;
        other.totallyTicked = this.totallyTicked;
    }

    public double getAvgCount() {
        return (double) this.totalCount / Math.min(this.totallyTicked, 1);
    }

    public int getCountThisTick() {
        return this.recordedThisTick;
    }

    public boolean isOutOfRate(int expected) {
        return this.recordedThisTick >= expected;
    }

    public void destroy() {
        if (this.status == STATE_DESTROYED) {
            throw new IllegalStateException("Already destroyed!");
        }

        this.status = STATE_DESTROYED;
    }

    public void begin() {
        this.checkDestroyed();

        if (this.status == STATE_RECORDING) {
            throw new IllegalStateException("Attempt to begin a already recording throttler!");
        }

        this.status = STATE_RECORDING;
    }

    public void done() {
        this.checkDestroyed();

        if (this.status == STATE_NOT_BEGIN) {
            throw new IllegalStateException("Attempt to done a already done or new throttler!");
        }

        this.status = STATE_NOT_BEGIN;

        this.totallyTicked++;
        this.totalCount += this.recordedThisTick;

        this.recordedThisTick = 0;
    }
}
