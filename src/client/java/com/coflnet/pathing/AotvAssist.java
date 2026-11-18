package com.coflnet.pathing;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import pingplus.voicechat.client.etherwarp.EtherwarpHelper;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The pathfinder's three stages, in this order - only the ones switched on (Pathfinder tab,
 * AOTV mode) take part:
 * <ol>
 *   <li><b>Etherwarp</b>: up to three warps onto visible blocks, for goals that are hard to walk
 *       to (a ledge, a single block in a wall, out of a cave).</li>
 *   <li><b>Teleport</b>: AOTV right click, 12 blocks along the look; for long straight ways,
 *       over rough ground after a jump, and straight down onto the floor while falling.</li>
 *   <li><b>Walking</b> with Baritone - always, whenever no warp saves enough time.</li>
 * </ol>
 * {@link WarpPlanner} compares all of them in ticks (a warp costs its time plus an extra for
 * the mana). While a warp runs Baritone is paused by a high-priority process; the camera turns
 * smoothly to every warp direction first. The lowballer never warps.
 */
public final class AotvAssist {

    public enum Mode { OFF, TELEPORT, ETHERWARP, BOTH }

    private enum Step { IDLE, PREPARE, JUMP, AIM, USE, WAIT }

    private static final String ID_AOTE = "ASPECT_OF_THE_END";
    private static final String ID_AOTV = "ASPECT_OF_THE_VOID";
    /** Without a path for this long Baritone counts as stuck: walking is then very expensive. */
    private static final long NO_PATH_STUCK_MS = 1_500L;

    private static Step step = Step.IDLE;
    private static List<WarpPlanner.Hop> route = List.of();
    private static int hopIndex;
    private static int slot = -1;
    private static int stepTicks;
    private static int previousSlot = -1;
    private static boolean sneakOwned;
    private static boolean jumpOwned;
    private static Vec3 startPos;
    private static double jumpStartY;
    private static long cooldownUntil;
    private static int evaluateCounter;
    private static IBaritone registeredOn;
    private static int warps;
    private static long noPathSince;
    private static int alignedTicks;
    private static long lastFrameNanos;
    /** Teleports done in a row without walking in between (each one re-planned). */
    private static int chain;
    /** The current route was planned in the air (cutting a jump short): stale once we land. */
    private static boolean plannedInAir;
    private static boolean wasOnGround = true;
    /** Etherwarp: times the aim was refined after the last check before firing failed. */
    private static int fireRetries;
    private static int etherCounter;
    private static boolean forwardOwned;
    private static int teleportReach = 12;
    /** Ticks an in-air teleport needs to turn and fire; the plan starts from there. */
    private static final int AIR_AIM_TICKS = 3;
    private static WalkSearch walkCache;
    private static List<BlockPos> walkCacheCenters = List.of();
    private static long walkCacheAt;
    private static net.minecraft.world.level.Level walkCacheLevel;

    /** Holds Baritone still while a warp is in progress. */
    private static final IBaritoneProcess PAUSE = new IBaritoneProcess() {
        @Override
        public boolean isActive() {
            return step != Step.IDLE;
        }

        @Override
        public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        @Override
        public boolean isTemporary() {
            return true;
        }

        @Override
        public void onLostControl() {
        }

        @Override
        public double priority() {
            return 1_000.0;
        }

        @Override
        public String displayName0() {
            return "AOTV warp";
        }
    };

    private AotvAssist() {
    }

    /** True while aiming/warping: other camera control must stay out. */
    public static boolean isBusy() {
        return step != Step.IDLE;
    }

    public static int warps() {
        return warps;
    }

    public static Mode mode() {
        try {
            return Mode.valueOf(PathfinderConfig.get().aotvMode.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return Mode.OFF;
        }
    }

    private static boolean etherwarpOn(Mode mode) {
        return mode == Mode.ETHERWARP || mode == Mode.BOTH;
    }

    private static boolean teleportOn(Mode mode) {
        return mode == Mode.TELEPORT || mode == Mode.BOTH;
    }

    // ================================================================ planning

    /** Called every client tick by {@link PathfinderSystem}. */
    static void tick(Minecraft client, IBaritone baritone) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || baritone == null) {
            abort(client);
            return;
        }
        if (registeredOn != baritone) {
            baritone.getPathingControlManager().registerProcess(PAUSE);
            registeredOn = baritone;
        }
        if (step != Step.IDLE) {
            if (client.gui.screen() != null || com.coflnet.lowballing.LowballingMod.isRunning()) {
                abort(client);
                return;
            }
            runStep(client, player, baritone);
            return;
        }
        Goal goal = baritone.getPathingBehavior().getGoal();
        boolean botWalking = goal != null && baritone.getPathingControlManager().mostRecentInControl()
                .filter(process -> process != PAUSE).isPresent();
        long now = System.currentTimeMillis();
        if (!botWalking || baritone.getPathingBehavior().hasPath()) {
            noPathSince = 0;
        } else if (noPathSince == 0) {
            noPathSince = now;
        }
        Mode mode = mode();
        if (mode == Mode.OFF || !botWalking || now < cooldownUntil || client.gui.screen() != null
                // the lowballer always walks: no AOTV, no etherwarp
                || com.coflnet.lowballing.LowballingMod.isRunning()) {
            chain = 0;
            return;
        }
        // In the air (a jump - with Jump Boost 20+ ticks - or a drop): check often, a teleport
        // can cut the flight short. On the ground every half second is enough.
        boolean inAir = !player.onGround() && !player.onClimbable()
                && airTicksLeft(client, player) >= 6;
        // Every jump: check the teleport stage right at take-off - the earlier the teleport,
        // the more of the (Jump Boost) flight it saves.
        boolean takeOff = inAir && wasOnGround && player.getDeltaMovement().y > 0.2;
        wasOnGround = player.onGround();
        int every = inAir ? 2 : 4;
        if ((!takeOff && ++evaluateCounter % every != 0) || (!player.onGround() && !inAir)
                || player.isInWater() || player.isInLava()) {
            return;
        }
        // the teleport check is cheap: every 4 ticks on the ground; the etherwarp search only
        // every third time
        boolean withEther = player.onGround() && ++etherCounter % 3 == 0;
        Optional<WarpPlanner.Route> found = plan(client, player, baritone, goal, mode, false, withEther);
        found.ifPresent(r -> start(client, player, r));
    }

    private static Optional<WarpPlanner.Route> plan(Minecraft client, LocalPlayer player, IBaritone baritone,
                                                    Goal goal, Mode mode, boolean chained, boolean withEther) {
        PathfinderConfig config = PathfinderConfig.get();
        WarpPlanner.Context c = new WarpPlanner.Context();
        c.level = client.level;
        c.feet = player.position();
        c.eye = player.getEyePosition();
        c.yaw = player.getYRot();
        c.pitch = player.getXRot();
        c.onGround = player.onGround();
        c.verticalSpeed = player.getDeltaMovement().y;
        c.airTicksLeft = c.onGround ? 0 : airTicksLeft(client, player);
        if (!c.onGround) {
            // In the air the player keeps flying while the camera turns: plan from where he
            // will be when the teleport fires, not from where he is now (the ray then went into
            // the ground: "There are blocks in the way!").
            Vec3 ahead = predictFlight(player, AIR_AIM_TICKS);
            c.eye = ahead.add(0, player.getEyePosition().y - player.getY(), 0);
            c.feet = ahead;
            c.airTicksLeft = Math.max(0, c.airTicksLeft - AIR_AIM_TICKS);
        }
        c.goal = goal;
        PlayerMovementStats stats = PathfinderTuning.stats();
        c.speedFactor = Math.clamp(0.1 / Math.max(0.02, stats.speed()), 0.15, 1.5);
        c.jumpHeight = stats.jumpHeight();
        // one jump in the walking search: the air time of a jump onto a block (25+ ticks with
        // strong Jump Boost) minus the walking part that is counted anyway
        c.jumpTicks = Math.max(3.0, PlayerMovementStats.airTicksOntoBlock(stats.jumpVelocity(), stats.gravity()) - 3.0);
        c.turnSpeed = turnSpeed();
        c.maxChain = config.etherwarpMaxChain;
        c.etherwarpExtraTicks = config.etherwarpCostTicks;
        c.teleportExtraTicks = config.teleportCostTicks;
        // Hard part ahead (gap, 1-block corridor, jump after jump): walking it may fail and get
        // stuck, so walking counts more expensive and an etherwarp over it cheaper.
        double difficulty = PathDifficulty.level();
        c.walkRisk = 1.0 + difficulty;
        c.etherwarpExtraTicks *= 1.0 - 0.6 * difficulty;

        // Reuse the walking search (its block reads and answers) while the goal stays put:
        // a landing spot that can not reach the goal costs the full search every time.
        List<BlockPos> centers = WarpPlanner.goalCenters(goal);
        long nowMs = System.currentTimeMillis();
        if (walkCache == null || !centers.equals(walkCacheCenters) || nowMs - walkCacheAt > 3_000L
                || walkCacheLevel != client.level) {
            walkCache = WarpPlanner.newWalk(c);
            walkCacheCenters = centers;
            walkCacheAt = nowMs;
            walkCacheLevel = client.level;
        }
        c.walk = walkCache;

        IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
        IPath path = executor == null ? null : executor.getPath();
        if (path != null && !chained) {
            int from = Math.max(0, executor.getPosition());
            List<BetterBlockPos> positions = path.positions();
            c.path = from < positions.size() ? positions.subList(from, positions.size()) : List.of();
            BlockPos end = path.getDest();
            c.walkVanillaTicks = path.ticksRemainingFrom(from)
                    + (goal.isInGoal(end) ? 0 : WarpPlanner.walkTicks(c, end));
        } else if (chained) {
            if (path != null) {
                c.path = path.positions();
            }
            c.walkVanillaTicks = WarpPlanner.walkTicks(c, player.blockPosition());
        } else if (noPathSince > 0 && System.currentTimeMillis() - noPathSince > NO_PATH_STUCK_MS) {
            // Baritone finds no way (a ledge, a hole in a wall): walking is not an option
            c.walkVanillaTicks = WarpPlanner.walkTicks(c, player.blockPosition()) * 2.0 + 200.0;
        } else {
            return Optional.empty(); // still planning the first path
        }
        if (c.walkVanillaTicks <= 0) {
            return Optional.empty();
        }
        double minSaving = Math.max(6.0, config.aotvMinGain * 3.6 * c.speedFactor);
        if (!c.onGround) {
            // cutting a running jump short: every saved tick of flight counts
            minSaving *= 0.5;
        }

        WarpPlanner.Route best = null;
        int etherSlot = hotbarSlot(player, true);
        if (withEther && etherwarpOn(mode) && etherSlot >= 0 && c.onGround) {
            best = better(best, WarpPlanner.etherwarp(c), minSaving, 0.15);
        }
        int teleSlot = hotbarSlot(player, false);
        if (teleportOn(mode) && teleSlot >= 0) {
            ItemStack item = player.getInventory().getItem(teleSlot);
            c.teleportReach = teleportBlocks(item, config.aotvTeleportBlocks);
            teleportReach = c.teleportReach;
            best = better(best, WarpPlanner.teleport(c), minSaving, c.onGround ? 0.15 : 0.0);
        }
        if (best != null) {
            WarpPlanner.Hop first = best.hops().get(0);
            System.out.println("[Pathfinder] Warp-Plan: " + best.hops().size() + "x "
                    + first.kind() + (first.jumpFirst() ? " (nach Sprung)" : "")
                    + (c.onGround ? "" : " (in der Luft)") + String.format(Locale.ROOT,
                    " %.0f statt %.0f Ticks, von %.1f %.1f %.1f nach %.1f %.1f %.1f",
                    best.ticks(), best.walkTicks(), c.feet.x, c.feet.y, c.feet.z,
                    best.hops().get(best.hops().size() - 1).landing().x,
                    best.hops().get(best.hops().size() - 1).landing().y,
                    best.hops().get(best.hops().size() - 1).landing().z));
        }
        return Optional.ofNullable(best);
    }

    /** Where the feet will be in {@code ticks} ticks of free flight (gravity, air drag). */
    static Vec3 predictFlight(LocalPlayer player, int ticks) {
        Vec3 pos = player.position();
        Vec3 v = player.getDeltaMovement();
        double gravity = PathfinderTuning.stats().gravity();
        double x = pos.x, y = pos.y, z = pos.z;
        double vx = v.x, vy = v.y, vz = v.z;
        for (int i = 0; i < ticks; i++) {
            x += vx;
            y += vy;
            z += vz;
            vy = (vy - gravity) * 0.98;
            vx *= 0.91;
            vz *= 0.91;
        }
        return new Vec3(x, y, z);
    }

    /** Ticks until the player lands, simulated from the vertical speed and the floor below. */
    static double airTicksLeft(Minecraft client, LocalPlayer player) {
        if (player.onGround()) {
            return 0;
        }
        BlockPos feet = player.blockPosition();
        double floor = Double.NaN;
        for (int i = 0; i <= 40; i++) {
            BlockPos below = feet.below(i);
            if (!client.level.getBlockState(below).getCollisionShape(client.level, below).isEmpty()) {
                floor = below.getY() + 1.0;
                break;
            }
        }
        if (Double.isNaN(floor)) {
            return 0; // nothing below within reach: no teleport "down onto the floor"
        }
        double y = player.getY();
        double v = player.getDeltaMovement().y;
        double gravity = PathfinderTuning.stats().gravity();
        for (int tick = 1; tick < 120; tick++) {
            y += v;
            v = (v - gravity) * 0.98;
            if (y <= floor) {
                return tick;
            }
        }
        return 120;
    }

    private static WarpPlanner.Route better(WarpPlanner.Route current, Optional<WarpPlanner.Route> candidate,
                                            double minSaving, double relative) {
        if (candidate.isEmpty()) {
            return current;
        }
        WarpPlanner.Route r = candidate.get();
        // clearly cheaper than walking: a fixed minimum and (on the ground) 15 % of the walk
        if (r.saving() < minSaving || r.saving() < r.walkTicks() * relative) {
            return current;
        }
        return current == null || r.ticks() < current.ticks() ? r : current;
    }

    // ================================================================ execution

    private static void start(Minecraft client, LocalPlayer player, WarpPlanner.Route r) {
        plannedInAir = !player.onGround();
        fireRetries = 0;
        baritone.behavior.LookBehavior.suspendSmoothing = true;
        route = r.hops();
        hopIndex = 0;
        if (previousSlot < 0) {
            previousSlot = player.getInventory().getSelectedSlot();
        }
        next(Step.PREPARE);
    }

    private static WarpPlanner.Hop hop() {
        return hopIndex < route.size() ? route.get(hopIndex) : null;
    }

    private static void runStep(Minecraft client, LocalPlayer player, IBaritone baritone) {
        WarpPlanner.Hop hop = hop();
        if (hop == null) {
            finish(client, player, baritone, true);
            return;
        }
        boolean ether = hop.kind() == WarpPlanner.Kind.ETHERWARP;
        stepTicks++;
        if (step != Step.WAIT) {
            steerServerRotation(baritone, player, hop);
            // Keep going while turning: Baritone is paused and let go of all keys, which
            // braked every jump mid-air ("something holds him back"). Not for etherwarp:
            // sneaking, and the target block is fixed anyway.
            // in the air only: on the ground walking on while turning just moved us off the plan
            holdForward(client, !ether && !player.onGround());
        }
        switch (step) {
            case PREPARE -> {
                if (stepTicks == 1) {
                    slot = hotbarSlot(player, ether);
                    if (slot < 0) {
                        finish(client, player, baritone, false);
                        return;
                    }
                    player.getInventory().setSelectedSlot(slot);
                    setSneak(client, ether);
                }
                // the server needs a moment to see the new slot and the sneaking
                // etherwarp: the server needs the sneaking for sure before the warp
                if (stepTicks >= (ether ? 4 : 1)) {
                    next(hop.jumpFirst() && player.onGround() ? Step.JUMP : Step.AIM);
                }
            }
            case JUMP -> {
                if (stepTicks == 1) {
                    jumpStartY = player.getY();
                    client.options.keyJump.setDown(true);
                    jumpOwned = true;
                } else if (stepTicks >= 3) {
                    releaseJump(client);
                    next(Step.AIM);
                }
            }
            case AIM -> {
                if (plannedInAir && player.onGround()) {
                    // landed before the turn was done: the jump is over, walk on
                    cancelQuietly(client, player, baritone);
                    return;
                }
                // The camera turns smoothly in frame(). Warp once it has been on target for a
                // moment, so the server has the exact direction too.
                // the camera AND what the server last got must point there: the teleport goes
                // where the server thinks we look
                float error = Math.max(aimError(player, hop), serverAimError(baritone, player, hop));
                boolean inAir = !player.onGround();
                // etherwarp has to hit one block exactly: steadier and more precise
                float tolerance = ether ? 1.0f : inAir ? 3.0f : 1.5f;
                int steady = ether ? 3 : inAir ? 1 : 2;
                alignedTicks = error < tolerance ? alignedTicks + 1 : 0;
                boolean ready = alignedTicks >= steady;
                if (hop.jumpFirst()) {
                    // teleport from the top of the jump; with strong Jump Boost already at
                    // 3.5 blocks (the planner assumes at most 4)
                    double risen = player.getY() - jumpStartY;
                    ready &= player.getDeltaMovement().y <= 0.05 || risen >= 3.5;
                }
                if (ready) {
                    next(Step.USE);
                } else if (stepTicks >= 30) {
                    // never warp in a direction we did not reach: the old timeout did exactly
                    // that and came out somewhere completely wrong
                    System.out.println("[Pathfinder] Warp abgebrochen: Ausrichtung nicht erreicht");
                    finish(client, player, baritone, false);
                }
            }
            case USE -> {
                aimExact(player, hop);
                if (!stillLands(client, baritone, player, hop)) {
                    if (ether && fireRetries < 2) {
                        // not quite on the block yet: aim again instead of giving the warp up
                        fireRetries++;
                        next(Step.AIM);
                        return;
                    }
                    // from where we are now this warp would not land where it should (fell
                    // further, walked on): better not fire than come out somewhere wrong
                    System.out.println("[Pathfinder] Warp verworfen: wuerde nicht mehr am Ziel landen");
                    cancelQuietly(client, player, baritone);
                    return;
                }
                fireRetries = 0;
                holdForward(client, false);
                startPos = player.position();
                client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                next(Step.WAIT);
            }
            case WAIT -> {
                double moved = startPos == null ? 0 : player.position().distanceTo(startPos);
                boolean arrived = player.position().distanceTo(hop.landing()) < 2.5;
                if (moved > 2.0 && (arrived || stepTicks >= 3) || stepTicks >= 12) {
                    boolean success = moved > 2.0;
                    if (!success) {
                        finish(client, player, baritone, false);
                        return;
                    }
                    warps++;
                    hopIndex++;
                    if (hop() != null) {
                        next(Step.PREPARE);
                    } else {
                        afterRoute(client, player, baritone, hop);
                    }
                }
            }
            default -> {
            }
        }
    }

    /**
     * Route done. After a teleport the next one is planned at once (40 blocks straight = a few
     * teleports in a row, no walking in between); otherwise Baritone takes over again.
     */
    private static void afterRoute(Minecraft client, LocalPlayer player, IBaritone baritone, WarpPlanner.Hop last) {
        if (last.kind() == WarpPlanner.Kind.TELEPORT && chain < 6) {
            Goal goal = baritone.getPathingBehavior().getGoal();
            Mode mode = mode();
            if (goal != null && teleportOn(mode) && !goal.isInGoal(player.blockPosition())) {
                Optional<WarpPlanner.Route> again = plan(client, player, baritone, goal, Mode.TELEPORT, true, false);
                if (again.isPresent()) {
                    chain++;
                    route = again.get().hops();
                    hopIndex = 0;
                    next(Step.PREPARE);
                    return;
                }
            }
        }
        finish(client, player, baritone, true);
    }

    /**
     * Last check before firing, from the current eye along the direction the server has: does
     * the teleport still end near its planned landing, does the etherwarp ray still hit its block?
     */
    private static boolean stillLands(Minecraft client, IBaritone baritone, LocalPlayer player, WarpPlanner.Hop hop) {
        Rotation server = BaritoneAPI.getSettings().freeLook.value
                ? baritone.getPlayerContext().playerRotations()
                : new Rotation(player.getYRot(), player.getXRot());
        Vec3 dir = Vec3.directionFromRotation(server.getPitch(), server.getYaw());
        Vec3 eye = player.getEyePosition();
        if (hop.kind() == WarpPlanner.Kind.ETHERWARP) {
            BlockPos target = BlockPos.containing(hop.landing()).below();
            return EtherwarpHelper.raycast(client.level, eye, dir, EtherwarpHelper.MAX_RANGE)
                    .map(hit -> hit.pos().equals(target)).orElse(false);
        }
        if (!teleportLands(client, eye, dir, hop)) {
            return false;
        }
        if (!player.onGround()) {
            // In the air the server still has us about a tick behind: a third of the in-air
            // teleports came back "There are blocks in the way!" although the check from the
            // current position passed. Fire only when the position a tick ago works too.
            Vec3 previousEye = new Vec3(player.xo, player.yo + player.getEyeHeight(), player.zo);
            return teleportLands(client, previousEye, dir, hop);
        }
        return true;
    }

    private static boolean teleportLands(Minecraft client, Vec3 eye, Vec3 dir, WarpPlanner.Hop hop) {
        Vec3 landed = WarpPlanner.simulateTeleport(client.level, eye, dir, teleportReach);
        if (landed == null) {
            return false; // "There are blocks in the way!"
        }
        double flat = Math.hypot(landed.x - hop.landing().x, landed.z - hop.landing().z);
        return flat <= 3.0 && landed.y >= hop.landing().y - 0.5;
    }

    private static void holdForward(Minecraft client, boolean down) {
        if (down) {
            client.options.keyUp.setDown(true);
            forwardOwned = true;
        } else if (forwardOwned) {
            client.options.keyUp.setDown(false);
            forwardOwned = false;
        }
    }

    private static float aimError(LocalPlayer player, WarpPlanner.Hop hop) {
        float[] wanted = aimRotation(player, hop);
        return Math.max(Math.abs(net.minecraft.util.Mth.wrapDegrees(wanted[0] - player.getYRot())),
                Math.abs(wanted[1] - player.getXRot()));
    }

    /**
     * With Baritone's free look the server gets Baritone's (silent) rotation, not the camera:
     * while walking that is the walking direction, maybe far from where the camera looks. Turn
     * that rotation towards the warp too - through Baritone, so its turn limit applies and the
     * server never sees a jump of 100+ degrees in one tick when the warp starts.
     */
    private static void steerServerRotation(IBaritone baritone, LocalPlayer player, WarpPlanner.Hop hop) {
        if (!BaritoneAPI.getSettings().freeLook.value) {
            return; // the server gets the camera rotation anyway
        }
        float[] wanted = aimRotation(player, hop);
        baritone.getLookBehavior().updateTarget(new Rotation(wanted[0], wanted[1]), false);
    }

    /** How far the rotation the server last received is from the warp direction. */
    private static float serverAimError(IBaritone baritone, LocalPlayer player, WarpPlanner.Hop hop) {
        Rotation server = baritone.getPlayerContext().playerRotations();
        float[] wanted = aimRotation(player, hop);
        return Math.max(Math.abs(net.minecraft.util.Mth.wrapDegrees(wanted[0] - server.getYaw())),
                Math.abs(wanted[1] - server.getPitch()));
    }

    /** Where a hop has to look: its fixed direction (teleport) or its block (etherwarp). */
    private static float[] aimRotation(LocalPlayer player, WarpPlanner.Hop hop) {
        if (hop.dir() != null) {
            Vec3 d = hop.dir();
            double flat = Math.sqrt(d.x * d.x + d.z * d.z);
            float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
            float pitch = (float) Math.toDegrees(-Math.atan2(d.y, flat));
            return new float[]{yaw, Math.clamp(pitch, -90.0f, 90.0f)};
        }
        return rotationTo(player, hop.aim());
    }

    private static float[] rotationTo(LocalPlayer player, Vec3 point) {
        Vec3 look = point.subtract(player.getEyePosition());
        double flat = Math.sqrt(look.x * look.x + look.z * look.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(look.z, look.x)) - 90.0);
        float pitch = (float) Math.toDegrees(-Math.atan2(look.y, flat));
        return new float[]{yaw, Math.clamp(pitch, -90.0f, 90.0f)};
    }

    /**
     * Turn speed for a warp (degrees per second). Faster than walking: the recorder showed the
     * old speed tilting the view only ~50 degrees per second at the end of the easing, so a
     * teleport during a jump fired just before landing.
     */
    private static float turnSpeed() {
        return (float) PathfinderConfig.get().warpRotationSpeed;
    }

    /** Every frame while preparing / aiming: turn the camera smoothly towards the warp direction. */
    public static void frame(Minecraft client) {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : Math.min(0.05, (now - lastFrameNanos) / 1e9);
        lastFrameNanos = now;
        LocalPlayer player = client.player;
        WarpPlanner.Hop hop = hop();
        if (player == null || hop == null
                || (step != Step.AIM && step != Step.PREPARE && step != Step.JUMP)) {
            return;
        }
        float[] wanted = aimRotation(player, hop);
        float speed = turnSpeed();
        float yaw = pingplus.voicechat.client.AimSmoothing.step(player.getYRot(), wanted[0], speed, dt, 20.0);
        float pitch = pingplus.voicechat.client.AimSmoothing.step(player.getXRot(), wanted[1], speed, dt, 20.0);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
    }

    private static void aimExact(LocalPlayer player, WarpPlanner.Hop hop) {
        float[] wanted = aimRotation(player, hop);
        player.setYRot(wanted[0]);
        player.setXRot(wanted[1]);
        player.setYHeadRot(wanted[0]);
    }

    private static void next(Step nextStep) {
        step = nextStep;
        stepTicks = 0;
        alignedTicks = 0;
    }

    private static void finish(Minecraft client, LocalPlayer player, IBaritone baritone, boolean success) {
        restore(client, player);
        step = Step.IDLE;
        route = List.of();
        hopIndex = 0;
        chain = 0;
        long now = System.currentTimeMillis();
        if (success) {
            cooldownUntil = now + 800L;
        } else {
            // no teleport: no mana, wrong item, blocked - do not spam it
            cooldownUntil = now + 6_000L;
            System.out.println("[Pathfinder] AOTV ohne Wirkung - Pause 6s");
        }
        try {
            baritone.getPathingBehavior().forceCancel(); // plan again from the new position
        } catch (RuntimeException ignored) {
        }
    }

    private static void cancelQuietly(Minecraft client, LocalPlayer player, IBaritone baritone) {
        restore(client, player);
        step = Step.IDLE;
        route = List.of();
        hopIndex = 0;
        chain = 0;
        cooldownUntil = System.currentTimeMillis() + 300L;
        try {
            baritone.getPathingBehavior().forceCancel();
        } catch (RuntimeException ignored) {
        }
    }

    private static void setSneak(Minecraft client, boolean sneak) {
        if (sneak && !sneakOwned) {
            client.options.keyShift.setDown(true);
            sneakOwned = true;
        } else if (!sneak && sneakOwned) {
            // a plain teleport while sneaking would be an etherwarp
            client.options.keyShift.setDown(false);
            sneakOwned = false;
            KeyMapping.setAll();
        }
    }

    private static void releaseJump(Minecraft client) {
        if (jumpOwned) {
            client.options.keyJump.setDown(false);
            jumpOwned = false;
        }
    }

    private static void restore(Minecraft client, LocalPlayer player) {
        setSneak(client, false);
        releaseJump(client);
        holdForward(client, false);
        baritone.behavior.LookBehavior.suspendSmoothing = false;
        if (player != null && previousSlot >= 0) {
            player.getInventory().setSelectedSlot(previousSlot);
        }
        previousSlot = -1;
        slot = -1;
    }

    private static void abort(Minecraft client) {
        if (step != Step.IDLE) {
            restore(client, client.player);
            step = Step.IDLE;
            route = List.of();
            hopIndex = 0;
            chain = 0;
        }
    }

    // ================================================================ items

    /** Hotbar slot of an Aspect of the End/Void (with Etherwarp if {@code etherwarp}). */
    static int hotbarSlot(LocalPlayer player, boolean etherwarp) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (etherwarp ? EtherwarpHelper.isEtherwarpItem(stack) : isAspect(stack)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isAspect(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
            CompoundTag tag = custom == null ? null : custom.copyTag();
            String id = tag == null ? "" : tag.getStringOr("id", "");
            return ID_AOTV.equals(id) || ID_AOTE.equals(id);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final java.util.regex.Pattern TELEPORT_BLOCKS =
            java.util.regex.Pattern.compile("(?i)teleport\\s+(\\d+)\\s+blocks");

    /** "Teleport 12 blocks ahead" from the item's lore (Transmission Tuners change it). */
    static int teleportBlocks(ItemStack stack, int fallback) {
        try {
            var lore = stack.get(DataComponents.LORE);
            if (lore != null) {
                for (var line : lore.lines()) {
                    java.util.regex.Matcher m = TELEPORT_BLOCKS.matcher(line.getString());
                    if (m.find()) {
                        int blocks = Integer.parseInt(m.group(1));
                        if (blocks >= 4 && blocks <= 30) {
                            return blocks;
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return fallback;
    }
}
