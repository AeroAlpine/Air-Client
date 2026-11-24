package com.coflnet.pathing.astar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A level made from one of the player's Interaction Logger recordings ("level 8" -> file
 * {@code logs/interactions/<time>_level_8.json}, the newest one wins). The recorded map (every
 * block next to air around the path) is placed 1:1 at its original coordinates in the singleplayer
 * world, the recorded SkyBlock mobs can be spawned back where they were first seen, and the path
 * gives reachable random goals.
 *
 * <p>Recordings made before the logger stored block states only have block names: stairs and
 * slabs then come out in their default state (stairs facing north, bottom slabs).
 */
public final class RecordedLevel {
    public static final String MOB_TAG = "airtest_mob";
    /** Send to clients, keep the shapes (no stair/fence reconnect, no falling vines), no onPlace (water stays). */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;
    /** "Speed: 395" in the tab list (a SkyBlock icon glyph may sit before the number; not "Attack Speed"). */
    private static final Pattern SPEED = Pattern.compile("(?<!Attack )Speed: \\D{0,3}(\\d+)");

    public record Point(double t, double x, double y, double z) {
        public Vec3 vec() {
            return new Vec3(x, y, z);
        }
    }

    public record Mob(String name, double x, double y, double z) {
    }

    private static final Map<Integer, RecordedLevel> CACHE = new HashMap<>();

    public final int level;
    public final Path file;
    public final long modified;
    public final Vec3 start;
    /** Where the player looked at the start of the recording. */
    public float startYaw, startPitch;
    /** Where the recording ended (levels 10+: the goal). */
    public final Vec3 end;
    /** Fill the ground under the map (SkyBlock maps 8/9; not parkour levels, their gaps are the point). */
    public boolean fillGround = true;
    public final double durationSeconds;
    /** Path points where the player stood on the ground. */
    public final List<Point> ground;
    public final long[] positions;
    public final BlockState[] states;
    /** Palette entries that did not parse (skipped). */
    public final int unknownBlocks;
    /** Whether the palette had full states or only names (stairs/slabs then default). */
    public final boolean exactStates;
    public final int minX, minY, minZ, maxX, maxY, maxZ;
    /** SkyBlock speed from the tab list (100 = vanilla walking), 100 if not recorded. */
    public final int skyblockSpeed;
    private final List<Mob> mobs;
    private final Map<Long, BlockState> byPos = new HashMap<>();

    private RecordedLevel(int level, Path file, long modified, JsonObject root) {
        this.level = level;
        this.file = file;
        this.modified = modified;
        this.durationSeconds = root.has("durationSeconds") ? root.get("durationSeconds").getAsDouble() : 0;

        JsonArray path = root.getAsJsonArray("path");
        List<Point> onGround = new ArrayList<>();
        Vec3 first = null;
        for (JsonElement e : path) {
            JsonArray p = e.getAsJsonArray();
            Point point = new Point(p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble(),
                    p.get(3).getAsDouble());
            if (first == null) {
                first = point.vec();
                if (p.size() > 5) {
                    startYaw = p.get(4).getAsFloat();
                    startPitch = p.get(5).getAsFloat();
                }
            }
            if (p.size() > 6 && p.get(6).getAsInt() == 1) onGround.add(point);
        }
        this.start = first == null ? Vec3.ZERO : first;
        JsonArray lastP = path.size() == 0 ? null : path.get(path.size() - 1).getAsJsonArray();
        this.end = lastP == null ? this.start
                : new Vec3(lastP.get(1).getAsDouble(), lastP.get(2).getAsDouble(), lastP.get(3).getAsDouble());
        this.ground = List.copyOf(onGround);

        JsonObject map = root.getAsJsonObject("map");
        JsonArray names = map.getAsJsonArray("palette");
        JsonArray full = map.has("states") ? map.getAsJsonArray("states") : null;
        this.exactStates = full != null && full.size() == names.size();
        BlockState[] palette = new BlockState[names.size()];
        int unknown = 0;
        for (int i = 0; i < palette.length; i++) {
            String id = exactStates ? full.get(i).getAsString() : names.get(i).getAsString();
            palette[i] = parse(id);
            if (palette[i] == null && exactStates) palette[i] = parse(names.get(i).getAsString());
        }
        JsonArray blocks = map.getAsJsonArray("blocks");
        List<Long> pos = new ArrayList<>(blocks.size());
        List<BlockState> st = new ArrayList<>(blocks.size());
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (JsonElement e : blocks) {
            JsonArray b = e.getAsJsonArray();
            int x = b.get(0).getAsInt(), y = b.get(1).getAsInt(), z = b.get(2).getAsInt();
            BlockState state = palette[b.get(3).getAsInt()];
            if (state == null) {
                unknown++;
                continue;
            }
            long packed = BlockPos.asLong(x, y, z);
            pos.add(packed);
            st.add(state);
            byPos.put(packed, state);
            x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
            x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
        }
        this.positions = pos.stream().mapToLong(Long::longValue).toArray();
        this.states = st.toArray(BlockState[]::new);
        this.unknownBlocks = unknown;
        this.minX = x0; this.minY = y0; this.minZ = z0;
        this.maxX = x1; this.maxY = y1; this.maxZ = z1;

        List<Mob> found = new ArrayList<>();
        if (root.has("entities")) {
            for (JsonElement e : root.getAsJsonArray("entities")) {
                JsonObject o = e.getAsJsonObject();
                String name = o.get("name").getAsString();
                if (!name.startsWith("[Lv")) continue;
                JsonArray p = o.getAsJsonArray("pos");
                found.add(new Mob(name, p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble()));
            }
        }
        this.mobs = List.copyOf(found);

        int speed = 100;
        for (JsonElement e : root.getAsJsonArray("events")) {
            Matcher m = SPEED.matcher(e.toString());
            if (m.find()) {
                speed = Integer.parseInt(m.group(1));
                break;
            }
        }
        this.skyblockSpeed = speed;
    }

    /** The newest recording named "level N", or null if there is none. Parsed once per file version. */
    public static synchronized RecordedLevel load(int level) {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("logs/interactions");
        Path newest = null;
        String suffix = "_level_" + level + ".json";
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                newest = files.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                        .max((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                        .orElse(null);
            } catch (IOException ignored) {
            }
        }
        if (newest == null) return null;
        try {
            long modified = Files.getLastModifiedTime(newest).toMillis();
            RecordedLevel cached = CACHE.get(level);
            if (cached != null && cached.file.equals(newest) && cached.modified == modified) return cached;
            try (Reader in = Files.newBufferedReader(newest, StandardCharsets.UTF_8)) {
                RecordedLevel loaded = new RecordedLevel(level, newest, modified, JsonParser.parseReader(in).getAsJsonObject());
                CACHE.put(level, loaded);
                return loaded;
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[AStarTest] cannot read " + newest + ": " + e);
            return null;
        }
    }

    private static BlockState parse(String id) {
        try {
            BlockState state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, id, false).blockState();
            // Leaves far from logs would decay in the rebuilt world.
            if (state.hasProperty(BlockStateProperties.PERSISTENT)) {
                state = state.setValue(BlockStateProperties.PERSISTENT, true);
            }
            return state;
        } catch (Exception e) {
            return null;
        }
    }

    /** Mobs whose name contains {@code part} (e.g. "Wolf"), one per animal (old recordings list moving mobs twice). */
    public List<Mob> mobs(String part) {
        List<Mob> kept = new ArrayList<>();
        for (Mob m : mobs) {
            if (!m.name().contains(part)) continue;
            String kind = kind(m.name());
            boolean twice = false;
            for (Mob k : kept) {
                double dx = k.x() - m.x(), dy = k.y() - m.y(), dz = k.z() - m.z();
                if (kind(k.name()).equals(kind) && dx * dx + dy * dy + dz * dz < 9.0) {
                    twice = true;
                    break;
                }
            }
            if (!twice) kept.add(m);
        }
        return kept;
    }

    /** "[Lv67] Old Wolf 10,549/15,000❤" -> "Old Wolf". */
    private static String kind(String name) {
        String s = name.replaceFirst("^\\[Lv\\d+]\\s*", "").replaceAll("[0-9.,/❤]+[kKmM]?", "");
        return s.replaceAll("[^A-Za-z ]", "").trim();
    }

    /**
     * A random spot the player really stood on, at least {@code minDistance} blocks from the start;
     * {@code humanSeconds} of the result is when the player first got there.
     */
    public Point randomGoal(long seed, double minDistance) {
        if (ground.isEmpty()) return null;
        Random rnd = new Random(seed * 7_919L + level * 104_729L);
        List<Point> far = new ArrayList<>();
        for (Point p : ground) {
            double dx = p.x() - start.x, dz = p.z() - start.z;
            if (dx * dx + dz * dz >= minDistance * minDistance) far.add(p);
        }
        List<Point> pool = far.isEmpty() ? ground : far;
        Point pick = pool.get(rnd.nextInt(pool.size()));
        for (Point p : ground) {
            double dx = p.x() - pick.x(), dy = p.y() - pick.y(), dz = p.z() - pick.z();
            if (dx * dx + dy * dy + dz * dz < 2.25) return new Point(p.t(), pick.x(), pick.y(), pick.z());
        }
        return pick;
    }

    /**
     * The block the player landed on at the end: the recording stops in the fall, so the end point
     * is in the air. Straight down from it, the first recorded solid block; the feet stand on top
     * (in the block itself for a bottom slab). Levels 10/11: the top of a log pillar.
     */
    public BlockPos landingSpot() {
        int bx = (int) Math.floor(end.x), bz = (int) Math.floor(end.z);
        for (int y = (int) Math.floor(end.y); y >= minY - 1; y--) {
            BlockState s = byPos.get(BlockPos.asLong(bx, y, bz));
            if (s == null || !s.blocksMotion()) continue;
            boolean bottomSlab = s.hasProperty(BlockStateProperties.SLAB_TYPE)
                    && s.getValue(BlockStateProperties.SLAB_TYPE) == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM;
            return new BlockPos(bx, bottomSlab ? y : y + 1, bz);
        }
        return BlockPos.containing(end);
    }

    /** Speed effect amplifier that matches the recorded SkyBlock speed (-1 = none). */
    public int speedAmplifier() {
        // Speed effect: +20 % per level; SkyBlock speed 100 = vanilla walking.
        int levels = (int) Math.round((skyblockSpeed - 100) / 20.0);
        return Math.min(levels, 255) - 1;
    }

    /** Server thread: place every recorded block (and, once per game session, the ground under them). */
    public void build(ServerLevel world) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int i = 0; i < positions.length; i++) {
            at.set(positions[i]);
            if (world.getBlockState(at) != states[i]) world.setBlock(at, states[i], FLAGS);
        }
        if (!groundDone && fillGround) {
            int filled = fillGround(world);
            groundDone = true;
            System.out.println("[AStarTest] level " + level + ": " + filled + " ground blocks filled under the map");
        }
    }

    private boolean groundDone;

    /**
     * The recording only has blocks next to air within a few blocks of the path: below cliffs and at
     * the edge of the scanned area the rebuilt map was open to the void, and the bot fell through.
     * Every column gets solid ground: under its lowest recorded ground block, and in columns with
     * none (holes) one block below the lowest ground of the columns around. Leaves, plants and the
     * like do not count as ground (no stone pillars under tree tops).
     */
    private int fillGround(ServerLevel world) {
        int w = maxX - minX + 1, d = maxZ - minZ + 1;
        if (w <= 0 || d <= 0 || (long) w * d > 4_000_000L) return 0;
        int[] floor = new int[w * d];
        java.util.Arrays.fill(floor, Integer.MAX_VALUE);
        for (int i = 0; i < positions.length; i++) {
            BlockState s = states[i];
            if (!s.isCollisionShapeFullBlock(world, BlockPos.ZERO) && !s.blocksMotion()) continue;
            if (s.is(net.minecraft.tags.BlockTags.LEAVES)) continue;
            int x = BlockPos.getX(positions[i]) - minX, z = BlockPos.getZ(positions[i]) - minZ;
            int idx = x * d + z;
            floor[idx] = Math.min(floor[idx], BlockPos.getY(positions[i]));
        }
        int[] fill = floor.clone();
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                if (floor[x * d + z] != Integer.MAX_VALUE) continue;
                int best = Integer.MAX_VALUE;
                for (int r = 1; r <= 6 && best == Integer.MAX_VALUE; r++) {
                    for (int ox = -r; ox <= r; ox++) {
                        for (int oz = -r; oz <= r; oz++) {
                            int nx = x + ox, nz = z + oz;
                            if (nx < 0 || nz < 0 || nx >= w || nz >= d) continue;
                            best = Math.min(best, floor[nx * d + nz]);
                        }
                    }
                }
                fill[x * d + z] = best == Integer.MAX_VALUE ? Integer.MAX_VALUE : best; // top = one below that ground
            }
        }
        BlockState stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int bottom = minY - 3, n = 0;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                int top = fill[x * d + z];
                if (top == Integer.MAX_VALUE) continue;
                for (int y = top - 1; y >= bottom; y--) {
                    at.set(minX + x, y, minZ + z);
                    if (byPos.containsKey(at.asLong())) continue; // recorded (caves below the floor)
                    if (!world.getBlockState(at).isAir()) continue;
                    world.setBlock(at, stone, FLAGS);
                    n++;
                }
            }
        }
        return n;
    }

    /** Server thread: remove earlier test mobs, then spawn the recorded ones standing on the floor below them. */
    public int spawnMobs(MinecraftServer server, ServerLevel world, String part, String id) {
        removeMobs(world);
        int spawned = 0;
        for (Mob m : mobs(part)) {
            double y = floorBelow(m.x(), m.y(), m.z());
            String cmd = String.format(Locale.ROOT,
                    "summon %s %.2f %.2f %.2f {Tags:[\"%s\"],PersistenceRequired:1b}", id, m.x(), y, m.z(), MOB_TAG);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
            spawned++;
        }
        return spawned;
    }

    /** Server thread. */
    public static void removeMobs(ServerLevel world) {
        List<Entity> old = new ArrayList<>();
        for (Entity e : world.getAllEntities()) {
            if (e.entityTags().contains(MOB_TAG)) old.add(e);
        }
        old.forEach(Entity::discard);
    }

    /** Server thread: test mobs still alive; one that fell out of the map (below {@code minY}) is removed. */
    public static int aliveMobs(ServerLevel world, double minY) {
        int n = 0;
        List<Entity> gone = new ArrayList<>();
        for (Entity e : world.getAllEntities()) {
            if (!e.isAlive() || !e.entityTags().contains(MOB_TAG)) continue;
            if (e.getY() < minY) gone.add(e);
            else n++;
        }
        gone.forEach(Entity::discard);
        return n;
    }

    /** Server thread: the living test mob nearest to {@code from}, or null. */
    public static Vec3 nearestMob(ServerLevel world, Vec3 from) {
        Vec3 best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : world.getAllEntities()) {
            if (!e.isAlive() || !e.entityTags().contains(MOB_TAG)) continue;
            double d = e.position().distanceToSqr(from);
            if (d < bestD) {
                bestD = d;
                best = e.position();
            }
        }
        return best;
    }

    /** Top of the first recorded solid block at or below a nametag (the stand floats over the mob). */
    private double floorBelow(double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int by = (int) Math.floor(y); by >= (int) Math.floor(y) - 6; by--) {
            BlockState s = byPos.get(BlockPos.asLong(bx, by, bz));
            if (s != null && s.blocksMotion()) return by + 1.0;
        }
        return y;
    }

    public String describe() {
        return "level " + level + " (" + file.getFileName() + "): " + positions.length + " blocks"
                + (exactStates ? "" : ", no block states recorded (stairs/slabs default)")
                + (unknownBlocks > 0 ? ", " + unknownBlocks + " unknown skipped" : "")
                + ", speed " + skyblockSpeed;
    }
}
