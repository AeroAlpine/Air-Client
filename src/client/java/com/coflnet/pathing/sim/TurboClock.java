package com.coflnet.pathing.sim;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Turbo mode for the simulation (env AIRCLIENT_SIM=1, singleplayer only): the real game, real
 * physics, real Baritone - just as many ticks per second as the PC manages.
 *
 * <ul>
 *   <li>Client and integrated server tick in lockstep (client tick n, server tick n, ...), so
 *       teleports, block changes and mobs stay in step with the player.</li>
 *   <li>Per client tick the queued packets are handled (vanilla: once per frame) and the per-frame
 *       camera code runs 3 times (a 60 FPS game) with {@link SimClock} moving 50 ms.</li>
 *   <li>While a path is being calculated the game runs in real time (20 ticks/s): planning costs
 *       the same game time as in the real game, instead of 100x more.</li>
 * </ul>
 */
public final class TurboClock {
    public static final boolean ENABLED = System.getenv("AIRCLIENT_SIM") != null
            && !"0".equals(System.getenv("AIRCLIENT_SIM"));
    /** AIRCLIENT_SIM_TURBO=0: simulation worker in real time (to check turbo against the real game). */
    public static final boolean TURBO = !"0".equals(System.getenv("AIRCLIENT_SIM_TURBO"));
    /** Wall time per frame spent on ticks before a frame is drawn. */
    private static final long FRAME_BUDGET_NANOS = 200_000_000L;
    private static final long SERVER_WAIT_NANOS = 200_000_000L;
    private static final long TICK_NANOS = 50_000_000L;
    private static final int SUB_FRAMES = 3;
    private static final float TURBO_RATE = 10_000f;

    private static final AtomicLong CLIENT = new AtomicLong();
    private static final AtomicLong SERVER = new AtomicLong();
    private static volatile boolean active;
    /** This frame: 0 vanilla, 1 turbo ticks, 2 turbo frame over (no more ticks). */
    private static int frameMode;
    private static long frameStart;
    private static long frameTicks;
    private static long totalTurboTicks;
    /** Wall time spent in turbo frames / in real-time frames while a run was wanted (for the runner's statistics). */
    private static long turboNanos;
    private static long realNanos;
    private static long lastFrameAt;
    private static boolean lastFrameTurbo;
    private static long clientWorkNanos;
    private static long serverWorkNanos;
    private static long serverTickStart;
    private static long clientTickStart;
    /** Set by the sim worker: turbo only while a run is going on. */
    private static volatile boolean wanted;

    private TurboClock() {
    }

    public static boolean active() {
        return active;
    }

    public static void want(boolean on) {
        wanted = on;
    }

    public static long turboTicks() {
        return totalTurboTicks;
    }

    /** "turbo ms / real-time ms" while runs were wanted. */
    public static String timeSplit() {
        long t = Math.max(1, totalTurboTicks);
        return (turboNanos / 1_000_000L) + " " + (realNanos / 1_000_000L)
                + " client " + (clientWorkNanos / t / 1000L) + "us server " + (serverWorkNanos / t / 1000L) + "us";
    }

    private static boolean shouldRun(Minecraft mc) {
        return ENABLED && TURBO && wanted && mc.level != null && mc.player != null && mc.getSingleplayerServer() != null
                && !mc.isPaused() && !planning();
    }

    /** A path calculation is running: real time. */
    private static boolean planning() {
        if (com.coflnet.pathing.PathEngine.planning()) return true;
        try {
            baritone.api.IBaritone b = baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
            return b != null && b.getPathingBehavior().getInProgress().isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void setActive(Minecraft mc, boolean on) {
        if (on == active) return;
        active = on;
        SimClock.setSimulated(on);
        CLIENT.set(SERVER.get());
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            float rate = on ? TURBO_RATE : 20f;
            server.execute(() -> server.tickRateManager().setTickRate(rate));
        }
    }

    /** Minecraft.runTick HEAD. */
    public static void beginFrame() {
        if (!ENABLED) return;
        long now = System.nanoTime();
        if (lastFrameAt != 0 && wanted) {
            if (lastFrameTurbo) turboNanos += now - lastFrameAt;
            else realNanos += now - lastFrameAt;
        }
        lastFrameAt = now;
        Minecraft mc = Minecraft.getInstance();
        setActive(mc, shouldRun(mc));
        frameMode = active ? 1 : 0;
        lastFrameTurbo = active;
        frameStart = System.nanoTime();
        frameTicks = 0;
    }

    /** The loop condition of Minecraft.runTick (vanilla: at most 10 ticks per frame). */
    public static int ticksThisFrame(int a, int b) {
        return switch (frameMode) {
            case 1 -> Integer.MAX_VALUE;
            case 2 -> 0;
            default -> Math.min(a, b);
        };
    }

    /** Before each client tick of the loop: false = skip it. */
    public static boolean beforeClientTick(Minecraft mc) {
        if (frameMode == 0) return true;
        if (frameMode == 2) return false;
        if (frameTicks > 0 && (System.nanoTime() - frameStart > FRAME_BUDGET_NANOS || !shouldRun(mc))) {
            if (!shouldRun(mc)) setActive(mc, false);
            frameMode = 2; // rest of this frame: nothing, draw it
            return false;
        }
        // wait for the server to finish the previous tick
        long waitStart = System.nanoTime();
        while (CLIENT.get() > SERVER.get()) {
            if (System.nanoTime() - frameStart > FRAME_BUDGET_NANOS) {
                frameMode = 2;
                return false;
            }
            pause(waitStart);
        }
        clientTickStart = System.nanoTime();
        // what vanilla does once per frame, now once per tick
        mc.packetProcessor().processQueuedPackets();
        ((com.coflnet.mixin.BlockableEventLoopInvoker) (Object) mc).airclient$runAllTasks();
        for (int i = 0; i < SUB_FRAMES; i++) {
            SimClock.advance(TICK_NANOS / SUB_FRAMES);
            pingplus.voicechat.client.FrameHooks.run(mc);
        }
        return true;
    }

    public static void afterClientTick() {
        if (frameMode != 1) return;
        frameTicks++;
        totalTurboTicks++;
        clientWorkNanos += System.nanoTime() - clientTickStart;
        CLIENT.incrementAndGet();
    }

    /** FramerateLimitTracker: no frame limit (minimised / unfocused windows sleep up to 100 ms per frame). */
    public static boolean unlimitedFrames() {
        return active;
    }

    /** Integrated server thread, before a tick: wait until the client did its tick. */
    public static void beforeServerTick(MinecraftServer server) {
        if (!active) return;
        long start = System.nanoTime();
        while (active && SERVER.get() >= CLIENT.get()) {
            ((com.coflnet.mixin.BlockableEventLoopInvoker) (Object) server).airclient$runAllTasks();
            if (System.nanoTime() - start > SERVER_WAIT_NANOS) break; // client hangs (frame, GC): go on
            pause(start);
        }
        serverTickStart = System.nanoTime();
    }

    /**
     * Waiting for the other thread: spin first (a tick hand-over takes microseconds; on Windows
     * even the shortest sleep is ~1 ms, which made turbo only 4x real time), sleep when it is long.
     */
    private static void pause(long since) {
        long waited = System.nanoTime() - since;
        if (waited < 3_000_000L) Thread.onSpinWait();
        else if (waited < 20_000_000L) Thread.yield();
        else LockSupport.parkNanos(200_000L);
    }

    public static void afterServerTick() {
        if (active && serverTickStart != 0) serverWorkNanos += System.nanoTime() - serverTickStart;
        SERVER.incrementAndGet();
    }
}
