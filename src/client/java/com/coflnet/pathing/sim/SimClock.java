package com.coflnet.pathing.sim;

/**
 * The clock the bots use instead of {@link System#currentTimeMillis()} / {@link System#nanoTime()}.
 * Normally it is the wall clock. In the simulation's turbo mode the game runs many ticks per real
 * millisecond; then this clock only moves with the simulated ticks (50 ms each), so click speeds,
 * timeouts and camera turns behave as in a real 20 tick/s game.
 */
public final class SimClock {
    private static final long BASE_MILLIS = System.currentTimeMillis();
    private static final long BASE_NANOS = System.nanoTime();
    private static long simNanos = BASE_NANOS;
    private static long lastWall = BASE_NANOS;
    private static boolean simulated;

    private SimClock() {
    }

    public static synchronized long nanos() {
        long wall = System.nanoTime();
        if (!simulated) simNanos += wall - lastWall;
        lastWall = wall;
        return simNanos;
    }

    public static long millis() {
        return BASE_MILLIS + (nanos() - BASE_NANOS) / 1_000_000L;
    }

    /** On: time only moves through {@link #advance}; off: wall clock again (no jump either way). */
    public static synchronized void setSimulated(boolean on) {
        nanos();
        simulated = on;
    }

    public static synchronized boolean simulated() {
        return simulated;
    }

    /** Simulated time passes (turbo: 50 ms per game tick). */
    public static synchronized void advance(long nanos) {
        nanos();
        if (simulated) simNanos += nanos;
    }
}
