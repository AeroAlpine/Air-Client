package com.coflnet.pathing;

import baritone.api.utils.BetterBlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * How calm the bots move the camera (Pathfinder tab, "Calm movement" 0-100 %). 0 % is the old
 * snappy behaviour; higher values look further ahead on the path, turn slower and softer, ignore
 * tiny corrections and glance around less. The path recorder logs of 0.2.8 showed the camera
 * reversing its turn every ~0.6 s: it aimed at the next path block, and on diagonal paths that
 * block sits left, right, left ...
 */
public final class MovementStyle {
    private MovementStyle() {
    }

    /** 0 = snappy, 1 = calm. */
    public static double calm() {
        return Math.clamp(PathfinderConfig.get().movementCalmness / 100.0, 0.0, 1.0);
    }

    /** How far ahead on the path the camera looks (blocks). */
    public static double lookAheadBlocks() {
        return 1.5 + 4.0 * calm();
    }

    /** Share of the camera's turn-speed setting that is used. */
    public static double turnSpeedFactor() {
        return 1.0 - 0.55 * calm();
    }

    /** Easing rate (1/s): how quickly the camera closes in on where it wants to look. */
    public static double easeRate() {
        return 10.0 - 6.5 * calm();
    }

    /** Smaller differences than this (degrees) do not start a turn: no micro corrections. */
    public static double deadzoneDegrees() {
        return 7.0 * calm();
    }

    /** Per tick blend towards a new look point (1 = jump to it at once). */
    public static double lookBlend() {
        return 1.0 - 0.8 * calm();
    }

    /** Idle glance: largest turn left / right (degrees) and the pause between glances (ms). */
    public static double idleGlanceDegrees() {
        return 70.0 - 45.0 * calm();
    }

    public static long idleGlanceMinMs() {
        return Math.round(650 + 1100 * calm());
    }

    public static long idleGlanceMaxMs() {
        return Math.round(1300 + 1900 * calm());
    }

    /**
     * Point {@code distance} blocks along the path (through the block centres from index
     * {@code from} on), starting at {@code start}. Null without path points.
     */
    public static Vec3 pointAlongPath(List<BetterBlockPos> positions, int from, Vec3 start, double distance) {
        if (positions == null || from >= positions.size()) {
            return null;
        }
        Vec3 previous = new Vec3(start.x, 0, start.z);
        double left = distance;
        Vec3 last = null;
        for (int i = Math.max(0, from); i < positions.size(); i++) {
            BetterBlockPos pos = positions.get(i);
            Vec3 next = new Vec3(pos.getX() + 0.5, 0, pos.getZ() + 0.5);
            double length = previous.distanceTo(next);
            last = new Vec3(next.x, pos.getY(), next.z);
            if (length >= left && length > 1.0E-6) {
                Vec3 flat = previous.lerp(next, left / length);
                return new Vec3(flat.x, pos.getY(), flat.z);
            }
            left -= length;
            previous = next;
        }
        return last;
    }
}
