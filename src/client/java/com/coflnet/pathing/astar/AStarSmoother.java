package com.coflnet.pathing.astar;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the A* node chain into long straight walking pieces. Jumps, drops and warps are never
 * smoothed away: they are exactly the places where the movement rules change. Floors are checked
 * with their real heights, so a half slab is walked on and not treated as a step.
 */
public final class AStarSmoother {
    private AStarSmoother() {
    }

    public static AStarPathfinder.Path smooth(AStarPathfinder.World world, AStarPathfinder.Path path) {
        if (!path.found() || path.nodes().size() < 3) return path;
        // A warp is an action, not a walkable shortcut; keep the whole action chain untouched.
        if (path.kinds().stream().anyMatch(k -> k == AStarPathfinder.Kind.TELEPORT
                || k == AStarPathfinder.Kind.ETHERWARP)) return path;
        List<AStarPathfinder.Node> nodes = new ArrayList<>();
        List<AStarPathfinder.Kind> kinds = new ArrayList<>();
        List<AStarPathfinder.Node> in = path.nodes();
        nodes.add(in.get(0));
        int i = 0;
        while (i < path.kinds().size()) {
            AStarPathfinder.Kind kind = path.kinds().get(i);
            if (kind != AStarPathfinder.Kind.WALK) {
                kinds.add(kind);
                nodes.add(in.get(i + 1));
                i++;
                continue;
            }
            int end = i;
            while (end + 1 < path.kinds().size()
                    && path.kinds().get(end + 1) == AStarPathfinder.Kind.WALK) {
                end++;
            }
            int pick = end;
            for (int j = end; j > i; j--) {
                if (lineWalkable(world, in.get(i), in.get(j + 1))) {
                    pick = j;
                    break;
                }
            }
            kinds.add(AStarPathfinder.Kind.WALK);
            nodes.add(in.get(pick + 1));
            i = pick + 1;
        }
        return new AStarPathfinder.Path(nodes, kinds, path.cost(), path.expanded());
    }

    /** Straight floor line with body room and no step bigger than a normal walk. */
    public static boolean lineWalkable(AStarPathfinder.World world,
                                       AStarPathfinder.Node a, AStarPathfinder.Node b) {
        double ax = a.x() + 0.5, az = a.z() + 0.5, ay = a.y();
        double bx = b.x() + 0.5, bz = b.z() + 0.5, by = b.y();
        double dx = bx - ax, dz = bz - az;
        double length = Math.hypot(dx, dz);
        if (length < 1.0E-4) return true;
        int steps = Math.max(1, (int) Math.ceil(length / 0.35));
        double previous = ay;
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            double x = ax + dx * t, z = az + dz * t;
            double wanted = ay + (by - ay) * t;
            List<Double> surfaces = world.surfaces((int) Math.floor(x), (int) Math.floor(z),
                    wanted - 0.75, wanted + 0.75);
            double floor = Double.NaN;
            double best = Double.MAX_VALUE;
            for (double top : surfaces) {
                double d = Math.abs(top - wanted);
                if (d < best) {
                    best = d;
                    floor = top;
                }
            }
            if (Double.isNaN(floor)) return false;
            if (Math.abs(floor - previous) > 0.65) return false;
            if (!world.bodyClear(x, z, floor)) return false;
            previous = floor;
        }
        return true;
    }
}
