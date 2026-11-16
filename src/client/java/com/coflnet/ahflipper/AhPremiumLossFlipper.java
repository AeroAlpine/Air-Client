package com.coflnet.ahflipper;

import com.coflnet.flipper.trading.ScreenUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

final class AhPremiumLossFlipper {
    private static final Object LOCK = new Object();

    private static volatile boolean running;
    private static Thread worker;
    private static boolean pending;
    private static int flipsSince;
    private static int nextAfter;
    private static long nextAt;

    private AhPremiumLossFlipper() {
    }

    /** PREMIUM-LOSS-FLIP: start with the AH flipper. */
    public static void start() {
        if (running || !AhFlipperMod.CONFIG.premiumLossFlip
                || !AhFlipperMod.isRunning()) {
            return;
        }
        running = true;
        resetCycle();
        worker = new Thread(AhPremiumLossFlipper::loop, "skycofl-premium-loss-flip");
        worker.setDaemon(true);
        worker.start();
    }

    /** PREMIUM-LOSS-FLIP: stop with the AH flipper. */
    public static void stop() {
        running = false;
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
        worker = null;
        synchronized (LOCK) {
            pending = false;
            flipsSince = 0;
            nextAt = 0;
        }
    }

    /** PREMIUM-LOSS-FLIP: called after a successful normal auto-buy. */
    public static void onNormalFlip() {
        if (!canRun()) {
            return;
        }
        synchronized (LOCK) {
            if (pending) {
                return;
            }
            flipsSince++;
            if (flipsSince < nextAfter) {
                return;
            }
            pending = true;
            nextAt = System.currentTimeMillis() + randomBetween(
                    AhFlipperMod.CONFIG.premiumLossFlipDelayMinSeconds,
                    AhFlipperMod.CONFIG.premiumLossFlipDelayMaxSeconds) * 1000L;
        }
    }

    /** PREMIUM-LOSS-FLIP: exact relist price for a premium buy. */
    public static long premiumListPrice(long paid, double markupPercent) {
        if (paid <= 0) {
            return 0;
        }
        double markup = Math.max(0, markupPercent);
        long marked = Math.round(paid * (1.0 + markup / 100.0));
        return Math.max(paid + 1, marked);
    }

    private static void loop() {
        while (running) {
            try {
                Thread.sleep(1000L);
                if (!running) {
                    break;
                }
                synchronized (LOCK) {
                    if (!pending || now() < nextAt) {
                        continue;
                    }
                }
                attemptPremiumBuy();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
            }
        }
    }

    private static void attemptPremiumBuy() {
        if (!canRun()) {
            finishCycle();
            return;
        }
        long purse = ScreenUtil.readPurse();
        long bankroll = purse > 0 ? Math.max(0, purse - AhFlipperMod.CONFIG.reserveCoins) : -1;
        if (bankroll > 0 && AhFlipperMod.CONFIG.maxBankrollCoins > 0) {
            bankroll = Math.min(bankroll, AhFlipperMod.CONFIG.maxBankrollCoins);
        }
        long maxPrice = bankroll > 0
                ? Math.round(bankroll * AhFlipperMod.CONFIG.maxBankFractionPerOrder) : -1;
        long minPrice = Math.max(AhFlipperMod.CONFIG.liveMinPrice,
                AhFlipperMod.CONFIG.minItemCoins);

        List<AhMarketData.ActiveAuction> auctions = fetchPremiumAuctions();
        long now = System.currentTimeMillis();
        List<AhMarketData.ActiveAuction> candidates = new ArrayList<>();
        for (AhMarketData.ActiveAuction auction : auctions) {
            if (!auction.bin || auction.uuid.isBlank()) {
                continue;
            }
            if (auction.end > 0 && auction.end <= now) {
                continue;
            }
            if (auction.price <= 0 || auction.price < minPrice) {
                continue;
            }
            if (maxPrice > 0 && auction.price > maxPrice) {
                continue;
            }
            candidates.add(auction);
        }
        Collections.shuffle(candidates);
        int limit = Math.min(candidates.size(),
                Math.max(1, AhFlipperMod.CONFIG.premiumLossFlipMaxScan));

        List<Candidate> eligible = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            Candidate candidate = evaluate(candidates.get(i), minPrice, maxPrice, now);
            if (candidate != null) {
                eligible.add(candidate);
            }
        }
        if (eligible.isEmpty()) {
            finishCycle();
            return;
        }
        Candidate pick = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
        AhFlipperMod.enqueuePremium(pick.auction.uuid, pick.value, 0);
        finishCycle();
    }

    /**
     * PREMIUM-LOSS-FLIP: active auctions of every premium player via the Cofl
     * active-auction filter. Cofl needs the seller UUID, so the names from
     * {@link PriorityAccess} are resolved once via Mojang and cached.
     */
    private static List<AhMarketData.ActiveAuction> fetchPremiumAuctions() {
        Map<String, AhMarketData.ActiveAuction> unique = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (String name : PriorityAccess.premiumPlayers()) {
            String uuid = AhProtectedBidders.uuidOf(name);
            if (uuid.isBlank()) {
                continue;
            }
            List<AhMarketData.ActiveAuction> found = AhMarketData.activeAuctionsBySeller(uuid, 3);
            for (AhMarketData.ActiveAuction auction : found) {
                if (auction.end <= 0 || auction.end > now) {
                    unique.putIfAbsent(auction.uuid, auction);
                }
            }
            try {
                Thread.sleep(350L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static Candidate evaluate(AhMarketData.ActiveAuction auction,
                                      long minPrice, long maxPrice, long now) {
        if (auction == null || auction.uuid == null || auction.uuid.isBlank()
                || !auction.bin) {
            return null;
        }
        if (auction.end > 0 && auction.end <= now) {
            return null;
        }
        long price = auction.price;
        if (price < minPrice || (maxPrice > 0 && price > maxPrice)) {
            return null;
        }
        AhMarketData.AuctionDetails details;
        try {
            details = AhMarketData.auction(auction.uuid);
        } catch (Exception e) {
            return null;
        }
        if (details == null || details.tag.isBlank() || !details.bin) {
            return null;
        }
        if (details.end > 0 && details.end <= now) {
            return null;
        }
        if (details.startingBid > 0) {
            price = details.startingBid;
        }
        if (price < minPrice || (maxPrice > 0 && price > maxPrice)) {
            return null;
        }
        AhItem item = AhItem.fromAuction(details);
        if (item.isPet() && !AhFlipperMod.CONFIG.petsEnabled) {
            return null;
        }
        if (AhFlipperMod.CONFIG.isBlockedItem(item.tag, item.displayName)) {
            return null;
        }
        AhAnalyzer.Result result;
        try {
            // expectedProfit=0 keeps the normal filters but skips the normal
            // +10 % profit gate; the loss tolerance is checked below.
            result = AhAnalyzer.analyze(item, price, 0, auction.uuid, minPrice);
        } catch (Exception e) {
            return null;
        }
        if (result == null || !result.buyable || result.expectedValue <= 1) {
            return null;
        }
        if (!Double.isFinite(result.profitPercent)
                || result.profitPercent < -AhFlipperMod.CONFIG.premiumLossFlipMaxLossPercent) {
            return null;
        }
        return new Candidate(auction, result.expectedValue);
    }

    private static boolean canRun() {
        return running
                && AhFlipperMod.CONFIG.premiumLossFlip
                && AhFlipperMod.isRunning()
                && AhFlipperMod.isAutoBuyOn()
                && (!AhFlipperMod.CONFIG.premiumLossFlipNonPremiumOnly
                || !PriorityAccess.isLocalPriority());
    }

    private static void finishCycle() {
        synchronized (LOCK) {
            pending = false;
            flipsSince = 0;
            nextAt = 0;
            nextAfter = randomBetween(AhFlipperMod.CONFIG.premiumLossFlipAfterMin,
                    AhFlipperMod.CONFIG.premiumLossFlipAfterMax);
        }
    }

    private static void resetCycle() {
        synchronized (LOCK) {
            pending = false;
            flipsSince = 0;
            nextAt = 0;
            nextAfter = randomBetween(AhFlipperMod.CONFIG.premiumLossFlipAfterMin,
                    AhFlipperMod.CONFIG.premiumLossFlipAfterMax);
        }
    }

    private static int randomBetween(int min, int max) {
        int low = Math.max(1, min);
        int high = Math.max(low, max);
        return low == high ? low : ThreadLocalRandom.current().nextInt(low, high + 1);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private record Candidate(AhMarketData.ActiveAuction auction, long value) {
    }
}