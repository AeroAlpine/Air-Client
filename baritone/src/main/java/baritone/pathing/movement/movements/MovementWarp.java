/*
 * Air Client addition: AOTV teleport and etherwarp as path moves.
 */

package baritone.pathing.movement.movements;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * A warp as one move of the path search (Air Client): an AOTV teleport along a fixed direction
 * (it stops in front of the first block, then we fall to the floor) or an etherwarp onto the
 * first block hit (it needs room for the player on top). The search finds ways only warps make
 * possible - onto a ledge, over a gap, out of a hole - and weighs them against walking.
 * <p>Only planned when {@link CalculationContext#allowTeleport} / {@link CalculationContext#allowEtherwarp}
 * is set (the Air Client path engine executes them itself; Baritone's own executor never gets one).
 */
public class MovementWarp extends Movement {
    public enum Kind { TELEPORT, ETHERWARP }

    /** The player's own etherwarp targets (block positions, x y z per entry): cheap moves when visible. */
    public static volatile int[][] etherwarpPoints = new int[0][];

    private static final double EYE = 1.62;
    private static final double ETHER_RANGE = 45.0;

    public final Kind kind;
    /** Look direction of the warp (yaw / pitch in degrees, Minecraft convention). */
    public final float yaw;
    public final float pitch;

    public MovementWarp(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, Kind kind, float yaw, float pitch) {
        super(baritone, src, dest, new BetterBlockPos[0]);
        this.kind = kind;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult r = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, kind, yaw, pitch, r);
        return r.x == dest.x && r.y == dest.y && r.z == dest.z ? r.cost : COST_INF;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return Set.of(src, dest);
    }

    @Override
    public MovementState updateState(MovementState state) {
        // Baritone's own executor never runs warps (only the Air Client path engine does)
        return state.setStatus(MovementStatus.UNREACHABLE);
    }

    public static MovementWarp createPoint(CalculationContext context, BetterBlockPos src, int nth) {
        MutableMoveResult r = new MutableMoveResult();
        pointCost(context, src.x, src.y, src.z, nth, r);
        return new MovementWarp(context.getBaritone(), src, new BetterBlockPos(r.x, r.y, r.z), Kind.ETHERWARP, 0f, 0f) {
            @Override
            public double calculateCost(CalculationContext c) {
                MutableMoveResult q = new MutableMoveResult();
                pointCost(c, src.x, src.y, src.z, nth, q);
                return q.x == dest.x && q.y == dest.y && q.z == dest.z ? q.cost : COST_INF;
            }
        };
    }

    public static MovementWarp create(CalculationContext context, BetterBlockPos src, Kind kind, float yaw, float pitch) {
        MutableMoveResult r = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, kind, yaw, pitch, r);
        return new MovementWarp(context.getBaritone(), src, new BetterBlockPos(r.x, r.y, r.z), kind, yaw, pitch);
    }

    /** Where the warp from feet (x, y, z) ends and what it costs (COST_INF = not possible). */
    public static void cost(CalculationContext context, int x, int y, int z, Kind kind, float yaw, float pitch,
                            MutableMoveResult res) {
        res.x = x;
        res.y = y;
        res.z = z;
        res.cost = COST_INF;
        if (kind == Kind.TELEPORT) {
            // half of all spots is enough for the search (keeps it fast)
            if (!context.allowTeleport || ((x + z) & 1) != 0) return;
        } else if (!context.allowEtherwarp || (x & 1) != 0 || (z & 1) != 0) {
            return;
        }
        double ex = x + 0.5, ey = y + EYE, ez = z + 0.5;
        double r = Math.toRadians(yaw), p = Math.toRadians(pitch);
        double dx = -Math.sin(r) * Math.cos(p), dy = -Math.sin(p), dz = Math.cos(r) * Math.cos(p);
        if (kind == Kind.TELEPORT) {
            int lx = Integer.MIN_VALUE, ly = 0, lz = 0;
            for (double t = 0.5; t <= context.teleportReach + 1.0E-6; t += 0.5) {
                int vx = floor(ex + dx * t), vy = floor(ey + dy * t), vz = floor(ez + dz * t);
                if (!MovementHelper.fullyPassable(context, vx, vy, vz) || !MovementHelper.fullyPassable(context, vx, vy - 1, vz)) {
                    break;
                }
                lx = vx;
                ly = vy - 1;
                lz = vz;
            }
            if (lx == Integer.MIN_VALUE) return;
            // fall to the floor under the landing point
            for (int drop = 0; drop <= 12; drop++) {
                int fy = ly - drop;
                if (!MovementHelper.fullyPassable(context, lx, fy, lz)) return;
                if (standable(context, lx, fy, lz)) {
                    int dist2 = (lx - x) * (lx - x) + (lz - z) * (lz - z) + (fy - y) * (fy - y);
                    if (dist2 < 16) return; // a few blocks are walked, not warped
                    res.x = lx;
                    res.y = fy;
                    res.z = lz;
                    res.cost = context.teleportCost + (drop > 0 ? FALL_N_BLOCKS_COST[Math.min(drop, FALL_N_BLOCKS_COST.length - 1)] * 0.5 : 0);
                    return;
                }
            }
            return;
        }
        // etherwarp: the first block the look hits (grid walk), with room for the player on it
        int vx = floor(ex), vy = floor(ey), vz = floor(ez);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double tdy = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double tdz = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tmx = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? vx + 1 - ex : ex - vx) * tdx;
        double tmy = dy == 0 ? Double.POSITIVE_INFINITY : (dy > 0 ? vy + 1 - ey : ey - vy) * tdy;
        double tmz = dz == 0 ? Double.POSITIVE_INFINITY : (dz > 0 ? vz + 1 - ez : ez - vz) * tdz;
        double t = 0;
        while (t <= ETHER_RANGE) {
            if (tmx < tmy && tmx < tmz) {
                vx += sx;
                t = tmx;
                tmx += tdx;
            } else if (tmy < tmz) {
                vy += sy;
                t = tmy;
                tmy += tdy;
            } else {
                vz += sz;
                t = tmz;
                tmz += tdz;
            }
            if (MovementHelper.fullyPassable(context, vx, vy, vz)) continue;
            // the block we hit: land on top of it
            if (!standable(context, vx, vy + 1, vz)) return;
            int dist2 = (vx - x) * (vx - x) + (vy + 1 - y) * (vy + 1 - y) + (vz - z) * (vz - z);
            if (dist2 < 25) return;
            res.x = vx;
            res.y = vy + 1;
            res.z = vz;
            res.cost = context.etherwarpCost + Math.sqrt(dist2) * 0.05;
            return;
        }
    }

    /**
     * Etherwarp onto the {@code nth} nearest of the player's etherwarp points that can be seen from
     * feet (x, y, z) - every spot that sees one gets a cheap way onto it.
     */
    public static void pointCost(CalculationContext context, int x, int y, int z, int nth, MutableMoveResult res) {
        res.x = x;
        res.y = y;
        res.z = z;
        res.cost = COST_INF;
        int[][] points = etherwarpPoints;
        if (!context.allowEtherwarp || points.length <= nth) return;
        double ex = x + 0.5, ey = y + EYE, ez = z + 0.5;
        int seen = 0;
        int[] order = nearestFirst(points, ex, ey, ez);
        for (int idx : order) {
            int[] p = points[idx];
            double dist = Math.sqrt((p[0] + 0.5 - ex) * (p[0] + 0.5 - ex) + (p[1] + 1 - ey) * (p[1] + 1 - ey)
                    + (p[2] + 0.5 - ez) * (p[2] + 0.5 - ez));
            if (dist > ETHER_RANGE || dist < 4) continue;
            if (!sees(context, ex, ey, ez, p[0], p[1], p[2])) continue;
            if (seen++ < nth) continue;
            res.x = p[0];
            res.y = p[1] + 1;
            res.z = p[2];
            res.cost = context.etherwarpCost * 0.5 + dist * 0.03;
            return;
        }
    }

    private static int[] nearestFirst(int[][] points, double x, double y, double z) {
        Integer[] idx = new Integer[points.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(d2(points[a], x, y, z), d2(points[b], x, y, z)));
        int[] out = new int[idx.length];
        for (int i = 0; i < idx.length; i++) out[i] = idx[i];
        return out;
    }

    private static double d2(int[] p, double x, double y, double z) {
        return (p[0] - x) * (p[0] - x) + (p[1] - y) * (p[1] - y) + (p[2] - z) * (p[2] - z);
    }

    /** True when the line from the eye to the top of block (bx, by, bz) hits that block first. */
    private static boolean sees(CalculationContext context, double ex, double ey, double ez, int bx, int by, int bz) {
        double tx = bx + 0.5, ty = by + 0.95, tz = bz + 0.5;
        double dist = Math.sqrt((tx - ex) * (tx - ex) + (ty - ey) * (ty - ey) + (tz - ez) * (tz - ez));
        int steps = (int) Math.ceil(dist / 0.25);
        for (int i = 1; i <= steps; i++) {
            double f = i / (double) steps;
            int vx = floor(ex + (tx - ex) * f), vy = floor(ey + (ty - ey) * f), vz = floor(ez + (tz - ez) * f);
            if (vx == bx && vy == by && vz == bz) return standable(context, bx, by + 1, bz);
            if (!MovementHelper.fullyPassable(context, vx, vy, vz)) return false;
        }
        return false;
    }

    private static boolean standable(CalculationContext context, int x, int y, int z) {
        BlockState below = context.get(x, y - 1, z);
        return MovementHelper.canWalkOn(context, x, y - 1, z, below)
                && MovementHelper.fullyPassable(context, x, y, z)
                && MovementHelper.fullyPassable(context, x, y + 1, z);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
