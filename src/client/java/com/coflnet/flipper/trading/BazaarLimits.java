package com.coflnet.flipper.trading;

import com.coflnet.flipper.FlipperConfig;
import com.coflnet.flipper.data.ItemNames;
import com.coflnet.flipper.data.MarketData;
import com.coflnet.session.BotSessions;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Limits of the Bazaar flipper:
 * <ul>
 *   <li><b>Max coins per session</b>: the value of every sell offer it sets up
 *       (also re-created / flipped ones) is added up; at the limit it places no
 *       new buy orders any more and only sells what it has.</li>
 *   <li><b>Max coins invested</b>: buy orders + sell offers on the Bazaar may
 *       not be worth more than this together.</li>
 *   <li><b>Item mode</b>: only the listed items are flipped.</li>
 * </ul>
 */
public final class BazaarLimits {
    private static double sessionSellVolume;
    private static long sessionKey = -1;
    private static boolean capAnnounced;

    private BazaarLimits() {
    }

    /** Every Bazaar chat event; counts the sell offers of this session. */
    static synchronized void onEvent(BazaarChatEvent event) {
        if (event == null || event.price <= 0) {
            return;
        }
        if (event.type == BazaarChatEvent.Type.SELL_SETUP || event.type == BazaarChatEvent.Type.FLIPPED) {
            syncSession();
            sessionSellVolume += event.price;
        }
    }

    /** A new Bazaar session (start, or end of a break) starts counting from 0. */
    private static void syncSession() {
        long key = BotSessions.sessionStartedAt(BotSessions.Bot.BAZAAR);
        if (key != sessionKey) {
            sessionKey = key;
            sessionSellVolume = 0;
            capAnnounced = false;
        }
    }

    public static synchronized double sessionSellVolume() {
        syncSession();
        return sessionSellVolume;
    }

    /** Sell offers of this session reached the configured maximum. */
    public static synchronized boolean sessionCapReached(FlipperConfig cfg) {
        if (cfg.maxCoinsPerSessionMillions <= 0) {
            return false;
        }
        syncSession();
        return sessionSellVolume >= cfg.maxCoinsPerSessionMillions * 1_000_000.0;
    }

    /** True once, when the cap was reached (for one chat message). */
    static synchronized boolean announceCap() {
        if (capAnnounced) {
            return false;
        }
        capAnnounced = true;
        return true;
    }

    /** Coins in open buy orders and sell offers (the part not filled yet). */
    static double invested(List<ScreenUtil.GuiOrder> buyOrders, List<ScreenUtil.GuiOrder> sellOffers) {
        double total = 0;
        for (ScreenUtil.GuiOrder order : buyOrders) {
            total += open(order);
        }
        for (ScreenUtil.GuiOrder order : sellOffers) {
            total += open(order);
        }
        return total;
    }

    private static double open(ScreenUtil.GuiOrder order) {
        long remaining = Math.max(0, order.amount - Math.max(0, order.filled));
        return Math.max(0, order.unitPrice) * remaining;
    }

    /** Item mode on and at least one item listed. */
    public static boolean itemModeActive(FlipperConfig cfg) {
        return cfg.onlyItemsMode && cfg.onlyItems != null && !cfg.onlyItems.isEmpty();
    }

    /** In item mode: every product that is not listed (the analyzer skips them). */
    static Set<String> excludedByItemMode(FlipperConfig cfg, MarketData data) {
        Set<String> excluded = new HashSet<>();
        if (!itemModeActive(cfg) || data == null) {
            return excluded;
        }
        migrateItemList(cfg, data);
        for (String productId : data.products.keySet()) {
            if (!allowed(cfg.onlyItems, productId, ItemNames.displayName(productId))) {
                excluded.add(productId);
            }
        }
        return excluded;
    }

    /**
     * Item mode: may the bot cancel, reprice or merge this order? Only orders of listed items;
     * orders of everything else stay untouched (claiming filled ones is still allowed).
     */
    public static boolean itemModeAllowsOrder(FlipperConfig cfg, String orderItemName) {
        if (!itemModeActive(cfg)) {
            return true;
        }
        migrateItemList(cfg, null);
        return allowed(cfg.onlyItems, ItemNames.productId(orderItemName), orderItemName);
    }

    /**
     * Listed by its exact Bazaar name ("Recombobulator 3000") or product id
     * ("RECOMBOBULATOR_3000"). Symbols and case do not count ("☠ Perfect Onyx Gemstone" =
     * "perfect onyx gemstone"); parts of a name do not match any more.
     */
    static boolean allowed(List<String> items, String productId, String displayName) {
        String id = normalize(productId);
        String name = normalize(displayName);
        for (String entry : items) {
            String wanted = normalize(entry);
            if (!wanted.isEmpty() && (wanted.equals(name) || wanted.equals(id))) {
                return true;
            }
        }
        return false;
    }

    /** Most products one entry may expand to ("onyx" = the five Onyx gemstones). */
    static final int MAX_MATCHES = 8;

    /**
     * The exact Bazaar products meant by what the user typed: an exact name or product id first,
     * otherwise every product whose name words start with the typed words ("recomb" ->
     * "Recombobulator 3000", "perf onyx" -> "Perfect Onyx Gemstone"). Sorted by name.
     */
    public static List<String> resolveItem(String input, MarketData data) {
        String wanted = normalize(input);
        java.util.Map<String, String> products = new java.util.TreeMap<>(ItemNames.allProducts());
        if (data != null) {
            for (String id : data.products.keySet()) {
                products.putIfAbsent(id, ItemNames.displayName(id));
            }
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        if (wanted.isEmpty()) {
            return result;
        }
        for (java.util.Map.Entry<String, String> e : products.entrySet()) {
            if (wanted.equals(normalize(e.getValue()))) {
                result.add(e.getValue().trim());
                return result;
            }
        }
        // A product id only when one was typed ("ONYX" is Black Diamond, "onyx" means the gems).
        String raw = input.trim();
        if (raw.contains("_") || (raw.equals(raw.toUpperCase(Locale.ROOT)) && !raw.equals(raw.toLowerCase(Locale.ROOT)))) {
            for (java.util.Map.Entry<String, String> e : products.entrySet()) {
                if (wanted.equals(normalize(e.getKey()))) {
                    result.add(e.getValue().trim());
                    return result;
                }
            }
        }
        String[] parts = wanted.split(" ");
        for (java.util.Map.Entry<String, String> e : products.entrySet()) {
            String[] words = normalize(e.getValue()).split(" ");
            boolean all = true;
            for (String part : parts) {
                boolean found = false;
                for (String word : words) {
                    if (word.startsWith(part)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    all = false;
                    break;
                }
            }
            if (all && !result.contains(e.getValue().trim())) {
                result.add(e.getValue().trim());
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    /**
     * Older lists hold what was typed ("recomb"); exact matching needs the real names. Entries
     * that are not an exact product name are replaced by what they resolve to.
     */
    static synchronized void migrateItemList(FlipperConfig cfg, MarketData data) {
        if (cfg.onlyItems == null || cfg.onlyItems.isEmpty()) {
            return;
        }
        java.util.List<String> fixed = new java.util.ArrayList<>();
        boolean changed = false;
        for (String entry : cfg.onlyItems) {
            List<String> resolved = resolveItem(entry, data);
            boolean exact = resolved.size() == 1 && normalize(resolved.get(0)).equals(normalize(entry));
            if (exact || resolved.isEmpty() || resolved.size() > MAX_MATCHES) {
                if (!fixed.contains(entry)) {
                    fixed.add(entry);
                }
                continue;
            }
            for (String name : resolved) {
                if (fixed.stream().noneMatch(f -> normalize(f).equals(normalize(name)))) {
                    fixed.add(name);
                }
            }
            changed = true;
            System.out.println("[Flipper] Item-Modus: '" + entry + "' -> " + resolved);
        }
        if (changed) {
            cfg.onlyItems = fixed;
            cfg.save();
        }
    }

    /** Lower case letters, digits and single spaces only. */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("(?i)§[0-9A-FK-OR]", "").replace('_', ' ')
                .replaceAll("[^\\p{L}\\p{N} ]", " ").trim().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
