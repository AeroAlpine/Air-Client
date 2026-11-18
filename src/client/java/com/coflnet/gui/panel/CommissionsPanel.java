package com.coflnet.gui.panel;

import com.coflnet.mining.Commission;
import com.coflnet.mining.MiningState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import pingplus.voicechat.client.PlayerSettings;
import pingplus.voicechat.client.gui.glass.GlassStyle;

import java.util.ArrayList;
import java.util.List;

/** Open commissions from the tab list with progress bars and what to do for each. */
public final class CommissionsPanel extends FlipperPanel {
    public CommissionsPanel() {
        super("commissions", "Commissions");
    }

    @Override protected boolean enabled() { return PlayerSettings.miningCommissions && PlayerSettings.miningCommissionPanel; }
    @Override protected boolean glassEnabled() { return PlayerSettings.miningCommissionGlass; }
    @Override protected boolean edgesEnabled() { return PlayerSettings.miningCommissionEdges; }
    @Override protected int rowLimit() { return PlayerSettings.miningCommissionRows; }
    @Override protected boolean footerEnabled() { return PlayerSettings.miningCommissionFooter; }
    @Override protected boolean active() { return !MiningState.commissions().isEmpty(); }

    @Override
    public boolean visible() {
        return enabled() && MiningState.inMiningIsland();
    }

    @Override
    protected List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        // Unfinished first (most progress on top), finished ones at the end.
        List<Commission> list = new ArrayList<>(MiningState.commissions());
        list.sort((a, b) -> a.done() != b.done() ? Boolean.compare(a.done(), b.done())
                : Double.compare(b.progress(), a.progress()));
        for (Commission c : list) {
            String main = c.done() ? "DONE" : Math.round(c.progress() * 100) + "%";
            int color = c.done() ? GlassStyle.STATUS : 0xFFE8D27A;
            rows.add(new Row(c.goal().name(), c.name(), c.hint(), main, "", color, c.done() ? 0 : Math.max(0.01, c.progress())));
        }
        return rows;
    }

    @Override
    protected String footerText() {
        if (com.coflnet.mining.bot.CommissionBot.isRunning()) {
            return "Bot: " + com.coflnet.mining.bot.CommissionBot.status();
        }
        List<Commission> list = MiningState.commissions();
        if (list.isEmpty()) return "Open the tab list widget \"Commissions\"";
        long done = list.stream().filter(Commission::done).count();
        return done > 0 ? done + " done - claim at the Emissary or the King" : done + "/" + list.size() + " done";
    }

    @Override
    protected ItemStack iconFor(Row row) {
        return switch (row.tag()) {
            case "MITHRIL" -> new ItemStack(Items.PRISMARINE_CRYSTALS);
            case "TITANIUM" -> new ItemStack(Items.POLISHED_DIORITE);
            case "GEMSTONE" -> new ItemStack(Items.AMETHYST_SHARD);
            case "MOBS" -> new ItemStack(Items.IRON_SWORD);
            default -> new ItemStack(Items.PAPER);
        };
    }
}
