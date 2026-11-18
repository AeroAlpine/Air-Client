package com.coflnet.mining;

import java.util.List;
import java.util.Locale;

/**
 * One line of the "Commissions:" tab list section, e.g. "Royal Mines Titanium: 40%" or
 * "Goblin Slayer: DONE", with what has to be done for it.
 */
public record Commission(String name, double progress, boolean done, String area, Goal goal, List<String> mobs) {

    public enum Goal { MITHRIL, TITANIUM, MOBS, GEMSTONE, EVENT, OTHER }

    /** Areas a commission can be bound to (prefix of its name), as they appear in the sidebar. */
    public static final List<String> AREAS = List.of("Upper Mines", "Royal Mines", "Lava Springs",
            "Cliffside Veins", "Rampart's Quarry", "Far Reserve", "Goblin Burrows", "Divan's Gateway",
            "Aristocrat Passage", "The Forge", "Forge Basin", "Palace Bridge", "Royal Palace", "Royal Quarters",
            "The Mist", "Hanging Court", "Great Ice Wall", "Dwarven Village");

    public static Commission parse(String name, String value) {
        boolean done = value.equalsIgnoreCase("DONE");
        double progress = done ? 1 : 0;
        if (!done) {
            try {
                progress = Math.min(1, Double.parseDouble(value.replace("%", "").trim()) / 100.0);
            } catch (NumberFormatException ignored) {
            }
        }
        String area = "";
        for (String a : AREAS) {
            if (name.startsWith(a + " ")) {
                area = a;
                break;
            }
        }
        String lower = name.toLowerCase(Locale.ROOT);
        Goal goal;
        List<String> mobs = List.of();
        if (lower.contains("event") || lower.contains("raffle") || lower.contains("goblin raid")
                || lower.contains("powder collector") || lower.contains("better together")) {
            // Needs a running mining event: nothing a bot can start on its own.
            goal = Goal.EVENT;
        } else if (lower.contains("mines slayer")) {
            // "Mines Slayer": any Dwarven Mines mob; Glacite Walkers are the easiest.
            goal = Goal.MOBS;
            mobs = List.of("Glacite Walker", "Ice Walker");
        } else if (lower.contains("titanium")) {
            goal = Goal.TITANIUM;
        } else if (lower.contains("mithril") && !lower.contains("powder")) {
            goal = Goal.MITHRIL;
        } else if (lower.contains("gemstone")) {
            goal = Goal.GEMSTONE;
        } else if (lower.contains("golden goblin")) {
            goal = Goal.MOBS;
            mobs = List.of("Golden Goblin");
        } else if (lower.contains("goblin")) {
            goal = Goal.MOBS;
            mobs = List.of("Goblin", "Weakling", "Knifethrower", "Fireslinger");
        } else if (lower.contains("glacite walker") || lower.contains("ice walker")) {
            goal = Goal.MOBS;
            mobs = List.of("Ice Walker", "Glacite Walker");
        } else if (lower.contains("treasure hoarder")) {
            goal = Goal.MOBS;
            // Hypixel spells the mob "Treasuer Hunter".
            mobs = List.of("Treasuer Hunter", "Treasure Hunter", "Treasure Hoarder");
        } else if (lower.contains("star sentry") || lower.contains("sentry")) {
            goal = Goal.MOBS;
            mobs = List.of("Crystal Sentry", "Star Sentry");
        } else if (lower.endsWith(" slayer") || lower.endsWith(" puncher")) {
            goal = Goal.MOBS;
            mobs = List.of(name.substring(0, name.lastIndexOf(' ')));
        } else {
            goal = Goal.OTHER;
        }
        return new Commission(name, progress, done, area, goal, mobs);
    }

    /** What the player should do, for the panel's second line. */
    public String hint() {
        if (done) return "Done - claim it at the Emissary or King";
        String where = area.isEmpty() ? "" : " in " + area;
        return switch (goal) {
            case MITHRIL -> "Mine Mithril" + where;
            case TITANIUM -> "Mine Titanium" + where;
            case GEMSTONE -> "Mine gemstones" + where;
            case MOBS -> "Kill " + String.join(" / ", mobs.subList(0, Math.min(2, mobs.size()))) + where;
            case EVENT -> "Needs a mining event";
            case OTHER -> where.isEmpty() ? "" : where.substring(1);
        };
    }

    /** True when the block counts for this commission. */
    public boolean wants(MiningBlock block) {
        if (done || block == null) return false;
        return switch (goal) {
            case MITHRIL -> block.isMithril();
            case TITANIUM -> block == MiningBlock.TITANIUM;
            case GEMSTONE -> block.group == MiningBlock.Group.GEMSTONE;
            default -> false;
        };
    }
}
