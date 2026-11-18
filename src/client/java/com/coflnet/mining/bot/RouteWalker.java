package com.coflnet.mining.bot;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import com.coflnet.pathing.PathEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import pingplus.voicechat.client.FollowFeature;

/**
 * Travel in the Dwarven Mines: "/warp forge" first when the map says that is quicker, then
 * one planned path to the target ({@link PathEngine}: Baritone plans it with teleports,
 * etherwarps and the player's etherwarp points as moves; the engine follows it as a line and
 * only plans again when it is really off).
 */
final class RouteWalker {
    enum Result { RUNNING, ARRIVED, FAILED }

    private Vec3 target;
    private double radius;
    private boolean allowWarp;
    private boolean decided;
    private boolean warpingForge;
    private long warpSentAt;
    private int warpTries;
    private String status = "";
    /** The current part goal on the way to the target (the target itself on the last part). */
    private Vec3 leg;

    void start(Vec3 target, double radius, boolean allowWarp) {
        this.target = target;
        this.radius = radius;
        this.allowWarp = allowWarp;
        decided = false;
        warpingForge = false;
        warpTries = 0;
        warpSentAt = 0;
        leg = null;
        status = "Planning";
    }

    String status() {
        return status;
    }

    Vec3 target() {
        return target;
    }

    /** The camera is steered by the path engine (through Combat's look); nothing extra here. */
    Vec3 lookPoint() {
        return null;
    }

    Result tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || target == null) return Result.FAILED;
        Vec3 feet = player.position();
        long now = System.currentTimeMillis();
        if (!decided) {
            decided = true;
            if (allowWarp && feet.distanceTo(target) > 40) {
                DwarvenGraph.Route route = DwarvenGraph.get().route(feet, target, true);
                warpingForge = route.warpFirst();
                if (warpingForge) CommissionBot.log("/warp forge first (quicker)");
            }
        }
        if (warpingForge) return tickForgeWarp(mc, player, now);
        // coarse + fine: the map knows the whole mines, the path engine plans only to the next
        // point of the map route up to ~60 blocks ahead (inside the loaded world), the next one
        // comes 12 blocks before we get there - planned while we walk, no stop
        boolean finalLeg = leg != null && leg.distanceTo(target) < 1.0E-6;
        if (leg == null || !finalLeg && (feet.distanceTo(leg) < 12
                || FollowFeature.walkResult() == PathEngine.Result.ARRIVED)) {
            leg = nextLeg(feet);
        }
        finalLeg = leg.distanceTo(target) < 1.0E-6;
        FollowFeature.walkTo(BlockPos.containing(leg), finalLeg ? (int) Math.max(1, Math.floor(radius)) : 3, true);
        PathEngine.Result r = FollowFeature.walkResult();
        status = FollowFeature.walkStatus();
        if (!finalLeg && r == PathEngine.Result.ARRIVED) {
            leg = nextLeg(feet);
            return Result.RUNNING;
        }
        if (r == PathEngine.Result.ARRIVED || feet.distanceTo(target) <= radius && player.onGround()) {
            FollowFeature.stopWalking();
            status = "Arrived";
            return Result.ARRIVED;
        }
        if (r == PathEngine.Result.FAILED && !finalLeg) {
            // that map point is not reachable from here: straight for the target instead
            CommissionBot.log("part goal unreachable, heading for the target");
            leg = target;
            return Result.RUNNING;
        }
        if (r == PathEngine.Result.FAILED) {
            FollowFeature.stopWalking();
            status = "No way";
            return Result.FAILED;
        }
        return Result.RUNNING;
    }

    /** The furthest point of the map route that is at most ~60 blocks away (the target when it is close). */
    private Vec3 nextLeg(Vec3 feet) {
        if (feet.distanceTo(target) <= 60) return target;
        DwarvenGraph.Route route = DwarvenGraph.get().route(feet, target, false);
        Vec3 best = null;
        for (Vec3 p : route.points()) {
            if (p.distanceTo(feet) <= 60 && p.distanceTo(target) < feet.distanceTo(target)) best = p;
        }
        if (best == null || best.distanceTo(feet) < 14) {
            // nothing useful on the map: head for the target, the engine extends part paths itself
            return target;
        }
        return best;
    }

    private Result tickForgeWarp(Minecraft mc, LocalPlayer player, long now) {
        Vec3 forge = DwarvenGraph.get().place("forgeWarp");
        if (forge != null && player.position().distanceTo(forge) < 6) {
            warpingForge = false;
            return Result.RUNNING;
        }
        if (warpSentAt == 0 || now - warpSentAt > 8_000L) {
            if (++warpTries > 3) {
                warpingForge = false; // no warp (not unlocked?): walk
                return Result.RUNNING;
            }
            FollowFeature.stopWalking();
            if (mc.getConnection() != null) mc.getConnection().sendCommand("warp forge");
            warpSentAt = now;
        }
        status = "/warp forge";
        return Result.RUNNING;
    }

    /** Stand still (keys free) but keep the target. */
    void pause() {
        FollowFeature.stopWalking();
        leg = null;
    }

    /** Go on after a pause: the engine plans from where we are. */
    void resume() {
    }

    void stop() {
        FollowFeature.stopWalking();
        target = null;
        status = "";
    }

    private static IBaritone baritone() {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Ends any walking. */
    static void stopBaritone() {
        FollowFeature.stopWalking();
        IBaritone b = baritone();
        if (b == null) return;
        try {
            b.getCustomGoalProcess().onLostControl();
            b.getPathingBehavior().cancelEverything();
        } catch (RuntimeException ignored) {
        }
    }
}
