package com.coflnet.pathing;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The corridor around the smoothed walking line. For every sample it probes how far the player can
 * stand to the left and right (up to 2.4 blocks, i.e. the "what if we are two blocks off" check).
 * The follower uses this per-point width: wide stretches tolerate small corrections, a one-block
 * corridor demands an immediate correction; leaving the corridor restarts the route.
 */
public final class PathCorridor {
    private static final double SPACING = 0.60;
    private static final double MAX_HALF_WIDTH = 2.4;
    private static final double PROBE_STEP = 0.40;
    private static final double MIN_HALF_WIDTH = 0.22;

    public record Sample(Vec3 center, Vec3 tangent, double halfWidth, boolean narrow,
                         double preferredOffset, double distance) {
    }

    private final List<Sample> samples;
    private final double minHalfWidth;
    private final double avgHalfWidth;

    private PathCorridor(List<Sample> samples) {
        this.samples = samples;
        double min = Double.MAX_VALUE;
        double sum = 0;
        for (Sample s : samples) {
            min = Math.min(min, s.halfWidth());
            sum += s.halfWidth();
        }
        this.minHalfWidth = samples.isEmpty() ? 0 : min;
        this.avgHalfWidth = samples.isEmpty() ? 0 : sum / samples.size();
    }

    public boolean isEmpty() {
        return samples.size() < 2;
    }

    public double minHalfWidth() {
        return minHalfWidth;
    }

    public double averageHalfWidth() {
        return avgHalfWidth;
    }

    /** Nearest corridor sample (only forward-nearest around the last movement). */
    public Sample nearest(Vec3 feet) {
        Sample best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Sample sample : samples) {
            if (Math.abs(feet.y - sample.center().y) > 2.2) {
                continue; // another floor / level of a switchback
            }
            double distance = Math.hypot(feet.x - sample.center().x, feet.z - sample.center().z);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = sample;
            }
        }
        // A corridor is a fine correction around the current walking line. If the nearest
        // sample is far away (plan starts with a teleport, or we are on another loop), do not
        // use it for "left the corridor" - otherwise the route restarts 70 blocks away.
        return bestDistance <= 5.0 ? best : null;
    }

    /** Corridor half width of the nearest sample (2.4 when there is none nearby). */
    public double halfWidthNear(Vec3 p) {
        Sample sample = nearest(p);
        return sample == null ? 2.4 : sample.halfWidth();
    }

    /** True where the real geometry is a one-block passage (not only the jump taper). */
    public boolean narrowNear(Vec3 p) {
        Sample sample = nearest(p);
        return sample != null && sample.narrow();
    }

    /** Signed lateral offset from the middle line (positive = left of the walking direction). */
    public static double lateralOffset(Vec3 feet, Sample sample) {
        Vec3 n = new Vec3(-sample.tangent().z, 0, sample.tangent().x);
        return (feet.x - sample.center().x) * n.x + (feet.z - sample.center().z) * n.z;
    }

    public String summary() {
        return String.format(Locale.ROOT,
                "corridor: %d samples, width %.2f-%.2f b (half), avg %.2f",
                samples.size(), minHalfWidth, maxHalfWidth(), avgHalfWidth);
    }

    private double maxHalfWidth() {
        double max = 0;
        for (Sample s : samples) {
            max = Math.max(max, s.halfWidth());
        }
        return max;
    }

    /**
     * @param points the prepared line
     * @param plain  true where the point is reached by walking (jumps/warps break the corridor)
     */
    public static PathCorridor build(Level level, List<Vec3> points, boolean[] plain) {
        List<Sample> samples = new ArrayList<>();
        List<Double> jumpDistances = new ArrayList<>();
        double prefix = 0;
        for (int i = 1; i < points.size(); i++) {
            double segment = Math.hypot(points.get(i).x - points.get(i - 1).x,
                    points.get(i).z - points.get(i - 1).z);
            if (plain[i] != plain[i - 1]) {
                // both ends of the jump/warp: approach tapers before take-off, and after landing
                jumpDistances.add(prefix);
                jumpDistances.add(prefix + segment);
            }
            prefix += segment;
        }
        double along = 0;
        for (int i = 1; i < points.size(); i++) {
            if (!plain[i - 1] || !plain[i]) {
                continue; // no corridor through a jump / warp
            }
            Vec3 a = points.get(i - 1);
            Vec3 b = points.get(i);
            double length = Math.hypot(b.x - a.x, b.z - a.z);
            if (length < 1.0E-4 || Math.abs(a.y - b.y) > 1.8) {
                continue;
            }
            Vec3 tangent = new Vec3((b.x - a.x) / length, 0, (b.z - a.z) / length);
            int steps = Math.max(1, (int) Math.ceil(length / SPACING));
            for (int step = 0; step <= steps; step++) {
                Vec3 at = a.lerp(b, step / (double) steps);
                // The smoothed line can float between two stair tops; the corridor has to be
                // measured from the real floor, otherwise every side probe fails and the width
                // collapses to the minimum (the Dwarven Mines replan loop).
                double floor = LineFollower.floorAt(level, at);
                if (!Double.isNaN(floor) && Math.abs(floor - at.y) < 1.5) {
                    at = new Vec3(at.x, floor, at.z);
                }
                double[] sides = widthsAt(level, at, tangent);
                double left = sides[0];
                double right = sides[1];
                boolean naturalNarrow = Math.min(left, right) < 0.5;
                double half = Math.clamp(Math.min(left, right) - 0.08, MIN_HALF_WIDTH, MAX_HALF_WIDTH);
                // If one side has much more room, use a bit of that half; the other half of
                // the corridor may be blocked by a wall/edge. Never leave the safe width.
                double preferred = Math.clamp((left - right) * 0.25, -half * 0.5, half * 0.5);
                double sampleDistance = along + length * step / steps;
                // Taper to a single centre line before/after a jump or warp: there the exact
                // take-off block matters, only the middle of the corridor is safe.
                double nearestJump = Double.MAX_VALUE;
                for (double jump : jumpDistances) {
                    nearestJump = Math.min(nearestJump, Math.abs(sampleDistance - jump));
                }
                if (nearestJump < 5.0) {
                    double t = Math.clamp(nearestJump / 5.0, 0.0, 1.0);
                    half = Math.clamp(0.24 + (half - 0.24) * t, MIN_HALF_WIDTH, MAX_HALF_WIDTH);
                    preferred *= t;
                }
                samples.add(new Sample(at, tangent, half, naturalNarrow, preferred, sampleDistance));
            }
            along += length;
        }
        return new PathCorridor(samples);
    }

    /** How far the player can stand to each side of the center line at this sample. */
    private static double[] widthsAt(Level level, Vec3 center, Vec3 tangent) {
        Vec3 left = new Vec3(-tangent.z, 0, tangent.x);
        double leftClear = 0;
        double rightClear = 0;
        for (double offset = PROBE_STEP; offset <= MAX_HALF_WIDTH + 1.0E-6; offset += PROBE_STEP) {
            if (!LineFollower.standable(level, center.add(left.scale(offset)))) {
                break;
            }
            leftClear = offset;
        }
        for (double offset = PROBE_STEP; offset <= MAX_HALF_WIDTH + 1.0E-6; offset += PROBE_STEP) {
            if (!LineFollower.standable(level, center.subtract(left.scale(offset)))) {
                break;
            }
            rightClear = offset;
        }
        return new double[]{leftClear, rightClear};
    }
}
