package com.coflnet.gui.panel;

import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.mining.MiningBlock;
import com.coflnet.mining.MiningFeatures;
import com.coflnet.mining.MiningState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import pingplus.voicechat.client.PlayerSettings;
import pingplus.voicechat.client.gui.glass.GlassStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Dwarven Mines at a glance: running event, king, cult meeting, powder per hour and mining pace. */
public final class DwarvenPanel extends FlipperPanel {
    public DwarvenPanel() {
        super("dwarven", "Dwarven Mines");
    }

    @Override protected boolean enabled() { return PlayerSettings.miningOverview; }
    @Override protected boolean glassEnabled() { return PlayerSettings.miningOverviewGlass; }
    @Override protected boolean edgesEnabled() { return PlayerSettings.miningOverviewEdges; }
    @Override protected int rowLimit() { return PlayerSettings.miningOverviewRows; }
    @Override protected boolean footerEnabled() { return PlayerSettings.miningOverviewFooter; }
    @Override protected boolean active() { return MiningState.event() != null; }

    @Override
    public boolean visible() {
        return enabled() && MiningState.inMiningIsland();
    }

    @Override
    protected List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        MiningFeatures.Stats stats = MiningFeatures.stats();

        // Mining pace and the ore you mined most.
        double perMin = stats.blocks / stats.minutes();
        MiningBlock top = null;
        int topCount = 0;
        for (Map.Entry<MiningBlock, int[]> e : stats.perBlock.entrySet()) {
            if (e.getValue()[0] > topCount) {
                topCount = e.getValue()[0];
                top = e.getKey();
            }
        }
        String topText = top == null ? "Start mining" : "Most: " + top.label + " x" + topCount
                + (stats.avgMs(top) > 0 ? String.format(Locale.ROOT, " · ⌀ %.2fs", stats.avgMs(top) / 1000.0) : "");
        rows.add(new Row("PACE", stats.blocks + " blocks", topText,
                String.format(Locale.ROOT, "%.1f/min", perMin), "", GlassStyle.STATUS, 0));

        // Powder gained this visit.
        Map<String, Long> gained = stats.powderGained;
        long mithril = gained.getOrDefault("Mithril", 0L);
        long gemstone = gained.getOrDefault("Gemstone", 0L);
        double hours = stats.minutes() / 60.0;
        String powderMain = mithril > 0 ? ScreenUtil.formatCoins(mithril / Math.max(hours, 1e-3)) + "/h" : "-";
        Long total = MiningState.powder().get("Mithril");
        String sub = "Mithril +" + ScreenUtil.formatCoins(mithril)
                + (gemstone > 0 ? " · Gemstone +" + ScreenUtil.formatCoins(gemstone) : "")
                + (total != null ? " · " + ScreenUtil.formatCoins(total) + " total" : "");
        rows.add(new Row("POWDER", "Powder", sub, powderMain, "", 0xFF66C7FF, 0));

        MiningState.MiningEvent event = MiningState.event();
        if (event != null) {
            rows.add(new Row("EVENT", event.name(), event.area().isEmpty() ? "Whole island" : event.area(),
                    event.timeLeft(), "", 0xFFE8D27A, 0));
        } else {
            rows.add(new Row("EVENT", "No mining event", "Events show up in the boss bar", "", "", GlassStyle.MUTED, 0));
        }

        String[] king = MiningFeatures.king();
        rows.add(new Row("KING", "King " + king[0], "Next: " + king[1], king[2], "", GlassStyle.TEXT, 0));

        Object[] cult = MiningFeatures.cult();
        boolean cultActive = (boolean) cult[0];
        rows.add(new Row("CULT", "Fallen Star Cult", cultActive ? "Meeting now in the cult room" : "Next meeting",
                (String) cult[1], "", cultActive ? GlassStyle.STATUS : GlassStyle.MUTED, 0));
        return rows;
    }

    @Override
    protected String footerText() {
        String where = MiningState.subArea();
        double speed = MiningState.miningSpeed();
        return (where.isEmpty() ? "Dwarven Mines" : capitalize(where))
                + (speed > 0 ? " · Speed " + Math.round(speed) : "");
    }

    private static String capitalize(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    @Override
    protected ItemStack iconFor(Row row) {
        return switch (row.tag()) {
            case "PACE" -> new ItemStack(Items.DIAMOND_PICKAXE);
            case "POWDER" -> new ItemStack(Items.SUGAR);
            case "EVENT" -> new ItemStack(Items.CLOCK);
            case "KING" -> new ItemStack(Items.GOLDEN_HELMET);
            case "CULT" -> new ItemStack(Items.NETHER_STAR);
            default -> new ItemStack(Items.PAPER);
        };
    }
}
