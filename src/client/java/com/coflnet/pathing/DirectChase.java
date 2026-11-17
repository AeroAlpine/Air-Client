package com.coflnet.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Last few blocks to a mob: walk straight at it instead of letting Baritone plan.
 *
 * <p>The path recorder showed the combat bot standing ~4 blocks from a mob while
 * the goal was moved to the mob's new block every 250 ms: seven new paths in two
 * seconds, each one starting in another direction - the "running in circles"
 * before a hit. A player just walks at the mob. That is only safe on open, level
 * ground, so this checks the straight line first: same height, line of sight,
 * nothing at feet or head height and solid ground under every step (no holes,
 * no edges).
 */
public final class DirectChase {
    /** Start walking straight at this distance (blocks) or closer. */
    public static final double ENTER_DISTANCE = 6.0;
    /** Keep walking straight until the mob is this far away (hysteresis). */
    public static final double LEAVE_DISTANCE = 8.0;
    /** Largest height difference between player and mob for a straight walk. */
    private static final double MAX_HEIGHT_DIFF = 1.0;
    private static final double STEP = 0.3;

    private DirectChase() {
    }

    /**
     * @param chasing whether the bot already walks straight (uses the longer leave distance)
     */
    public static boolean possible(Level level, Player player, Entity target, boolean chasing) {
        if (level == null || player == null || target == null || !target.isAlive()) {
            return false;
        }
        if (!player.onGround() || player.isInWater() || player.isInLava() || player.onClimbable()) {
            return false;
        }
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat > (chasing ? LEAVE_DISTANCE : ENTER_DISTANCE)) {
            return false;
        }
        if (Math.abs(target.getY() - player.getY()) > MAX_HEIGHT_DIFF) {
            return false;
        }
        if (!player.hasLineOfSight(target)) {
            return false;
        }
        return lineWalkable(level, player, dx, dz, flat);
    }

    /** Feet and head free and solid ground under the whole straight line. */
    private static boolean lineWalkable(Level level, Player player, double dx, double dz, double flat) {
        if (flat < 1.0E-3) {
            return true;
        }
        double ux = dx / flat;
        double uz = dz / flat;
        int feetY = Mth.floor(player.getY() + 0.01);
        // half a player width to each side, so the body also fits past corners
        double sideX = -uz * 0.3;
        double sideZ = ux * 0.3;
        for (double t = STEP; t <= flat; t += STEP) {
            double cx = player.getX() + ux * t;
            double cz = player.getZ() + uz * t;
            for (int side = -1; side <= 1; side++) {
                double x = cx + sideX * side;
                double z = cz + sideZ * side;
                int bx = Mth.floor(x);
                int bz = Mth.floor(z);
                if (!free(level, new BlockPos(bx, feetY, bz))
                        || !free(level, new BlockPos(bx, feetY + 1, bz))
                        || !solid(level, new BlockPos(bx, feetY - 1, bz))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean free(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean solid(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.getCollisionShape(level, pos).isEmpty();
    }
}
