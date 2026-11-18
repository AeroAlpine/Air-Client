package com.coflnet.pathing;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * Where the combat bot made its kills this session. When no mob is in sight it walks towards
 * where kills were densest - most likely the best spot, but picked by chance so it does not
 * always run to the very same block. Cleared when combat is switched off.
 */
public final class KillMap {

    /** Kernel width (blocks): kills within ~this distance count towards one hot spot. */
    static final double SIGMA = 8.0;
    /** Most kills remembered (the oldest go first). */
    private static final int MAX_KILLS = 400;
    /** Random offset around the chosen spot (blocks). */
    private static final double SPREAD = 4.0;

    private static final List<Vec3> KILLS = new ArrayList<>();

    private KillMap() {
    }

    public static synchronized void record(Vec3 at) {
        if (at == null) {
            return;
        }
        KILLS.add(at);
        if (KILLS.size() > MAX_KILLS) {
            KILLS.remove(0);
        }
    }

    public static synchronized void clear() {
        KILLS.clear();
    }

    public static synchronized int size() {
        return KILLS.size();
    }

    /**
     * A spot to go to: a kill position picked with probability density² (dense areas far more
     * likely), spots within {@code minDistance} of {@code from} left out (we are already there),
     * plus a random offset. Null without kills.
     */
    public static synchronized Vec3 pick(Vec3 from, double minDistance, DoubleSupplier random) {
        return pick(List.copyOf(KILLS), from, minDistance, random);
    }

    static Vec3 pick(List<Vec3> kills, Vec3 from, double minDistance, DoubleSupplier random) {
        if (kills.isEmpty()) {
            return null;
        }
        List<Vec3> options = new ArrayList<>();
        for (Vec3 kill : kills) {
            if (from == null || flat(kill, from) >= minDistance) {
                options.add(kill);
            }
        }
        if (options.isEmpty()) {
            options = new ArrayList<>(kills); // everything is close: still move around a bit
        }
        double[] weights = new double[options.size()];
        double total = 0;
        for (int i = 0; i < options.size(); i++) {
            double d = density(kills, options.get(i));
            weights[i] = d * d;
            total += weights[i];
        }
        double roll = random.getAsDouble() * total;
        Vec3 chosen = options.get(options.size() - 1);
        for (int i = 0; i < options.size(); i++) {
            roll -= weights[i];
            if (roll <= 0) {
                chosen = options.get(i);
                break;
            }
        }
        double angle = random.getAsDouble() * Math.PI * 2;
        double radius = random.getAsDouble() * SPREAD;
        return new Vec3(chosen.x + Math.cos(angle) * radius, chosen.y, chosen.z + Math.sin(angle) * radius);
    }

    /** Gaussian kernel density of kills around {@code at}. */
    static double density(List<Vec3> kills, Vec3 at) {
        double sum = 0;
        for (Vec3 kill : kills) {
            double d = flat(kill, at);
            sum += Math.exp(-(d * d) / (2 * SIGMA * SIGMA));
        }
        return sum;
    }

    private static double flat(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
