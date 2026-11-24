/*
 * This file is part of Baritone (Air Client patch).
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/**
 * Air Client: a sprint jump over a gap in one of 16 directions, also diagonal and onto lower or
 * higher blocks - the jumps a player with Speed / Jump Boost makes (6-8 block gaps, 5-8 down).
 * Vanilla parkour only knows straight 1-3 block gaps on one level.
 *
 * <p>Reach is computed with Minecraft's movement laws: vertical (v - 0.08) * 0.98 per tick from the
 * jump that reaches {@code jumpHeight}; horizontal: ground sprint speed + 0.2 jump boost, then
 * v * 0.91 + 0.026 per tick in the air. The body (2 blocks) must be free along the arc. The client's
 * path engine flies the jump itself with the real physics; this only decides that it is possible.
 */
public class MovementLongJump extends Movement {
    public static final int SECTORS = 16;
    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};
    private static final int MAX_TICKS = 80;
    /** Center of the take-off block to the take-off point (edge) plus landing tolerance (half a player). */
    private static final double TAKEOFF_AND_LANDING_SLACK = 1.2;
    /** Jump tick's extra speed, calibrated: vanilla sprint jump (0.28 b/t) clears a 4 block gap, not 5. */
    private static final double JUMP_BOOST_SPEED = 0.12;
    /** The run-up from the block centre to the edge is short: only part of the sprint speed is there. */
    private static final double RUN_UP_SPEED = 0.6;

    private static double cachedHeight = Double.NaN;
    private static double cachedGround = Double.NaN;
    private static String cachedCalibration = "";
    private static double[] cachedY;
    private static double[] cachedX;

    private final int sector;
    private boolean airborne;
    /** Run-up done (stepped back on the take-off block, now sprinting forward). */
    private boolean runUp;
    private boolean backing;

    private MovementLongJump(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, int sector) {
        super(baritone, src, dest, EMPTY, dest.below());
        this.sector = sector;
    }

    public static MovementLongJump create(CalculationContext context, BetterBlockPos src, int sector) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, sector, res);
        if (res.cost >= COST_INF) {
            return new MovementLongJump(context.getBaritone(), src, src, sector);
        }
        return new MovementLongJump(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z), sector);
    }

    /** Height (relative to the take-off feet) and horizontal distance after each tick of a sprint jump. */
    private static synchronized double[][] arc(double jumpHeight, double groundSpeed) {
        baritone.api.Settings set = Baritone.settings();
        String calibration = set.longJumpRunUp.value + "/" + set.longJumpBoost.value + "/" + set.longJumpAirAccel.value;
        if (jumpHeight != cachedHeight || groundSpeed != cachedGround || !calibration.equals(cachedCalibration)) {
            cachedCalibration = calibration;
            // jump velocity that peaks at jumpHeight
            double lo = 0.0, hi = 3.0;
            for (int i = 0; i < 40; i++) {
                double mid = (lo + hi) / 2;
                if (peak(mid) < jumpHeight) lo = mid;
                else hi = mid;
            }
            double vy = (lo + hi) / 2;
            double vx = groundSpeed * set.longJumpRunUp.value + set.longJumpBoost.value;
            double y = 0, x = 0;
            double[] ys = new double[MAX_TICKS + 1];
            double[] xs = new double[MAX_TICKS + 1];
            for (int t = 1; t <= MAX_TICKS; t++) {
                y += vy;
                x += vx;
                vy = (vy - 0.08) * 0.98;
                // 0.02, not the sprint 0.026: in the recordings the speed in the air fell to ~0.2
                // (no sprint in the air), and an 8.5 block jump planned with 0.026 fell 2.5 short
                vx = vx * 0.91 + set.longJumpAirAccel.value;
                ys[t] = y;
                xs[t] = x;
            }
            cachedY = ys;
            cachedX = xs;
            cachedHeight = jumpHeight;
            cachedGround = groundSpeed;
        }
        return new double[][]{cachedY, cachedX};
    }

    private static double peak(double vy) {
        double y = 0, best = 0;
        for (int t = 0; t < 60 && vy > 0; t++) {
            y += vy;
            best = Math.max(best, y);
            vy = (vy - 0.08) * 0.98;
        }
        return best;
    }

    public static void cost(CalculationContext context, int x, int y, int z, int sector, MutableMoveResult res) {
        res.cost = COST_INF;
        if (!context.allowParkour || !context.canSprint || !context.allowLongJump) return;
        double angle = sector * (Math.PI * 2 / SECTORS);
        double dx = Math.sin(angle), dz = Math.cos(angle);
        // only over a gap: the next block in that direction has no floor
        int nx = (int) Math.floor(x + 0.5 + dx * 1.0), nz = (int) Math.floor(z + 0.5 + dz * 1.0);
        if (nx == x && nz == z) return;
        if (MovementHelper.canWalkOn(context, nx, y - 1, nz)) return;
        if (!MovementHelper.fullyPassable(context, nx, y, nz) || !MovementHelper.fullyPassable(context, nx, y + 1, nz)) return;
        BlockState standingOn = context.get(x, y - 1, z);
        if (!MovementHelper.canWalkOn(context, x, y - 1, z, standingOn)) return;
        // stairs and slabs are fine to jump from (the players' courses do), ladders / soul sand / water not
        if (MovementHelper.isClimbable(standingOn.getBlock()) || standingOn.is(Blocks.SOUL_SAND)
                || !standingOn.getFluidState().isEmpty()) return;
        if (!context.get(x, y, z).getFluidState().isEmpty()) return;
        if (!MovementHelper.fullyPassable(context, x, y + 2, z)) return; // head room for the jump

        // Calibrated up to Jump Boost IV (~3.3 blocks) and Speed II. SkyBlock reaches a 6-block jump
        // and 500 % speed: planned with that the jumps were far longer than the bot really managed.
        // Planning with less reach only means one long jump fewer, never one that falls short.
        double height = Math.min(Baritone.settings().longJumpMaxHeight.value, Baritone.settings().jumpHeight.value);
        double[][] a = arc(height, Math.min(Baritone.settings().longJumpMaxGroundSpeed.value,
                Baritone.settings().longJumpGroundSpeed.value));
        double[] ay = a[0], ax = a[1];
        int maxUp = Math.max(0, (int) Math.floor(height - 0.2));
        // a planned jump down may be deeper than a walk-off drop (the course is built for it)
        int maxDown = Math.max(0, Math.max(context.maxFallHeightNoWater, Baritone.settings().longJumpMaxDrop.value));
        // landing tick per height difference: first tick after the peak at or below it
        double maxReach = 0;
        int[] landTick = new int[maxUp + maxDown + 1];
        for (int d = -maxDown; d <= maxUp; d++) {
            int tick = -1;
            boolean falling = false;
            for (int t = 1; t <= MAX_TICKS; t++) {
                if (t > 1 && ay[t] < ay[t - 1]) falling = true;
                if (falling && ay[t] <= d + 1.0E-3) {
                    tick = t;
                    break;
                }
            }
            landTick[d + maxDown] = tick;
            if (tick > 0) maxReach = Math.max(maxReach, ax[tick]);
        }
        double slack = Baritone.settings().longJumpSlack.value;
        int maxCells = (int) Math.ceil(maxReach + slack);
        int lastX = x, lastZ = z;
        for (double s = 1.5; s <= maxCells; s += 0.5) {
            int cx = (int) Math.floor(x + 0.5 + dx * s), cz = (int) Math.floor(z + 0.5 + dz * s);
            if (cx == lastX && cz == lastZ) continue;
            lastX = cx;
            lastZ = cz;
            double centre = Math.hypot(cx - x, cz - z);
            if (centre < 1.9) continue; // a normal step / diagonal
            // highest landing first: a floor in this column ends the search either way
            for (int d = maxUp; d >= -maxDown; d--) {
                int ly = y + d;
                if (!MovementHelper.canWalkOn(context, cx, ly - 1, cz)) continue;
                if (!MovementHelper.fullyPassable(context, cx, ly, cz) || !MovementHelper.fullyPassable(context, cx, ly + 1, cz)) {
                    continue;
                }
                int tick = landTick[d + maxDown];
                if (tick < 0 || centre - slack > ax[tick]) return; // a floor we cannot reach: blocked
                if (!arcFree(context, x, y, z, dx, dz, centre, tick, ay, ax)) return;
                // Straight jumps of up to 4 blocks on one level (or one up) are vanilla parkour's: it
                // does them more exactly. The long jump took those too and fell there 22 times in
                // the level 5 runs (it should only cover what parkour cannot: long, diagonal, down).
                boolean cardinal = cx == x || cz == z;
                int dist = Math.abs(cx - x) + Math.abs(cz - z);
                if (cardinal && dist <= 4 && (d == 0 || d == 1) && context.allowParkour) return;
                res.x = cx;
                res.y = ly;
                res.z = cz;
                res.cost = 2 + tick + context.jumpPenalty + Baritone.settings().longJumpPenalty.value;
                return;
            }
            // nothing to stand on in this column: the arc has to pass it
        }
    }

    /**
     * Horizontal distance the current velocity still carries the player (no keys: x0.91 per tick)
     * until the feet are {@code above} blocks lower than now (gravity (vy - 0.08) * 0.98).
     */
    private static double coastDistance(net.minecraft.world.phys.Vec3 v, double above) {
        double vh = Math.hypot(v.x, v.z), vy = v.y, y = 0, dist = 0;
        for (int t = 0; t < 100; t++) {
            y += vy;
            dist += vh;
            vy = (vy - 0.08) * 0.98;
            vh *= 0.91;
            if (vy < 0 && y <= -above) break;
        }
        return dist;
    }

    /** The two body blocks along the flight are free. */
    private static boolean arcFree(CalculationContext context, int x, int y, int z, double dx, double dz,
                                   double centre, int tick, double[] ay, double[] ax) {
        double total = ax[tick];
        for (double s = 0.5; s < centre - 0.5; s += 0.5) {
            // horizontal progress s along the jump -> tick at that point
            double along = Math.max(0, s - 0.8);
            int t = 1;
            while (t < tick && ax[t] < along) t++;
            double h = t <= tick ? ay[t] : ay[tick];
            if (total <= 0) return false;
            int feet = (int) Math.floor(y + h + 0.05);
            // the whole body (0.3 to each side), not only the centre line: diagonal jumps clipped
            // a wall corner with the shoulder (watchdog: WALL at MovementLongJump)
            for (int side = -1; side <= 1; side++) {
                double ox = -dz * 0.3 * side, oz = dx * 0.3 * side;
                int cx = (int) Math.floor(x + 0.5 + dx * s + ox), cz = (int) Math.floor(z + 0.5 + dz * s + oz);
                if (!MovementHelper.fullyPassable(context, cx, feet, cz) || !MovementHelper.fullyPassable(context, cx, feet + 1, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, sector, res);
        if (res.x != dest.x || res.y != dest.y || res.z != dest.z) {
            return COST_INF;
        }
        return res.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        int steps = (int) Math.ceil(Math.hypot(dest.x - src.x, dest.z - src.z) * 2);
        // the whole flight: with Jump Boost the peak is 4+ blocks up, and the feet drift sideways a
        // little - outside these the executor thinks we left the path and switches movement mid-air
        int lo = Math.min(src.y, dest.y) - 1;
        int hi = Math.max(src.y, dest.y) + (int) Math.ceil(Baritone.settings().jumpHeight.value) + 2;
        for (int i = 0; i <= steps; i++) {
            double f = steps == 0 ? 0 : (double) i / steps;
            int px = (int) Math.floor(src.x + 0.5 + (dest.x - src.x) * f);
            int pz = (int) Math.floor(src.z + 0.5 + (dest.z - src.z) * f);
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    for (int py = lo; py <= hi; py++) set.add(new BetterBlockPos(px + ox, py, pz + oz));
                }
            }
        }
        return set;
    }

    @Override
    public void reset() {
        super.reset();
        airborne = false; // the executor restarts this movement (e.g. back on the take-off block)
        runUp = false;
        backing = false;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING;
    }

    /**
     * Run to the edge, jump there, fly to the landing: sprint + forward while the landing is ahead,
     * nothing once above it or past it (turning round in the air killed the speed and the jump fell
     * short). Landing anywhere but the target block = replan.
     */
    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        net.minecraft.world.phys.Vec3 pos = ctx.player().position();
        if (pos.y < Math.min(src.y, dest.y) - 1.5) {
            return state.setStatus(MovementStatus.UNREACHABLE); // fell
        }
        double tx = dest.x + 0.5, tz = dest.z + 0.5;
        double toX = tx - pos.x, toZ = tz - pos.z;
        double left = Math.hypot(toX, toZ);
        boolean onGround = ctx.player().onGround();
        if (airborne && onGround && !ctx.playerFeet().equals(src)) {
            // landed on the planned block, or right next to it on the same height (a slab next to
            // the planned one): the path goes on from there instead of a re-plan from scratch
            boolean there = ctx.playerFeet().equals(dest)
                    || (left < 1.3 && Math.abs(pos.y - dest.y) < 0.7);
            return state.setStatus(there ? MovementStatus.SUCCESS : MovementStatus.UNREACHABLE);
        }
        if (!onGround) airborne = true;
        else if (ctx.playerFeet().equals(src)) airborne = false; // back on the take-off block: new try
        MovementHelper.moveTowards(ctx, state, dest);
        state.setInput(Input.SPRINT, true);
        // the keys move along the camera, which turns at a limited speed: only push where we look
        double jx0 = dest.x - src.x, jz0 = dest.z - src.z;
        float wantYaw = (float) Math.toDegrees(Math.atan2(-jx0, jz0));
        // the direction we really move in this tick (Baritone's own, turn-limited rotation; the
        // camera is separate and smooth)
        float moveYaw = ((baritone.behavior.LookBehavior) baritone.getLookBehavior())
                .plannedMoveYaw(new baritone.api.utils.Rotation(wantYaw, 10f));
        float off = Math.abs(net.minecraft.util.Mth.wrapDegrees(wantYaw - moveYaw));
        // Look along the jump, forced: path smoothing aimed at a later path point ("carrot") and
        // turned the camera - and with it the keys - up to 100 degrees away in mid air.
        float lookYaw = wantYaw;
        if (airborne && left > 1.0) {
            // in the air: aim at the landing from where we are now (corrects a sideways take-off)
            lookYaw = (float) Math.toDegrees(Math.atan2(-toX, toZ));
        }
        // Not forced: a forced rotation set the camera itself, in one tick (the video: 87-153
        // degrees and 40 down at a time). The carrot of path smoothing only works on walking
        // moves, so nothing turns this aim away mid air.
        state.setTarget(new MovementState.MovementTarget(new baritone.api.utils.Rotation(lookYaw, 10f), false));
        if (airborne) {
            net.minecraft.world.phys.Vec3 v = ctx.player().getDeltaMovement();
            boolean ahead = v.x * toX + v.z * toZ > 0;
            float offAir = Math.abs(net.minecraft.util.Mth.wrapDegrees(lookYaw - moveYaw));
            if (left < 0.35 || !ahead && left < 1.5 || offAir > 50f) {
                state.setInput(Input.MOVE_FORWARD, false); // above / just past the landing: drop onto it
                state.setInput(Input.SPRINT, false);
            }
            // Where does the momentum alone carry us until we are down at the landing height?
            // With Jump Boost a short jump is still high above the landing when it gets there and
            // flew on into the gap behind it (level 5: 6 blocks, Jump Boost IV, 1.5 blocks over).
            double drift = coastDistance(v, pos.y - dest.y);
            if (ahead && drift > left + 0.25) {
                state.setInput(Input.MOVE_FORWARD, false);
                state.setInput(Input.SPRINT, false);
                if (drift > left + 0.6 && offAir < 50f) state.setInput(Input.MOVE_BACK, true); // brake
            }
            return state;
        }
        // on the ground: jump at the edge of the take-off block (or once off it)
        double jx = dest.x - src.x, jz = dest.z - src.z;
        double len = Math.hypot(jx, jz);
        double along = len == 0 ? 0 : ((pos.x - (src.x + 0.5)) * jx + (pos.z - (src.z + 0.5)) * jz) / len;
        if (off > 12f && ctx.playerFeet().equals(src)) {
            // first face the jump (the previous move may have come from another side)
            state.setInput(Input.MOVE_FORWARD, false);
            state.setInput(Input.SPRINT, false);
            return state;
        }
        net.minecraft.world.phys.Vec3 v = ctx.player().getDeltaMovement();
        double speed = Math.hypot(v.x, v.z);
        if (!runUp && ctx.playerFeet().equals(src)) {
            if (speed < 0.15) backing = true; // a standing start (moving back counts as fast: decide once)
            double relX = pos.x - (src.x + 0.5), relZ = pos.z - (src.z + 0.5);
            // a long run-up: back to the far side of the block (the centre may go 0.8 from the block
            // centre before the player falls off; 0.6 keeps a margin)
            // with the momentum of the next ticks: backing up on a 1x1 pillar slid off its back
            boolean roomBehind = Math.abs(relX + v.x * 3) < 0.5 && Math.abs(relZ + v.z * 3) < 0.5;
            // no run-up into a wall behind us (it pushed S against the wall for 100+ ticks)
            boolean wallBehind = ctx.player().horizontalCollision
                    || !MovementHelper.canWalkThrough(ctx, new BetterBlockPos(
                    (int) Math.floor(pos.x - jx / Math.max(len, 1e-6) * 0.8), src.y,
                    (int) Math.floor(pos.z - jz / Math.max(len, 1e-6) * 0.8)));
            if (backing && along > -0.55 && roomBehind && !wallBehind) {
                // standing start: step back to the far side of the block first, the long jumps
                // need a sprint run-up (from a standstill they fell 2+ blocks short)
                state.setInput(Input.MOVE_FORWARD, false);
                state.setInput(Input.SPRINT, false);
                state.setInput(Input.MOVE_BACK, true);
                return state;
            }
            runUp = true;
        }
        double forward = len == 0 ? 0 : (v.x * jx + v.z * jz) / len;
        // Jump at the last moment: when the next tick would carry the body off the take-off block
        // (centre more than 0.8 from the block centre on an axis). A fixed point 0.45 past the
        // centre gave away a third of a block of every jump - the failed level 6 jumps.
        double nextX = pos.x + v.x - (src.x + 0.5), nextZ = pos.z + v.z - (src.z + 0.5);
        double ux = len == 0 ? 0 : jx / len, uz = len == 0 ? 0 : jz / len;
        // only the FRONT edge (in the jump direction) counts: after the run-up the back edge is
        // close too, and jumping there (level 6, diagonal jump) flew off sideways, 2 blocks off
        boolean leaving = Math.abs(nextX) > 0.75 && nextX * ux > 0 && Math.abs(ux) > 0.2
                || Math.abs(nextZ) > 0.75 && nextZ * uz > 0 && Math.abs(uz) > 0.2;
        if (!ctx.playerFeet().equals(src) && forward > 0.08 || leaving && forward > 0.1) {
            state.setInput(Input.JUMP, true);
        }
        return state;
    }
}
