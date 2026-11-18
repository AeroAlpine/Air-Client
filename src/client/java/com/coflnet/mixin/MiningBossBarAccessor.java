package com.coflnet.mixin;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/** Boss bars: Hypixel shows the running mining event ("EVENT 2X POWDER ... for 12:34") there. */
@Mixin(BossHealthOverlay.class)
public interface MiningBossBarAccessor {
    @Accessor("events")
    Map<UUID, LerpingBossEvent> mining$events();
}
