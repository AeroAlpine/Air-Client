package com.coflnet.pathing.astar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Clean A* on a block grid with real floor heights. A node is a standing spot; its y is stored
 * in half blocks, so slabs and stairs do not turn into fake integer steps.
 *
 * <p>Walking, jumping and dropping are native edges. Teleport and etherwarp are edges supplied
 * by a {@link WarpProvider}: the provider decides where the AOTV can land, the search decides
 * whether the warp is worth it. Jumping and etherwarp are deliberately expensive, teleporting is
 * medium.
 */
public final class AStarPathfinder {

    public enum Kind { WALK, JUMP, DROP, TELEPORT, ETHERWARP }

    /** Feet position on a surface. {@code y2} is the floor top in half blocks. */
    public record Node(int x, int y2, int z) {
        public double y() {
            return y2 * 0.5;
        }

        public Node offset(int dx, int dy2, int dz) {
            return new Node(x + dx, y2 + dy2, z + dz);
        }
    }

    /** One movement. {@code cost} already includes the landing kind's penalty. */
    public record Edge(Node to, Kind kind, double cost, WarpAction warp) {
        public Edge(Node to, Kind kind, double cost) {
            this(to, kind, cost, null);
        }
    }

    /** How a warp edge has to be aimed and fired. */
    public record WarpAction(Kind kind, double aimX, double aimY, double aimZ,
                             double dirX, double dirY, double dirZ) {
    }

    public record Path(List<Node> nodes, List<Kind> kinds, List<WarpAction> warps,
                       double cost, int expanded) {
        public Path(List<Node> nodes, List<Kind> kinds, double cost, int expanded) {
            this(nodes, kinds, List.of(), cost, expanded);
        }

        public boolean found() {
            return !nodes.isEmpty();
        }
    }

    @FunctionalInterface
    public interface Goal {
        boolean reached(Node node);

        /** Admissible lower bound in ticks; default 0 keeps custom goals safe. */
        default double heuristic(Node node) {
            return 0;
        }
    }

    /**
     * World access for the search. Implementations must return an empty surface list for
     * unloaded chunks; the search never expands into them.
     */
    public interface World {
        /** Loaded and inside the world? */
        boolean loaded(int x, int z);

        boolean inBounds(int x, int y, int z);

        /** Full collision block at the block position. */
        boolean solid(int x, int y, int z);

        /** Top of every collision surface in this column between {@code minY} and {@code maxY}. */
        List<Double> surfaces(int x, int z, double minY, double maxY);

        default boolean passable(int x, int y, int z) {
            return inBounds(x, y, z) && !solid(x, y, z);
        }

        /** Player body room above this floor, using the real collision shapes. */
        boolean bodyClear(double x, double z, double floorY);

        default boolean standable(double x, double z, double floorY) {
            return bodyClear(x, z, floorY);
        }
    }

    /** Warp candidates from one standing spot. Empty by default. */
    public interface WarpProvider {
        List<Edge> edges(World world, Node from, Goal goal);

        WarpProvider NONE = (world, from, goal) -> List.of();
    }

    public record Settings(
            double maxJump,
            double maxStep,
            double maxDrop,
            int maxNodes,
            double walkCost,
            double diagonalCost,
            double jumpCost,
            double dropCost,
            double teleportCost,
            double etherwarpCost
    ) {
        public static Settings walking(double maxJump) {
            return new Settings(maxJump, 0.65, 4.0, 200_000,
                    5.0, 7.0, 18.0, 2.5, 25.0, 40.0);
        }
    }

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final World world;
    private final Settings settings;
    private final WarpProvider warps;

    public AStarPathfinder(World world, Settings settings) {
        this(world, settings, WarpProvider.NONE);
    }

    public AStarPathfinder(World world, Settings settings, WarpProvider warps) {
        this.world = world;
        this.settings = settings;
        this.warps = warps == null ? WarpProvider.NONE : warps;
    }

    public Path find(double startX, double startY, double startZ, Goal goal) {
        int sx = (int) Math.floor(startX);
        int sz = (int) Math.floor(startZ);
        Node start = startNode(sx, sz, startY);
        if (start == null) return empty();
        if (goal.reached(start)) return new Path(List.of(start), List.of(), 0, 0);

        Map<Node, Double> best = new HashMap<>();
        Map<Node, Edge> parent = new HashMap<>();
        Set<Node> closed = new HashSet<>();
        PriorityQueue<Open> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        best.put(start, 0.0);
        open.add(new Open(start, 0, heuristic(start, goal)));

        int expanded = 0;
        long deadline = System.nanoTime() + 200_000_000L;
        while (!open.isEmpty() && expanded < settings.maxNodes() && System.nanoTime() < deadline) {
            Open current = open.poll();
            if (closed.contains(current.node)) continue;
            if (current.g > best.getOrDefault(current.node, Double.MAX_VALUE) + 1.0E-6) continue;
            if (goal.reached(current.node)) {
                return reconstruct(current.node, parent, current.g, expanded);
            }
            closed.add(current.node);
            expanded++;

            for (Edge edge : neighbours(current.node, goal)) {
                Node key = edge.to;
                if (closed.contains(key)) continue;
                double g = current.g + edge.cost;
                if (g >= best.getOrDefault(key, Double.MAX_VALUE) - 1.0E-6) continue;
                best.put(key, g);
                parent.put(key, new Edge(current.node, edge.kind, edge.cost));
                open.add(new Open(edge.to, g, g + heuristic(edge.to, goal)));
            }
        }
        return new Path(List.of(), List.of(), Double.POSITIVE_INFINITY, expanded);
    }

    private Node startNode(int x, int z, double playerY) {
        if (!world.loaded(x, z)) return null;
        double min = playerY - 2.0;
        double max = playerY + 0.7;
        double best = Double.NaN;
        double bestDistance = Double.MAX_VALUE;
        for (double top : world.surfaces(x, z, min, max)) {
            if (!world.standable(x + 0.5, z + 0.5, top)) continue;
            double d = Math.abs(top - playerY);
            if (d < bestDistance) {
                bestDistance = d;
                best = top;
            }
        }
        if (Double.isNaN(best)) return null;
        return new Node(x, (int) Math.round(best * 2.0), z);
    }

    private List<Edge> neighbours(Node from, Goal goal) {
        List<Edge> out = new ArrayList<>();
        double fromY = from.y();
        double minY = fromY - settings.maxDrop() - 0.01;
        double maxY = fromY + settings.maxJump() + 0.01;
        for (int[] dir : DIRS) {
            int x = from.x + dir[0];
            int z = from.z + dir[1];
            if (!world.loaded(x, z)) continue;
            boolean diagonal = dir[0] != 0 && dir[1] != 0;
            List<Double> surfaces = world.surfaces(x, z, minY, maxY);
            for (double top : surfaces) {
                double rise = top - fromY;
                if (rise > settings.maxJump() + 1.0E-6) continue;
                if (rise < -settings.maxDrop() - 1.0E-6) continue;
                if (!world.standable(x + 0.5, z + 0.5, top)) continue;
                if (diagonal && !cornerClear(from, x, z, top, rise)) continue;
                Kind kind;
                double cost = diagonal ? settings.diagonalCost() : settings.walkCost();
                if (rise > settings.maxStep() + 1.0E-6) {
                    kind = Kind.JUMP;
                    cost += settings.jumpCost() + Math.max(0, rise - 1.0) * 4.0;
                } else if (rise < -settings.maxStep() - 1.0E-6) {
                    kind = Kind.DROP;
                    cost += -rise * settings.dropCost();
                } else {
                    kind = Kind.WALK;
                }
                Node to = new Node(x, (int) Math.round(top * 2.0), z);
                out.add(new Edge(to, kind, cost));
            }
        }
        // Precision jumps over a gap: 2 or 3 columns with no floor in between.
        for (int[] dir : CARDINALS) {
            for (int len = 2; len <= 3; len++) {
                boolean gapClear = true;
                for (int l = 1; l < len; l++) {
                    int gx = from.x + dir[0] * l, gz = from.z + dir[1] * l;
                    if (!world.loaded(gx, gz)) { gapClear = false; break; }
                    if (!world.bodyClear(gx + 0.5, gz + 0.5, fromY + 0.1)) { gapClear = false; break; }
                    if (!world.surfaces(gx, gz, fromY - 0.5, fromY + 0.6).isEmpty()) { gapClear = false; break; }
                }
                if (!gapClear) continue;
                int tx = from.x + dir[0] * len, tz = from.z + dir[1] * len;
                if (!world.loaded(tx, tz)) continue;
                double bestTop = Double.NaN;
                double bestDiff = Double.MAX_VALUE;
                for (double top : world.surfaces(tx, tz, fromY - 1.5, fromY + settings.maxJump() + 0.01)) {
                    if (!world.standable(tx + 0.5, tz + 0.5, top)) continue;
                    double diff = Math.abs(top - fromY);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        bestTop = top;
                    }
                }
                if (!Double.isNaN(bestTop) && bestTop >= fromY - 1.5) {
                    Node to = new Node(tx, (int) Math.round(bestTop * 2.0), tz);
                    double rise = bestTop - fromY;
                    out.add(new Edge(to, Kind.JUMP,
                            settings.jumpCost() + 4.0 * len + Math.abs(rise) * 3.0));
                }
            }
        }
        for (Edge warp : warps.edges(world, from, goal)) {
            if (warp != null && warp.cost() < Double.POSITIVE_INFINITY) out.add(warp);
        }
        return out;
    }

    private boolean cornerClear(Node from, int x, int z, double top, double rise) {
        int sx = from.x + Integer.signum(x - from.x);
        int sz = from.z + Integer.signum(z - from.z);
        double y = Math.min(from.y(), top);
        return world.bodyClear(sx + 0.5, from.z + 0.5, y)
                && world.bodyClear(from.x + 0.5, sz + 0.5, y);
    }

    private double heuristic(Node node, Goal goal) {
        return Math.max(0, goal.heuristic(node));
    }

    private Path reconstruct(Node goal, Map<Node, Edge> parent, double cost, int expanded) {
        List<Node> nodes = new ArrayList<>();
        List<Kind> kinds = new ArrayList<>();
        List<WarpAction> warps = new ArrayList<>();
        Node cursor = goal;
        nodes.add(cursor);
        while (parent.containsKey(cursor)) {
            Edge edge = parent.get(cursor);
            kinds.add(edge.kind());
            warps.add(edge.warp());
            cursor = edge.to();
            nodes.add(cursor);
        }
        Collections.reverse(nodes);
        Collections.reverse(kinds);
        Collections.reverse(warps);
        return new Path(nodes, kinds, warps, cost, expanded);
    }

    private static Path empty() {
        return new Path(List.of(), List.of(), Double.POSITIVE_INFINITY, 0);
    }

    private record Open(Node node, double g, double f) {
    }
}
