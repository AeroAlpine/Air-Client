package com.coflnet.gui;

import com.coflnet.flipper.trading.ScreenUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import pingplus.voicechat.client.gui.glass.GlassStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small glass box next to the Bazaar order menu ("Your/Co-op Bazaar Orders") and
 * "Manage Auctions": the total value of all sell offers and buy orders, and what
 * all listed auctions bring in once they are sold. Read from the menu itself, so
 * co-op orders and auctions are included.
 */
public final class MarketTotalsOverlay {

    private static final int WIDTH = 156;
    private static final int PAD = 7;
    private static final int LINE = 11;
    private static final long REFRESH_MS = 400L;
    /** Hypixel takes 1% when coins of an auction over 1M are claimed. */
    private static final double AH_CLAIM_TAX = 0.01;

    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)");

    private record Line(String label, String value, int color, boolean big) {
    }

    private static Object lastScreen;
    private static long lastRefresh;
    private static String title = "";
    private static List<Line> lines = List.of();

    private MarketTotalsOverlay() {
    }

    public static void render(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen,
                              int leftPos, int topPos, int imageWidth) {
        try {
            long now = System.currentTimeMillis();
            if (screen != lastScreen || now - lastRefresh > REFRESH_MS) {
                lastScreen = screen;
                lastRefresh = now;
                refresh(screen);
            }
            List<Line> shown = lines;
            if (shown.isEmpty()) {
                return;
            }
            draw(g, screen, leftPos, topPos, imageWidth, shown);
        } catch (Exception e) {
            // never break the menu because of the overlay
        }
    }

    // ---------------------------------------------------------------- parsing

    private static void refresh(AbstractContainerScreen<?> screen) {
        String lower = ScreenUtil.cleanName(screen.getTitle().getString()).toLowerCase(Locale.ROOT);
        if (lower.contains("bazaar orders")) {
            bazaar(screen);
        } else if (lower.contains("manage auctions")) {
            auctions(screen);
        } else {
            lines = List.of();
        }
    }

    private static void bazaar(AbstractContainerScreen<?> screen) {
        long sellValue = 0;
        long buyValue = 0;
        int sellCount = 0;
        int buyCount = 0;
        long toClaim = 0;
        for (ItemStack stack : menuItems(screen)) {
            if (stack.getCustomName() == null) {
                continue;
            }
            String name = ScreenUtil.cleanName(stack.getCustomName().getString()).trim();
            String lowerName = name.toLowerCase(Locale.ROOT);
            boolean sell = lowerName.startsWith("sell ");
            boolean buy = lowerName.startsWith("buy ");
            if (!sell && !buy) {
                continue;
            }
            long amount = 0;
            long filled = 0;
            double unit = 0;
            for (String line : lore(stack)) {
                String low = line.toLowerCase(Locale.ROOT);
                if (low.startsWith("order amount") || low.startsWith("offer amount")) {
                    amount = (long) number(line);
                } else if (low.startsWith("filled:")) {
                    int slash = line.indexOf('/');
                    filled = (long) number(slash > 0 ? line.substring(0, slash) : line);
                    if (slash > 0 && amount <= 0) {
                        amount = (long) number(line.substring(slash + 1));
                    }
                } else if (low.startsWith("price per unit")) {
                    unit = number(line);
                }
            }
            if (amount <= 0 || unit <= 0) {
                continue;
            }
            long value = Math.round(amount * unit);
            if (sell) {
                sellValue += value;
                sellCount++;
                toClaim += Math.round(Math.min(filled, amount) * unit);
            } else {
                buyValue += value;
                buyCount++;
            }
        }
        if (sellCount == 0 && buyCount == 0) {
            lines = List.of();
            return;
        }
        List<Line> out = new ArrayList<>();
        out.add(new Line("Sell offers · " + sellCount, coins(sellValue), GlassStyle.STATUS, true));
        out.add(new Line("Buy orders · " + buyCount, coins(buyValue), 0xFFF0C27A, true));
        if (toClaim > 0) {
            out.add(new Line("Sold, to claim", coins(toClaim), GlassStyle.MUTED, false));
        }
        title = "Order totals";
        lines = out;
    }

    private static void auctions(AbstractContainerScreen<?> screen) {
        long listedValue = 0;
        long soldValue = 0;
        int listed = 0;
        int sold = 0;
        for (ItemStack stack : menuItems(screen)) {
            long buyNow = 0;
            long topBid = 0;
            long startBid = 0;
            long soldFor = 0;
            boolean isSold = false;
            boolean ended = false;
            for (String line : lore(stack)) {
                String low = line.toLowerCase(Locale.ROOT);
                if (low.startsWith("buy it now:")) {
                    buyNow = (long) number(line);
                } else if (low.startsWith("top bid:")) {
                    topBid = (long) number(line);
                } else if (low.startsWith("starting bid:")) {
                    startBid = (long) number(line);
                } else if (low.startsWith("sold for:")) {
                    soldFor = (long) number(line);
                } else if (low.startsWith("status: sold")) {
                    isSold = true;
                } else if (low.startsWith("status: expired") || low.startsWith("status: ended")) {
                    ended = true;
                }
            }
            if (isSold) {
                long value = soldFor > 0 ? soldFor : Math.max(buyNow, topBid);
                if (value > 0) {
                    soldValue += value;
                    sold++;
                }
                continue;
            }
            if (ended) {
                continue; // expired: nothing to get any more
            }
            long value = buyNow > 0 ? buyNow : Math.max(topBid, startBid);
            if (value > 0) {
                listedValue += value;
                listed++;
            }
        }
        if (listed == 0 && sold == 0) {
            lines = List.of();
            return;
        }
        long total = listedValue + soldValue;
        List<Line> out = new ArrayList<>();
        out.add(new Line("Listed · " + listed, coins(listedValue), GlassStyle.TEXT, false));
        if (sold > 0) {
            out.add(new Line("Sold, to collect · " + sold, coins(soldValue), GlassStyle.STATUS, false));
        }
        out.add(new Line("If all sell", coins(total), GlassStyle.STATUS, true));
        out.add(new Line("After 1% tax", coins(Math.round(total * (1 - AH_CLAIM_TAX))),
                GlassStyle.MUTED, false));
        title = "Auction totals";
        lines = out;
    }

    private static List<ItemStack> menuItems(AbstractContainerScreen<?> screen) {
        var slots = screen.getMenu().slots;
        int size = Math.max(0, slots.size() - 36); // without the player inventory
        List<ItemStack> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (!stack.isEmpty()) {
                items.add(stack);
            }
        }
        return items;
    }

    private static List<String> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Component line : lore.lines()) {
            out.add(ScreenUtil.cleanName(line.getString()).trim());
        }
        return out;
    }

    private static double number(String text) {
        int colon = text.indexOf(':');
        Matcher matcher = NUMBER.matcher(colon >= 0 ? text.substring(colon + 1) : text);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Double.parseDouble(matcher.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String coins(long value) {
        return ScreenUtil.formatCoins(value);
    }

    // ---------------------------------------------------------------- drawing

    private static void draw(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen,
                             int leftPos, int topPos, int imageWidth, List<Line> shown) {
        Minecraft client = Minecraft.getInstance();
        int height = PAD + 12 + 4;
        for (Line line : shown) {
            height += line.big() ? LINE + 2 : LINE;
        }
        height += PAD - 3;
        int x = leftPos + imageWidth + 6;
        if (x + WIDTH > screen.width - 2) {
            x = leftPos - WIDTH - 6; // no room on the right
        }
        if (x < 2) {
            return;
        }
        int y = topPos;
        g.nextStratum();
        GlassStyle.surface(g, x, y, WIDTH, height, .88f, .08f, false);
        g.nextStratum();
        g.text(client.font, GlassStyle.label(title), x + PAD, y + PAD - 1, GlassStyle.TEXT, false);
        g.fill(x + PAD, y + PAD + 11, x + WIDTH - PAD, y + PAD + 12,
                GlassStyle.alpha(0xFFFFFFFF, .12f));
        int rowY = y + PAD + 16;
        for (Line line : shown) {
            int labelColor = line.big() ? GlassStyle.TEXT : GlassStyle.MUTED;
            g.text(client.font, GlassStyle.label(line.label()), x + PAD, rowY, labelColor, false);
            int valueWidth = client.font.width(GlassStyle.label(line.value()));
            g.text(client.font, GlassStyle.label(line.value()),
                    x + WIDTH - PAD - valueWidth, rowY, line.color(), false);
            rowY += line.big() ? LINE + 2 : LINE;
        }
    }
}
