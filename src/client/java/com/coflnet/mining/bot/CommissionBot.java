package com.coflnet.mining.bot;

import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.mining.Commission;
import com.coflnet.mining.MiningBlock;
import com.coflnet.mining.MiningState;
import com.coflnet.pathing.AotvAssist;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import pingplus.voicechat.client.AimSmoothing;
import pingplus.voicechat.client.FollowFeature;
import pingplus.voicechat.client.PlayerSettings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Commission Bot for the Dwarven Mines:
 * <ol>
 *   <li>walk to the King, claim finished commissions and read the open ones;</li>
 *   <li>do every commission it can (Glacite Walkers with Combat, Mithril / Titanium with the
 *       miner) - event commissions are skipped, they need a running mining event;</li>
 *   <li>back to the King (via "/warp forge" when that is quicker), claim, and again.</li>
 * </ol>
 * Travel uses the routes learned from recorded runs ({@link DwarvenGraph}) with many teleports.
 */
public final class CommissionBot {
    private enum State { IDLE, TO_KING, OPEN_KING, CLAIM, PLAN, TRAVEL, SLAYER, MINING, SELL }

    private enum Kind { SLAYER, MINING }

    private record Task(String name, Kind kind, Commission commission) {
    }

    private static final long GUI_TIMEOUT_MS = 4_000L;
    private static final long SLAYER_TIMEOUT_MS = 8 * 60_000L;
    private static final long MINING_TIMEOUT_MS = 15 * 60_000L;

    private static KeyMapping key;
    private static boolean initialized;
    private static volatile State state = State.IDLE;
    private static State afterSell = State.IDLE;
    private static final RouteWalker walker = new RouteWalker();
    private static final DwarvenMiner miner = new DwarvenMiner();
    private static final Deque<Task> queue = new ArrayDeque<>();
    private static Task task;
    private static long stateAt;
    private static long nextActionAt;
    private static int tries;
    private static Entity king;
    private static Vec3 lookAt;
    private static long lastFrame;
    private static int sellStep;
    private static boolean sellBroken;
    private static long notInMinesSince;
    private static int claimed;
    private static int finished;
    private static long startedAt;
    private static String lastMessage = "";

    private CommissionBot() {
    }

    public static void init(KeyMapping toggleKey) {
        key = toggleKey;
        if (initialized) return;
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(CommissionBot::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && state != State.IDLE) onChat(ScreenUtil.cleanName(message.getString()));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (state != State.IDLE) stop("Disconnected");
        });
    }

    public static KeyMapping key() {
        return key;
    }

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    /** The miner is breaking a block right now (vanilla's own attack handling must stay out). */
    public static boolean isBreaking() {
        return state == State.MINING && miner.breaking();
    }

    public static void toggle() {
        if (isRunning()) stop("Stopped");
        else start();
    }

    public static String status() {
        if (state == State.IDLE) return lastMessage.isEmpty() ? "Off" : lastMessage;
        if (pausedAt != 0) return pauseReason;
        String detail = switch (state) {
            case TO_KING, TRAVEL -> walker.status();
            case MINING -> miner.status();
            case SLAYER -> "Fighting (" + FollowFeature.doneCount() + " kills)";
            default -> "";
        };
        String what = switch (state) {
            case TO_KING -> "To the King";
            case OPEN_KING -> "Talking to the King";
            case CLAIM -> "Claiming";
            case PLAN -> "Planning";
            case TRAVEL -> "To " + (task == null ? "task" : task.name());
            case SLAYER, MINING -> task == null ? "Working" : task.name();
            case SELL -> "Selling inventory";
            case IDLE -> "Off";
        };
        return what + (detail.isEmpty() ? "" : " · " + detail);
    }

    public static String summary() {
        long min = startedAt == 0 ? 0 : (System.currentTimeMillis() - startedAt) / 60_000L;
        return claimed + " claimed · " + finished + " done · " + min + " min";
    }

    // ------------------------------------------------------------------ start / stop

    private static void start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!MiningState.inDwarvenMines()) {
            message("§cGo to the Dwarven Mines first (/warp mines).");
            lastMessage = "Not in the Dwarven Mines";
            return;
        }
        try {
            DwarvenGraph.get();
        } catch (RuntimeException e) {
            message("§c" + e.getMessage());
            return;
        }
        if (FollowFeature.isBorrowed() || PlayerSettings.combatEnabled) {
            if (PlayerSettings.combatEnabled) FollowFeature.toggle(); // one bot at a time
        }
        queue.clear();
        task = null;
        claimed = 0;
        finished = 0;
        sellBroken = false;
        startedAt = System.currentTimeMillis();
        AotvAssist.setBias(0.7, 0.6, true);
        // the player's etherwarp points become moves of the path search
        com.coflnet.pathing.PathEngine.setEtherwarpPoints(DwarvenGraph.get().etherwarpPoints());
        // Commissions already known from the tab list: no need to ask the King first.
        List<Commission> known = MiningState.commissions();
        if (!known.isEmpty()) {
            if (known.stream().anyMatch(Commission::done)) {
                message("§aStarted. §7A commission is done: to the King to claim it.");
                goKing();
                return;
            }
            List<Task> tasks = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (Commission c : known) {
                Kind kind = kindOf(c);
                if (kind == null) skipped.add(c.name());
                else tasks.add(new Task(c.name(), kind, c));
            }
            tasks.sort((a, b) -> Integer.compare(a.kind() == Kind.MINING ? 0 : 1, b.kind() == Kind.MINING ? 0 : 1));
            queue.addAll(tasks);
            workedSinceKing = false;
            message("§aStarted. §7Commissions from the tab list: §f" + String.join(", ", known.stream().map(Commission::name).toList())
                    + (skipped.isEmpty() ? "" : " §8(skipping " + String.join(", ", skipped) + ")"));
            set(State.PLAN);
            return;
        }
        message("§aStarted. §7First to the King (commissions not in the tab list).");
        goKing();
    }

    public static void stop(String why) {
        Minecraft mc = Minecraft.getInstance();
        walker.stop();
        miner.stop();
        FollowFeature.giveBack();
        AotvAssist.clearBias();
        com.coflnet.pathing.PathEngine.setEtherwarpPoints(List.of());
        RouteWalker.stopBaritone();
        if (mc.options != null) {
            mc.options.keyShift.setDown(false);
            mc.options.keyUp.setDown(false);
        }
        state = State.IDLE;
        pausedAt = 0;
        notInMinesSince = 0;
        king = null;
        lookAt = null;
        lastMessage = why;
        message("§7" + why + " §8(" + summary() + ")");
    }

    private static void set(State s) {
        state = s;
        stateAt = System.currentTimeMillis();
        tries = 0;
    }

    private static void goKing() {
        walker.start(DwarvenGraph.get().place("king"), 2.0, true);
        set(State.TO_KING);
    }

    // ------------------------------------------------------------------ tick

    private static void tick(Minecraft mc) {
        while (key != null && key.consumeClick()) toggle();
        if (state == State.IDLE) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        long now = System.currentTimeMillis();
        // a server reboot moves us around (/is, /hub ...): hold still until that is over
        if (com.coflnet.flipper.ServerRestartHandler.isActive()) {
            hold(mc, "Server reboot - waiting for the warps", now);
            return;
        }
        // the AH flipper buys / claims (it needs the menus): pause, then go on where we were
        if (com.coflnet.failsafe.Failsafe.holding()) {
            hold(mc, "Fail-safe: " + com.coflnet.failsafe.Failsafe.status(), now);
            return;
        }
        if (com.coflnet.ahflipper.AhFlipperMod.isRunningGuiAction()
                || (state != State.SELL && state != State.CLAIM && state != State.OPEN_KING
                && com.coflnet.ahflipper.AhScreenUtil.currentScreen() != com.coflnet.ahflipper.AhScreenUtil.AhScreen.NONE)) {
            hold(mc, "Paused: AH flipper is busy", now);
            return;
        }
        if (!MiningState.inDwarvenMines()) {
            // after a reboot, a kick or a lobby: back to the mines with /warp forge
            if (notInMinesSince == 0) notInMinesSince = now;
            if (now - notInMinesSince > 2_500L) {
                recover(mc, now);
                return;
            }
        } else if (notInMinesSince != 0) {
            boolean wasAway = now - notInMinesSince > 2_500L;
            notInMinesSince = 0;
            if (wasAway) backInMines();
        }
        if (pausedAt != 0) resume(now);
        try {
            switch (state) {
                case TO_KING -> {
                    RouteWalker.Result r = walker.tick(mc);
                    if (r == RouteWalker.Result.ARRIVED) set(State.OPEN_KING);
                    else if (r == RouteWalker.Result.FAILED) retryOrStop("Could not reach the King", State.TO_KING);
                }
                case OPEN_KING -> openKing(mc, player, now);
                case CLAIM -> claim(mc, player, now);
                case PLAN -> plan(mc, player);
                case TRAVEL -> travel(mc, player);
                case SLAYER -> slayer(mc, now);
                case MINING -> mining(mc, player, now);
                case SELL -> sell(mc, player, now);
                default -> {
                }
            }
        } catch (RuntimeException e) {
            System.out.println("[CommissionBot] " + state + " error: " + e);
        }
    }

    // ------------------------------------------------------------------ pause / recovery

    private static long pausedAt;
    private static String pauseReason = "";
    private static long nextRecoverAt;

    /** Stops moving and mining (keys free), keeps the plan; {@link #resume} goes on from there. */
    private static void hold(Minecraft mc, String why, long now) {
        if (pausedAt != 0) return;
        pausedAt = now;
        pauseReason = why;
        walker.pause();
        miner.stop();
        if (FollowFeature.isBorrowed()) FollowFeature.giveBack();
        log(why);
    }

    private static void resume(long now) {
        long paused = now - pausedAt;
        pausedAt = 0;
        stateAt += paused; // timeouts do not count the pause
        nextActionAt += paused;
        walker.resume();
        if (state == State.MINING && task != null) startMiner();
        if (state == State.SLAYER && task != null) FollowFeature.borrow(slayerTargets(task));
        log("Going on after " + (paused / 1000) + " s (" + pauseReason + ")");
    }

    /** Not in the Dwarven Mines: on SkyBlock "/warp forge", elsewhere (lobby, limbo) "/skyblock" first. */
    private static void recover(Minecraft mc, long now) {
        hold(mc, "Not in the Dwarven Mines - going back", now);
        if (now - notInMinesSince > 4 * 60_000L) {
            stop("Could not get back to the Dwarven Mines");
            return;
        }
        if (now < nextRecoverAt || mc.getConnection() == null) return;
        boolean onSkyBlock = !MiningState.area().isEmpty();
        mc.getConnection().sendCommand(onSkyBlock ? "warp forge" : "skyblock");
        log(onSkyBlock ? "/warp forge" : "/skyblock");
        nextRecoverAt = now + (onSkyBlock ? 8_000L : 10_000L);
    }

    /** Back after a reboot / warp: plan again from here; the current task goes first. */
    private static void backInMines() {
        pausedAt = 0;
        walker.stop();
        miner.stop();
        if (task != null) {
            queue.addFirst(task);
            task = null;
        }
        message("§aBack in the Dwarven Mines, going on.");
        if (state == State.TO_KING || state == State.OPEN_KING || state == State.CLAIM || queue.isEmpty()) {
            goKing();
        } else {
            set(State.PLAN);
        }
    }

    /** A sword-like weapon from the hotbar (the Goblins were fought with one), if there is one. */
    private static void selectWeapon(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String name = clean(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
            if (name.contains("aspect of the dragons") || name.contains("sword") || name.contains("blade")
                    || name.contains("katana") || name.contains("dagger") || name.contains("scythe")) {
                player.getInventory().setSelectedSlot(i);
                return;
            }
        }
    }

    /** Who to fight for a slayer task: Glacite Walkers, or the Goblins of the Goblin Burrows. */
    private static List<String> slayerTargets(Task t) {
        List<String> mobs = t.commission().mobs();
        return mobs.contains("Goblin") ? mobs : List.of("Glacite Walker");
    }

    private static void startMiner() {
        if (task == null) return;
        if (task.commission().goal() == Commission.Goal.TITANIUM) {
            // Titanium shows up while mining Mithril: mine both, Titanium first
            miner.start(b -> b == MiningBlock.TITANIUM || b.isMithril(), b -> b == MiningBlock.TITANIUM);
        } else {
            // Mithril Miner: Titanium does not count, leave it
            miner.start(MiningBlock::isMithril, b -> false);
        }
    }

    private static void retryOrStop(String why, State again) {
        if (++tries > 2) {
            stop(why);
            return;
        }
        log(why + " - try " + tries);
        int keep = tries;
        if (again == State.TO_KING) goKing();
        else set(again);
        tries = keep;
    }

    // ------------------------------------------------------------------ King

    private static void openKing(Minecraft mc, LocalPlayer player, long now) {
        if (mc.gui.screen() instanceof AbstractContainerScreen<?> c && clean(c.getTitle().getString()).startsWith("Commissions")) {
            lookAt = null;
            nextActionAt = now + 350 + ThreadLocalRandom.current().nextInt(300);
            set(State.CLAIM);
            return;
        }
        if (king == null || king.isRemoved()) king = findKing(mc);
        if (king == null) {
            if (now - stateAt > 5_000L) retryOrStop("King not found", State.TO_KING);
            return;
        }
        lookAt = king.getEyePosition().add(0, -0.3, 0);
        double distance = player.getEyePosition().distanceTo(king.getEyePosition());
        if (distance > 4.0) {
            if (now - stateAt > 6_000L) goKing();
            return;
        }
        if (aimError(player, lookAt) < 6f && now >= nextActionAt) {
            mc.gameMode.interact(player, king, new EntityHitResult(king), InteractionHand.MAIN_HAND);
            player.swing(InteractionHand.MAIN_HAND);
            nextActionAt = now + GUI_TIMEOUT_MS;
            if (++tries > 4) stop("The King does not open");
        }
    }

    /** The King NPC: a player-shaped NPC on the throne spot, its name on the armor stand above. */
    private static Entity findKing(Minecraft mc) {
        Vec3 spot = DwarvenGraph.get().place("kingNpc");
        Entity best = null;
        double bestDist = 3.0;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Player) || e == mc.player) continue;
            double d = e.position().distanceTo(spot);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    private record GuiCommission(int slot, String name, boolean completed, String progress) {
    }

    private static void claim(Minecraft mc, LocalPlayer player, long now) {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> c)
                || !clean(c.getTitle().getString()).startsWith("Commissions")) {
            if (now - stateAt > GUI_TIMEOUT_MS) set(State.OPEN_KING);
            return;
        }
        if (now < nextActionAt) return;
        List<GuiCommission> list = readCommissions(c.getMenu());
        if (list.isEmpty()) {
            if (now - stateAt > GUI_TIMEOUT_MS) {
                player.closeContainer();
                stop("Could not read the commissions");
            }
            return;
        }
        for (GuiCommission g : list) {
            if (g.completed()) {
                mc.gameMode.handleContainerInput(c.getMenu().containerId, g.slot(), 0, ContainerInput.PICKUP, player);
                claimed++;
                log("Claimed " + g.name());
                nextActionAt = now + 500 + ThreadLocalRandom.current().nextInt(400);
                stateAt = now;
                return;
            }
        }
        queue.clear();
        List<String> skipped = new ArrayList<>();
        for (GuiCommission g : list) {
            Commission commission = Commission.parse(g.name(), g.progress().isEmpty() ? "0%" : g.progress());
            Kind kind = kindOf(commission);
            if (kind == null) {
                skipped.add(g.name());
            } else {
                queue.add(new Task(g.name(), kind, commission));
            }
        }
        // mining first when we stand at the King (the spots are closer than the walkers)
        List<Task> ordered = new ArrayList<>(queue);
        ordered.sort((a, b) -> Integer.compare(a.kind() == Kind.MINING ? 0 : 1, b.kind() == Kind.MINING ? 0 : 1));
        queue.clear();
        queue.addAll(ordered);
        message("§7Commissions: §f" + String.join(", ", list.stream().map(GuiCommission::name).toList())
                + (skipped.isEmpty() ? "" : " §8(skipping " + String.join(", ", skipped) + ")"));
        player.closeContainer();
        workedSinceKing = false;
        set(State.PLAN);
    }

    private static Kind kindOf(Commission c) {
        if (c.goal() == Commission.Goal.MOBS && PlayerSettings.miningBotSlayer
                && (c.mobs().contains("Glacite Walker") || c.mobs().contains("Goblin"))) {
            return Kind.SLAYER;
        }
        if ((c.goal() == Commission.Goal.MITHRIL || c.goal() == Commission.Goal.TITANIUM) && PlayerSettings.miningBotMining) {
            return Kind.MINING;
        }
        return null;
    }

    private static List<GuiCommission> readCommissions(AbstractContainerMenu menu) {
        List<GuiCommission> list = new ArrayList<>();
        int size = menu instanceof ChestMenu chest ? chest.getContainer().getContainerSize() : menu.slots.size() - 36;
        for (int i = 0; i < size && i < menu.slots.size(); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty() || !clean(stack.getHoverName().getString()).startsWith("Commission #")) continue;
            List<String> lore = lore(stack);
            String name = "";
            boolean completed = false;
            String progress = "";
            for (int l = 0; l < lore.size(); l++) {
                String line = lore.get(l);
                if (name.isEmpty() && line.toLowerCase(Locale.ROOT).endsWith("rewards.")) {
                    for (int k = l + 1; k < lore.size(); k++) {
                        if (!lore.get(k).isBlank()) {
                            name = lore.get(k).trim();
                            break;
                        }
                    }
                }
                if (line.equals("COMPLETED") || line.contains("Click to claim")) completed = true;
                if (line.equals("Progress") && l + 1 < lore.size()) progress = lore.get(l + 1).trim();
            }
            if (!name.isEmpty()) list.add(new GuiCommission(i, name, completed, progress));
        }
        return list;
    }

    // ------------------------------------------------------------------ tasks

    private static void plan(Minecraft mc, LocalPlayer player) {
        if (inventoryFull(player) && PlayerSettings.miningBotSell && !sellBroken) {
            startSell(State.PLAN);
            return;
        }
        while (!queue.isEmpty() && isDone(queue.peek().name())) queue.poll();
        if (queue.isEmpty()) {
            task = null;
            if (workedSinceKing) {
                goKing(); // claim what we did, get new ones
                return;
            }
            stop("No commission the bot can do (events only?)");
            return;
        }
        task = queue.poll();
        Vec3 here = player.position();
        DwarvenGraph graph = DwarvenGraph.get();
        if (task.kind() == Kind.SLAYER) {
            boolean goblins = task.commission().mobs().contains("Goblin");
            Vec3 place = graph.place(goblins ? "goblins" : "walkers");
            if (place == null) place = graph.place("walkers");
            walker.start(place, 10.0, true);
        } else {
            Vec3 where = null;
            String area = task.commission().area();
            DwarvenGraph.Spot spot = graph.bestSpot(here, area);
            if (spot != null) where = spot.pos();
            if (where == null && !area.isEmpty()) where = graph.anyPointIn(here, area);
            if (where == null) {
                spot = graph.bestSpot(here, null);
                where = spot == null ? null : spot.pos();
            }
            if (where == null) {
                log("No place known for " + task.name());
                set(State.PLAN);
                return;
            }
            walker.start(where, 6.0, true);
        }
        message("§7Next: §f" + task.name());
        set(State.TRAVEL);
    }

    /** A task was finished since the last visit to the King (then going back is worth it). */
    private static boolean workedSinceKing;

    private static boolean isDone(String name) {
        for (Commission c : MiningState.commissions()) {
            if (c.name().equalsIgnoreCase(name)) return c.done();
        }
        return false;
    }

    private static void travel(Minecraft mc, LocalPlayer player) {
        if (task == null) {
            set(State.PLAN);
            return;
        }
        if (isDone(task.name())) {
            finishTask();
            return;
        }
        RouteWalker.Result r = walker.tick(mc);
        if (r == RouteWalker.Result.ARRIVED) {
            if (task.kind() == Kind.SLAYER) {
                AotvAssist.suppress(true); // Combat walks: no etherwarp, no teleport
                if (task.commission().mobs().contains("Goblin")) selectWeapon(player); // like the recording
                FollowFeature.borrow(slayerTargets(task));
                set(State.SLAYER);
            } else {
                startMiner();
                set(State.MINING);
            }
        } else if (r == RouteWalker.Result.FAILED) {
            log("Could not get to " + task.name() + ", skipping it");
            task = null;
            set(State.PLAN);
        }
    }

    private static void slayer(Minecraft mc, long now) {
        if (task == null || isDone(task.name())) {
            finishTask();
            return;
        }
        if (now - stateAt > SLAYER_TIMEOUT_MS) {
            log("Slayer took too long, moving on");
            finishTask();
        }
    }

    private static void mining(Minecraft mc, LocalPlayer player, long now) {
        if (task == null || isDone(task.name())) {
            finishTask();
            return;
        }
        if (inventoryFull(player) && PlayerSettings.miningBotSell && !sellBroken) {
            miner.stop();
            startSell(State.MINING);
            return;
        }
        if (DwarvenMiner.pickaxeSlot(player) < 0) {
            stop("No pickaxe or drill in the hotbar");
            return;
        }
        miner.tick(mc);
        if (miner.exhaustedMs() > 12_000L) {
            // nothing left around here (or it is all bedrock until it regenerates): next spot
            miner.stop();
            DwarvenGraph graph = DwarvenGraph.get();
            String area = task.commission().area();
            for (DwarvenGraph.Spot s : graph.spotsSortedFrom(player.position())) {
                if (s.pos().distanceTo(player.position()) < 15) continue;
                if (!area.isEmpty() && !s.area().equalsIgnoreCase(area)) continue;
                log("Spot empty, moving to " + s.area());
                walker.start(s.pos(), 6.0, true);
                set(State.TRAVEL);
                return;
            }
            miner.start(MiningBlock::isMithril, b -> false); // nowhere else known: wait for regen
        }
        if (now - stateAt > MINING_TIMEOUT_MS) {
            log("Mining took too long, moving on");
            finishTask();
        }
    }

    private static void finishTask() {
        miner.stop();
        walker.stop();
        if (FollowFeature.isBorrowed()) {
            FollowFeature.giveBack();
            AotvAssist.suppress(false);
        }
        if (task != null) {
            finished++;
            workedSinceKing = true;
            message("§aDone: §f" + task.name());
        }
        task = null;
        set(State.PLAN);
    }

    private static void onChat(String text) {
        if (text.contains("Commission Complete!") && task != null
                && text.toLowerCase(Locale.ROOT).contains(task.name().toLowerCase(Locale.ROOT))) {
            if (state == State.SLAYER || state == State.MINING || state == State.TRAVEL) finishTask();
        }
    }

    // ------------------------------------------------------------------ selling (recorded: /bz -> Sell Inventory Now)

    private static boolean inventoryFull(LocalPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    private static void startSell(State back) {
        afterSell = back;
        sellStep = 0;
        set(State.SELL);
    }

    private static void sell(Minecraft mc, LocalPlayer player, long now) {
        if (now < nextActionAt) return;
        switch (sellStep) {
            case 0 -> {
                RouteWalker.stopBaritone();
                if (mc.getConnection() != null) mc.getConnection().sendCommand("bz");
                sellStep = 1;
                stateAt = now;
            }
            case 1, 2 -> {
                String want = sellStep == 1 ? "Sell Inventory Now" : "Selling whole inventory";
                if (mc.gui.screen() instanceof AbstractContainerScreen<?> c) {
                    int slot = slotNamed(c.getMenu(), want);
                    if (slot >= 0) {
                        mc.gameMode.handleContainerInput(c.getMenu().containerId, slot, 0, ContainerInput.PICKUP, player);
                        sellStep++;
                        stateAt = now;
                        nextActionAt = now + 500 + ThreadLocalRandom.current().nextInt(300);
                        return;
                    }
                }
                if (now - stateAt > 5_000L) {
                    log("Selling did not work (no Booster Cookie?) - not trying again this session");
                    sellBroken = true;
                    if (mc.gui.screen() != null) player.closeContainer();
                    set(afterSell);
                }
            }
            default -> {
                if (mc.gui.screen() != null) player.closeContainer();
                log("Inventory sold");
                set(afterSell);
                if (afterSell == State.MINING && task != null) startMiner();
            }
        }
    }

    private static int slotNamed(AbstractContainerMenu menu, String name) {
        int size = menu instanceof ChestMenu chest ? chest.getContainer().getContainerSize() : menu.slots.size();
        for (int i = 0; i < size && i < menu.slots.size(); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (!stack.isEmpty() && clean(stack.getHoverName().getString()).contains(name)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ camera

    /** Every frame (KillAuraFrameMixin): the King look, or the miner's aim. */
    public static void frame(Minecraft mc) {
        if (state == State.IDLE) return;
        LocalPlayer player = mc.player;
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.min(0.05, (now - lastFrame) / 1e9);
        lastFrame = now;
        if (player == null || mc.gui.screen() != null) return;
        if (state == State.MINING) {
            miner.frame(mc);
        } else if ((state == State.TO_KING || state == State.TRAVEL) && walker.lookPoint() != null
                && !AotvAssist.isBusy()) {
            // straight walking: a calm camera on the way ahead (it also steers: forward = where we look)
            Vec3 d = walker.lookPoint().subtract(player.getEyePosition());
            float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
            float pitch = Math.clamp((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))), -15f, 30f);
            player.setYRot(AimSmoothing.step(player.getYRot(), yaw, 300f, dt, 8.0));
            player.setXRot(Math.clamp(AimSmoothing.step(player.getXRot(), pitch, 160f, dt, 6.0), -90f, 90f));
        } else if (state == State.OPEN_KING && lookAt != null) {
            Vec3 d = lookAt.subtract(player.getEyePosition());
            float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
            player.setYRot(AimSmoothing.step(player.getYRot(), yaw, 420f, dt));
            player.setXRot(Math.clamp(AimSmoothing.step(player.getXRot(), pitch, 420f, dt), -90f, 90f));
        }
    }

    private static float aimError(LocalPlayer player, Vec3 point) {
        Vec3 d = point.subtract(player.getEyePosition());
        float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        return Math.max(Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - player.getYRot())),
                Math.abs(pitch - player.getXRot()));
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> lore(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        var lore = stack.get(DataComponents.LORE);
        if (lore == null) return lines;
        for (Component line : lore.lines()) lines.add(clean(line.getString()));
        return lines;
    }

    private static String clean(String s) {
        return ScreenUtil.cleanName(s);
    }

    static void log(String text) {
        System.out.println("[CommissionBot] " + text);
        if (PlayerSettings.miningBotChat) message("§8" + text);
    }

    static void message(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§b[Commissions] §r" + text));
    }
}
