package com.coflnet.pathing.astar.fuzz;

import com.coflnet.pathing.astar.AStarPathfinder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Headless world for a generated course; collisions use the real block heights. */
public final class FakeWorld implements AStarPathfinder.World {
    private final Course course;
    private final Map<Long, Course.Cell> cells = new HashMap<>();

    public FakeWorld(Course course) {
        this.course = course;
        for (Course.Cell c : course.cells()) {
            cells.put(key(c.x(), c.z()), c);
        }
    }

    public Course course() {
        return course;
    }

    @Override
    public boolean loaded(int x, int z) {
        return Math.abs(x) <= 64 && Math.abs(z) <= 64;
    }

    @Override
    public boolean inBounds(int x, int y, int z) {
        return y >= -64 && y <= 320;
    }

    @Override
    public boolean solid(int x, int y, int z) {
        for (double[] box : collision(x, z)) {
            if (box[0] < y + 1.0 - 1.0E-6 && box[1] > y + 1.0E-6) return true;
        }
        return false;
    }

    @Override
    public List<Double> surfaces(int x, int z, double minY, double maxY) {
        List<Double> out = new ArrayList<>(2);
        for (double[] box : collision(x, z)) {
            if (box[1] >= minY - 1.0E-6 && box[1] <= maxY + 1.0E-6) out.add(box[1]);
        }
        return out;
    }

    @Override
    public boolean bodyClear(double x, double z, double floorY) {
        double[][] offsets = {{-0.28, -0.28}, {-0.28, 0.28}, {0.28, -0.28}, {0.28, 0.28}};
        double top = floorY + 1.7;
        double bottom = floorY + 0.1;
        for (double[] o : offsets) {
            int bx = (int) Math.floor(x + o[0]);
            int bz = (int) Math.floor(z + o[1]);
            for (double[] box : collision(bx, bz)) {
                if (box[1] > bottom + 1.0E-6 && box[0] < top - 1.0E-6) return false;
            }
        }
        return true;
    }

    /** y-ranges of the solid blocks in one column. */
    private List<double[]> collision(int x, int z) {
        Course.Cell cell = cells.get(key(x, z));
        if (cell == null) return List.of();
        double floor = cell.floorY();
        List<double[]> boxes = new ArrayList<>(2);
        switch (cell.block()) {
            case AIR -> {
                return boxes;
            }
            case WALL -> {
                boxes.add(new double[]{floor, floor + 3});
                return boxes;
            }
            case FLOOR -> boxes.add(new double[]{floor - 1, floor});
            case SLAB, STAIRS -> boxes.add(new double[]{floor - 1, floor - 0.5});
            default -> boxes.add(new double[]{floor - 1, floor});
        }
        for (int h = 1; h <= cell.step(); h++) {
            boxes.add(new double[]{floor + h - 1, floor + h});
        }
        return boxes;
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
