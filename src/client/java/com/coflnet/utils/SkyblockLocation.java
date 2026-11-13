package com.coflnet.utils;

import com.coflnet.flipper.trading.ScreenUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

import java.util.Locale;

/**
 * Where the player is on Hypixel SkyBlock, read from the tab list ("Area: Hub",
 * "Area: Private Island") with the sidebar ("⏣ Your Island") as backup.
 * Warp loops use it to see that they are already where a command would take them,
 * because a warp to the current place only teleports and never changes the world.
 */
public final class SkyblockLocation {
    /** Tab list and sidebar arrive a moment after a world change; older data belongs to the last place. */
    private static final long SETTLE_MS = 2_500L;

    private static volatile Object lastLevel;
    private static volatile long levelChangedAt;
    private static boolean initialized;

    private SkyblockLocation() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != lastLevel) {
                lastLevel = client.level;
                levelChangedAt = System.currentTimeMillis();
            }
        });
    }

    /** On the player's own island (not visiting someone else's). */
    public static boolean isOnIsland() {
        if (!settled()) {
            return false;
        }
        String sidebar = sidebarLocation();
        if (!sidebar.isEmpty()) {
            return sidebar.equals("your island");
        }
        return tabArea().equals("private island");
    }

    /** In the SkyBlock hub (not the Dungeon Hub). */
    public static boolean isInHub() {
        return settled() && tabArea().equals("hub");
    }

    /** Lower-case area for logs, e.g. "hub", "private island", or "" when unknown. */
    public static String describe() {
        String area = tabArea();
        String sidebar = sidebarLocation();
        return (area.isEmpty() ? "?" : area) + (sidebar.isEmpty() ? "" : " / " + sidebar);
    }

    private static boolean settled() {
        return lastLevel != null && System.currentTimeMillis() - levelChangedAt >= SETTLE_MS;
    }

    /** "Area: Hub" in the tab list, lower case without the prefix. */
    public static String tabArea() {
        String area = ScreenUtil.sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client.getConnection() == null) {
                return "";
            }
            for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
                Component name = info.getTabListDisplayName();
                if (name == null) {
                    continue;
                }
                String line = ScreenUtil.cleanName(name.getString());
                int index = line.indexOf("Area:");
                if (index < 0) {
                    index = line.indexOf("Dungeon:");
                    if (index >= 0) {
                        return "dungeon";
                    }
                    continue;
                }
                return line.substring(index + "Area:".length()).trim().toLowerCase(Locale.ROOT);
            }
            return "";
        });
        return area == null ? "" : area;
    }

    /** The sidebar line starting with ⏣ (or ф in the Rift), lower case without the symbol. */
    public static String sidebarLocation() {
        String location = ScreenUtil.sync(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client.level == null) {
                return "";
            }
            Scoreboard scoreboard = client.level.getScoreboard();
            Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
            if (objective == null) {
                return "";
            }
            for (ScoreHolder holder : scoreboard.getTrackedPlayers()) {
                if (scoreboard.getPlayerScoreInfo(holder, objective) == null) {
                    continue;
                }
                PlayerTeam team = scoreboard.getPlayersTeam(holder.getScoreboardName());
                if (team == null) {
                    continue;
                }
                String line = ScreenUtil.cleanName(team.getPlayerPrefix().getString()
                        + team.getPlayerSuffix().getString());
                int index = line.indexOf('⏣');
                if (index < 0) {
                    index = line.indexOf('ф');
                }
                if (index >= 0) {
                    return line.substring(index + 1).trim().toLowerCase(Locale.ROOT);
                }
            }
            return "";
        });
        return location == null ? "" : location;
    }
}
