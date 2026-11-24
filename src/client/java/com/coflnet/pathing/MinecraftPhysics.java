package com.coflnet.pathing;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The movement laws of Minecraft 26.2, copied from the decompiled source
 * ({@code LivingEntity.travelInAir}, {@code handleRelativeFrictionAndCalculateMovement},
 * {@code getFrictionInfluencedSpeed}, {@code jumpFromGround}, {@code Player.getFlyingSpeed},
 * {@code Entity.getInputVector/collideBoundingBox}).
 *
 * <p>Used to answer the physical questions before a jump: does the arc reach the planned landing
 * at all, is a block (leaves, a half slab edge, a wall) in the way, and when is the latest
 * possible take-off tick with the current momentum? No guessing from block centres any more.
 */
public final class MinecraftPhysics {
    /** LivingEntity.travelInAir */
    public static final double GRAVITY = 0.08;
    public static final double VERTICAL_DRAG = 0.98;
    public static final double AIR_DRAG = 0.91;
    public static final double DEFAULT_BLOCK_FRICTION = 0.6;
    public static final double GROUND_DRAG = DEFAULT_BLOCK_FRICTION * AIR_DRAG;
    /** Player.getFlyingSpeed (air acceleration, independent of the speed attribute). */
    public static final double AIR_ACCEL = 0.02;
    public static final double SPRINT_AIR_ACCEL = 0.026;
    /** LivingEntity.jumpFromGround: sprint adds 0.2 in the look direction. */
    public static final double SPRINT_JUMP_BOOST = 0.2;
    /** Entity.getInputVector: the keyboard vector is normalised to 0.98 first. */
    public static final double INPUT_SCALE = 0.98;
    public static final int MAX_TICKS = 40;

    /** Result of a jump simulated from the current player state. */
    public record Jump(Vec3 landing, int ticks, boolean blocked, boolean landed, double forwardDistance) {
    }

    /** Full already-simulated jump: every air position per tick, used as the pre-computed air path. */
    public record Trajectory(Vec3 start, Vec3 landing, int ticks, boolean blocked, boolean landed,
                             double forwardDistance, List<Vec3> path) {
    }

    private MinecraftPhysics() {
    }

    /** Minecraft forward vector for a yaw (Entity.getInputVector with forward=1). */
    public static Vec3 forward(float yawDegrees) {
        double rad = Math.toRadians(yawDegrees);
        return new Vec3(-Math.sin(rad), 0, Math.cos(rad));
    }

    /**
     * LivingEntity.getJumpPower incl. the custom JUMP_STRENGTH attribute, Jump Boost and the
     * block below (slime/honey jump factor). This is the custom jump height of the player.
     */
    public static double jumpPower(LocalPlayer player) {
        try {
            BlockState here = player.level().getBlockState(player.blockPosition());
            BlockState below = player.level().getBlockState(player.getBlockPosBelowThatAffectsMyMovement());
            float jumpFactorHere = here.getBlock().getJumpFactor();
            float jumpFactorBelow = below.getBlock().getJumpFactor();
            float factor = jumpFactorHere == 1.0F ? jumpFactorBelow : jumpFactorHere;
            return player.getAttributeValue(Attributes.JUMP_STRENGTH) * factor + player.getJumpBoostPower();
        } catch (RuntimeException e) {
            return PathfinderTuning.stats().jumpVelocity();
        }
    }

    /** Entity.getBlockSpeedFactor: soul sand / honey slow the actual movement, not the attribute. */
    public static float blockSpeedFactor(Level level, Vec3 feet) {
        try {
            BlockPos here = BlockPos.containing(feet.x, feet.y + 0.01, feet.z);
            BlockPos below = BlockPos.containing(feet.x, feet.y - 0.500001, feet.z);
            float hereFactor = level.getBlockState(here).getBlock().getSpeedFactor();
            float belowFactor = level.getBlockState(below).getBlock().getSpeedFactor();
            return hereFactor == 1.0F ? belowFactor : hereFactor;
        } catch (RuntimeException e) {
            return 1.0F;
        }
    }

    public static float yawTo(Vec3 from, Vec3 to) {
        return (float) (Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90.0);
    }

    /**
     * Simulates a jump that starts this tick with W held and the camera towards {@code target}.
     * The first tick still uses the ground acceleration/friction (the game does the same: the
     * jump is applied before travel, onGround is only updated by the move).
     */
    public static Jump simulateJump(Level level, LocalPlayer player, Vec3 target) {
        if (level == null || player == null || !player.onGround() || player.isInWater()
                || player.isInLava() || player.onClimbable() || player.isFallFlying()) {
            return null;
        }
        Trajectory trajectory = simulateCore(level, player, player.position(), player.getDeltaMovement(),
                player.isSprinting(), target);
        return new Jump(trajectory.landing(), trajectory.ticks(), trajectory.blocked(),
                trajectory.landed(), trajectory.forwardDistance());
    }

    /**
     * Simulates any jump (also one whose take-off is not the player's current position). Used to
     * pre-compute the take-off points and the whole air path for every realistic speed.
     */
    public static Trajectory simulate(Level level, LocalPlayer source, Vec3 start,
                                      Vec3 velocity, boolean sprinting, Vec3 target) {
        if (level == null || source == null || target == null) {
            return null;
        }
        return simulateCore(level, source, start, velocity, sprinting, target);
    }

    private static Trajectory simulateCore(Level level, LocalPlayer player, Vec3 start,
                                           Vec3 velocity, boolean sprinting, Vec3 target) {
        PlayerMovementStats stats = PathfinderTuning.stats();
        double gravity = player.getAttributeValue(Attributes.GRAVITY);
        if (gravity <= 0) {
            gravity = stats.gravity() > 0 ? stats.gravity() : GRAVITY;
        }
        double jumpPower = jumpPower(player);
        if (jumpPower <= 0) {
            jumpPower = stats.jumpVelocity() > 0 ? stats.jumpVelocity() : 0.42;
        }
        Vec3 pos = start;
        Vec3 vel = velocity;
        float yaw = yawTo(start, target);
        Vec3 forward = forward(yaw);
        double width = player.getBbWidth();
        double height = player.getBbHeight();
        BlockPos below = BlockPos.containing(start.x, start.y - 0.08, start.z);
        BlockState belowState = level.getBlockState(below);
        double attributeSpeed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (sprinting && !player.isSprinting()) {
            // LivingEntity.setSprinting adds +30 % MOVEMENT_SPEED as a modifier
            attributeSpeed *= 1.3;
        }
        List<Vec3> path = new ArrayList<>(MAX_TICKS);

        // jumpFromGround (LivingEntity)
        vel = new Vec3(vel.x, Math.max(jumpPower, vel.y), vel.z);
        if (sprinting) {
            vel = vel.add(forward.scale(SPRINT_JUMP_BOOST));
        }

        boolean onGround = true;
        boolean blocked = false;
        double startX = start.x, startZ = start.z;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            double accel;
            double blockFriction;
            if (onGround) {
                blockFriction = belowState.getBlock().getFriction();
                double frictionSpeed = blockFriction > 0.6
                        ? attributeSpeed * (0.21600002 / (blockFriction * blockFriction * blockFriction))
                        : attributeSpeed;
                accel = frictionSpeed;
            } else {
                blockFriction = 1.0;
                accel = sprinting ? SPRINT_AIR_ACCEL : AIR_ACCEL;
            }
            // moveRelative: normalised input (0.98) * friction influenced speed
            vel = vel.add(forward.scale(accel * INPUT_SCALE));

            AABB box = new AABB(pos.x - width / 2.0, pos.y, pos.z - width / 2.0,
                    pos.x + width / 2.0, pos.y + height, pos.z + width / 2.0);
            Vec3 delta = Entity.collideBoundingBox(player, vel, box, level, List.of());
            boolean xzBlocked = Math.abs(delta.x - vel.x) > 1.0E-7 || Math.abs(delta.z - vel.z) > 1.0E-7;
            blocked |= xzBlocked;
            pos = pos.add(delta);
            path.add(pos);
            if (vel.y <= 0.0 && delta.y > vel.y + 1.0E-9) {
                double dx = pos.x - startX, dz = pos.z - startZ;
                return new Trajectory(start, pos, tick + 1, blocked, true,
                        Math.hypot(dx, dz), path);
            }
            double movementY = delta.y - gravity;
            double friction = blockFriction * AIR_DRAG;
            // Entity.move: block speed factor (soul sand / honey) scales the moved vector.
            float speedFactor = blockSpeedFactor(level, pos);
            vel = new Vec3(delta.x * speedFactor * friction,
                    movementY * VERTICAL_DRAG,
                    delta.z * speedFactor * friction);
            onGround = false;
            if (pos.y < level.getMinY() - 4.0) {
                break;
            }
        }
        return new Trajectory(start, pos, MAX_TICKS, blocked, false,
                Math.hypot(pos.x - startX, pos.z - startZ), path);
    }

    /**
     * Should the bot jump this tick to land on {@code landing}? The arc must reach it (or at
     * most a little past it), must not be cut off by a wall/leaves, and a jump that is still far
     * short is too early. If we are already at the take-off edge and it is still short, the jump
     * is physically impossible from here.
     */
    public static Decision decideJump(Level level, LocalPlayer player, Vec3 takeoff, Vec3 landing) {
        Jump jump = simulateJump(level, player, landing);
        if (jump == null) {
            return Decision.notNow(false);
        }
        Vec3 feet = player.position();
        double required = Math.hypot(landing.x - feet.x, landing.z - feet.z);
        double reached = jump.forwardDistance();
        double heightError = jump.landed() ? Math.abs(jump.landing().y - landing.y) : Double.MAX_VALUE;
        boolean heightOk = heightError <= 0.7;
        boolean atEdge = Math.hypot(feet.x - takeoff.x, feet.z - takeoff.z) <= 0.8;
        // A side collision while jumping onto a ledge is normal: the body touches the step
        // side but the feet still land on top. "blocked" only matters when the arc also lands
        // short or at the wrong height.
        boolean landedOk = jump.landed() && heightOk && reached >= required - 1.0;
        boolean impossible = (!jump.landed() && atEdge)
                || (atEdge && (reached < required - 1.0 || !heightOk));
        boolean jumpNow = landedOk
                && reached >= required - 0.35
                && (reached <= required + 4.0 || atEdge); // at the edge: better jump than walk off
        String reason = String.format(Locale.ROOT,
                "need %.2f, arc reaches %.2f in %d ticks, dy %.2f%s", required, reached, jump.ticks(),
                jump.landed() ? jump.landing().y - landing.y : Double.NaN,
                jump.blocked() ? ", blocked" : "");
        return new Decision(jumpNow, impossible, atEdge, jump.blocked() || !jump.landed(), reason);
    }

    public record Decision(boolean jumpNow, boolean impossible, boolean atEdge, boolean blocked, String reason) {
        static Decision notNow(boolean impossible) {
            return new Decision(false, impossible, false, false, "not on ground");
        }
    }
}
