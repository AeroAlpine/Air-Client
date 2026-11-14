package com.coflnet.mixin;

import com.coflnet.pathing.PathRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server teleports of the own player (anticheat setbacks, warps) for the path recorder. */
@Mixin(ClientPacketListener.class)
public class PathSetbackMixin {
    @Unique
    private double cofl$beforeX;
    @Unique
    private double cofl$beforeY;
    @Unique
    private double cofl$beforeZ;
    @Unique
    private boolean cofl$hasBefore;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void cofl$beforeTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        // The handler first runs on the network thread and is then re-queued on the client thread.
        if (!client.isSameThread() || client.player == null) {
            return;
        }
        cofl$beforeX = client.player.getX();
        cofl$beforeY = client.player.getY();
        cofl$beforeZ = client.player.getZ();
        cofl$hasBefore = true;
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void cofl$afterTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (!cofl$hasBefore || client.player == null) {
            return;
        }
        cofl$hasBefore = false;
        try {
            PathRecorder.onServerTeleport(client, cofl$beforeX, cofl$beforeY, cofl$beforeZ);
        } catch (RuntimeException e) {
            System.out.println("[Pathfinder] Recorder-Fehler: " + e);
        }
    }
}
