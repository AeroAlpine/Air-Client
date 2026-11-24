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
import com.coflnet.pathing.PathLine.Kind;
import com.coflnet.pathing.PathLine.Section;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One planned path, followed without leaving it (Air Client).
 * <ol>
 *   <li><b>Plan</b>: Baritone's A* finds the way, teleports and etherwarps included as moves.</li>
 *   <li><b>Pieces</b> ({@link PathLine}): walking lines in half-block resolution at mid player
 *       height, tight and round; jumps, drops and warps are gaps with only a take-off and a
 *       landing point.</li>
 *   <li><b>Follow</b>: before walking the camera turns onto the line; then the crosshair stays on
 *       the line a little ahead of us and W walks where we look. Off the line a quick look at what
 *       is right in front decides: a block = jump, a wall / fence / hole = A or D back to the
 *       line, otherwise the camera brings us back by itself.</li>
 *   <li><b>Jumps</b>: every tick on the run-up the jump is simulated with the real speed, position
 *       and look direction; we jump at the last tick from which it still lands (one tick ahead,
 *       because keys act in the next tick). In the air the keys that land closest are chosen.</li>
 *   <li><b>Drops</b>: just walk on towards where the line goes on; steer the fall onto it.</li>
 *   <li><b>Warps</b>: only inside a small field around the warp spot and only when the landing
 *       is checked from where we really stand; after the landing the line is picked up again.</li>
 * </ol>
 */
public final class PathEngine {
    public enum Result { IDLE, PLANNING, RUNNING, ARRIVED, FAILED }

    private enum Mode { TURN_FIRST, WALK, GAP, WARP_AIM, WARPING }

    private enum Pending { NONE, FRESH, EXTEND }

    /** kinds per position: 0 walk, 1 jump, 2 teleport, 3 etherwarp; dirs = warp look directions. */
    private record Raw(List<BlockPos> blocks, List<Integer> kinds, List<Vec3> dirs, boolean partial, boolean failed) {
    }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "path-engine");
        t.setDaemon(true);
        return t;
    });
    private static final double EYE = 1.62;
    /** Warps only fire this close to the planned warp spot. */
    private static final double WARP_FIELD = 1.3;
    private static final double REPLAN_OFF = 4.0;
    private static final AtomicInteger IDS = new AtomicInteger();
    /** Path calculations running right now (the simulation runs the game in real time meanwhile). */
    private static final AtomicInteger PLANNING = new AtomicInteger();
    private static volatile List<BlockPos> etherwarpPoints = List.of();
    private static volatile boolean etherwarpAllowed = true;
    /** The engine that walked last (for the overlay). */
    private static volatile PathEngine shown;
    private static volatile long shownAt;

    private final int id = IDS.incrementAndGet();
    private String engineName = "PathEngine";
    private BlockPos goal;
    private double radius;
    private boolean warps;
    private long warpSuppressedUntil;
    private int warpAborts;
    private CompletableFuture<Raw> pending;
    private Pending pendingKind = Pending.NONE;
    private boolean partial;
    private boolean planFailed;
    private int replans;

    private List<Section> sections = new ArrayList<>();
    private int si;
    /** Segment index on the current walking line. */
    private int wi;
    private Mode mode = Mode.WALK;
    private long modeSince;
    private Vec3 look;
    private boolean ownsKeys;
    private int jumpHold;
    private boolean jumped;
    private boolean airborne;
    private long jumpAt;
    private int[] lastKeys = {0, 0};
    /** Etherwarp shortcuts over jump chains even when warps are off (Combat, far mobs). */
    private boolean shortcuts;
    /** Teleports on straight stretches even when warps are off (Combat, far mobs). */
    private boolean teleports;
    /** The running warp is a teleport decided while walking (not a planned piece). */
    private boolean runtimeTp;
    private long lastTpCheckAt;
    private long tpPausedUntil;
    private long lastTpNoLogAt;
    private long lastEwLookAt;
    private int tpFails;
    /** The moving focus point: which piece and how far along its line. */
    private int focusSi = -1;
    private double focusAlong;
    private long lastVineLogAt;
    private String jumpWhy = "";
    private long lastJumpWhyAt;
    /** Jump / warp / air: the camera must not be taken away now (KillAura waits). */
    private boolean critical;
    private boolean avoidVines = true;
    private long criticalAt;
    /** 0 = around vines and gaps, 1 = parkour over gaps allowed, 2 = through vines too. */
    private int planLevel;
    /** At a jump the arc does not work from here: stand instead of walking off the edge. */
    private boolean edgeHold;
    private long edgeHoldSince;
    private WarpPlanner.Hop warpHop;
    private long aimSince;
    private Vec3 hopStart;
    private long hopAt;
    private long progressAt;
    private double progressMark = -1;
    private boolean nudged;
    private long lastSampleAt;
    private String status = "";
    private final double wobblePhase = Math.random() * Math.PI * 2.0;

    public PathEngine() {
    }

    /** Name used in the engine log ("Commission walk", "Combat chase", ...). */
    public PathEngine named(String name) {
        if (name != null && !name.isBlank()) engineName = name;
        return this;
    }

    /** The player's own etherwarp targets: cheap etherwarp moves for the search. */
    /** Mining setting: off = no etherwarps in the plan / shortcuts. */
    public static void setEtherwarpAllowed(boolean on) {
        etherwarpAllowed = on;
    }

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

    /** Allow teleports on straight stretches (warps otherwise off). */
    public void allowTeleports(boolean on) {
        teleports = on;
    }

    /** Allow etherwarps over jump chains in the next plans (warps otherwise off). */
    public void allowShortcuts(boolean on) {
        shortcuts = on;
    }

    /** True while a jump, a drop or a warp needs the camera (other features keep off it). */
    public boolean cameraCritical() {
        return goal != null && com.coflnet.pathing.sim.SimClock.millis() - criticalAt < 150L;
    }

    /** Turning / aiming for an etherwarp or teleport (the camera uses the warp turn speed). */
    public boolean aimingWarp() {
        return goal != null && (mode == Mode.WARP_AIM || mode == Mode.WARPING);
    }

    /** The Pathfinder setting for the current warp type (degrees per second). */
    public double warpAimSpeedDegPerSecond() {
        PathfinderConfig config = PathfinderConfig.get();
        WarpPlanner.Kind kind = warpHop != null ? warpHop.kind() : null;
        if (kind == null && si < sections.size()) {
            Section section = sections.get(si);
            if (section.kind() == Kind.TELEPORT) {
                kind = WarpPlanner.Kind.TELEPORT;
            } else if (section.kind() == Kind.ETHERWARP) {
                kind = WarpPlanner.Kind.ETHERWARP;
            }
        }
        double value = kind == WarpPlanner.Kind.ETHERWARP ? config.etherwarpRotationSpeed
                : kind == WarpPlanner.Kind.TELEPORT ? config.teleportRotationSpeed
                : Math.min(config.etherwarpRotationSpeed, config.teleportRotationSpeed);
        return Math.clamp(Math.min(value, TurnProfile.HUMAN_MAX_DEG_PER_SEC),
                120.0, TurnProfile.HUMAN_MAX_DEG_PER_SEC);
    }

    /** Planning or walking. */
    public boolean busy() {
        return goal != null;
    }

    /** Go to {@code target}; the same goal again (a mob a few blocks further counts) keeps the plan. */
    public void goTo(BlockPos target, double radius, boolean allowWarps) {
        if (target == null) return;
        boolean useWarps = allowWarps && com.coflnet.pathing.sim.SimClock.millis() >= warpSuppressedUntil;
        LocalPlayer me = Minecraft.getInstance().player;
        boolean close = me != null && me.position().distanceTo(Vec3.atCenterOf(target)) < 8;
        double same = sections.isEmpty() || close ? 2 : 16;
        if (goal != null && useWarps == warps && goal.distSqr(target) <= same) return;
        goal = target;
        this.radius = Math.max(1.0, radius);
        warps = useWarps;
        replans = 0;
        warpAborts = 0;
        plan(Pending.FRESH); // the old path is walked on until the new one is there
    }

    public void stop() {
        releaseKeys(Minecraft.getInstance());
        if (goal != null) EngineRecorder.end(id, "gestoppt");
        goal = null;
        sections = new ArrayList<>();
        pending = null;
        pendingKind = Pending.NONE;
        look = null;
        mode = Mode.WALK;
        warpHop = null;
        status = "";
    }

    public void releaseKeys(Minecraft mc) {
        if (!ownsKeys || mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
        ownsKeys = false;
        jumpHold = 0;
    }

    // ------------------------------------------------------------------ planning

    private void plan(Pending kind) {
        Minecraft mc = Minecraft.getInstance();
        IBaritone baritone = primary();
        if (mc.player == null || baritone == null || goal == null) return;
        BetterBlockPos start;
        if (kind == Pending.EXTEND && !sections.isEmpty()) {
            Section last = sections.get(sections.size() - 1);
            start = new BetterBlockPos(BlockPos.containing(last.to().add(0, 0.2, 0)));
        } else {
            start = new BetterBlockPos(mc.player.blockPosition());
        }
        avoidVines = planLevel < 2;
        configure(avoidVines);
        baritone.api.Settings s = BaritoneAPI.getSettings();
        boolean oldTp = s.allowTeleportMoves.value, oldEw = s.allowEtherwarpMoves.value;
        boolean oldPk = s.allowParkour.value, oldPkUp = s.allowParkourAscend.value;
        double oldJump = s.jumpPenalty.value;
        int reach = AotvAssist.teleportReach(mc.player);
        int mana = com.coflnet.mining.MiningState.mana();
        boolean manaOk = mana < 0 || mana >= 60;
        boolean tp = warps && manaOk && reach > 0;
        boolean ew = warps && etherwarpAllowed && manaOk && AotvAssist.canEtherwarp(mc.player);
        // teleports are not planned: they are decided while walking (straight stretches)
        s.allowTeleportMoves.value = false;
        s.allowEtherwarpMoves.value = ew;
        s.teleportMoveReach.value = Math.max(4, reach);
        s.allowParkour.value = planLevel >= 1;
        s.allowParkourAscend.value = planLevel >= 1;
        // with warps a one-block jump is the worse way up: a warp is quicker and cleaner
        if (ew) s.jumpPenalty.value = Math.max(oldJump, 8.0);
        CalculationContext context;
        try {
            context = new CalculationContext(baritone, true);
        } finally {
            s.allowTeleportMoves.value = oldTp;
            s.allowEtherwarpMoves.value = oldEw;
            s.allowParkour.value = oldPk;
            s.allowParkourAscend.value = oldPkUp;
            s.jumpPenalty.value = oldJump;
        }
        Favoring favoring = new Favoring(baritone.getPlayerContext(), null, context);
        GoalNear target = new GoalNear(goal, (int) Math.max(1, Math.floor(radius)));
        AStarPathFinder finder = new AStarPathFinder(start, start.getX(), start.getY(), start.getZ(), target, favoring, context);
        pendingKind = kind;
        PLANNING.incrementAndGet();
        pending = CompletableFuture.supplyAsync(() -> {
            try {
                return calculate(finder);
            } finally {
                PLANNING.decrementAndGet();
            }
        }, POOL);
        status = "Planning";
    }

    public static boolean planning() {
        return PLANNING.get() > 0;
    }

    private static Raw calculate(AStarPathFinder finder) {
        {
            PathCalculationResult result = finder.calculate(2_500L, 6_000L);
            IPath path = result.getPath().orElse(null);
            if (path == null || path.positions().size() < 2) {
                return new Raw(List.of(), List.of(), List.of(), false, true);
            }
            List<BetterBlockPos> positions = path.positions();
            List<IMovement> moves = path.movements();
            List<BlockPos> blocks = new ArrayList<>();
            List<Integer> kinds = new ArrayList<>();
            List<Vec3> dirs = new ArrayList<>();
            for (int i = 0; i < positions.size(); i++) {
                BetterBlockPos p = positions.get(i);
                blocks.add(new BlockPos(p.getX(), p.getY(), p.getZ()));
                int how = 0;
                Vec3 dir = null;
                if (i > 0) {
                    IMovement move = i - 1 < moves.size() ? moves.get(i - 1) : null;
                    if (move instanceof MovementWarp warp) {
                        how = warp.kind == MovementWarp.Kind.TELEPORT ? 2 : 3;
                        dir = Vec3.directionFromRotation(warp.pitch, warp.yaw);
                    }
                }
                kinds.add(how);
                dirs.add(dir);
            }
            boolean toGoal = result.getType() == PathCalculationResult.Type.SUCCESS_TO_GOAL;
            return new Raw(blocks, kinds, dirs, !toGoal, false);
        }
    }

    private static final net.minecraft.world.level.block.Block[] VINES = {
            net.minecraft.world.level.block.Blocks.VINE, net.minecraft.world.level.block.Blocks.CAVE_VINES,
            net.minecraft.world.level.block.Blocks.CAVE_VINES_PLANT, net.minecraft.world.level.block.Blocks.WEEPING_VINES,
            net.minecraft.world.level.block.Blocks.WEEPING_VINES_PLANT, net.minecraft.world.level.block.Blocks.TWISTING_VINES,
            net.minecraft.world.level.block.Blocks.TWISTING_VINES_PLANT};

    /** The Baritone settings a walk plans with (the simulation reads them as the evolution's start). */
    public static void applyWalkSettings() {
        configure(true);
    }

    private static void configure(boolean avoid) {
        baritone.api.Settings s = BaritoneAPI.getSettings();
        s.allowBreak.value = false;
        s.allowPlace.value = false;
        s.allowInventory.value = false;
        s.antiCheatCompatibility.value = true;
        s.freeLook.value = false; // the server gets the camera (warps aim with it)
        for (net.minecraft.world.level.block.Block vine : VINES) {
            if (avoid && !s.blocksToAvoid.value.contains(vine)) s.blocksToAvoid.value.add(vine);
            if (!avoid) s.blocksToAvoid.value.remove(vine);
        }
        s.maxFallHeightNoWater.value = 6; // no long falls (damage, and a missed edge is the void)
        PathfinderTuning.apply(s, false);
    }

    /** A drop that was planned to a higher floor than the real one falls until the real floor. */
    private void correctDropLandings(Minecraft mc) {
        if (mc.level == null) return;
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            if (s.kind() != Kind.DROP) continue;
            Vec3 to = s.to();
            double floor = PathLine.floorIn(mc.level, to.x, to.z, (int) Math.floor(to.y + 0.6));
            if (!Double.isNaN(floor) && floor < to.y - 0.05) {
                sections.set(i, new Section(Kind.DROP, List.of(), s.from(),
                        new Vec3(to.x, floor, to.z), null));
                EngineRecorder.event(id, String.format(Locale.ROOT,
                        "drop landing corrected %.1f down to the real floor", to.y - floor));
            }
        }
    }

    private void applyPlan(Minecraft mc, LocalPlayer player, Raw raw, Pending kind, long now) {
        if (raw.failed()) {
            if (planLevel < 2 && kind == Pending.FRESH) {
                // no way like that: allow gap jumps, then the vines
                planLevel++;
                EngineRecorder.event(id, planLevel == 1 ? "no way without gap jumps - allowing them"
                        : "no way around vines - planning through them");
                plan(Pending.FRESH);
                return;
            }
            if (sections.isEmpty() || si >= sections.size()) planFailed = true;
            return;
        }
        planLevel = 0;
        if (kind == Pending.EXTEND && !sections.isEmpty()) {
            Section last = sections.get(sections.size() - 1);
            List<Section> more = PathLine.build(mc.level, raw.blocks(), raw.kinds(), raw.dirs(), last.to());
            if (last.walk() && !more.isEmpty() && more.get(0).walk()) {
                List<Vec3> line = new ArrayList<>(last.line());
                List<Vec3> add = more.get(0).line();
                line.addAll(add.subList(Math.min(1, add.size()), add.size()));
                List<Boolean> tight = new ArrayList<>(last.tight());
                List<Boolean> addTight = more.get(0).tight();
                tight.addAll(addTight.subList(Math.min(1, addTight.size()), addTight.size()));
                sections.set(sections.size() - 1, new Section(Kind.WALK, line, last.from(), more.get(0).to(), null, tight));
                more = more.subList(1, more.size());
            }
            sections.addAll(more);
            partial = raw.partial();
            EngineRecorder.event(id, "extended: " + describe());
            return;
        }
        List<Section> fresh = PathLine.build(mc.level, raw.blocks(), raw.kinds(), raw.dirs(), player.position());
        if (mode == Mode.WALK && !sections.isEmpty() && si < sections.size() && goal != null) {
            Section last = sections.get(sections.size() - 1);
            double oldLeft = remaining(player.position());
            double newLen = 0;
            for (Section c : fresh) newLen += c.walk() ? PathLine.length(c.line()) : PathLine.flat(c.from(), c.to());
            if (oldLeft > 4.0 && last.to().distanceTo(Vec3.atBottomCenterOf(goal)) < 5.0 && newLen > oldLeft - 2.0) {
                // the old way still leads there and the new one is not shorter: no switching
                // between two ways (that was the twitching)
                EngineRecorder.event(id, "new way not better - staying on the old one");
                return;
            }
        }
        sections = new ArrayList<>(fresh);
        int mana = com.coflnet.mining.MiningState.mana();
        if ((warps || shortcuts) && etherwarpAllowed && (mana < 0 || mana >= 60) && AotvAssist.canEtherwarp(player)) {
            int before = sections.size();
            sections = new ArrayList<>(WarpLogic.shortcuts(mc.level, sections));
            if (sections.size() != before) EngineRecorder.event(id, "etherwarp shortcuts: " + describe());
        }
        correctDropLandings(mc);
        edgeHold = false;
        si = 0;
        focusSi = -1;
        wi = 0;
        partial = raw.partial();
        planFailed = false;
        jumped = false;
        airborne = false;
        warpHop = null;
        progressAt = now;
        progressMark = -1;
        nudged = false;
        double speed = player.getDeltaMovement().horizontalDistance();
        setMode(speed < 0.12 ? Mode.TURN_FIRST : Mode.WALK, now);
        EngineRecorder.begin(id, engineName, goal.toShortString(), raw.blocks().size(), describe(), "");
        System.out.println("[PathEngine] " + engineName + " " + describe() + (partial ? " (part, extended later)" : ""));
    }

    // ------------------------------------------------------------------ following

    public Result tick(Minecraft mc) {
        shown = this;
        shownAt = com.coflnet.pathing.sim.SimClock.millis();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || goal == null) {
            releaseKeys(mc);
            return Result.IDLE;
        }
        long now = com.coflnet.pathing.sim.SimClock.millis();
        Vec3 feet = player.position();
        if (pending != null && pending.isDone()) {
            Raw raw = pending.join();
            Pending kind = pendingKind;
            pending = null;
            pendingKind = Pending.NONE;
            applyPlan(mc, player, raw, kind, now);
        }
        boolean near = PathLine.flat(feet, Vec3.atBottomCenterOf(goal)) <= radius && Math.abs(feet.y - goal.getY()) <= 2.5
                || feet.distanceTo(Vec3.atCenterOf(goal)) <= radius + 0.5;
        if (near && player.onGround() && mode != Mode.WARPING) {
            releaseKeys(mc);
            look = null;
            status = "Arrived";
            EngineRecorder.end(id, "angekommen");
            return Result.ARRIVED;
        }
        if (sections.isEmpty()) {
            releaseKeys(mc);
            look = null;
            if (planFailed) {
                status = "No way found";
                EngineRecorder.end(id, "kein Weg gefunden");
                return Result.FAILED;
            }
            status = "Planning";
            return Result.PLANNING;
        }
        if (AotvAssist.isBusy()) {
            if (runtimeTp) {
                // teleport on a straight stretch: no stop, W stays down
                mc.options.keyUp.setDown(true);
                ownsKeys = true;
            } else {
                releaseKeys(mc);
            }
            look = null;
            status = "Warping";
            progressAt = now;
            return Result.RUNNING;
        }
        if (mode == Mode.WARPING) {
            return afterWarp(mc, player, now);
        }
        if (partial && pending == null && remaining(feet) < 30) {
            plan(Pending.EXTEND);
        }
        if (si >= sections.size()) {
            if (pending == null) plan(partial ? Pending.EXTEND : Pending.FRESH);
            releaseKeys(mc);
            status = "Planning";
            return Result.RUNNING;
        }
        Section s = sections.get(si);
        critical = false;
        Result r;
        if (s.walk()) {
            r = walk(mc, player, s, now);
        } else if (s.warp()) {
            r = warp(mc, player, s, now);
        } else {
            r = gap(mc, player, s, now);
        }
        if (jumpHold > 0) {
            jumpHold--;
            mc.options.keyJump.setDown(true);
            ownsKeys = true;
        } else if (ownsKeys) {
            mc.options.keyJump.setDown(false);
        }
        if (r == Result.RUNNING && (critical || mode == Mode.GAP || mode == Mode.WARP_AIM)) criticalAt = now;
        return r == Result.RUNNING ? watchProgress(mc, feet, now) : r;
    }

    /** A walking line: crosshair on the line just ahead, W, and a look at what is in front. */
    private Result walk(Minecraft mc, LocalPlayer player, Section s, long now) {
        Vec3 feet = player.position();
        Level level = mc.level;
        List<Vec3> line = s.line();
        Section next = si + 1 < sections.size() ? sections.get(si + 1) : null;
        double speed = player.getDeltaMovement().horizontalDistance();
        // where on the line are we (only forwards)
        double alongHere;
        double lateral;
        Vec3 onLine;
        double total = PathLine.length(line);
        if (line.size() == 1) {
            alongHere = 0;
            onLine = line.get(0);
            lateral = PathLine.flat(feet, onLine);
            total = 0;
        } else {
            int best = wi;
            double bestScore = Double.MAX_VALUE, bestT = 0;
            for (int k = wi; k < Math.min(line.size() - 1, focusSi != si ? line.size() : wi + 24); k++) {
                double[] pr = project(feet, line.get(k), line.get(k + 1));
                Vec3 p = line.get(k).lerp(line.get(k + 1), pr[1]);
                double score = pr[0] + Math.abs(feet.y + PathLine.MID - p.y) * 0.5;
                if (score <= bestScore + 1.0E-3) {
                    bestScore = score;
                    best = k;
                    bestT = pr[1];
                }
            }
            wi = best;
            onLine = line.get(wi).lerp(line.get(wi + 1), bestT);
            lateral = PathLine.flat(feet, onLine);
            alongHere = along(line, wi) + PathLine.flat(line.get(wi), onLine);
        }
        double left = total - alongHere;
        progress(si * 10_000.0 + alongHere, now);
        // end of this line: on to the next piece
        boolean atEnd = line.size() == 1 ? lateral < 0.6 : left < 0.35;
        if (atEnd || line.size() == 1 && next != null && next.warp() && lateral < WARP_FIELD) {
            if (next == null) {
                if (pending == null) plan(partial ? Pending.EXTEND : Pending.FRESH);
                releaseKeys(mc);
                status = "Planning";
                return Result.RUNNING;
            }
            si++;
            wi = 0;
            jumped = false;
            airborne = false;
            setMode(next.warp() ? Mode.WARP_AIM : Mode.GAP, now);
            aimSince = 0;
            warpHop = null;
            return Result.RUNNING;
        }
        if (lateral > REPLAN_OFF && player.onGround()) {
            return replan(mc, String.format(Locale.ROOT, "%.1f b off the line", lateral));
        }
        boolean tight = s.tightNear(wi);
        boolean jumpSoon = next != null && next.kind() == Kind.JUMP && left < runUp(next);
        boolean warpSoon = next != null && next.warp() && left < 4.0;
        // The focus point runs along the line ahead of us and never goes back: as fast as we
        // are (a little faster), slowing down - less than we do - when it is far ahead, and
        // pushed on when we catch up. Camera and walking both go for it: nothing behind us.
        double minLead = (tight ? 1.0 : 1.4) + 1.5 * speed;
        double maxLead = (tight ? 2.0 : 2.8) + 3.0 * speed;
        if (focusSi != si) {
            focusSi = si;
            focusAlong = alongHere + minLead;
        }
        double stepF = Math.max(0.05, speed * 1.15);
        if (focusAlong - alongHere > maxLead) stepF = 0.02;
        focusAlong = Math.max(focusAlong + stepF, alongHere + minLead);
        double ahead = Math.max(0.5, focusAlong - alongHere);
        Vec3 focus = focusPoint(line, focusAlong, total, next, si);
        float headingNow = player.getYRot();
        for (int guard = 0; guard < 24 && focusAlong < total + 3.0; guard++) {
            double fx = focus.x - feet.x, fz = focus.z - feet.z;
            double fwd = -Math.sin(Math.toRadians(headingNow)) * fx + Math.cos(Math.toRadians(headingNow)) * fz;
            boolean behind = fwd < 0.3 && Math.hypot(fx, fz) < 3.0 || Math.hypot(fx, fz) < 0.8;
            if (!behind) break;
            focusAlong += 0.5; // it was behind / at our feet: on along the line
            focus = focusPoint(line, focusAlong, total, next, si);
        }
        Vec3 aim = focus;
        if (next != null && left < ahead) {
            Vec3 beyond = switch (next.kind()) {
                case JUMP -> next.to().add(0, PathLine.MID, 0);
                case DROP -> rejoinPoint(si + 1);
                default -> null; // warp: stop on the line's end
            };
            if (beyond != null) aim = aim.lerp(beyond, Math.clamp(1.0 - left / ahead, 0.0, 1.0));
        }
        if (!tight && !jumpSoon) {
            // a hint of life: a few centimetres sideways, never off the line
            double wob = Math.sin(now / 1000.0 * 0.8 + wobblePhase) * 0.04;
            Vec3 dirLine = line.size() > 1 ? line.get(Math.min(line.size() - 1, wi + 1)).subtract(line.get(wi)) : Vec3.ZERO;
            double dl = Math.hypot(dirLine.x, dirLine.z);
            if (dl > 1.0E-6) aim = aim.add(-dirLine.z / dl * wob, 0, dirLine.x / dl * wob);
        }
        // where the feet go: just ahead on the line; before a jump straight along the jump axis
        Vec3 moveTo;
        if (jumpSoon) {
            moveTo = onAxis(feet, next.from(), next.to(), 1.2);
            aim = next.to().add(0, PathLine.MID, 0); // face the jump: the sprint push goes where we look
        } else if (line.size() == 1) {
            moveTo = line.get(0);
        } else if (tight && lateral < 0.4) {
            moveTo = pointAt(line, Math.min(total, alongHere + 0.9)); // exact where it is tight
        } else {
            moveTo = focus;
        }
        if (PathLine.flat(feet, aim) > 0.6) look = aim.add(0, 0.7, 0); // head height: not at the floor
        float err = yawError(player, aim);
        if (mode == Mode.TURN_FIRST) {
            // before the first step: turn onto the line, standing
            releaseKeys(mc);
            status = String.format(Locale.ROOT, "Turning %.0f deg", err);
            if (err < 12f || now - modeSince > 1_500L) setMode(Mode.WALK, now);
            progressAt = now;
            return Result.RUNNING;
        }
        if (PathLine.flat(feet, moveTo) < 0.7) moveTo = focus;
        // the keys whose real direction (with the camera as it is now) hits the line best:
        // in the open that is W and the camera steers; where it is tight W+A / W+D / A / D
        boolean precise = tight || jumpSoon || lateral > 0.6 || pingplus.voicechat.client.KillAuraFeature.hasTarget();
        int[] k = keysFor(player, moveTo, precise);
        if (k[0] == 0 && k[1] == 0) {
            look = moveTo.add(0, 0.7, 0); // otherwise both wait for each other (stood in front of walls)
        }
        boolean onVine = player.onClimbable();
        if (onVine && player.horizontalCollision) {
            // pushing into vines makes us climb (the "lag"): no W into them, sideways back to the line
            k[0] = 0;
            if (k[1] == 0) k[1] = sideTo(player, onLine);
            if (now - lastVineLogAt > 1_000L) {
                lastVineLogAt = now;
                EngineRecorder.event(id, "vines in front at " + point(feet) + " - sideways, no jump");
            }
        }
        boolean hop = false;
        if (lateral > 0.3 && player.onGround() && !onVine && !jumpSoon && roomToJump(level, feet, 1.0)) {
            hop = probe(level, player, speed) == Obstacle.STEP;
        }
        float moveErr = yawError(player, moveTo);
        boolean longJump = jumpSoon && PathLine.flat(next.from(), next.to()) > 1.8;
        boolean sprint = k[0] == 1 && k[1] == 0 && moveErr < 15f && !warpSoon && PathfinderConfig.get().allowSprint
                && (!tight && !jumpSoon && straightFor(line, alongHere, 4.0) || longJump);
        mc.options.keyUp.setDown(k[0] > 0);
        mc.options.keyDown.setDown(k[0] < 0);
        mc.options.keyLeft.setDown(k[1] > 0);
        mc.options.keyRight.setDown(k[1] < 0);
        mc.options.keySprint.setDown(sprint);
        mc.options.keyJump.setDown(false);
        ownsKeys = true;
        if (hop && jumpHold == 0) {
            jumpHold = 2;
            EngineRecorder.event(id, "block in front, jumping");
        }
        if (edgeHold && jumpSoon) {
            mc.options.keyUp.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keySprint.setDown(false);
            if (now - edgeHoldSince > 1_500L) {
                edgeHold = false;
                return replan(mc, "jump not possible from here");
            }
        }
        if (tryTeleport(mc, player, line, wi, onLine, now)) {
            return Result.RUNNING;
        }
        if (next != null && next.kind() == Kind.ETHERWARP && left < 10.0 && player.onGround()
                && now - lastEwLookAt > 150L) {
            lastEwLookAt = now;
            if (WarpLogic.etherAim(level, player.getEyePosition(), WarpLogic.blockUnder(next.to())) != null) {
                // the target block can already be hit from here: warp from here
                si++;
                wi = 0;
                setMode(Mode.WARP_AIM, now);
                aimSince = 0;
                warpHop = null;
                EngineRecorder.event(id, String.format(Locale.ROOT, "etherwarp target visible %.1f b before the spot", left));
                return Result.RUNNING;
            }
        }
        // run-up to a jump of the plan: jump at the exact tick
        if (jumpSoon && player.onGround() && shouldJump(mc, player, next, k)) {
            fireJump(mc, now);
            si++;
            wi = 0;
            setMode(Mode.GAP, now);
        }
        critical = jumpSoon || warpSoon;
        status = "Walking";
        sample(mc, player, String.format(Locale.ROOT, "walk %d/%d left=%.1f lat=%.2f err=%.0f keys=%d/%d%s%s",
                si + 1, sections.size(), left, lateral, err, k[0], k[1], tight ? " tight" : "",
                onVine ? " VINES" : ""), err, now);
        return Result.RUNNING;
    }

    /** A jump or a drop: no line here, only where we take off and where we come down. */
    private Result gap(Minecraft mc, LocalPlayer player, Section s, long now) {
        Vec3 feet = player.position();
        Vec3 rejoin = rejoinPoint(si);
        if (!player.onGround()) airborne = true;
        if (s.kind() == Kind.CLIMB) {
            return climb(mc, player, s, now);
        }
        if (airborne && player.onGround()) {
            EngineRecorder.event(id, String.format(Locale.ROOT, "%s landed at %s (planned %s)",
                    s.kind() == Kind.JUMP ? "jump" : "drop", point(feet), point(s.to())));
            airborne = false;
            jumped = false;
            if (s.kind() == Kind.JUMP && feet.y < s.to().y - 0.6) {
                // came down short (below the landing): no walking on towards a point above us
                return replan(mc, String.format(Locale.ROOT, "jump came down %.1f below the landing", s.to().y - feet.y));
            }
            resync(feet, si + 1);
            setMode(Mode.WALK, now);
            return Result.RUNNING;
        }
        if (airborne) {
            // in the air: look down at where we land, keys that bring us closest to it
            Vec3 target = s.kind() == Kind.JUMP ? s.to() : new Vec3(rejoin.x, s.to().y, rejoin.z);
            Vec3 axis = new Vec3(rejoin.x - s.from().x, 0, rejoin.z - s.from().z);
            double axisLen = axis.horizontalDistance();
            Vec3 forwardDown = axisLen < 1.0E-6 ? target
                    : new Vec3(feet.x + axis.x / axisLen * 2.5, target.y + 0.2, feet.z + axis.z / axisLen * 2.5);
            look = forwardDown;
            critical = true;
            steerAir(mc, player, target);
            status = s.kind() == Kind.JUMP ? "Jumping" : "Dropping";
            progressAt = now;
            return Result.RUNNING;
        }
        if (s.kind() == Kind.DROP) {
            // Walk off the edge without braking: aim horizontally at the landing (or the next
            // line for a straight-down drop), and keep a sprint that is already running.
            Vec3 toward = PathLine.flat(s.from(), s.to()) > 0.5 ? s.to() : rejoin;
            look = toward.add(0, PathLine.MID, 0);
            int[] k = keysFor(player, toward, false);
            mc.options.keyUp.setDown(k[0] > 0);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(k[1] > 0);
            mc.options.keyRight.setDown(k[1] < 0);
            mc.options.keySprint.setDown(PathfinderConfig.get().allowSprint && player.isSprinting());
            ownsKeys = true;
            if (feet.y < s.from().y - 0.4 || PathLine.flat(feet, s.from()) > 1.2 && PathLine.flat(feet, rejoin)
                    < PathLine.flat(s.from(), rejoin) - 0.8 && Math.abs(feet.y - s.to().y) < 0.4) {
                // down without really leaving the ground
                resync(feet, si + 1);
                setMode(Mode.WALK, now);
            } else if (now - modeSince > 3_000L) {
                return replan(mc, "drop not taken");
            }
            status = "Dropping";
            return Result.RUNNING;
        }
        // jump, still on the ground: at the take-off point, waiting for the right tick
        look = s.to().add(0, PathLine.MID, 0);
        boolean vertical = PathLine.flat(s.from(), s.to()) < 0.25;
        float jumpAxis = (float) yawTo(s.from(), s.to());
        float jumpOff = Math.abs(Mth.wrapDegrees(player.getYRot() - jumpAxis));
        if (!vertical && jumpOff > 8f) {
            // Do not run into the wall while the camera still points somewhere else: stand
            // and let the path camera turn onto the jump axis first. The server jump goes
            // where the camera looks, so jumping now would land beside/behind the target.
            mc.options.keyUp.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            jumpWhy = String.format(Locale.ROOT, "turning to the jump (%.0f deg)", jumpOff);
            status = "Turning to jump";
            return Result.RUNNING;
        }
        int[] k = vertical ? new int[]{0, 0} : keysFor(player, onAxis(feet, s.from(), s.to(), 1.2), true);
        mc.options.keyUp.setDown(k[0] > 0);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(k[1] > 0);
        mc.options.keyRight.setDown(k[1] < 0);
        ownsKeys = true;
        critical = true;
        boolean go = !jumped && shouldJump(mc, player, s, k);
        double rise = s.to().y - feet.y;
        float toLanding = yawError(player, s.to());
        boolean atTakeoff = player.horizontalCollision || PathLine.flat(feet, s.from()) < 0.6
                || AirMath.jump(mc.level, feet, player.getDeltaMovement(), player.getYRot(), player.getYRot(),
                player.isSprinting(), 0.1, 1) == null; // next step would be over the edge
        if (!go && !jumped && player.onGround() && rise <= 1.25 && toLanding < 45f && atTakeoff
                && !PathLine.solidAt(mc.level, feet.x, feet.y + 1.9, feet.z)) {
            // Baritone planned this jump: at the take-off point it is taken - the arc check only
            // decides an earlier moment, it never holds the jump back ("springt gar nicht mehr")
            go = true;
            edgeHold = false;
            if (!jumpWhy.isEmpty()) EngineRecorder.event(id, "jumping at the take-off (" + jumpWhy + ")");
        }
        if (!go && now - lastJumpWhyAt > 1_000L) {
            lastJumpWhyAt = now;
            EngineRecorder.event(id, "waiting to jump at " + point(feet) + ": " + jumpWhy);
        }
        if (edgeHold) {
            mc.options.keyUp.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            if (now - edgeHoldSince > 1_500L) {
                edgeHold = false;
                return replan(mc, "jump not possible from here");
            }
        }
        if (go) {
            fireJump(mc, now);
        } else if (jumped && now - jumpAt > 700L) {
            jumped = false; // pressed, but we never left the ground: try again
        }
        if (now - modeSince > 4_000L && !jumped) {
            return replan(mc, "jump not taken");
        }
        status = "Take-off";
        return Result.RUNNING;
    }

    /** Up a vine / ladder: push into it (it lifts us), at the top step forwards onto the floor. */
    private Result climb(Minecraft mc, LocalPlayer player, Section s, long now) {
        Vec3 feet = player.position();
        Vec3 top = s.to();
        Vec3 next = rejoinPoint(si);
        look = new Vec3(next.x, top.y + 1.4, next.z);
        boolean up = feet.y >= top.y - 0.05;
        mc.options.keyUp.setDown(true);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(!up && player.onClimbable());
        ownsKeys = true;
        critical = true;
        progressAt = now;
        status = String.format(Locale.ROOT, "Climbing %.1f", top.y - feet.y);
        if (up && player.onGround()) {
            EngineRecorder.event(id, "climbed up to " + point(feet));
            resync(feet, si + 1);
            setMode(Mode.WALK, now);
        } else if (!player.onClimbable() && player.onGround() && now - modeSince > 700L) {
            // nothing to hold on to here
            double rise = top.y - feet.y;
            if (rise <= 1.25 && !jumped && roomToJump(mc.level, feet, rise)) {
                fireJump(mc, now); // it is only a step: jump onto it
                EngineRecorder.event(id, String.format(Locale.ROOT, "no vine at %s - jumping the %.1f step", point(feet), rise));
            } else if (now - modeSince > 1_800L) {
                return replan(mc, String.format(Locale.ROOT, "nothing to climb at %s (%.1f up)", point(feet), rise));
            }
        } else if (now - modeSince > 8_000L) {
            return replan(mc, "climb did not get up");
        }
        return Result.RUNNING;
    }

    /**
     * After a landing: go on with the piece we are really at - the next line, or one further on
     * when we came down past it (a ledge further down, a longer flight).
     */
    private void resync(Vec3 feet, int from) {
        int best = from;
        double bestD = Double.MAX_VALUE;
        for (int k = from; k < Math.min(sections.size(), from + 7); k++) {
            Section c = sections.get(k);
            double d;
            if (c.walk()) {
                d = Double.MAX_VALUE;
                for (Vec3 p : c.line()) d = Math.min(d, PathLine.flat(feet, p) + Math.abs(feet.y + PathLine.MID - p.y));
            } else {
                d = PathLine.flat(feet, c.from()) + Math.abs(feet.y - c.from().y);
            }
            if (d < bestD - 0.3) {
                bestD = d;
                best = k;
            }
        }
        if (best != from) EngineRecorder.event(id, "landed further on: piece " + (best + 1) + "/" + sections.size());
        si = best;
        wi = 0;
        focusSi = -1;
    }

    /** Teleport / etherwarp: stop inside the field, check the landing from here, fire. */
    private Result warp(Minecraft mc, LocalPlayer player, Section s, long now) {
        Vec3 feet = player.position();
        double speed = player.getDeltaMovement().horizontalDistance();
        double off = PathLine.flat(feet, s.from());
        releaseKeys(mc);
        if (warpHop == null) {
            if (speed > 0.05 && player.onGround()) {
                look = s.dir() != null ? player.getEyePosition().add(s.dir().normalize().scale(6)) : s.to();
                status = "Stopping";
                return Result.RUNNING;
            }
            boolean seenFromHere = s.kind() == Kind.ETHERWARP
                    && WarpLogic.etherAim(mc.level, player.getEyePosition(), WarpLogic.blockUnder(s.to())) != null;
            if (off > WARP_FIELD && !seenFromHere) {
                if (off < 3.5 && now - modeSince < 3_000L) {
                    // slid a little past it: step back onto the spot, slowly
                    look = s.from().add(0, 1.5, 0);
                    int[] k = keysFor(player, s.from(), true);
                    mc.options.keyUp.setDown(k[0] > 0);
                    mc.options.keyLeft.setDown(k[1] > 0);
                    mc.options.keyRight.setDown(k[1] < 0);
                    ownsKeys = true;
                    status = "Back to the warp spot";
                    return Result.RUNNING;
                }
                return abortWarp(mc, String.format(Locale.ROOT, "%.1f b outside the warp field", off));
            }
            warpHop = checkWarp(mc, player, s);
            if (warpHop == null) {
                return abortWarp(mc, "warp would not land from here");
            }
            aimSince = now;
            look = s.kind() == Kind.TELEPORT
                    ? player.getEyePosition().add(warpHop.dir().normalize().scale(6))
                    : warpHop.aim();
        }
        float error = aimError(player, look);
        status = String.format(Locale.ROOT, "Aiming %.0f deg", error);
        if (error > 3f && now - aimSince < 1_500L) {
            progressAt = now;
            return Result.RUNNING;
        }
        if (!AotvAssist.execute(List.of(warpHop), false)) {
            return abortWarp(mc, "AOTV not ready");
        }
        hopStart = feet;
        hopAt = now;
        look = null;
        setMode(Mode.WARPING, now);
        EngineRecorder.event(id, (s.kind() == Kind.TELEPORT ? "teleport" : "etherwarp") + " fired at "
                + point(feet) + " -> " + point(warpHop.landing()));
        return Result.RUNNING;
    }

    /** The warp from where we really stand: does it land where the plan wants? */
    private WarpPlanner.Hop checkWarp(Minecraft mc, LocalPlayer player, Section s) {
        Vec3 eye = player.getEyePosition();
        if (s.kind() == Kind.TELEPORT) {
            int reach = AotvAssist.teleportReach(player);
            if (reach <= 0) return null;
            WarpPlanner.Hop best = null;
            double bestOff = Double.MAX_VALUE;
            List<Vec3> dirs = new ArrayList<>();
            if (s.dir() != null) dirs.add(s.dir().normalize());
            dirs.add(s.to().add(0, EYE, 0).subtract(eye).normalize());
            for (Vec3 base : List.copyOf(dirs)) {
                float yaw = (float) Math.toDegrees(Math.atan2(-base.x, base.z));
                float pitch = (float) -Math.toDegrees(Math.asin(Math.clamp(base.y, -1.0, 1.0)));
                for (float dy : new float[]{-6f, -3f, 3f, 6f}) dirs.add(Vec3.directionFromRotation(pitch, yaw + dy));
                for (float dp : new float[]{-6f, -3f, 3f, 6f}) dirs.add(Vec3.directionFromRotation(pitch + dp, yaw));
            }
            for (Vec3 dir : dirs) {
                Vec3 land = WarpPlanner.simulateTeleport(mc.level, eye, dir, reach);
                if (land == null || Math.abs(land.y - s.to().y) > 1.5) continue;
                double off = PathLine.flat(land, s.to());
                if (off > 2.0 || off >= bestOff) continue;
                if (!WarpPlanner.teleportBubbleClear(mc.level, eye, dir, reach)) continue;
                bestOff = off;
                best = new WarpPlanner.Hop(WarpPlanner.Kind.TELEPORT, eye.add(dir.scale(4)), land, false, dir);
            }
            if (best == null) EngineRecorder.event(id, "teleport from " + point(player.position())
                    + " would not land near " + point(s.to()));
            return best;
        }
        BlockPos floor = WarpLogic.blockUnder(s.to());
        Vec3 aim = WarpLogic.etherAim(mc.level, eye, floor);
        if (aim == null) {
            EngineRecorder.event(id, "etherwarp from " + point(player.position()) + " does not see " + floor.toShortString());
            return null;
        }
        return new WarpPlanner.Hop(WarpPlanner.Kind.ETHERWARP, aim,
                new Vec3(floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5), false, null);
    }

    /**
     * The teleport point runs on the path one teleport length ahead. When the way there is
     * straight (see {@link WarpLogic#straightTeleport}) and the teleport really lands on it: go,
     * while walking. A jump on that stretch is skipped by it.
     */
    private boolean tryTeleport(Minecraft mc, LocalPlayer player, List<Vec3> line, int seg, Vec3 onLine, long now) {
        if (!(warps || teleports) || now < tpPausedUntil || now - lastTpCheckAt < 200L) {
            return false;
        }
        if (!player.onGround() || AotvAssist.isBusy()) return false;
        lastTpCheckAt = now;
        int mana = com.coflnet.mining.MiningState.mana();
        if (mana >= 0 && mana < 60) return false;
        int reach = AotvAssist.teleportReach(player);
        if (reach <= 0) return false;
        List<Vec3> route = routeAhead(line, seg, onLine, reach + 6.0);
        WarpPlanner.Hop hop = WarpLogic.straightTeleport(mc.level, player.position(), player.getEyePosition(), route, reach);
        if (hop == null) {
            if (!WarpLogic.lastNo.isEmpty() && now - lastTpNoLogAt > 3_000L && !WarpLogic.lastNo.startsWith("way left")) {
                lastTpNoLogAt = now;
                EngineRecorder.event(id, "no teleport here: " + WarpLogic.lastNo);
            }
            return false;
        }
        if (!AotvAssist.execute(List.of(hop), false)) {
            EngineRecorder.event(id, "teleport not started (AOTV busy / missing)");
            return false;
        }
        runtimeTp = true;
        hopStart = player.position();
        hopAt = now;
        setMode(Mode.WARPING, now);
        EngineRecorder.event(id, "teleport on a straight stretch from " + point(player.position())
                + " -> " + point(hop.landing()));
        return true;
    }

    /** The path ahead from {@code onLine} as one line (walking lines and jump / drop ends), up to {@code max} blocks. */
    private List<Vec3> routeAhead(List<Vec3> line, int seg, Vec3 onLine, double max) {
        List<Vec3> out = new ArrayList<>();
        out.add(onLine);
        double len = 0;
        for (int i = seg + 1; i < line.size() && len < max; i++) {
            len += PathLine.flat(out.get(out.size() - 1), line.get(i));
            out.add(line.get(i));
        }
        for (int k = si + 1; k < sections.size() && len < max; k++) {
            Section c = sections.get(k);
            if (c.warp()) break;
            List<Vec3> pts = c.walk() ? c.line()
                    : List.of(c.from().add(0, PathLine.MID, 0), c.to().add(0, PathLine.MID, 0));
            for (Vec3 p : pts) {
                len += PathLine.flat(out.get(out.size() - 1), p);
                out.add(p);
                if (len >= max) break;
            }
        }
        return out;
    }

    /** After a warp: pick the line up again where we came down. */
    private Result afterWarp(Minecraft mc, LocalPlayer player, long now) {
        if (now - hopAt < 250L) return Result.RUNNING;
        Vec3 feet = player.position();
        if (runtimeTp) {
            runtimeTp = false;
            lastTpCheckAt = 0; // straight on: the next one may follow right away
            if (hopStart != null && feet.distanceTo(hopStart) < 1.2) {
                if (++tpFails >= 2) {
                    tpPausedUntil = now + 15_000L;
                    tpFails = 0;
                    EngineRecorder.event(id, "teleports while walking off for 15 s (did not go through)");
                }
            } else {
                tpFails = 0;
                EngineRecorder.event(id, "teleported to " + point(feet));
                resync(feet, si);
            }
            progressAt = now;
            progressMark = -1;
            setMode(Mode.WALK, now);
            return Result.RUNNING;
        }
        if (hopStart != null && feet.distanceTo(hopStart) < 1.2) {
            if (now - hopAt < 1_500L) return Result.RUNNING;
            return abortWarp(mc, "the warp did not move us");
        }
        si++;
        wi = 0;
        warpHop = null;
        progressAt = now;
        progressMark = -1;
        if (si < sections.size() && sections.get(si).walk()) {
            List<Vec3> line = sections.get(si).line();
            double best = Double.MAX_VALUE;
            for (Vec3 p : line) best = Math.min(best, PathLine.flat(feet, p) + Math.abs(feet.y + PathLine.MID - p.y));
            if (best > 3.5) {
                return replan(mc, String.format(Locale.ROOT, "warp landed %.1f b off the line", best));
            }
        }
        EngineRecorder.event(id, "warp landed at " + point(feet));
        setMode(Mode.TURN_FIRST, now);
        return Result.RUNNING;
    }

    private Result abortWarp(Minecraft mc, String why) {
        warpHop = null;
        if (++warpAborts >= 2) {
            warpSuppressedUntil = com.coflnet.pathing.sim.SimClock.millis() + 20_000L;
            warps = false;
            EngineRecorder.event(id, "warps off for 20 s");
        }
        return replan(mc, "warp cancelled: " + why);
    }

    // ------------------------------------------------------------------ jumps

    /**
     * The jump is simulated from here (next tick) and from one tick later. Jump now when now it
     * lands and later it would not (the last good tick = the take-off point for exactly this
     * speed), or when the next tick would already be over the edge.
     */
    private boolean shouldJump(Minecraft mc, LocalPlayer player, Section j, int[] keys) {
        jumpWhy = "";
        boolean vertical = PathLine.flat(j.from(), j.to()) < 0.25;
        if (keys[0] == 0 && keys[1] == 0 && !vertical) {
            jumpWhy = "no key towards the jump";
            return false;
        }
        Level level = mc.level;
        Vec3 feet = player.position();
        Vec3 to = j.to();
        float axisYaw = vertical ? player.getYRot() : (float) yawTo(j.from(), to);
        float camYaw = player.getYRot();
        float inputYaw = moveYaw(camYaw, keys);
        double moved = Math.hypot(feet.x - player.xo, feet.z - player.zo);
        boolean up = to.y > j.from().y + 0.4;
        if (up && to.y - feet.y > PathfinderTuning.stats().jumpHeight() + 0.25) {
            jumpWhy = String.format(Locale.ROOT, "wall %.1f higher than the real jump", to.y - feet.y);
            return false;
        }
        // "long" means over a gap / down - a step up stays a step up even when its landing point
        // was set further ahead (that made every step a long jump: waiting at the wall for speed)
        boolean longJump = !up && PathLine.flat(j.from(), to) > 1.2;
        if (!roomToJump(level, feet, Math.max(0.6, to.y - feet.y))) {
            jumpWhy = "block above the head";
            return false;
        }
        if (up && to.y - feet.y <= 1.25) {
            if (player.horizontalCollision) return true; // at the step: jump, no pushing
            double dx = to.x - j.from().x, dz = to.z - j.from().z, dl = Math.hypot(dx, dz);
            double before = dl < 1.0E-6 ? 0 : ((j.from().x - feet.x) * dx + (j.from().z - feet.z) * dz) / dl;
            if (before <= Math.max(0.25, moved * 1.5)) return true; // at the take-off point
        }
        Vec3 vel = player.getDeltaMovement();
        double accel = vertical ? 0.0 : Math.max(0.05, moved * (1.0 - AirMath.GROUND_FRICTION));
        boolean sprint = !vertical && player.isSprinting();
        AirMath.Landing walkOn = AirMath.jump(level, feet, vel, camYaw, inputYaw, sprint, accel, 1);
        boolean edge = walkOn == null && PathLine.flat(feet, j.from()) < 1.2;
        // the speed must point along the jump (not only be fast enough), and for a sprint jump
        // the camera too - the push goes where we look
        float velYaw = moved > 0.03 ? (float) yawTo(new Vec3(player.xo, 0, player.zo), new Vec3(feet.x, 0, feet.z)) : inputYaw;
        float velOff = Math.abs(Mth.wrapDegrees(velYaw - axisYaw));
        float camOff = Math.abs(Mth.wrapDegrees(camYaw - axisYaw));
        if (longJump && !edge && (velOff > 12f || sprint && camOff > 15f)) {
            jumpWhy = String.format(Locale.ROOT, "run-up not straight (speed %.0f, camera %.0f deg off)", velOff, camOff);
            return false;
        }
        // The flight is planned with all keys let go after the take-off. Jump in the tick from
        // which that lands closest to the planned landing point: one tick later would be worse
        // (further past it, or over the edge).
        AirMath.Landing now = AirMath.jump(level, feet, vel, camYaw, inputYaw, sprint, accel, 0, false);
        if (!lands(now, j)) {
            if (edge && to.y < j.from().y - 3.0) {
                // only over a deep drop: stop instead of jumping into it
                if (!edgeHold) {
                    edgeHold = true;
                    edgeHoldSince = com.coflnet.pathing.sim.SimClock.millis();
                    EngineRecorder.event(id, String.format(Locale.ROOT,
                            "jump would not land from the edge at %s (speed %.2f, %.0f deg off) - stopping",
                            point(feet), moved, velOff));
                }
            }
            jumpWhy = "the arc would not land (" + (now == null ? "no landing" : point(now.pos())) + ")";
            return false;
        }
        edgeHold = false;
        AirMath.Landing later = AirMath.jump(level, feet, vel, camYaw, inputYaw, sprint, accel, 1, false);
        boolean jump = edge || !lands(later, j)
                || PathLine.flat(later.pos(), to) >= PathLine.flat(now.pos(), to) - 0.02;
        if (!jump) jumpWhy = "a later take-off lands closer";
        if (jump) {
            EngineRecorder.event(id, String.format(Locale.ROOT,
                    "jump at %s speed %.2f, %.0f deg off the axis -> lands %s in %d ticks (planned %s)",
                    point(feet), moved, velOff, point(now.pos()), now.ticks(), point(to)));
        }
        return jump;
    }

    /**
     * The nearest point ahead on the line that lies in front of us (within 75 degrees of where we
     * are going), at least a little further than we are off the line.
     */
    private static Vec3 rejoinAhead(LocalPlayer player, List<Vec3> line, double along, double total, double lateral) {
        float h = heading(player);
        Vec3 feet = player.position();
        for (double d = Math.max(1.0, lateral + 0.6); d <= 8.0; d += 0.5) {
            Vec3 p = pointAt(line, Math.min(total, along + d));
            if (Math.abs(Mth.wrapDegrees((float) yawTo(feet, p) - h)) < 75f) return p;
            if (along + d >= total) break;
        }
        return pointAt(line, Math.min(total, along + Math.max(1.6, lateral + 1.0)));
    }

    /** Where we are going: the speed when we move, else the camera. */
    private static float heading(LocalPlayer player) {
        double mx = player.getX() - player.xo, mz = player.getZ() - player.zo;
        if (Math.hypot(mx, mz) > 0.06) return (float) (Math.toDegrees(Math.atan2(mz, mx)) - 90.0);
        return player.getYRot();
    }

    /**
     * The focus point {@code along} blocks on the line; past the end of the line it goes on
     * straight: towards the landing / where the line goes on after a jump or drop, otherwise in
     * the line's last direction. It never stops at our feet.
     */
    private Vec3 focusPoint(List<Vec3> line, double along, double total, Section next, int index) {
        if (along <= total || line.isEmpty()) return line.isEmpty() ? Vec3.ZERO : pointAt(line, along);
        Vec3 end = line.get(line.size() - 1);
        Vec3 toward;
        if (next != null && (next.kind() == Kind.JUMP || next.kind() == Kind.DROP || next.kind() == Kind.CLIMB)) {
            toward = next.kind() == Kind.DROP ? rejoinPoint(index + 1) : next.to().add(0, PathLine.MID, 0);
        } else if (line.size() > 1) {
            Vec3 before = line.get(line.size() - 2);
            toward = end.add(end.subtract(before).normalize().scale(4));
        } else {
            toward = goal == null ? end : Vec3.atBottomCenterOf(goal).add(0, PathLine.MID, 0);
        }
        Vec3 d = new Vec3(toward.x - end.x, toward.y - end.y, toward.z - end.z);
        double len = d.horizontalDistance();
        if (len < 1.0E-6) return end;
        double over = Math.min(along - total, Math.max(len, 1.5));
        return end.add(d.x / len * over, d.y / Math.max(len, 1.0) * over, d.z / len * over);
    }

    /** Run-up length before a jump: long jumps need a straight run. */
    private static double runUp(Section jump) {
        return PathLine.flat(jump.from(), jump.to()) > 1.2 ? 3.0 : 1.5;
    }

    /** A point {@code ahead} blocks further on the jump axis (from -> to), seen from where we are. */
    private static Vec3 onAxis(Vec3 feet, Vec3 from, Vec3 to, double ahead) {
        double dx = to.x - from.x, dz = to.z - from.z;
        double len = Math.hypot(dx, dz);
        if (len < 1.0E-6) return to;
        dx /= len;
        dz /= len;
        double t = (feet.x - from.x) * dx + (feet.z - from.z) * dz;
        return new Vec3(from.x + dx * (t + ahead), to.y, from.z + dz * (t + ahead));
    }

    /**
     * {forward, strafe} (strafe +1 = A / left, -1 = D / right) whose real walking direction with
     * the camera as it is right now points best at {@code target}. Not precise: W already for
     * up to 40 degrees (the camera brings it round); precise: the nearest of the eight directions.
     */
    private int[] keysFor(LocalPlayer player, Vec3 target, boolean precise) {
        float delta = Mth.wrapDegrees((float) yawTo(player.position(), target) - player.getYRot());
        double a = Math.abs(delta);
        int side = delta < 0 ? 1 : -1;
        boolean wasW = lastKeys[0] == 1 && lastKeys[1] == 0;
        double wOnly = (precise ? 15 : 40) + (wasW ? 5 : 0);
        int[] k;
        if (a < wOnly) k = new int[]{1, 0};
        else if (a < (precise ? 67.5 : 75)) k = new int[]{1, side};
        else if (a < 115) k = new int[]{0, side};
        else k = new int[]{0, 0}; // behind us: the camera turns first
        lastKeys = k;
        return k;
    }

    /** The walking direction (yaw) of the keys with this camera yaw. */
    private static float moveYaw(float camYaw, int[] keys) {
        if (keys[0] > 0) return camYaw + (keys[1] > 0 ? -45f : keys[1] < 0 ? 45f : 0f);
        if (keys[0] == 0 && keys[1] != 0) return camYaw + (keys[1] > 0 ? -90f : 90f);
        return camYaw;
    }

    /** On the landing height, not short of it, and not far past it. */
    private static boolean lands(AirMath.Landing l, Section j) {
        if (l == null) return false;
        Vec3 dir = j.to().subtract(j.from());
        double len = Math.hypot(dir.x, dir.z);
        double past = len < 1.0E-6 ? 1 : ((l.pos().x - j.from().x) * dir.x + (l.pos().z - j.from().z) * dir.z) / len;
        if (Math.abs(l.pos().y - j.to().y) >= 0.35) return false;
        if (j.to().y > j.from().y + 0.4) return past > 0.2 && PathLine.flat(l.pos(), j.to()) < 2.5; // up: on top = landed
        return PathLine.flat(l.pos(), j.to()) < 1.6 && past > len - 0.6;
    }

    private void fireJump(Minecraft mc, long now) {
        jumped = true;
        jumpAt = now;
        jumpHold = 2;
        mc.options.keyJump.setDown(true);
        ownsKeys = true;
    }

    /** In the air: of the possible key combinations the one that lands closest to the target. */
    private void steerAir(Minecraft mc, LocalPlayer player, Vec3 target) {
        Level level = mc.level;
        boolean sprint = player.isSprinting();
        int bestF = 0, bestS = 0;
        double bestScore = Double.MAX_VALUE;
        for (int f = 0; f >= -2; f = f == 0 ? 1 : f == 1 ? -1 : -2) {
            if (f == -2) break;
            for (int st = 0; st >= -1; st = st == 0 ? 1 : st == 1 ? -1 : -2) {
                AirMath.Landing l = AirMath.fly(level, player.position(), player.getDeltaMovement(),
                        player.getYRot(), f, st, sprint);
                double score = l == null ? 50
                        : PathLine.flat(l.pos(), target) + (Math.abs(l.pos().y - target.y) > 0.6 ? 6 : 0);
                score += (f != 0 ? 0.12 : 0) + (st != 0 ? 0.12 : 0); // keys off unless clearly better
                if (score < bestScore) {
                    bestScore = score;
                    bestF = f;
                    bestS = st;
                }
            }
        }
        mc.options.keyUp.setDown(bestF == 1);
        mc.options.keyDown.setDown(bestF == -1);
        mc.options.keySprint.setDown(false);
        mc.options.keyLeft.setDown(bestS > 0);
        mc.options.keyRight.setDown(bestS < 0);
        ownsKeys = true;
    }

    // ------------------------------------------------------------------ what is in front

    private enum Obstacle { NONE, STEP, BLOCKED }

    /** One block-length look along where we are really going. */
    private static Obstacle probe(Level level, LocalPlayer player, double speed) {
        Vec3 feet = player.position();
        Vec3 v = player.getDeltaMovement();
        double vx = v.x, vz = v.z;
        double len = Math.hypot(vx, vz);
        if (len < 0.03) {
            double r = Math.toRadians(player.getYRot());
            vx = -Math.sin(r);
            vz = Math.cos(r);
            len = 1;
        }
        double dist = 0.5 + speed * 3.0;
        double x = feet.x + vx / len * dist, z = feet.z + vz / len * dist;
        double g = PathLine.groundNear(level, x, z, feet.y);
        if (g == Double.POSITIVE_INFINITY) {
            // something taller than a step: a block we can jump onto, or a wall / fence
            double top = PathLine.columnTop(level, BlockPos.containing(x, feet.y + 0.6, z), x, z);
            if (!Double.isNaN(top) && top - feet.y <= 1.25 && PathLine.headClear(level, x, z, top)
                    && PathLine.headClear(level, feet.x, feet.z, feet.y + (top - feet.y))) {
                return Obstacle.STEP;
            }
            return Obstacle.BLOCKED;
        }
        if (g == Double.NEGATIVE_INFINITY || feet.y - g > 1.1) return Obstacle.BLOCKED; // a hole the line does not have
        if (!PathLine.headClear(level, x, z, g)) return Obstacle.BLOCKED;
        return Obstacle.NONE;
    }

    /** Free space above the head to get {@code rise} blocks up (a low tunnel is fine for one step). */
    private static boolean roomToJump(Level level, Vec3 feet, double rise) {
        double top = 1.8 + Math.min(1.25, rise + 0.05);
        for (double[] c : new double[][]{{0, 0}, {0.29, 0.29}, {0.29, -0.29}, {-0.29, 0.29}, {-0.29, -0.29}}) {
            for (double h = 1.85; h <= top + 1.0E-6; h += 0.25) {
                if (PathLine.solidAt(level, feet.x + c[0], feet.y + h, feet.z + c[1])) return false;
            }
        }
        return true;
    }

    /** +1 = the line is to our left (A), -1 = to our right (D). */
    private static int sideTo(LocalPlayer player, Vec3 onLine) {
        double r = Math.toRadians(player.getYRot());
        double lx = Math.cos(r), lz = Math.sin(r); // Minecraft's "left" for this yaw
        double dx = onLine.x - player.getX(), dz = onLine.z - player.getZ();
        double d = dx * lx + dz * lz;
        return Math.abs(d) < 0.1 ? 0 : d > 0 ? 1 : -1;
    }

    // ------------------------------------------------------------------ helpers

    private void progress(double mark, long now) {
        if (mark > progressMark + 0.5) {
            progressMark = mark;
            progressAt = now;
            nudged = false;
        }
    }

    /** Stuck: a hop after 2.5 s, a new plan after 5 s. */
    private Result watchProgress(Minecraft mc, Vec3 feet, long now) {
        if (mode != Mode.WALK) return Result.RUNNING;
        if (now - progressAt > 5_000L) return replan(mc, "stuck");
        if (now - progressAt > 2_500L && !nudged) {
            nudged = true;
            if (!roomToJump(Minecraft.getInstance().level, feet, 1.0)) return Result.RUNNING; // a ceiling: no hop
            jumpHold = 2;
            EngineRecorder.event(id, "no progress, a hop");
        }
        return Result.RUNNING;
    }

    private Result replan(Minecraft mc, String why) {
        releaseKeys(mc);
        EngineRecorder.event(id, "replan: " + why);
        System.out.println("[PathEngine] " + engineName + " new plan (" + why + ")");
        if (++replans > 8) {
            status = "Stuck";
            EngineRecorder.end(id, "stecken geblieben: " + why);
            sections = new ArrayList<>();
            planFailed = true;
            return Result.FAILED;
        }
        sections = new ArrayList<>();
        si = 0;
        wi = 0;
        warpHop = null;
        setMode(Mode.WALK, com.coflnet.pathing.sim.SimClock.millis());
        plan(Pending.FRESH);
        return Result.RUNNING;
    }

    private void setMode(Mode m, long now) {
        mode = m;
        modeSince = now;
    }

    /** The first point of the walking line after the gap {@code gapIndex} (mid height). */
    private Vec3 rejoinPoint(int gapIndex) {
        Section gap = sections.get(gapIndex);
        if (gapIndex + 1 < sections.size() && sections.get(gapIndex + 1).walk()) {
            List<Vec3> line = sections.get(gapIndex + 1).line();
            if (!line.isEmpty()) return line.get(0);
        }
        return gap.to().add(0, PathLine.MID, 0);
    }

    private double remaining(Vec3 feet) {
        double sum = 0;
        for (int k = si; k < sections.size(); k++) {
            Section s = sections.get(k);
            sum += s.walk() ? PathLine.length(s.line()) : PathLine.flat(s.from(), s.to());
        }
        return sum;
    }

    private static boolean straightFor(List<Vec3> line, double from, double dist) {
        if (line.size() < 2) return false;
        double total = PathLine.length(line);
        if (total - from < dist) return false;
        Vec3 a = pointAt(line, from), b = pointAt(line, from + 1.0), c = pointAt(line, from + dist);
        double h1 = Math.atan2(b.z - a.z, b.x - a.x), h2 = Math.atan2(c.z - b.z, c.x - b.x);
        return Math.abs(Mth.wrapDegrees(Math.toDegrees(h2 - h1))) < 25.0;
    }

    private static Vec3 pointAt(List<Vec3> line, double dist) {
        double left = dist;
        for (int i = 0; i + 1 < line.size(); i++) {
            double d = PathLine.flat(line.get(i), line.get(i + 1));
            if (left <= d) return d < 1.0E-6 ? line.get(i + 1) : line.get(i).lerp(line.get(i + 1), left / d);
            left -= d;
        }
        return line.get(line.size() - 1);
    }

    private static double along(List<Vec3> line, int upTo) {
        double sum = 0;
        for (int i = 0; i < upTo && i + 1 < line.size(); i++) sum += PathLine.flat(line.get(i), line.get(i + 1));
        return sum;
    }

    private static double[] project(Vec3 p, Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        double len2 = dx * dx + dz * dz;
        double t = len2 < 1.0E-9 ? 1 : Math.clamp(((p.x - a.x) * dx + (p.z - a.z) * dz) / len2, 0.0, 1.0);
        return new double[]{Math.hypot(p.x - (a.x + dx * t), p.z - (a.z + dz * t)), t};
    }

    private static double yawTo(Vec3 from, Vec3 to) {
        return Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90.0;
    }

    private static float yawError(LocalPlayer player, Vec3 point) {
        return Math.abs(Mth.wrapDegrees((float) yawTo(player.position(), point) - player.getYRot()));
    }

    private static float aimError(LocalPlayer player, Vec3 point) {
        Vec3 eye = player.getEyePosition();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz)));
        return Math.max(Math.abs(Mth.wrapDegrees(yaw - player.getYRot())), Math.abs(pitch - player.getXRot()));
    }

    private void sample(Minecraft mc, LocalPlayer player, String what, float err, long now) {
        if (now - lastSampleAt < 1_000L) return;
        lastSampleAt = now;
        String line = String.format(Locale.ROOT, "%s speed=%.2f keys=%s", what,
                player.getDeltaMovement().horizontalDistance(), keys(mc));
        EngineRecorder.sample(id, line, err, false, mode == Mode.TURN_FIRST);
    }

    private static String keys(Minecraft mc) {
        StringBuilder out = new StringBuilder();
        if (mc.options.keyUp.isDown()) out.append('W');
        if (mc.options.keyLeft.isDown()) out.append('A');
        if (mc.options.keyDown.isDown()) out.append('S');
        if (mc.options.keyRight.isDown()) out.append('D');
        if (mc.options.keySprint.isDown()) out.append('+');
        if (mc.options.keyJump.isDown()) out.append('J');
        return out.isEmpty() ? "-" : out.toString();
    }

    private static String point(Vec3 p) {
        return String.format(Locale.ROOT, "%.1f,%.1f,%.1f", p.x, p.y, p.z);
    }

    private String describe() {
        int walk = 0, jump = 0, drop = 0, tp = 0, ew = 0, climb = 0;
        for (Section s : sections) {
            switch (s.kind()) {
                case WALK -> walk++;
                case JUMP -> jump++;
                case DROP -> drop++;
                case TELEPORT -> tp++;
                case ETHERWARP -> ew++;
                case CLIMB -> climb++;
            }
        }
        return "path: " + walk + " lines, " + jump + " jumps, " + drop + " drops, " + climb + " climbs, "
                + tp + " teleports, " + ew + " etherwarps";
    }

    /**
     * For drawing: {x, y, z, kind, connect} per point. kind 0 = line, 1 jump, 2 teleport,
     * 3 etherwarp, 4 drop (take-off and landing markers, no line between); connect = 1 when the
     * point continues the line from the point before.
     */
    public static List<double[]> overlay(Vec3 from) {
        PathEngine e = shown;
        if (e == null || e.goal == null || com.coflnet.pathing.sim.SimClock.millis() - shownAt > 500L) return List.of();
        List<Section> secs = e.sections;
        List<double[]> out = new ArrayList<>();
        for (int k = Math.max(0, e.si); k < secs.size() && out.size() < 400; k++) {
            Section s = secs.get(k);
            if (s.walk()) {
                List<Vec3> line = s.line();
                int start = k == e.si ? Math.min(e.wi, line.size() - 1) : 0;
                for (int i = start; i < line.size(); i++) {
                    Vec3 p = line.get(i);
                    out.add(new double[]{p.x, p.y, p.z, 0, i > start ? 1 : 0, along(line, i)});
                }
            } else {
                int kind = switch (s.kind()) {
                    case JUMP -> 1;
                    case TELEPORT -> 2;
                    case ETHERWARP -> 3;
                    default -> 4;
                };
                out.add(new double[]{s.from().x, s.from().y + PathLine.MID, s.from().z, kind, 0, 0});
                out.add(new double[]{s.to().x, s.to().y + PathLine.MID, s.to().z, kind, 0, 0});
            }
        }
        return out;
    }

    private static IBaritone primary() {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
