package com.coflnet.pathing;

import baritone.api.IBaritone;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks Baritone's path as a tightened line instead of block by block.
 * <ol>
 *   <li>Baritone plans as always (block to block).</li>
 *   <li>The path is lifted onto a line and pulled tight: every corner that can be cut by a
 *       straight walkable line (floor all the way, room for the player's width, steps up to
 *       0.6, drops ok) disappears. Jumps stay as fixed points.</li>
 *   <li>We follow that line (forward + steering, a point ~3 blocks ahead on it): speed and the
 *       exact height do not matter while we stay within the corridor. Baritone is paused
 *       meanwhile (it keeps its path).</li>
 *   <li>Out of the corridor, at the end of the line, before a warp or on a hard part
 *       (narrow, parkour): Baritone walks again as before.</li>
 * </ol>
 */
public final class LineFollower {
    private record Node(Vec3 pos, boolean jump) {
    }

    private static final double CORRIDOR = 1.3;
    private static final double LOOKAHEAD = 3.0;
    private static final double HALF_WIDTH = 0.3;
    private static final int MAX_NODES = 48;

    private static List<Node> line;
    private static int segment;
    private static boolean active;
    private static boolean ownsKeys;
    private static IBaritone registeredOn;
    private static int jumpCooldown;
    private static long retryAfter;
    private static boolean hard;
    private static boolean continuing;

    /** Holds Baritone (with its path) while we walk the line. */
    private static final IBaritoneProcess PAUSE = new IBaritoneProcess() {
        @Override
        public boolean isActive() {
            return active;
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
            return 900.0; // below the AOTV warp (1000)
        }

        @Override
        public String displayName0() {
            return "Line follow";
        }
    };

    private LineFollower() {
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * Every tick while a bot walks with Baritone. Returns the point the camera should look at
     * while the line is followed, or null when Baritone walks (as before).
     */
    public static Vec3 tick(Minecraft mc, IBaritone baritone) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || baritone == null || !PathfinderConfig.get().lineFollow
                || AotvAssist.isBusy() || mc.gui.screen() != null) {
            stop(mc);
            return null;
        }
        if (registeredOn != baritone) {
            baritone.getPathingControlManager().registerProcess(PAUSE);
            registeredOn = baritone;
        }
        long now = com.coflnet.pathing.sim.SimClock.millis();
        if (!active) {
            if (now < retryAfter || !player.onGround() || !start(mc, baritone, player)) return null;
        }
        Vec3 feet = player.position();
        // where on the line are we (only forward, a few segments ahead)
        int best = segment;
        double bestDist = Double.MAX_VALUE;
        double bestT = 0;
        for (int i = segment; i < Math.min(line.size() - 1, segment + 4); i++) {
            double[] r = flatProject(feet, line.get(i).pos(), line.get(i + 1).pos());
            if (r[0] < bestDist - 1.0E-6) {
                bestDist = r[0];
                best = i;
                bestT = r[1];
            }
        }
        segment = best;
        if (bestDist > (hard ? 0.75 : CORRIDOR)) {
            // off the line: Baritone takes over again (it re-plans from here if needed)
            stop(mc);
            retryAfter = now + 600;
            return null;
        }
        Vec3 end = line.get(line.size() - 1).pos();
        if (segment == line.size() - 2 && bestT > 0.85 || flat(feet, end) < 1.2) {
            // end of this piece: the next piece of the same path right away (no stop, no hand-over)
            boolean keys = ownsKeys;
            active = false;
            if (continuing || !player.onGround() || !start(mc, baritone, player)) {
                ownsKeys = keys;
                stop(mc); // nothing left to pull tight: Baritone goes on with the rest
                return null;
            }
            ownsKeys = keys;
            continuing = true;
            try {
                return tick(mc, baritone);
            } finally {
                continuing = false;
            }
        }
        Vec3 look = pointAhead(segment, bestT, LOOKAHEAD);
        float yaw = (float) (Math.toDegrees(Math.atan2(look.z - feet.z, look.x - feet.x)) - 90.0);
        float off = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - player.getYRot()));
        boolean forward = off < 45f;
        double left = remaining(segment, bestT);
        mc.options.keyUp.setDown(forward);
        mc.options.keySprint.setDown(forward && off < 15f && left > 3.0 && PathfinderConfig.get().allowSprint);
        // jumps stay fixed points: jump right before them, or when a step is in the way
        boolean jump = false;
        if (jumpCooldown > 0) {
            jumpCooldown--;
        } else if (player.onGround()) {
            Node next = line.get(segment + 1);
            if (next.jump() && flat(feet, next.pos()) < 1.25) jump = true;
            if (forward && player.horizontalCollision) jump = true;
            if (jump) jumpCooldown = 8;
        }
        mc.options.keyJump.setDown(jump);
        ownsKeys = true;
        return new Vec3(look.x, Math.max(look.y + 1.4, player.getEyeY() - 0.4), look.z);
    }

    public static void stop(Minecraft mc) {
        if (ownsKeys && mc.options != null) {
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.options.keyJump.setDown(false);
        }
        ownsKeys = false;
        active = false;
        line = null;
        segment = 0;
    }

    // ------------------------------------------------------------------ building the line

    private static boolean start(Minecraft mc, IBaritone baritone, LocalPlayer player) {
        // hard parts (narrow, jump after jump): still a line, but short pieces and a tight corridor
        hard = PathDifficulty.isHard();
        IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null || executor.getPath() == null) return false;
        List<BetterBlockPos> positions = executor.getPath().positions();
        int from = Math.max(0, executor.getPosition());
        {
            int nearest = from;
            double nd = Double.MAX_VALUE;
            for (int i = from; i < Math.min(positions.size(), from + 80); i++) {
                BetterBlockPos q = positions.get(i);
                double d = Math.hypot(q.getX() + 0.5 - player.getX(), q.getZ() + 0.5 - player.getZ())
                        + Math.abs(q.getY() - player.getY()) * 0.5;
                if (d < nd) {
                    nd = d;
                    nearest = i;
                }
            }
            from = nearest;
        }
        int to = Math.min(positions.size(), from + MAX_NODES);
        if (to - from < 4) return false;
        List<Node> raw = new ArrayList<>();
        for (int i = from; i < to; i++) {
            BetterBlockPos p = positions.get(i);
            boolean jump = false;
            if (i > from) {
                BetterBlockPos q = positions.get(i - 1);
                // a full block up, or a gap (parkour): a jump has to happen here
                jump = p.getY() > q.getY() || Math.abs(p.getX() - q.getX()) + Math.abs(p.getZ() - q.getZ()) > 2;
            }
            raw.add(new Node(new Vec3(p.getX() + 0.5, groundTop(mc.level, p), p.getZ() + 0.5), jump));
        }
        if (flat(player.position(), raw.get(0).pos()) > 1.5) return false;
        // pull tight: from each kept point, the furthest point that a straight walkable line reaches
        List<Node> tight = new ArrayList<>();
        tight.add(new Node(player.position(), false));
        int i = 0;
        while (i < raw.size() - 1) {
            int j = i + 1;
            for (int k = Math.min(raw.size() - 1, i + (hard ? 3 : 20)); k > i + 1; k--) {
                boolean jumpInside = false;
                for (int m = i + 1; m < k; m++) {
                    if (raw.get(m).jump()) {
                        jumpInside = true;
                        break;
                    }
                }
                if (jumpInside) continue;
                Vec3 a = i == 0 ? player.position() : raw.get(i).pos();
                if (walkable(mc.level, a, raw.get(k).pos())) {
                    j = k;
                    break;
                }
            }
            tight.add(raw.get(j));
            i = j;
        }
        if (tight.size() < 2 || tight.size() > raw.size()) return false;
        line = tight;
        segment = 0;
        active = true;
        jumpCooldown = 0;
        return true;
    }

    /** A straight walk from a to b: floor under the whole player width, steps <= 0.6 up, drops <= 3.5. */
    static boolean walkable(Level level, Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        double dist = Math.hypot(dx, dz);
        if (dist < 0.5) return true;
        double ux = dx / dist, uz = dz / dist;
        double px = -uz, pz = ux; // sideways
        int steps = (int) Math.ceil(dist / 0.3);
        double y = a.y;
        for (int s = 1; s <= steps; s++) {
            double f = s / (double) steps;
            double x = a.x + dx * f, z = a.z + dz * f;
            double center = Double.NaN;
            for (double off : new double[]{0.0, HALF_WIDTH, -HALF_WIDTH}) {
                double sx = x + px * off, sz = z + pz * off;
                double g = ground(level, sx, y, sz);
                if (Double.isNaN(g)) return false;
                if (g - y > 0.6 || y - g > 3.5) return false;
                if (!headRoom(level, sx, Math.max(g, y), sz)) return false;
                if (off == 0.0) center = g;
            }
            y = center;
        }
        return Math.abs(y - b.y) < 1.0;
    }

    /** Height of the floor at (x, z) near feet height {@code y}; NaN for a wall, a hole, lava or no head room. */
    private static double ground(Level level, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int top = (int) Math.floor(y + 0.6);
        // nothing at head height
        for (double h : new double[]{y + 1.0, y + 1.75}) {
            BlockPos head = new BlockPos(bx, (int) Math.floor(h), bz);
            if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) return Double.NaN;
        }
        for (int by = top; by >= (int) Math.floor(y) - 4; by--) {
            BlockPos pos = new BlockPos(bx, by, bz);
            BlockState state = level.getBlockState(pos);
            if (state.getFluidState().is(FluidTags.LAVA)) return Double.NaN;
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) continue;
            double t = by + shape.max(Direction.Axis.Y);
            if (t > y + 0.65) return Double.NaN; // a wall, not a floor
            return t;
        }
        return Double.NaN;
    }

    /** 1.85 blocks of free space above {@code feet} at (x, z) (no low ceiling, no overhang). */
    private static boolean headRoom(Level level, double x, double feet, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (double h = feet + 0.1; h < feet + 1.85; h += 0.45) {
            BlockPos pos = new BlockPos(bx, (int) Math.floor(h), bz);
            VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
            if (!shape.isEmpty() && pos.getY() + shape.min(Direction.Axis.Y) < feet + 1.85
                    && pos.getY() + shape.max(Direction.Axis.Y) > feet + 0.05) {
                return false;
            }
        }
        return true;
    }

    /** Feet can stand at this exact point (floor within a step, no block at feet/head). */
    static boolean standable(Level level, Vec3 p) {
        double g = ground(level, p.x, p.y, p.z);
        return !Double.isNaN(g) && Math.abs(g - p.y) <= 0.65 && headRoom(level, p.x, g, p.z);
    }

    /** The floor height at a sample of the smoothed line (NaN when there is none). */
    static double floorAt(Level level, Vec3 p) {
        return ground(level, p.x, p.y + 0.45, p.z);
    }

    static double groundTop(Level level, BetterBlockPos p) {
        BlockPos below = new BlockPos(p.getX(), p.getY() - 1, p.getZ());
        VoxelShape shape = level.getBlockState(below).getCollisionShape(level, below);
        return shape.isEmpty() ? p.getY() : below.getY() + shape.max(Direction.Axis.Y);
    }

    // ------------------------------------------------------------------ geometry

    /** {flat distance to the segment, position 0..1 along it}. */
    private static double[] flatProject(Vec3 p, Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        double len2 = dx * dx + dz * dz;
        double t = len2 < 1.0E-9 ? 1 : Math.clamp(((p.x - a.x) * dx + (p.z - a.z) * dz) / len2, 0.0, 1.0);
        double cx = a.x + dx * t, cz = a.z + dz * t;
        return new double[]{Math.hypot(p.x - cx, p.z - cz), t};
    }

    private static Vec3 pointAhead(int seg, double t, double ahead) {
        Vec3 a = line.get(seg).pos(), b = line.get(seg + 1).pos();
        Vec3 at = a.lerp(b, t);
        double left = ahead;
        int i = seg;
        Vec3 cur = at;
        while (true) {
            Vec3 next = line.get(i + 1).pos();
            double d = flat(cur, next);
            if (d >= left || i + 2 >= line.size()) {
                return d < 1.0E-6 ? next : cur.lerp(next, Math.min(1.0, left / d));
            }
            left -= d;
            cur = next;
            i++;
        }
    }

    private static double remaining(int seg, double t) {
        Vec3 a = line.get(seg).pos(), b = line.get(seg + 1).pos();
        double sum = flat(a.lerp(b, t), b);
        for (int i = seg + 1; i < line.size() - 1; i++) sum += flat(line.get(i).pos(), line.get(i + 1).pos());
        return sum;
    }

    private static double flat(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }
}
