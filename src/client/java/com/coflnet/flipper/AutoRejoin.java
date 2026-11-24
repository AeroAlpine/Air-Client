package com.coflnet.flipper;

import com.coflnet.ahflipper.AhBidMacro;
import com.coflnet.ahflipper.AhFlipperMod;
import com.coflnet.flipper.trading.Trader;
import com.coflnet.lowballing.LowballingMod;
import com.coflnet.mining.bot.CommissionBot;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;

import java.util.Locale;

/**
 * Keeps the macros online:
 * <ul>
 *   <li>disconnected unexpectedly (kick, connection lost: the "Disconnected" screen) while a
 *       macro runs: connect to the same server again, go to SkyBlock with /skyblock and turn
 *       the macros back on that were running;</li>
 *   <li>sent to Limbo while a macro runs: leave the server and join again the same way.</li>
 * </ul>
 * Leaving the server yourself (pause menu) is never undone. Bans are not retried.
 */
public final class AutoRejoin {
    private enum Phase { IDLE, WAIT_RECONNECT, CONNECTING, TO_SKYBLOCK }

    /** Waits before the n-th reconnect try (then every 2 minutes). */
    private static final long[] BACKOFF_MS = {8_000L, 20_000L, 45_000L, 90_000L};
    private static final long LATER_MS = 120_000L;
    private static final int MAX_TRIES = 20;

    private static boolean initialized;
    private static Phase phase = Phase.IDLE;
    private static long phaseAt;
    private static int tries;
    private static long nextActionAt;
    private static int skyblockCommands;
    /** The Hypixel server we were on (address for the reconnect). */
    private static ServerData lastServer;
    /** What ran while we were online (sampled every tick, before any disconnect handler stops it). */
    private static boolean bidWas;
    private static boolean ahWas;
    private static boolean bazaarWas;
    private static boolean lowballingWas;
    private static boolean commissionWas;
    /** Our own disconnect from Limbo: reconnect even though no "Disconnected" screen comes. */
    private static boolean limboLeave;
    private static long limboSeenAt;

    private AutoRejoin() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(AutoRejoin::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && message != null) {
                onChat(ChatFormatting.stripFormatting(message.getString()));
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (phase == Phase.CONNECTING || phase == Phase.WAIT_RECONNECT) {
                setPhase(Phase.TO_SKYBLOCK);
                skyblockCommands = 0;
                nextActionAt = System.currentTimeMillis() + 4_000L;
            }
        });
    }

    private static boolean anyRunning() {
        return bidWas || ahWas || bazaarWas || lowballingWas || commissionWas;
    }

    private static void sample(Minecraft client) {
        Trader trader = Trader.get();
        bidWas = AhBidMacro.isRunning();
        ahWas = AhFlipperMod.isRunning();
        bazaarWas = trader != null && trader.isRunning();
        lowballingWas = LowballingMod.isRunning();
        commissionWas = CommissionBot.isRunning();
        ServerData server = client.getCurrentServer();
        if (server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel")) {
            lastServer = server;
        }
    }

    private static void onChat(String text) {
        if (text == null || phase != Phase.IDLE) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        // "You were spawned in Limbo." / "You are AFK. Move around to return from AFK."
        if (lower.contains("spawned in limbo") || lower.contains("you are afk. move around")) {
            limboSeenAt = System.currentTimeMillis();
        }
    }

    private static void tick(Minecraft client) {
        long now = System.currentTimeMillis();
        boolean online = client.player != null && client.getConnection() != null;
        if (phase == Phase.IDLE) {
            if (online) {
                sample(client);
                if (limboSeenAt > 0 && now - limboSeenAt < 10_000L && anyRunning() && lastServer != null) {
                    limboSeenAt = 0;
                    log("In Limbo while a macro runs - leaving and joining again");
                    limboLeave = true;
                    startWaiting(now);
                    client.disconnectFromWorld(Component.literal("Air Client: leaving Limbo"));
                }
                return;
            }
            if (client.gui.screen() instanceof TitleScreen
                    || client.gui.screen() instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen) {
                clearMacros(); // left on purpose: a later failed connect elsewhere is no reason to rejoin
                return;
            }
            if (client.gui.screen() instanceof DisconnectedScreen screen && anyRunning() && lastServer != null) {
                String reason = screenText(screen);
                if (reason.contains("banned") || reason.contains("ban id")) {
                    log("Disconnected with a ban - no rejoin: " + reason);
                    clearMacros();
                    return;
                }
                log("Disconnected while a macro ran (" + reason + ") - rejoining");
                startWaiting(now);
            }
            return;
        }
        switch (phase) {
            case WAIT_RECONNECT -> {
                if (online) {
                    return; // still leaving (Limbo) - the JOIN event moves us on later
                }
                if (now < nextActionAt) {
                    return;
                }
                if (tries >= MAX_TRIES) {
                    log("Gave up rejoining after " + tries + " tries");
                    reset();
                    return;
                }
                tries++;
                log("Reconnect " + tries + "/" + MAX_TRIES + " to " + lastServer.ip);
                setPhase(Phase.CONNECTING);
                ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(lastServer.ip),
                        lastServer, false, null);
            }
            case CONNECTING -> {
                // failed again: back to waiting (the JOIN event leads on when it works)
                if (client.gui.screen() instanceof DisconnectedScreen || now - phaseAt > 40_000L && !online) {
                    setPhase(Phase.WAIT_RECONNECT);
                    nextActionAt = now + backoff(tries);
                }
            }
            case TO_SKYBLOCK -> {
                if (!online) {
                    if (client.gui.screen() instanceof DisconnectedScreen) {
                        setPhase(Phase.WAIT_RECONNECT);
                        nextActionAt = now + backoff(tries);
                    }
                    return;
                }
                if (onSkyBlock(client)) {
                    log("Back on SkyBlock - turning the macros on again");
                    restoreMacros();
                    reset();
                    return;
                }
                if (now >= nextActionAt) {
                    if (skyblockCommands >= 8) {
                        log("Could not get to SkyBlock - reconnecting");
                        setPhase(Phase.WAIT_RECONNECT);
                        nextActionAt = now + backoff(tries);
                        client.disconnectFromWorld(Component.literal("Air Client: rejoin"));
                        return;
                    }
                    skyblockCommands++;
                    client.getConnection().sendCommand("skyblock");
                    log("/skyblock (" + skyblockCommands + ")");
                    nextActionAt = now + 10_000L;
                }
            }
            default -> {
            }
        }
    }

    private static void startWaiting(long now) {
        tries = 0;
        setPhase(Phase.WAIT_RECONNECT);
        nextActionAt = now + BACKOFF_MS[0];
    }

    private static long backoff(int tries) {
        return tries < BACKOFF_MS.length ? BACKOFF_MS[tries] : LATER_MS;
    }

    private static void restoreMacros() {
        AhFlipperMod.resumeAfterRestart(ahWas);
        if (bazaarWas) {
            Trader trader = Trader.get();
            if (trader != null && !trader.isRunning()) {
                FlipperMod.toggle();
            } else {
                FlipperMod.resumeAfterRestart(true);
            }
        }
        LowballingMod.resumeAfterRestart(lowballingWas);
        if (bidWas && !AhBidMacro.isRunning()) {
            AhBidMacro.start();
        }
        if (commissionWas && !CommissionBot.isRunning()) {
            CommissionBot.toggle();
        }
    }

    private static boolean onSkyBlock(Minecraft client) {
        if (client.level == null) {
            return false;
        }
        Objective objective = client.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        return objective != null && ChatFormatting.stripFormatting(objective.getDisplayName().getString())
                .toUpperCase(Locale.ROOT).contains("SKYBLOCK");
    }

    private static String screenText(DisconnectedScreen screen) {
        StringBuilder text = new StringBuilder(screen.getTitle().getString());
        try {
            text.append(' ').append(screen.getNarrationMessage().getString());
        } catch (RuntimeException ignored) {
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static void setPhase(Phase next) {
        phase = next;
        phaseAt = System.currentTimeMillis();
    }

    private static void clearMacros() {
        bidWas = ahWas = bazaarWas = lowballingWas = commissionWas = false;
    }

    private static void reset() {
        phase = Phase.IDLE;
        tries = 0;
        limboLeave = false;
        limboSeenAt = 0;
        clearMacros();
    }

    /** True while we reconnect (other bots: do not count the missing world as a problem). */
    public static boolean isActive() {
        return phase != Phase.IDLE;
    }

    private static void log(String text) {
        System.out.println("[AutoRejoin] " + text);
    }
}
