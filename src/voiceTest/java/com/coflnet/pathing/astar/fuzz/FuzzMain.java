package com.coflnet.pathing.astar.fuzz;

import com.coflnet.pathing.astar.AStarPathfinder;
import com.coflnet.pathing.astar.AStarSmoother;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless farm: generates hundreds of solvable courses per second, plans them with the A*
 * and checks the plan. Failures are printed with their seed so they can become regression
 * tests. No Minecraft instance is needed.
 */
public final class FuzzMain {
    private static boolean debugOnce = true;

    private static void dbg(long seed, int level, double jump) {
        com.coflnet.pathing.astar.fuzz.Course course =
                com.coflnet.pathing.astar.fuzz.Course.generate(seed, 64, jump >= 1.2, level);
        FakeWorld world = new FakeWorld(course);
        AStarPathfinder.Settings settings = new AStarPathfinder.Settings(
                jump, 0.65, 4.0, 80_000, 5.0, 7.0, 18.0, 2.5, 25.0, 40.0);
        AStarPathfinder.Path path = new AStarPathfinder(world, settings)
                .find(0.5, 64, 0.5, node -> node.x() == course.goalX() && node.z() == course.goalZ()
                        && Math.abs(node.y() - course.goalY()) <= 0.75);
        System.out.println("DBG seed=" + seed + " level=" + level + " jump=" + jump
                + " goalY=" + course.goalY() + " found=" + path.found()
                + " expanded=" + path.expanded());
        System.out.println("DBG start surfaces=" + world.surfaces(0, 0, 62.0, 64.7)
                + " clear=" + world.bodyClear(0.5, 0.5, 64.0)
                + " solid=" + world.solid(0, 63, 0));
        for (com.coflnet.pathing.astar.fuzz.Course.Cell cell : course.cells()) {
            if (cell.x() == 0 && cell.z() == 0) System.out.println("DBG cell0 " + cell);
        }
        java.util.Map<Integer, Double> floorAt = new java.util.HashMap<>();
        java.util.Map<Integer, String> blockAt = new java.util.HashMap<>();
        for (com.coflnet.pathing.astar.fuzz.Course.Cell cell : course.cells()) {
            if (cell.z() == 0) {
                floorAt.put(cell.x(), cell.floorY());
                blockAt.put(cell.x(), cell.block() + "/step" + cell.step());
            }
        }
        for (int x : new int[]{15, 16, 17, 18}) {
            System.out.println("DBG surf(" + x + ",0)=" + world.surfaces(x, 0, 62.0, 66.0)
                    + " clear=" + world.bodyClear(x + 0.5, 0.5, 64.0));
        }
        for (int x : new int[]{30, 31}) {
            System.out.println("DBG surf(" + x + ",0)=" + world.surfaces(x, 0, 57.9, 63.3)
                    + " clear0=" + world.bodyClear(x + 0.5, 0.5, 62.0)
                    + " surf(" + x + ",1)=" + world.surfaces(x, 1, 57.9, 65.3)
                    + " clear1=" + world.bodyClear(x + 0.5, 1.5, 64.0));
        }
        {
            AStarPathfinder.Path p31 = new AStarPathfinder(world, settings)
                    .find(0.5, 64, 0.5, node -> node.x() == 31 && node.z() == 0);
            System.out.println("DBG p31-anyY found=" + p31.found() + " expanded=" + p31.expanded());
            for (int i = 0; i < p31.nodes().size(); i++) {
                System.out.println("DBG p31 node " + i + " " + p31.nodes().get(i));
            }
        }
        {
            AStarPathfinder.Path p18 = new AStarPathfinder(world, settings)
                    .find(0.5, 64, 0.5, node -> node.x() == 15);
            System.out.println("DBG k15 found=" + p18.found() + " expanded=" + p18.expanded());
            for (int i = 0; i < p18.nodes().size(); i++) {
                System.out.println("DBG k15 node " + i + " " + p18.nodes().get(i));
            }
            System.out.println("DBG k18 found=" + p18.found() + " expanded=" + p18.expanded());
            for (int i = 0; i < p18.nodes().size(); i++) {
                System.out.println("DBG k18 node " + i + " " + p18.nodes().get(i));
            }
        }
        for (int k = 1; k <= course.goalX(); k++) {
            final int kk = k;
            AStarPathfinder.Path pk = new AStarPathfinder(world, settings)
                    .find(0.5, 64, 0.5, node -> node.x() == kk);
            if (k == 30) {
                System.out.println("DBG k30 found=" + pk.found() + " expanded=" + pk.expanded());
                for (int i = 0; i < pk.nodes().size(); i++) {
                    System.out.println("DBG k30 node " + i + " " + pk.nodes().get(i));
                }
            }
            if (k == 18) {
                System.out.println("DBG k18 found=" + pk.found() + " expanded=" + pk.expanded());
                for (int i = 0; i < pk.nodes().size(); i++) {
                    System.out.println("DBG k18 node " + i + " " + pk.nodes().get(i));
                }
            }
            if (!pk.found() && k == 31) {
                System.out.println("DBG k31 expanded=" + pk.expanded());
                for (int i = 0; i < pk.nodes().size(); i++) {
                    System.out.println("DBG k31 node " + i + " " + pk.nodes().get(i));
                }
            }
            if (!pk.found()) {
                System.out.println("DBG first unreachable x=" + k + " block=" + blockAt.get(k)
                        + " floor=" + floorAt.get(k));
                for (int x = k - 3; x <= k + 2; x++) {
                    System.out.println("DBG col x=" + x + " " + blockAt.get(x) + " floor=" + floorAt.get(x));
                }
                break;
            }
        }
        for (int i = 0; i < path.nodes().size(); i++) {
            AStarPathfinder.Node n = path.nodes().get(i);
            System.out.println("NODE " + i + " x=" + n.x() + " z=" + n.z() + " y=" + n.y()
                    + (i < path.kinds().size() ? " kind=" + path.kinds().get(i) : ""));
        }
    }

    private FuzzMain() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("dbg")) {
            dbg(Long.parseLong(args[1]), Integer.parseInt(args[2]), Double.parseDouble(args[3]));
            return;
        }
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 500;
        double[] jumpHeights = {0.6, 1.25, 2.5, 6.13};
        int planned = 0;
        int failed = 0;
        long worstNs = 0;
        long worstSeed = 0;
        List<String> lines = new ArrayList<>();
        lines.add("seed\tlevel\tmaxJump\tfound\tnodes\tcost\texpanded\tms");

        for (int i = 0; i < seeds; i++) {
            long seed = Course.mix(i * 0x9E3779B97F4A7C15L);
            for (int level = 1; level <= 5; level++) {
                for (double jump : jumpHeights) {
                Course course = Course.generate(seed, 64, jump >= 1.2, level);
                FakeWorld world = new FakeWorld(course);
                AStarPathfinder.Settings settings = new AStarPathfinder.Settings(
                        jump, 0.65, 4.0, 80_000, 5.0, 7.0, 18.0, 2.5, 25.0, 40.0);
                long start = System.nanoTime();
                AStarPathfinder.Path path = new AStarPathfinder(world, settings)
                        .find(course.startX() + 0.5, course.baseY(), course.startZ() + 0.5,
                                node -> node.x() == course.goalX() && node.z() == course.goalZ()
                                        && Math.abs(node.y() - course.goalY()) <= 0.75);
                long ns = System.nanoTime() - start;
                worstNs = Math.max(worstNs, ns);
                boolean found = path.found();
                if (found) {
                    planned++;
                    AStarPathfinder.Path smooth = AStarSmoother.smooth(world, path);
                    if (!smooth.found() || smooth.nodes().size() < 2) {
                        System.out.println("BAD SMOOTH seed=" + seed + " level=" + level + " jump=" + jump);
                        failed++;
                    }
                    if (path.nodes().get(path.nodes().size() - 1).x() != course.goalX()
                            || path.nodes().get(path.nodes().size() - 1).z() != course.goalZ()) {
                        System.out.println("BAD GOAL seed=" + seed + " level=" + level + " jump=" + jump);
                        failed++;
                    }
                } else {
                    failed++;
                    System.out.println("NO PATH seed=" + seed + " level=" + level + " jump=" + jump);
                    worstSeed = seed;
                    if (debugOnce) {
                        debugOnce = false;
                        for (com.coflnet.pathing.astar.fuzz.Course.Cell cell : course.cells()) {
                            System.out.println("CELL x=" + cell.x() + " z=" + cell.z() + " " + cell.block()
                                    + " floor=" + cell.floorY() + " step=" + cell.step() + " top=" + cell.top());
                        }
                    }
                }
                lines.add(seed + "\t" + level + "\t" + jump + "\t" + found + "\t" + path.nodes().size()
                        + "\t" + String.format(java.util.Locale.ROOT, "%.1f", path.cost())
                        + "\t" + path.expanded() + "\t" + String.format(java.util.Locale.ROOT, "%.2f", ns / 1e6));
                }
            }
        }

        Path out = Path.of(System.getProperty("user.dir"), "build", "astar-fuzz");
        Files.createDirectories(out);
        Files.write(out.resolve("summary.tsv"), lines);
        System.out.println("FUZZ planned=" + planned + " failed=" + failed
                + " worstMs=" + String.format(java.util.Locale.ROOT, "%.2f", worstNs / 1e6)
                + " worstSeed=" + worstSeed);
        if (failed > 0) {
            throw new AssertionError("Fuzz failures: " + failed);
        }
    }
}
