package com.coflnet.mixin;

import com.coflnet.failsafe.Failsafe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What the server does to our own player, for the fail-safe: teleports and camera turns
 * (before / after the packet), a forced hotbar slot, a swapped hotbar item, block changes.
 * Every handler runs once on the network thread first (then it is re-queued): only the run on
 * the client thread counts.
 */
@Mixin(ClientPacketListener.class)
public class FailsafePacketMixin {
    @Unique
    private double failsafe$x, failsafe$y, failsafe$z;
    @Unique
    private float failsafe$yaw, failsafe$pitch;
    @Unique
    private boolean failsafe$moveBefore, failsafe$rotBefore;

    @Unique
    private static boolean failsafe$ready() {
        Minecraft mc = Minecraft.getInstance();
        return mc.isSameThread() && mc.player != null && mc.level != null;
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void failsafe$moveHead(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (!failsafe$ready()) return;
        var p = Minecraft.getInstance().player;
        failsafe$x = p.getX();
        failsafe$y = p.getY();
        failsafe$z = p.getZ();
        failsafe$yaw = p.getYRot();
        failsafe$pitch = p.getXRot();
        failsafe$moveBefore = true;
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void failsafe$moveTail(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (!failsafe$moveBefore || !failsafe$ready()) return;
        failsafe$moveBefore = false;
        var p = Minecraft.getInstance().player;
        try {
            double dx = p.getX() - failsafe$x, dy = p.getY() - failsafe$y, dz = p.getZ() - failsafe$z;
            Failsafe.onServerMove(Math.sqrt(dx * dx + dy * dy + dz * dz), failsafe$turned(p.getYRot(), p.getXRot()));
        } catch (RuntimeException ignored) {
        }
    }

    @Inject(method = "handleRotatePlayer", at = @At("HEAD"))
    private void failsafe$rotHead(ClientboundPlayerRotationPacket packet, CallbackInfo ci) {
        if (!failsafe$ready()) return;
        var p = Minecraft.getInstance().player;
        failsafe$yaw = p.getYRot();
        failsafe$pitch = p.getXRot();
        failsafe$rotBefore = true;
    }

    @Inject(method = "handleRotatePlayer", at = @At("TAIL"))
    private void failsafe$rotTail(ClientboundPlayerRotationPacket packet, CallbackInfo ci) {
        if (!failsafe$rotBefore || !failsafe$ready()) return;
        failsafe$rotBefore = false;
        var p = Minecraft.getInstance().player;
        try {
            Failsafe.onServerRotation(failsafe$turned(p.getYRot(), p.getXRot()));
        } catch (RuntimeException ignored) {
        }
    }

    @Unique
    private float failsafe$turned(float yaw, float pitch) {
        return Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - failsafe$yaw)) + Math.abs(pitch - failsafe$pitch);
    }

    @Inject(method = "handleSetHeldSlot", at = @At("HEAD"))
    private void failsafe$heldSlot(ClientboundSetHeldSlotPacket packet, CallbackInfo ci) {
        if (!failsafe$ready()) return;
        var p = Minecraft.getInstance().player;
        int before = p.getInventory().getSelectedSlot();
        if (packet.slot() != before) {
            try {
                Failsafe.onItemSwap("the server selected slot " + (packet.slot() + 1));
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Inject(method = "handleContainerSetSlot", at = @At("HEAD"))
    private void failsafe$setSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        if (!failsafe$ready()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) return; // a menu of ours (selling, the King ...) changes items itself
        int hotbar = -1;
        if (packet.getContainerId() == 0 && packet.getSlot() >= 36 && packet.getSlot() <= 44) {
            hotbar = packet.getSlot() - 36;
        } else if (packet.getContainerId() == -2 && packet.getSlot() >= 0 && packet.getSlot() <= 8) {
            hotbar = packet.getSlot();
        }
        if (hotbar < 0) return;
        ItemStack before = mc.player.getInventory().getItem(hotbar);
        ItemStack after = packet.getItem();
        if (before.isEmpty() || after.isEmpty()) return; // picked up / used up / sold: normal
        String a = Failsafe.itemId(before), b = Failsafe.itemId(after);
        if (!a.equals(b) && hotbar == mc.player.getInventory().getSelectedSlot()) {
            try {
                Failsafe.onItemSwap("held item became " + after.getHoverName().getString());
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void failsafe$block(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if (!failsafe$ready()) return;
        var level = Minecraft.getInstance().level;
        try {
            Failsafe.onBlockChange(packet.getPos(), level.getBlockState(packet.getPos()), packet.getBlockState());
        } catch (RuntimeException ignored) {
        }
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("HEAD"))
    private void failsafe$blocks(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if (!failsafe$ready()) return;
        var level = Minecraft.getInstance().level;
        try {
            packet.runUpdates((pos, state) -> Failsafe.onBlockChange(pos.immutable(), level.getBlockState(pos), state));
        } catch (RuntimeException ignored) {
        }
    }
}
