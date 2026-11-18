package com.coflnet.mining.bot;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import com.coflnet.mining.MiningBlock;
import com.coflnet.pathing.AotvAssist;
import com.coflnet.pathing.WarpPlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import pingplus.voicechat.client.AimSmoothing;
import pingplus.voicechat.client.etherwarp.EtherwarpHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Mines like the recordings: sneaking all the time, snapping the crosshair to a random spot on
 * the next block (not its centre), breaking it, a short pause, the next block. Out of reach:
 * a few sneaking steps on flat ground, a teleport down, an etherwarp up - jumping is avoided.
 * Recorded numbers this is tuned to: reach ~2.7 (max 4.7), ~300 ms between two blocks,
 * the next block mostly right next to the last one.
 */
final class DwarvenMiner {
    private enum State { SELECT, AIM, BREAK, PAUSE, WALK, BARITONE, WARP }

    private static final double REACH = 4.4;
    /**
     * Mining distance like a player: go close first. Blocks at or below eye height only within
     * this many blocks (flat), blocks above the head may be a bit further away.
     */
    private static final double FLAT_NEAR = 2.5;
    private static final double FLAT_ABOVE = 3.5;
    private static final int SCAN = 12;
    private static final long BREAK_TIMEOUT_MS = 14_000L;

    private Predicate<MiningBlock> wanted = b -> false;
    private Predicate<MiningBlock> priority = b -> false;
    private State state = State.SELECT;
    private BlockPos target;
    private Direction face = Direction.UP;
    private Vec3 aimPoint;
    private long stateAt;
    private long pauseUntil;
    private int offTargetTicks;
    private final Map<BlockPos, Long> skip = new HashMap<>();
    private BlockPos standPos;
    private boolean sneakOwned;
    private boolean forwardOwned;
    private long exhaustedSince;
    private long lastFrame;
    private float turnSpeed = 900f;
    private int broken;
    private long expectedMs = 2_000L;
    private boolean hasPriority;
    private boolean anyTarget;
    private BlockState anyTargetState;
    private final Map<BlockPos, Integer> prioritySeen = new HashMap<>();
    private long lastHuntAt;
    private int brokenHere;
    private int roamAfter = 10;
    private final Map<BlockPos, Integer> huntTries = new HashMap<>();
    private int retries;
    private BlockPos retryTarget;
    private net.minecraft.client.KeyMapping jiggleKey;
    private long jiggleUntil;

    void start(Predicate<MiningBlock> wanted, Predicate<MiningBlock> priority) {
        this.wanted = wanted;
        this.priority = priority;
        this.hasPriority = priority.test(MiningBlock.TITANIUM);
        prioritySeen.clear();
        huntTries.clear();
        brokenHere = 0;
        roamAfter = 6 + ThreadLocalRandom.current().nextInt(9);
        state = State.SELECT;
        target = null;
        exhaustedSince = 0;
        skip.clear();
        broken = 0;
    }

    boolean breaking() {
        return state == State.BREAK || state == State.AIM;
    }

    int broken() {
        return broken;
    }

    /** Nothing to mine around here for this long (0 = there is something). */
    long exhaustedMs() {
        return exhaustedSince == 0 ? 0 : System.currentTimeMillis() - exhaustedSince;
    }

    String status() {
        return switch (state) {
            case SELECT -> exhaustedSince > 0 ? "Looking for ore" : "Mining";
            case AIM, BREAK, PAUSE -> "Mining" + (target == null ? "" : " " + label(target));
            case WALK -> "Stepping to ore";
            case BARITONE -> "Walking to ore";
            case WARP -> "Warping to ore";
        };
    }

    private String label(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        MiningBlock b = mc.level == null ? null : MiningBlock.of(mc.level.getBlockState(pos));
        return b == null ? "" : b.label;
    }

    void stop() {
        Minecraft mc = Minecraft.getInstance();
        if (jiggleKey != null) {
            jiggleKey.setDown(false);
            jiggleKey = null;
        }
        stopBreaking(mc);
        setSneak(mc, false);
        setForward(mc, false);
        if (state == State.BARITONE) RouteWalker.stopBaritone();
        state = State.SELECT;
        target = null;
        aimPoint = null;
    }

    // ------------------------------------------------------------------ tick

    void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        long now = System.currentTimeMillis();
        skip.values().removeIf(until -> until < now);
        if (AotvAssist.isBusy()) {
            stopBreaking(mc);
            return;
        }
        if (state == State.WARP) {
            if (now - stateAt > 400) next(State.SELECT);
            return;
        }
        if (state != State.BARITONE) {
            setSneak(mc, true);
            selectPickaxe(player);
        }
        switch (state) {
            case SELECT -> select(mc, player, now);
            case AIM -> {
                if (!stillWanted(mc, target)) {
                    next(State.SELECT);
                } else if (onTarget(mc) && aimError(player) < 25f) {
                    // the crosshair is on the block: start breaking, the camera keeps easing in
                    expectedMs = expectedBreakMs(MiningBlock.of(mc.level.getBlockState(target)));
                    next(State.BREAK);
                } else if (now - stateAt > 1_200L) {
                    skip.put(target, now + 15_000L);
                    next(State.SELECT);
                }
            }
            case BREAK -> {
                if (!stillWanted(mc, target)) {
                    stopBreaking(mc);
                    broken++;
                    brokenHere++;
                    anyTarget = false;
                    retries = 0;
                    // a short stop before the next block (recorded: ~100-400 ms). A new block
                    // (Titanium right where the Mithril was) only breaks after a fresh click.
                    pauseUntil = now + 40 + ThreadLocalRandom.current().nextInt(110);
                    maybeJiggle(mc);
                    next(State.PAUSE);
                    return;
                }
                if (!onTarget(mc)) {
                    if (++offTargetTicks > 4) {
                        stopBreaking(mc);
                        next(State.AIM);
                    }
                    return;
                }
                offTargetTicks = 0;
                BlockHitResult hit = (BlockHitResult) mc.hitResult;
                if (mc.gameMode != null && mc.gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection())) {
                    player.swing(InteractionHand.MAIN_HAND);
                }
                if (now - stateAt > Math.max(1_200L, expectedMs * 2) || now - stateAt > BREAK_TIMEOUT_MS) {
                    // twice as long as it should take and still there: the server did not take the
                    // click (e.g. Titanium that spawned under the crosshair). Let go, then again.
                    stopBreaking(mc);
                    if (++retries > 2) {
                        retries = 0;
                        skip.put(target, now + 25_000L);
                        CommissionBot.log("Block does not break, leaving it for now");
                        next(State.SELECT);
                    } else {
                        // let go and mine another block first, then this one again
                        prioritySeen.put(target, broken);
                        if (!first(MiningBlock.of(mc.level.getBlockState(target)))) {
                            skip.put(target, now + 2_500L);
                        }
                        anyTarget = false;
                        pauseUntil = now + 200 + ThreadLocalRandom.current().nextInt(200);
                        next(State.PAUSE);
                    }
                }
            }
            case PAUSE -> {
                if (jiggleKey != null && now >= jiggleUntil) {
                    jiggleKey.setDown(false);
                    jiggleKey = null;
                }
                if (now >= pauseUntil && jiggleKey == null) {
                    if (retryTarget != null && stillWanted(mc, retryTarget)) {
                        target = retryTarget; // same block again, fresh click
                        retryTarget = null;
                        next(State.AIM);
                    } else {
                        retryTarget = null;
                        next(State.SELECT);
                    }
                }
            }
            case WALK -> walk(mc, player, now);
            case BARITONE -> {
                boolean near = standPos != null && player.position().distanceTo(Vec3.atBottomCenterOf(standPos)) < 1.3;
                if (near || now - stateAt > 10_000L || (!pingplus.voicechat.client.FollowFeature.walkPathing()
                        && now - stateAt > 2_500L)) {
                    RouteWalker.stopBaritone();
                    next(State.SELECT);
                }
            }
            default -> {
            }
        }
    }

    /** How long a block should take: own measured average, else the formula, else a safe guess. */
    private static long expectedBreakMs(MiningBlock kind) {
        if (kind == null) return 2_000L;
        long measured = com.coflnet.mining.MiningFeatures.stats().avgMs(kind);
        if (measured > 0) return measured;
        double speed = com.coflnet.mining.MiningState.miningSpeed();
        int ticks = kind.ticks(speed > 0 ? speed : 700);
        return Math.max(250L, ticks * 50L);
    }

    /** Now and then a small sneaking step between two blocks, like a player shifting position. */
    private void maybeJiggle(Minecraft mc) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (rnd.nextDouble() > 0.3) return;
        net.minecraft.client.KeyMapping[] keys = {mc.options.keyLeft, mc.options.keyRight, mc.options.keyUp, mc.options.keyDown};
        jiggleKey = keys[rnd.nextInt(keys.length)];
        // away from the middle of the area happens, but towards it is much likelier (in the middle: any way)
        Vec3 center = centerFor(mc.player);
        if (center != null && rnd.nextDouble() < 0.7) {
            double yaw = Math.toRadians(mc.player.getYRot());
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // forward
            double rx = -Math.cos(yaw), rz = -Math.sin(yaw);  // right
            double dx = center.x - mc.player.getX(), dz = center.z - mc.player.getZ();
            double len = Math.hypot(dx, dz);
            dx /= len;
            dz /= len;
            double fwd = fx * dx + fz * dz, right = rx * dx + rz * dz;
            if (Math.abs(fwd) >= Math.abs(right)) jiggleKey = fwd > 0 ? mc.options.keyUp : mc.options.keyDown;
            else jiggleKey = right > 0 ? mc.options.keyRight : mc.options.keyLeft;
        }
        jiggleKey.setDown(true);
        jiggleUntil = System.currentTimeMillis() + 120 + rnd.nextInt(280);
    }

    /** The middle of the mining area we are in, when we are not in the middle already (else null). */
    private static Vec3 centerFor(LocalPlayer player) {
        if (player == null) return null;
        Vec3 c;
        try {
            c = DwarvenGraph.get().miningCenter(player.position(), 30);
        } catch (RuntimeException e) {
            return null;
        }
        if (c == null || Math.hypot(c.x - player.getX(), c.z - player.getZ()) < 3.0) return null;
        return c;
    }

    private void next(State s) {
        state = s;
        stateAt = System.currentTimeMillis();
        offTargetTicks = 0;
    }

    private boolean stillWanted(Minecraft mc, BlockPos pos) {
        if (pos == null) return false;
        BlockState state = mc.level.getBlockState(pos);
        if (anyTarget) {
            // the "other block" before Titanium: done once it changed (broken -> bedrock / air)
            return state == anyTargetState;
        }
        return want(MiningBlock.of(state));
    }

    private boolean onTarget(Minecraft mc) {
        return mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(target);
    }

    private void select(Minecraft mc, LocalPlayer player, long now) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1f);
        // Titanium (the priority block) somewhere around but out of reach: go there first
        if (now - lastHuntAt > 1_000L) {
            lastHuntAt = now;
            BlockPos hunt = nearestPriority(mc, player);
            if (hunt != null && !inReach(mc, player, eye, hunt)) {
                // a Titanium we already walked to 3 times without reaching it: leave it for a while
                int tries = huntTries.merge(hunt, 1, Integer::sum);
                if (tries > 3) {
                    skip.put(hunt, now + 30_000L);
                    huntTries.remove(hunt);
                } else if (moveToOre(mc, player, hunt, false)) {
                    CommissionBot.log("Going to " + label(hunt));
                    return;
                }
            }
        }
        // moved enough here: change platform now and then (up, down, to the next patch)
        if (brokenHere >= roamAfter && moveToOre(mc, player, null, true)) {
            brokenHere = 0;
            roamAfter = 6 + ThreadLocalRandom.current().nextInt(9);
            return;
        }
        Object[] best = null;     // {pos, point, face, angle}
        Object[] bestOther = null; // best wanted block that is not the priority one
        Object[] bestAny = null;   // any breakable block (for "another block first")
        double bestScore = Double.MAX_VALUE, otherScore = Double.MAX_VALUE, anyScore = Double.MAX_VALUE;
        BlockPos center = BlockPos.containing(eye);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int r = (int) Math.ceil(REACH);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    at.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = mc.level.getBlockState(at);
                    if (state.isAir() || skip.containsKey(at)) continue;
                    MiningBlock kind = MiningBlock.of(state);
                    boolean wantedHere = want(kind);
                    boolean breakable = !state.is(net.minecraft.world.level.block.Blocks.BEDROCK)
                            && state.getDestroySpeed(mc.level, at) >= 0 && !state.getCollisionShape(mc.level, at).isEmpty();
                    if (!wantedHere && !breakable) continue;
                    if (Vec3.atCenterOf(at).distanceTo(eye) > REACH + 0.9) continue;
                    if (!closeEnough(player.position(), eye, at)) continue;
                    BlockPos pos = at.immutable();
                    Object[] aim = aimPoint(mc.level, player, eye, pos);
                    if (aim == null) continue;
                    Vec3 point = (Vec3) aim[0];
                    double angle = Math.toDegrees(Math.acos(Math.clamp(point.subtract(eye).normalize().dot(look), -1, 1)));
                    double score = angle + point.distanceTo(eye) * 3.0;
                    Object[] cand = {pos, point, aim[1], angle};
                    if (wantedHere) {
                        double s = score - (first(kind) ? 1_000 : 0);
                        if (s < bestScore) { bestScore = s; best = cand; }
                        if (!first(kind) && score < otherScore) { otherScore = score; bestOther = cand; }
                    } else if (score < anyScore) {
                        anyScore = score;
                        bestAny = cand;
                    }
                }
            }
        }
        if (best == null) {
            if (now - stateAt < 150) return; // do not rescan the whole area every tick
            stateAt = now;
            if (!moveToOre(mc, player, null, false)) {
                if (exhaustedSince == 0) exhaustedSince = now;
            }
            return;
        }
        exhaustedSince = 0;
        anyTarget = false;
        BlockPos pos = (BlockPos) best[0];
        if (first(MiningBlock.of(mc.level.getBlockState(pos)))) {
            // Titanium only breaks after another block was mined since it showed up
            Integer seenAt = prioritySeen.putIfAbsent(pos, broken);
            if (seenAt == null || seenAt >= broken) {
                Object[] other = bestOther != null ? bestOther : bestAny;
                if (other != null) {
                    anyTarget = bestOther == null;
                    if (anyTarget) anyTargetState = mc.level.getBlockState((BlockPos) other[0]);
                    best = other;
                    CommissionBot.log("Another block first, then the " + label(pos));
                }
            }
        }
        target = (BlockPos) best[0];
        aimPoint = (Vec3) best[1];
        face = (Direction) best[2];
        // fast for small turns, calmer for big ones (a big snap looks wrong)
        double angle = (double) best[3];
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // never a snap: quick for the block next to the last one, calm for bigger turns
        turnSpeed = angle < 25 ? 420f + rnd.nextFloat() * 200f
                : angle < 70 ? 320f + rnd.nextFloat() * 120f
                : 240f + rnd.nextFloat() * 90f;
        next(State.AIM);
    }

    /** Null-safe: a broken block turns into bedrock or air, which is no ore at all. */
    private boolean want(MiningBlock kind) {
        return kind != null && wanted.test(kind);
    }

    private boolean first(MiningBlock kind) {
        return kind != null && priority.test(kind);
    }

    /** Close like a player mines: near for blocks around the feet, a bit further for blocks overhead. */
    private static boolean closeEnough(Vec3 feet, Vec3 eye, BlockPos block) {
        double flat = Math.hypot(block.getX() + 0.5 - feet.x, block.getZ() + 0.5 - feet.z);
        boolean above = block.getY() + 0.5 > eye.y + 0.5;
        return flat <= (above ? FLAT_ABOVE : FLAT_NEAR);
    }

    /** A random point on a visible face of {@code pos} the eye can hit, with its face; null if none. */
    private static Object[] aimPoint(Level level, LocalPlayer player, Vec3 eye, BlockPos pos) {
        Vec3 c = Vec3.atCenterOf(pos);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Object[] best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            Vec3 n = new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
            Vec3 faceCenter = c.add(n.scale(0.5));
            if (n.dot(eye.subtract(faceCenter)) <= 0) continue; // face looks away from us
            BlockState neighbour = level.getBlockState(pos.relative(d));
            if (neighbour.canOcclude()) continue;
            double u = (rnd.nextDouble() - 0.5) * 0.6, v = (rnd.nextDouble() - 0.5) * 0.6;
            Vec3 point = switch (d.getAxis()) {
                case X -> faceCenter.add(0, u, v);
                case Y -> faceCenter.add(u, 0, v);
                case Z -> faceCenter.add(u, v, 0);
            };
            double dist = point.distanceTo(eye);
            if (dist > REACH || dist >= bestDist) continue;
            BlockHitResult hit = level.clip(new ClipContext(eye, point.add(point.subtract(eye).normalize().scale(0.05)),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) continue;
            best = new Object[]{point, hit.getDirection()};
            bestDist = dist;
        }
        return best;
    }

    // ------------------------------------------------------------------ moving to ore

    /** Starts a move to the nearest wanted ore out of reach; false when there is none around. */
    /**
     * Moves towards ore out of reach: {@code forced} (Titanium we saw), or the best patch around -
     * with {@code elsewhere} not the one we stand at (platform change). False when there is none.
     */
    private boolean moveToOre(Minecraft mc, LocalPlayer player, BlockPos forced, boolean elsewhere) {
        Level level = mc.level;
        BlockPos feet = player.blockPosition();
        BlockPos ore = forced;
        if (ore == null) {
            List<BlockPos> found = new java.util.ArrayList<>();
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
            for (int dx = -SCAN; dx <= SCAN; dx++) {
                // deep down too: the tiers below are often full again while the ones here regenerate
                for (int dy = -10; dy <= 6; dy++) {
                    for (int dz = -SCAN; dz <= SCAN; dz++) {
                        at.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                        MiningBlock kind = MiningBlock.of(level.getBlockState(at));
                        if (!want(kind) || skip.containsKey(at) || !exposed(level, at)) continue;
                        if (elsewhere && Math.abs(dx) <= 3 && Math.abs(dz) <= 3 && Math.abs(dy) <= 2) continue;
                        found.add(at.immutable());
                    }
                }
            }
            double bestScore = Double.MAX_VALUE;
            Vec3 centerNow = centerFor(player);
            for (BlockPos p : found) {
                int dx = p.getX() - feet.getX(), dy = p.getY() - feet.getY(), dz = p.getZ() - feet.getZ();
                // a patch with many blocks is worth a few more steps
                int around = 0;
                for (BlockPos q : found) {
                    if (Math.abs(q.getX() - p.getX()) <= 2 && Math.abs(q.getY() - p.getY()) <= 2
                            && Math.abs(q.getZ() - p.getZ()) <= 2) around++;
                }
                MiningBlock kind = MiningBlock.of(level.getBlockState(p));
                double score = Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 1.2 - Math.min(around, 12) * 0.8
                        - (first(kind) ? 8 : 0);
                if (centerNow != null) {
                    // patches towards the middle of the area are preferred
                    score += Math.hypot(p.getX() + 0.5 - centerNow.x, p.getZ() + 0.5 - centerNow.z) * 0.35;
                }
                if (score < bestScore) {
                    bestScore = score;
                    ore = p;
                }
            }
        }
        if (ore == null) return false;
        BlockPos stand = standFor(level, ore, player);
        if (stand == null) {
            skip.put(ore, System.currentTimeMillis() + 20_000L);
            return forced == null;
        }
        standPos = stand;
        brokenHere = 0;
        double dy = stand.getY() - player.getY();
        double flat = Math.hypot(stand.getX() + 0.5 - player.getX(), stand.getZ() + 0.5 - player.getZ());
        if (dy >= 0.6 && tryEtherwarp(mc, player, stand)) return true;   // up: etherwarp, no jumping
        if (dy <= -1.5 && tryTeleportDown(mc, player, stand)) return true; // down: teleport
        if (Math.abs(dy) < 0.6 && flat <= 8) {
            next(State.WALK); // same height: sneaking steps
            return true;
        }
        // a step down (sneaking never steps off an edge) or no warp possible: the pathfinder walks
        setSneak(mc, false);
        pingplus.voicechat.client.FollowFeature.walkTo(stand, 0);
        next(State.BARITONE);
        return true;
    }

    /** The nearest exposed priority block (Titanium) within 20 blocks, or null. */
    private BlockPos nearestPriority(Minecraft mc, LocalPlayer player) {
        if (!hasPriority) return null;
        BlockPos feet = player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -20; dx <= 20; dx++) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dz = -20; dz <= 20; dz++) {
                    at.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (!first(MiningBlock.of(mc.level.getBlockState(at))) || skip.containsKey(at)
                            || !exposed(mc.level, at)) continue;
                    double d = dx * dx + dz * dz + dy * dy * 2.0;
                    if (d < bestDist) {
                        bestDist = d;
                        best = at.immutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean inReach(Minecraft mc, LocalPlayer player, Vec3 eye, BlockPos pos) {
        return Vec3.atCenterOf(pos).distanceTo(eye) <= REACH + 0.9 && closeEnough(player.position(), eye, pos)
                && aimPoint(mc.level, player, eye, pos) != null;
    }

    /** A spot to stand on (solid below, two free blocks) from where the ore is in reach and visible. */
    private static BlockPos standFor(Level level, BlockPos ore, LocalPlayer player) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -4; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    at.set(ore.getX() + dx, ore.getY() + dy, ore.getZ() + dz);
                    if (!standable(level, at)) continue;
                    Vec3 eye = Vec3.atBottomCenterOf(at).add(0, 1.62, 0);
                    if (eye.distanceTo(Vec3.atCenterOf(ore)) > REACH - 0.3) continue;
                    if (!closeEnough(Vec3.atBottomCenterOf(at), eye, ore)) continue;
                    BlockHitResult hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(ore),
                            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                    if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(ore)) continue;
                    // the way there counts most; standing right next to the ore is a little better
                    double flat = Math.hypot(at.getX() - ore.getX(), at.getZ() - ore.getZ());
                    double score = Vec3.atBottomCenterOf(at).distanceTo(player.position()) + flat * 0.5;
                    if (score < bestScore) {
                        bestScore = score;
                        best = at.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static boolean standable(Level level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return !below.getCollisionShape(level, pos.below()).isEmpty()
                && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && level.getBlockState(pos).getFluidState().isEmpty();
    }

    private static boolean exposed(Level level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (!level.getBlockState(pos.relative(d)).canOcclude()) return true;
        }
        return false;
    }

    /** Up: etherwarp onto the block we want to stand on. */
    private boolean tryEtherwarp(Minecraft mc, LocalPlayer player, BlockPos stand) {
        if (!AotvAssist.canEtherwarp(player)) return false;
        BlockPos floor = stand.below();
        Vec3 eye = player.getEyePosition();
        // on the real top of the block (slab, stairs ...), not the top of a full cube
        Vec3 aim = WarpPlanner.etherwarpAim(mc.level, eye, floor, true);
        if (aim == null) return false;
        stopBreaking(mc);
        boolean ok = AotvAssist.execute(List.of(new WarpPlanner.Hop(WarpPlanner.Kind.ETHERWARP, aim,
                Vec3.atBottomCenterOf(stand), false, null)));
        if (ok) {
            CommissionBot.log("Etherwarp up " + Math.round(stand.getY() - player.getY()) + " blocks");
            setSneak(mc, false); // AOTV sneaks itself for the etherwarp
            next(State.WARP);
        }
        return ok;
    }

    /** Down: look at the spot below and teleport (a plain teleport, so no sneaking). */
    private boolean tryTeleportDown(Minecraft mc, LocalPlayer player, BlockPos stand) {
        int reach = AotvAssist.teleportReach(player);
        if (reach <= 0) return false;
        Vec3 eye = player.getEyePosition();
        Vec3 landing = Vec3.atBottomCenterOf(stand);
        Vec3 dir = null;
        // aim a bit above the floor of the spot; try a few heights for the one that lands there
        for (double lift : new double[]{1.2, 0.8, 1.6, 0.4}) {
            Vec3 d = landing.add(0, lift, 0).subtract(eye).normalize();
            Vec3 land = WarpPlanner.simulateTeleport(mc.level, eye, d, reach);
            if (land != null && Math.hypot(land.x - landing.x, land.z - landing.z) <= 2.0
                    && Math.abs(land.y - landing.y) <= 1.5) {
                dir = d;
                landing = land;
                break;
            }
        }
        if (dir == null) return false;
        stopBreaking(mc);
        setSneak(mc, false); // sneaking would turn it into an etherwarp
        boolean ok = AotvAssist.execute(List.of(new WarpPlanner.Hop(WarpPlanner.Kind.TELEPORT,
                eye.add(dir.scale(4)), landing, false, dir)));
        if (ok) {
            CommissionBot.log("Teleport down " + Math.round(player.getY() - stand.getY()) + " blocks");
            next(State.WARP);
        }
        return ok;
    }

    /** A few sneaking steps on flat ground (sneaking never falls off an edge). */
    private void walk(Minecraft mc, LocalPlayer player, long now) {
        if (standPos == null) {
            next(State.SELECT);
            return;
        }
        Vec3 goal = Vec3.atBottomCenterOf(standPos);
        double flat = Math.hypot(goal.x - player.getX(), goal.z - player.getZ());
        if (flat < 0.6 || now - stateAt > 4_000L || (player.horizontalCollision && now - stateAt > 1_200L)) {
            setForward(mc, false);
            if (flat >= 0.6 && player.horizontalCollision) {
                // a step in the way: let the pathfinder walk it
                setSneak(mc, false);
                pingplus.voicechat.client.FollowFeature.walkTo(standPos, 0);
                next(State.BARITONE);
                return;
            }
            next(State.SELECT);
            return;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(goal.z - player.getZ(), goal.x - player.getX())) - 90.0);
        aimPoint = player.getEyePosition().add(Vec3.directionFromRotation(28f, yaw).scale(4));
        setForward(mc, Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - player.getYRot())) < 35f);
    }

    // ------------------------------------------------------------------ camera, keys, tools

    /** Every frame: snap the camera to the aim point (fast, eased). */
    void frame(Minecraft mc) {
        LocalPlayer player = mc.player;
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.min(0.05, (now - lastFrame) / 1e9);
        lastFrame = now;
        if (player == null || aimPoint == null || mc.gui.screen() != null || AotvAssist.isBusy()
                || state == State.BARITONE || state == State.WARP) return;
        float[] rot = rotationTo(player, aimPoint);
        float speed = state == State.WALK ? 360f : turnSpeed;
        player.setYRot(AimSmoothing.step(player.getYRot(), rot[0], speed, dt, 9.0));
        player.setXRot(Math.clamp(AimSmoothing.step(player.getXRot(), rot[1], speed, dt, 9.0), -90f, 90f));
    }

    private float aimError(LocalPlayer player) {
        if (aimPoint == null) return 180f;
        float[] rot = rotationTo(player, aimPoint);
        return Math.max(Math.abs(net.minecraft.util.Mth.wrapDegrees(rot[0] - player.getYRot())),
                Math.abs(rot[1] - player.getXRot()));
    }

    private static float[] rotationTo(LocalPlayer player, Vec3 point) {
        Vec3 d = point.subtract(player.getEyePosition());
        float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        return new float[]{yaw, pitch};
    }

    private void stopBreaking(Minecraft mc) {
        if (mc.gameMode != null && (state == State.BREAK || state == State.AIM)) {
            mc.gameMode.stopDestroyBlock();
        }
    }

    private void setSneak(Minecraft mc, boolean on) {
        if (on) {
            mc.options.keyShift.setDown(true);
            sneakOwned = true;
        } else if (sneakOwned) {
            mc.options.keyShift.setDown(false);
            sneakOwned = false;
        }
    }

    private void setForward(Minecraft mc, boolean on) {
        if (on) {
            mc.options.keyUp.setDown(true);
            forwardOwned = true;
        } else if (forwardOwned) {
            mc.options.keyUp.setDown(false);
            forwardOwned = false;
        }
    }

    /** Hotbar slot of a pickaxe, drill or gauntlet; -1 without one. */
    static int pickaxeSlot(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
            String id = skyblockId(stack);
            if (name.contains("pickaxe") || name.contains("drill") || name.contains("gauntlet")
                    || id.contains("PICKAXE") || id.contains("DRILL") || id.contains("GAUNTLET")) {
                return i;
            }
        }
        return -1;
    }

    private static void selectPickaxe(LocalPlayer player) {
        if (com.coflnet.failsafe.Failsafe.slotFrozen()) return; // a swap we have not "noticed" yet
        int slot = pickaxeSlot(player);
        if (slot >= 0 && player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
        }
    }

    private static String skyblockId(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) return "";
        var root = data.copyTag();
        var extra = root.getCompound("ExtraAttributes").orElse(root);
        return extra.getString("id").orElse("");
    }

    private static IBaritone baritone() {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
