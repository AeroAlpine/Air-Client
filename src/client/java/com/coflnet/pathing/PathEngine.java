package com.coflnet.pathing;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementWarp;
import baritone.utils.pathing.Favoring;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One path, planned once and then followed - instead of planning again all the time.
 * <ol>
 *   <li><b>Plan</b>: Baritone's A* computes the whole way to the goal (as far as the loaded world
 *       allows; a part path is extended before we reach its end - not planned again).</li>
 *   <li><b>Prepare</b>, before walking: the block-by-block path is lifted onto a line and pulled
 *       tight (corners cut where the player fits, half slabs and stairs just run over). Jumps
 *       become fixed take-off points. Then the warps are put in: the player's etherwarp points
 *       first, an etherwarp over complicated parts (jumps, climbs, detours), teleports along
 *       long straight parts.</li>
 *   <li><b>Follow</b>: forward + camera on a point ahead on the line, a little strafing back to
 *       the middle. Speed and exact height do not matter. Off the line we are steered back;
 *       only far away (or really stuck) the path is planned again.</li>
 * </ol>
 * Baritone itself never walks here - it is only the planner.
 */
public final class PathEngine {
    public enum Result { IDLE, PLANNING, RUNNING, ARRIVED, FAILED }

    private enum Act { WALK, JUMP, TELEPORT, ETHERWARP }

    private record Way(Vec3 pos, Act act, Vec3 aim, boolean jumpFirst, List<Way> fallback) {
        Way(Vec3 pos, Act act) {
            this(pos, act, null, false, List.of());
        }
    }

    /** kinds: 0 walk, 1 jump, 2 teleport, 3 etherwarp (how each point is reached); dirs for teleports. */
    private record Raw(List<Vec3> points, List<Boolean> jumps, List<Integer> kinds, List<Vec3> dirs,
                       boolean partial, boolean failed) {
    }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "path-engine");
        t.setDaemon(true);
        return t;
    });
    private static final double LOOKAHEAD = 3.2;
    private static final double OFF_LINE = 1.6;
    private static final double REPLAN_OFF = 6.0;
    private static final double EYE = 1.62;
    private static volatile List<BlockPos> etherwarpPoints = List.of();
    /** The engine that walked last (for the overlay). */
    private static volatile PathEngine shown;
    private static volatile long shownAt;

    private BlockPos goal;
    private double radius;
    private boolean warps;
    private List<Way> ways = new ArrayList<>();
    private int w;
    private CompletableFuture<Raw> pending;
    private enum Pending { NONE, FRESH, EXTEND }
    private Pending pendingKind = Pending.NONE;
    private boolean partial;
    private boolean planFailed;
    private long progressAt;
    private double bestRemaining = Double.MAX_VALUE;
    private int replans;
    private Vec3 look;
    private boolean ownsKeys;
    private int jumpTicks;
    private Vec3 hopLanding;
    private int hopIndex;
    private long hopAt;
    private int hopFails;
    private boolean nudged;
    private String status = "";

    /** The player's etherwarp points (Dwarven Mines): used when they are on the way. */
    public static void setEtherwarpPoints(List<BlockPos> points) {
        etherwarpPoints = points == null ? List.of() : List.copyOf(points);
        int[][] raw = new int[etherwarpPoints.size()][];
        for (int i = 0; i < raw.length; i++) {
            BlockPos p = etherwarpPoints.get(i);
            raw[i] = new int[]{p.getX(), p.getY(), p.getZ()};
        }
        MovementWarp.etherwarpPoints = raw;
    }

    public String status() {
        return status;
    }

    /** Where the camera should look (null while not walking). */
    public Vec3 lookPoint() {
        return look;
    }

    /** Planning or walking. */
    public boolean busy() {
        return goal != null;
    }

    /**
     * Go to {@code target}. The same goal again does nothing; a moved goal (a mob) is planned in
     * the background while we keep walking the old path.
     */
    public void goTo(BlockPos target, double radius, boolean allowWarps) {
        if (target == null) return;
        // the same goal again (a mob that moved a few blocks counts as the same): keep the plan
        if (goal != null && allowWarps == warps && goal.distSqr(target) <= (ways.isEmpty() ? 2 : 16)) return;
        boolean moving = goal != null && !ways.isEmpty();
        goal = target;
        this.radius = Math.max(1.0, radius);
        warps = allowWarps;
        replans = 0;
        if (!moving) {
            ways = new ArrayList<>();
            w = 0;
        }
        plan(Pending.FRESH);
    }

    public void stop() {
        releaseKeys(Minecraft.getInstance());
        goal = null;
        ways = new ArrayList<>();
        pending = null;
        pendingKind = Pending.NONE;
        look = null;
        hopLanding = null;
        status = "";
    }

    public void releaseKeys(Minecraft mc) {
        if (!ownsKeys || mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
        ownsKeys = false;
    }

    // ------------------------------------------------------------------ planning

    private void plan(Pending kind) {
        Minecraft mc = Minecraft.getInstance();
        IBaritone baritone = primary();
        if (mc.player == null || baritone == null || goal == null) return;
        BetterBlockPos start;
        if (kind == Pending.EXTEND && !ways.isEmpty()) {
            start = new BetterBlockPos(BlockPos.containing(ways.get(ways.size() - 1).pos().add(0, 0.5, 0)));
        } else {
            start = new BetterBlockPos(mc.player.blockPosition());
        }
        configure();
        // warps and long jumps are moves of the search itself - only for this plan
        baritone.api.Settings s = BaritoneAPI.getSettings();
        boolean oldTp = s.allowTeleportMoves.value, oldEw = s.allowEtherwarpMoves.value;
        boolean oldPk = s.allowParkour.value, oldPkUp = s.allowParkourAscend.value;
        int reach = AotvAssist.teleportReach(mc.player);
        int mana = com.coflnet.mining.MiningState.mana();
        boolean manaOk = mana < 0 || mana >= 60;
        s.allowTeleportMoves.value = warps && manaOk && reach > 0;
        s.allowEtherwarpMoves.value = warps && manaOk && AotvAssist.canEtherwarp(mc.player);
        s.teleportMoveReach.value = Math.max(4, reach);
        s.allowParkour.value = true;
        s.allowParkourAscend.value = true;
        CalculationContext context;
        try {
            context = new CalculationContext(baritone, true);
        } finally {
            s.allowTeleportMoves.value = oldTp;
            s.allowEtherwarpMoves.value = oldEw;
            s.allowParkour.value = oldPk;
            s.allowParkourAscend.value = oldPkUp;
        }
        Favoring favoring = new Favoring(baritone.getPlayerContext(), null, context);
        GoalNear target = new GoalNear(goal, (int) Math.max(1, Math.floor(radius)));
        AStarPathFinder finder = new AStarPathFinder(start, start.getX(), start.getY(), start.getZ(), target, favoring, context);
        Level level = mc.level;
        pendingKind = kind;
        pending = CompletableFuture.supplyAsync(() -> {
            PathCalculationResult result = finder.calculate(2_500L, 6_000L);
            IPath path = result.getPath().orElse(null);
            if (path == null || path.positions().size() < 2) {
                return new Raw(List.of(), List.of(), List.of(), List.of(), false, true);
            }
            List<BetterBlockPos> positions = path.positions();
            List<IMovement> moves = path.movements();
            List<Vec3> points = new ArrayList<>();
            List<Boolean> jumps = new ArrayList<>();
            List<Integer> kinds = new ArrayList<>();
            List<Vec3> dirs = new ArrayList<>();
            for (int i = 0; i < positions.size(); i++) {
                BetterBlockPos p = positions.get(i);
                points.add(new Vec3(p.getX() + 0.5, LineFollower.groundTop(level, p), p.getZ() + 0.5));
                boolean jump = false;
                int how = 0;
                Vec3 dir = null;
                if (i > 0) {
                    BetterBlockPos q = positions.get(i - 1);
                    IMovement move = i - 1 < moves.size() ? moves.get(i - 1) : null;
                    if (move instanceof MovementWarp warp) {
                        how = warp.kind == MovementWarp.Kind.TELEPORT ? 2 : 3;
                        dir = Vec3.directionFromRotation(warp.pitch, warp.yaw);
                    } else {
                        String type = move == null ? "" : move.getClass().getSimpleName();
                        jump = p.getY() > q.getY() || type.contains("Parkour") || type.contains("Pillar")
                                || Math.abs(p.getX() - q.getX()) + Math.abs(p.getZ() - q.getZ()) > 2;
                        how = jump ? 1 : 0;
                    }
                }
                jumps.add(jump || how >= 2);
                kinds.add(how);
                dirs.add(dir);
            }
            boolean toGoal = result.getType() == PathCalculationResult.Type.SUCCESS_TO_GOAL;
            return new Raw(points, jumps, kinds, dirs, !toGoal, false);
        }, POOL);
        status = "Planning";
    }

    private static void configure() {
        baritone.api.Settings s = BaritoneAPI.getSettings();
        s.allowBreak.value = false;
        s.allowPlace.value = false;
        s.allowInventory.value = false;
        s.antiCheatCompatibility.value = true;
        s.maxFallHeightNoWater.value = Math.max(s.maxFallHeightNoWater.value, 20);
        PathfinderTuning.apply(s, false);
    }

    /** Lift the path onto a tight line with jump points, then put the warps in. */
    private List<Way> prepare(Minecraft mc, Raw raw, Vec3 from) {
        Level level = mc.level;
        List<Vec3> pts = raw.points();
        List<Way> line = new ArrayList<>();
        line.add(new Way(from, Act.WALK));
        int i = 0;
        while (i < pts.size() - 1) {
            int kind = raw.kinds().get(i + 1);
            if (kind == 2 || kind == 3) {
                // a warp Baritone planned: from the point before onto this one
                line.add(new Way(pts.get(i + 1), kind == 2 ? Act.TELEPORT : Act.ETHERWARP, raw.dirs().get(i + 1), false,
                        List.of(new Way(pts.get(i + 1), Act.WALK))));
                i++;
                continue;
            }
            if (raw.jumps().get(i + 1)) {
                line.add(new Way(pts.get(i + 1), Act.JUMP)); // take-off is the point before
                i++;
                continue;
            }
            int j = i + 1;
            Vec3 a = i == 0 ? from : pts.get(i);
            for (int k = Math.min(pts.size() - 1, i + 24); k > i + 1; k--) {
                boolean jumpInside = false;
                for (int m = i + 1; m <= k; m++) {
                    if (raw.jumps().get(m)) {
                        jumpInside = true;
                        break;
                    }
                }
                if (!jumpInside && LineFollower.walkable(level, a, pts.get(k))) {
                    j = k;
                    break;
                }
            }
            line.add(new Way(pts.get(j), Act.WALK));
            i = j;
        }
        return round(level, line); // the warps are already part of Baritone's plan
    }

    /**
     * Corners of the walking line become arcs (a curve from ~2.5 blocks before the corner to
     * ~2.5 after it, smaller where the room is tight, the corner itself where even that does not
     * fit). Following an arc is one smooth camera turn instead of a stop-and-turn at the corner.
     */
    private static List<Way> round(Level level, List<Way> line) {
        if (line.size() < 3) return line;
        List<Way> out = new ArrayList<>();
        out.add(line.get(0));
        for (int i = 1; i < line.size(); i++) {
            Way cur = line.get(i);
            Way next = i + 1 < line.size() ? line.get(i + 1) : null;
            if (next == null || cur.act() != Act.WALK || next.act() != Act.WALK) {
                out.add(cur);
                continue;
            }
            Vec3 a = out.get(out.size() - 1).pos(), c = cur.pos(), b = next.pos();
            double lin = flat(a, c), lout = flat(c, b);
            if (lin < 0.8 || lout < 0.8) {
                out.add(cur);
                continue;
            }
            double ix = (c.x - a.x) / lin, iz = (c.z - a.z) / lin;
            double ox = (b.x - c.x) / lout, oz = (b.z - c.z) / lout;
            double turn = Math.acos(Math.clamp(ix * ox + iz * oz, -1.0, 1.0));
            if (turn < Math.toRadians(10)) {
                out.add(cur);
                continue;
            }
            List<Vec3> arc = null;
            double d = Math.min(2.5, Math.min(lin * 0.48, lout * 0.48));
            for (int tries = 0; tries < 3 && arc == null && d >= 0.45; tries++, d *= 0.6) {
                Vec3 t1 = new Vec3(c.x - ix * d, c.y + (a.y - c.y) * (d / lin), c.z - iz * d);
                Vec3 t2 = new Vec3(c.x + ox * d, c.y + (b.y - c.y) * (d / lout), c.z + oz * d);
                int n = turn > 1.2 ? 7 : turn > 0.6 ? 5 : 3;
                List<Vec3> pts = new ArrayList<>();
                for (int k = 0; k <= n; k++) {
                    double t = k / (double) n, u = 1 - t;
                    pts.add(new Vec3(u * u * t1.x + 2 * u * t * c.x + t * t * t2.x,
                            u * u * t1.y + 2 * u * t * c.y + t * t * t2.y,
                            u * u * t1.z + 2 * u * t * c.z + t * t * t2.z));
                }
                boolean ok = true;
                for (int k = 0; k + 1 < pts.size() && ok; k++) {
                    ok = LineFollower.walkable(level, pts.get(k), pts.get(k + 1));
                }
                if (ok) arc = pts;
            }
            if (arc == null) {
                out.add(cur);
            } else {
                for (Vec3 p : arc) out.add(new Way(p, Act.WALK));
            }
        }
        return out;
    }

    private List<Way> addWarps(Minecraft mc, List<Way> line) {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        int reach = AotvAssist.teleportReach(player);
        boolean ether = AotvAssist.canEtherwarp(player);
        int mana = com.coflnet.mining.MiningState.mana();
        if ((reach <= 0 && !ether) || (mana >= 0 && mana < 60)) return line;
        List<Way> out = new ArrayList<>();
        out.add(line.get(0));
        int a = 0;
        Vec3 end = line.get(line.size() - 1).pos();
        while (a < line.size() - 1) {
            Vec3 at = line.get(a).pos();
            Vec3 eye = at.add(0, EYE, 0);
            Way hop = null;
            int hopTo = -1;
            // 1. the player's own etherwarp points, when the path passes close to them
            if (ether) {
                for (BlockPos p : etherwarpPoints) {
                    Vec3 land = Vec3.atBottomCenterOf(p.above());
                    if (land.distanceTo(eye) > 45 || land.distanceTo(at) < 4) continue;
                    int c = -1;
                    for (int k = a + 1; k < line.size(); k++) {
                        if (flat(line.get(k).pos(), land) < 6 && Math.abs(line.get(k).pos().y - land.y) < 4) c = k;
                    }
                    if (c < 0 || along(line, a, c) - land.distanceTo(at) < 6) continue;
                    if (land.distanceTo(end) >= at.distanceTo(end)) continue;
                    Vec3 aim = WarpPlanner.etherwarpAim(level, eye, p, true);
                    boolean jump = false;
                    if (aim == null) {
                        aim = WarpPlanner.etherwarpAim(level, eye.add(0, 1.1, 0), p, true);
                        jump = aim != null;
                    }
                    if (aim != null && c > hopTo) {
                        hop = new Way(new Vec3(aim.x, p.getY() + 1.0, aim.z), Act.ETHERWARP, aim, jump,
                                List.copyOf(line.subList(a + 1, c + 1)));
                        hopTo = c;
                    }
                }
            }
            // 2. an etherwarp over a complicated part (jumps, a climb, a detour)
            if (hop == null && ether) {
                for (int b = line.size() - 1; b > a + 1; b--) {
                    double al = along(line, a, b);
                    if (al > 40) continue;
                    Vec3 t = line.get(b).pos();
                    double straight = t.distanceTo(at);
                    if (straight < 5 || al < 6) continue;
                    boolean jumps = false;
                    for (int m = a + 1; m <= b; m++) jumps |= line.get(m).act() == Act.JUMP;
                    if (!jumps && t.y - at.y < 2.0 && al < straight * 1.5) continue;
                    BlockPos floor = BlockPos.containing(t.x, t.y - 0.3, t.z);
                    Vec3 aim = WarpPlanner.etherwarpAim(level, eye, floor, true);
                    if (aim == null) continue;
                    hop = new Way(new Vec3(aim.x, floor.getY() + 1.0, aim.z), Act.ETHERWARP, aim, false,
                            List.copyOf(line.subList(a + 1, b + 1)));
                    hopTo = b;
                    break;
                }
            }
            // 3. a teleport along a long straight part
            if (hop == null && reach > 0) {
                for (int b = line.size() - 1; b > a; b--) {
                    Vec3 t = line.get(b).pos();
                    double straight = t.distanceTo(at);
                    if (straight > reach + 0.5 || along(line, a, b) < 7 || t.y > at.y + 1.5) continue;
                    Vec3 dir = t.add(0, EYE, 0).subtract(eye).normalize();
                    Vec3 land = WarpPlanner.simulateTeleport(level, eye, dir, reach);
                    if (land == null || flat(land, t) > 1.6 || Math.abs(land.y - t.y) > 1.5) continue;
                    hop = new Way(land, Act.TELEPORT, eye.add(dir.scale(4)), false, List.copyOf(line.subList(a + 1, b + 1)));
                    hopTo = b;
                    break;
                }
            }
            if (hop != null) {
                out.add(hop);
                a = hopTo;
                continue;
            }
            out.add(line.get(a + 1));
            a++;
        }
        return out;
    }

    // ------------------------------------------------------------------ following

    /**
     * The planned line ahead for drawing: {x, y, z, kind} per point (kind 0 walk, 1 jump, 2 teleport,
     * 3 etherwarp), starting at the player. Empty when nothing walks.
     */
    public static List<double[]> overlay(Vec3 from) {
        PathEngine e = shown;
        if (e == null || e.goal == null || System.currentTimeMillis() - shownAt > 500L) return List.of();
        List<Way> ways = e.ways;
        List<double[]> out = new ArrayList<>();
        out.add(new double[]{from.x, from.y, from.z, 0});
        for (int i = Math.max(1, e.w); i < ways.size() && out.size() < 64; i++) {
            Way x = ways.get(i);
            out.add(new double[]{x.pos().x, x.pos().y, x.pos().z, x.act().ordinal()});
        }
        return out;
    }

    public Result tick(Minecraft mc) {
        shown = this;
        shownAt = System.currentTimeMillis();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || goal == null) {
            releaseKeys(mc);
            return Result.IDLE;
        }
        long now = System.currentTimeMillis();
        Vec3 feet = player.position();
        // a plan came back
        if (pending != null && pending.isDone()) {
            Raw raw = pending.join();
            Pending kind = pendingKind;
            pending = null;
            pendingKind = Pending.NONE;
            if (raw.failed()) {
                if (ways.isEmpty() || w >= ways.size()) {
                    planFailed = true;
                }
            } else if (kind == Pending.EXTEND && !ways.isEmpty()) {
                List<Way> more = prepare(mc, raw, ways.get(ways.size() - 1).pos());
                ways.addAll(more.subList(1, more.size()));
                partial = raw.partial();
            } else {
                ways = prepare(mc, raw, feet);
                w = 1;
                partial = raw.partial();
                planFailed = false;
                hopLanding = null;
                progressAt = now;
                bestRemaining = Double.MAX_VALUE;
                nudged = false;
                System.out.println("[PathEngine] " + describe() + (partial ? " (part, extended later)" : ""));
            }
        }
        if (flat(feet, Vec3.atBottomCenterOf(goal)) <= radius && Math.abs(feet.y - goal.getY()) <= 2.5 && player.onGround()) {
            releaseKeys(mc);
            look = null;
            status = "Arrived";
            return Result.ARRIVED;
        }
        if (ways.size() < 2) {
            releaseKeys(mc);
            look = null;
            if (planFailed) {
                status = "No way found";
                return Result.FAILED;
            }
            status = "Planning";
            return Result.PLANNING;
        }
        if (AotvAssist.isBusy()) {
            releaseKeys(mc);
            look = null;
            status = "Warping";
            progressAt = now;
            return Result.RUNNING;
        }
        if (hopLanding != null) {
            // wherever the warp put us: go on from the nearest point of the path ahead
            int on = -1;
            if (now - hopAt > 250L) {
                for (int k = Math.min(ways.size() - 1, hopIndex + 30); k >= hopIndex; k--) {
                    Vec3 p = ways.get(k).pos();
                    if (flat(feet, p) < 3.5 && Math.abs(feet.y - p.y) < 3.0) {
                        on = k;
                        break;
                    }
                }
            }
            if (on >= 0) {
                w = on + 1;
                hopLanding = null;
                hopFails = 0;
                progressAt = now;
                bestRemaining = Double.MAX_VALUE;
            } else if (now - hopAt > 1_500L && feet.distanceTo(ways.get(hopIndex - 1 >= 0 ? hopIndex - 1 : 0).pos()) > 3.0) {
                hopLanding = null; // moved, but off the path: plan from here
                return replan(mc, "warp landed off the path");
            } else if (now - hopAt > 1_500L) {
                hopLanding = null;
                if (++hopFails >= 2) {
                    // walk what the warp would have skipped
                    Way h = ways.get(hopIndex);
                    ways.remove(hopIndex);
                    ways.addAll(hopIndex, h.fallback().isEmpty() ? List.of(new Way(h.pos(), Act.WALK)) : h.fallback());
                    hopFails = 0;
                    System.out.println("[PathEngine] warp did not work, walking that part");
                }
            } else {
                return Result.RUNNING;
            }
        }
        // extend a part path well before its end
        if (partial && pending == null && along(ways, Math.max(0, w - 1), ways.size() - 1) < 30) {
            plan(Pending.EXTEND);
        }
        if (w >= ways.size()) {
            if (pending == null) {
                if (partial) plan(Pending.EXTEND);
                else plan(Pending.FRESH); // end of the path but not at the goal
            }
            releaseKeys(mc);
            status = "Planning";
            return Result.RUNNING;
        }
        Way cur = ways.get(w);
        Vec3 prev = ways.get(w - 1).pos();
        if (cur.act() == Act.TELEPORT || cur.act() == Act.ETHERWARP) {
            double speed = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z);
            if (flat(feet, prev) <= 1.3 && player.onGround() && speed > 0.08) {
                // stand still first: a warp fired while sliding on lands somewhere else
                releaseKeys(mc);
                look = lookFor(player, cur);
                status = "Aiming";
                return Result.RUNNING;
            }
            // stopped a little past the spot (sliding): fire from there, the aim is worked out
            // from where we really are - no walking back and forth around the spot
            double reach = speed < 0.08 ? 2.2 : 1.3;
            if (flat(feet, prev) <= reach && (player.onGround() || cur.act() == Act.TELEPORT)) {
                if (fire(mc, player, cur)) {
                    hopIndex = w;
                    hopAt = now;
                    hopLanding = cur.pos();
                    status = cur.act() == Act.TELEPORT ? "Teleport" : "Etherwarp";
                    return Result.RUNNING;
                }
                // not possible from here right now: walk that part instead
                ways.remove(w);
                ways.addAll(w, cur.fallback().isEmpty() ? List.of(new Way(cur.pos(), Act.WALK)) : cur.fallback());
                cur = ways.get(w);
            } else {
                Vec3 before = ways.get(Math.max(0, w - 2)).pos();
                Vec3 in = prev.subtract(before);
                double len = Math.hypot(in.x, in.z);
                Vec3 aim = flat(feet, prev) > 2.0 || len < 1.0E-6 ? prev
                        : prev.add(in.x / len * 1.5, 0, in.z / len * 1.5);
                steer(mc, player, before, prev, aim, now, false);
                status = "To the warp spot";
                return progress(mc, feet, now);
            }
        }
        // walking / jumping along the line
        double[] proj = projectFlat(feet, prev, cur.pos());
        if (proj[1] >= 0.999 || flat(feet, cur.pos()) < 0.7) {
            if (cur.act() != Act.JUMP || player.onGround()) {
                w++;
                return Result.RUNNING;
            }
        }
        // already on the next piece (cut the corner a little): go on from there, never back
        for (int skip = 0; skip < 4 && cur.act() == Act.WALK && w + 1 < ways.size(); skip++) {
            Way after = ways.get(w + 1);
            if (after.act() != Act.WALK && after.act() != Act.JUMP) break;
            double[] onNext = projectFlat(feet, cur.pos(), after.pos());
            if (onNext[1] <= 0.0 || onNext[0] > proj[0] + 0.25) break;
            w++;
            prev = cur.pos();
            cur = after;
            proj = onNext;
        }
        boolean jumpNow = cur.act() == Act.JUMP && player.onGround() && flat(feet, prev) < 0.85;
        // the camera aims at a point a bit ahead on the line: further when we are off it,
        // so we curve back onto it instead of strafing
        double ahead = LOOKAHEAD + Math.min(2.0, proj[0]);
        steer(mc, player, prev, cur.pos(), pointAhead(w, prev, cur.pos(), proj[1], ahead), now, jumpNow);
        status = "Walking " + w + "/" + ways.size();
        return progress(mc, feet, now);
    }

    /** Distance from the line and the replan rule: far away or stuck = plan again, else steer back. */
    private Result progress(Minecraft mc, Vec3 feet, long now) {
        double remaining = remainingFrom(feet);
        if (remaining < bestRemaining - 0.5) {
            bestRemaining = remaining;
            progressAt = now;
            nudged = false;
        } else if (now - progressAt > 4_000L && !nudged) {
            nudged = true;
            mc.options.keyJump.setDown(true); // a little hop out of whatever holds us
            ownsKeys = true;
        } else if (now - progressAt > 8_000L) {
            return replan(mc, "stuck");
        }
        Way cur = ways.get(Math.min(w, ways.size() - 1));
        Vec3 prev = ways.get(Math.max(0, w - 1)).pos();
        double off = projectFlat(feet, prev, cur.pos())[0];
        if (off > REPLAN_OFF || Math.abs(feet.y - cur.pos().y) > 8 && !mc.player.onGround() == false) {
            return replan(mc, "far off the path");
        }
        return Result.RUNNING;
    }

    private Result replan(Minecraft mc, String why) {
        releaseKeys(mc);
        if (++replans > 6) {
            status = "Stuck";
            return Result.FAILED;
        }
        System.out.println("[PathEngine] new plan (" + why + ")");
        ways = new ArrayList<>();
        w = 0;
        progressAt = System.currentTimeMillis();
        bestRemaining = Double.MAX_VALUE;
        plan(Pending.FRESH);
        return Result.RUNNING;
    }

    /** Forward + camera at {@code aim}; strafe back to the line {@code a}->{@code b}; jump when told. */
    private void steer(Minecraft mc, LocalPlayer player, Vec3 a, Vec3 b, Vec3 aim, long now, boolean jump) {
        Vec3 feet = player.position();
        double[] proj = projectFlat(feet, a, b);
        double off = proj[0];
        if (flat(feet, aim) > 0.4) {
            // the camera only changes where it has to; a point right at our feet keeps the view
            look = new Vec3(aim.x, Math.max(aim.y + 1.4, player.getEyeY() - 0.4), aim.z);
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(aim.z - feet.z, aim.x - feet.x)) - 90.0);
        float err = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - player.getYRot()));
        // like a player: W stays down and the camera steers; only a turn of more than ~95
        // degrees (the way is behind us) waits for the camera first. S is never used.
        boolean forward = err < 95f || flat(feet, aim) < 0.4;
        mc.options.keyUp.setDown(forward);
        // A / D only in an emergency: pushed against a wall while clearly off the line
        double cross = (b.x - a.x) * (feet.z - a.z) - (b.z - a.z) * (feet.x - a.x);
        boolean strafe = forward && err < 45f && off > 0.45 && player.horizontalCollision;
        mc.options.keyLeft.setDown(strafe && cross < 0);
        mc.options.keyRight.setDown(strafe && cross > 0);
        Way next = w < ways.size() ? ways.get(w) : null;
        boolean jumpSoon = next != null && next.act() == Act.JUMP && flat(feet, a) < 2.5;
        boolean warpSoon = next != null && (next.act() == Act.TELEPORT || next.act() == Act.ETHERWARP)
                && flat(feet, ways.get(Math.max(0, w - 1)).pos()) < 4.5;
        boolean sprint = forward && err < 20f && remainingStraight(feet) > 4 && !jumpSoon && !warpSoon
                && PathfinderConfig.get().allowSprint;
        mc.options.keySprint.setDown(sprint);
        boolean hop = jump || (forward && player.onGround() && player.horizontalCollision);
        if (jumpTicks > 0) {
            jumpTicks--;
            hop = jumpTicks > 0;
        } else if (hop) {
            jumpTicks = 3;
        }
        mc.options.keyJump.setDown(hop);
        ownsKeys = true;
    }

    /** The camera already turns towards the warp while we slow down. */
    private Vec3 lookFor(LocalPlayer player, Way hop) {
        if (hop.act() == Act.TELEPORT && hop.aim() != null) {
            return player.getEyePosition().add(hop.aim().normalize().scale(6));
        }
        return hop.pos().add(0, -0.2, 0);
    }

    private boolean fire(Minecraft mc, LocalPlayer player, Way hop) {
        releaseKeys(mc);
        Vec3 eye = player.getEyePosition();
        WarpPlanner.Hop h;
        if (hop.act() == Act.TELEPORT) {
            int reach = AotvAssist.teleportReach(player);
            if (reach <= 0) return false;
            Vec3 dir = hop.pos().add(0, EYE, 0).subtract(eye).normalize();
            Vec3 land = WarpPlanner.simulateTeleport(mc.level, eye, dir, reach);
            if ((land == null || flat(land, hop.pos()) > 2.0) && hop.aim() != null) {
                // the direction the search planned (e.g. down onto the floor below)
                dir = hop.aim().normalize();
                land = WarpPlanner.simulateTeleport(mc.level, eye, dir, reach);
            }
            if (land == null || flat(land, hop.pos()) > 2.5) return false;
            h = new WarpPlanner.Hop(WarpPlanner.Kind.TELEPORT, eye.add(dir.scale(4)), land, false, dir);
        } else {
            BlockPos floor = BlockPos.containing(hop.pos().x, hop.pos().y - 0.5, hop.pos().z);
            Vec3 aim = WarpPlanner.etherwarpAim(mc.level, eye, floor, true);
            boolean jump = false;
            if (aim == null) {
                aim = WarpPlanner.etherwarpAim(mc.level, eye.add(0, 1.1, 0), floor, true);
                jump = aim != null;
            }
            if (aim == null) return false;
            h = new WarpPlanner.Hop(WarpPlanner.Kind.ETHERWARP, aim, new Vec3(aim.x, floor.getY() + 1.0, aim.z), jump, null);
        }
        boolean ok = AotvAssist.execute(List.of(h), true);
        if (ok) look = null;
        return ok;
    }

    // ------------------------------------------------------------------ geometry

    private Vec3 pointAhead(int from, Vec3 a, Vec3 b, double t, double lookahead) {
        Vec3 cur = a.lerp(b, t);
        double left = lookahead;
        int i = from;
        Vec3 next = b;
        while (true) {
            double d = flat(cur, next);
            Way after = i + 1 < ways.size() ? ways.get(i + 1) : null;
            if (d >= left || after == null || after.act() == Act.TELEPORT || after.act() == Act.ETHERWARP) {
                return d < 1.0E-6 ? next : cur.lerp(next, Math.min(1.0, left / d));
            }
            left -= d;
            cur = next;
            i++;
            next = after.pos();
        }
    }

    private double remainingFrom(Vec3 feet) {
        if (w >= ways.size()) return 0;
        double sum = flat(feet, ways.get(w).pos());
        for (int i = w; i < ways.size() - 1; i++) sum += ways.get(i).pos().distanceTo(ways.get(i + 1).pos());
        return sum;
    }

    private double remainingStraight(Vec3 feet) {
        if (w >= ways.size()) return 0;
        return flat(feet, ways.get(w).pos());
    }

    private static double along(List<Way> line, int a, int b) {
        double sum = 0;
        for (int i = a; i < b && i + 1 < line.size(); i++) sum += line.get(i).pos().distanceTo(line.get(i + 1).pos());
        return sum;
    }

    private static double[] projectFlat(Vec3 p, Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        double len2 = dx * dx + dz * dz;
        double t = len2 < 1.0E-9 ? 1 : Math.clamp(((p.x - a.x) * dx + (p.z - a.z) * dz) / len2, 0.0, 1.0);
        return new double[]{Math.hypot(p.x - (a.x + dx * t), p.z - (a.z + dz * t)), t};
    }

    private static double flat(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private String describe() {
        int walk = 0, jump = 0, tp = 0, ew = 0;
        for (Way x : ways) {
            switch (x.act()) {
                case WALK -> walk++;
                case JUMP -> jump++;
                case TELEPORT -> tp++;
                case ETHERWARP -> ew++;
            }
        }
        return "path: " + walk + " lines, " + jump + " jumps, " + tp + " teleports, " + ew + " etherwarps";
    }

    private static IBaritone primary() {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
