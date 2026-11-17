package com.coflnet.pathing;

import baritone.api.pathing.goals.Goal;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.PriorityQueue;

/**
 * Small walking search on the block grid for the warp planner: how many ticks it really takes
 * to walk from a spot to the goal - around walls, over holes, up steps (as high as the player
 * can jump) and down drops. The straight line ignored walls: the planner teleported next to or
 * onto a wall close to the goal, and from there the walk went nowhere.
 *
 * <p>Deliberately simple (no breaking, placing, parkour or swimming) and limited in nodes, so a
 * planning run can check several landing spots. One instance per planning run: block reads and
 * results are shared between the checks. Not found = not reachable for this purpose.
 */
public final class WalkSearch {

    private static final double STRAIGHT = 3.6;
    private static final double DIAGONAL = 5.1;
    private static final double DOWN_PER_BLOCK = 1.5;
    private static final int MAX_DOWN = 4;
    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /**
     * @param maxUp     highest step the player can jump onto (blocks)
     * @param jumpTicks what one jump costs (longer with Jump Boost: more time in the air)
     * @param maxNodes  search limit per check
     */
    public record Settings(int maxUp, double jumpTicks, int maxNodes) {
    }

    private record Node(long key, BlockPos pos, double g, double f) {
    }

    /** What a block is for walking: 1 = passable, 2 = solid, 0 = neither (lava, outside the world). */
    @FunctionalInterface
    public interface Blocks {
        byte at(BlockPos pos);
    }

    private final Blocks world;
    private final Goal goal;
    private final Settings settings;
    private final List<BlockPos> centers;
    /** Block lookups of this run: 1 = passable, 2 = solid, 0 = lava / outside the world. */
    private final Long2ByteOpenHashMap blocks = new Long2ByteOpenHashMap();
    private final Long2DoubleOpenHashMap results = new Long2DoubleOpenHashMap();

    public WalkSearch(Level level, Goal goal, Settings settings) {
        this(pos -> read(level, pos), goal, settings);
    }

    public WalkSearch(Blocks world, Goal goal, Settings settings) {
        this.world = world;
        this.goal = goal;
        this.settings = settings;
        this.centers = WarpPlanner.goalCenters(goal);
        this.blocks.defaultReturnValue((byte) -1);
        this.results.defaultReturnValue(Double.NEGATIVE_INFINITY);
    }

    /**
     * Ticks (vanilla speed) to walk from {@code start} (feet, may hang up to 3 blocks above the
     * ground) into the goal, or {@link Double#NaN} when no way was found within the limit.
     */
    public double ticksToGoal(BlockPos start) {
        BlockPos from = standOn(start);
        if (from == null) {
            return Double.NaN;
        }
        double cached = results.get(from.asLong());
        if (cached != Double.NEGATIVE_INFINITY) {
            return cached;
        }
        double ticks = search(from);
        results.put(from.asLong(), ticks);
        return ticks;
    }

    private double search(BlockPos from) {
        if (goal.isInGoal(from)) {
            return 0;
        }
        Long2DoubleOpenHashMap best = new Long2DoubleOpenHashMap();
        best.defaultReturnValue(Double.MAX_VALUE);
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        open.add(new Node(from.asLong(), from, 0, heuristic(from)));
        best.put(from.asLong(), 0);
        int expanded = 0;
        while (!open.isEmpty() && expanded < settings.maxNodes()) {
            Node node = open.poll();
            if (node.g() > best.get(node.key())) {
                continue; // outdated entry
            }
            if (goal.isInGoal(node.pos())) {
                return node.g();
            }
            expanded++;
            BlockPos a = node.pos();
            for (int[] d : DIRS) {
                double flat = d[0] != 0 && d[1] != 0 ? DIAGONAL : STRAIGHT;
                // up (jump), same height, down (drop)
                for (int dy = settings.maxUp(); dy >= -MAX_DOWN; dy--) {
                    BlockPos b = a.offset(d[0], dy, d[1]);
                    double cost = moveCost(a, b, d, dy, flat);
                    if (Double.isNaN(cost)) {
                        continue;
                    }
                    double g = node.g() + cost;
                    long key = b.asLong();
                    if (g < best.get(key)) {
                        best.put(key, g);
                        open.add(new Node(key, b, g, g + heuristic(b)));
                    }
                }
            }
        }
        return Double.NaN;
    }

    /** Cost of the move a -> b, NaN when it is not possible. */
    private double moveCost(BlockPos a, BlockPos b, int[] d, int dy, double flat) {
        if (!standable(b)) {
            return Double.NaN;
        }
        int top = Math.max(a.getY(), b.getY());
        // room for the body over the whole move: above the start for a jump, over the target
        // column for a drop
        for (int y = a.getY() + 2; y <= top + 1; y++) {
            if (!passable(new BlockPos(a.getX(), y, a.getZ()))) {
                return Double.NaN;
            }
        }
        for (int y = b.getY() + 2; y <= top + 1; y++) {
            if (!passable(new BlockPos(b.getX(), y, b.getZ()))) {
                return Double.NaN;
            }
        }
        if (d[0] != 0 && d[1] != 0) {
            // no cutting corners: both side cells must let the body through
            for (int y = top; y <= top + 1; y++) {
                if (!passable(new BlockPos(a.getX() + d[0], y, a.getZ()))
                        || !passable(new BlockPos(a.getX(), y, a.getZ() + d[1]))) {
                    return Double.NaN;
                }
            }
        }
        if (dy > 0) {
            return flat + settings.jumpTicks() + 2.0 * (dy - 1);
        }
        if (dy < 0) {
            return flat + DOWN_PER_BLOCK * -dy;
        }
        return flat;
    }

    /** Feet block on the ground at or up to 3 blocks below {@code pos}, null when there is none. */
    BlockPos standOn(BlockPos pos) {
        for (int i = 0; i <= 3; i++) {
            BlockPos p = pos.below(i);
            if (standable(p)) {
                return p;
            }
        }
        return null;
    }

    private boolean standable(BlockPos feet) {
        return info(feet.below()) == 2 && passable(feet) && passable(feet.above());
    }

    private boolean passable(BlockPos pos) {
        return info(pos) == 1;
    }

    private byte info(BlockPos pos) {
        long key = pos.asLong();
        byte known = blocks.get(key);
        if (known >= 0) {
            return known;
        }
        byte value = world.at(pos);
        blocks.put(key, value);
        return value;
    }

    private static byte read(Level level, BlockPos pos) {
        if (!level.isInWorldBounds(pos)) {
            return 0;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getFluidState().is(FluidTags.LAVA)) {
            return 0;
        }
        return (byte) (state.getCollisionShape(level, pos).isEmpty() ? 1 : 2);
    }

    /** Admissible estimate: straight-line walking without detours. */
    private double heuristic(BlockPos pos) {
        double best = Double.MAX_VALUE;
        for (BlockPos center : centers) {
            double flat = Math.hypot(center.getX() - pos.getX(), center.getZ() - pos.getZ());
            best = Math.min(best, Math.max(0, flat - 3.0) * STRAIGHT);
        }
        return best == Double.MAX_VALUE ? 0 : best;
    }
}
