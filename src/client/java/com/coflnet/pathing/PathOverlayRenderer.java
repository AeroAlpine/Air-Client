package com.coflnet.pathing;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.debug.DebugValueAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * What the bot is doing, drawn instead of Baritone's own path / goal render:
 * <ul>
 *     <li>the target's hitbox, at the target's smooth rendered position every frame (Baritone's
 *     goal box sat on a goal block that only moved every 2 blocks and wobbled with its
 *     animation);</li>
 *     <li>the path ahead as a dashed line lying on the floor surface (slabs and stairs included),
 *     starting at the player and with the corners rounded off, roughly where the player really
 *     walks. Baritone's line ran through block centres, zig-zagged and cut into the ground.</li>
 * </ul>
 */
public final class PathOverlayRenderer extends EntityHitboxDebugRenderer {
    private static final int PATH_COLOR = 0xE0FF3B3B;
    private static final GizmoStyle TARGET_STYLE = GizmoStyle.strokeAndFill(0xFF35FF6A, 2.0F, 0x2835FF6A);
    private static final GizmoStyle QUEUE_STYLE = GizmoStyle.stroke(0xA0FFD24D, 1.0F);
    private static final double LIFT = 0.06;
    private static final double DASH = 0.45;
    private static final double GAP = 0.3;
    private static final int MAX_POINTS = 48;

    public PathOverlayRenderer() {
        super(Minecraft.getInstance());
    }

    @Override
    public void emitGizmos(double camX, double camY, double camZ,
                           DebugValueAccess debugValues, Frustum frustum, float partialTicks) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !PathfinderConfig.get().showPathOverlay) {
            return;
        }
        try {
            drawTarget(client);
            if (!drawPlan(client, partialTicks)) {
                drawPath(client);
            }
        } catch (RuntimeException ignored) {
            // Baritone's path is replaced on its own thread; a frame without overlay is fine.
        }
    }

    /** walk blue, jump yellow, teleport cyan, etherwarp purple, drop orange. */
    private static final int[] PLAN_COLORS = {0xE04CC2FF, 0xE0FFD24D, 0xE035FFD7, 0xE0C77DFF, 0xE0FF8A3D};

    /**
     * The path engine's plan: the walking line at the middle of the player's height; jumps,
     * drops and warps have no line, only a take-off and a landing marker.
     */
    /** The 0.3.7 engine's line (Commission Bot), drawn as in 0.3.7. */
    private static boolean drawLegacyPlan(Vec3 from) {
        List<double[]> plan = LegacyPathEngine.overlay(from);
        if (plan.size() < 2) {
            return false;
        }
        for (int i = 0; i + 1 < plan.size(); i++) {
            double[] a = plan.get(i), b = plan.get(i + 1);
            int color = PLAN_COLORS[Math.clamp((int) b[3], 0, 3)];
            Vec3 va = new Vec3(a[0], a[1] + 0.9, a[2]);
            Vec3 vb = new Vec3(b[0], b[1] + 0.9, b[2]);
            Gizmos.line(va, vb, color, b[3] >= 2 ? 1.5f : 2.5f);
            if (b[3] >= 1) {
                Gizmos.point(vb, color, 6f);
            }
        }
        return true;
    }

    private static boolean drawPlan(Minecraft client, float partialTicks) {
        Vec3 from = client.player.getPosition(partialTicks);
        List<double[]> plan = PathEngine.overlay(from);
        if (plan.isEmpty()) {
            return drawLegacyPlan(from);
        }
        Vec3 prev = null;
        // the walking line as a dashed line at head height, the dashes flowing towards the goal
        double dashOn = 0.35, period = 0.6;
        double phase = (System.currentTimeMillis() % 100_000L) / 1000.0 * 1.6;
        double along = 0;
        for (double[] p : plan) {
            Vec3 v = new Vec3(p[0], p[1] + 0.7, p[2]);
            int kind = Math.clamp((int) p[3], 0, 4);
            if (kind == 0 && (p[4] < 0.5 || prev == null)) {
                along = p.length > 5 ? p[5] : 0; // dashes anchored to the line, not to where we stand
            }
            if (kind == 0 && p[4] > 0.5 && prev != null) {
                double len = prev.distanceTo(v);
                double t = 0;
                while (t < len) {
                    double pos = (along + t - phase) % period;
                    if (pos < 0) pos += period;
                    double step = pos < dashOn ? Math.min(dashOn - pos, len - t) : Math.min(period - pos, len - t);
                    if (pos < dashOn && step > 0.02) {
                        Gizmos.line(prev.lerp(v, t / len), prev.lerp(v, (t + step) / len), 0xD08FE3FF, 2.0f);
                    }
                    t += Math.max(step, 0.01);
                }
                along += len;
            }
            if (kind != 0) {
                Gizmos.point(v, PLAN_COLORS[kind], 7f); // take-off / landing: a dot, no stick
            }
            prev = kind == 0 ? v : null;
        }
        return true;
    }

    private static void drawTarget(Minecraft client) {
        Entity target = EntityObstacles.target();
        box(client, target, TARGET_STYLE);
        // the next targets the bot already picked: thin yellow outline
        for (Entity queued : EntityObstacles.queue()) {
            if (queued != target) {
                box(client, queued, QUEUE_STYLE);
            }
        }
    }

    private static void box(Minecraft client, Entity entity, GizmoStyle style) {
        if (entity == null || entity.isRemoved() || entity.level() != client.level) {
            return;
        }
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(
                !client.level.tickRateManager().isEntityFrozen(entity));
        Vec3 offset = entity.getPosition(partial).subtract(entity.position());
        Gizmos.cuboid(entity.getBoundingBox().move(offset).inflate(0.04), style);
    }

    private static void drawPath(Minecraft client) {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null || !baritone.getPathingBehavior().isPathing()) {
            return;
        }
        IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null || executor.getPath() == null) {
            return;
        }
        List<BetterBlockPos> positions = executor.getPath().positions();
        int from = Math.max(0, executor.getPosition() + 1);
        if (from >= positions.size()) {
            return;
        }
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 feet = client.player.getPosition(partial);
        Level level = client.level;

        List<Vec3> points = new ArrayList<>();
        points.add(new Vec3(feet.x, feet.y + LIFT, feet.z));
        for (int i = from; i < positions.size() && points.size() < MAX_POINTS; i++) {
            BlockPos pos = positions.get(i);
            points.add(new Vec3(pos.getX() + 0.5, floorHeight(level, pos) + LIFT, pos.getZ() + 0.5));
        }
        dashed(smooth(points));
    }

    /** Chaikin corner cutting, twice: diagonal zig-zags become the curve a player walks. */
    static List<Vec3> smooth(List<Vec3> points) {
        List<Vec3> current = points;
        for (int pass = 0; pass < 2 && current.size() > 2; pass++) {
            List<Vec3> next = new ArrayList<>(current.size() * 2);
            next.add(current.get(0));
            for (int i = 0; i < current.size() - 1; i++) {
                Vec3 a = current.get(i);
                Vec3 b = current.get(i + 1);
                if (Math.abs(a.y - b.y) > 0.6) {
                    // a jump or a drop: keep the step sharp instead of a ramp through the block edge
                    next.add(a);
                    next.add(new Vec3(b.x, a.y, b.z).lerp(a, 0.25));
                    next.add(b);
                    continue;
                }
                next.add(a.lerp(b, 0.25));
                next.add(a.lerp(b, 0.75));
            }
            next.add(current.get(current.size() - 1));
            current = next;
        }
        return current;
    }

    private static void dashed(List<Vec3> points) {
        boolean drawing = true;
        double left = DASH;
        for (int i = 0; i < points.size() - 1; i++) {
            Vec3 a = points.get(i);
            Vec3 b = points.get(i + 1);
            double length = a.distanceTo(b);
            double done = 0;
            while (length - done > 1.0E-4) {
                double step = Math.min(left, length - done);
                Vec3 start = a.lerp(b, done / length);
                Vec3 end = a.lerp(b, (done + step) / length);
                if (drawing) {
                    Gizmos.line(start, end, PATH_COLOR, 2.5f);
                }
                done += step;
                left -= step;
                if (left <= 1.0E-4) {
                    drawing = !drawing;
                    left = drawing ? DASH : GAP;
                }
            }
        }
    }

    /** Top of what the player stands on with feet in {@code feet} (slabs, stairs, carpets ...). */
    static double floorHeight(Level level, BlockPos feet) {
        VoxelShape at = level.getBlockState(feet).getCollisionShape(level, feet);
        if (!at.isEmpty() && at.max(Direction.Axis.Y) <= 0.6) {
            return feet.getY() + at.max(Direction.Axis.Y);
        }
        BlockPos below = feet.below();
        VoxelShape under = level.getBlockState(below).getCollisionShape(level, below);
        return under.isEmpty() ? feet.getY() : below.getY() + under.max(Direction.Axis.Y);
    }
}
