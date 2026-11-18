package com.coflnet.mining;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.debug.DebugValueAccess;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Boxes on commission blocks and titanium, and a marker plus line to the Dark Monolith. */
public final class MiningRenderer implements DebugRenderer.SimpleDebugRenderer {
    @Override
    public void emitGizmos(double camX, double camY, double camZ, DebugValueAccess debugValues,
                           Frustum frustum, float partialTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gui.hud.isHidden() || !MiningFeatures.active()) return;
        for (MiningFeatures.Highlight h : MiningFeatures.highlights()) {
            AABB box = new AABB(h.pos()).inflate(0.002);
            if (!frustum.isVisible(box)) continue;
            int color = h.color();
            Gizmos.cuboid(box, GizmoStyle.strokeAndFill(0xE0000000 | (color & 0xFFFFFF), 1.5f,
                    0x28000000 | (color & 0xFFFFFF))).setAlwaysOnTop();
        }
        BlockPos egg = MiningFeatures.monolith();
        if (egg != null) {
            int color = 0xFFB061E8;
            AABB box = new AABB(egg).inflate(0.05);
            Gizmos.cuboid(box, GizmoStyle.strokeAndFill(color, 2.5f, 0x50000000 | (color & 0xFFFFFF))).setAlwaysOnTop();
            Vec3 eye = mc.player.getEyePosition(partialTicks);
            Vec3 look = mc.player.getViewVector(partialTicks);
            Gizmos.line(eye.add(look.scale(0.6)), Vec3.atCenterOf(egg), color, 2f).setAlwaysOnTop();
            int distance = (int) Math.round(Math.sqrt(egg.distToCenterSqr(eye)));
            Gizmos.billboardTextOverBlock("Dark Monolith  " + distance + "m", egg, 0, color, 0.6f).setAlwaysOnTop();
        }
    }
}
