package com.coflnet.ahflipper;

import com.coflnet.flipper.FlipperConfig;
import com.coflnet.flipper.FlipperMod;
import com.coflnet.flipper.analysis.BazaarFlipTracker;
import com.coflnet.flipper.analysis.FlipAnalyzer;
import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.gui.RenderUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Best Bazaar flips, built like the Investment Tracker screen: one row per item with buy and
 * sell price, the numbers in the middle and a coins/hour bar on the right. Uses the Bazaar
 * flipper's own analysis and filters (Bazaar Flips settings in the INVESTMENT tab).
 */
public final class BazaarFlipScreen extends Screen {
    private static final int MAX_PANEL_WIDTH = 600;
    private static final int ROW_HEIGHT = 46;
    private static final int HEADER_HEIGHT = 74;
    private static final int FOOTER_HEIGHT = 56;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    public BazaarFlipScreen() {
        super(Component.literal("Bazaar Flips"));
    }

    /** Screen to go back to on close (the SkyBlock menu); null = back to the game. */
    private net.minecraft.client.gui.screens.Screen parent;

    public BazaarFlipScreen withParent(net.minecraft.client.gui.screens.Screen parent) {
        this.parent = parent;
        return this;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    protected void init() {
        BazaarFlipTracker.start();
        panelWidth = Math.max(420, Math.min(MAX_PANEL_WIDTH, width - 16));
        int rows = visibleRows();
        panelHeight = HEADER_HEIGHT + rows * ROW_HEIGHT + FOOTER_HEIGHT;
        panelX = Math.max(8, (width - panelWidth) / 2);
        panelY = Math.max(8, (height - panelHeight) / 2);
        int footerY = panelY + panelHeight - 24;
        addRenderableWidget(actionButton(panelX + 8, footerY, 78, 18, () -> "Refresh",
                BazaarFlipTracker::requestRefresh));
        addRenderableWidget(actionButton(panelX + 92, footerY, 110, 18,
                () -> "Sort: " + BazaarFlipTracker.Sort.of(FlipperMod.CONFIG.flipsSort).label,
                this::cycleSort));
        addRenderableWidget(actionButton(panelX + 208, footerY, 96, 18, () -> "AH Investments",
                () -> {
                    AhInvestmentTracker.start();
                    minecraft.gui.setScreen(new AhInvestmentScreen().withParent(parent));
                }));
        addRenderableWidget(actionButton(panelX + panelWidth - 86, footerY, 78, 18,
                () -> "Close", this::onClose));
    }

    @Override
    public void removed() {
        BazaarFlipTracker.stop();
        super.removed();
    }

    private void cycleSort() {
        FlipperConfig cfg = FlipperMod.CONFIG;
        cfg.flipsSort = BazaarFlipTracker.Sort.of(cfg.flipsSort).next().name();
        cfg.save();
        BazaarFlipTracker.resort();
    }

    private int visibleRows() {
        int maxByHeight = Math.max(1, (height - HEADER_HEIGHT - FOOTER_HEIGHT - 16) / ROW_HEIGHT);
        return Math.max(1, Math.min(Math.min(10, FlipperMod.CONFIG.flipsTopCount), maxByHeight));
    }

    private int middleX() {
        return panelX + (barWidth() > 0 ? 320 : 300);
    }

    private int barWidth() {
        return panelWidth >= 540 ? 112 : 0;
    }

    private int barX() {
        return panelX + panelWidth - barWidth() - 14;
    }

    private Button actionButton(int x, int y, int width, int height,
                                java.util.function.Supplier<String> label, Runnable action) {
        return new Button(x, y, width, height, Component.literal(label.get()),
                button -> action.run(), supplier -> supplier.get()) {
            @Override
            protected void extractContents(GuiGraphicsExtractor graphics,
                                           int mouseX, int mouseY, float delta) {
                if (isHoveredOrFocused()) {
                    graphics.fill(getX(), getY(), getRight(), getBottom(), 0x80555555);
                }
                RenderUtils.drawString(graphics, label.get(), getX() + 5, getY() + 5, 0xFFFFFFFF);
            }
        };
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractBackground(context, mouseX, mouseY, delta);
        BazaarFlipTracker.Snapshot snapshot = BazaarFlipTracker.snapshot();
        FlipperConfig cfg = FlipperMod.CONFIG;
        RenderUtils.drawRoundedRect(context, panelX, panelY, panelWidth, panelHeight, 6, 0xEE0B0B10);
        RenderUtils.drawRoundedRect(context, panelX + 6, panelY + 6, panelWidth - 12, 24, 4, 0xFF1B1B24);
        RenderUtils.drawStringWithShadow(context, "§6Bazaar Flips", panelX + 12, panelY + 12, 0xFFFFFFFF);
        RenderUtils.drawString(context, statusLine(snapshot), panelX + 100, panelY + 13, 0xFFB8B8C8);
        String bankroll = snapshot.bankroll <= 0 ? "Bankroll ?"
                : "Bankroll " + fmt(snapshot.bankroll) + (snapshot.bankrollKnown ? "" : " §8(est.)");
        RenderUtils.drawString(context, "§7" + bankroll, panelX + panelWidth - 130, panelY + 13, 0xFFB8B8C8);

        // Item mode line under the title bar: what the flipper itself will do.
        String itemMode = !cfg.onlyItemsMode ? "§7Item mode §cOFF §8· flipper takes the top of this list"
                : (cfg.onlyItems == null || cfg.onlyItems.isEmpty())
                ? "§7Item mode §eON §8· list empty, flipper uses this list"
                : "§7Item mode §aON §8· flipper only buys §f" + cfg.onlyItems.size()
                + " §8listed item(s), marked §a★";
        RenderUtils.drawString(context, itemMode, panelX + 12, panelY + 36, 0xFFB8B8C8);

        int tableTop = panelY + HEADER_HEIGHT - 12;
        RenderUtils.drawString(context, "§8ITEM", panelX + 32, tableTop, 0xFF808080);
        RenderUtils.drawString(context, "§8BUY ORDER → SELL OFFER · FLOW", panelX + 158, tableTop, 0xFF808080);
        RenderUtils.drawString(context, "§8NUMBERS", middleX(), tableTop, 0xFF808080);
        if (barWidth() > 0) {
            RenderUtils.drawString(context, "§8COINS / HOUR", barX(), tableTop, 0xFF808080);
        }

        List<FlipAnalyzer.FlipPlan> top = snapshot.top;
        int maxRows = visibleRows();
        int rowY = panelY + HEADER_HEIGHT;
        if (top.isEmpty()) {
            String message = BazaarFlipTracker.scanning()
                    ? "§7Scanning the Bazaar ..."
                    : "§7No flips found. Press Refresh or loosen the filters (INVESTMENT → Bazaar Flips).";
            RenderUtils.drawString(context, message, panelX + 14, rowY + 12, 0xFFFFFFFF);
        } else {
            double bestCph = 1;
            for (FlipAnalyzer.FlipPlan plan : top) {
                bestCph = Math.max(bestCph, plan.coinsPerHour);
            }
            int middleX = middleX();
            for (int index = 0; index < top.size() && index < maxRows; index++) {
                FlipAnalyzer.FlipPlan plan = top.get(index);
                int y = rowY + index * ROW_HEIGHT;
                boolean listed = snapshot.itemModeProducts.contains(plan.productId);
                RenderUtils.drawRect(context, panelX + 6, y, panelWidth - 12, ROW_HEIGHT - 2,
                        index % 2 == 0 ? 0x8014141B : 0x80101016);
                RenderUtils.drawRect(context, panelX + 6, y, 2, ROW_HEIGHT - 2, marginColor(plan.marginPercent));

                RenderUtils.drawItemStack(context, AhInvestmentScreen.iconFor(plan.productId), panelX + 12, y + 6, 1.0f);
                RenderUtils.drawStringWithShadow(context, (listed ? "§a★ " : "")
                        + "§f" + trim(plan.displayName, listed ? 22 : 24), panelX + 32, y + 4, 0xFFFFFFFF);
                RenderUtils.drawString(context,
                        "§7Buy §f" + fmt(plan.buyPrice) + " §7→ §f" + fmt(plan.sellPrice)
                                + " §8(" + pct(plan.marginPercent) + ")",
                        panelX + 32, y + 16, 0xFFDDDDDD);
                RenderUtils.drawString(context,
                        "§7Volume §f" + fmt(plan.hourlyVolume) + "/h §7· Fill "
                                + fillColor(plan.fillProbability) + pct(plan.fillProbability * 100.0)
                                + " §7· +" + fmt(plan.profitPerUnit) + "/unit",
                        panelX + 32, y + 27, 0xFFBBBBBB);

                RenderUtils.drawString(context,
                        "§7Qty §f" + plan.qty + " §7· Stake §f" + fmt(plan.stake),
                        middleX, y + 4, 0xFFFFFFFF);
                RenderUtils.drawString(context,
                        "§a+" + fmt(plan.expectedProfit()) + " §7· §e" + fmt(plan.coinsPerHour) + "/h",
                        middleX, y + 16, 0xFFFFFFFF);
                RenderUtils.drawString(context,
                        "§7Score §f" + fmt(plan.score) + " §7· Undercut §f"
                                + String.format(Locale.US, "%.1f", Math.max(plan.uBuy, plan.uSell)) + "/h",
                        middleX, y + 27, 0xFFAAAAAA);

                if (barWidth() > 0) {
                    drawBars(context, barX(), y + 7, barWidth(), 32, plan, bestCph);
                }
            }
        }

        int footerY = panelY + panelHeight - 50;
        String footer = "§8Tax " + String.format(Locale.US, "%.2f%%", cfg.taxPercent)
                + " · Margin " + String.format(Locale.US, "%.1f–%.0f%%", cfg.minMarginPercent, cfg.maxMarginPercent)
                + " · Vol ≥" + fmt(cfg.minWeeklyVolume) + "/w"
                + " · Unit ≥" + fmt(cfg.minUnitCoins)
                + " · Order ≥" + fmt(cfg.minOrderCoins)
                + (cfg.minCoinsPerHour > 0 ? " · ≥" + fmt(cfg.minCoinsPerHour) + "/h" : "");
        RenderUtils.drawString(context, footer, panelX + 12, footerY, 0xFF888888);
        if (BazaarFlipTracker.scanning()) {
            // no per-item progress from the analyzer: a running stripe instead of a bar
            int barWidth = panelWidth - 24;
            int stripe = Math.max(40, barWidth / 5);
            long elapsed = System.currentTimeMillis() - BazaarFlipTracker.scanStartedAt();
            int offset = (int) ((elapsed / 6) % (barWidth + stripe)) - stripe;
            int start = Math.max(0, offset);
            int end = Math.min(barWidth, offset + stripe);
            RenderUtils.drawRect(context, panelX + 12, panelY + panelHeight - 36, barWidth, 3, 0xFF232330);
            if (end > start) {
                RenderUtils.drawRect(context, panelX + 12 + start, panelY + panelHeight - 36, end - start, 3, 0xFFFFAA00);
            }
        }
    }

    /** Coins/hour relative to the best row, and the chance both orders fill before an undercut. */
    private static void drawBars(GuiGraphicsExtractor context, int x, int y, int width, int height,
                                 FlipAnalyzer.FlipPlan plan, double bestCph) {
        RenderUtils.drawRect(context, x, y, width, height, 0x60000000);
        int inner = width - 8;
        int cph = (int) Math.round(inner * Math.min(1.0, plan.coinsPerHour / bestCph));
        RenderUtils.drawRect(context, x + 4, y + 6, inner, 7, 0x40FFFFFF);
        RenderUtils.drawRect(context, x + 4, y + 6, Math.max(2, cph), 7, 0xFFFFC34D);
        int fill = (int) Math.round(inner * Math.max(0.0, Math.min(1.0, plan.fillProbability)));
        RenderUtils.drawRect(context, x + 4, y + 19, inner, 4, 0x40FFFFFF);
        RenderUtils.drawRect(context, x + 4, y + 19, Math.max(2, fill), 4, 0xFF5599CC);
        RenderUtils.drawRect(context, x, y + height - 1, width, 1, 0x40FFFFFF);
    }

    private static String statusLine(BazaarFlipTracker.Snapshot snapshot) {
        if (BazaarFlipTracker.scanning()) {
            long seconds = (System.currentTimeMillis() - BazaarFlipTracker.scanStartedAt()) / 1000;
            return "§eScanning " + seconds + "s ...";
        }
        if (snapshot.error != null && !snapshot.error.isBlank()) {
            return "§c" + trim(snapshot.error, 46);
        }
        if (snapshot.updatedAt > 0) {
            return "§7updated " + age(snapshot.updatedAt) + " §7· " + snapshot.candidateCount
                    + " flips §7· Top " + snapshot.top.size();
        }
        return "§7waiting for scan ...";
    }

    private static String age(long at) {
        long seconds = Math.max(0, (System.currentTimeMillis() - at) / 1000);
        if (seconds < 60) {
            return seconds + "s ago";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        }
        return (seconds / 3600) + "h ago";
    }

    private static int marginColor(double margin) {
        if (margin >= 5.0) {
            return 0xFF55DD55;
        }
        if (margin >= 2.0) {
            return 0xFFDDCC55;
        }
        return 0xFF8888AA;
    }

    private static String fillColor(double probability) {
        return probability >= 0.6 ? "§a" : probability >= 0.35 ? "§e" : "§c";
    }

    private static String pct(double value) {
        return value < 10 ? String.format(Locale.US, "%.1f%%", value) : String.format(Locale.US, "%.0f%%", value);
    }

    private static String fmt(double coins) {
        return ScreenUtil.formatCoins(coins);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
    }
}
