package com.coflnet.gui.panel;

import pingplus.voicechat.client.FollowFeature;
import pingplus.voicechat.client.PlayerSettings;
import pingplus.voicechat.client.gui.glass.GlassStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Combat on screen: what it is doing, the current target, the next targets and the pace. */
public final class CombatPanel extends FlipperPanel {
    public CombatPanel() {
        super("combat", "Combat");
    }

    @Override protected boolean enabled() { return PlayerSettings.combatPanel; }
    @Override protected boolean glassEnabled() { return PlayerSettings.combatPanelGlass; }
    @Override protected boolean edgesEnabled() { return PlayerSettings.combatPanelEdges; }
    @Override protected int rowLimit() { return PlayerSettings.combatPanelRows; }
    @Override protected boolean footerEnabled() { return PlayerSettings.combatPanelFooter; }
    @Override protected boolean active() { return PlayerSettings.combatEnabled; }

    /** Only while Combat runs (the other panels are tied to their bots the same way). */
    @Override
    public boolean visible() {
        return PlayerSettings.combatPanel && PlayerSettings.combatEnabled;
    }

    @Override
    protected List<Row> buildRows() {
        FollowFeature.Status status = FollowFeature.status();
        List<Row> rows = new ArrayList<>();
        String mode = PlayerSettings.combatOneHit ? "One-hit" : PlayerSettings.followAttack ? "Fight" : "Follow";
        int stateColor = switch (status.state()) {
            case "Fighting" -> GlassStyle.STATUS;
            case "Walking", "Following", "Going to last known position" -> 0xFFE8D27A;
            default -> GlassStyle.MUTED;
        };
        if (status.target().isEmpty()) {
            rows.add(new Row("", status.state(), "Targets: " + String.join(", ", FollowFeature.targets()),
                    mode, "", stateColor, 0));
        } else {
            String detail = status.distance() < 0 ? status.state() : status.state() + " · " + Math.round(status.distance()) + "m";
            rows.add(new Row("", status.target(), detail, mode, "", stateColor, 0));
        }
        for (String next : status.next()) {
            if (rows.size() >= rowLimit()) {
                break;
            }
            rows.add(new Row("", next, "Next target", "", "", GlassStyle.MUTED, 0));
        }
        return rows;
    }

    @Override
    protected String footerText() {
        FollowFeature.Status status = FollowFeature.status();
        long minutes = status.runningMs() / 60_000L;
        double hours = status.runningMs() / 3_600_000.0;
        String rate = hours > 0.02 ? Math.round(status.done() / hours) + "/h" : "-";
        return "Done " + status.done() + " · " + rate + " · "
                + String.format(Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60) + " h";
    }
}
