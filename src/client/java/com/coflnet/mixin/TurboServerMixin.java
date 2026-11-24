package com.coflnet.mixin;

import com.coflnet.pathing.sim.TurboClock;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** Simulation turbo mode: the integrated server waits for the client's tick (lockstep). */
@Mixin(MinecraftServer.class)
public abstract class TurboServerMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void airclient$waitForClient(BooleanSupplier haveTime, CallbackInfo ci) {
        TurboClock.beforeServerTick((MinecraftServer) (Object) this);
    }

    /**
     * Turbo: no sleeping until the next tick (on Windows even a 0.1 ms wait sleeps ~15 ms, which
     * capped turbo at ~60 ticks/s); queued tasks still run, the lockstep wait paces the server.
     */
    @Inject(method = "waitUntilNextTick", at = @At("HEAD"), cancellable = true)
    private void airclient$noSleep(CallbackInfo ci) {
        if (TurboClock.active()) {
            ((BlockableEventLoopInvoker) (Object) this).airclient$runAllTasks();
            ci.cancel();
        }
    }

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void airclient$serverTicked(BooleanSupplier haveTime, CallbackInfo ci) {
        TurboClock.afterServerTick();
    }
}
