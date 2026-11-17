package com.coflnet.pathing;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * How hard the next part of the path is to walk. Hard spots - a jump over a gap, a 1-block wide
 * corridor, a 1x1 hole to drop through, several jumps up in a row, a high jump onto a ledge - are
 * where a loosely followed path fails: the bot overshoots, bumps and gets stuck. There the bot
 * walks Baritone's path exactly (no sprint, no smoothing, camera along the way) and warps more
 * readily over the hard part instead.
 */
public final class PathDifficulty {

    /** Movements ahead that are rated. */
    private static final int LOOK_AHEAD = 12;
    /** Score at which the path counts as hard. */
    private static final double HARD_SCORE = 4.0;

    private static volatile double score;

    private PathDifficulty() {
    }

    /** 0 = easy, 1 = very hard. */
    public static double level() {
        return Math.min(1.0, score / (HARD_SCORE * 1.5));
    }

    public static boolean isHard() {
        return score >= HARD_SCORE;
    }

    public static double score() {
        return score;
    }

    /** Called a few times a second by {@link PathfinderSystem}. */
    static void update(Level level, IBaritone baritone) {
        boolean wasHard = isHard();
        score = baritone == null || level == null ? 0 : rate(level, baritone);
        if (isHard() != wasHard) {
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[Pathfinder] %s (Schwierigkeit %.1f)", isHard()
                            ? "Schwierige Stelle: genau dem Pfad folgen, kein Sprint" : "Wieder leichter Weg", score));
        }
    }

    static void reset() {
        score = 0;
    }

    private static double rate(Level level, IBaritone baritone) {
        if (!baritone.getPathingBehavior().isPathing()) {
            return 0;
        }
        IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
        IPath path = executor == null ? null : executor.getPath();
        if (path == null) {
            return 0;
        }
        List<IMovement> movements = path.movements();
        int from = Math.max(0, executor.getPosition());
        double total = 0;
        int ascendsInRow = 0;
        for (int i = from; i < movements.size() && i < from + LOOK_AHEAD; i++) {
            IMovement movement = movements.get(i);
            String name = movement.getClass().getSimpleName();
            BlockPos src = movement.getSrc();
            BlockPos dest = movement.getDest();
            // closer spots weigh more than ones at the end of the window
            double weight = 1.0 - 0.5 * (i - from) / (double) LOOK_AHEAD;
            double points = 0;
            switch (name) {
                case "MovementParkour" -> points += 3.0;
                case "MovementHighAscend" -> points += 2.0;
                case "MovementPillar" -> points += 2.0;
                case "MovementAscend" -> {
                    if (halfStep(level, src, dest)) {
                        // stairs / half slabs are a slope, walked at full speed: not hard
                        ascendsInRow = 0;
                        break;
                    }
                    ascendsInRow++;
                    // one step up is normal; jump after jump onto small blocks is not
                    points += ascendsInRow >= 2 ? 1.5 : 0.3;
                }
                case "MovementFall" -> {
                    int drop = src.getY() - dest.getY();
                    points += drop > 3 ? 1.0 : 0.3;
                    if (enclosed(level, dest)) {
                        points += 2.0; // 1x1 hole
                    }
                }
                case "MovementDownward" -> points += enclosed(level, dest) ? 2.0 : 0.5;
                default -> {
                }
            }
            if (!name.equals("MovementAscend")) {
                ascendsInRow = 0;
            }
            if (name.equals("MovementDescend") && halfStep(level, src, dest)) {
                continue; // walking down stairs
            }
            if ((name.equals("MovementTraverse") || name.equals("MovementDiagonal")) && narrow(level, src, dest)) {
                points += 0.6; // 1-block wide corridor or ledge between walls
            }
            total += points * weight;
        }
        return total;
    }

    /** A stair or half slab under one end of the move: half-block steps, no real jump. */
    private static boolean halfStep(Level level, BlockPos src, BlockPos dest) {
        return halfBlock(level, src.below()) || halfBlock(level, dest.below());
    }

    private static boolean halfBlock(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.getBlock() instanceof net.minecraft.world.level.block.StairBlock
                || (state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
                && state.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM);
    }

    /** Walls on all four sides at feet height: a 1x1 shaft. */
    private static boolean enclosed(Level level, BlockPos feet) {
        int walls = 0;
        for (BlockPos side : new BlockPos[]{feet.north(), feet.south(), feet.east(), feet.west()}) {
            if (solid(level, side) || solid(level, side.above())) {
                walls++;
            }
        }
        return walls >= 4;
    }

    /** Both sides across the walking direction are blocked: only one block of room. */
    private static boolean narrow(Level level, BlockPos src, BlockPos dest) {
        int dx = Integer.signum(dest.getX() - src.getX());
        int dz = Integer.signum(dest.getZ() - src.getZ());
        if (dx != 0 && dz != 0) {
            return false; // diagonal moves already need both sides free
        }
        BlockPos left = dest.offset(-dz, 0, dx);
        BlockPos right = dest.offset(dz, 0, -dx);
        boolean leftBlocked = solid(level, left) || solid(level, left.above()) || !solid(level, left.below());
        boolean rightBlocked = solid(level, right) || solid(level, right.above()) || !solid(level, right.below());
        return leftBlocked && rightBlocked;
    }

    private static boolean solid(Level level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }
}
