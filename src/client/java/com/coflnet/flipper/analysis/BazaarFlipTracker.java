package com.coflnet.flipper.analysis;

import com.coflnet.flipper.FlipperConfig;
import com.coflnet.flipper.FlipperMod;
import com.coflnet.flipper.data.BazaarApi;
import com.coflnet.flipper.data.MarketData;
import com.coflnet.flipper.trading.BazaarLimits;
import com.coflnet.flipper.trading.ScreenUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Background scan for the Bazaar flips screen: the same analysis the Bazaar flipper runs
 * ({@link FlipAnalyzer#candidates}), with the same filters, so the list shows what the bot would
 * buy. Runs only while the screen is open; the last result is kept for the next time.
 */
public final class BazaarFlipTracker {
    /** A purse that can not be read (not on SkyBlock): plan with this much. */
    private static final double FALLBACK_BANKROLL = 100_000_000.0;
    private static final long SCAN_BUDGET_MS = 45_000L;

    public enum Sort {
        CPH("Coins/h"), MARGIN("Margin"), PROFIT("Profit/flip"), SCORE("Score");

        public final String label;

        Sort(String label) {
            this.label = label;
        }

        public static Sort of(String name) {
            try {
                return Sort.valueOf(name == null ? "CPH" : name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return CPH;
            }
        }

        public Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public static final class Snapshot {
        public final List<FlipAnalyzer.FlipPlan> top;
        public final Set<String> itemModeProducts;
        public final int candidateCount;
        public final double bankroll;
        public final boolean bankrollKnown;
        public final long updatedAt;
        public final String error;

        Snapshot(List<FlipAnalyzer.FlipPlan> top, Set<String> itemModeProducts, int candidateCount,
                 double bankroll, boolean bankrollKnown, long updatedAt, String error) {
            this.top = top;
            this.itemModeProducts = itemModeProducts;
            this.candidateCount = candidateCount;
            this.bankroll = bankroll;
            this.bankrollKnown = bankrollKnown;
            this.updatedAt = updatedAt;
            this.error = error;
        }
    }

    private static volatile Snapshot snapshot = new Snapshot(List.of(), Set.of(), 0, 0, false, 0, null);
    /** Every candidate of the last scan, so a new sort needs no rescan. */
    private static volatile List<FlipAnalyzer.FlipPlan> lastCandidates = List.of();
    private static volatile boolean running;
    private static volatile boolean scanning;
    private static volatile boolean refreshRequested;
    private static volatile long scanStartedAt;
    private static Thread thread;

    private BazaarFlipTracker() {
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static boolean scanning() {
        return scanning;
    }

    public static long scanStartedAt() {
        return scanStartedAt;
    }

    public static synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(BazaarFlipTracker::loop, "Bazaar-Flips-Scan");
        thread.setDaemon(true);
        thread.start();
    }

    public static synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    public static void requestRefresh() {
        refreshRequested = true;
    }

    /** Re-sorts the last scan (sort changed in the screen). */
    public static void resort() {
        Snapshot current = snapshot;
        snapshot = new Snapshot(pickTop(lastCandidates), current.itemModeProducts, current.candidateCount,
                current.bankroll, current.bankrollKnown, current.updatedAt, current.error);
    }

    private static void loop() {
        while (running) {
            FlipperConfig cfg = FlipperMod.CONFIG;
            long interval = Math.max(1, cfg.flipsRefreshMinutes) * 60_000L;
            boolean due = snapshot.updatedAt == 0
                    || System.currentTimeMillis() - snapshot.updatedAt >= interval;
            if (refreshRequested || due) {
                refreshRequested = false;
                scan(cfg);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void scan(FlipperConfig cfg) {
        scanning = true;
        scanStartedAt = System.currentTimeMillis();
        try {
            MarketData data = BazaarApi.fetch();
            if (data == null || data.products.isEmpty()) {
                fail("Bazaar API not reachable");
                return;
            }
            long purse = ScreenUtil.readPurse();
            boolean known = purse > 0;
            double bankroll = known ? Math.max(0, purse - cfg.reserveCoins) : FALLBACK_BANKROLL;
            if (cfg.maxInvestedMillions > 0) {
                bankroll = Math.min(bankroll, cfg.maxInvestedMillions * 1_000_000.0);
            }
            if (bankroll <= 0) {
                fail("Purse below the reserve (" + ScreenUtil.formatCoins(cfg.reserveCoins) + ")");
                return;
            }
            List<FlipAnalyzer.FlipPlan> candidates = FlipAnalyzer.candidates(data, cfg, bankroll, null,
                    () -> !running, SCAN_BUDGET_MS);
            Set<String> itemMode = new HashSet<>(BazaarLimits.itemModeProducts(cfg, data));
            lastCandidates = List.copyOf(candidates);
            snapshot = new Snapshot(pickTop(candidates), itemMode, candidates.size(), bankroll, known,
                    System.currentTimeMillis(), null);
        } catch (RuntimeException e) {
            fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            scanning = false;
        }
    }

    private static void fail(String error) {
        Snapshot current = snapshot;
        snapshot = new Snapshot(current.top, current.itemModeProducts, current.candidateCount,
                current.bankroll, current.bankrollKnown, System.currentTimeMillis(), error);
    }

    static List<FlipAnalyzer.FlipPlan> pickTop(List<FlipAnalyzer.FlipPlan> candidates) {
        FlipperConfig cfg = FlipperMod.CONFIG;
        Comparator<FlipAnalyzer.FlipPlan> order = switch (Sort.of(cfg.flipsSort)) {
            case MARGIN -> Comparator.comparingDouble(p -> p.marginPercent);
            case PROFIT -> Comparator.comparingDouble(FlipAnalyzer.FlipPlan::expectedProfit);
            case SCORE -> Comparator.comparingDouble(p -> p.score);
            case CPH -> Comparator.comparingDouble(p -> p.coinsPerHour);
        };
        List<FlipAnalyzer.FlipPlan> sorted = new ArrayList<>(candidates);
        sorted.sort(order.reversed());
        int count = Math.max(1, Math.min(25, cfg.flipsTopCount));
        return List.copyOf(sorted.subList(0, Math.min(count, sorted.size())));
    }
}
