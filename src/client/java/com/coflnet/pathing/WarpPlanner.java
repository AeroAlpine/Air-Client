package com.coflnet.pathing;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.interfaces.IGoalRenderPos;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import pingplus.voicechat.client.etherwarp.EtherwarpHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Plans the first two of the pathfinder's three stages (the third is walking with Baritone):
 *
 * <ol>
 *   <li><b>Etherwarp</b> (Aspect of the Void, Shift + right click): onto any visible block with
 *       two blocks of room above, up to 57 blocks away - also straight up through a cave
 *       ceiling or onto a single block in a wall. Up to {@code maxChain} warps in a row are
 *       searched (A*-like: cheapest route first, only the best nodes are expanded).</li>
 *   <li><b>Teleport</b> (right click): {@code reach} blocks along the look direction, stopped by
 *       the first block in the way - the ground counts too, so looking down while falling puts
 *       the player straight on the floor. From standing, after a jump (over rough ground), or
 *       while already in the air.</li>
 * </ol>
 *
 * Everything is compared in game ticks: walking is Baritone's path cost scaled by the player's
 * real speed; a warp costs the time for switching, sneaking, turning and using the item plus
 * a configurable extra for the mana it uses. A warp is only taken when it is clearly cheaper.
 */
public final class WarpPlanner {

    public enum Kind { ETHERWARP, TELEPORT }

    /**
     * One warp. {@code aim} is the world point to look at (recomputed from the eye every tick
     * while turning), {@code landing} where the feet should end up, {@code jumpFirst} for a
     * teleport from the top of a jump.
     */
    public record Hop(Kind kind, Vec3 aim, Vec3 landing, boolean jumpFirst) {
    }

    /** A route of one or more hops and what it costs compared to walking. */
    public record Route(List<Hop> hops, double ticks, double walkTicks) {
        public double saving() {
            return walkTicks - ticks;
        }
    }

    /** Ticks the bot needs for the parts of a warp that do not depend on the turn. */
    private static final double ETHER_HANDLING_TICKS = 3 + 3 + 2; // switch + sneak, settle, use + land
    private static final double TELEPORT_HANDLING_TICKS = 2 + 2 + 1;
    private static final double EYE = 1.62;
    private static final int TOP_K_SECOND = 6;
    private static final int TOP_K_THIRD = 3;
    private static final int GOAL_SCAN_RADIUS = 4;
    private static final int MAX_FALL = 12;
    private static final int MAX_CANDIDATES = 110;

    /** Inputs of one planning run, filled by {@link AotvAssist}. */
    public static final class Context {
        public Level level;
        public Vec3 feet;
        public Vec3 eye;
        public float yaw;
        public float pitch;
        public boolean onGround;
        public double verticalSpeed;
        public Goal goal;
        /** Remaining path (block positions the feet pass), may be empty. */
        public List<BetterBlockPos> path = List.of();
        /** Walking cost of the rest of the way in vanilla ticks (Baritone's path cost). */
        public double walkVanillaTicks;
        /** Real ticks per vanilla tick: 0.1 / movement speed attribute (348 % speed: ~0.29). */
        public double speedFactor = 1.0;
        /** Degrees per second the camera turns. */
        public double turnSpeed = 360;
        public double jumpHeight = 1.25;
        public int teleportReach = 12;
        public int maxChain = 3;
        public double etherwarpExtraTicks = 25;
        public double teleportExtraTicks = 4;
        public double minSavingTicks = 10;
        /**
         * Real ticks the player still spends in the air before walking on (rising + falling),
         * 0 on the ground. A teleport in the air saves them.
         */
        public double airTicksLeft;

        /** Straight-line estimate from where we stand (vanilla ticks); set by {@link #prepare}. */
        double startEstimate;
        /**
         * Baritone's real path cost / straight-line estimate: detours, jumps and penalties of
         * this area. Applied to the estimate after a warp too, so both sides use one measure.
         * (Comparing the real path cost with a bare straight-line estimate after the warp made
         * every teleport look like a saving - even one straight down onto the take-off spot.)
         */
        double ratio = 1.0;
        /** Jump cost and height for the walking search (set by AotvAssist from the player's stats). */
        public double jumpTicks = 8.0;
        /** Real walking search of this planning run (around walls, not through them). */
        public WalkSearch walk;
        /** Walking from here in vanilla ticks, as {@link WalkSearch} or Baritone measured it. */
        double baselineVanilla;
    }

    /** Candidates (ranked by the estimate) that get the real walking search. */
    private static final int VERIFY = 6;

    /** A finished route before its walk was checked: hops, their time and where we land. */
    private record Draft(List<Hop> hops, double warpTicks, BlockPos landingFeet, double estimate) {
    }

    /** Vanilla ticks a warp has to bring us closer at least (about 4 blocks). */
    private static final double MIN_PROGRESS = 4 * 3.6 * 1.25;

    /** Fills the derived values; false when no warp makes sense (already at the goal). */
    private static boolean prepare(Context c) {
        c.startEstimate = walkTicks(c, BlockPos.containing(c.feet));
        if (c.startEstimate <= MIN_PROGRESS) {
            return false;
        }
        if (c.walk == null) {
            c.walk = newWalk(c);
        }
        // the same measure as for the spots after a warp; Baritone's path cost only when the
        // search does not reach the goal from here (far away, in the air)
        double real = c.onGround ? c.walk.ticksToGoal(BlockPos.containing(c.feet)) : Double.NaN;
        c.baselineVanilla = Double.isNaN(real) ? c.walkVanillaTicks : Math.min(real, c.walkVanillaTicks);
        c.ratio = Mth.clamp(c.baselineVanilla / c.startEstimate, 1.0, 4.0);
        return true;
    }

    /**
     * Checks the best drafts with the real walking search: a landing spot from which the goal
     * can not be walked to (on a wall, behind one) is dropped, the others get their real walk.
     */
    private static Route verify(Context c, List<Draft> drafts) {
        drafts.sort(Comparator.comparingDouble(Draft::estimate));
        double walkNow = walkNow(c);
        Route best = null;
        for (int i = 0; i < drafts.size() && i < VERIFY; i++) {
            Draft d = drafts.get(i);
            double walk = c.walk.ticksToGoal(d.landingFeet());
            if (Double.isNaN(walk)) {
                continue;
            }
            double total = d.warpTicks() + walk * c.speedFactor;
            if (best == null || total < best.ticks()) {
                best = new Route(d.hops(), total, walkNow);
            }
        }
        return best;
    }

    /** A walking search for this context's goal and the player's jump. */
    public static WalkSearch newWalk(Context c) {
        int maxUp = Mth.clamp((int) Math.floor(c.jumpHeight - 0.05), 1, 3);
        return new WalkSearch(c.level, c.goal, new WalkSearch.Settings(maxUp, c.jumpTicks, 1500));
    }

    /** Real ticks of walking left after a warp that puts the feet at {@code feet}. */
    private static double walkAfter(Context c, BlockPos feet) {
        return walkTicks(c, feet) * c.ratio * c.speedFactor;
    }

    /** The warp brings us clearly closer to the goal. */
    private static boolean progresses(Context c, BlockPos feet) {
        return walkTicks(c, feet) <= c.startEstimate - MIN_PROGRESS;
    }

    /** Walking now in real ticks: the rest of the path, plus the air time of a running jump. */
    private static double walkNow(Context c) {
        return c.baselineVanilla * c.speedFactor + c.airTicksLeft;
    }

    private WarpPlanner() {
    }

    // ================================================================ stage 1: etherwarp

    private record Node(BlockPos block, Vec3 eye, Vec3 lookDir, double g, List<Hop> hops) {
    }

    public static Optional<Route> etherwarp(Context c) {
        if (c.goal == null || c.level == null || !prepare(c)) {
            return Optional.empty();
        }
        List<BlockPos> candidates = etherCandidates(c);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<Draft> drafts = new ArrayList<>();
        Vec3 startLook = Vec3.directionFromRotation(c.pitch, c.yaw);
        List<Node> frontier = List.of(new Node(null, c.eye, startLook, 0, List.of()));
        for (int depth = 1; depth <= Math.max(1, Math.min(3, c.maxChain)); depth++) {
            List<Node> next = new ArrayList<>();
            for (Node from : frontier) {
                for (BlockPos target : candidates) {
                    if (from.block() != null && from.block().equals(target)) {
                        continue;
                    }
                    // first hop: also the corners of the block top; later hops only its centre
                    Vec3 aim = visibleAim(c.level, from.eye(), target, depth == 1);
                    if (aim == null) {
                        continue;
                    }
                    Vec3 dir = aim.subtract(from.eye()).normalize();
                    double g = from.g() + ETHER_HANDLING_TICKS + turnTicks(c, from.lookDir(), dir)
                            + c.etherwarpExtraTicks;
                    Vec3 landing = new Vec3(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5);
                    List<Hop> hops = new ArrayList<>(from.hops());
                    hops.add(new Hop(Kind.ETHERWARP, aim, landing, false));
                    // a route has to end clearly closer; a hop that does not (out of a cave,
                    // up onto a roof) may still lead to a better second hop
                    if (progresses(c, target.above())) {
                        drafts.add(new Draft(List.copyOf(hops), g, target.above(), g + walkAfter(c, target.above())));
                    }
                    next.add(new Node(target, landing.add(0, EYE, 0), dir, g, hops));
                }
            }
            if (depth >= c.maxChain || next.isEmpty()) {
                break;
            }
            // expand only the most promising nodes (cheapest so far + what is left to walk)
            int keep = depth == 1 ? TOP_K_SECOND : TOP_K_THIRD;
            next.sort(Comparator.comparingDouble(n -> n.g() + walkAfter(c, n.block().above())));
            frontier = next.subList(0, Math.min(keep, next.size()));
        }
        return Optional.ofNullable(verify(c, drafts));
    }

    /**
     * Landing candidates: standable blocks around the goal, every other block of the planned
     * path, and what a fan of rays from the eye towards the goal hits (ways out of caves,
     * ledges, roofs).
     */
    private static List<BlockPos> etherCandidates(Context c) {
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos center : goalCenters(c.goal)) {
            List<BlockPos> near = new ArrayList<>();
            for (int dx = -GOAL_SCAN_RADIUS; dx <= GOAL_SCAN_RADIUS; dx++) {
                for (int dz = -GOAL_SCAN_RADIUS; dz <= GOAL_SCAN_RADIUS; dz++) {
                    for (int dy = -GOAL_SCAN_RADIUS; dy <= GOAL_SCAN_RADIUS; dy++) {
                        BlockPos pos = center.offset(dx, dy - 1, dz);
                        if (validTarget(c.level, pos)) {
                            near.add(pos);
                        }
                    }
                }
            }
            near.sort(Comparator.comparingDouble(p -> walkTicks(c, p.above())));
            for (int i = 0; i < near.size() && i < 40; i++) {
                if (seen.add(near.get(i))) {
                    out.add(near.get(i));
                }
            }
        }
        for (int i = 2; i < c.path.size(); i += 2) {
            BlockPos below = c.path.get(i).below();
            if (seen.add(below) && validTarget(c.level, below)) {
                out.add(below);
            }
        }
        Vec3 toGoal = null;
        for (BlockPos center : goalCenters(c.goal)) {
            toGoal = Vec3.atCenterOf(center).subtract(c.eye);
            break;
        }
        if (toGoal != null && toGoal.lengthSqr() > 1.0E-6) {
            float baseYaw = (float) (Math.toDegrees(Math.atan2(toGoal.z, toGoal.x)) - 90.0);
            for (int yawOff = -60; yawOff <= 60; yawOff += 15) {
                for (int pitch = -70; pitch <= 50; pitch += 15) {
                    Vec3 dir = Vec3.directionFromRotation(pitch, baseYaw + yawOff);
                    EtherwarpHelper.raycast(c.level, c.eye, dir, EtherwarpHelper.MAX_RANGE).ifPresent(hit -> {
                        BlockPos pos = hit.pos();
                        if (seen.add(pos) && validTarget(c.level, pos)) {
                            out.add(pos);
                        }
                    });
                }
            }
        }
        // keep the planning cheap: the candidates closest (in walking) to the goal
        if (out.size() > MAX_CANDIDATES) {
            out.sort(Comparator.comparingDouble(p -> walkTicks(c, p.above())));
            return new ArrayList<>(out.subList(0, MAX_CANDIDATES));
        }
        return out;
    }

    private static boolean validTarget(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !EtherwarpHelper.isPassThrough(level, pos, state) && EtherwarpHelper.isValidTarget(level, pos);
    }

    /** A point on the top of {@code target} the etherwarp ray from {@code eye} really hits, or null. */
    private static Vec3 visibleAim(Level level, Vec3 eye, BlockPos target, boolean corners) {
        double[][] offsets = corners
                ? new double[][]{{0.5, 0.5}, {0.25, 0.25}, {0.75, 0.75}, {0.25, 0.75}, {0.75, 0.25}}
                : new double[][]{{0.5, 0.5}};
        for (double[] o : offsets) {
            Vec3 aim = new Vec3(target.getX() + o[0], target.getY() + 0.95, target.getZ() + o[1]);
            double dist = aim.distanceTo(eye);
            if (dist > EtherwarpHelper.MAX_RANGE || dist < 1.5) {
                return null;
            }
            Optional<EtherwarpHelper.RayHit> hit = EtherwarpHelper.raycast(level, eye, aim.subtract(eye),
                    EtherwarpHelper.MAX_RANGE);
            if (hit.isPresent() && hit.get().pos().equals(target)) {
                return aim;
            }
        }
        return null;
    }

    // ================================================================ stage 2: teleport

    /** Pitches tried for a teleport: up onto higher ground, level, and down onto the floor. */
    private static final int[] TELEPORT_PITCHES = {-50, -35, -22, -12, -5, 0, 5, 10, 16, 25, 40, 60, 89};

    public static Optional<Route> teleport(Context c) {
        if (c.goal == null || c.level == null || c.teleportReach < 4 || !prepare(c)) {
            return Optional.empty();
        }
        Vec3 look = Vec3.directionFromRotation(c.pitch, c.yaw);
        List<Draft> drafts = new ArrayList<>();
        for (Vec3 point : teleportTargets(c)) {
            Vec3 flat = new Vec3(point.x - c.feet.x, 0, point.z - c.feet.z);
            if (flat.lengthSqr() < 1.0) {
                continue;
            }
            float yaw = (float) (Math.toDegrees(Math.atan2(flat.z, flat.x)) - 90.0);
            // from standing (or where we are in the air) and, on the ground, from the top of a jump
            for (int variant = 0; variant < (c.onGround ? 2 : 1); variant++) {
                boolean jump = variant == 1;
                double lift = jump ? Math.min(c.jumpHeight, 4.0) : 0.0;
                Vec3 eye = c.feet.add(0, lift + EYE, 0);
                for (int pitch : TELEPORT_PITCHES) {
                    Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
                    Vec3 landed = simulateTeleport(c.level, eye, dir, c.teleportReach);
                    if (landed == null || landed.distanceToSqr(c.feet) < 9.0) {
                        continue;
                    }
                    Vec3 ground = fallToGround(c.level, landed);
                    if (ground == null) {
                        continue;
                    }
                    BlockPos groundFeet = BlockPos.containing(ground);
                    if (!progresses(c, groundFeet)) {
                        continue; // e.g. straight down onto the take-off spot
                    }
                    double jumpTicks = jump ? airTicksUp(lift) + 2 : 0;
                    double warp = TELEPORT_HANDLING_TICKS + turnTicks(c, look, dir) + c.teleportExtraTicks
                            + jumpTicks + fallTicks(landed.y - ground.y);
                    Vec3 aimPoint = eye.add(dir.scale(c.teleportReach));
                    drafts.add(new Draft(List.of(new Hop(Kind.TELEPORT, aimPoint, ground, jump)), warp,
                            groundFeet, warp + walkAfter(c, groundFeet)));
                }
            }
        }
        return Optional.ofNullable(verify(c, drafts));
    }

    /**
     * Points worth teleporting towards: path points up to one and a half teleports ahead (in
     * the air also the close ones - the landing block of the running jump) and the goal.
     */
    private static List<Vec3> teleportTargets(Context c) {
        List<Vec3> out = new ArrayList<>();
        double reach = c.teleportReach;
        double min = c.onGround ? reach * 0.5 : 2.0;
        for (BetterBlockPos pos : c.path) {
            double d = Math.hypot(pos.getX() + 0.5 - c.feet.x, pos.getZ() + 0.5 - c.feet.z);
            if (d >= min && d <= reach * 1.6) {
                out.add(new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
            }
        }
        for (BlockPos center : goalCenters(c.goal)) {
            out.add(Vec3.atBottomCenterOf(center));
        }
        // not too many directions: a dozen spread over the candidates is enough
        if (out.size() > 12) {
            List<Vec3> thin = new ArrayList<>();
            for (int i = 0; i < out.size(); i += Math.max(1, out.size() / 12)) {
                thin.add(out.get(i));
            }
            thin.add(out.get(out.size() - 1));
            return thin;
        }
        return out;
    }

    /**
     * Where an AOTV teleport puts the feet, following Hypixel's rule as seen in the path
     * recorder: a ray from the <b>eyes</b> along the look, block by block, as long as the block
     * at the ray point and the block below it are free; the feet end up one block below the
     * last free ray point, centred on that block. Looking down from 6 blocks up lands on the
     * floor below; looking 9 degrees down from standing stops where the ray gets within a block
     * of the ground. Null when the first step is blocked ("There are blocks in the way!").
     */
    public static Vec3 simulateTeleport(Level level, Vec3 eye, Vec3 dir, double reach) {
        Vec3 unit = dir.normalize();
        BlockPos last = null;
        for (double t = 0.5; t <= reach + 1.0E-6; t += 0.5) {
            Vec3 p = eye.add(unit.scale(t));
            BlockPos at = BlockPos.containing(p);
            if (!free(level, at) || !free(level, at.below())) {
                break;
            }
            last = at;
        }
        if (last == null) {
            return null;
        }
        return new Vec3(last.getX() + 0.5, last.getY() - 1.0, last.getZ() + 0.5);
    }

    private static boolean free(Level level, BlockPos pos) {
        if (!level.isInWorldBounds(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty()
                && !state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
    }

    /** Feet position after falling from {@code feet}, null over a deep drop, lava or the void. */
    private static Vec3 fallToGround(Level level, Vec3 feet) {
        BlockPos pos = BlockPos.containing(feet);
        for (int i = 0; i <= MAX_FALL; i++) {
            BlockPos below = pos.below(i + 1);
            if (!level.isInWorldBounds(below)) {
                return null;
            }
            BlockState state = level.getBlockState(below);
            if (!state.getFluidState().isEmpty() && !state.getFluidState().isSource()) {
                continue;
            }
            if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                return null;
            }
            if (!state.getCollisionShape(level, below).isEmpty()) {
                return new Vec3(feet.x, below.getY() + 1.0, feet.z);
            }
        }
        return null;
    }

    // ================================================================ costs

    /** Vanilla-tick walking estimate from {@code feet} to the goal (detour margin included). */
    static double walkTicks(Context c, BlockPos feet) {
        if (c.goal.isInGoal(feet)) {
            return 0;
        }
        double best = Double.MAX_VALUE;
        for (BlockPos center : goalCenters(c.goal)) {
            double flat = Math.hypot(center.getX() - feet.getX(), center.getZ() - feet.getZ());
            double up = Math.max(0, center.getY() - feet.getY());
            double down = Math.max(0, feet.getY() - center.getY());
            double ticks = (Math.max(0, flat - 2.0) * 3.6 + up * 8.0 + down * 1.5) * 1.25;
            best = Math.min(best, ticks);
        }
        return best == Double.MAX_VALUE ? c.goal.heuristic(feet) : best;
    }

    private static double turnTicks(Context c, Vec3 from, Vec3 to) {
        double cos = Mth.clamp(from.normalize().dot(to.normalize()), -1.0, 1.0);
        double degrees = Math.toDegrees(Math.acos(cos));
        return degrees / Math.max(60.0, c.turnSpeed) * 20.0 + 2.0;
    }

    static double fallTicks(double height) {
        if (height <= 0.1) {
            return 0;
        }
        double y = 0;
        double v = 0;
        for (int tick = 1; tick < 100; tick++) {
            v = (v - 0.08) * 0.98;
            y += v;
            if (-y >= height) {
                return tick;
            }
        }
        return 100;
    }

    private static double airTicksUp(double height) {
        double y = 0;
        double v = 0.42 + Math.max(0, height - 1.25) * 0.1;
        for (int tick = 1; tick < 60; tick++) {
            y += v;
            v = (v - 0.08) * 0.98;
            if (v <= 0 || y >= height) {
                return tick;
            }
        }
        return 20;
    }

    /** Centres of the goal (GoalNear / GoalBlock / VisibleGoal, also inside a GoalComposite). */
    public static List<BlockPos> goalCenters(Goal goal) {
        List<BlockPos> out = new ArrayList<>();
        collectCenters(goal, out, 0);
        return out;
    }

    private static void collectCenters(Goal goal, List<BlockPos> out, int depth) {
        if (goal == null || depth > 3) {
            return;
        }
        if (goal instanceof GoalComposite composite) {
            for (Goal inner : composite.goals()) {
                collectCenters(inner, out, depth + 1);
            }
        } else if (goal instanceof IGoalRenderPos render) {
            out.add(render.getGoalPos());
        }
    }
}
