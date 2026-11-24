package com.coflnet.pathing;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Movement through Baritone's own walker. Baritone owns W/A/S/D, jumping and the
 * silent server rotation; this class only watches the goal, fires an AOTV teleport
 * on a straight stretch, and exposes a smooth camera point along Baritone's path.
 */
public final class BaritoneWalk {
    static {
        BaritoneWarpExecutor.install();
    }

    private BlockPos goal;
    private int radius;
    private boolean warps;
    private PathEngine.Result result = PathEngine.Result.IDLE;
    private String status = "";
    private long startedAt;
    private long lastProgressAt;
    private long planningSince;
    private long replanAt;
    private Vec3 lastPos = Vec3.ZERO;
    private Vec3 look;
    private int replans;

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
        long now = com.coflnet.pathing.sim.SimClock.millis();
        startedAt = now;
        lastProgressAt = now;
        planningSince = now;
        replanAt = 0;
        replans = 0;
        lastPos = playerPos();
        configure();
        beginPath();
    }

    public PathEngine.Result tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (goal == null || player == null || mc.level == null) {
            stop();
            return PathEngine.Result.IDLE;
        }
        long now = com.coflnet.pathing.sim.SimClock.millis();
        if (result == PathEngine.Result.ARRIVED || result == PathEngine.Result.FAILED) {
            return result;
        }
        if (inGoal(player)) {
            // an exact goal (radius 0: a pillar top, a spot): there means standing still on it. At
            // SkyBlock speed the slide after the last step carried the bot over the edge.
            Vec3 v = player.getDeltaMovement();
            if (radius == 0 && player.onGround() && Math.hypot(v.x, v.z) > 0.05 && brakeTicks < 20) {
                brakeTicks++;
                stopBaritone();
                brake(mc, player, v);
                result = PathEngine.Result.RUNNING;
                status = "Stopping";
                return result;
            }
            releaseBrake(mc);
            arrive();
            return result;
        }
        brakeTicks = 0;
        releaseBrake(mc);
        if (warps && AotvAssist.isBusy()) {
            // Baritone's MovementWarp owns this pause; the frame mixin lets AOTV aim.
            result = PathEngine.Result.RUNNING;
            status = "Warping";
            return result;
        }
        IBaritone baritone = baritone();
        if (baritone == null) {
            fail("no Baritone");
            return result;
        }
        boolean active = baritone.getCustomGoalProcess().isActive()
                || baritone.getPathingBehavior().isPathing()
                || baritone.getPathingBehavior().hasPath()
                || baritone.getPathingBehavior().getInProgress().isPresent();
        if (active) {
            planningSince = 0;
            Vec3 feet = player.position();
            if (feet.distanceTo(lastPos) > 0.12) {
                lastPos = feet;
                lastProgressAt = now;
                if (replans > 0 && now - replanAt > 5_000L) replans = 0; // going again: fresh tries
            }
            result = PathEngine.Result.RUNNING;
            status = "Walking";
        } else {
            if (planningSince == 0) planningSince = now;
            status = "Planning";
            // Baritone has no path and no goal active any more: arrived is checked above,
            // so this is either a failed calculation or a cancelled path.
            // Baritone dropped the path (off it, cancelled): plan again at once instead of after
            // 3 s, and keep trying a few times (backing off) before giving up
            if (now - planningSince > 400L) {
                if (replans < 5 && now >= replanAt) {
                    replans++;
                    replanAt = now + 400L * replans;
                    beginPath();
                    lastProgressAt = now;
                } else if (replans >= 5) {
                    fail("Baritone has no path");
                }
            }
        }
        if (now - lastProgressAt > 4_000L) {
            if (replans < 5) {
                replans++;
                lastProgressAt = now;
                beginPath();
                status = "Replanning";
            } else {
                fail("no progress");
            }
        }
        look = steadyInAir(player, lookAlongPath(baritone, player));
        return result;
    }

    private void beginPath() {
        IBaritone baritone = baritone();
        Minecraft mc = Minecraft.getInstance();
        if (baritone == null || goal == null || mc.player == null) return;
        configure();
        try {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(goal, Math.max(0, radius)));
            result = PathEngine.Result.RUNNING;
            status = "Walking";
            planningSince = 0;
        } catch (RuntimeException e) {
            fail("Baritone error: " + e.getMessage());
        }
    }

    private void configure() {
        baritone.api.Settings s = BaritoneAPI.getSettings();
        s.randomLooking.value = 0.0;
        s.randomLooking113.value = 0.0;
        s.allowBreak.value = false;
        s.allowPlace.value = false;
        s.allowInventory.value = false;
        s.antiCheatCompatibility.value = true;
        // The camera is ours; the server gets Baritone's smooth walking rotation.
        s.freeLook.value = true;
        // Baritone plans the warp moves; BaritoneWarpExecutor only performs the AOTV click.
        s.allowTeleportMoves.value = warps;
        s.allowEtherwarpMoves.value = warps && AotvAssist.etherwarpAllowed()
                && pingplus.voicechat.client.PlayerSettings.miningBotEtherwarp;
        PathfinderTuning.apply(s, false);
    }

    private boolean inGoal(LocalPlayer player) {
        if (goal == null) return true;
        BlockPos feet = player.blockPosition();
        double dy = feet.getY() - goal.getY();
        double dx = feet.getX() - goal.getX();
        double dz = feet.getZ() - goal.getZ();
        int r = Math.max(0, radius);
        if (r <= 0) return dx == 0 && dy == 0 && dz == 0;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    private static Vec3 lookAlongPath(IBaritone baritone, LocalPlayer player) {
        IPath path = baritone.getPathingBehavior().getPath().orElse(null);
        if (path == null) return null;
        List<BetterBlockPos> positions = path.positions();
        if (positions.isEmpty()) return null;
        int near = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < positions.size(); i++) {
            double d = positions.get(i).distSqr(player.blockPosition());
            if (d < best) {
                best = d;
                near = i;
            }
        }
        Vec3 feet = player.position();
        double bestAhead = Double.MAX_VALUE;
        Vec3 chosen = null;
        for (int i = near; i < positions.size(); i++) {
            Vec3 p = Vec3.atBottomCenterOf(positions.get(i)).add(0, PathLine.MID, 0);
            double ahead = p.distanceTo(feet);
            // 3+ blocks ahead: a step up 2 blocks in front tilted the view by 25-45 degrees at once
            if (ahead >= 3.0 && ahead < bestAhead) {
                bestAhead = ahead;
                chosen = p;
            }
            if (ahead > 6.0) break;
        }
        return chosen;
    }

    /** Pitch towards the look point when we left the ground (null on the ground). */
    private Float airPitch;
    /** The calm pitch of the walking view (rate limited). */
    private Float walkPitch;

    /**
     * In the air the look point stays at the pitch of the take-off; only the direction follows the
     * path. A Jump Boost jump lifts the eyes 4 blocks over the path, and the point 2-5 blocks ahead
     * switched from block to block: the camera went up, down, up by 20-30 degrees a tick
     * (path recorder, combat runs: 36 such jumps in 50 s).
     */
    private Vec3 steadyInAir(LocalPlayer player, Vec3 point) {
        if (point == null) return null;
        double dx = point.x - player.getX(), dz = point.z - player.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 0.3) return point;
        float wanted = (float) Math.toDegrees(-Math.atan2(point.y - player.getEyeY(), flat));
        if (player.onGround() || player.isInWater() || player.onClimbable()) {
            // on the ground: the view follows slopes and steps, but calmly - at most 4 degrees a
            // tick and within -30..25 (path recorder: Ascend steps flipped the pitch 20-44 degrees
            // up and down within two ticks)
            float target = Math.clamp(wanted, -30f, 25f);
            float from = walkPitch == null ? target : walkPitch;
            walkPitch = from + Math.clamp(target - from, -4f, 4f);
            airPitch = walkPitch;
        } else if (airPitch != null) {
            walkPitch = airPitch; // in the air: the pitch of the take-off stays
        } else {
            return point;
        }
        double y = player.getEyeY() - Math.tan(Math.toRadians(walkPitch)) * flat;
        return new Vec3(point.x, y, point.z);
    }

    public Vec3 lookPoint() {
        return look;
    }

    public boolean aimingWarp() {
        return AotvAssist.isBusy();
    }

    public double warpAimSpeedDegPerSecond() {
        return PathfinderConfig.get().teleportRotationSpeed;
    }

    public String status() {
        return status;
    }

    public boolean busy() {
        return goal != null && result != PathEngine.Result.IDLE
                && result != PathEngine.Result.ARRIVED && result != PathEngine.Result.FAILED;
    }

    /** Compatibility with the old key-driven walker: Baritone owns the keys. */
    public void releaseKeys(Minecraft mc) {
    }

    public void stop() {
        IBaritone baritone = baritone();
        if (baritone != null) {
            try {
                baritone.getCustomGoalProcess().onLostControl();
                baritone.getPathingBehavior().cancelEverything();
            } catch (RuntimeException ignored) {
            }
        }
        goal = null;
        radius = 0;
        warps = false;
        result = PathEngine.Result.IDLE;
        status = "";
        look = null;

        planningSince = 0;
        replans = 0;
        brakeTicks = 0;
        releaseBrake(Minecraft.getInstance()); // held brake keys carried the player away after the walk
    }

    private int brakeTicks;
    private boolean braking;

    /** Keys against the slide (relative to where the player looks). */
    private void brake(Minecraft mc, LocalPlayer player, Vec3 v) {
        Vec3 f = Vec3.directionFromRotation(0, player.getYRot());
        double fwd = v.x * f.x + v.z * f.z;
        double side = v.x * -f.z + v.z * f.x; // + = moving to the player's left
        mc.options.keyDown.setDown(fwd > 0.02);
        mc.options.keyUp.setDown(fwd < -0.02);
        mc.options.keyLeft.setDown(side < -0.02);
        mc.options.keyRight.setDown(side > 0.02);
        mc.options.keySprint.setDown(false);
        braking = true;
    }

    private void releaseBrake(Minecraft mc) {
        if (!braking) return;
        mc.options.keyDown.setDown(false);
        mc.options.keyUp.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        braking = false;
    }

    private void stopBaritone() {
        IBaritone baritone = baritone();
        if (baritone == null) return;
        try {
            if (baritone.getPathingBehavior().isPathing()) baritone.getPathingBehavior().cancelEverything();
        } catch (RuntimeException ignored) {
        }
    }

    private void arrive() {
        releaseBrake(Minecraft.getInstance());
        look = null;
        result = PathEngine.Result.ARRIVED;
        status = "Arrived";
        IBaritone baritone = baritone();
        if (baritone != null) {
            try {
                baritone.getCustomGoalProcess().onLostControl();
                baritone.getPathingBehavior().cancelEverything();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void fail(String why) {
        releaseBrake(Minecraft.getInstance());
        result = PathEngine.Result.FAILED;
        status = "No way: " + why;
        try {
            IBaritone b = baritone();
            LocalPlayer player = Minecraft.getInstance().player;
            System.out.println("[BaritoneWalk] FAIL " + why + " goal=" + goal
                    + " at=" + (player == null ? "null" : player.position())
                    + " path=" + (b == null ? "null" : b.getPathingBehavior().getPath().orElse(null))
                    + " inProgress=" + (b == null ? "null" : b.getPathingBehavior().getInProgress().isPresent())
                    + " jump=" + BaritoneAPI.getSettings().jumpHeight.value
                    + " jumpPenalty=" + BaritoneAPI.getSettings().jumpPenalty.value
                    + " parkour=" + BaritoneAPI.getSettings().allowParkour.value);
        } catch (RuntimeException e) {
            System.out.println("[BaritoneWalk] diag error: " + e);
        }
    }

    private Vec3 playerPos() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? Vec3.ZERO : player.position();
    }

    public static IBaritone baritone() {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
