package com.coflnet.mixin;

import com.coflnet.session.BackgroundAutomation;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class BackgroundAutomationMixin {
    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void cofl$keepAutomationRunning(boolean pauseOnly, CallbackInfo ci) {
        Minecraft client = (Minecraft) (Object) this;
        if (!BackgroundAutomation.isActive(client)) {
            return;
        }
        if (cofl$escapePressed(client)) {
            // The player pressed Escape on purpose: show the pause menu. Only the
            // automatic pause (window lost focus, alt-tab) is suppressed, because a
            // pause menu would block the bots' GUI/movement steps.
            return;
        }
        if (client.gui.screen() == null) {
            client.mouseHandler.releaseMouse();
        }
        ci.cancel();
    }

    private static boolean cofl$escapePressed(Minecraft client) {
        try {
            return client.isWindowActive()
                    && GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        } catch (Throwable t) {
            return false;
        }
    }
}
