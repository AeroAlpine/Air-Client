package com.coflnet.pathing.astar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small deterministic worlds: flat floor, wall detour, stairs, slabs and warp edges. */
public final class AStarPathfinderTest {
    private AStarPathfinderTest() {
    }

    public static void main(String[] args) {
        flat();
        smooth();
        wallDetour();
        slab();
        warp();
        System.out.println("PASS: A* flat, wall detour, half slabs, warp edges");
    }

    private static void flat() {
        TestWorld world = new TestWorld();
        AStarPathfinder.Path path = new AStarPathfinder(world, AStarPathfinder.Settings.walking(1.25))
                .find(0.5, 64, 0.5, node -> node.x() == 5 && node.z() == 0);
        check(path.found(), "flat path found");
        check(path.nodes().size() == 6, "flat path is five steps: " + path.nodes().size());
        check(path.cost() > 0, "flat path has a cost");
    }

    private static void smooth() {
        TestWorld world = new TestWorld();
        AStarPathfinder.Path raw = new AStarPathfinder(world, AStarPathfinder.Settings.walking(1.25))
                .find(0.5, 64, 0.5, node -> node.x() == 5 && node.z() == 0);
        AStarPathfinder.Path smooth = AStarSmoother.smooth(world, raw);
        check(smooth.found(), "smooth path found");
        check(smooth.nodes().size() <= 2, "flat path is one straight line: " + smooth.nodes().size());
    }

    private static void wallDetour() {
        TestWorld world = new TestWorld();
        for (int y = 64; y <= 67; y++) {
            for (int z = -3; z <= 3; z++) {
                world.wall.put(key(2, y, z), true);
            }
        }
        AStarPathfinder.Path path = new AStarPathfinder(world, AStarPathfinder.Settings.walking(1.25))
                .find(0.5, 64, 0.5, node -> node.x() == 4 && node.z() == 0);
        check(path.found(), "wall detour found");
        check(path.nodes().stream().noneMatch(n -> n.x() == 2 && Math.abs(n.z()) <= 3),
                "wall is not walked through");
        check(path.nodes().stream().anyMatch(n -> n.x() == 2 && Math.abs(n.z()) > 3),
                "path goes around the wall");
    }

    private static void slab() {
        TestWorld world = new TestWorld();
        world.floor.put(key(1, 0), 64.5); // half slab
        world.floor.put(key(2, 0), 65.5); // one block step above the half slab
        AStarPathfinder.Path path = new AStarPathfinder(world, AStarPathfinder.Settings.walking(1.25))
                .find(0.5, 64, 0.5, node -> node.x() == 3 && node.z() == 0);
        check(path.found(), "slab/step path found");
        check(path.nodes().stream().anyMatch(n -> n.y() == 64.5), "half slab keeps its real height");
    }

    private static void warp() {
        TestWorld world = new TestWorld();
        AStarPathfinder.WarpProvider warps = (w, from, goal) -> {
            if (from.x() == 0 && from.z() == 0) {
                return List.of(new AStarPathfinder.Edge(new AStarPathfinder.Node(10, 128, 0),
                        AStarPathfinder.Kind.TELEPORT, 25));
            }
            return List.of();
        };
        AStarPathfinder.Path path = new AStarPathfinder(world, AStarPathfinder.Settings.walking(1.25), warps)
                .find(0.5, 64, 0.5, node -> node.x() == 10 && node.z() == 0);
        check(path.found(), "warp path found");
        check(path.kinds().contains(AStarPathfinder.Kind.TELEPORT), "teleport edge used");
        check(path.cost() < 60, "teleport is cheaper than ten blocks of walking: " + path.cost());
    }

    private static final class TestWorld implements AStarPathfinder.World {
        final Map<Long, Double> floor = new HashMap<>();
        final Map<Long, Boolean> wall = new HashMap<>();

        TestWorld() {
            // floor top at y=64 on a 64..72 grid
            for (int x = -64; x <= 64; x++) {
                for (int z = -64; z <= 64; z++) {
                    floor.put(key(x, z), 64.0);
                }
            }
        }

        @Override
        public boolean loaded(int x, int z) {
            return Math.abs(x) <= 62 && Math.abs(z) <= 62;
        }

        @Override
        public boolean inBounds(int x, int y, int z) {
            return y >= -64 && y <= 320;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return wall.containsKey(key(x, y, z));
        }

        @Override
        public List<Double> surfaces(int x, int z, double minY, double maxY) {
            if (!loaded(x, z)) return List.of();
            double top = floor.getOrDefault(key(x, z), 64.0);
            if (top < minY || top > maxY) return List.of();
            return List.of(top);
        }

        @Override
        public boolean bodyClear(double x, double z, double floorY) {
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            for (int y = (int) Math.floor(floorY + 0.01); y <= (int) Math.floor(floorY + 1.75); y++) {
                if (solid(bx, y, bz)) return false;
            }
            return true;
        }
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static long key(int x, int y, int z) {
        return (((long) x) << 42) ^ (((long) y & 0xfff) << 30) ^ (z & 0x3fffffffL);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
