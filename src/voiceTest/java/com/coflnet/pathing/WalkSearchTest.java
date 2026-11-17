package com.coflnet.pathing;

import baritone.api.pathing.goals.GoalNear;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;

/**
 * The warp planner's walking search on a small block grid: a wall between landing spot and goal
 * means a detour, a spot on top of a high wall means no way at all (that was the bug: the
 * straight line said "close", the bot teleported onto / next to the wall and got stuck).
 */
public final class WalkSearchTest {
    private WalkSearchTest() {
    }

    private static final int FLOOR = 63;

    public static void main(String[] args) {
        Set<Long> solid = new HashSet<>();
        for (int x = -25; x <= 25; x++) {
            for (int z = -25; z <= 25; z++) {
                solid.add(BlockPos.asLong(x, FLOOR, z));
            }
        }
        // a wall at x = 5, 7 blocks high, with a door at z = 15..16
        for (int z = -25; z <= 25; z++) {
            if (z == 15 || z == 16) {
                continue;
            }
            for (int y = FLOOR + 1; y <= FLOOR + 7; y++) {
                solid.add(BlockPos.asLong(5, y, z));
            }
        }
        // a one-block step in front of the start
        solid.add(BlockPos.asLong(-3, FLOOR + 1, 0));
        WalkSearch.Blocks world = pos -> {
            if (pos.getY() < FLOOR - 5 || pos.getY() > FLOOR + 30) {
                return 0;
            }
            return solid.contains(pos.asLong()) ? (byte) 2 : (byte) 1;
        };
        GoalNear goal = new GoalNear(new BlockPos(10, FLOOR + 1, 0), 2);
        WalkSearch.Settings settings = new WalkSearch.Settings(1, 6.0, 1500);

        WalkSearch search = new WalkSearch(world, goal, settings);
        double detour = search.ticksToGoal(new BlockPos(0, FLOOR + 1, 0));
        check(!Double.isNaN(detour), "goal behind the wall is reachable through the door");
        // straight line is 10 blocks (~36 ticks); through the door it is about 32 blocks
        check(detour > 90, "the walk goes round the wall, not through it: " + detour);

        double onWall = search.ticksToGoal(new BlockPos(5, FLOOR + 8, 0));
        check(Double.isNaN(onWall), "on top of the 7-block wall there is no way down: " + onWall);

        double nextToGoal = search.ticksToGoal(new BlockPos(9, FLOOR + 1, 1));
        check(nextToGoal == 0, "a spot inside the goal costs nothing");

        double overStep = search.ticksToGoal(new BlockPos(-5, FLOOR + 1, 0));
        check(!Double.isNaN(overStep) && overStep > detour, "the step is jumped onto, further away costs more");

        // the same spot answered from the cache
        check(search.ticksToGoal(new BlockPos(0, FLOOR + 1, 0)) == detour, "cached result");

        System.out.println("PASS: walking search detours walls, rejects unreachable ledges, jumps steps");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
