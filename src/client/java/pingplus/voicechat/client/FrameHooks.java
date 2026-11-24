package pingplus.voicechat.client;

import com.coflnet.ahflipper.AhMobPathfinder;
import com.coflnet.lowballing.LowballingRotation;
import net.minecraft.client.Minecraft;

/**
 * Per-frame camera work of the bots (smooth aim, path looking). Called before every rendered
 * frame; in the simulation's turbo mode three times per simulated tick instead.
 */
public final class FrameHooks {
    private FrameHooks() {
    }

    public static void run(Minecraft client) {
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
        FollowFeature.frame(client);
        LowballingRotation.frame(client.player);
        AhMobPathfinder.frame(client);
    }
}
