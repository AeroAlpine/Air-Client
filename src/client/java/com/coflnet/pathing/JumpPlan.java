package com.coflnet.pathing;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A jump is the only thing that is pre-computed in detail. For every realistic speed the take-off
 * point is solved with the real Minecraft physics (custom speed, jump height, gravity, drag and
 * collisions), and the whole air path (one position per tick) is stored. At runtime the bot only
 * has to pick the entry for the speed it really has, jump one tick before that take-off point and
 * follow the stored air path.
 */
public final class JumpPlan {
    /** One speed sample: where to take off and the already simulated positions in the air. */
    public record Entry(double speed, Vec3 takeoff, double takeoffDistance,
                        boolean lands, boolean blocked, List<Vec3> airPath) {
    }

    private final Vec3 landing;
    private final Vec3 direction;
    private final List<Entry> entries;
    private final boolean feasible;

    private JumpPlan(Vec3 landing, Vec3 direction, List<Entry> entries) {
        this.landing = landing;
        this.direction = direction;
        this.entries = entries;
        this.feasible = entries.stream().anyMatch(e -> e.lands() && !e.blocked());
    }

    public Vec3 landing() {
        return landing;
    }

    public Vec3 direction() {
        return direction;
    }

    public boolean feasible() {
        return feasible;
    }

    public Entry entryForSpeed(double speed) {
        if (entries.isEmpty()) {
            return null;
        }
        Entry best = entries.get(0);
        double bestError = Double.MAX_VALUE;
        for (Entry entry : entries) {
            double error = Math.abs(entry.speed() - speed);
            if (error < bestError) {
                bestError = error;
                best = entry;
            }
        }
        return best;
    }

    /**
     * Builds the profile for one planned jump. {@code takeoff} is the last walking point before
     * the gap, {@code landing} the point the bot has to come down on.
     */
    public static JumpPlan build(Level level, LocalPlayer player, Vec3 takeoff, Vec3 landing) {
        if (level == null || player == null || takeoff == null || landing == null
                || player.isInWater() || player.isInLava()) {
            return null;
        }
        Vec3 flat = new Vec3(landing.x - takeoff.x, 0, landing.z - takeoff.z);
        if (flat.horizontalDistance() < 0.15) {
            return null;
        }
        Vec3 direction = flat.normalize();
        double minRunUp = flat.horizontalDistance(); // never start the jump inside the gap
        double max = Math.max(0.30, Math.min(1.2, TurnProfile.sprintSpeed()));
        double[] speeds = {
                0.02,
                max * 0.20,
                max * 0.40,
                max * 0.60,
                max * 0.80,
                max
        };
        List<Entry> entries = new ArrayList<>(speeds.length);
        for (double speed : speeds) {
            Entry entry = solve(level, player, landing, direction, speed, minRunUp);
            if (entry != null) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            return null;
        }
        return new JumpPlan(landing, direction, List.copyOf(entries));
    }

    private static Entry solve(Level level, LocalPlayer player, Vec3 landing, Vec3 direction,
                               double speed, double minRunUp) {
        boolean sprint = speed >= 0.26;
        double distance = Math.clamp(speed * 12.0 + 0.35, 0.35, 8.0);
        MinecraftPhysics.Trajectory best = null;
        double bestDistance = distance;
        double bestError = Double.MAX_VALUE;

        for (int iteration = 0; iteration < 5; iteration++) {
            Vec3 start = landing.subtract(direction.scale(distance));
            MinecraftPhysics.Trajectory trajectory = MinecraftPhysics.simulate(
                    level, player, start, direction.scale(speed), sprint, landing);
            if (trajectory == null) {
                return null;
            }
            double error = trajectory.forwardDistance() - distance;
            if (Math.abs(error) < Math.abs(bestError) || best == null) {
                best = trajectory;
                bestDistance = distance;
                bestError = error;
            }
            if (Math.abs(error) < 0.06) {
                break;
            }
            distance = Math.clamp(distance + error, 0.25, 8.0);
        }
        if (best == null) {
            return null;
        }
        List<Vec3> air = new ArrayList<>(best.path());
        if (best.landed()) {
            air.add(best.landing());
        }
        // touching the side of the ledge while landing on top is normal; only the landing counts
        boolean usable = best.landed()
                && Math.abs(bestError) <= 0.45
                && Math.abs(best.landing().y - landing.y) <= 0.7
                && bestDistance >= minRunUp - 0.15; // the take-off must still be on the approach side
        return new Entry(speed, landing.subtract(direction.scale(bestDistance)), bestDistance,
                usable, best.blocked(), List.copyOf(air));
    }

    /** Short log line for the engine log. */
    public String summary() {
        if (entries.isEmpty()) {
            return "no jump profile";
        }
        Entry fastest = entries.get(entries.size() - 1);
        return String.format(Locale.ROOT,
                "jump profile: speeds %.2f-%.2f, takeoff %.2f-%.2f b before landing%s",
                entries.get(0).speed(), fastest.speed(),
                entries.get(0).takeoffDistance(), fastest.takeoffDistance(),
                feasible ? "" : " (PHYSICALLY IMPOSSIBLE)");
    }
}
