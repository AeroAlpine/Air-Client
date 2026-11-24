package com.coflnet.pathing.astar;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Live world for the incremental A*, with a small per-column cache. Client thread only. */
public final class MinecraftWorld implements AStarPathfinder.World {
    private final Level level;
    private final Map<Long, Double> topCache = new HashMap<>();
    private final Map<Long, Boolean> solidCache = new HashMap<>();

    public MinecraftWorld(Level level) {
        this.level = level;
    }

    public void clearCache() {
        topCache.clear();
        solidCache.clear();
    }

    @Override
    public boolean loaded(int x, int z) {
        return level.hasChunk(x >> 4, z >> 4);
    }

    @Override
    public boolean inBounds(int x, int y, int z) {
        return level.isInWorldBounds(new BlockPos(x, y, z));
    }

    @Override
    public boolean solid(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        Boolean known = solidCache.get(key);
        if (known != null) return known;
        BlockPos pos = new BlockPos(x, y, z);
        boolean solid = !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
        solidCache.put(key, solid);
        return solid;
    }

    @Override
    public List<Double> surfaces(int x, int z, double minY, double maxY) {
        if (!loaded(x, z)) return List.of();
        int from = (int) Math.floor(minY);
        int to = (int) Math.floor(maxY);
        List<Double> out = new ArrayList<>(2);
        for (int y = to; y >= from; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            long key = pos.asLong();
            Double cached = topCache.get(key);
            double top;
            if (cached != null) {
                top = cached;
            } else {
                BlockState state = level.getBlockState(pos);
                VoxelShape shape = state.getCollisionShape(level, pos);
                top = shape.isEmpty() ? Double.NaN : shape.max(net.minecraft.core.Direction.Axis.Y) + y;
                topCache.put(key, top);
            }
            if (Double.isNaN(top)) continue;
            if (top >= minY - 1.0E-6 && top <= maxY + 1.0E-6) out.add(top);
        }
        return out;
    }

    @Override
    public boolean bodyClear(double x, double z, double floorY) {
        AABB box = new AABB(x - 0.3, floorY + 0.02, z - 0.3,
                x + 0.3, floorY + 1.79, z + 0.3);
        return !level.getBlockCollisions(null, box).iterator().hasNext();
    }
}
