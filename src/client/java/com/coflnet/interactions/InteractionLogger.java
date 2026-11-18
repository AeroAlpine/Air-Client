package com.coflnet.interactions;

import com.coflnet.flipper.trading.ScreenUtil;
import com.coflnet.utils.SkyblockLocation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

import java.io.BufferedWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Interaction Logger: press the hotkey, do something in the game, press it again and name it.
 * Everything in between (path, island, GUIs and their items, clicks, NPC/entity/block
 * interactions, chat, scoreboard, tab list and the blocks around the path) is written to
 * {@code logs/interactions/<time>_<name>.json} plus a readable {@code .txt} timeline,
 * so new scripts can be built from real data instead of clips.
 */
public final class InteractionLogger {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final int MAX_EVENTS = 50_000;
    private static final int MAX_PATH_POINTS = 100_000;
    private static final int MAX_MAP_BLOCKS = 400_000;
    private static final int MAX_ENTITIES = 3_000;

    private static KeyMapping key;
    private static Recording recording;
    private static boolean keyWasDown;
    private static boolean initialized;

    private InteractionLogger() {
    }

    // ------------------------------------------------------------------ setup

    public static void init(KeyMapping toggleKey) {
        key = toggleKey;
        if (initialized) {
            return;
        }
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(InteractionLogger::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            Recording r = recording;
            if (r != null) r.onGameMessage(message, overlay);
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> {
            Recording r = recording;
            if (r != null && pingplus.voicechat.client.PlayerSettings.interactionChat) {
                JsonObject e = r.event("chat");
                e.addProperty("text", clean(message.getString()));
                e.addProperty("player", true);
                if (sender != null) e.addProperty("sender", sender.name());
            }
        });
        ClientSendMessageEvents.COMMAND.register(command -> {
            Recording r = recording;
            if (r != null) r.event("command").addProperty("command", "/" + command);
        });
        ClientSendMessageEvents.CHAT.register(message -> {
            Recording r = recording;
            if (r != null) r.event("sent_chat").addProperty("text", message);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            Recording r = recording;
            if (r != null) {
                recording = null;
                r.event("disconnect");
                r.finish();
                save(r, "disconnected");
            }
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("voicechat", "interaction_logger"),
                (graphics, delta) -> {
                    Recording r = recording;
                    Minecraft mc = Minecraft.getInstance();
                    if (r == null || mc.player == null) return;
                    long secs = (System.currentTimeMillis() - r.startedAt) / 1000;
                    String text = "● REC " + String.format(Locale.ROOT, "%02d:%02d", secs / 60, secs % 60)
                            + "  " + r.events.size() + " events";
                    int w = mc.font.width(text) + 8;
                    int x = (graphics.guiWidth() - w) / 2;
                    graphics.fill(x, 2, x + w, 14, 0x90000000);
                    graphics.text(mc.font, text, x + 4, 4, (secs % 2 == 0) ? 0xFFFF5555 : 0xFFFF9999, false);
                });
    }

    public static KeyMapping key() {
        return key;
    }

    public static boolean isRecording() {
        return recording != null;
    }

    public static String status() {
        Recording r = recording;
        if (r == null) {
            String bound = key == null || key.isUnbound() ? "no key" : key.getTranslatedKeyMessage().getString();
            return "Idle - press " + bound + " to start";
        }
        long secs = (System.currentTimeMillis() - r.startedAt) / 1000;
        return "Recording " + secs + " s, " + r.events.size() + " events, " + r.map.size() + " blocks";
    }

    public static Path folder() {
        return FabricLoader.getInstance().getGameDir().resolve("logs").resolve("interactions");
    }

    // ------------------------------------------------------------------ start / stop

    public static void toggle() {
        if (recording == null) start();
        else stop();
    }

    private static void start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        recording = new Recording(mc);
        message("§aRecording started. §7Press §f" + keyName() + "§7 again to stop and name it.");
    }

    private static void stop() {
        Recording r = recording;
        if (r == null) return;
        recording = null;
        r.event("stop");
        r.finish();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.gui.screen() instanceof AbstractContainerScreen<?>) {
            mc.player.closeContainer();
        }
        mc.gui.setScreen(new InteractionNameScreen(r));
    }

    private static String keyName() {
        return key == null || key.isUnbound() ? "the key" : key.getTranslatedKeyMessage().getString();
    }

    static void message(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§b[Interactions] §r" + text));
        }
    }

    private static void tick(Minecraft mc) {
        while (key != null && key.consumeClick()) {
            if (!(mc.gui.screen() instanceof InteractionNameScreen)) toggle();
        }
        // Key mappings do not fire inside GUIs; let the key also stop a recording in a container menu.
        Screen screen = mc.gui.screen();
        boolean down = false;
        if (key != null && !key.isUnbound() && screen instanceof AbstractContainerScreen<?>) {
            InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(key);
            if (bound != null && bound.getType() == InputConstants.Type.KEYSYM) {
                down = InputConstants.isKeyDown(mc.getWindow(), bound.getValue());
            }
        }
        if (down && !keyWasDown && recording != null) {
            stop();
        }
        keyWasDown = down;
        Recording r = recording;
        if (r != null) {
            try {
                r.tick(mc);
            } catch (RuntimeException e) {
                System.out.println("[Interactions] tick error: " + e);
            }
        }
    }

    // ------------------------------------------------------------------ hooks (mixin)

    public static void onContainerInput(int containerId, int slotId, int button, ContainerInput input) {
        Recording r = recording;
        if (r == null) return;
        Minecraft mc = Minecraft.getInstance();
        JsonObject e = r.event("click");
        e.addProperty("slot", slotId);
        e.addProperty("button", button);
        e.addProperty("mode", input.name());
        if (mc.gui.screen() instanceof AbstractContainerScreen<?> container) {
            e.addProperty("gui", clean(container.getTitle().getString()));
            int size = containerSize(container.getMenu());
            e.addProperty("inPlayerInventory", slotId >= size);
            AbstractContainerMenu menu = container.getMenu();
            if (slotId >= 0 && slotId < menu.slots.size()) {
                Slot slot = menu.slots.get(slotId);
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) e.add("item", itemJson(stack, true));
            }
            ItemStack carried = menu.getCarried();
            if (!carried.isEmpty()) e.add("carried", itemJson(carried, false));
        }
    }

    public static void onInteractEntity(Entity entity) {
        Recording r = recording;
        if (r == null || entity == null) return;
        JsonObject e = r.event("interact_entity");
        e.add("entity", entityJson(entity, true));
        r.noteEntity(entity);
    }

    public static void onAttackEntity(Entity entity) {
        Recording r = recording;
        if (r == null || entity == null) return;
        if (!r.attackThrottle(entity.getId())) return;
        JsonObject e = r.event("attack_entity");
        e.add("entity", entityJson(entity, false));
    }

    public static void onUseItemOn(BlockHitResult hit) {
        Recording r = recording;
        Minecraft mc = Minecraft.getInstance();
        if (r == null || hit == null || mc.level == null) return;
        JsonObject e = r.event("use_block");
        BlockPos pos = hit.getBlockPos();
        e.addProperty("block", blockId(mc.level.getBlockState(pos)));
        e.add("blockPos", posJson(pos));
        e.addProperty("face", hit.getDirection().getName());
        addHeld(e, mc);
    }

    public static void onUseItem() {
        Recording r = recording;
        Minecraft mc = Minecraft.getInstance();
        if (r == null || mc.player == null) return;
        if (mc.player.getMainHandItem().isEmpty()) return;
        JsonObject e = r.event("use_item");
        addHeld(e, mc);
        e.addProperty("yaw", round1(mc.player.getYRot()));
        e.addProperty("pitch", round1(mc.player.getXRot()));
    }

    public static void onStartDestroy(BlockPos pos, Direction face) {
        Recording r = recording;
        Minecraft mc = Minecraft.getInstance();
        if (r == null || mc.level == null || pos == null) return;
        r.onStartDestroy(mc, pos.immutable(), face);
    }

    // ------------------------------------------------------------------ recording

    static final class Recording {
        final long startedAt = System.currentTimeMillis();
        final LocalDateTime startedTime = LocalDateTime.now();
        final List<JsonObject> events = new ArrayList<>();
        final List<double[]> path = new ArrayList<>();
        final Map<Long, Integer> map = new HashMap<>();
        final List<String> palette = new ArrayList<>();
        final Map<String, Integer> paletteIndex = new HashMap<>();
        final Set<Long> scannedCells = new HashSet<>();
        final Map<String, JsonObject> entities = new LinkedHashMap<>();
        final Map<String, Integer> guiSnapshots = new HashMap<>();
        final Map<Integer, Integer> lastAttack = new HashMap<>();
        final Map<BlockPos, Object[]> mining = new HashMap<>();
        final Set<String> areas = new LinkedHashSet<>();
        long endedAt;
        int ticks;
        boolean truncated;

        String area = "";
        Object level;
        double lastX = Double.NaN, lastY, lastZ;
        float lastYaw, lastPitch;
        int lastPathTick = -1000;
        int lastHotbar = -1;
        String lastHeld = "";
        // GUI state
        Screen lastScreen;
        String lastScreenTitle = "";
        String pendingSignature = "";
        int pendingStableTicks;
        String loggedSignature = "";
        // Text state
        String lastActionBarKey = "";
        long lastActionBarAt;
        List<String> lastSidebar = List.of();
        List<String> lastTab = List.of();
        int lastScanTick = -1000;

        Recording(Minecraft mc) {
            level = mc.level;
            JsonObject e = event("start");
            e.addProperty("dimension", mc.level.dimension().identifier().toString());
            e.addProperty("server", mc.getCurrentServer() == null ? "" : mc.getCurrentServer().ip);
            updateArea(true);
            if (pingplus.voicechat.client.PlayerSettings.interactionScoreboard) {
                lastSidebar = sidebarLines(mc);
                lastTab = tabLines(mc);
                JsonObject sb = event("sidebar");
                sb.add("lines", stringArray(lastSidebar));
                JsonObject tab = event("tablist");
                tab.add("lines", stringArray(lastTab));
            }
            JsonObject inv = event("inventory");
            JsonArray items = new JsonArray();
            var inventory = mc.player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) continue;
                JsonObject item = itemJson(stack, false);
                item.addProperty("slot", i);
                items.add(item);
            }
            inv.add("items", items);
        }

        double seconds() {
            return Math.round((System.currentTimeMillis() - startedAt) / 10.0) / 100.0;
        }

        JsonObject event(String type) {
            JsonObject e = new JsonObject();
            e.addProperty("t", seconds());
            e.addProperty("type", type);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                Vec3 p = mc.player.position();
                JsonArray pos = new JsonArray();
                pos.add(round1(p.x));
                pos.add(round1(p.y));
                pos.add(round1(p.z));
                e.add("pos", pos);
            }
            if (!area.isEmpty()) e.addProperty("area", area);
            if (events.size() < MAX_EVENTS) events.add(e);
            else truncated = true;
            return e;
        }

        void finish() {
            endedAt = System.currentTimeMillis();
        }

        void tick(Minecraft mc) {
            ticks++;
            if (mc.player == null || mc.level == null) return;
            if (mc.level != level) {
                level = mc.level;
                event("world_change").addProperty("dimension", mc.level.dimension().identifier().toString());
                scannedCells.clear();
                mining.clear();
            }
            if (ticks % 20 == 0) updateArea(false);
            trackPath(mc);
            trackHotbar(mc);
            trackScreen(mc);
            trackMining(mc);
            if (pingplus.voicechat.client.PlayerSettings.interactionScoreboard && ticks % 20 == 0) trackSidebar(mc);
            if (pingplus.voicechat.client.PlayerSettings.interactionScoreboard && ticks % 40 == 5) trackTab(mc);
            if (ticks % 40 == 10) scanEntities(mc);
            if (pingplus.voicechat.client.PlayerSettings.interactionScanMap && ticks - lastScanTick >= 5) scanMap(mc);
        }

        void updateArea(boolean force) {
            String now = SkyblockLocation.describe();
            if (force || !now.equals(area)) {
                String before = area;
                area = now;
                areas.add(now);
                if (!force) {
                    JsonObject e = event("area_change");
                    e.addProperty("from", before);
                    e.addProperty("to", now);
                }
            }
        }

        void trackPath(Minecraft mc) {
            Player p = mc.player;
            double x = p.getX(), y = p.getY(), z = p.getZ();
            float yaw = p.getYRot(), pitch = p.getXRot();
            boolean first = Double.isNaN(lastX);
            double moved = first ? 99 : Math.sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ));
            float turned = first ? 99 : Math.abs(wrap(yaw - lastYaw)) + Math.abs(pitch - lastPitch);
            if (moved > 8 && !first) {
                JsonObject e = event("teleport");
                e.addProperty("distance", round1(moved));
                JsonArray from = new JsonArray();
                from.add(round1(lastX));
                from.add(round1(lastY));
                from.add(round1(lastZ));
                e.add("from", from);
            }
            if (moved >= 0.5 || turned >= 20 || (ticks - lastPathTick >= 40 && (moved > 0.05 || turned > 1))) {
                if (path.size() < MAX_PATH_POINTS) {
                    path.add(new double[]{seconds(), round2(x), round2(y), round2(z), round1(yaw), round1(pitch),
                            p.onGround() ? 1 : 0, p.isSprinting() ? 1 : 0, p.isShiftKeyDown() ? 1 : 0});
                }
                lastX = x;
                lastY = y;
                lastZ = z;
                lastYaw = yaw;
                lastPitch = pitch;
                lastPathTick = ticks;
            }
        }

        void trackHotbar(Minecraft mc) {
            int selected = mc.player.getInventory().getSelectedSlot();
            ItemStack held = mc.player.getMainHandItem();
            String name = held.isEmpty() ? "" : clean(held.getHoverName().getString());
            if (selected != lastHotbar || !name.equals(lastHeld)) {
                if (lastHotbar != -1) {
                    JsonObject e = event("held_item");
                    e.addProperty("hotbarSlot", selected);
                    if (!held.isEmpty()) e.add("item", itemJson(held, false));
                }
                lastHotbar = selected;
                lastHeld = name;
            }
        }

        void trackScreen(Minecraft mc) {
            Screen screen = mc.gui.screen();
            if (screen instanceof InteractionNameScreen || screen instanceof ChatScreen) {
                screen = null;
            }
            String title = screen == null ? "" : clean(screen.getTitle().getString());
            if (screen != lastScreen || !title.equals(lastScreenTitle)) {
                if (lastScreen != null && screen == null) {
                    event("gui_close").addProperty("gui", lastScreenTitle);
                }
                if (screen != null) {
                    JsonObject e = event("gui_open");
                    e.addProperty("gui", title);
                    e.addProperty("screen", screen.getClass().getSimpleName());
                    if (screen instanceof AbstractContainerScreen<?> c) {
                        e.addProperty("slots", containerSize(c.getMenu()));
                        e.addProperty("menu", c.getMenu().getClass().getSimpleName());
                    }
                }
                lastScreen = screen;
                lastScreenTitle = title;
                pendingSignature = "";
                loggedSignature = "";
                pendingStableTicks = 0;
            }
            if (!pingplus.voicechat.client.PlayerSettings.interactionGuis || !(screen instanceof AbstractContainerScreen<?> container)) {
                return;
            }
            // Items arrive a few ticks after the screen opens and Hypixel menus change while open:
            // log the contents each time they changed and then stayed the same for 3 ticks.
            String signature = signature(container);
            if (!signature.equals(pendingSignature)) {
                pendingSignature = signature;
                pendingStableTicks = 0;
                return;
            }
            pendingStableTicks++;
            if (pendingStableTicks == 3 && !signature.equals(loggedSignature)) {
                loggedSignature = signature;
                logContents(container, signature);
            }
        }

        void logContents(AbstractContainerScreen<?> container, String signature) {
            String title = clean(container.getTitle().getString());
            String key = title + "\n" + signature;
            Integer earlier = guiSnapshots.get(key);
            JsonObject e = event("gui_contents");
            e.addProperty("gui", title);
            if (earlier != null) {
                e.addProperty("sameAsSnapshot", earlier);
                return;
            }
            int id = guiSnapshots.size() + 1;
            guiSnapshots.put(key, id);
            e.addProperty("snapshot", id);
            AbstractContainerMenu menu = container.getMenu();
            int size = containerSize(menu);
            e.addProperty("slots", size);
            JsonArray items = new JsonArray();
            for (int i = 0; i < size && i < menu.slots.size(); i++) {
                ItemStack stack = menu.slots.get(i).getItem();
                if (stack.isEmpty()) continue;
                JsonObject item = itemJson(stack, true);
                item.addProperty("slot", i);
                items.add(item);
            }
            e.add("items", items);
        }

        void onStartDestroy(Minecraft mc, BlockPos pos, Direction face) {
            if (mining.containsKey(pos)) return;
            BlockState state = mc.level.getBlockState(pos);
            if (state.isAir()) return;
            String id = blockId(state);
            JsonObject e = event("mine_start");
            e.addProperty("block", id);
            e.add("blockPos", posJson(pos));
            e.addProperty("face", face == null ? "" : face.getName());
            addHeld(e, mc);
            if (mining.size() > 64) mining.clear();
            mining.put(pos, new Object[]{id, System.currentTimeMillis()});
        }

        void trackMining(Minecraft mc) {
            if (mining.isEmpty()) return;
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<BlockPos, Object[]>> it = mining.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                String before = (String) entry.getValue()[0];
                long since = (long) entry.getValue()[1];
                String current = blockId(mc.level.getBlockState(entry.getKey()));
                if (!current.equals(before)) {
                    JsonObject e = event("block_broken");
                    e.addProperty("block", before);
                    e.addProperty("becomes", current);
                    e.add("blockPos", posJson(entry.getKey()));
                    e.addProperty("ms", now - since);
                    it.remove();
                } else if (now - since > 15_000) {
                    it.remove();
                }
            }
        }

        void onGameMessage(Component message, boolean overlay) {
            String text = clean(message.getString());
            if (text.isBlank()) return;
            if (overlay) {
                // The action bar repeats every tick with changing numbers: log only when its shape changes.
                String shape = text.replaceAll("[0-9.,]+", "#");
                long now = System.currentTimeMillis();
                if (!shape.equals(lastActionBarKey) || now - lastActionBarAt > 15_000) {
                    lastActionBarKey = shape;
                    lastActionBarAt = now;
                    event("actionbar").addProperty("text", text);
                }
                return;
            }
            if (!pingplus.voicechat.client.PlayerSettings.interactionChat) return;
            event("chat").addProperty("text", text);
        }

        void trackSidebar(Minecraft mc) {
            List<String> now = sidebarLines(mc);
            if (now.equals(lastSidebar)) return;
            JsonObject e = event("sidebar");
            e.add("lines", stringArray(now));
            lastSidebar = now;
        }

        void trackTab(Minecraft mc) {
            List<String> now = tabLines(mc);
            if (now.equals(lastTab)) return;
            List<String> added = new ArrayList<>(now);
            added.removeAll(lastTab);
            List<String> removed = new ArrayList<>(lastTab);
            removed.removeAll(now);
            lastTab = now;
            if (added.isEmpty() && removed.isEmpty()) return;
            JsonObject e = event("tablist_change");
            e.add("added", stringArray(added));
            e.add("removed", stringArray(removed));
        }

        boolean attackThrottle(int entityId) {
            Integer last = lastAttack.get(entityId);
            if (last != null && ticks - last < 10) return false;
            if (lastAttack.size() > 256) lastAttack.clear();
            lastAttack.put(entityId, ticks);
            return true;
        }

        void scanEntities(Minecraft mc) {
            Vec3 me = mc.player.position();
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity == mc.player || entity.position().distanceToSqr(me) > 32 * 32) continue;
                noteEntity(entity);
            }
        }

        void noteEntity(Entity entity) {
            if (entities.size() >= MAX_ENTITIES) return;
            String name = entityName(entity);
            boolean named = entity.getCustomName() != null || entity instanceof Player;
            if (!named || name.isBlank()) return;
            BlockPos pos = entity.blockPosition();
            // Same name in the same 8-block cell = same NPC/mob group.
            String shape = name.replaceAll("[0-9.,]+[kKmM]?", "#");
            String key = shape + "@" + (pos.getX() >> 3) + "," + (pos.getY() >> 3) + "," + (pos.getZ() >> 3);
            if (entities.containsKey(key)) return;
            JsonObject json = entityJson(entity, false);
            json.addProperty("firstSeen", seconds());
            json.addProperty("area", area);
            entities.put(key, json);
        }

        void scanMap(Minecraft mc) {
            BlockPos center = mc.player.blockPosition();
            long cell = BlockPos.asLong(center.getX() >> 2, center.getY() >> 2, center.getZ() >> 2);
            if (!scannedCells.add(cell)) return;
            lastScanTick = ticks;
            if (map.size() >= MAX_MAP_BLOCKS) return;
            Level level = mc.level;
            int r = Math.clamp(pingplus.voicechat.client.PlayerSettings.interactionScanRadius, 2, 16);
            int down = Math.min(r, 4), up = Math.min(r, 6);
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos near = new BlockPos.MutableBlockPos();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -down; dy <= up; dy++) {
                        at.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        long packed = at.asLong();
                        if (map.containsKey(packed) || !level.isLoaded(at)) continue;
                        BlockState state = level.getBlockState(at);
                        if (state.isAir()) continue;
                        boolean exposed = false;
                        for (Direction d : Direction.values()) {
                            near.setWithOffset(at, d);
                            if (level.getBlockState(near).isAir()) {
                                exposed = true;
                                break;
                            }
                        }
                        if (!exposed) continue;
                        String id = blockId(state);
                        Integer index = paletteIndex.get(id);
                        if (index == null) {
                            index = palette.size();
                            palette.add(id);
                            paletteIndex.put(id, index);
                        }
                        map.put(packed, index);
                        if (map.size() >= MAX_MAP_BLOCKS) return;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ saving

    static void save(Recording r, String rawName) {
        String name = rawName == null || rawName.isBlank() ? "unnamed" : rawName.trim();
        String safe = name.replaceAll("[^A-Za-z0-9äöüÄÖÜß _.-]", "_").replace(' ', '_');
        if (safe.length() > 60) safe = safe.substring(0, 60);
        String base = r.startedTime.format(FILE_TIME) + "_" + safe;
        Thread thread = new Thread(() -> {
            try {
                Path dir = folder();
                Files.createDirectories(dir);
                Path json = dir.resolve(base + ".json");
                Path txt = dir.resolve(base + ".txt");
                writeJson(r, name, json);
                writeTimeline(r, name, txt);
                Minecraft.getInstance().execute(() -> message("§aSaved §f" + name + "§a: §7"
                        + r.events.size() + " events, " + r.path.size() + " path points, " + r.map.size()
                        + " blocks §8(" + json.getFileName() + ")"));
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> message("§cCould not save: " + e.getMessage()));
            }
        }, "interaction-logger-save");
        thread.setDaemon(true);
        thread.start();
    }

    private static void writeJson(Recording r, String name, Path file) throws Exception {
        try (Writer out = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8));
             JsonWriter w = new JsonWriter(out)) {
            w.setIndent(" ");
            w.beginObject();
            w.name("name").value(name);
            w.name("started").value(r.startedTime.toString());
            w.name("durationSeconds").value(Math.round((r.endedAt - r.startedAt) / 100.0) / 10.0);
            w.name("areas");
            GSON.toJson(stringArray(new ArrayList<>(r.areas)), w);
            w.name("truncated").value(r.truncated);
            w.name("events");
            w.beginArray();
            for (JsonObject e : r.events) GSON.toJson(e, w);
            w.endArray();
            w.name("pathFormat").value("[t, x, y, z, yaw, pitch, onGround, sprinting, sneaking]");
            w.name("path");
            w.beginArray();
            w.setIndent("");
            for (double[] p : r.path) {
                w.beginArray();
                for (int i = 0; i < p.length; i++) {
                    if (i >= 6) w.value((long) p[i]);
                    else w.value(p[i]);
                }
                w.endArray();
            }
            w.endArray();
            w.setIndent(" ");
            w.name("entities");
            w.beginArray();
            for (JsonObject e : r.entities.values()) GSON.toJson(e, w);
            w.endArray();
            w.name("map");
            w.beginObject();
            w.name("info").value("Exposed (next to air) blocks within the scan radius of the path. "
                    + "blocks = [x, y, z, paletteIndex]");
            w.name("palette");
            GSON.toJson(stringArray(r.palette), w);
            w.name("counts");
            w.beginObject();
            for (Map.Entry<String, Integer> c : paletteCounts(r).entrySet()) w.name(c.getKey()).value(c.getValue());
            w.endObject();
            w.name("blocks");
            w.beginArray();
            w.setIndent("");
            for (Map.Entry<Long, Integer> b : r.map.entrySet()) {
                long packed = b.getKey();
                w.beginArray();
                w.value(BlockPos.getX(packed)).value(BlockPos.getY(packed)).value(BlockPos.getZ(packed)).value(b.getValue());
                w.endArray();
            }
            w.endArray();
            w.setIndent(" ");
            w.endObject();
            w.endObject();
        }
    }

    private static Map<String, Integer> paletteCounts(Recording r) {
        int[] counts = new int[r.palette.size()];
        for (int index : r.map.values()) counts[index]++;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) order.add(i);
        order.sort((a, b) -> Integer.compare(counts[b], counts[a]));
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i : order) result.put(r.palette.get(i), counts[i]);
        return result;
    }

    private static void writeTimeline(Recording r, String name, Path file) throws Exception {
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("Interaction: " + name + "\n");
            out.write("Started: " + r.startedTime + "   Duration: "
                    + Math.round((r.endedAt - r.startedAt) / 1000.0) + " s\n");
            out.write("Areas: " + String.join(", ", r.areas) + "\n");
            out.write("Events: " + r.events.size() + (r.truncated ? " (truncated)" : "")
                    + "   Path points: " + r.path.size() + "   Map blocks: " + r.map.size() + "\n\n");
            out.write("=== Timeline ===\n");
            for (JsonObject e : r.events) {
                out.write(timelineLine(e));
                out.write("\n");
            }
            out.write("\n=== Entities seen (named) ===\n");
            for (JsonObject e : r.entities.values()) {
                out.write("  " + e.get("name").getAsString() + "  [" + e.get("type").getAsString() + "] at "
                        + e.get("blockPos") + "  t=" + e.get("firstSeen") + "\n");
            }
            out.write("\n=== Blocks around the path ===\n");
            for (Map.Entry<String, Integer> c : paletteCounts(r).entrySet()) {
                out.write(String.format(Locale.ROOT, "  %7d  %s%n", c.getValue(), c.getKey()));
            }
        }
    }

    private static String timelineLine(JsonObject e) {
        StringBuilder line = new StringBuilder();
        line.append(String.format(Locale.ROOT, "[%8.2fs] %-16s", e.get("t").getAsDouble(), e.get("type").getAsString()));
        if (e.has("pos")) line.append(' ').append(e.get("pos"));
        StringBuilder extra = new StringBuilder();
        for (Map.Entry<String, com.google.gson.JsonElement> field : e.entrySet()) {
            String k = field.getKey();
            if (k.equals("t") || k.equals("type") || k.equals("pos") || k.equals("items") || k.equals("lines")
                    || k.equals("area")) continue;
            extra.append(' ').append(k).append('=').append(field.getValue());
        }
        line.append(extra);
        if (e.has("items")) {
            for (var element : e.getAsJsonArray("items")) {
                JsonObject item = element.getAsJsonObject();
                line.append("\n              slot ").append(item.has("slot") ? item.get("slot").getAsInt() : -1)
                        .append(": ").append(item.get("name").getAsString());
                if (item.has("count") && item.get("count").getAsInt() > 1) line.append(" x").append(item.get("count"));
                if (item.has("id")) line.append("  {").append(item.get("id").getAsString()).append('}');
                if (item.has("lore")) {
                    for (var lore : item.getAsJsonArray("lore")) {
                        line.append("\n                  | ").append(lore.getAsString());
                    }
                }
            }
        }
        if (e.has("lines")) {
            for (var l : e.getAsJsonArray("lines")) line.append("\n                  | ").append(l.getAsString());
        }
        return line.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static void addHeld(JsonObject e, Minecraft mc) {
        if (mc.player == null) return;
        ItemStack held = mc.player.getMainHandItem();
        if (!held.isEmpty()) e.add("held", itemJson(held, false));
    }

    static JsonObject itemJson(ItemStack stack, boolean withLore) {
        JsonObject item = new JsonObject();
        item.addProperty("name", clean(stack.getHoverName().getString()));
        item.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if (stack.getCount() != 1) item.addProperty("count", stack.getCount());
        String sbId = skyblockId(stack);
        if (sbId != null) item.addProperty("id", sbId);
        if (withLore) {
            var lore = stack.get(DataComponents.LORE);
            if (lore != null && !lore.lines().isEmpty()) {
                JsonArray lines = new JsonArray();
                for (Component line : lore.lines()) lines.add(clean(line.getString()));
                item.add("lore", lines);
            }
        }
        return item;
    }

    private static String skyblockId(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) return null;
        CompoundTag root = data.copyTag();
        CompoundTag extra = root.getCompound("ExtraAttributes").orElse(root);
        String id = extra.getString("id").orElse("");
        return id.isBlank() ? null : id;
    }

    static JsonObject entityJson(Entity entity, boolean withNearby) {
        JsonObject json = new JsonObject();
        json.addProperty("name", entityName(entity));
        json.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        json.addProperty("uuidVersion", entity.getUUID().version());
        Vec3 p = entity.position();
        JsonArray pos = new JsonArray();
        pos.add(round1(p.x));
        pos.add(round1(p.y));
        pos.add(round1(p.z));
        json.add("pos", pos);
        json.add("blockPos", posJson(entity.blockPosition()));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) json.addProperty("distance", round1(Math.sqrt(entity.distanceToSqr(mc.player))));
        if (withNearby && mc.level != null) {
            // Hypixel puts NPC names and "CLICK" on invisible armor stands just above the NPC.
            JsonArray labels = new JsonArray();
            for (Entity other : mc.level.entitiesForRendering()) {
                if (other == entity || !(other instanceof ArmorStand) || other.getCustomName() == null) continue;
                double dx = other.getX() - entity.getX(), dz = other.getZ() - entity.getZ();
                double dy = other.getY() - entity.getY();
                if (dx * dx + dz * dz <= 1.5 * 1.5 && dy > -1 && dy < 4) {
                    labels.add(clean(other.getCustomName().getString()));
                }
            }
            if (!labels.isEmpty()) json.add("labels", labels);
        }
        return json;
    }

    private static String entityName(Entity entity) {
        Component custom = entity.getCustomName();
        return clean((custom != null ? custom : entity.getName()).getString());
    }

    static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().replace("minecraft:", "");
    }

    private static JsonArray posJson(BlockPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String v : values) array.add(v);
        return array;
    }

    private static int containerSize(AbstractContainerMenu menu) {
        if (menu instanceof ChestMenu chest) return chest.getContainer().getContainerSize();
        return Math.max(0, menu.slots.size() - 36);
    }

    private static String signature(AbstractContainerScreen<?> container) {
        AbstractContainerMenu menu = container.getMenu();
        int size = containerSize(menu);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size && i < menu.slots.size(); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            sb.append(i).append(':').append(stack.getHoverName().getString()).append('x').append(stack.getCount());
            var lore = stack.get(DataComponents.LORE);
            if (lore != null) sb.append('#').append(lore.lines().hashCode());
            sb.append(';');
        }
        return sb.toString();
    }

    private static List<String> sidebarLines(Minecraft mc) {
        List<String> lines = new ArrayList<>();
        if (mc.level == null) return lines;
        Scoreboard scoreboard = mc.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) return lines;
        lines.add("# " + clean(objective.getDisplayName().getString()));
        List<Object[]> scored = new ArrayList<>();
        for (ScoreHolder holder : scoreboard.getTrackedPlayers()) {
            var info = scoreboard.getPlayerScoreInfo(holder, objective);
            if (info == null) continue;
            PlayerTeam team = scoreboard.getPlayersTeam(holder.getScoreboardName());
            String text = team == null ? holder.getScoreboardName()
                    : team.getPlayerPrefix().getString() + holder.getScoreboardName() + team.getPlayerSuffix().getString();
            text = clean(text);
            if (!text.isBlank()) scored.add(new Object[]{info.value(), text});
        }
        scored.sort((a, b) -> Integer.compare((int) b[0], (int) a[0]));
        for (Object[] s : scored) lines.add((String) s[1]);
        return lines;
    }

    private static List<String> tabLines(Minecraft mc) {
        List<String> lines = new ArrayList<>();
        if (mc.getConnection() == null) return lines;
        for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
            Component name = info.getTabListDisplayName();
            if (name == null) continue;
            String line = clean(name.getString());
            if (!line.isBlank()) lines.add(line);
        }
        java.util.Collections.sort(lines);
        return lines;
    }

    static String clean(String raw) {
        // Hypixel pads scoreboard lines with emoji/colour tricks; strip formatting and odd spacing.
        return ScreenUtil.cleanName(raw).replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
    }

    private static float wrap(float degrees) {
        degrees %= 360;
        if (degrees >= 180) degrees -= 360;
        if (degrees < -180) degrees += 360;
        return degrees;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
