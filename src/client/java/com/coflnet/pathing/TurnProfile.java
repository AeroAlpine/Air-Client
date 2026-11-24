package com.coflnet.pathing;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pre-computed turn profile of a prepared path: how far and how fast the camera has to turn
 * at every corner, before the bot starts walking. The player configures the maximum turn
 * speed (Pathfinder tab, 10-60 deg/tick); the path is then rounded/slowed so it can really be
 * followed at that rate. There is no instant snap - just a fast, continuous turn.
 */
public final class TurnProfile {
    /** Hard ceiling of the configurable turn speed: 60 deg/tick = 1200 deg/s. */
    public static final double HUMAN_MAX_DEG_PER_TICK = 60.0;
    public static final double HUMAN_MAX_DEG_PER_SEC = HUMAN_MAX_DEG_PER_TICK * 20.0;
    /** Distance on the line the camera looks ahead when nothing is sharp. */
    public static final double BASE_LOOKAHEAD = 3.2;
    /** Further than this the camera would look past the next warp/jump corner. */
    public static final double MAX_LOOKAHEAD = 8.0;
    /** Feet speed used for the profile when the player is not moving yet (blocks per tick). */
    public static final double WALK_SPEED = 0.215;
    public static final double SPRINT_SPEED = 0.30;

    public record Corner(int index, Vec3 at, double turnDegrees, double inLength, double outLength,
                         double neededWalkDegPerTick, double neededSprintDegPerTick,
                         double lookaheadWalk, double lookaheadSprint,
                         boolean earlyTurn, boolean tooSharpWalk, boolean tooSharpSprint) {
    }

    private final Corner[] corners;
    private final double limitDegPerTick;
    private final int tooSharp;
    private final int early;
    private final Corner worst;

    private TurnProfile(Corner[] corners, double limitDegPerTick) {
        this.corners = corners;
        this.limitDegPerTick = limitDegPerTick;
        Corner w = null;
        int sharp = 0;
        int preTurn = 0;
        for (Corner c : corners) {
            if (c == null) {
                continue;
            }
            if (c.tooSharpWalk()) {
                sharp++;
            }
            if (c.earlyTurn()) {
                preTurn++;
            }
            if (w == null || c.neededWalkDegPerTick() > w.neededWalkDegPerTick()) {
                w = c;
            }
        }
        this.tooSharp = sharp;
        this.early = preTurn;
        this.worst = w;
    }

    /**
     * Builds the profile once per planned path.
     *
     * @param points     the prepared line
     * @param plainWalk  true where the point is reached by walking (no jump, no warp)
     * @param limit      the camera's real turn rate in degrees per tick
     */
    public static TurnProfile build(List<Vec3> points, boolean[] plainWalk, double limit) {
        Corner[] corners = new Corner[points.size()];
        if (points.size() < 3) {
            return new TurnProfile(corners, limit);
        }
        double walkSpeed = walkSpeed();
        double sprintSpeed = sprintSpeed();
        for (int i = 1; i < points.size() - 1; i++) {
            if (!plainWalk[i - 1] || !plainWalk[i] || !plainWalk[i + 1]) {
                continue;
            }
            Vec3 a = points.get(i - 1), b = points.get(i), c = points.get(i + 1);
            double inX = b.x - a.x, inZ = b.z - a.z;
            double outX = c.x - b.x, outZ = c.z - b.z;
            double inLength = Math.hypot(inX, inZ);
            double outLength = Math.hypot(outX, outZ);
            if (inLength < 0.05 || outLength < 0.05) {
                continue;
            }
            double inYaw = Math.toDegrees(Math.atan2(inZ, inX));
            double outYaw = Math.toDegrees(Math.atan2(outZ, outX));
            double turn = Math.abs(Mth.wrapDegrees(outYaw - inYaw));
            if (turn < 2.5) {
                continue;
            }
            double walkRate = turn * walkSpeed / Math.max(0.05, BASE_LOOKAHEAD);
            double sprintRate = turn * sprintSpeed / Math.max(0.05, BASE_LOOKAHEAD);
            double walkLook = turn * walkSpeed / Math.max(0.5, limit);
            double sprintLook = turn * sprintSpeed / Math.max(0.5, limit);
            corners[i] = new Corner(i, b, turn, inLength, outLength, walkRate, sprintRate,
                    walkLook, sprintLook,
                    walkLook > BASE_LOOKAHEAD + 0.1,
                    walkLook > MAX_LOOKAHEAD,
                    sprintRate > limit * 0.95 || sprintLook > MAX_LOOKAHEAD);
        }
        return new TurnProfile(corners, limit);
    }

    public double limitDegPerTick() {
        return limitDegPerTick;
    }

    public double limitDegPerSecond() {
        return limitDegPerTick * 20.0;
    }

    public int tooSharpCount() {
        return tooSharp;
    }

    public Corner cornerAt(int index) {
        return index >= 0 && index < corners.length ? corners[index] : null;
    }

    /** Look-ahead distance for the point at {@code index}, so the corner can be pre-turned. */
    public double lookahead(int index, double speed, double base) {
        Corner corner = null;
        for (int i = index; i <= index + 2 && i < corners.length; i++) {
            if (corners[i] != null) {
                corner = corners[i];
                break;
            }
        }
        if (corner == null) {
            return base;
        }
        double need = corner.turnDegrees() * Math.max(0.12, speed) / Math.max(0.5, limitDegPerTick);
        return Math.clamp(Math.max(base, need + 0.6), base, MAX_LOOKAHEAD);
    }

    /**
     * Sprint is only clean when every corner within the next {@code distance} blocks can still
     * be pre-turned from the base look-ahead at sprint speed.
     */
    public boolean sprintAllowed(int from, double distance) {
        int until = Math.min(corners.length - 1, Math.max(from, 1) + 12);
        for (int i = Math.max(1, from - 1); i <= until; i++) {
            Corner c = corners[i];
            if (c != null && c.tooSharpSprint()) {
                return false;
            }
        }
        return true;
    }

    /** Short log line written right after planning. */
    public String summary() {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT,
                "turn plan: max %.1f deg/tick (limit %.1f = %.0f deg/s), %d early pre-turn, %d too sharp",
                worst == null ? 0.0 : worst.neededWalkDegPerTick(), limitDegPerTick,
                limitDegPerSecond(), early, tooSharp));
        if (worst != null) {
            out.append(String.format(Locale.ROOT, " - worst %.0f deg at %s (%.1f b look-ahead)",
                    worst.turnDegrees(), block(worst.at()), worst.lookaheadWalk()));
        }
        return out.toString();
    }

    /** Up to {@code max} corners that need an early pre-turn or cannot be walked smoothly. */
    public List<String> sharpCorners(int max) {
        List<String> out = new ArrayList<>();
        for (Corner c : corners) {
            if (c == null || !c.earlyTurn()) {
                continue;
            }
            out.add(String.format(Locale.ROOT,
                    "%s: %.0f deg turn needs %.1f deg/tick, start %.1f b early%s",
                    block(c.at()), c.turnDegrees(), c.neededWalkDegPerTick(), c.lookaheadWalk(),
                    c.tooSharpWalk() ? " (too sharp: slow/stop)" : ""));
            if (out.size() >= max) {
                break;
            }
        }
        return out;
    }

    private static String block(Vec3 p) {
        return String.format(Locale.ROOT, "%d,%d,%d",
                Mth.floor(p.x), Mth.floor(p.y), Mth.floor(p.z));
    }

    /**
     * Walking speed from Minecraft physics: acceleration = MOVEMENT_SPEED, ground drag is
     * 0.6 * 0.91, so the terminal speed is speed / (1 - 0.546). Uses the custom SkyBlock
     * speed attribute, not a constant.
     */
    public static double walkSpeed() {
        try {
            double speed = PathfinderTuning.stats().speed();
            return Math.clamp(speed / Math.max(0.2, 1.0 - MinecraftPhysics.GROUND_DRAG), 0.20, 0.95);
        } catch (Throwable ignored) {
            return WALK_SPEED;
        }
    }

    /** Sprint adds +30 % MOVEMENT_SPEED (AttributeModifier ADD_MULTIPLIED_TOTAL). */
    public static double sprintSpeed() {
        try {
            return Math.clamp(walkSpeed() * 1.3, 0.25, 1.20);
        } catch (Throwable ignored) {
            return SPRINT_SPEED;
        }
    }

    /** The configured turn speed (Pathfinder tab), in degrees per tick. One authority for all. */
    public static double cameraLimitDegPerTick() {
        return Math.clamp(PathfinderConfig.get().maxTurnPerTick, 10.0, HUMAN_MAX_DEG_PER_TICK);
    }

    /** The same limit for the warp aiming (AOTV), degrees per second. */
    public static double warpLimitDegPerSecond() {
        // its own setting (the slider), not capped by the walking camera
        return Math.clamp(PathfinderConfig.get().warpRotationSpeed, 120.0, HUMAN_MAX_DEG_PER_SEC);
    }
}
