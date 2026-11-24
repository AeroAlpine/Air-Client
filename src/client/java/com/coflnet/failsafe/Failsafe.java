package com.coflnet.failsafe;

import com.coflnet.flipper.trading.ScreenUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import pingplus.voicechat.client.AimSmoothing;
import pingplus.voicechat.client.PlayerSettings;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One fail-safe for every bot (Commission Bot, Combat, Lowballer). It watches what the server
 * does to us - teleports, camera turns, the held item, blocks appearing around us, our name in
 * chat - and reacts like a player would:
 * <ul>
 *   <li>small things (a little lag back, a small turn): carry on, at most a short glance;</li>
 *   <li>the held item swapped: carry on 4-15 s, then notice it, look around, check the
 *       inventory, take the right item again and go on;</li>
 *   <li>our name in chat: pause, answer like a player, go on;</li>
 *   <li>big or repeated or obvious things (a box built around us, a big teleport, the camera
 *       set again and again): stop, say something cheeky in chat, wait ~5 s, go on.</li>
 * </ul>
 * Bots ask {@link #holding()} every tick and pause while it is true (like for the AH flipper).
 */
public final class Failsafe {
    private enum Step { NONE, GLANCE, ITEM_NOTICE, ITEM_LOOK, ITEM_INVENTORY, CHAT_READ, CHAT_TYPED, OFFENSIVE_WAIT, OFFENSIVE_TYPED }

    private static final List<String> TROLL = List.of(
            "W macro check lol", "im not macroing gng", "bro what was that lmao", "macro check? lol",
            "nice try lol im here", "?? im literally playing", "admins bored today lol", "chill im not botting");

    private static boolean initialized;
    private static Step step = Step.NONE;
    private static long stepUntil;
    private static long holdUntil;
    private static String reason = "";
    private static String lastTrigger = "None yet";
    private static long lastTriggerAt;
    private static boolean testMode;

    // detection state
    private static long lastOwnWarpCommandAt;
    private static Object level;
    private static long levelChangedAt;
    private static final Deque<Long> minorEvents = new ArrayDeque<>();
    private static final Deque<Long> rotationEvents = new ArrayDeque<>();
    private static final Deque<long[]> blockEvents = new ArrayDeque<>(); // {time, inFov}
    private static boolean floorRemoved;
    private static long lastOffensiveAt;
    private static long lastTrollAt;
    // item reaction
    private static boolean slotFrozen;
    private static long itemNoticeAt;
    // glance
    private static float glanceFromYaw;
    private static float glanceYaw;
    private static float glancePitch;
    private static long glanceStart;
    private static long glanceEnd;
    private static long lastFrame;
    // chat reaction
    private static String replyCommand;
    private static String replyText;
    private static final Map<String, Long> repliedTo = new HashMap<>();
    private static final Deque<Long> replies = new ArrayDeque<>();

    private Failsafe() {
    }

    public static void init() {
        if (initialized) return;
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(Failsafe::tick);
        ClientSendMessageEvents.COMMAND.register(command -> {
            String c = command.toLowerCase(Locale.ROOT);
            if (c.startsWith("warp") || c.startsWith("hub") || c.equals("is") || c.startsWith("skyblock")
                    || c.startsWith("play") || c.startsWith("lobby") || c.startsWith("visit")) {
                lastOwnWarpCommandAt = com.coflnet.pathing.sim.SimClock.millis();
            }
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                String text = ScreenUtil.cleanName(message.getString());
                // "Warping...", "Warping you to your SkyBlock island...", "Sending you to ...":
                // the server announces every warp, also the ones from menus / NPCs / portals that
                // are no command of ours - the teleport right after is no staff check
                if (text.startsWith("Warping") || text.startsWith("Sending you to")
                        || text.startsWith("Sending to server")) {
                    lastOwnWarpCommandAt = com.coflnet.pathing.sim.SimClock.millis();
                }
                onChat(text, false);
            }
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) ->
                onChat(ScreenUtil.cleanName(message.getString()), false));
    }

    // ------------------------------------------------------------------ what bots ask

    /** True while a reaction is running: every bot pauses (keys free) until it is false again. */
    public static boolean holding() {
        return step != Step.NONE && step != Step.ITEM_NOTICE || com.coflnet.pathing.sim.SimClock.millis() < holdUntil;
    }

    /** True while the bots must not switch the held item (a swap was "not noticed yet"). */
    public static boolean slotFrozen() {
        return slotFrozen;
    }

    public static String status() {
        if (!PlayerSettings.failsafeEnabled) return "Off";
        if (step != Step.NONE) return "Reacting: " + reason;
        return lastTriggerAt == 0 ? "Watching" : "Last: " + lastTrigger + " (" + ago(lastTriggerAt) + ")";
    }

    private static String ago(long at) {
        long s = (com.coflnet.pathing.sim.SimClock.millis() - at) / 1000;
        return s < 60 ? s + " s ago" : (s / 60) + " min ago";
    }

    /** A bot is running that could be checked (only then the fail-safe reacts). */
    static boolean botsActive() {
        return com.coflnet.mining.bot.CommissionBot.isRunning() || PlayerSettings.combatEnabled
                || com.coflnet.lowballing.LowballingMod.isRunning();
    }

    private static boolean watching() {
        return PlayerSettings.failsafeEnabled && (botsActive() || testMode);
    }

    /** Our own warps / commands / world changes explain any teleport right now. */
    private static boolean ownMovement() {
        long now = com.coflnet.pathing.sim.SimClock.millis();
        return com.coflnet.pathing.AotvAssist.isBusy()
                || now - com.coflnet.pathing.AotvAssist.lastWarpAt() < 4_000L
                || now - lastOwnWarpCommandAt < 10_000L
                || now - levelChangedAt < 4_000L
                || com.coflnet.flipper.ServerRestartHandler.isActive();
    }

    // ------------------------------------------------------------------ detections (from the packet mixin)

    /** The server moved us (position packet), {@code distance} blocks and turned the camera {@code turned} degrees. */
    public static void onServerMove(double distance, float turned) {
        if (!watching() || ownMovement()) return;
        Minecraft mc = Minecraft.getInstance();
        // back to a place we were at a moment ago = the anticheat set us back (lag): harmless.
        // A check puts us somewhere new.
        boolean backwards = mc.player != null && wasNear(mc.player.position(), 2.5);
        if (backwards && !testMode) {
            if (distance > 0.6) minor("Set back " + String.format(Locale.ROOT, "%.1f", distance) + " blocks", false);
            if (turned > 0.5f) onServerRotation(turned);
            return;
        }
        if (distance >= PlayerSettings.failsafeTeleportBlocks) {
            // our own AOTV: the teleport reach of the item in the hand, shortly after our click
            // (the server's answer can come late; "Teleported 12 blocks" was our 12-block AOTV)
            int reach = mc.player == null ? 0 : com.coflnet.pathing.AotvAssist.teleportReach(mc.player);
            long sinceWarp = com.coflnet.pathing.sim.SimClock.millis() - com.coflnet.pathing.AotvAssist.lastWarpAt();
            if (!testMode && reach > 0 && distance <= reach + 2.0 && sinceWarp < 15_000L) {
                minor("Own teleport landed " + Math.round(distance) + " blocks away", false);
                return;
            }
            offensive("Teleported " + Math.round(distance) + " blocks by the server");
            return;
        }
        if (distance > 0.6) minor("Lag back " + String.format(Locale.ROOT, "%.1f", distance) + " blocks", false);
        if (turned > 0.5f) onServerRotation(turned);
    }

    /** The server turned our camera. */
    public static void onServerRotation(float degrees) {
        if (!watching() || ownMovement() || degrees < 0.5f) return;
        long now = com.coflnet.pathing.sim.SimClock.millis();
        rotationEvents.addLast(now);
        while (!rotationEvents.isEmpty() && now - rotationEvents.peekFirst() > 30_000L) rotationEvents.removeFirst();
        if (degrees >= PlayerSettings.failsafeRotationDegrees) {
            offensive("Camera turned " + Math.round(degrees) + "° by the server");
        } else if (rotationEvents.size() >= 3) {
            offensive("Camera set by the server again and again");
        } else {
            // small: carry on; a short glance only when it was big enough to notice
            minor("Camera turned " + Math.round(degrees) + "°", degrees >= 8f);
        }
    }

    /** The server changed the selected hotbar slot or put another item into the hotbar. */
    public static void onItemSwap(String what) {
        if (!watching() || !PlayerSettings.failsafeItem || slotFrozen) return;
        if (!testMode && ownMovement()) return; // joining a server / warping resets the slot
        trigger("Item swapped: " + what);
        slotFrozen = true;
        // keep going as if nothing happened, and only "notice" it after a while
        int min = Math.max(1, PlayerSettings.failsafeItemMinS), max = Math.max(min, PlayerSettings.failsafeItemMaxS);
        itemNoticeAt = com.coflnet.pathing.sim.SimClock.millis() + 1_000L * ThreadLocalRandom.current().nextInt(min, max + 1);
        if (step == Step.NONE) {
            step = Step.ITEM_NOTICE;
            reason = what;
        }
    }

    /** A block changed near us; called for every server block update. */
    public static void onBlockChange(BlockPos pos, BlockState before, BlockState after) {
        if (!watching() || !PlayerSettings.failsafeBlocks) return;
        // right after a warp the new area loads around us: blocks "appear" by the hundred
        if (ownMovement()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        Vec3 eye = player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        double dist = center.distanceTo(eye);
        if (dist > 6.5) return;
        boolean wasOpen = before.getCollisionShape(mc.level, pos).isEmpty();
        boolean nowSolid = !after.getCollisionShape(mc.level, pos).isEmpty();
        BlockPos feet = player.blockPosition();
        boolean underUs = Math.abs(pos.getX() - feet.getX()) <= 1 && Math.abs(pos.getZ() - feet.getZ()) <= 1
                && (pos.getY() == feet.getY() - 1 || pos.getY() == feet.getY() - 2);
        if (wasOpen && nowSolid && !expected(after)) {
            // a block appeared out of the air, of a kind that does not belong here
            Vec3 look = player.getViewVector(1f);
            double angle = Math.toDegrees(Math.acos(Math.clamp(center.subtract(eye).normalize().dot(look), -1, 1)));
            addBlockEvent(angle < 55 && dist < 5.0);
        } else if (!wasOpen && after.isAir() && underUs && !before.is(Blocks.BEDROCK)) {
            // the floor vanished (mining never makes air here: mined blocks turn to bedrock)
            floorRemoved = true;
            addBlockEvent(true);
        }
    }

    /** Blocks that come and go normally in the mines (ores regenerating, mined blocks as bedrock). */
    private static boolean expected(BlockState state) {
        return state.is(Blocks.BEDROCK) || com.coflnet.mining.MiningBlock.of(state) != null
                || state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE);
    }

    private static void addBlockEvent(boolean inFov) {
        long now = com.coflnet.pathing.sim.SimClock.millis();
        blockEvents.addLast(new long[]{now, inFov ? 1 : 0});
    }

    private static void checkBlocks(long now) {
        while (!blockEvents.isEmpty() && now - blockEvents.peekFirst()[0] > 3_000L) blockEvents.removeFirst();
        if (blockEvents.isEmpty()) {
            floorRemoved = false;
            return;
        }
        int total = blockEvents.size();
        int seen = 0;
        for (long[] e : blockEvents) seen += (int) e[1];
        // a few odd blocks somewhere: ignore. Many, close and in view (a box), or the floor gone: react.
        if (floorRemoved || seen >= PlayerSettings.failsafeBlockCount || total >= PlayerSettings.failsafeBlockCount * 2) {
            blockEvents.clear();
            boolean floor = floorRemoved;
            floorRemoved = false;
            offensive(floor ? "The floor under us vanished" : total + " blocks appeared around us");
        }
    }

    /** Chat line: our name from another player, or a private message. */
    static void onChat(String text, boolean test) {
        if (!PlayerSettings.failsafeEnabled || !PlayerSettings.failsafeChat || text.isEmpty()) return;
        if (!test && (!botsActive() || com.coflnet.lowballing.LowballingMod.isRunning())) return; // the lowballer answers itself
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String me = mc.player.getName().getString();
        FailsafeChat.Line line = FailsafeChat.parse(text, me);
        if (line == null) return;
        long now = com.coflnet.pathing.sim.SimClock.millis();
        Long last = repliedTo.get(line.sender().toLowerCase(Locale.ROOT));
        while (!replies.isEmpty() && now - replies.peekFirst() > 300_000L) replies.removeFirst();
        if (!test && ((last != null && now - last < 25_000L) || replies.size() >= 4)) return; // no chat spam
        if (step != Step.NONE && step != Step.ITEM_NOTICE) return;
        repliedTo.put(line.sender().toLowerCase(Locale.ROOT), now);
        replies.addLast(now);
        trigger("Chat from " + line.sender() + ": " + line.message());
        replyText = FailsafeChat.reply(line.message());
        replyCommand = line.replyPrefix();
        testMode = test;
        reason = "answering " + line.sender();
        start(Step.CHAT_READ, 900 + ThreadLocalRandom.current().nextInt(1_300));
        hold(60_000L);
    }

    // ------------------------------------------------------------------ reactions

    private static void minor(String why, boolean glance) {
        long now = com.coflnet.pathing.sim.SimClock.millis();
        minorEvents.addLast(now);
        while (!minorEvents.isEmpty() && now - minorEvents.peekFirst() > 20_000L) minorEvents.removeFirst();
        if (minorEvents.size() >= 5) {
            minorEvents.clear();
            offensive("Set back again and again (" + why + ")");
            return;
        }
        System.out.println("[Failsafe] minor: " + why);
        if (glance && step == Step.NONE) {
            trigger(why);
            reason = why;
            startGlance(ThreadLocalRandom.current().nextFloat() * 14f + 6f, 450);
            start(Step.GLANCE, 600 + ThreadLocalRandom.current().nextInt(500));
        }
    }

    private static void offensive(String why) {
        long now = com.coflnet.pathing.sim.SimClock.millis();
        if (step == Step.OFFENSIVE_WAIT || step == Step.OFFENSIVE_TYPED) return;
        if (now - lastOffensiveAt < 8_000L) return; // one reaction per incident
        lastOffensiveAt = now;
        trigger(why);
        reason = why;
        releaseKeys();
        // stand still, look at it for a moment, then say something
        startGlance(ThreadLocalRandom.current().nextFloat() * 20f + 10f, 700);
        start(Step.OFFENSIVE_WAIT, 900 + ThreadLocalRandom.current().nextInt(1_100));
        hold(15_000L);
    }

    private static void trigger(String why) {
        lastTrigger = why;
        lastTriggerAt = com.coflnet.pathing.sim.SimClock.millis();
        System.out.println("[Failsafe] " + why);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.sendSystemMessage(Component.literal("§c[Failsafe] §7" + why));
        if (PlayerSettings.failsafeSound) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 0.6f));
        }
    }

    private static void start(Step s, long ms) {
        step = s;
        stepUntil = com.coflnet.pathing.sim.SimClock.millis() + ms;
    }

    private static void hold(long ms) {
        holdUntil = Math.max(holdUntil, com.coflnet.pathing.sim.SimClock.millis() + ms);
    }

    private static void done() {
        step = Step.NONE;
        holdUntil = 0;
        testMode = false;
        glanceEnd = 0;
    }

    private static void releaseKeys() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keyAttack.setDown(false);
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    }

    /** Where we were over the last 10 s (one point every 5 ticks). */
    private static final Deque<Vec3> history = new ArrayDeque<>();
    private static int historyTick;

    private static boolean wasNear(Vec3 pos, double radius) {
        for (Vec3 p : history) {
            if (p.distanceTo(pos) <= radius) return true;
        }
        return false;
    }

    private static void tick(Minecraft mc) {
        if (mc.player != null && ++historyTick % 5 == 0) {
            history.addLast(mc.player.position());
            while (history.size() > 40) history.removeFirst();
        }
        if (mc.level != level) {
            history.clear();
            level = mc.level;
            levelChangedAt = com.coflnet.pathing.sim.SimClock.millis();
            blockEvents.clear();
        }
        if (mc.player == null) {
            done();
            slotFrozen = false;
            return;
        }
        long now = com.coflnet.pathing.sim.SimClock.millis();
        if (PlayerSettings.failsafeEnabled) checkBlocks(now);
        switch (step) {
            case NONE -> {
            }
            case GLANCE -> {
                if (now >= stepUntil) done();
            }
            case ITEM_NOTICE -> {
                // still "not noticed": the bots go on with whatever is in the hand
                if (now >= itemNoticeAt) {
                    reason = "noticing the wrong item";
                    releaseKeys();
                    startGlance(ThreadLocalRandom.current().nextFloat() * 16f + 8f, 600);
                    start(Step.ITEM_LOOK, 900 + ThreadLocalRandom.current().nextInt(700));
                    hold(20_000L);
                }
            }
            case ITEM_LOOK -> {
                if (now >= stepUntil) {
                    // check the inventory like a player
                    if (mc.gui.screen() == null) mc.gui.setScreen(new InventoryScreen(mc.player));
                    start(Step.ITEM_INVENTORY, 1_100 + ThreadLocalRandom.current().nextInt(1_400));
                }
            }
            case ITEM_INVENTORY -> {
                if (now >= stepUntil) {
                    if (mc.gui.screen() instanceof InventoryScreen) mc.player.closeContainer();
                    slotFrozen = false; // the bots take their item again on their own
                    log("Item back, going on");
                    hold(400L + ThreadLocalRandom.current().nextInt(500));
                    step = Step.NONE;
                    testMode = false;
                }
            }
            case CHAT_READ -> {
                if (now >= stepUntil) {
                    // typing time like a player
                    long typing = 600L + replyText.length() * 85L + ThreadLocalRandom.current().nextInt(700);
                    start(Step.CHAT_TYPED, typing);
                }
            }
            case CHAT_TYPED -> {
                if (now >= stepUntil) {
                    send(mc, replyCommand, replyText);
                    hold(800L + ThreadLocalRandom.current().nextInt(1_400));
                    step = Step.NONE;
                    testMode = false;
                }
            }
            case OFFENSIVE_WAIT -> {
                if (now >= stepUntil) {
                    String text = TROLL.get(ThreadLocalRandom.current().nextInt(TROLL.size()));
                    if (PlayerSettings.failsafeTroll && now - lastTrollAt > 60_000L) {
                        lastTrollAt = now;
                        long typing = 500L + text.length() * 70L + ThreadLocalRandom.current().nextInt(500);
                        replyText = text;
                        replyCommand = "";
                        start(Step.OFFENSIVE_TYPED, typing);
                    } else {
                        start(Step.OFFENSIVE_TYPED, 300);
                        replyText = null;
                    }
                }
            }
            case OFFENSIVE_TYPED -> {
                if (now >= stepUntil) {
                    if (replyText != null) send(mc, "", replyText);
                    // wait a bit (~5 s), then go on exactly where we were
                    holdUntil = now + 3_500L + ThreadLocalRandom.current().nextInt(3_000);
                    step = Step.NONE;
                    testMode = false;
                }
            }
        }
    }

    private static void send(Minecraft mc, String prefix, String text) {
        if (text == null || text.isBlank() || mc.getConnection() == null) return;
        if (testMode) {
            mc.player.sendSystemMessage(Component.literal("§c[Failsafe test] §7would say: §f"
                    + (prefix.isEmpty() ? "" : "/" + prefix + " ") + text));
            return;
        }
        if (prefix.isEmpty()) mc.getConnection().sendChat(text);
        else mc.getConnection().sendCommand(prefix + " " + text);
        log("Said: " + text);
    }

    private static void log(String text) {
        System.out.println("[Failsafe] " + text);
    }

    // ------------------------------------------------------------------ glance (camera, every frame)

    private static void startGlance(float yawOffset, long ms) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        glanceFromYaw = mc.player.getYRot();
        glanceYaw = ThreadLocalRandom.current().nextBoolean() ? yawOffset : -yawOffset;
        glancePitch = (ThreadLocalRandom.current().nextFloat() - 0.5f) * 10f;
        glanceStart = com.coflnet.pathing.sim.SimClock.millis();
        glanceEnd = glanceStart + ms * 2;
    }

    /** Called every frame before the bots' own camera: a short look to the side and back. */
    public static void frame(Minecraft mc) {
        LocalPlayer player = mc.player;
        long nanos = com.coflnet.pathing.sim.SimClock.nanos();
        double dt = lastFrame == 0 ? 0 : Math.min(0.05, (nanos - lastFrame) / 1e9);
        lastFrame = nanos;
        long now = com.coflnet.pathing.sim.SimClock.millis();
        if (player == null || glanceEnd == 0 || now > glanceEnd || mc.gui.screen() != null) return;
        double t = (now - glanceStart) / (double) (glanceEnd - glanceStart);
        float wave = (float) Math.sin(Math.PI * Math.clamp(t, 0, 1));
        float wantYaw = glanceFromYaw + glanceYaw * wave;
        player.setYRot(AimSmoothing.step(player.getYRot(), wantYaw, 220f, dt, 10.0));
        player.setXRot(Math.clamp(AimSmoothing.step(player.getXRot(), player.getXRot() + glancePitch * wave * 0.05f,
                120f, dt, 10.0), -90f, 90f));
    }

    /** True while the fail-safe moves the camera (bots keep their hands off it). */
    public static boolean ownsCamera() {
        return glanceEnd != 0 && com.coflnet.pathing.sim.SimClock.millis() <= glanceEnd;
    }

    // ------------------------------------------------------------------ tests (module buttons)

    public static void test(String which) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        testMode = true;
        lastOffensiveAt = 0;
        lastTrollAt = 0;
        switch (which) {
            case "chat" -> onChat("[MVP+] Tester: hey " + mc.player.getName().getString() + " are you macroing?", true);
            case "item" -> {
                int slot = mc.player.getInventory().getSelectedSlot();
                int other = (slot + 1 + ThreadLocalRandom.current().nextInt(7)) % 9;
                mc.player.getInventory().setSelectedSlot(other);
                onItemSwap("test (slot " + (slot + 1) + " -> " + (other + 1) + ")");
            }
            case "small" -> onServerRotation(Math.max(8f, PlayerSettings.failsafeRotationDegrees * 0.4f));
            case "rotation" -> {
                mc.player.setYRot(mc.player.getYRot() + 90f);
                onServerRotation(90f);
            }
            case "teleport" -> onServerMove(PlayerSettings.failsafeTeleportBlocks + 4, 0f);
            case "blocks" -> {
                for (int i = 0; i < PlayerSettings.failsafeBlockCount + 1; i++) addBlockEvent(true);
            }
            default -> {
            }
        }
        if (step == Step.NONE && blockEvents.isEmpty()) testMode = false;
    }

    static boolean isTest() {
        return testMode;
    }

    /** Item stack identity: the SkyBlock id, else the item type. */
    public static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && !data.isEmpty()) {
            var root = data.copyTag();
            var extra = root.getCompound("ExtraAttributes").orElse(root);
            String id = extra.getString("id").orElse("");
            if (!id.isBlank()) return id;
        }
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
