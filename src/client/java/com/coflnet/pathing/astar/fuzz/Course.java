package com.coflnet.pathing.astar.fuzz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, solvable jump-and-run courses, always built at height 100. Drops, descending
 * stairs and corners have NO side walls: falling is possible and counts as a failure.
 * Obstacles: slab runs, staircases up and down around corners, one-block steps, 2-block
 * precision jumps and a final open-edge drop. Level 5 adds random speed/jump effects.
 */
public record Course(long seed, int baseY, int startX, int startZ, int goalX, int goalZ,
                     int goalY, boolean allowJump, int level, int speedLevel, int jumpLevel,
                     List<Cell> cells, List<Fill> fills) {

    public enum Block {
        FLOOR("minecraft:stone"),
        SLAB("minecraft:smooth_stone_slab[type=bottom]"),
        STAIRS("minecraft:stone_brick_stairs[facing=east,half=bottom]"),
        STEP("minecraft:stone"),
        WALL("minecraft:stone"),
        AIR("minecraft:air");

        public final String command;

        Block(String command) {
            this.command = command;
        }
    }

    /** One column of the course. {@code step} = number of blocks stacked on the floor. */
    public record Cell(int x, int z, Block block, double top, double floorY, int step) {
    }

    public record Fill(String block, int x1, int y1, int z1, int x2, int y2, int z2) {
    }

    public static Course generate(long seed, int baseY, boolean allowJump, int level) {
        long s = mix(seed ^ 0x9E3779B97F4A7C15L);
        level = Math.max(1, Math.min(5, level));
        int goalX = 22 + level * 6;
        int finalDropStart = goalX - 3;
        int finalDrop = 3;

        int turn1 = 8 + (int) Math.floorMod(s, 4); s = mix(s);
        int turn1Len = 4 + level / 2; s = mix(s);
        int turn2 = 16 + (int) Math.floorMod(s, 6) + level; s = mix(s);
        int turn2Len = 4; s = mix(s);

        int slabStart = 4; s = mix(s);
        int slabLen = 3 + level / 2; s = mix(s);
        int stairUpStart = slabStart + slabLen + 2; s = mix(s);
        int stairDownStart = stairUpStart + 3; s = mix(s);
        int gapX = stairDownStart + 4; s = mix(s);
        int stepX = gapX + 3; s = mix(s);
        if (stepX >= turn2) stepX = turn2 - 1;

        if (!allowJump) {
            stairUpStart = 10_000;
            stairDownStart = 10_000;
            gapX = 10_000;
            stepX = 10_000;
        }

        int speedLevel = level >= 5 ? (int) (mix(s) & 3) : 0; s = mix(s);
        int jumpLevel = level >= 5 ? (int) (mix(s) & 3) : 0; s = mix(s);

        // Path columns, with both cells at a turn boundary so turns are straight moves.
        Map<Long, Cell> path = new LinkedHashMap<>();
        for (int x = 0; x <= goalX; x++) {
            int z = zAt(x, level, turn1, turn1Len, turn2, turn2Len);
            addPath(path, x, z);
            if (x > 0) {
                int prevZ = zAt(x - 1, level, turn1, turn1Len, turn2, turn2Len);
                if (prevZ != z) addPath(path, x, prevZ);
            }
        }

        Map<Long, Cell> byColumn = new LinkedHashMap<>();
        for (Cell c : path.values()) {
            int x = c.x();
            int z = c.z();
            double floorY = x >= finalDropStart ? baseY - finalDrop : baseY;
            Block block = Block.FLOOR;
            double top = floorY;
            int step = 0;
            if (x >= slabStart && x < slabStart + slabLen && x < finalDropStart) {
                block = Block.SLAB;
                top = floorY - 0.5;
            } else if (x >= stairUpStart && x < stairUpStart + 3 && x < finalDropStart) {
                step = x - stairUpStart + 1;
                top = floorY + step;
            } else if (x >= stairDownStart && x < stairDownStart + 3 && x < finalDropStart) {
                step = stairDownStart + 3 - x;
                top = floorY + step;
            } else if (x >= gapX && x < gapX + 2 && x < finalDropStart) {
                block = Block.AIR;
                top = Double.NaN;
            } else if (allowJump && x == stepX && x < finalDropStart) {
                step = 1;
                top = floorY + 1;
            }
            byColumn.put(key(x, z), new Cell(x, z, block, top, floorY, step));
        }

        // Walls where falling must not bail the level out.
        for (Cell c : path.values()) {
            int x = c.x();
            int z = c.z();
            if (openZone(x, stairUpStart, stairDownStart, finalDropStart)) continue;
            double f = x >= finalDropStart ? baseY - finalDrop : baseY;
            if (!path.containsKey(key(x, z + 1))) {
                byColumn.putIfAbsent(key(x, z + 1), new Cell(x, z + 1, Block.WALL, f + 3, f, 0));
            }
            if (!path.containsKey(key(x, z - 1))) {
                byColumn.putIfAbsent(key(x, z - 1), new Cell(x, z - 1, Block.WALL, f + 3, f, 0));
            }
        }

        List<Fill> fills = new ArrayList<>();
        for (Cell c : byColumn.values()) {
            switch (c.block()) {
                case AIR -> fills.add(new Fill(Block.AIR.command, c.x(), (int) c.floorY() - 1,
                        c.z(), c.x(), (int) c.floorY() + 2, c.z()));
                case FLOOR -> fills.add(new Fill(Block.FLOOR.command, c.x(), (int) c.floorY() - 1,
                        c.z(), c.x(), (int) c.floorY() - 1, c.z()));
                case SLAB, STAIRS -> fills.add(new Fill(c.block().command, c.x(), (int) c.floorY() - 1,
                        c.z(), c.x(), (int) c.floorY() - 1, c.z()));
                case WALL -> fills.add(new Fill(Block.WALL.command, c.x(), (int) c.floorY(),
                        c.z(), c.x(), (int) c.floorY() + 2, c.z()));
                case STEP -> fills.add(new Fill(Block.STEP.command, c.x(), (int) c.floorY(),
                        c.z(), c.x(), (int) c.floorY(), c.z()));
            }
            for (int h = 1; h <= c.step(); h++) {
                fills.add(new Fill(Block.STEP.command, c.x(), (int) c.floorY() + h - 1,
                        c.z(), c.x(), (int) c.floorY() + h - 1, c.z()));
            }
        }
        return new Course(seed, baseY, 0, 0, goalX, 0, baseY - finalDrop, allowJump, level,
                speedLevel, jumpLevel, new ArrayList<>(byColumn.values()), fills);
    }

    private static int zAt(int x, int level, int t1, int t1l, int t2, int t2l) {
        if (level >= 2 && x >= t1 && x < t1 + t1l) return 1;
        if (level >= 4 && x >= t2 && x < t2 + t2l) return -1;
        return 0;
    }

    private static boolean openZone(int x, int stairUpStart, int stairDownStart, int finalDropStart) {
        return x >= stairUpStart && x < stairDownStart + 3 || x >= finalDropStart;
    }

    private static void addPath(Map<Long, Cell> path, int x, int z) {
        path.putIfAbsent(key(x, z), new Cell(x, z, Block.FLOOR, 0, 0, 0));
    }

    /** SplitMix64 step. */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
