package com.coflnet.pathing;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.BetterBlockPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * "Get close to the target AND see it". GoalNear alone accepts any block
 * within the radius, also the one on the wrong side of a wall, where the bot
 * then stands and can not hit. The standing spots are computed once on the
 * client thread (line of sight needs the world); Baritone's thread only does
 * set lookups.
 */
public final class VisibleGoal implements Goal {
    private static final double EYE = 1.62;

    private final GoalNear near;
    private final LongOpenHashSet spots;
    private final BlockPos center;

    private VisibleGoal(BlockPos center, int radius, LongOpenHashSet spots) {
        this.center = center;
        this.near = new GoalNear(center, radius);
        this.spots = spots;
    }

    /** Extra rings searched when leaves or walls hide the target from every close spot. */
    private static final int EXTRA_RADIUS = 2;

    /**
     * Goal for {@code target}: standable blocks within {@code radius} that see
     * it. Falls back to a plain GoalNear when no such block exists (target in
     * a hole, unloaded area ...), so the bot at least gets close.
     */
    public static Goal of(Level level, Entity target, int radius) {
        return of(level, target, radius, radius + 1.5);
    }

    /**
     * Like {@link #of(Level, Entity, int)}, but a spot also has to be within {@code maxReach}
     * (eyes to the target). When leaves or walls hide the target from every spot within
     * {@code radius}, up to {@value #EXTRA_RADIUS} more rings are searched before giving up,
     * so the bot walks around the leaves instead of standing behind them.
     */
    public static Goal of(Level level, Entity target, int radius, double maxReach) {
        BlockPos center = target.blockPosition();
        LongOpenHashSet spots = new LongOpenHashSet();
        int used = radius;
        for (int r = radius; r <= radius + EXTRA_RADIUS && spots.isEmpty(); r++) {
            used = r;
            collect(level, target, center, r, maxReach, spots);
        }
        if (spots.isEmpty()) {
            return new GoalNear(center, radius);
        }
        return new VisibleGoal(center, used, spots);
    }

    private static void collect(Level level, Entity target, BlockPos center, int radius, double maxReach,
                                LongOpenHashSet spots) {
        int r2 = radius * radius;
        double reach2 = maxReach * maxReach;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) {
                        continue;
                    }
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!standable(level, pos)) {
                        continue;
                    }
                    Vec3 eye = new Vec3(pos.getX() + 0.5, pos.getY() + EYE, pos.getZ() + 0.5);
                    if (eye.distanceToSqr(target.getBoundingBox().getCenter()) > reach2) {
                        continue;
                    }
                    if (seesAny(level, eye, target)) {
                        spots.add(BetterBlockPos.longHash(pos.getX(), pos.getY(), pos.getZ()));
                    }
                }
            }
        }
    }

    /**
     * Can a hit from {@code eye} reach {@code target}? Leaves, glass and other blocks with a
     * collision box block it; any of the target's centre, head or feet being visible is enough.
     */
    public static boolean seesAny(Level level, Vec3 eye, Entity target) {
        var box = target.getBoundingBox();
        Vec3 center = box.getCenter();
        Vec3[] aims = {
                center,
                new Vec3(center.x, box.maxY - 0.15, center.z),
                new Vec3(center.x, box.minY + 0.25, center.z)
        };
        for (Vec3 aim : aims) {
            BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, target));
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    static boolean standable(Level level, BlockPos feet) {
        if (!level.isLoaded(feet)) {
            return false;
        }
        BlockPos floor = feet.below();
        return !level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }

    static boolean sees(Level level, BlockPos feet, Vec3 aim, Entity target) {
        Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + EYE, feet.getZ() + 0.5);
        BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, target));
        return hit.getType() == HitResult.Type.MISS;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return spots.contains(BetterBlockPos.longHash(x, y, z));
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return near.heuristic(x, y, z);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VisibleGoal other && other.center.equals(center) && other.spots.equals(spots);
    }

    @Override
    public int hashCode() {
        return center.hashCode() * 31 + spots.size();
    }

    @Override
    public String toString() {
        return "VisibleGoal{" + center.toShortString() + ", spots=" + spots.size() + "}";
    }
}
