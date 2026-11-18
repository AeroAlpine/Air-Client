package com.coflnet.mining;

import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.mixin.MiningBossBarAccessor;
import com.coflnet.mixin.MiningHudAccessor;
import com.coflnet.utils.SkyblockLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the game tells us about mining, refreshed once a second from the tab list, sidebar and
 * boss bars: where we are, the open commissions, powder, mining speed and the running event.
 */
public final class MiningState {
    private static final Pattern COMMISSION = Pattern.compile("^(.+?): (DONE|[0-9]+(?:\\.[0-9]+)?%)$");
    private static final Pattern POWDER = Pattern.compile("^(Mithril|Gemstone|Glacite)(?: Powder)?: ([0-9,]+)$");
    private static final Pattern SPEED = Pattern.compile("^Mining Speed: \\D*([0-9,]+)");
    private static final Pattern EVENT = Pattern.compile(
            "^(?:PASSIVE )?EVENT (.+?) (?:RUNNING FOR|ACTIVE IN (.+?) FOR) ([0-9:]+)$", Pattern.CASE_INSENSITIVE);

    public record MiningEvent(String name, String area, String timeLeft) {
    }

    private static volatile String area = "";
    private static volatile String subArea = "";
    private static volatile List<Commission> commissions = List.of();
    private static volatile Map<String, Long> powder = Map.of();
    private static volatile double miningSpeed;
    private static volatile MiningEvent event;
    private static final Pattern MANA = Pattern.compile("([0-9][0-9,]*)/([0-9][0-9,]*)[^ ]* *Mana");
    private static volatile int mana = -1;
    private static volatile long manaAt;
    private static int ticks;

    private MiningState() {
    }

    public static void tick(Minecraft mc) {
        if (++ticks % 20 != 0) return;
        if (mc.player == null || mc.getConnection() == null) {
            area = "";
            return;
        }
        area = SkyblockLocation.tabArea();
        subArea = SkyblockLocation.sidebarLocation();
        if (!inMiningIsland()) {
            commissions = List.of();
            event = null;
            return;
        }
        readTab(mc);
        readBossBars(mc);
    }

    private static void readTab(Minecraft mc) {
        List<PlayerInfo> infos;
        try {
            infos = ((MiningHudAccessor) mc.gui.hud.getTabList()).mining$orderedPlayers();
        } catch (RuntimeException e) {
            infos = new ArrayList<>(mc.getConnection().getListedOnlinePlayers());
        }
        List<Commission> found = new ArrayList<>();
        Map<String, Long> powders = new LinkedHashMap<>();
        double speed = 0;
        boolean inCommissions = false;
        for (PlayerInfo info : infos) {
            Component display = info.getTabListDisplayName();
            if (display == null) continue;
            String raw = display.getString();
            String line = ScreenUtil.cleanName(raw);
            if (line.isEmpty()) {
                inCommissions = false;
                continue;
            }
            // Section headers start at the left edge; their entries are indented by one space.
            String stripped = net.minecraft.ChatFormatting.stripFormatting(raw);
            boolean header = stripped != null && !stripped.startsWith(" ") && line.endsWith(":");
            if (header) {
                inCommissions = line.equalsIgnoreCase("Commissions:");
                continue;
            }
            Matcher m = COMMISSION.matcher(line);
            if (inCommissions && m.matches()) {
                found.add(Commission.parse(m.group(1).trim(), m.group(2)));
                continue;
            }
            Matcher p = POWDER.matcher(line);
            if (p.matches()) {
                powders.put(p.group(1), parseLong(p.group(2)));
                continue;
            }
            Matcher s = SPEED.matcher(line);
            if (s.find()) speed = parseLong(s.group(1));
        }
        commissions = List.copyOf(found);
        powder = Map.copyOf(powders);
        if (speed > 0) miningSpeed = speed;
    }

    private static void readBossBars(Minecraft mc) {
        MiningEvent current = null;
        try {
            for (LerpingBossEvent bar : ((MiningBossBarAccessor) mc.gui.hud.getBossOverlay()).mining$events().values()) {
                String text = ScreenUtil.cleanName(bar.getName().getString()).replaceAll("\\s+", " ").trim();
                Matcher m = EVENT.matcher(text);
                if (m.matches()) {
                    current = new MiningEvent(title(m.group(1)), m.group(2) == null ? "" : title(m.group(2)), m.group(3));
                    break;
                }
            }
        } catch (RuntimeException ignored) {
        }
        event = current;
    }

    private static String title(String upper) {
        StringBuilder sb = new StringBuilder();
        for (String word : upper.toLowerCase(Locale.ROOT).split(" ")) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString().replace("2x", "2X");
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ reading

    public static boolean inDwarvenMines() {
        return area.equals("dwarven mines");
    }

    /** Dwarven Mines, Crystal Hollows or the Glacite Mineshafts. */
    public static boolean inMiningIsland() {
        return area.equals("dwarven mines") || area.equals("crystal hollows") || area.contains("mineshaft");
    }

    public static String area() {
        return area;
    }

    /** Sidebar location in the mines, lower case ("the forge", "royal mines"). */
    public static String subArea() {
        return subArea;
    }

    public static List<Commission> commissions() {
        return commissions;
    }

    public static Map<String, Long> powder() {
        return powder;
    }

    /** Mining speed from the tab list "Stats" widget, 0 when it is not shown. */
    public static double miningSpeed() {
        return miningSpeed;
    }

    public static MiningEvent event() {
        return event;
    }

    /** Action bar ("354/600 Mana"): remembered for a few seconds. */
    public static void onActionBar(String text) {
        Matcher m = MANA.matcher(text);
        if (m.find()) {
            mana = (int) parseLong(m.group(1));
            manaAt = System.currentTimeMillis();
        }
    }

    /** Current mana from the action bar, -1 when unknown. */
    public static int mana() {
        return System.currentTimeMillis() - manaAt < 10_000L ? mana : -1;
    }
}
