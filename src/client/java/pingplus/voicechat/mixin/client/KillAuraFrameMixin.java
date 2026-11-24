package pingplus.voicechat.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class KillAuraFrameMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void voicechat$smoothAim(CallbackInfo ci) {
        // simulation turbo mode runs the frame work per simulated tick itself
        if (com.coflnet.pathing.sim.TurboClock.active()) return;
        pingplus.voicechat.client.FrameHooks.run(Minecraft.getInstance());
    }
}
