package com.coflnet.pathing;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/** Follows one loaded player with a moving Baritone goal. */
public final class PlayerFollowProcess implements IBaritoneProcess {
    private static final long REACQUIRE_MS = 3_000;
    private static final long REPLAN_MS = 300;
    private static final double RANGE_SQUARED = 96 * 96;

    private IBaritone registeredOn;
    private ClientLevel level;
    private UUID targetId;
    private String targetName;
    private Player target;
    private Vec3 lastPosition;
    private long lastSeenAt;

    private Goal goal;
    private Goal observedGoal;
    private BlockPos anchor;
    private long plannedAt;
    private long tickAt;
    private long retryAt;
    private int failures;
    private int plannedRadius;
    private boolean blockedGoal;
    private boolean holdingDistance;

    public void attach(IBaritone baritone) {
        if (registeredOn != baritone) {
            baritone.getPathingControlManager().registerProcess(this);
            registeredOn = baritone;
        }
    }

    /** Returns true while a configured player is tracked, including temporary loss. */
    public boolean update(ClientLevel world, LocalPlayer follower, List<String> names, int radius, long now) {
        if (world != level || (targetName != null && names.stream().noneMatch(targetName::equalsIgnoreCase))) {
            reset();
            level = world;
        }
        tickAt = now;
        Player seen = targetId == null ? null : world.players().stream()
                .filter(player -> targetId.equals(player.getUUID())).findFirst().orElse(null);
        if (seen != null && (seen.isSpectator() || !seen.isAlive())) {
            lastPosition = null;
            onLostControl();
        }
        if (!available(seen, follower)) {
            seen = null;
            if (lastPosition == null || now - lastSeenAt >= REACQUIRE_MS) {
                seen = findPlayer(world, follower, names);
            }
        }
        if (seen == null) {
            boolean justLost = target != null;
            target = null;
            holdingDistance = false;
            if (lastPosition != null && now - lastSeenAt < REACQUIRE_MS
                    && world.isLoaded(BlockPos.containing(lastPosition))) {
                BlockPos lastBlock = BlockPos.containing(lastPosition);
                if (justLost || goal == null) {
                    setGoal(lastBlock, new GoalNear(lastBlock, 2), 2, false, now);
                }
            } else {
                onLostControl();
            }
            return targetId != null;
        }

        boolean changedPlayer = !seen.getUUID().equals(targetId);
        if (changedPlayer || target == null) {
            lastPosition = null;
            onLostControl();
            holdingDistance = false;
        }
        target = seen;
        targetId = seen.getUUID();
        targetName = seen.getGameProfile().name();
        Vec3 position = seen.position();
        Vec3 previous = lastPosition;
        long elapsed = now - lastSeenAt;
        lastPosition = position;
        lastSeenAt = now;

        double distance = follower.distanceTo(seen);
        boolean near = distance <= radius + 2.0;
        boolean clear = near && follower.hasLineOfSight(seen);
        BlockPos feet = follower.blockPosition();
        boolean routeReached = goal != null && goal.isInGoal(feet);
        // Baritone arrives on a block, while the two players can stand off-center in theirs.
        holdingDistance = clear && distance <= radius + (holdingDistance || routeReached ? 0.75 : 0.0);
        if (holdingDistance) {
            onLostControl();
            return true;
        }

        boolean blocked = near && !clear;
        BlockPos current = seen.blockPosition();
        double threshold = Math.clamp((distance - radius) * 0.12, 1.0, 4.0);
        boolean staleGoalReached = !blocked && routeReached && distance > radius + 0.75;
        if (goal != null && !staleGoalReached && plannedRadius == radius && blockedGoal == blocked
                && (now - plannedAt < REPLAN_MS || (anchor.distSqr(current) < threshold * threshold
                && Math.abs(anchor.getY() - current.getY()) < 2))) {
            return true;
        }

        if (blocked) {
            // A radius alone can accept the wrong side of a wall or the floor below.
            Goal visible = VisibleGoal.of(world, seen, Math.min(radius, 2));
            Goal approach = visible instanceof VisibleGoal && !visible.isInGoal(feet)
                    ? visible : new GoalNear(current, feet.distSqr(current) > 1 ? 1 : 0);
            setGoal(current, approach, radius, true, now);
            return true;
        }

        Vec3 lead = distance > radius + 4 && seen.onGround() ? lead(position, previous, elapsed) : position;
        BlockPos ahead = BlockPos.containing(lead);
        Goal observed = new GoalNear(current, radius);
        if (staleGoalReached && observed.isInGoal(feet)) {
            observed = new GoalNear(current, radius - 1);
        }
        if (!ahead.equals(current) && VisibleGoal.standable(world, ahead)
                && LineFollower.walkable(world, position, lead)) {
            setGoal(current, new GoalNear(ahead, radius), radius, false, now);
            observedGoal = observed;
        } else {
            setGoal(current, observed, radius, false, now);
        }
        return true;
    }

    private static Player findPlayer(ClientLevel world, LocalPlayer follower, List<String> names) {
        for (String name : names) {
            for (Player player : world.players()) {
                if (available(player, follower) && player.getGameProfile().name().equalsIgnoreCase(name)) {
                    return player;
                }
            }
        }
        return null;
    }

    private static boolean available(Player player, LocalPlayer follower) {
        return player != null && player != follower && player.isAlive() && !player.isRemoved()
                && !player.isSpectator() && follower.distanceToSqr(player) < RANGE_SQUARED;
    }

    /** A short lead along observed horizontal movement, ignoring jumps, stale samples and teleports. */
    static Vec3 lead(Vec3 position, Vec3 previous, long elapsed) {
        if (previous == null || elapsed <= 0 || elapsed > 250 || Math.abs(position.y - previous.y) > 0.6) {
            return position;
        }
        Vec3 movement = new Vec3(position.x - previous.x, 0, position.z - previous.z);
        double speed = movement.length() * 1_000 / elapsed;
        if (speed < 0.75 || speed > 16) return position;
        return position.add(movement.normalize().scale(Math.min(2.5, speed * 0.35)));
    }

    private void setGoal(BlockPos anchor, Goal goal, int radius, boolean blocked, long now) {
        this.anchor = anchor;
        this.goal = goal;
        observedGoal = goal;
        plannedRadius = radius;
        blockedGoal = blocked;
        plannedAt = now;
        retryAt = 0;
        failures = 0;
    }

    public Player target() {
        return target;
    }

    public String targetName() {
        return targetName;
    }

    public String status() {
        if (target == null) return goal == null ? "Waiting for player" : "Going to last known position";
        if (retryAt > tickAt) return "Waiting for a route";
        return holdingDistance ? "Keeping distance" : "Following";
    }

    @Override
    public boolean isActive() {
        return goal != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (calcFailed) {
            if (!goal.equals(observedGoal)) {
                goal = observedGoal;
            } else {
                retryAt = tickAt + (500L << Math.min(failures++, 2));
            }
        }
        if (retryAt > tickAt) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return new PathingCommand(goal, isSafeToCancel
                ? PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH : PathingCommandType.SET_GOAL_AND_PATH);
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    @Override
    public void onLostControl() {
        goal = null;
        observedGoal = null;
        retryAt = 0;
        failures = 0;
    }

    @Override
    public String displayName0() {
        return "Following " + targetName;
    }

    public void reset() {
        onLostControl();
        level = null;
        targetId = null;
        targetName = null;
        target = null;
        lastPosition = null;
        holdingDistance = false;
    }
}
