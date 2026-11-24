package com.coflnet.pathing;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft's player movement, tick by tick, for jumps and drops (Air Client path engine).
 * <ul>
 *   <li>Ground tick: velocity + input acceleration, move, then x0.546 (block friction 0.6 x 0.91).
 *       The input acceleration is read from how fast we really go (it includes SkyBlock speed):
 *       steady speed m per tick means acceleration 0.454 * m.</li>
 *   <li>Jump tick: vertical 0.42, sprinting adds 0.2 forward, still ground friction that tick.</li>
 *   <li>Air tick: + 0.02 (sprinting 0.026) in the input direction, move, x0.91; vertical
 *       (v - 0.08) x 0.98.</li>
 * </ul>
 * Keys pressed now act in the next tick (the engine runs at the end of a client tick), so a
 * simulation always starts with the next tick.
 */
final class AirMath {
    static final double GROUND_FRICTION = 0.546;

    private static double jumpPower() {
        try {
            return Math.max(0.42, PathfinderTuning.stats().jumpVelocity());
        } catch (RuntimeException e) {
            return 0.42;
        }
    }

    private static double gravity() {
        try {
            double g = PathfinderTuning.stats().gravity();
            return g > 0 ? g : 0.08;
        } catch (RuntimeException e) {
            return 0.08;
        }
    }
    static final double AIR_FRICTION = 0.91;
    private static final double[][] CORNERS = {{0, 0}, {0.299, 0.299}, {0.299, -0.299}, {-0.299, 0.299}, {-0.299, -0.299}};

    private AirMath() {
    }

    /** Where we land: position, or null (no landing within 3 s / walked off before the jump). */
    record Landing(Vec3 pos, int ticks) {
    }

    /**
     * Jump after {@code groundTicks} more ground ticks, W held towards {@code yaw} the whole time.
     *
     * @param groundAccel input acceleration on the ground (0.454 x the real per-tick speed)
     */
    static Landing jump(Level level, Vec3 pos, Vec3 vel, float facingYaw, float inputYaw, boolean sprint,
                        double groundAccel, int groundTicks) {
        return jump(level, pos, vel, facingYaw, inputYaw, sprint, groundAccel, groundTicks, false);
    }

    /**
     * Same, {@code holdAir}: the keys stay down in the air too; otherwise they are let go right
     * after the take-off (the clean jump: up, keys off, down on the landing point).
     */
    static Landing jump(Level level, Vec3 pos, Vec3 vel, float facingYaw, float inputYaw, boolean sprint,
                        double groundAccel, int groundTicks, boolean holdAir) {
        strictHit = false;
        return jump0(level, pos, vel, facingYaw, inputYaw, sprint, groundAccel, groundTicks, holdAir);
    }

    /** True when the last simulation touched a block on the way (wall, head against a ceiling). */
    static boolean touched() {
        return strictHit;
    }

    private static boolean strictHit;

    private static Landing jump0(Level level, Vec3 pos, Vec3 vel, float facingYaw, float inputYaw, boolean sprint,
                                 double groundAccel, int groundTicks, boolean holdAir) {
        double r = Math.toRadians(inputYaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        // the sprint-jump push goes where the camera faces, not where the keys move us
        double rf = Math.toRadians(facingYaw);
        double bx = -Math.sin(rf), bz = Math.cos(rf);
        double[] s = {pos.x, pos.y, pos.z, vel.x, 0, vel.z};
        for (int g = 0; g < groundTicks; g++) {
            s[3] += fx * groundAccel;
            s[5] += fz * groundAccel;
            moveFlat(level, s);
            s[3] *= GROUND_FRICTION;
            s[5] *= GROUND_FRICTION;
            if (Double.isInfinite(floorUnder(level, s[0], s[2], s[1] + 0.05, s[1] - 0.3))) {
                return null; // walked off the edge before jumping
            }
        }
        // the jump tick
        s[4] = jumpPower();
        if (sprint) {
            s[3] += bx * 0.2;
            s[5] += bz * 0.2;
        }
        s[3] += fx * groundAccel;
        s[5] += fz * groundAccel;
        Vec3 landed = step(level, s);
        s[3] *= GROUND_FRICTION;
        s[5] *= GROUND_FRICTION;
        s[4] = (s[4] - gravity()) * 0.98;
        if (landed != null) return new Landing(landed, 1);
        return fly(level, s, inputYaw, holdAir ? 1 : 0, 0, sprint, 1);
    }

    /** From the current air state with the keys held (forward 1/0, strafe +1 left / -1 right). */
    static Landing fly(Level level, Vec3 pos, Vec3 vel, float yaw, int forward, int strafe, boolean sprint) {
        double[] s = {pos.x, pos.y, pos.z, vel.x, vel.y, vel.z};
        return fly(level, s, yaw, forward, strafe, sprint, 0);
    }

    private static Landing fly(Level level, double[] s, float yaw, int forward, int strafe, boolean sprint, int t0) {
        double r = Math.toRadians(yaw);
        double sin = Math.sin(r), cos = Math.cos(r);
        double ix = strafe, iz = forward;
        double len = Math.hypot(ix, iz);
        if (len > 1) {
            ix /= len;
            iz /= len;
        }
        double acc = sprint ? 0.026 : 0.02;
        double ax = (ix * cos - iz * sin) * acc, az = (iz * cos + ix * sin) * acc;
        for (int t = t0; t < 60; t++) {
            s[3] += ax;
            s[5] += az;
            Vec3 landed = step(level, s);
            if (landed != null) return new Landing(landed, t + 1);
            s[3] *= AIR_FRICTION;
            s[5] *= AIR_FRICTION;
            s[4] = (s[4] - gravity()) * 0.98;
        }
        return null;
    }

    /** One move with the velocity in s; the landing point when we touched the floor going down. */
    private static Vec3 step(Level level, double[] s) {
        moveFlat(level, s);
        double ny = s[1] + s[4];
        if (s[4] > 0) {
            if (bodyBlocked(level, s[0], ny, s[2])) {
                strictHit = true;
                s[4] = 0; // head against the ceiling
            } else {
                s[1] = ny;
            }
            return null;
        }
        double floor = floorUnder(level, s[0], s[2], s[1] + 0.01, ny);
        if (!Double.isInfinite(floor)) {
            s[1] = floor;
            return new Vec3(s[0], floor, s[2]);
        }
        s[1] = ny;
        return null;
    }

    private static void moveFlat(Level level, double[] s) {
        double nx = s[0] + s[3];
        if (bodyBlocked(level, nx, s[1], s[2])) {
            if (Math.abs(s[3]) > 1.0E-4) strictHit = true;
            s[3] = 0;
        } else {
            s[0] = nx;
        }
        double nz = s[2] + s[5];
        if (bodyBlocked(level, s[0], s[1], nz)) {
            if (Math.abs(s[5]) > 1.0E-4) strictHit = true;
            s[5] = 0;
        } else {
            s[2] = nz;
        }
    }

    /** The player's box (0.6 wide, 1.8 high) at feet (x, y, z) is inside a block. */
    static boolean bodyBlocked(Level level, double x, double y, double z) {
        for (double[] c : CORNERS) {
            for (double h : new double[]{0.02, 0.6, 1.2, 1.78}) {
                if (PathLine.solidAt(level, x + c[0], y + h, z + c[1])) return true;
            }
        }
        return false;
    }

    /**
     * The highest floor under the player's box between heights {@code top} and {@code bottom};
     * -infinity when there is none.
     */
    static double floorUnder(Level level, double x, double z, double top, double bottom) {
        double best = Double.NEGATIVE_INFINITY;
        for (double[] c : CORNERS) {
            double cx = x + c[0], cz = z + c[1];
            for (int by = (int) Math.floor(top); by >= (int) Math.floor(bottom) - 1; by--) {
                double t = PathLine.columnTop(level, new net.minecraft.core.BlockPos((int) Math.floor(cx), by,
                        (int) Math.floor(cz)), cx, cz);
                if (Double.isNaN(t)) continue;
                if (t <= top + 1.0E-6 && t >= bottom - 1.0E-6) {
                    best = Math.max(best, t);
                }
                break;
            }
        }
        return best;
    }
}
