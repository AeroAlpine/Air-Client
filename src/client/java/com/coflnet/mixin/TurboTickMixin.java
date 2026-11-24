package com.coflnet.mixin;

import com.coflnet.pathing.sim.TurboClock;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Simulation turbo mode: many client ticks per frame, in lockstep with the integrated server. */
@Mixin(Minecraft.class)
public abstract class TurboTickMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void airclient$turboFrame(boolean advanceGameTime, CallbackInfo ci) {
        TurboClock.beginFrame();
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"))
    private int airclient$turboTicks(int a, int b) {
        return TurboClock.ticksThisFrame(a, b);
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V"))
    private void airclient$turboTick(Minecraft mc) {
        if (!TurboClock.beforeClientTick(mc)) return;
        mc.tick();
        TurboClock.afterClientTick();
    }
}
