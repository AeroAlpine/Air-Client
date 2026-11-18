package pingplus.voicechat.mixin.client;

import com.coflnet.ahflipper.AhMobPathfinder;
import com.coflnet.lowballing.LowballingRotation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pingplus.voicechat.client.KillAuraFeature;

@Mixin(GameRenderer.class)
public abstract class KillAuraFrameMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void voicechat$smoothAim(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (com.coflnet.failsafe.Failsafe.ownsCamera()) {
            // the fail-safe's short look around: nothing else turns the camera meanwhile
            com.coflnet.failsafe.Failsafe.frame(client);
            return;
        }
        if (com.coflnet.pathing.AotvAssist.isBusy()) {
            // AOTV turns the camera to the warp direction itself (smoothly); nothing else may
            // move it meanwhile
            com.coflnet.pathing.AotvAssist.frame(client);
            return;
        }
        com.coflnet.mining.bot.CommissionBot.frame(client);
        KillAuraFeature.frame(client);
        pingplus.voicechat.client.FollowFeature.frame(client);
        LowballingRotation.frame(client.player);
        AhMobPathfinder.frame(client);
    }
}
