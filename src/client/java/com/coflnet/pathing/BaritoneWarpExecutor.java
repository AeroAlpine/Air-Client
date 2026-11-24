package com.coflnet.pathing;

import baritone.pathing.movement.movements.MovementWarp;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The Air Client side of Baritone's own warp moves. Baritone decides where and when; this only
 * performs the AOTV click. The aim point is on the real block top, never in the air above a slab.
 */
public final class BaritoneWarpExecutor implements MovementWarp.Executor {
    private static boolean installed;

    public static void install() {
        if (installed) return;
        installed = true;
        MovementWarp.executor = new BaritoneWarpExecutor();
    }

    @Override
    public boolean execute(MovementWarp.Kind kind, float yaw, float pitch, int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return false;
        BlockPos dest = new BlockPos(x, y, z);
        if (kind == MovementWarp.Kind.TELEPORT) {
            int reach = AotvAssist.teleportReach(player);
            if (reach <= 0) return false;
            Vec3 eye = player.getEyePosition();
            Vec3 dir = Vec3.directionFromRotation(pitch, yaw).normalize();
            WarpPlanner.Hop hop = new WarpPlanner.Hop(WarpPlanner.Kind.TELEPORT,
                    eye.add(dir.scale(4)), Vec3.atBottomCenterOf(dest), false, dir);
            return AotvAssist.execute(List.of(hop), false);
        }
        if (!AotvAssist.etherwarpAllowed() || !pingplus.voicechat.client.PlayerSettings.miningBotEtherwarp
                || !AotvAssist.canEtherwarp(player)) {
            return false;
        }
        // from the sneaking eye (the etherwarp is fired sneaking): from the standing eye the
        // aim went over an edge that hides the block once we crouch - it "warped into the wall"
        Vec3 sneakEye = player.position().add(0, WarpPlanner.SNEAK_EYE, 0);
        Vec3 aim = WarpLogic.etherAim(mc.level, sneakEye, dest.below());
        if (aim == null) return false;
        WarpPlanner.Hop hop = new WarpPlanner.Hop(WarpPlanner.Kind.ETHERWARP, aim,
                Vec3.atBottomCenterOf(dest), false, null);
        return AotvAssist.execute(List.of(hop), false);
    }

    @Override
    public boolean busy() {
        return AotvAssist.isBusy();
    }
}
