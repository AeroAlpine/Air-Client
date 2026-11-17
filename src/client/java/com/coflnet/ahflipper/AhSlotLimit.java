package com.coflnet.ahflipper;

import com.coflnet.flipper.trading.ScreenUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How many auctions the profile may have at once. Hypixel gives a solo profile 14
 * AH slots and every further co-op member adds 3; the slots are shared by the
 * whole co-op ("You own N auctions" in the AH menu counts all members).
 *
 * <p>{@link AhFlipperConfig#ahSlots} &gt; 0 is a fixed value set by the player.
 * 0 means automatic: the co-op size is learned from what the game shows -
 * "You are playing on profile: X (Co-op)", the sellers marked "Seller is in your
 * co-op!" in Manage Auctions, the highest "You own N auctions" ever seen and,
 * exactly, the owned count at the moment Hypixel says the maximum is reached.
 */
public final class AhSlotLimit {

    public static final int BASE_SLOTS = 14;
    public static final int SLOTS_PER_MEMBER = 3;

    private static final Pattern PROFILE_CHAT = Pattern.compile(
            "^You are playing on profile: (\\S+)( \\(Co-op\\))?.*$");

    private static volatile String profile = "";
    private static volatile boolean coopProfile;
    /** Highest owned count seen this session on the current profile. */
    private static volatile int observedMaxOwned;

    private AhSlotLimit() {
    }

    /** The number of auctions the flipper fills, at least 1. */
    public static int limit() {
        int manual = AhFlipperMod.CONFIG.ahSlots;
        if (manual > 0) {
            return manual;
        }
        return autoLimit();
    }

    public static boolean isManual() {
        return AhFlipperMod.CONFIG.ahSlots > 0;
    }

    public static synchronized int autoLimit() {
        Integer confirmed = confirmedMap().get(profileKey());
        if (confirmed != null && confirmed > 0 && confirmed >= observedMaxOwned) {
            return confirmed;
        }
        int estimate = slotsFor(coopMembers());
        if (observedMaxOwned > estimate) {
            // Hypixel never lets the co-op own more than its limit.
            estimate = roundUpToLimit(observedMaxOwned);
        }
        return estimate;
    }

    /**
     * Co-op members including the player, at least 1. Members without auctions
     * or orders never show their name, so the slot count Hypixel allowed (owned
     * auctions seen, or the exact limit) also tells how many there are.
     */
    public static synchronized int coopMembers() {
        int members = 1 + knownMembers().size();
        if (coopProfile) {
            members = Math.max(2, members);
        }
        int slots = Math.max(observedMaxOwned, confirmedMap().getOrDefault(profileKey(), 0));
        if (slots > BASE_SLOTS) {
            members = Math.max(members, 1 + (roundUpToLimit(slots) - BASE_SLOTS) / SLOTS_PER_MEMBER);
        }
        return members;
    }

    /** Names of the co-op members seen so far (without the player), lower case. */
    public static synchronized List<String> memberNames() {
        return List.copyOf(knownMembers());
    }

    public static boolean isCoop() {
        return coopMembers() > 1;
    }

    static int slotsFor(int members) {
        return BASE_SLOTS + SLOTS_PER_MEMBER * Math.max(0, members - 1);
    }

    static int roundUpToLimit(int owned) {
        if (owned <= BASE_SLOTS) {
            return BASE_SLOTS;
        }
        int extra = owned - BASE_SLOTS;
        return BASE_SLOTS + ((extra + SLOTS_PER_MEMBER - 1) / SLOTS_PER_MEMBER) * SLOTS_PER_MEMBER;
    }

    /** Short text for the settings: "23 (4 co-op members)". */
    public static String describe() {
        int members = coopMembers();
        String who = members > 1 ? members + " co-op members" : "solo";
        return autoLimit() + " slots (" + who + ")";
    }

    // ---------------------------------------------------------------- input

    /** Chat line of every profile switch / server join. */
    public static void onChat(String plain) {
        if (plain == null || !plain.startsWith("You are playing on profile:")) {
            return;
        }
        Matcher matcher = PROFILE_CHAT.matcher(plain.trim());
        if (!matcher.matches()) {
            return;
        }
        String name = matcher.group(1).toLowerCase(Locale.ROOT);
        synchronized (AhSlotLimit.class) {
            if (!name.equals(profile)) {
                observedMaxOwned = 0;
            }
            profile = name;
            coopProfile = matcher.group(2) != null;
        }
    }

    /** "You own N auctions in progress or which recently ended" of the AH menu. */
    public static synchronized void onOwnedCount(int owned) {
        if (owned <= 0) {
            return;
        }
        if (owned > observedMaxOwned) {
            observedMaxOwned = owned;
        }
        Integer confirmed = confirmedMap().get(profileKey());
        if (confirmed != null && owned > confirmed) {
            // The co-op grew: the old exact value is out of date.
            confirmedMap().remove(profileKey());
            AhFlipperMod.CONFIG.save();
        }
    }

    /** Hypixel refused a new auction ("reached the maximum") while owning {@code owned}. */
    public static synchronized void onMaximumReached(int owned) {
        if (owned <= 0) {
            return;
        }
        Integer old = confirmedMap().put(profileKey(), owned);
        if (old == null || old != owned) {
            System.out.println("[AH-Flipper] AH-Limit bestaetigt: " + owned + " Slots (Profil "
                    + profileKey() + ")");
            AhFlipperMod.CONFIG.save();
        }
    }

    /** A seller marked "Seller is in your co-op!" in an AH menu. */
    public static void onCoopSeller(String seller) {
        String name = ScreenUtil.cleanName(seller == null ? "" : seller).trim();
        int space = name.lastIndexOf(' ');
        if (space >= 0) {
            name = name.substring(space + 1); // "[VIP+] Name" -> "Name"
        }
        if (!name.matches("[A-Za-z0-9_]{2,16}")) {
            return;
        }
        if (AhFlipperMod.isOwnPlayer(name)) {
            return;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        synchronized (AhSlotLimit.class) {
            List<String> known = membersMap().computeIfAbsent(profileKey(), k -> new ArrayList<>());
            if (known.contains(lower)) {
                return;
            }
            known.add(lower);
            System.out.println("[AH-Flipper] Co-op-Mitglied erkannt: " + name + " -> "
                    + slotsFor(1 + known.size()) + " AH-Slots");
        }
        AhFlipperMod.CONFIG.save();
    }

    // ---------------------------------------------------------------- storage

    private static String profileKey() {
        String name = profile;
        return name == null || name.isBlank() ? "default" : name;
    }

    private static Set<String> knownMembers() {
        List<String> list = membersMap().get(profileKey());
        return list == null ? Set.of() : new LinkedHashSet<>(list);
    }

    private static Map<String, List<String>> membersMap() {
        AhFlipperConfig c = AhFlipperMod.CONFIG;
        if (c.coopMembersByProfile == null) {
            c.coopMembersByProfile = new HashMap<>();
        }
        return c.coopMembersByProfile;
    }

    private static Map<String, Integer> confirmedMap() {
        AhFlipperConfig c = AhFlipperMod.CONFIG;
        if (c.ahSlotsConfirmedByProfile == null) {
            c.ahSlotsConfirmedByProfile = new HashMap<>();
        }
        return c.ahSlotsConfirmedByProfile;
    }
}
