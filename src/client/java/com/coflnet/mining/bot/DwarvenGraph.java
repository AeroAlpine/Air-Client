package com.coflnet.mining.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Ways through the Dwarven Mines, learned from real recorded runs (Interaction Logger):
 * points about 6 blocks apart, connected in the direction they were walked (a drop is one way),
 * teleport hops included. A route here is only the rough line; the pathfinder walks (and warps)
 * between its points, so it never copies a recording 1:1.
 */
public final class DwarvenGraph {
    private static final String RESOURCE = "/assets/coflmod/mining/dwarven_graph.json";
    /** "/warp forge" from anywhere: command, loading and the first steps. */
    private static final double WARP_COST_S = 6.0;
    private static final double WALK_SPEED = 5.0;

    public record Spot(String area, Vec3 pos, int blocks) {
    }

    /** How a route point is reached from the one before it. */
    public static final int WALK = 0, TELEPORT = 1, ETHERWARP = 2;
    /** A walk on a line checked against the blocks: walked straight ahead, stairs included. */
    public static final int STRAIGHT = 3;

    /**
     * A planned way: optionally "/warp forge" first, then the stations to pass (last = closest to
     * the target) and for each how to get there from the previous one ({@link #WALK} ...).
     */
    public record Route(boolean warpFirst, List<Vec3> points, List<Integer> modes, double seconds, double gap) {
    }

    private static DwarvenGraph instance;

    private final double[][] nodes;
    private final int[] nodeArea;
    private final List<String> areas;
    private final List<int[]>[] out;
    private final List<double[]>[] outCost;
    private final List<Spot> spots;
    private final Map<String, Vec3> places = new HashMap<>();
    /** Blocks the player showed as great etherwarp targets ("ew" recordings). */
    private final List<net.minecraft.core.BlockPos> etherwarpPoints = new ArrayList<>();
    /** Middles of good mining areas ("mining pos" recordings) plus the mining spots. */
    private final List<Vec3> miningCenters = new ArrayList<>();
    private final int forgeNode;

    @SuppressWarnings("unchecked")
    private DwarvenGraph(JsonObject json) {
        JsonArray areaArray = json.getAsJsonArray("areas");
        List<String> areaList = new ArrayList<>();
        for (JsonElement e : areaArray) areaList.add(e.getAsString());
        areas = List.copyOf(areaList);
        JsonArray nodeArray = json.getAsJsonArray("nodes");
        nodes = new double[nodeArray.size()][];
        nodeArea = new int[nodeArray.size()];
        out = new List[nodes.length];
        outCost = new List[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            JsonArray n = nodeArray.get(i).getAsJsonArray();
            nodes[i] = new double[]{n.get(0).getAsDouble(), n.get(1).getAsDouble(), n.get(2).getAsDouble()};
            nodeArea[i] = n.get(3).getAsInt();
            out[i] = new ArrayList<>();
            outCost[i] = new ArrayList<>();
        }
        for (JsonElement e : json.getAsJsonArray("edges")) {
            JsonArray a = e.getAsJsonArray();
            int from = a.get(0).getAsInt(), to = a.get(1).getAsInt();
            out[from].add(new int[]{to, a.get(2).getAsInt()});
            outCost[from].add(new double[]{a.get(3).getAsDouble()});
        }
        List<Spot> spotList = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("spots")) {
            JsonObject s = e.getAsJsonObject();
            JsonArray p = s.getAsJsonArray("pos");
            spotList.add(new Spot(s.get("area").getAsString(),
                    new Vec3(p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble()),
                    s.get("blocks").getAsInt()));
        }
        spots = List.copyOf(spotList);
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("places").entrySet()) {
            JsonArray p = e.getValue().getAsJsonArray();
            places.put(e.getKey(), new Vec3(p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble()));
        }
        if (json.has("etherwarpPoints")) {
            for (JsonElement e : json.getAsJsonArray("etherwarpPoints")) {
                JsonArray p = e.getAsJsonArray();
                etherwarpPoints.add(new net.minecraft.core.BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()));
            }
        }
        if (json.has("miningCenters")) {
            for (JsonElement e : json.getAsJsonArray("miningCenters")) {
                JsonArray p = e.getAsJsonArray();
                miningCenters.add(new Vec3(p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble()));
            }
        }
        for (Spot s : spots) miningCenters.add(s.pos());
        forgeNode = nearest(place("forgeWarp"), 8);
    }

    public static synchronized DwarvenGraph get() {
        if (instance == null) {
            try (InputStream in = DwarvenGraph.class.getResourceAsStream(RESOURCE)) {
                if (in == null) throw new IllegalStateException("missing " + RESOURCE);
                instance = new DwarvenGraph(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                        .getAsJsonObject());
            } catch (Exception e) {
                throw new IllegalStateException("Could not load the Dwarven Mines routes: " + e.getMessage(), e);
            }
        }
        return instance;
    }

    public List<net.minecraft.core.BlockPos> etherwarpPoints() {
        return etherwarpPoints;
    }

    /** The nearest mining centre within {@code max} blocks, or null. */
    public Vec3 miningCenter(Vec3 near, double max) {
        Vec3 best = null;
        double bestDist = max;
        for (Vec3 c : miningCenters) {
            double d = c.distanceTo(near);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    public Vec3 place(String name) {
        return places.get(name);
    }

    public List<Spot> spots() {
        return spots;
    }

    public String areaOf(int node) {
        int a = nodeArea[node];
        return a < 0 || a >= areas.size() ? "" : areas.get(a);
    }

    private Vec3 pos(int node) {
        return new Vec3(nodes[node][0], nodes[node][1], nodes[node][2]);
    }

    private double dist(int node, Vec3 p) {
        double dx = nodes[node][0] - p.x, dy = nodes[node][1] - p.y, dz = nodes[node][2] - p.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private int nearest(Vec3 p, double max) {
        int best = -1;
        double bestDist = max;
        for (int i = 0; i < nodes.length; i++) {
            double d = dist(i, p);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** Seconds of travel from {@code from} to every node (Dijkstra), with or without "/warp forge". */
    private double[] costs(Vec3 from, boolean allowWarp, boolean allowHops, int[] prev, int[] prevMode, boolean[] warped) {
        double[] cost = new double[nodes.length];
        Arrays.fill(cost, Double.MAX_VALUE);
        Arrays.fill(prev, -1);
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        for (int i = 0; i < nodes.length; i++) {
            double d = dist(i, from);
            if (d < 12) {
                cost[i] = d / WALK_SPEED * 1.4;
                queue.add(new double[]{cost[i], i});
            }
        }
        if (allowWarp && forgeNode >= 0 && WARP_COST_S < cost[forgeNode]) {
            cost[forgeNode] = WARP_COST_S;
            warped[forgeNode] = true;
            queue.add(new double[]{WARP_COST_S, forgeNode});
        }
        while (!queue.isEmpty()) {
            double[] head = queue.poll();
            int u = (int) head[1];
            if (head[0] > cost[u]) continue;
            for (int k = 0; k < out[u].size(); k++) {
                int v = out[u].get(k)[0];
                int mode = out[u].get(k)[1];
                if (!allowHops && (mode == TELEPORT || mode == ETHERWARP)) continue;
                double c = cost[u] + outCost[u].get(k)[0];
                if (c < cost[v]) {
                    cost[v] = c;
                    prev[v] = u;
                    prevMode[v] = mode;
                    warped[v] = warped[u];
                    queue.add(new double[]{c, v});
                }
            }
        }
        return cost;
    }

    /**
     * The cheapest way towards {@code to}: through the graph to the node that is reachable and
     * close to the target (the rest is left to the pathfinder), optionally "/warp forge" first.
     */
    public Route route(Vec3 from, Vec3 to, boolean allowWarp) {
        return route(from, to, allowWarp, true);
    }

    /** {@code allowHops} false: walking links only (no mana for teleports). */
    public Route route(Vec3 from, Vec3 to, boolean allowWarp, boolean allowHops) {
        int[] prev = new int[nodes.length];
        int[] prevMode = new int[nodes.length];
        boolean[] warped = new boolean[nodes.length];
        double[] cost = costs(from, allowWarp, allowHops, prev, prevMode, warped);
        double direct = from.distanceTo(to);
        int best = -1;
        double bestScore = direct / WALK_SPEED * 2.0; // just walking there (no node needed)
        for (int i = 0; i < nodes.length; i++) {
            if (cost[i] == Double.MAX_VALUE) continue;
            double score = cost[i] + dist(i, to) / WALK_SPEED * 2.0;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0) {
            return new Route(false, List.of(to), List.of(WALK), direct / WALK_SPEED, direct);
        }
        List<Vec3> points = new ArrayList<>();
        List<Integer> modes = new ArrayList<>();
        for (int n = best; n >= 0; n = prev[n]) {
            points.add(pos(n));
            modes.add(prev[n] >= 0 ? prevMode[n] : WALK);
        }
        Collections.reverse(points);
        Collections.reverse(modes);
        return new Route(warped[best], points, modes, cost[best], dist(best, to));
    }

    /** Estimated seconds to get from {@code from} to {@code to} (graph plus the rest on foot). */
    public double seconds(Vec3 from, Vec3 to, boolean allowWarp) {
        Route r = route(from, to, allowWarp);
        return r.seconds() + r.gap() / WALK_SPEED * 2.0;
    }

    /** Best known mining spot, in {@code area} when given (case-insensitive), the quickest to reach otherwise. */
    public Spot bestSpot(Vec3 from, String area) {
        Spot best = null;
        double bestScore = Double.MAX_VALUE;
        for (Spot s : spots) {
            if (area != null && !area.isEmpty() && !s.area().toLowerCase(Locale.ROOT).equals(area.toLowerCase(Locale.ROOT))) {
                continue;
            }
            // a good spot (many blocks mined there) is worth a little more travel
            double score = seconds(from, s.pos(), true) - Math.log(1 + s.blocks()) * 4.0;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    /** A point inside {@code area}, for area commissions without a known spot. */
    public Vec3 anyPointIn(Vec3 from, String area) {
        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < nodes.length; i++) {
            if (!areaOf(i).equalsIgnoreCase(area)) continue;
            double score = dist(i, from);
            if (score < bestScore) {
                bestScore = score;
                best = pos(i);
            }
        }
        return best;
    }

    public List<Spot> spotsSortedFrom(Vec3 from) {
        List<Spot> list = new ArrayList<>(spots);
        list.sort((a, b) -> Double.compare(a.pos().distanceTo(from), b.pos().distanceTo(from)));
        return list;
    }
}
