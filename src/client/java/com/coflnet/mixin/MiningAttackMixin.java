package com.coflnet.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the Commission Bot breaks a block it drives the breaking itself (also with the window
 * in the background). Vanilla would otherwise call stopDestroyBlock() every tick the attack key
 * is not held, which resets the break progress.
 */
@Mixin(Minecraft.class)
public abstract class MiningAttackMixin {
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void cofl$botBreaks(boolean leftClick, CallbackInfo ci) {
        if (com.coflnet.mining.bot.CommissionBot.isBreaking()) {
            ci.cancel();
        }
    }
}
