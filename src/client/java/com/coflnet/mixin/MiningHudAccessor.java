package com.coflnet.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** The tab list in the order it is drawn, so sections ("Commissions:", "Powders:") can be read top to bottom. */
@Mixin(PlayerTabOverlay.class)
public interface MiningHudAccessor {
    @Invoker("getPlayerInfos")
    List<PlayerInfo> mining$orderedPlayers();
}
