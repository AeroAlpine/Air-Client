package com.coflnet.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The rotation the client last sent to the server (what the anticheat sees). */
@Mixin(LocalPlayer.class)
public interface LocalPlayerRotationAccessor {
    @Accessor("yRotLast")
    float cofl$getYRotLast();

    @Accessor("xRotLast")
    float cofl$getXRotLast();
}
