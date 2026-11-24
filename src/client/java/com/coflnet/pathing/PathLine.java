package com.coflnet.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * The planned way as clean pieces (Air Client path engine).
 * <ol>
 *   <li>Baritone's block-by-block path is looked at in <b>half blocks</b>: every block has a first
 *       and a second half, each with its real floor height (slabs, stairs, carpets).</li>
 *   <li>Between two halves the floor may rise up to 0.6 (a stair step, a slab): that is walked.
 *       More than that up is a <b>jump</b>; 0.45 or more down is a <b>drop</b> (half slab drops too,
 *       so they are run down quickly). Teleports and etherwarps from the search are pieces of
 *       their own as well.</li>
 *   <li>Jumps, drops and warps have <b>no line</b>: only a take-off and a landing point. The line
 *       after them starts again a little further on (the player still moves fast after landing).</li>
 *   <li>Walking lines are pulled tight (no block zig-zag), corners become arcs where the player
 *       fits, and the line sits at the middle of the player's height with a smooth slope (no
 *       up-down zig-zag over stairs).</li>
 * </ol>
 */
final class PathLine {
    enum Kind { WALK, JUMP, DROP, CLIMB, TELEPORT, ETHERWARP }

    /**
     * One piece. WALK: {@code line} at mid player height, {@code from}/{@code to} the first/last
     * floor point. Other kinds: no line; {@code from} = take-off (floor), {@code to} = landing
     * (floor), {@code dir} = planned look direction of a teleport/etherwarp.
     */
    record Section(Kind kind, List<Vec3> line, Vec3 from, Vec3 to, Vec3 dir, List<Boolean> tight) {
        Section(Kind kind, List<Vec3> line, Vec3 from, Vec3 to, Vec3 dir) {
            this(kind, line, from, to, dir, List.of());
        }

        /** True when the line near point {@code i} has a wall, a hole or vines right beside it. */
        boolean tightNear(int i) {
            for (int k = Math.max(0, i - 1); k <= Math.min(tight.size() - 1, i + 3); k++) {
                if (tight.get(k)) return true;
            }
            return false;
        }

        boolean walk() {
            return kind == Kind.WALK;
        }

        boolean warp() {
            return kind == Kind.TELEPORT || kind == Kind.ETHERWARP;
        }
    }

    /** Height of the line above the floor: the middle of the player. */
    static final double MID = 0.9;
    /** Higher than this between two half blocks = a jump. */
    static final double STEP = 0.6;
    /**
     * Lower by more than this = a drop. A stair or half slab down (0.5) is walked like a step up:
     * a stair set into the floor is no drop.
     */
    static final double DROP = 0.6;
    private static final double HALF_WIDTH = 0.28;

    private record Sample(Vec3 pos, Kind how, Vec3 dir) {
    }

    private PathLine() {
    }

    /** All pieces of a Baritone path ({@code kinds}: 0 walk, 1 jump, 2 teleport, 3 etherwarp). */
    static List<Section> build(Level level, List<BlockPos> blocks, List<Integer> kinds, List<Vec3> dirs, Vec3 feet) {
        List<Sample> samples = samples(level, blocks, kinds, dirs, feet);
        List<Section> raw = mergeChains(level, mergeDrops(sections(samples)));
        raw = farLandings(level, raw);
        List<Section> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Section s = raw.get(i);
            if (!s.walk()) {
                out.add(s);
                continue;
            }
            List<Vec3> floor = s.line();
            if (i > 0) {
                floor = trimStart(floor, rejoinMargin(raw.get(i - 1)));
            }
            out.add(walk(level, floor));
        }
        return out;
    }

    /** How far after a landing the line starts again (the player is still fast there). */
    static double rejoinMargin(Section gap) {
        return switch (gap.kind()) {
            case DROP -> 1.3 + 0.5 * Math.sqrt(Math.max(0.0, gap.from().y - gap.to().y));
            case JUMP -> 0.8;
            case CLIMB -> 0.5;
            default -> 0.6;
        };
    }

    // ------------------------------------------------------------------ half-block samples

    private static List<Sample> samples(Level level, List<BlockPos> blocks, List<Integer> kinds, List<Vec3> dirs,
                                        Vec3 feet) {
        List<Sample> out = new ArrayList<>();
        BlockPos b0 = blocks.get(0);
        double y0 = floorIn(level, feet.x, feet.z, b0.getY());
        if (Double.isNaN(y0)) y0 = floorIn(level, b0.getX() + 0.5, b0.getZ() + 0.5, b0.getY());
        if (Double.isNaN(y0)) y0 = feet.y;
        out.add(new Sample(new Vec3(feet.x, y0, feet.z), Kind.WALK, null));
        for (int i = 1; i < blocks.size(); i++) {
            BlockPos q = blocks.get(i - 1), p = blocks.get(i);
            int k = kinds.get(i);
            double px = p.getX() + 0.5, pz = p.getZ() + 0.5;
            double pFloor = floorIn(level, px, pz, p.getY());
            if (Double.isNaN(pFloor)) pFloor = p.getY();
            if (k == 2 || k == 3) {
                out.add(new Sample(new Vec3(px, pFloor, pz), k == 2 ? Kind.TELEPORT : Kind.ETHERWARP, dirs.get(i)));
                continue;
            }
            double qx = q.getX() + 0.5, qz = q.getZ() + 0.5;
            boolean climbing = p.getY() > q.getY() && (climbable(level, q) || climbable(level, p));
            if (climbing) {
                // up a vine / ladder (Baritone only goes straight up like this when it climbs):
                // no jump - pushed into the climbable, the player goes up by itself
                double top = Double.isNaN(floorIn(level, px, pz, p.getY())) ? p.getY() : pFloor;
                out.add(new Sample(new Vec3(px, top, pz), Kind.CLIMB, null));
                continue;
            }
            if (Math.hypot(px - qx, pz - qz) > 1.6) {
                // over a gap (parkour): one jump from here to there
                out.add(new Sample(new Vec3(px, pFloor, pz), Kind.JUMP, null));
                continue;
            }
            // second half of this block, first half of the next one, the next one's middle
            double[] ts = {0.25, 0.75, 1.0};
            for (double t : ts) {
                BlockPos in = t < 0.5 ? q : p;
                double x = qx + (px - qx) * t, z = qz + (pz - qz) * t;
                double fy = t == 1.0 ? pFloor : floorIn(level, x, z, in.getY());
                Sample last = out.get(out.size() - 1);
                if (Double.isNaN(fy)) fy = in == p ? pFloor : last.pos().y;
                double diff = fy - last.pos().y;
                Kind how;
                if (diff > 1.3) {
                    double qFloor = floorIn(level, qx, qz, q.getY());
                    double centerRise = pFloor - (Double.isNaN(qFloor) ? q.getY() : qFloor);
                    // climbing only where there really are vines / a ladder - everything else is jumped
                    how = climbableNear(level, q) || climbableNear(level, p)
                            ? Kind.CLIMB : centerRise > STEP ? Kind.JUMP : Kind.WALK;
                } else {
                    how = diff > STEP ? Kind.JUMP : diff < -DROP ? Kind.DROP : Kind.WALK;
                }
                out.add(new Sample(new Vec3(x, fy, z), how, null));
            }
        }
        return out;
    }

    private static List<Section> sections(List<Sample> samples) {
        List<Section> out = new ArrayList<>();
        List<Vec3> cur = new ArrayList<>();
        cur.add(samples.get(0).pos());
        for (int i = 1; i < samples.size(); i++) {
            Sample s = samples.get(i);
            if (s.how() == Kind.WALK) {
                cur.add(s.pos());
                continue;
            }
            Vec3 from = cur.get(cur.size() - 1);
            out.add(new Section(Kind.WALK, cur, cur.get(0), from, null));
            out.add(new Section(s.how(), List.of(), from, s.pos(), s.dir()));
            cur = new ArrayList<>();
            cur.add(s.pos());
        }
        out.add(new Section(Kind.WALK, cur, cur.get(0), cur.get(cur.size() - 1), null));
        return out;
    }

    /** Vines / a ladder in this column or right next to it (feet or head height). */
    private static boolean climbableNear(Level level, BlockPos feet) {
        for (BlockPos p : new BlockPos[]{feet, feet.north(), feet.south(), feet.east(), feet.west()}) {
            if (climbable(level, p)) return true;
        }
        return false;
    }

    private static boolean climbable(Level level, BlockPos feet) {
        return level.getBlockState(feet).is(net.minecraft.tags.BlockTags.CLIMBABLE)
                || level.getBlockState(feet.above()).is(net.minecraft.tags.BlockTags.CLIMBABLE);
    }

    /** Drops close behind each other (stairs down, several slabs) become one drop; climbs too. */
    private static List<Section> mergeDrops(List<Section> in) {
        List<Section> out = new ArrayList<>(in);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i + 2 < out.size(); i++) {
                Section a = out.get(i), mid = out.get(i + 1), b = out.get(i + 2);
                boolean drops = a.kind() == Kind.DROP && b.kind() == Kind.DROP && length(mid.line()) < rejoinMargin(a);
                boolean climbs = a.kind() == Kind.CLIMB && b.kind() == Kind.CLIMB && length(mid.line()) < 0.6;
                if (mid.walk() && (drops || climbs)) {
                    out.set(i, new Section(a.kind(), List.of(), a.from(), b.to(), null));
                    out.remove(i + 2);
                    out.remove(i + 1);
                    changed = true;
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Several jumps / drops close behind each other become one: drop + short walk + drop is one
     * drop; any pair with a jump is one jump when a sprint jump (keys off in the air) from the
     * first take-off reaches the last landing without hitting anything.
     */
    private static List<Section> mergeChains(Level level, List<Section> in) {
        List<Section> out = new ArrayList<>(in);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i + 2 < out.size(); i++) {
                Section a = out.get(i), mid = out.get(i + 1), b = out.get(i + 2);
                boolean gaps = (a.kind() == Kind.JUMP || a.kind() == Kind.DROP)
                        && (b.kind() == Kind.JUMP || b.kind() == Kind.DROP) && mid.walk();
                if (!gaps || length(mid.line()) > 3.0) continue;
                Section merged = null;
                if (a.kind() == Kind.DROP && b.kind() == Kind.DROP) {
                    continue;
                } else if (b.to().y - a.from().y <= 1.1 && reaches(level, a.from(), b.to())) {
                    merged = new Section(Kind.JUMP, List.of(), a.from(), b.to(), null);
                }
                if (merged != null) {
                    out.set(i, merged);
                    out.remove(i + 2);
                    out.remove(i + 1);
                    changed = true;
                    break;
                }
            }
        }
        return out;
    }

    /** A plain sprint jump (vanilla speed, keys off in the air) from {@code from} gets to {@code to}. */
    private static boolean reaches(Level level, Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dz = to.z - from.z;
        double len = Math.hypot(dx, dz);
        if (len < 0.5 || len > 4.5) return false;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        Vec3 vel = new Vec3(dx / len * 0.153, 0, dz / len * 0.153); // steady sprint, after friction
        AirMath.Landing l = AirMath.jump(level, from, vel, yaw, yaw, true, 0.127, 0, false);
        // one clean arc only: no block touched on the way (no sliding along a trunk, no head bump)
        if (l == null || AirMath.touched() || Math.abs(l.pos().y - to.y) > 0.35) return false;
        double along = ((l.pos().x - from.x) * dx + (l.pos().z - from.z) * dz) / len;
        return along >= len - 0.3;
    }

    /**
     * The landing point of a jump as far ahead on the same floor as a jump at walking speed
     * (keys off in the air, nothing touched) surely gets - progress during the jump instead of
     * landing on the first half block and walking on from there. Half a block of safety.
     */
    private static List<Section> farLandings(Level level, List<Section> in) {
        List<Section> out = new ArrayList<>(in);
        for (int i = 0; i + 1 < out.size(); i++) {
            Section j = out.get(i), w = out.get(i + 1);
            if (j.kind() != Kind.JUMP || !w.walk() || w.line().size() < 2) continue;
            List<Vec3> pts = w.line();
            int best = -1;
            double along = 0;
            for (int k = 1; k < pts.size() && along < 3.0; k++) {
                along += flat(pts.get(k - 1), pts.get(k));
                Vec3 f = pts.get(k);
                if (Math.abs(f.y - j.to().y) > 0.15) break; // only on the same floor
                if (walkJumpReaches(level, j.from(), f)) best = k;
            }
            if (best < 0) continue;
            // half a block back from the furthest that still works
            double back = 0;
            int pick = best;
            while (pick > 0 && back < 0.5) {
                back += flat(pts.get(pick - 1), pts.get(pick));
                pick--;
            }
            if (flat(pts.get(pick), j.to()) < 0.3) continue;
            out.set(i, new Section(Kind.JUMP, List.of(), j.from(), pts.get(pick), null));
            int rest = pick; // the usual rejoin margin after a jump is added later
            List<Vec3> line = new ArrayList<>(pts.subList(rest, pts.size()));
            out.set(i + 1, new Section(Kind.WALK, line, line.get(0), w.to(), null));
        }
        return out;
    }

    /** A jump from {@code from} at plain walking speed reaches {@code to} (same height, clean arc). */
    private static boolean walkJumpReaches(Level level, Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dz = to.z - from.z;
        double len = Math.hypot(dx, dz);
        if (len < 0.3 || len > 4.0) return false;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        Vec3 vel = new Vec3(dx / len * 0.12, 0, dz / len * 0.12); // walking, after friction
        AirMath.Landing l = AirMath.jump(level, from, vel, yaw, yaw, false, 0.1, 0, false);
        if (l == null || AirMath.touched() && to.y - from.y < 0.4 || Math.abs(l.pos().y - to.y) > 0.35) return false;
        double reach = ((l.pos().x - from.x) * dx + (l.pos().z - from.z) * dz) / len;
        return reach >= len - 0.1;
    }

    private static List<Vec3> trimStart(List<Vec3> pts, double margin) {
        if (pts.size() <= 1) return pts;
        double along = 0;
        int k = 0;
        while (k + 1 < pts.size() && along < margin) {
            along += flat(pts.get(k), pts.get(k + 1));
            k++;
        }
        return new ArrayList<>(pts.subList(k, pts.size()));
    }

    // ------------------------------------------------------------------ the walking line

    /** Floor points -> tight, round line at mid player height. */
    private static Section walk(Level level, List<Vec3> floor) {
        List<Vec3> pts = floor;
        if (pts.size() > 2) {
            pts = simplify(level, pts);
            pts = round(level, pts);
        }
        pts = resample(level, pts, 0.5);
        List<Boolean> tight = new ArrayList<>(pts.size());
        for (int i = 0; i < pts.size(); i++) {
            Vec3 a = pts.get(Math.max(0, i - 1)), b = pts.get(Math.min(pts.size() - 1, i + 1));
            tight.add(tightAt(level, pts.get(i), b.x - a.x, b.z - a.z));
        }
        pts = smoothHeight(pts);
        List<Vec3> line = new ArrayList<>(pts.size());
        for (Vec3 p : pts) line.add(p.add(0, MID, 0));
        return new Section(Kind.WALK, line, floor.get(0), floor.get(floor.size() - 1), null, tight);
    }

    /** From each point straight on to the furthest point that can be walked in a straight line. */
    private static List<Vec3> simplify(Level level, List<Vec3> pts) {
        List<Vec3> out = new ArrayList<>();
        out.add(pts.get(0));
        int i = 0, n = pts.size();
        while (i < n - 1) {
            int j = i + 1;
            for (int k = Math.min(n - 1, i + 30); k > i + 1; k--) {
                if (flat(pts.get(i), pts.get(k)) <= 14.0 && straightOk(level, pts.get(i), pts.get(k))) {
                    j = k;
                    break;
                }
            }
            out.add(pts.get(j));
            i = j;
        }
        return out;
    }

    /** Corners become arcs (as wide as the room allows; the corner stays where nothing fits). */
    private static List<Vec3> round(Level level, List<Vec3> pts) {
        if (pts.size() < 3) return pts;
        List<Vec3> out = new ArrayList<>();
        out.add(pts.get(0));
        for (int i = 1; i + 1 < pts.size(); i++) {
            Vec3 a = out.get(out.size() - 1), c = pts.get(i), b = pts.get(i + 1);
            double lin = flat(a, c), lout = flat(c, b);
            if (lin < 0.6 || lout < 0.6) {
                out.add(c);
                continue;
            }
            double ix = (c.x - a.x) / lin, iz = (c.z - a.z) / lin;
            double ox = (b.x - c.x) / lout, oz = (b.z - c.z) / lout;
            double turn = Math.acos(Math.clamp(ix * ox + iz * oz, -1.0, 1.0));
            if (turn < Math.toRadians(8)) {
                out.add(c);
                continue;
            }
            List<Vec3> arc = null;
            double d = Math.min(2.2, Math.min(lin * 0.48, lout * 0.48));
            for (int tries = 0; tries < 3 && arc == null && d >= 0.4; tries++, d *= 0.6) {
                Vec3 t1 = new Vec3(c.x - ix * d, c.y + (a.y - c.y) * (d / lin), c.z - iz * d);
                Vec3 t2 = new Vec3(c.x + ox * d, c.y + (b.y - c.y) * (d / lout), c.z + oz * d);
                int n = Math.max(3, (int) Math.ceil(turn / Math.toRadians(15)));
                List<Vec3> curve = new ArrayList<>();
                for (int k = 0; k <= n; k++) {
                    double t = k / (double) n, u = 1 - t;
                    curve.add(new Vec3(u * u * t1.x + 2 * u * t * c.x + t * t * t2.x,
                            u * u * t1.y + 2 * u * t * c.y + t * t * t2.y,
                            u * u * t1.z + 2 * u * t * c.z + t * t * t2.z));
                }
                boolean ok = true;
                for (int k = 0; k + 1 < curve.size() && ok; k++) {
                    ok = straightOk(level, curve.get(k), curve.get(k + 1));
                }
                if (ok) arc = curve;
            }
            if (arc == null) {
                out.add(c);
            } else {
                out.addAll(arc);
            }
        }
        out.add(pts.get(pts.size() - 1));
        return out;
    }

    /** Points every {@code step} blocks along the line, each on the real floor below it. */
    private static List<Vec3> resample(Level level, List<Vec3> pts, double step) {
        List<Vec3> out = new ArrayList<>();
        out.add(pts.get(0));
        double carry = 0;
        for (int i = 0; i + 1 < pts.size(); i++) {
            Vec3 a = pts.get(i), b = pts.get(i + 1);
            double len = flat(a, b);
            double t = step - carry;
            while (t < len) {
                Vec3 p = a.lerp(b, t / len);
                double g = groundNear(level, p.x, p.z, p.y);
                out.add(Double.isInfinite(g) ? p : new Vec3(p.x, g, p.z));
                t += step;
            }
            carry = len - (t - step);
        }
        Vec3 end = pts.get(pts.size() - 1);
        if (flat(out.get(out.size() - 1), end) > 0.05) out.add(end);
        else out.set(out.size() - 1, end);
        return out;
    }

    /** Stairs and slabs as one even slope: average over about two blocks. */
    private static List<Vec3> smoothHeight(List<Vec3> pts) {
        int n = pts.size();
        if (n < 3) return pts;
        List<Vec3> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double sum = 0;
            int count = 0;
            for (int k = Math.max(0, i - 2); k <= Math.min(n - 1, i + 2); k++) {
                sum += pts.get(k).y;
                count++;
            }
            Vec3 p = pts.get(i);
            out.add(new Vec3(p.x, sum / count, p.z));
        }
        return out;
    }

    // ------------------------------------------------------------------ world checks

    /** A wall, a hole, a big step or vines within ~0.85 blocks to the side of floor point p. */
    private static boolean tightAt(Level level, Vec3 p, double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 1.0E-6) return true;
        double nx = -dz / len, nz = dx / len;
        for (double off : new double[]{0.85, -0.85}) {
            double x = p.x + nx * off, z = p.z + nz * off;
            double g = groundNear(level, x, z, p.y);
            if (Double.isInfinite(g) || Math.abs(g - p.y) > 0.6) return true;
            if (!headClear(level, x, z, g) || climbableAt(level, x, g, z)) return true;
        }
        return climbableAt(level, p.x, p.y, p.z);
    }

    /** Vines, ladders, scaffolding... in the player's body standing on floor y at (x, z): it would climb. */
    static boolean climbableAt(Level level, double x, double floorY, double z) {
        for (double h : new double[]{0.1, 1.0}) {
            BlockPos pos = BlockPos.containing(x, floorY + h, z);
            if (level.getBlockState(pos).is(net.minecraft.tags.BlockTags.CLIMBABLE)) return true;
        }
        return false;
    }

    /**
     * True when the player can walk straight from floor point a to b: floor under the whole
     * width all the way (no hole, no step over 0.55 against the straight slope) and head room.
     */
    static boolean straightOk(Level level, Vec3 a, Vec3 b) {
        double d = flat(a, b);
        if (d < 0.05) return true;
        double ux = (b.x - a.x) / d, uz = (b.z - a.z) / d;
        double px = -uz, pz = ux;
        int n = (int) Math.ceil(d / 0.3);
        for (int s = 1; s <= n; s++) {
            double f = s / (double) n;
            double x = a.x + (b.x - a.x) * f, z = a.z + (b.z - a.z) * f;
            double ey = a.y + (b.y - a.y) * f;
            for (double off : new double[]{0.0, HALF_WIDTH, -HALF_WIDTH}) {
                double sx = x + px * off, sz = z + pz * off;
                double g = groundNear(level, sx, sz, ey);
                if (Double.isInfinite(g) || Math.abs(g - ey) > 0.55) return false;
                if (!headClear(level, sx, sz, g) || climbableAt(level, sx, g, sz)) return false;
            }
        }
        return true;
    }

    /**
     * The floor at (x, z) close to height y: the top of the first solid thing at most 0.6 above
     * y, searching down to ~1.5 below. +infinity = a wall there, -infinity = a hole.
     */
    static double groundNear(Level level, double x, double z, double y) {
        int top = (int) Math.floor(y + 0.55);
        int bottom = (int) Math.floor(y - 1.5);
        for (int by = top; by >= bottom; by--) {
            double t = columnTop(level, new BlockPos((int) Math.floor(x), by, (int) Math.floor(z)), x, z);
            if (Double.isNaN(t)) continue;
            if (t > y + 0.6) return Double.POSITIVE_INFINITY;
            return t;
        }
        return Double.NEGATIVE_INFINITY;
    }

    /** Floor at (x, z) for a player whose feet are in block row {@code feetY} (Baritone position). */
    static double floorIn(Level level, double x, double z, int feetY) {
        for (int by = feetY; by >= feetY - 2; by--) {
            double t = columnTop(level, new BlockPos((int) Math.floor(x), by, (int) Math.floor(z)), x, z);
            if (Double.isNaN(t)) continue;
            if (t <= feetY + 0.6) return t;
        }
        return Double.NaN;
    }

    /** Top of the collision shape of {@code pos} exactly above the point (x, z); NaN = nothing there. */
    static double columnTop(Level level, BlockPos pos, double x, double z) {
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        if (shape.isEmpty()) return Double.NaN;
        double lx = x - pos.getX(), lz = z - pos.getZ();
        double best = Double.NaN;
        for (AABB box : shape.toAabbs()) {
            if (lx >= box.minX - 0.02 && lx <= box.maxX + 0.02 && lz >= box.minZ - 0.02 && lz <= box.maxZ + 0.02) {
                double t = pos.getY() + box.maxY;
                if (Double.isNaN(best) || t > best) best = t;
            }
        }
        return best;
    }

    /** True when a solid part of a block is at the point. */
    static boolean solidAt(Level level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        if (shape.isEmpty()) return false;
        double lx = x - pos.getX(), ly = y - pos.getY(), lz = z - pos.getZ();
        for (AABB box : shape.toAabbs()) {
            if (lx > box.minX && lx < box.maxX && ly > box.minY && ly < box.maxY && lz > box.minZ && lz < box.maxZ) {
                return true;
            }
        }
        return false;
    }

    /** Room for the player's body (1.8 high) standing on floor {@code floorY} at (x, z). */
    static boolean headClear(Level level, double x, double z, double floorY) {
        for (double h : new double[]{0.1, 0.9, 1.75}) {
            if (solidAt(level, x, floorY + h, z)) return false;
        }
        return true;
    }

    static double length(List<Vec3> line) {
        double sum = 0;
        for (int i = 0; i + 1 < line.size(); i++) sum += flat(line.get(i), line.get(i + 1));
        return sum;
    }

    static double flat(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }
}
