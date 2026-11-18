package com.coflnet.mining;

import com.coflnet.flipper.trading.ScreenUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import pingplus.voicechat.client.PlayerSettings;
import pingplus.voicechat.client.gui.glass.GlassStyle;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Dwarven Mines helpers: commission blocks and mobs, titanium alert, Dark Monolith finder,
 * goblin and portal alerts, mining stats and the king / cult timers. Everything runs on the
 * client thread from one tick handler and only while on a mining island.
 */
public final class MiningFeatures {
    public static final List<String> KINGS = List.of("Brammor", "Emkam", "Redros", "Erren", "Thormyr", "Emmor", "Grandan");
    private static final int[] CULT_DAYS = {7, 14, 21, 28};
    private static final int COMMISSION_COLOR = 0xFFFFD84A;
    private static final int GOBLIN_COLOR = 0xFFFFB020;
    private static final int DIAMOND_GOBLIN_COLOR = 0xFF5CE6E0;

    private static final Pattern COMMISSION_DONE = Pattern.compile("^(.+?) Commission Complete!");
    private static final Pattern GOBLIN = Pattern.compile("^A (Golden|Diamond) Goblin has spawned!");
    private static final Pattern MONOLITH = Pattern.compile("^MONOLITH! You found a mysterious Dark Monolith");

    public record Highlight(BlockPos pos, int color) {
    }

    /** Mining stats of this visit to a mining island. */
    public static final class Stats {
        public long startedAt = System.currentTimeMillis();
        public int blocks;
        public final EnumMap<MiningBlock, int[]> perBlock = new EnumMap<>(MiningBlock.class); // [count, totalMs]
        public final Map<String, Long> powderStart = new HashMap<>();
        public final Map<String, Long> powderGained = new HashMap<>();
        public final Map<String, Long> powderLast = new HashMap<>();

        public double minutes() {
            return Math.max(1e-6, (System.currentTimeMillis() - startedAt) / 60_000.0);
        }

        public long avgMs(MiningBlock block) {
            int[] v = perBlock.get(block);
            return v == null || v[0] == 0 ? -1 : v[1] / v[0];
        }
    }

    private static volatile List<Highlight> highlights = List.of();
    private static volatile Map<Integer, Integer> outlines = Map.of();
    private static volatile BlockPos monolith;
    private static final Map<BlockPos, Long> titanium = new HashMap<>();
    private static final Set<BlockPos> knownTitanium = new HashSet<>();
    private static boolean titaniumSeeded;
    private static BlockPos lastMonolithAlert;
    private static final Map<BlockPos, Object[]> mining = new HashMap<>();
    private static Stats stats = new Stats();
    private static Object level;
    private static boolean wasOnIsland;
    private static int ticks;
    private static boolean initialized;

    private MiningFeatures() {
    }

    public static void init() {
        if (initialized) return;
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(MiningFeatures::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) onChat(ScreenUtil.cleanName(message.getString()));
            else MiningState.onActionBar(ScreenUtil.cleanName(message.getString()));
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("voicechat", "mining_block_info"),
                (graphics, delta) -> MiningBlockInfo.render(graphics));
    }

    // ------------------------------------------------------------------ public reading

    public static List<Highlight> highlights() {
        return highlights;
    }

    public static BlockPos monolith() {
        return PlayerSettings.miningAlerts && PlayerSettings.miningMonolith ? monolith : null;
    }

    /** Outline colour for an entity (commission mob / goblin), or -1. */
    public static int outlineColor(Entity entity) {
        Integer c = outlines.get(entity.getId());
        return c == null ? -1 : c & 0xFFFFFF;
    }

    public static Stats stats() {
        return stats;
    }

    public static void resetStats() {
        stats = new Stats();
    }

    public static boolean active() {
        return MiningState.inMiningIsland();
    }

    // ------------------------------------------------------------------ tick

    private static void tick(Minecraft mc) {
        MiningState.tick(mc);
        ticks++;
        boolean onIsland = MiningState.inMiningIsland() && mc.player != null && mc.level != null;
        if (mc.level != level) {
            level = mc.level;
            clearWorld();
        }
        if (!onIsland) {
            if (wasOnIsland) clearWorld();
            wasOnIsland = false;
            return;
        }
        if (!wasOnIsland) {
            wasOnIsland = true;
            resetStats();
        }
        try {
            trackMining(mc);
            if (ticks % 5 == 0) scanTitanium(mc);
            if (ticks % 10 == 0) updateOutlines(mc);
            if (ticks % 20 == 0) scanCommissionBlocks(mc);
            if (ticks % 40 == 0) scanMonolith(mc);
            if (ticks % 20 == 0) trackPowder();
        } catch (RuntimeException e) {
            System.out.println("[Mining] tick error: " + e);
        }
    }

    private static void clearWorld() {
        highlights = List.of();
        outlines = Map.of();
        monolith = null;
        titanium.clear();
        knownTitanium.clear();
        titaniumSeeded = false;
        mining.clear();
    }

    // ------------------------------------------------------------------ mining stats

    /** Called from the game-mode mixin when the player starts breaking a block. */
    public static void onStartDestroy(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || pos == null || !MiningState.inMiningIsland()) return;
        BlockPos key = pos.immutable();
        if (mining.containsKey(key)) return;
        MiningBlock kind = MiningBlock.of(mc.level.getBlockState(key));
        if (kind == null) return;
        if (mining.size() > 32) mining.clear();
        mining.put(key, new Object[]{kind, System.currentTimeMillis()});
    }

    private static void trackMining(Minecraft mc) {
        if (mining.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<BlockPos, Object[]>> it = mining.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            MiningBlock kind = (MiningBlock) e.getValue()[0];
            long since = (long) e.getValue()[1];
            if (MiningBlock.of(mc.level.getBlockState(e.getKey())) != kind) {
                // Broken: Hypixel turns it into bedrock or air until it regenerates.
                stats.blocks++;
                int[] v = stats.perBlock.computeIfAbsent(kind, k -> new int[2]);
                v[0]++;
                v[1] += (int) Math.min(60_000, now - since);
                it.remove();
            } else if (now - since > 20_000) {
                it.remove();
            }
        }
    }

    private static void trackPowder() {
        for (Map.Entry<String, Long> p : MiningState.powder().entrySet()) {
            String type = p.getKey();
            long value = p.getValue();
            stats.powderStart.putIfAbsent(type, value);
            Long last = stats.powderLast.put(type, value);
            if (last != null && value > last) {
                stats.powderGained.merge(type, value - last, Long::sum);
            }
        }
    }

    // ------------------------------------------------------------------ titanium

    private static void scanTitanium(Minecraft mc) {
        if (!PlayerSettings.miningOres || !PlayerSettings.miningTitaniumAlert || !MiningState.inDwarvenMines()) {
            titanium.clear();
            return;
        }
        Level level = mc.level;
        BlockPos center = mc.player.blockPosition();
        int r = 6;
        Set<BlockPos> seen = new HashSet<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -3; dy <= 4; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    at.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (level.getBlockState(at).is(Blocks.POLISHED_DIORITE)) seen.add(at.immutable());
                }
            }
        }
        long now = System.currentTimeMillis();
        boolean alerted = false;
        for (BlockPos pos : seen) {
            if (knownTitanium.add(pos) && titaniumSeeded && exposed(level, pos)) {
                titanium.put(pos, now);
                if (!alerted) {
                    alerted = true;
                    alert("Titanium!", "Spawned " + Math.round(Math.sqrt(pos.distSqr(center))) + " blocks away",
                            0xFFE8E8F0);
                }
            }
        }
        titaniumSeeded = true;
        // Forget titanium that was mined or is out of range, so a respawn there alerts again.
        knownTitanium.removeIf(pos -> pos.distSqr(center) <= (r - 1) * (r - 1) && !seen.contains(pos));
        titanium.entrySet().removeIf(e -> !seen.contains(e.getKey()) && e.getKey().distSqr(center) <= r * r
                || now - e.getValue() > 60_000);
    }

    // ------------------------------------------------------------------ commission blocks

    private static void scanCommissionBlocks(Minecraft mc) {
        List<Highlight> found = new ArrayList<>();
        Level level = mc.level;
        BlockPos center = mc.player.blockPosition();
        if (PlayerSettings.miningOres && PlayerSettings.miningTitaniumBox) {
            for (BlockPos pos : titanium.keySet()) found.add(new Highlight(pos, 0xFFFFFFFF));
        }
        List<Commission> wanted = new ArrayList<>();
        if (PlayerSettings.miningCommissions && PlayerSettings.miningCommissionBlocks) {
            String here = MiningState.subArea();
            for (Commission c : MiningState.commissions()) {
                if (c.done() || (c.goal() != Commission.Goal.MITHRIL && c.goal() != Commission.Goal.TITANIUM
                        && c.goal() != Commission.Goal.GEMSTONE)) continue;
                // Area commissions only count in their area.
                if (!c.area().isEmpty() && !here.contains(c.area().toLowerCase(java.util.Locale.ROOT))) continue;
                wanted.add(c);
            }
        }
        if (!wanted.isEmpty()) {
            int r = Math.clamp(PlayerSettings.miningBlockRadius, 3, 20);
            List<Object[]> hits = new ArrayList<>();
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r / 2 - 2; dy <= r / 2 + 3; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        at.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        BlockState state = level.getBlockState(at);
                        if (state.isAir()) continue;
                        MiningBlock kind = MiningBlock.of(state);
                        if (kind == null) continue;
                        boolean counts = false;
                        for (Commission c : wanted) {
                            if (c.wants(kind)) {
                                counts = true;
                                break;
                            }
                        }
                        if (!counts || !exposed(level, at)) continue;
                        hits.add(new Object[]{at.immutable(), kind, at.distSqr(center)});
                    }
                }
            }
            hits.sort((a, b) -> Double.compare((double) a[2], (double) b[2]));
            for (int i = 0; i < hits.size() && i < 160; i++) {
                MiningBlock kind = (MiningBlock) hits.get(i)[1];
                found.add(new Highlight((BlockPos) hits.get(i)[0], kind == MiningBlock.TITANIUM ? 0xFFFFFFFF : kind.color));
            }
        }
        highlights = List.copyOf(found);
    }

    private static boolean exposed(Level level, BlockPos pos) {
        BlockPos.MutableBlockPos near = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            near.setWithOffset(pos, d);
            BlockState s = level.getBlockState(near);
            if (s.isAir() || !s.canOcclude()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ monolith

    private static void scanMonolith(Minecraft mc) {
        if (!PlayerSettings.miningAlerts || !PlayerSettings.miningMonolith || !MiningState.inDwarvenMines()) {
            monolith = null;
            return;
        }
        Level level = mc.level;
        BlockPos center = mc.player.blockPosition();
        int cx = center.getX() >> 4, cz = center.getZ() >> 4;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int x = cx - 4; x <= cx + 4; x++) {
            for (int z = cz - 4; z <= cz + 4; z++) {
                if (!level.hasChunk(x, z)) continue;
                LevelChunk chunk = level.getChunk(x, z);
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> s.is(Blocks.DRAGON_EGG))) continue;
                    int baseY = (chunk.getMinSectionY() + i) << 4;
                    for (int lx = 0; lx < 16; lx++) {
                        for (int ly = 0; ly < 16; ly++) {
                            for (int lz = 0; lz < 16; lz++) {
                                if (!section.getBlockState(lx, ly, lz).is(Blocks.DRAGON_EGG)) continue;
                                BlockPos pos = new BlockPos((x << 4) + lx, baseY + ly, (z << 4) + lz);
                                double d = pos.distSqr(center);
                                if (d < bestDist) {
                                    bestDist = d;
                                    best = pos;
                                }
                            }
                        }
                    }
                }
            }
        }
        monolith = best;
        if (best != null && !best.equals(lastMonolithAlert)) {
            lastMonolithAlert = best;
            alert("Dark Monolith", Math.round(Math.sqrt(bestDist)) + " blocks away", 0xFFB061E8);
        }
    }

    // ------------------------------------------------------------------ mobs

    private static void updateOutlines(Minecraft mc) {
        boolean commissionMobs = PlayerSettings.miningCommissions && PlayerSettings.miningCommissionMobs;
        boolean goblins = PlayerSettings.miningAlerts && PlayerSettings.miningGoblinOutline;
        if (!commissionMobs && !goblins) {
            outlines = Map.of();
            return;
        }
        List<String> names = new ArrayList<>();
        if (commissionMobs) {
            for (Commission c : MiningState.commissions()) {
                if (!c.done() && c.goal() == Commission.Goal.MOBS) names.addAll(c.mobs());
            }
        }
        List<Entity> all = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.distanceToSqr(mc.player) < 48 * 48) all.add(e);
        }
        Map<Integer, Integer> result = new HashMap<>();
        for (Entity e : all) {
            if (!(e instanceof ArmorStand stand) || stand.getCustomName() == null) continue;
            String tag = ScreenUtil.cleanName(stand.getCustomName().getString());
            int color = -1;
            if (goblins && tag.contains("Golden Goblin")) color = GOBLIN_COLOR;
            else if (goblins && tag.contains("Diamond Goblin")) color = DIAMOND_GOBLIN_COLOR;
            else {
                for (String n : names) {
                    if (tag.contains(n)) {
                        color = COMMISSION_COLOR;
                        break;
                    }
                }
            }
            if (color == -1) continue;
            LivingEntity mob = mobUnder(stand, all, mc);
            if (mob != null) result.put(mob.getId(), color);
        }
        outlines = result;
    }

    /** Hypixel mobs carry their name on an armor stand just above them. */
    private static LivingEntity mobUnder(ArmorStand stand, List<Entity> all, Minecraft mc) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : all) {
            if (!(e instanceof LivingEntity living) || e instanceof ArmorStand || e == mc.player || !living.isAlive()) continue;
            double dx = e.getX() - stand.getX(), dz = e.getZ() - stand.getZ();
            if (Math.abs(dx) > 1.2 || Math.abs(dz) > 1.2) continue;
            double dy = stand.getY() - e.getY();
            if (dy < -0.5 || dy > 4) continue;
            double d = dx * dx + dz * dz + dy * dy * 0.1;
            if (d < bestDist) {
                bestDist = d;
                best = living;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ chat alerts

    private static void onChat(String text) {
        if (text.isEmpty() || !MiningState.inMiningIsland()) return;
        Matcher done = COMMISSION_DONE.matcher(text);
        if (done.find()) {
            if (PlayerSettings.miningCommissions && PlayerSettings.miningCommissionAlert) {
                alert("Commission done", titleCase(done.group(1)), 0xFFA4F0C1);
            }
            return;
        }
        if (!PlayerSettings.miningAlerts) return;
        Matcher goblin = GOBLIN.matcher(text);
        if (goblin.find() && PlayerSettings.miningGoblinAlert) {
            boolean diamond = goblin.group(1).equals("Diamond");
            alert(goblin.group(1) + " Goblin!", "Spawned near you", diamond ? DIAMOND_GOBLIN_COLOR : GOBLIN_COLOR);
        } else if (MONOLITH.matcher(text).find()) {
            monolith = null;
        } else if (PlayerSettings.miningRareAlerts && text.contains("You found a Glacite Mineshaft portal")) {
            alert("Mineshaft!", "A portal spawned", 0xFF5AB8F0);
        } else if (PlayerSettings.miningRareAlerts && text.contains("You found a Suspicious Scrap")) {
            alert("Suspicious Scrap", "", 0xFF5577FF);
        }
    }

    private static String titleCase(String upper) {
        StringBuilder sb = new StringBuilder();
        for (String word : upper.toLowerCase(java.util.Locale.ROOT).split(" ")) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    public static void alert(String title, String subtitle, int color) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.gui.hud.setTimes(4, 40, 12);
        mc.gui.hud.setTitle(Component.literal(title).withColor(color & 0xFFFFFF));
        mc.gui.hud.setSubtitle(Component.literal(subtitle).withColor(GlassStyle.MUTED & 0xFFFFFF));
        if (PlayerSettings.miningAlertSound) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.6f));
        }
    }

    // ------------------------------------------------------------------ timers

    /** Current king of the Royal Palace and seconds until the next one. */
    public static String[] king() {
        SkyBlockClock now = SkyBlockClock.now();
        int index = (now.day() + 5) % KINGS.size();
        long nextDay = now.startOfToday() + SkyBlockClock.DAY;
        long left = nextDay - System.currentTimeMillis() / 1000;
        SkyBlockClock tomorrow = SkyBlockClock.at(nextDay + 1);
        String next = KINGS.get((tomorrow.day() + 5) % KINGS.size());
        return new String[]{KINGS.get(index), next, SkyBlockClock.format(left)};
    }

    /** Fallen Star Cult: meetings on days 7, 14, 21 and 28 from 00:00 to 06:00. [active?, time text]. */
    public static Object[] cult() {
        SkyBlockClock now = SkyBlockClock.now();
        long unix = System.currentTimeMillis() / 1000;
        for (int day : CULT_DAYS) {
            if (day == now.day() && now.hour() < 6) {
                long end = SkyBlockClock.startOf(now.year(), now.month(), day, 6);
                return new Object[]{true, SkyBlockClock.format(end - unix)};
            }
        }
        for (int day : CULT_DAYS) {
            if (day > now.day()) {
                return new Object[]{false, SkyBlockClock.format(SkyBlockClock.startOf(now.year(), now.month(), day, 0) - unix)};
            }
        }
        int month = now.month() == 12 ? 1 : now.month() + 1;
        int year = now.month() == 12 ? now.year() + 1 : now.year();
        return new Object[]{false, SkyBlockClock.format(SkyBlockClock.startOf(year, month, CULT_DAYS[0], 0) - unix)};
    }

    /** The block under the crosshair, for the block info HUD. */
    static MiningBlock targetBlock(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (mc.level == null || !(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) return null;
        return MiningBlock.of(mc.level.getBlockState(bh.getBlockPos()));
    }
}
