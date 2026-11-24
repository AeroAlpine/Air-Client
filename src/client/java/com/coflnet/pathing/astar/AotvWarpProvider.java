package com.coflnet.pathing.astar;

import com.coflnet.pathing.AotvAssist;
import com.coflnet.pathing.WarpPlanner;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AOTV edges for the A*: teleport along sampled directions and etherwarp onto real block tops.
 * Expensive cases are cached per node; the search decides whether the higher cost is worth it.
 */
public final class AotvWarpProvider implements AStarPathfinder.WarpProvider {
    private static final double EYE = 1.62;
    private final Level level;
    private final LocalPlayer player;
    private final double targetX, targetY, targetZ;
    private final Map<AStarPathfinder.Node, List<AStarPathfinder.Edge>> cache = new HashMap<>();

    public AotvWarpProvider(Level level, LocalPlayer player, BlockPos target) {
        this.level = level;
        this.player = player;
        this.targetX = target.getX() + 0.5;
        this.targetY = target.getY();
        this.targetZ = target.getZ() + 0.5;
    }

    @Override
    public List<AStarPathfinder.Edge> edges(AStarPathfinder.World world,
                                            AStarPathfinder.Node from,
                                            AStarPathfinder.Goal goal) {
        return cache.computeIfAbsent(from, n -> make(world, n));
    }

    private List<AStarPathfinder.Edge> make(AStarPathfinder.World world, AStarPathfinder.Node from) {
        if (player == null || level == null) return List.of();
        int mana = com.coflnet.mining.MiningState.mana();
        if (mana >= 0 && mana < 60) return List.of();
        Vec3 eye = new Vec3(from.x() + 0.5, from.y() + EYE, from.z() + 0.5);
        List<AStarPathfinder.Edge> out = new ArrayList<>();
        teleports(world, from, eye, out);
        etherwarps(world, from, eye, out);
        return out;
    }

    private void teleports(AStarPathfinder.World world, AStarPathfinder.Node from,
                           Vec3 eye, List<AStarPathfinder.Edge> out) {
        int reach = AotvAssist.teleportReach(player);
        if (reach <= 0) return;
        float base = (float) (Math.toDegrees(Math.atan2(targetZ - eye.z, targetX - eye.x)) - 90.0);
        float[] yaws = {0, 25, -25, 50, -50, 90, -90, 135, -135, 180};
        int[] pitches = {0, 20, 35, -15};
        for (float off : yaws) {
            for (int pitch : pitches) {
                Vec3 dir = Vec3.directionFromRotation(pitch, base + off).normalize();
                Vec3 land = WarpPlanner.simulateTeleport(level, eye, dir, reach);
                if (land == null) continue;
                double floor = floorNear(world, land.x, land.z, land.y - 1.0, land.y + 2.0);
                if (Double.isNaN(floor)) continue;
                double dx = land.x - eye.x, dz = land.z - eye.z;
                if (dx * dx + dz * dz < 36.0) continue;
                if (!world.bodyClear(land.x, land.z, floor)) continue;
                AStarPathfinder.Node to = node(land.x, floor, land.z);
                double dist = eye.distanceTo(new Vec3(land.x, floor, land.z));
                double cost = 25.0 + dist * 0.35;
                out.add(new AStarPathfinder.Edge(to, AStarPathfinder.Kind.TELEPORT, cost,
                        new AStarPathfinder.WarpAction(AStarPathfinder.Kind.TELEPORT,
                                eye.x + dir.x * 4, eye.y + dir.y * 4, eye.z + dir.z * 4,
                                dir.x, dir.y, dir.z)));
            }
        }
    }

    private void etherwarps(AStarPathfinder.World world, AStarPathfinder.Node from,
                            Vec3 eye, List<AStarPathfinder.Edge> out) {
        if (!AotvAssist.canEtherwarp(player)) return;
        if (!AotvAssist.etherwarpAllowed()
                || !pingplus.voicechat.client.PlayerSettings.miningBotEtherwarp) return;
        double dx = targetX - eye.x, dz = targetZ - eye.z;
        double flat = Math.hypot(dx, dz);
        if (flat < 2.0) return;
        double ux = dx / flat, uz = dz / flat;
        double[] distances = {8, 14, 20, 28, 36, 44};
        for (double distance : distances) {
            int x = (int) Math.floor(eye.x + ux * distance);
            int z = (int) Math.floor(eye.z + uz * distance);
            if (!world.loaded(x, z)) continue;
            List<Double> surfaces = world.surfaces(x, z, from.y() - 5.0, from.y() + 9.0);
            for (double top : surfaces) {
                if (top < from.y() - 1.0) continue;
                BlockPos target = new BlockPos(x, (int) Math.floor(top - 0.01), z);
                if (!landable(target)) continue;
                Vec3 aim = WarpPlanner.etherwarpAim(level, eye, target, true);
                if (aim == null) continue;
                double x2 = x + 0.5, z2 = z + 0.5;
                if (!world.bodyClear(x2, z2, top)) continue;
                AStarPathfinder.Node to = node(x2, top, z2);
                double d = eye.distanceTo(new Vec3(x2, top, z2));
                double cost = 40.0 + d * 0.4;
                out.add(new AStarPathfinder.Edge(to, AStarPathfinder.Kind.ETHERWARP, cost,
                        new AStarPathfinder.WarpAction(AStarPathfinder.Kind.ETHERWARP,
                                aim.x, aim.y, aim.z, 0, 0, 0)));
            }
        }
    }

    private boolean landable(BlockPos target) {
        BlockPos above = target.above();
        return !level.getBlockState(target).getCollisionShape(level, target).isEmpty()
                && level.getBlockState(above).getCollisionShape(level, above).isEmpty()
                && level.getBlockState(above.above()).getCollisionShape(level, above.above()).isEmpty();
    }

    private static double floorNear(AStarPathfinder.World world, double x, double z,
                                    double minY, double maxY) {
        double best = Double.NaN;
        double bestDistance = Double.MAX_VALUE;
        for (double top : world.surfaces((int) Math.floor(x), (int) Math.floor(z), minY, maxY)) {
            double d = Math.abs(top - (minY + 0.5));
            if (d < bestDistance) {
                bestDistance = d;
                best = top;
            }
        }
        return best;
    }

    private static AStarPathfinder.Node node(double x, double floor, double z) {
        return new AStarPathfinder.Node((int) Math.floor(x), (int) Math.round(floor * 2.0),
                (int) Math.floor(z));
    }
}
