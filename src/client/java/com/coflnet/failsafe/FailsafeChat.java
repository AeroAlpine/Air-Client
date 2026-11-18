package com.coflnet.failsafe;

import com.coflnet.lowballing.LowballingChatClassification;
import com.coflnet.lowballing.LowballingChatClassifier;
import com.coflnet.lowballing.LowballingChatIntent;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hypixel chat lines aimed at us: a private message, or our name in all / party / guild chat.
 * The answer is short and casual, picked by what the line seems to be (the lowballer's chat
 * understanding), with extra answers for "are you macroing?".
 */
final class FailsafeChat {
    record Line(String sender, String message, String replyPrefix) {
    }

    // "From [MVP+] Name: hi"
    private static final Pattern DM = Pattern.compile("^From (?:\\[[^\\]]+\\] )?([A-Za-z0-9_]{3,16}): (.+)$");
    // "Party > [VIP] Name: hi", "Guild > [MVP] Name [Officer]: hi"
    private static final Pattern GROUP = Pattern.compile(
            "^(Party|Guild|Co-op) > (?:\\[[^\\]]+\\] )?([A-Za-z0-9_]{3,16})(?: \\[[^\\]]+\\])?: (.+)$");
    // "[123] ✦ [MVP+] Name: hi" (SkyBlock level, emblem, rank are optional)
    private static final Pattern ALL = Pattern.compile(
            "^(?:\\[\\d+\\] )?(?:\\S{1,2} )?(?:\\[[^\\]]+\\] )?([A-Za-z0-9_]{3,16})(?: [^:]{1,3})?: (.+)$");

    private static final List<String> MACRO = List.of("nah im here lol", "im not macroing gng", "bro im literally mining",
            "no lol", "why would i macro this lmao", "im right here", "nope just grinding");
    private static final List<String> GREETING = List.of("hey", "yo", "hi", "sup", "hey whats up");
    private static final List<String> QUESTION = List.of("?", "wdym", "huh", "what", "hm?");
    private static final List<String> INSULT = List.of("ok", "lol ok", "alr", "k");
    private static final List<String> OTHER = List.of("?", "yeah?", "wsg", "hm?", "whats up");

    private FailsafeChat() {
    }

    static Line parse(String text, String me) {
        String lowerMe = me.toLowerCase(Locale.ROOT);
        // NPCs and bosses say our name all the time: never answer them
        if (text.startsWith("[NPC]") || text.startsWith("[BOSS]") || text.startsWith("[SECRET]")
                || text.contains("[NPC] ")) {
            return null;
        }
        Matcher m = DM.matcher(text);
        if (m.matches()) {
            return m.group(1).equalsIgnoreCase(me) ? null : new Line(m.group(1), m.group(2), "r");
        }
        m = GROUP.matcher(text);
        if (m.matches()) {
            if (m.group(2).equalsIgnoreCase(me) || !mentions(m.group(3), lowerMe)) return null;
            String prefix = switch (m.group(1)) {
                case "Party" -> "pc";
                case "Guild" -> "gc";
                default -> "cc";
            };
            return new Line(m.group(2), m.group(3), prefix);
        }
        m = ALL.matcher(text);
        if (m.matches()) {
            if (m.group(1).equalsIgnoreCase(me) || !mentions(m.group(2), lowerMe)) return null;
            return new Line(m.group(1), m.group(2), "");
        }
        return null;
    }

    private static boolean mentions(String message, String lowerMe) {
        String lower = message.toLowerCase(Locale.ROOT);
        int i = lower.indexOf(lowerMe);
        if (i < 0) return false;
        boolean startOk = i == 0 || !Character.isLetterOrDigit(lower.charAt(i - 1)) && lower.charAt(i - 1) != '_';
        int end = i + lowerMe.length();
        boolean endOk = end >= lower.length() || !Character.isLetterOrDigit(lower.charAt(end)) && lower.charAt(end) != '_';
        return startOk && endOk;
    }

    static String reply(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("macro") || lower.contains("bot") || lower.contains("afk") || lower.contains("cheat")
                || lower.contains("hack") || lower.contains("script")) {
            return pick(MACRO);
        }
        LowballingChatIntent intent;
        try {
            LowballingChatClassification c = LowballingChatClassifier.classify(message, 0);
            intent = c.intent();
        } catch (RuntimeException e) {
            intent = LowballingChatIntent.UNKNOWN;
        }
        return switch (intent) {
            case GREETING -> pick(GREETING);
            case QUESTION, WEALTH_QUESTION -> pick(QUESTION);
            case INSULT, COMPLAINT -> pick(INSULT);
            default -> pick(OTHER);
        };
    }

    private static String pick(List<String> list) {
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }
}
