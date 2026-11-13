package com.coflnet.flipper.trading;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Hypixel's two daily Bazaar limits, counted from Hypixel's own chat lines:
 * <ul>
 *   <li>coins put into new buy orders ("Buy Order Setup!")</li>
 *   <li>value of new sell offers ("Sell Offer Setup!", "Order Flipped!")</li>
 * </ul>
 * Hypixel counts every created order, so a cancelled and re-listed offer counts again;
 * subtracting cancels made the tracker show 0.76B when Hypixel already said "limit reached".
 * Hypixel's own "You reached the daily limit ..." line marks a limit as reached.
 * The day rolls over at 00:00 UTC (01:00 Europe/Berlin in winter).
 */
public final class BazaarDailyLimit {
    public static final long LIMIT_COINS = 10_000_000_000L;
    /** Stop a little early: an order that crosses the limit is refused anyway. */
    private static final long SAFETY_COINS = 100_000_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean loaded;
    private static String tradeDay;
    private static long buyCoins;
    private static long sellCoins;
    private static boolean buyReached;
    private static boolean sellReached;
    private static long resetAt;

    private BazaarDailyLimit() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("CoflSky").resolve("bazaar-daily-limit.json");
    }

    private static String currentTradeDay() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path path = file();
            if (Files.exists(path)) {
                Data data = GSON.fromJson(Files.readString(path), Data.class);
                if (data != null) {
                    tradeDay = data.day;
                    buyCoins = Math.max(0L, data.buy);
                    sellCoins = Math.max(0L, data.sold);
                    buyReached = data.buyReached;
                    sellReached = data.sellReached;
                    resetAt = data.resetAt;
                }
            }
        } catch (Exception e) {
            System.out.println("[BazaarLimit] Load failed: " + e.getMessage());
        }
        rollIfNeeded();
    }

    private static void rollIfNeeded() {
        String today = currentTradeDay();
        if (!today.equals(tradeDay)) {
            tradeDay = today;
            buyCoins = 0L;
            sellCoins = 0L;
            buyReached = false;
            sellReached = false;
            resetAt = System.currentTimeMillis();
            save();
        }
    }

    /** Every Bazaar chat event: the source of truth for both counters. */
    static synchronized void onEvent(BazaarChatEvent event) {
        if (event == null) {
            return;
        }
        ensureLoaded();
        rollIfNeeded();
        switch (event.type) {
            case BUY_SETUP -> buyCoins += Math.max(0L, Math.round(event.price));
            case SELL_SETUP, FLIPPED -> sellCoins += Math.max(0L, Math.round(event.price));
            case DAILY_BUY_LIMIT -> {
                buyReached = true;
                System.out.println("[BazaarLimit] Hypixel: buy order limit reached (tracked "
                        + buyCoins + ")");
            }
            case DAILY_SELL_LIMIT -> {
                sellReached = true;
                System.out.println("[BazaarLimit] Hypixel: sell limit reached (tracked "
                        + sellCoins + ")");
            }
            default -> {
                return;
            }
        }
        save();
    }

    public static synchronized long buyCoins() {
        ensureLoaded();
        rollIfNeeded();
        return buyCoins;
    }

    public static synchronized long sold() {
        ensureLoaded();
        rollIfNeeded();
        return sellCoins;
    }

    public static synchronized boolean buyLimitReached() {
        ensureLoaded();
        rollIfNeeded();
        return buyReached || buyCoins >= LIMIT_COINS - SAFETY_COINS;
    }

    public static synchronized boolean sellLimitReached() {
        ensureLoaded();
        rollIfNeeded();
        return sellReached || sellCoins >= LIMIT_COINS - SAFETY_COINS;
    }

    /** No new buy orders: either they are refused, or what they buy could not be listed today. */
    public static synchronized boolean reached() {
        return buyLimitReached() || sellLimitReached();
    }

    public static synchronized String tradeDay() {
        ensureLoaded();
        rollIfNeeded();
        return tradeDay;
    }

    public static synchronized long resetAt() {
        ensureLoaded();
        rollIfNeeded();
        return resetAt;
    }

    /** Short status for chat and panels, e.g. "Buy 2.1B/10B · Sell 3.4B/10B". */
    public static String summary() {
        return "Buy " + ScreenUtil.formatCoins(buyCoins()) + (buyLimitReached() ? " (max)" : "")
                + "/" + ScreenUtil.formatCoins(LIMIT_COINS)
                + " · Sell " + ScreenUtil.formatCoins(sold()) + (sellLimitReached() ? " (max)" : "")
                + "/" + ScreenUtil.formatCoins(LIMIT_COINS);
    }

    private static void save() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Data data = new Data();
            data.day = tradeDay;
            data.buy = buyCoins;
            data.sold = sellCoins;
            data.buyReached = buyReached;
            data.sellReached = sellReached;
            data.resetAt = resetAt;
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, path,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.out.println("[BazaarLimit] Save failed: " + e.getMessage());
        }
    }

    private static final class Data {
        String day;
        long buy;
        long sold;
        boolean buyReached;
        boolean sellReached;
        long resetAt;
    }
}
