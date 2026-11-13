package com.coflnet.mixin;

import com.coflnet.ahflipper.AhInstantConfirm;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the AH buy flow click "Confirm Purchase" in the same moment its items arrive. */
@Mixin(ClientPacketListener.class)
public class AhInstantClickMixin {

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void cofl$instantConfirmContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        try {
            AhInstantConfirm.onContainerUpdate();
        } catch (RuntimeException e) {
            System.out.println("[AH-Flipper] Instant-Confirm Fehler: " + e);
        }
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void cofl$instantConfirmSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        try {
            AhInstantConfirm.onContainerUpdate();
        } catch (RuntimeException e) {
            System.out.println("[AH-Flipper] Instant-Confirm Fehler: " + e);
        }
    }
}
