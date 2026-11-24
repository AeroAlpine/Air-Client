package com.coflnet.pathing.astar;

import com.coflnet.pathing.AotvAssist;
import com.coflnet.pathing.PathEngine;
import com.coflnet.pathing.PathfinderConfig;
import com.coflnet.pathing.PlayerMovementStats;
import com.coflnet.pathing.WarpPlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Camera + keys executor for the new A* path. It never talks to Baritone. Walking uses a look
 * point along the smoothed path; jumps fire only at a jump edge or a real wall, drops keep their
 * momentum, and teleport/etherwarp edges are handed to AotvAssist.
 */
public final class AStarWalk {
    private BlockPos goal;
    private int radius;
    private boolean warps;
    private PathEngine.Result result = PathEngine.Result.IDLE;
    private String status = "";
    private AStarPathfinder.Path path;
    private int index;
    private Vec3 look;
    private long lastProgressAt;
    private long startedAt;
    private Vec3 lastPos = Vec3.ZERO;
    private int replans;
    private boolean warping;
    private boolean ownsKeys;
    private int jumpHold;
    private boolean testTeleport;

    public boolean same(BlockPos target, int r, boolean allowWarps) {
        return goal != null && goal.equals(target) && radius == r && warps == allowWarps;
    }

    /** Combat retargets a moving mob often; a nearby new block keeps the current path. */
    public void goTo(BlockPos target, int r, boolean allowWarps) {
        if (target == null) return;
        if (goal != null && goal.distSqr(target) <= 9 && radius == Math.max(0, r) && warps == allowWarps) {
            goal = target;
            return;
        }
        start(target, r, allowWarps);
    }

    public void allowShortcuts(boolean on) {
    }

    public void allowTeleports(boolean on) {
    }

    public boolean cameraCritical() {
        return aimingWarp();
    }

    public void start(BlockPos target, int r, boolean allowWarps) {
        if (same(target, r, allowWarps)) return;
        stop();
        goal = target;
        radius = Math.max(0, r);
        warps = allowWarps;
        result = PathEngine.Result.PLANNING;
        status = "Planning";
        startedAt = com.coflnet.pathing.sim.SimClock.millis();
        lastProgressAt = startedAt;
        lastPos = playerPos();
        replans = 0;
        plan();
    }

    public PathEngine.Result tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (goal == null || player == null || mc.level == null) {
            stop();
            return PathEngine.Result.IDLE;
        }
        if (result == PathEngine.Result.ARRIVED || result == PathEngine.Result.FAILED) {
            release(mc);
            return result;
        }
        if (inGoal(player)) {
            release(mc);
            look = null;
            result = PathEngine.Result.ARRIVED;
            status = "Arrived";
            System.out.println("[AStarWalk] arrived at " + goal.toShortString());
            return result;
        }
        if (warping) {
            if (AotvAssist.isBusy()) return PathEngine.Result.RUNNING;
            warping = false;
            index++;
            if (index >= path.nodes().size()) {
                result = PathEngine.Result.RUNNING;
            }
            lastProgressAt = com.coflnet.pathing.sim.SimClock.millis();
        }
        if (path == null || !path.found()) {
            fail("No way");
            return result;
        }
        if (index >= path.nodes().size() - 1) {
            fail("end of path");
            return result;
        }
        long now = com.coflnet.pathing.sim.SimClock.millis();
        if (player.position().distanceTo(lastPos) > 0.12) {
            lastPos = player.position();
            lastProgressAt = now;
        }
        if (now - lastProgressAt > 5_000L && !warping) {
            if (++replans <= 2) {
                status = "Replanning";
                plan();
                return result;
            }
            fail("stuck");
            return result;
        }
        AStarPathfinder.Kind edge = path.kinds().get(index);
        if (edge == AStarPathfinder.Kind.TELEPORT || edge == AStarPathfinder.Kind.ETHERWARP) {
            if (executeWarp(player)) return PathEngine.Result.RUNNING;
            fail("warp not possible");
            return result;
        }
        follow(mc, player, edge, now);
        return result;
    }

    private void follow(Minecraft mc, LocalPlayer player, AStarPathfinder.Kind edge, long now) {
        AStarPathfinder.Node to = path.nodes().get(index + 1);
        Vec3 feet = player.position();
        Vec3 target = new Vec3(to.x() + 0.5, to.y(), to.z() + 0.5);
        Vec3 ahead = lookAhead(player);
        look = ahead.add(0, 1.45, 0);

        double flat = Math.hypot(feet.x - target.x, feet.z - target.z);
        double dy = target.y - feet.y;
        if (flat < 0.55 && Math.abs(dy) < 0.85 && player.onGround()) {
            index++;
            jumpHold = 0;
            if (index >= path.nodes().size() - 1) {
                // let the arrival check run on the next tick
                status = "Arriving";
            } else {
                status = "Walking";
            }
            return;
        }

        float yaw = (float) (Math.toDegrees(Math.atan2(target.z - feet.z, target.x - feet.x)) - 90.0);
        float off = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - player.getYRot()));
        boolean forward = off < 55f;
        boolean jump = false;
        if (jumpHold > 0) {
            jump = true;
            jumpHold--;
        } else if (player.onGround()) {
            if (edge == AStarPathfinder.Kind.JUMP && flat < 1.8) {
                jump = true;
            } else if (forward && player.horizontalCollision && dy > 0.25) {
                jump = true;
            }
            if (jump) jumpHold = 2;
        }
        boolean sprint = forward && off < 20f && flat > 2.5
                && edge != AStarPathfinder.Kind.JUMP && PathfinderConfig.get().allowSprint;
        mc.options.keyUp.setDown(forward);
        mc.options.keySprint.setDown(sprint);
        mc.options.keyJump.setDown(jump);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyDown.setDown(false);
        ownsKeys = true;
        status = edge == AStarPathfinder.Kind.JUMP ? "Jumping" : edge == AStarPathfinder.Kind.DROP ? "Dropping" : "Walking";
    }

    private Vec3 lookAhead(LocalPlayer player) {
        Vec3 feet = player.position();
        double total = 0;
        Vec3 last = new Vec3(path.nodes().get(index).x() + 0.5, path.nodes().get(index).y(), path.nodes().get(index).z() + 0.5);
        for (int i = index + 1; i < Math.min(path.nodes().size(), index + 6); i++) {
            AStarPathfinder.Node n = path.nodes().get(i);
            Vec3 p = new Vec3(n.x() + 0.5, n.y(), n.z() + 0.5);
            total += last.distanceTo(p);
            if (total >= com.coflnet.pathing.MovementStyle.lookAheadBlocks()) return p;
            last = p;
        }
        return last;
    }

    public void setTestTeleport(boolean on) {
        testTeleport = on;
    }

    private boolean executeWarp(LocalPlayer player) {
        AStarPathfinder.WarpAction action = path.warps().get(index);
        if (action == null) return false;
        AStarPathfinder.Node to = path.nodes().get(index + 1);
        Vec3 landing = new Vec3(to.x() + 0.5, to.y(), to.z() + 0.5);
        if (action.kind() == AStarPathfinder.Kind.TELEPORT) {
            Vec3 dir = new Vec3(action.dirX(), action.dirY(), action.dirZ()).normalize();
            Vec3 aim = new Vec3(action.aimX(), action.aimY(), action.aimZ());
            if (!AotvAssist.execute(List.of(new WarpPlanner.Hop(WarpPlanner.Kind.TELEPORT,
                    aim, landing, false, dir)), false)) return false;
        } else {
            Vec3 aim = new Vec3(action.aimX(), action.aimY(), action.aimZ());
            if (!AotvAssist.execute(List.of(new WarpPlanner.Hop(WarpPlanner.Kind.ETHERWARP,
                    aim, landing, false, null)), false)) return false;
        }
        warping = true;
        status = "Warping";
        releaseKeysOnly(Minecraft.getInstance());
        return true;
    }

    private void plan() {
        Minecraft mc = Minecraft.getInstance();
        if (goal == null || mc.player == null || mc.level == null) {
            result = PathEngine.Result.FAILED;
            status = "No world";
            return;
        }
        PlayerMovementStats stats = PlayerMovementStats.read(mc.player);
        AStarPathfinder.Settings settings = new AStarPathfinder.Settings(
                Math.max(0.7, stats.jumpHeight()), Math.max(0.5, stats.stepHeight()),
                4.0, 80_000, 5.0, 7.0, 18.0, 2.5, 25.0, 40.0);
        MinecraftWorld world = new MinecraftWorld(mc.level);
        AStarPathfinder.WarpProvider warpProvider = warps
                ? new AotvWarpProvider(mc.level, mc.player, goal) : AStarPathfinder.WarpProvider.NONE;
        AStarPathfinder search = new AStarPathfinder(world, settings, warpProvider);
        int gx = goal.getX(), gy = goal.getY(), gz = goal.getZ();
        int rr = Math.max(0, radius);
        AStarPathfinder.Goal g = new AStarPathfinder.Goal() {
            @Override
            public boolean reached(AStarPathfinder.Node node) {
                double dx = node.x() - gx, dy = node.y() - gy, dz = node.z() - gz;
                return dx * dx + dy * dy + dz * dz <= (double) rr * rr + 0.25;
            }

            @Override
            public double heuristic(AStarPathfinder.Node node) {
                double dx = node.x() - gx, dy = node.y() - gy, dz = node.z() - gz;
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz) - rr - 1.0;
                // 1.5 ticks per block is below both walking and the per-block teleport price.
                return Math.max(0, d) * 1.5;
            }
        };
        Vec3 feet = mc.player.position();
        AStarPathfinder.Path raw = search.find(feet.x, feet.y, feet.z, g);
        if (!raw.found()) {
            path = raw;
            result = PathEngine.Result.FAILED;
            status = "No way";
            System.out.println("[AStarWalk] no way to " + goal.toShortString());
            return;
        }
        path = AStarSmoother.smooth(world, raw);
        System.out.println("[AStarWalk] " + goal.toShortString() + " nodes " + path.nodes().size()
                + " cost " + String.format(java.util.Locale.ROOT, "%.1f", path.cost())
                + " warps " + path.warps().stream().filter(java.util.Objects::nonNull).count());
        index = 0;
        result = PathEngine.Result.RUNNING;
        status = "Walking";
        List<AStarPathfinder.Node> nodes = path.nodes();
        if (nodes.size() > 1) {
            AStarPathfinder.Node n = nodes.get(1);
            look = new Vec3(n.x() + 0.5, n.y() + 1.4, n.z() + 0.5);
        }
    }

    private boolean inGoal(LocalPlayer player) {
        if (goal == null) return true;
        int r = Math.max(0, radius);
        Vec3 center = new Vec3(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5);
        return player.position().distanceTo(center) <= Math.max(0.75, r);
    }

    public Vec3 lookPoint() {
        return look;
    }

    public boolean aimingWarp() {
        return warping || AotvAssist.isBusy();
    }

    public double warpAimSpeedDegPerSecond() {
        return PathfinderConfig.get().teleportRotationSpeed;
    }

    public String status() {
        return status;
    }

    public int pathNodeCount() {
        return path == null ? 0 : path.nodes().size();
    }

    public int pathIndex() {
        return index;
    }

    public boolean busy() {
        return result == PathEngine.Result.PLANNING || result == PathEngine.Result.RUNNING;
    }

    public void stop() {
        releaseKeysOnly(Minecraft.getInstance());
        goal = null;
        path = null;
        index = 0;
        look = null;
        result = PathEngine.Result.IDLE;
        status = "";
        warping = false;
    }

    public void releaseKeys(Minecraft mc) {
        releaseKeysOnly(mc);
    }

    private void releaseKeysOnly(Minecraft mc) {
        if (!ownsKeys || mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
        ownsKeys = false;
        jumpHold = 0;
    }

    private void release(Minecraft mc) {
        releaseKeysOnly(mc);
    }

    private void fail(String why) {
        releaseKeysOnly(Minecraft.getInstance());
        result = PathEngine.Result.FAILED;
        status = String.format(Locale.ROOT, "No way: %s", why);
    }

    private static Vec3 playerPos() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? Vec3.ZERO : player.position();
    }
}
