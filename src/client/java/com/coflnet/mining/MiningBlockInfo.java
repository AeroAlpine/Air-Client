package com.coflnet.mining;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import pingplus.voicechat.client.PlayerSettings;
import pingplus.voicechat.client.gui.glass.GlassStyle;

import java.util.Locale;

/**
 * Small glass tag under the crosshair: which ore you look at, its strength and how long it takes.
 * The time comes from your own measured break times (the real number, lag included) and, when the
 * tab list shows your mining speed, from the formula as well.
 */
final class MiningBlockInfo {
    private MiningBlockInfo() {
    }

    static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (!PlayerSettings.miningOres || !PlayerSettings.miningBlockInfo || mc.player == null
                || mc.gui.screen() != null || !MiningState.inMiningIsland()) return;
        MiningBlock block = MiningFeatures.targetBlock(mc);
        if (block == null) return;
        StringBuilder text = new StringBuilder(block.label).append("  ").append(block.strength);
        double speed = MiningState.miningSpeed();
        int ticks = block.ticks(speed);
        if (ticks > 0) {
            text.append("  ·  ").append(ticks).append(ticks == 1 ? " tick" : " ticks");
            double next = block.speedForNextTick(speed);
            if (next > 0) text.append(" (+").append(Math.round(next)).append(" speed = -1)");
        }
        long measured = MiningFeatures.stats().avgMs(block);
        if (measured > 0) text.append("  ·  ⌀ ").append(String.format(Locale.ROOT, "%.2fs", measured / 1000.0));
        String s = text.toString();
        int w = mc.font.width(s) + 12;
        int x = (g.guiWidth() - w) / 2;
        int y = g.guiHeight() / 2 + 14;
        g.nextStratum();
        GlassStyle.surface(g, x, y, w, 14, .85f, .08f, false);
        g.nextStratum();
        g.fill(x + 3, y + 4, x + 5, y + 10, block.color);
        g.text(mc.font, s, x + 8, y + 3, GlassStyle.TEXT, false);
    }
}
