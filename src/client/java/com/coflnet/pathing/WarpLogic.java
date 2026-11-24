package com.coflnet.pathing;

import com.coflnet.pathing.PathLine.Kind;
import com.coflnet.pathing.PathLine.Section;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import pingplus.voicechat.client.etherwarp.EtherwarpHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * When to warp (Air Client path engine).
 * <ul>
 *   <li><b>Etherwarp</b> is planned into the path: where the way goes on with jumps / climbs
 *       (two or more, or one that goes up well), a single etherwarp from the last spot before
 *       them onto a block further on skips the whole lot. The block may be seen from any side -
 *       from below too: the etherwarp then lands on top of it.</li>
 *   <li><b>Teleport</b> is not planned at all. While walking a second point runs on the path
 *       about one teleport length ahead; when the path up to it is straight (no turn over 30
 *       degrees left / right, height does not matter) and the teleport from here really lands
 *       there with room for the body all the way, we teleport - while walking, again and again
 *       as long as it stays straight. A jump on that stretch is simply flown over.</li>
 * </ul>
 */
final class WarpLogic {
    /** A little under Hypixel's 57 so the target is surely in range. */
    static final double EW_RANGE = 54.0;
    private static final double EYE = 1.62;
    /** A warp followed by a shorter walk and another warp is slower than walking that part. */
    private static final double MIN_WALK_BETWEEN_WARPS = 12.0;
    private static final Direction[] FACE_ORDER = {Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.EAST, Direction.WEST, Direction.DOWN};
    private static final double[][] FACE_OFFSETS = {{0, 0}, {0.3, 0.3}, {-0.3, 0.3}, {0.3, -0.3}, {-0.3, -0.3}};

    private WarpLogic() {
    }

    // ------------------------------------------------------------------ etherwarp

    /** Solid, with two free blocks above: an etherwarp onto it works. */
    static boolean landable(Level level, BlockPos target) {
        BlockPos a = target.above(), b = a.above();
        return !level.getBlockState(target).getCollisionShape(level, target).isEmpty()
                && level.getBlockState(a).getCollisionShape(level, a).isEmpty()
                && level.getBlockState(b).getCollisionShape(level, b).isEmpty();
    }

    /**
     * A point on {@code target} that the etherwarp ray from {@code eye} hits first - top face
     * first, then the sides, then the bottom (from below the etherwarp still lands on top).
     * Null when nothing of it can be hit.
     */
    static Vec3 etherAim(Level level, Vec3 eye, BlockPos target) {
        if (!landable(level, target)) return null;
        // First the real top of the block (half slab, stair step, fence post): aiming at the
        // full-cube top points into the air there and the server refuses the etherwarp.
        Vec3 preciseTop = WarpPlanner.etherwarpAim(level, eye, target, true);
        if (preciseTop != null) return preciseTop;
        Vec3 c = Vec3.atCenterOf(target);
        if (c.distanceTo(eye) > EW_RANGE) return null;
        // the real shape of the block: on a half slab / stair the top is lower and only part of the
        // block - aiming at the air part of it does not etherwarp
        net.minecraft.world.phys.shapes.VoxelShape shape = level.getBlockState(target).getShape(level, target);
        if (shape.isEmpty()) shape = level.getBlockState(target).getCollisionShape(level, target);
        if (shape.isEmpty()) return null;
        List<net.minecraft.world.phys.AABB> boxes = shape.toAabbs();
        for (Direction d : FACE_ORDER) {
            Vec3 n = new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
            for (net.minecraft.world.phys.AABB local : boxes) {
                net.minecraft.world.phys.AABB box = local.move(target.getX(), target.getY(), target.getZ());
                Vec3 bc = box.getCenter();
                Vec3 faceCenter = switch (d) {
                    case UP -> new Vec3(bc.x, box.maxY, bc.z);
                    case DOWN -> new Vec3(bc.x, box.minY, bc.z);
                    case NORTH -> new Vec3(bc.x, bc.y, box.minZ);
                    case SOUTH -> new Vec3(bc.x, bc.y, box.maxZ);
                    case WEST -> new Vec3(box.minX, bc.y, bc.z);
                    case EAST -> new Vec3(box.maxX, bc.y, bc.z);
                };
                if (n.dot(eye.subtract(faceCenter)) <= 0) continue; // that face looks away from us
                double sx = box.getXsize() * 0.3, sy = box.getYsize() * 0.3, sz = box.getZsize() * 0.3;
                for (double[] o : FACE_OFFSETS) {
                    Vec3 p = switch (d.getAxis()) {
                        case X -> faceCenter.add(0, o[0] / 0.3 * sy, o[1] / 0.3 * sz);
                        case Y -> faceCenter.add(o[0] / 0.3 * sx, 0, o[1] / 0.3 * sz);
                        case Z -> faceCenter.add(o[0] / 0.3 * sx, o[1] / 0.3 * sy, 0);
                    };
                    p = p.subtract(n.scale(0.02)); // just inside the shape
                    Optional<EtherwarpHelper.RayHit> hit = EtherwarpHelper.raycast(level, eye, p.subtract(eye), EW_RANGE + 2);
                    if (hit.isEmpty() || !hit.get().pos().equals(target)) continue;
                    // and the real shapes on the way: the first thing hit must be this block
                    Vec3 through = p.add(p.subtract(eye).normalize().scale(0.05));
                    net.minecraft.world.phys.BlockHitResult clip = level.clip(new net.minecraft.world.level.ClipContext(
                            eye, through, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                            net.minecraft.world.level.ClipContext.Fluid.NONE,
                            net.minecraft.world.phys.shapes.CollisionContext.empty()));
                    if (clip.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && clip.getBlockPos().equals(target)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /** Room for the body (0.66 wide, 1.8 high) along the whole teleport line, start and floor left out. */
    static boolean bodyClear(Level level, Vec3 eye, Vec3 dir, double reach) {
        Vec3 unit = dir.normalize();
        for (double t = 1.0; t <= reach - 0.5; t += 0.25) {
            Vec3 feet = eye.add(unit.scale(t)).add(0, -EYE, 0);
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(feet.x - 0.33, feet.y + 0.1, feet.z - 0.33,
                    feet.x + 0.33, feet.y + 1.75, feet.z + 0.33);
            if (level.getBlockCollisions(null, box).iterator().hasNext()) return false;
        }
        return true;
    }

    /** Why the last {@link #straightTeleport} said no (for the log). */
    static String lastNo = "";

    /** The block whose top is the floor point {@code floor}. */
    static BlockPos blockUnder(Vec3 floor) {
        return BlockPos.containing(floor.x, floor.y - 0.01, floor.z);
    }

    private record Candidate(int section, int index, double route, int gaps) {
    }

    /**
     * Replaces runs of jumps / climbs by one etherwarp where that works: from the last spot of a
     * line before a jump (or up to 3 blocks before it) onto the furthest line point (up to ~55
     * blocks of way) that can be hit and skips at least two jumps / climbs, or one that goes up
     * 1.5+ blocks, or a big detour.
     */
    static List<Section> shortcuts(Level level, List<Section> in) {
        List<Section> out = new ArrayList<>(in);
        int k = 0;
        while (k < out.size()) {
            Section s = out.get(k);
            Section next = k + 1 < out.size() ? out.get(k + 1) : null;
            if (!s.walk() || next == null || next.kind() != Kind.JUMP && next.kind() != Kind.CLIMB) {
                k++;
                continue;
            }
            List<Vec3> line = s.line();
            double lineLen = PathLine.length(line);
            Candidate found = null;
            int from = -1;
            BlockPos target = null;
            for (double back = 0; back <= 3.0 && found == null; back += 1.0) {
                int ai = indexAt(line, lineLen - back);
                Vec3 aFloor = line.get(ai).subtract(0, PathLine.MID, 0);
                Vec3 eye = aFloor.add(0, EYE, 0);
                List<Candidate> cands = new ArrayList<>();
                double route = lineLen - along(line, ai);
                int gaps = 0;
                for (int m = k + 1; m < out.size() && route < 55; m++) {
                    Section t = out.get(m);
                    if (t.warp()) break;
                    if (!t.walk()) {
                        route += PathLine.flat(t.from(), t.to());
                        if (t.kind() == Kind.JUMP || t.kind() == Kind.CLIMB) gaps++;
                        continue;
                    }
                    List<Vec3> l = t.line();
                    for (int idx = 0; idx < l.size(); idx += 2) {
                        cands.add(new Candidate(m, idx, route + along(l, idx), gaps));
                    }
                    route += PathLine.length(l);
                }
                for (int c = cands.size() - 1; c >= 0; c--) {
                    Candidate cand = cands.get(c);
                    if (cand.gaps() < 1 || cand.route() < 5) continue;
                    Vec3 bFloor = out.get(cand.section()).line().get(cand.index()).subtract(0, PathLine.MID, 0);
                    double up = bFloor.y - aFloor.y;
                    double straight = aFloor.distanceTo(bFloor);
                    // not onto a hill only to drop down again right after it
                    boolean peak = false;
                    for (int c2 = c + 1; c2 < cands.size() && cands.get(c2).route() < cand.route() + 6; c2++) {
                        Vec3 f = out.get(cands.get(c2).section()).line().get(cands.get(c2).index());
                        if (f.y - PathLine.MID < bFloor.y - 1.5) peak = true;
                    }
                    if (peak) continue;
                    // the walk it saves (each jump counts like a few blocks) must clearly beat the
                    // etherwarp (stop, sneak, aim, warp ~ 6 blocks of walking)
                    double saved = cand.route() + 2.5 * cand.gaps() - (6.0 + straight * 0.1);
                    boolean worth = saved >= 6.0 && (Math.abs(up) >= 1.5 || cand.gaps() >= 2
                            || straight >= 5 && cand.route() > 2.2 * straight);
                    if (!worth || straight < 3.0) continue; // right next to us (one block up) etherwarp does not work
                    // No "etherwarp, 8 blocks walking, etherwarp again": either chain directly or
                    // walk the whole part. A short walk after the warp before the next warp is slower.
                    if (shortWalkBeforeNextWarp(out, cand.section(), cand.index())) continue;
                    BlockPos tb = blockUnder(bFloor);
                    if (etherAim(level, eye, tb) == null) continue;
                    found = cand;
                    from = ai;
                    target = tb;
                    break;
                }
            }
            if (found == null) {
                k++;
                continue;
            }
            Section t = out.get(found.section());
            Vec3 aFloor = line.get(from).subtract(0, PathLine.MID, 0);
            Vec3 bFloor = t.line().get(found.index()).subtract(0, PathLine.MID, 0);
            Section head = cut(s, 0, from);
            Section ew = new Section(Kind.ETHERWARP, List.of(), aFloor,
                    new Vec3(target.getX() + 0.5, bFloor.y, target.getZ() + 0.5), null);
            Section tail = cut(t, found.index(), t.line().size() - 1);
            List<Section> replaced = new ArrayList<>(out.subList(0, k));
            replaced.add(head);
            replaced.add(ew);
            replaced.add(tail);
            replaced.addAll(out.subList(found.section() + 1, out.size()));
            out = replaced;
            k += 2; // go on from the line after the etherwarp
        }
        return out;
    }

    /**
     * True when the candidate lands, then a short walk (< 12 b) follows, and after that comes
     * another warp. Those routes are slower than just walking the part.
     */
    private static boolean shortWalkBeforeNextWarp(List<Section> sections, int section, int index) {
        Section s = sections.get(section);
        double walk = PathLine.length(s.line()) - along(s.line(), index);
        if (walk >= MIN_WALK_BETWEEN_WARPS) {
            return false;
        }
        for (int m = section + 1; m < sections.size(); m++) {
            Section t = sections.get(m);
            if (t.warp()) {
                return walk > 0.5 && walk < MIN_WALK_BETWEEN_WARPS;
            }
            if (!t.walk()) {
                return false; // jump / climb / drop between them, not a short walk
            }
            walk += PathLine.length(t.line());
            if (walk >= MIN_WALK_BETWEEN_WARPS) {
                return false;
            }
        }
        return false; // no later warp: one warp and then walk is fine
    }

    private static Section cut(Section walk, int a, int b) {
        List<Vec3> line = new ArrayList<>(walk.line().subList(a, b + 1));
        List<Boolean> tight = walk.tight().size() == walk.line().size()
                ? new ArrayList<>(walk.tight().subList(a, b + 1)) : List.of();
        return new Section(Kind.WALK, line, line.get(0).subtract(0, PathLine.MID, 0),
                line.get(line.size() - 1).subtract(0, PathLine.MID, 0), null, tight);
    }

    private static int indexAt(List<Vec3> line, double dist) {
        double sum = 0;
        for (int i = 0; i + 1 < line.size(); i++) {
            double d = PathLine.flat(line.get(i), line.get(i + 1));
            if (sum + d >= dist) return dist - sum < d / 2 ? i : i + 1;
            sum += d;
        }
        return line.size() - 1;
    }

    private static double along(List<Vec3> line, int upTo) {
        double sum = 0;
        for (int i = 0; i < upTo && i + 1 < line.size(); i++) sum += PathLine.flat(line.get(i), line.get(i + 1));
        return sum;
    }

    // ------------------------------------------------------------------ teleport while walking

    /**
     * A teleport straight along {@code route} (points at mid player height, starting where we
     * are on the path): aimed a little above the route point one teleport length away, only when
     * the route up to it turns less than 30 degrees and stays within 1.5 blocks of the straight
     * line, the body fits all the way and the landing (after the fall) is on the route.
     */
    static WarpPlanner.Hop straightTeleport(Level level, Vec3 feet, Vec3 eye, List<Vec3> route, int reach) {
        lastNo = "";
        if (reach <= 0 || route.size() < 2) return null;
        double total = PathLine.length(route);
        if (total < reach + 1.0) {
            lastNo = "way left too short";
            return null;
        }
        Vec3 aim = null;
        double aimAlong = 0;
        for (double d = reach - 3.0; d <= reach + 3.0 && d <= total; d += 0.5) {
            Vec3 q = pointAt(route, d).add(0, 0.7, 0); // a little above the line: head height
            if (aim == null || Math.abs(q.distanceTo(eye) - reach) < Math.abs(aim.distanceTo(eye) - reach)) {
                aim = q;
                aimAlong = d;
            }
        }
        if (aim == null) return null;
        double hx = aim.x - feet.x, hz = aim.z - feet.z;
        double hl = Math.hypot(hx, hz);
        if (hl < 4.0) return null;
        double straightYaw = Math.atan2(hz, hx);
        for (double d = 2.0; d < aimAlong; d += 0.5) {
            Vec3 q = pointAt(route, d);
            double dev = Math.abs(wrap(Math.atan2(q.z - feet.z, q.x - feet.x) - straightYaw));
            if (dev > Math.toRadians(30)) {
                lastNo = String.format(java.util.Locale.ROOT, "turns %.0f deg", Math.toDegrees(dev));
                return null;
            }
            // sideways distance of the route from the straight line
            double side = Math.abs((q.x - feet.x) * hz - (q.z - feet.z) * hx) / hl;
            if (side > 2.0) {
                lastNo = String.format(java.util.Locale.ROOT, "way %.1f b beside the straight line", side);
                return null;
            }
        }
        Vec3 dir = aim.subtract(eye).normalize();
        Vec3 land = WarpPlanner.simulateTeleport(level, eye, dir, reach);
        if (land == null) {
            lastNo = "blocks in the way";
            return null;
        }
        double floor = AirMath.floorUnder(level, land.x, land.z, land.y + 0.01, land.y - 4.0);
        if (Double.isInfinite(floor)) {
            lastNo = "would land over a hole";
            return null;
        }
        Vec3 routeFloor = pointAt(route, aimAlong).subtract(0, PathLine.MID, 0);
        if (PathLine.flat(land, routeFloor) > 2.0 || Math.abs(floor - routeFloor.y) > 1.0) {
            lastNo = String.format(java.util.Locale.ROOT, "lands %.1f b off the way", PathLine.flat(land, routeFloor));
            return null;
        }
        if (!bodyClear(level, eye, dir, reach)) {
            lastNo = "body does not fit along the line";
            return null;
        }
        return new WarpPlanner.Hop(WarpPlanner.Kind.TELEPORT, eye.add(dir.scale(4)), land, false, dir);
    }

    private static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    static Vec3 pointAt(List<Vec3> line, double dist) {
        double left = dist;
        for (int i = 0; i + 1 < line.size(); i++) {
            double d = PathLine.flat(line.get(i), line.get(i + 1));
            if (left <= d) return d < 1.0E-6 ? line.get(i + 1) : line.get(i).lerp(line.get(i + 1), left / d);
            left -= d;
        }
        return line.get(line.size() - 1);
    }
}
