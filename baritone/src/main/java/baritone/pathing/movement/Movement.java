/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.behavior.LookBehavior;
import baritone.behavior.PathingBehavior;
import baritone.utils.BlockStateInterface;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.AABB;

public abstract class Movement implements IMovement, MovementHelper {

    public static final Direction[] HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

    protected final IBaritone baritone;
    protected final IPlayerContext ctx;

    private MovementState currentState = new MovementState().setStatus(MovementStatus.PREPPING);

    protected final BetterBlockPos src;

    protected final BetterBlockPos dest;

    /**
     * The positions that need to be broken before this movement can ensue
     */
    protected final BetterBlockPos[] positionsToBreak;

    /**
     * The position where we need to place a block before this movement can ensue
     */
    protected final BetterBlockPos positionToPlace;

    private Double cost;

    public List<BlockPos> toBreakCached = null;
    public List<BlockPos> toPlaceCached = null;
    public List<BlockPos> toWalkIntoCached = null;

    private Set<BetterBlockPos> validPositionsCached = null;

    private Boolean calculatedWhileLoaded;

    protected Movement(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak, BetterBlockPos toPlace) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.src = src;
        this.dest = dest;
        this.positionsToBreak = toBreak;
        this.positionToPlace = toPlace;
    }

    protected Movement(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak) {
        this(baritone, src, dest, toBreak, null);
    }

    public double getCost() throws NullPointerException {
        return cost;
    }

    public double getCost(CalculationContext context) {
        if (cost == null) {
            cost = calculateCost(context);
        }
        return cost;
    }

    public abstract double calculateCost(CalculationContext context);

    public double recalculateCost(CalculationContext context) {
        cost = null;
        return getCost(context);
    }

    public void override(double cost) {
        this.cost = cost;
    }

    protected abstract Set<BetterBlockPos> calculateValidPositions();

    public Set<BetterBlockPos> getValidPositions() {
        if (validPositionsCached == null) {
            validPositionsCached = calculateValidPositions();
            Objects.requireNonNull(validPositionsCached);
        }
        return validPositionsCached;
    }

    protected boolean playerInValidPosition() {
        return getValidPositions().contains(ctx.playerFeet()) || getValidPositions().contains(((PathingBehavior) baritone.getPathingBehavior()).pathStart());
    }

    /**
     * Handles the execution of the latest Movement
     * State, and offers a Status to the calling class.
     *
     * @return Status
     */
    @Override
    public MovementStatus update() {
        ctx.player().getAbilities().flying = false;
        currentState = updateState(currentState);
        // on the last block before an edge, "there" only counts once we stopped: at SkyBlock speed
        // the slide carried the bot off the 1x1 pillar right after the movement was done
        if (currentState.getStatus() == MovementStatus.SUCCESS && stillSlidingToEdge()) {
            currentState.setStatus(MovementStatus.RUNNING);
        }
        if (MovementHelper.isLiquid(ctx, ctx.playerFeet()) && ctx.player().position().y < dest.y + 0.6) {
            currentState.setInput(Input.JUMP, true);
        }
        if (ctx.player().isInWall()) {
            ctx.getSelectedBlock().ifPresent(pos -> MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, pos)));
            currentState.setInput(Input.CLICK_LEFT, true);
        }

        // If the movement target has to force the new rotations, or we aren't using silent move, then force the rotations
        if (carrot != null && !currentState.getTarget().hasToForceRotations()
                && currentState.getStatus() == MovementStatus.RUNNING
                && (this instanceof baritone.pathing.movement.movements.MovementTraverse
                || this instanceof baritone.pathing.movement.movements.MovementDiagonal)
                && currentState.getTarget().getRotation().isPresent()) {
            // walk at the smoothing point (farther down the path) instead of this block's centre
            Rotation toCarrot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                    new net.minecraft.world.phys.Vec3(carrot.x, ctx.playerHead().y, carrot.z),
                    ctx.playerRotations());
            currentState.setTarget(new MovementState.MovementTarget(
                    toCarrot.withPitch(currentState.getTarget().getRotation().get().getPitch()), false));
        }
        preTurn();
        noTurnBack();
        currentState.getTarget().getRotation().ifPresent(rotation -> {
            baritone.getLookBehavior().updateTarget(
                    rotation,
                    currentState.getTarget().hasToForceRotations());
            if (!currentState.getTarget().hasToForceRotations()) {
                alignInputsWithTurn(currentState, rotation);
            }
        });
        dropStyle();
        edgeBrake();
        baritone.getInputOverrideHandler().clearAllKeys();
        currentState.getInputStates().forEach((input, forced) -> {
            baritone.getInputOverrideHandler().setInputForceState(input, forced);
        });
        currentState.getInputStates().clear();

        // If the current status indicates a completed movement
        if (currentState.getStatus().isComplete()) {
            baritone.getInputOverrideHandler().clearAllKeys();
        }

        return currentState.getStatus();
    }

    /**
     * Air Client: the walking direction may only turn {@code maxYawChangePerTick} degrees per
     * tick. Holding forward during a bigger turn walked through every direction in between: the
     * path recorder showed 2 blocks of sideways drift after each corner and the bot walking back
     * and forth. While the turn is not done, pick the WASD keys that move towards the wanted
     * direction from the yaw we really have (like a player who strafes a little into a corner);
     * a turn of more than ~110 degrees waits the two or three ticks it takes.
     */
    /**
     * Already moving along the move at speed: never turn back to the block centre. At SkyBlock
     * speed every 1-block step overshot its centre, the aim flipped 100-180 degrees back and forth
     * every two ticks and the bot drifted off a 1-wide path (levels 10/11). Past the centre the
     * executor moves on to the next movement anyway.
     */
    private void noTurnBack() {
        if (currentState.getStatus() != MovementStatus.RUNNING || currentState.getTarget().hasToForceRotations()
                || currentState.getTarget().getRotation().isEmpty()
                || !(this instanceof baritone.pathing.movement.movements.MovementTraverse
                || this instanceof baritone.pathing.movement.movements.MovementDiagonal
                || this instanceof baritone.pathing.movement.movements.MovementDescend
                || this instanceof baritone.pathing.movement.movements.MovementAscend
                || this instanceof baritone.pathing.movement.movements.MovementFall)) {
            return;
        }
        int dx = dest.x - src.x, dz = dest.z - src.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len == 0) return;
        net.minecraft.world.phys.Vec3 v = ctx.player().getDeltaMovement();
        double along = (v.x * dx + v.z * dz) / len;
        if (along < 0.15) return;
        if (edgeAfterDest()) return; // stopping on the last block before an edge may need a turn back
        float dirYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        Rotation r = currentState.getTarget().getRotation().get();
        if (Math.abs(net.minecraft.util.Mth.wrapDegrees(r.getYaw() - dirYaw)) > 75f) {
            currentState.setTarget(new MovementState.MovementTarget(new Rotation(dirYaw, r.getPitch()), false));
        }
    }

    private boolean stillSlidingToEdge() {
        if (!(this instanceof baritone.pathing.movement.movements.MovementTraverse
                || this instanceof baritone.pathing.movement.movements.MovementDiagonal
                || this instanceof baritone.pathing.movement.movements.MovementAscend
                || this instanceof baritone.pathing.movement.movements.MovementDescend)
                || !ctx.player().onGround() || !edgeAfterDest()) {
            return false;
        }
        int dx = dest.x - src.x, dz = dest.z - src.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        net.minecraft.world.phys.Vec3 v = ctx.player().getDeltaMovement();
        return len > 0 && (v.x * dx + v.z * dz) / len > 0.06;
    }

    /** Beyond the destination (one more block the same way) there is no floor: an edge. */
    private boolean edgeAfterDest() {
        int dx = Integer.signum(dest.x - src.x), dz = Integer.signum(dest.z - src.z);
        if (dx == 0 && dz == 0) return false;
        BetterBlockPos beyond = new BetterBlockPos(dest.x + dx, dest.y - 1, dest.z + dz);
        return !MovementHelper.canWalkOn(ctx, beyond) && !MovementHelper.canWalkOn(ctx, beyond.below());
    }

    /**
     * Stop on the last block before an edge: at SkyBlock speed the momentum alone carries a player
     * more than a block (ground: x0.546 per tick). Let go - and brake - so it ends on the block.
     * Level 10/11: over the 1x1 pillar top and down the other side.
     */
    private void edgeBrake() {
        if (currentState.getStatus() != MovementStatus.RUNNING || !ctx.player().onGround()
                || !(this instanceof baritone.pathing.movement.movements.MovementTraverse
                || this instanceof baritone.pathing.movement.movements.MovementDiagonal
                || this instanceof baritone.pathing.movement.movements.MovementAscend
                || this instanceof baritone.pathing.movement.movements.MovementDescend)
                || !edgeAfterDest()) {
            return;
        }
        int dx = dest.x - src.x, dz = dest.z - src.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len == 0) return;
        net.minecraft.world.phys.Vec3 p = ctx.player().position(), v = ctx.player().getDeltaMovement();
        double left = ((dest.x + 0.5 - p.x) * dx + (dest.z + 0.5 - p.z) * dz) / len;
        double along = (v.x * dx + v.z * dz) / len;
        if (along <= 0.05) return;
        // what friction alone still carries us, plus this tick's move (keys act from the next one)
        double coast = along / (1 - 0.546) + along;
        if (coast > left + 0.15) {
            currentState.setInput(Input.MOVE_FORWARD, false);
            currentState.setInput(Input.SPRINT, false);
            if (coast > left + 0.5) currentState.setInput(Input.MOVE_BACK, true);
        }
    }

    /** No floor beside the move (left and right of start and end): a 1-wide path. */
    private boolean narrowHere() {
        int dx = Integer.signum(dest.x - src.x), dz = Integer.signum(dest.z - src.z);
        if (dx == 0 && dz == 0) return false;
        int sx = -dz, sz = dx; // sideways
        for (BetterBlockPos p : new BetterBlockPos[]{src, dest}) {
            int floorY = p.y - 1;
            if (MovementHelper.canWalkOn(ctx, new BetterBlockPos(p.x + sx, floorY, p.z + sz))
                    || MovementHelper.canWalkOn(ctx, new BetterBlockPos(p.x - sx, floorY, p.z - sz))
                    || MovementHelper.canWalkOn(ctx, new BetterBlockPos(p.x + sx, floorY - 1, p.z + sz))
                    || MovementHelper.canWalkOn(ctx, new BetterBlockPos(p.x - sx, floorY - 1, p.z - sz))) {
                return false;
            }
        }
        return true;
    }

    /** Turn style 2: near the end of a walking move, aim part of the way towards the next one. */
    private void preTurn() {
        if (Baritone.settings().turnStyle.value != 2 || nextYaw == null
                || currentState.getStatus() != MovementStatus.RUNNING
                || currentState.getTarget().hasToForceRotations()
                || !(this instanceof baritone.pathing.movement.movements.MovementTraverse
                || this instanceof baritone.pathing.movement.movements.MovementDiagonal)
                || currentState.getTarget().getRotation().isEmpty()) {
            return;
        }
        double dx = dest.x + 0.5 - ctx.player().position().x, dz = dest.z + 0.5 - ctx.player().position().z;
        double left = Math.sqrt(dx * dx + dz * dz);
        double within = Math.max(0.2, Baritone.settings().preTurnDistance.value);
        if (left >= within) {
            return;
        }
        Rotation r = currentState.getTarget().getRotation().get();
        float share = (float) (1.0 - left / within) * 0.5f; // at the corner: half way round
        float yaw = r.getYaw() + net.minecraft.util.Mth.wrapDegrees(nextYaw - r.getYaw()) * share;
        currentState.setTarget(new MovementState.MovementTarget(new Rotation(yaw, r.getPitch()), false));
    }

    /** Drop style 1/2 for walking off an edge (descend / fall, stairs are slopes and stay as they are). */
    private void dropStyle() {
        int style = Baritone.settings().dropStyle.value;
        if (style == 0 || currentState.getStatus() != MovementStatus.RUNNING
                || !(this instanceof baritone.pathing.movement.movements.MovementDescend
                || this instanceof baritone.pathing.movement.movements.MovementFall)
                || !ctx.player().onGround() || !ctx.playerFeet().equals(src)
                || MovementHelper.isRamp(ctx, src, dest)) {
            return;
        }
        currentState.setInput(Input.SPRINT, false);
        if (style == 2) {
            double jx = dest.x - src.x, jz = dest.z - src.z;
            double len = Math.sqrt(jx * jx + jz * jz);
            if (len == 0) return;
            net.minecraft.world.phys.Vec3 p = ctx.player().position(), v = ctx.player().getDeltaMovement();
            double along = ((p.x - (src.x + 0.5)) * jx + (p.z - (src.z + 0.5)) * jz) / len;
            double speed = (v.x * jx + v.z * jz) / len;
            if (along > 0.1 && speed > 0.08) {
                currentState.setInput(Input.MOVE_FORWARD, false); // brake at the edge, then step off slowly
            }
        }
    }

    private void alignInputsWithTurn(MovementState state, Rotation rotation) {
        java.util.Map<Input, Boolean> inputs = state.getInputStates();
        if (!Boolean.TRUE.equals(inputs.get(Input.MOVE_FORWARD))
                || Boolean.TRUE.equals(inputs.get(Input.MOVE_BACK))
                || Boolean.TRUE.equals(inputs.get(Input.MOVE_LEFT))
                || Boolean.TRUE.equals(inputs.get(Input.MOVE_RIGHT))) {
            return;
        }
        float wantYaw = rotation.getYaw();
        float moveYaw = ((LookBehavior) baritone.getLookBehavior()).plannedMoveYaw(rotation);
        float off = net.minecraft.util.Mth.wrapDegrees(wantYaw - moveYaw);
        // With the walking yaw smoothed (moveYawSmoothing) it lags the block-centre aim a bit on
        // every zig-zag; forward still makes good progress up to ~40 degrees, and strafing on
        // every small lag looked like walking sideways back and forth.
        boolean narrow = narrowHere();
        // on a 1-wide path a few degrees off is a block sideways at SkyBlock speed
        float tolerance = narrow ? 6f : Baritone.settings().moveYawSmoothing.value > 0 ? 40f : 25f;
        if (Math.abs(off) <= tolerance) {
            return;
        }
        if (narrow) {
            // a 1-wide path (no floor left and right): no strafing into the turn - at SkyBlock
            // speed the side keys carried the bot off the edge (levels 10/11). Stand and turn.
            state.setInput(Input.MOVE_FORWARD, false);
            state.setInput(Input.SPRINT, false);
            return;
        }
        if (Baritone.settings().turnStyle.value == 1 && Math.abs(off) > Baritone.settings().turnStopAngle.value) {
            // turn style 1: stand (and sneak) until the camera is round, then walk on
            state.setInput(Input.MOVE_FORWARD, false);
            state.setInput(Input.SPRINT, false);
            if (Baritone.settings().turnSneak.value && ctx.player().onGround()) {
                state.setInput(Input.SNEAK, true);
            }
            return;
        }
        state.setInput(Input.MOVE_FORWARD, false);
        if (Math.abs(off) > 112.5f) {
            return;
        }
        float rad = (float) Math.PI / 180f;
        MovementOption.getOptions(
                net.minecraft.util.Mth.sin(moveYaw * rad),
                net.minecraft.util.Mth.cos(moveYaw * rad),
                Baritone.settings().allowSprint.value
        ).filter(option -> option.input1() != Input.MOVE_BACK)
                .min(java.util.Comparator.comparing(option -> option.distanceToSq(
                        net.minecraft.util.Mth.sin(wantYaw * rad),
                        net.minecraft.util.Mth.cos(wantYaw * rad))))
                .ifPresent(option -> option.setInputs(state));
    }

    /** Air Client: path smoothing point to walk towards instead of the block centre (or null). */
    private net.minecraft.world.phys.Vec3 carrot;

    public void setCarrot(net.minecraft.world.phys.Vec3 carrot) {
        this.carrot = carrot;
    }

    /** Air Client: yaw of the movement after this one (turn style 2 turns towards it early), or null. */
    private Float nextYaw;

    public void setNextYaw(Float nextYaw) {
        this.nextYaw = nextYaw;
    }

    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        boolean somethingInTheWay = false;
        for (BetterBlockPos blockPos : positionsToBreak) {
            if (!ctx.world().getEntitiesOfClass(FallingBlockEntity.class, new AABB(0, 0, 0, 1, 1.1, 1).move(blockPos)).isEmpty() && Baritone.settings().pauseMiningForFallingBlocks.value) {
                return false;
            }
            if (!MovementHelper.canWalkThrough(ctx, blockPos)) { // can't break air, so don't try
                somethingInTheWay = true;
                MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, blockPos));
                Optional<Rotation> reachable = RotationUtils.reachable(ctx, blockPos, ctx.playerController().getBlockReachDistance());
                if (reachable.isPresent()) {
                    Rotation rotTowardsBlock = reachable.get();
                    state.setTarget(new MovementState.MovementTarget(rotTowardsBlock, true));
                    if (ctx.isLookingAt(blockPos) || ctx.playerRotations().isReallyCloseTo(rotTowardsBlock)) {
                        state.setInput(Input.CLICK_LEFT, true);
                    }
                    return false;
                }
                //get rekt minecraft
                //i'm doing it anyway
                //i dont care if theres snow in the way!!!!!!!
                //you dont own me!!!!
                state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                        VecUtils.getBlockPosCenter(blockPos), ctx.playerRotations()), true)
                );
                // don't check selectedblock on this one, this is a fallback when we can't see any face directly, it's intended to be breaking the "incorrect" block
                state.setInput(Input.CLICK_LEFT, true);
                return false;
            }
        }
        if (somethingInTheWay) {
            // There's a block or blocks that we can't walk through, but we have no target rotation to reach any
            // So don't return true, actually set state to unreachable
            state.setStatus(MovementStatus.UNREACHABLE);
            return true;
        }
        return true;
    }

    @Override
    public boolean safeToCancel() {
        return safeToCancel(currentState);
    }

    protected boolean safeToCancel(MovementState currentState) {
        return true;
    }

    @Override
    public BetterBlockPos getSrc() {
        return src;
    }

    @Override
    public BetterBlockPos getDest() {
        return dest;
    }

    @Override
    public void reset() {
        currentState = new MovementState().setStatus(MovementStatus.PREPPING);
    }

    /**
     * Calculate latest movement state. Gets called once a tick.
     *
     * @param state The current state
     * @return The new state
     */
    public MovementState updateState(MovementState state) {
        if (!prepared(state)) {
            return state.setStatus(MovementStatus.PREPPING);
        } else if (state.getStatus() == MovementStatus.PREPPING) {
            state.setStatus(MovementStatus.WAITING);
        }

        if (state.getStatus() == MovementStatus.WAITING) {
            state.setStatus(MovementStatus.RUNNING);
        }

        return state;
    }

    @Override
    public BlockPos getDirection() {
        return getDest().subtract(getSrc());
    }

    public void checkLoadedChunk(CalculationContext context) {
        calculatedWhileLoaded = context.bsi.worldContainsLoadedChunk(dest.x, dest.z);
    }

    @Override
    public boolean calculatedWhileLoaded() {
        return calculatedWhileLoaded;
    }

    @Override
    public void resetBlockCache() {
        toBreakCached = null;
        toPlaceCached = null;
        toWalkIntoCached = null;
    }

    public List<BlockPos> toBreak(BlockStateInterface bsi) {
        if (toBreakCached != null) {
            return toBreakCached;
        }
        List<BlockPos> result = new ArrayList<>();
        for (BetterBlockPos positionToBreak : positionsToBreak) {
            if (!MovementHelper.canWalkThrough(bsi, positionToBreak.x, positionToBreak.y, positionToBreak.z)) {
                result.add(positionToBreak);
            }
        }
        toBreakCached = result;
        return result;
    }

    public List<BlockPos> toPlace(BlockStateInterface bsi) {
        if (toPlaceCached != null) {
            return toPlaceCached;
        }
        List<BlockPos> result = new ArrayList<>();
        if (positionToPlace != null && !MovementHelper.canWalkOn(bsi, positionToPlace.x, positionToPlace.y, positionToPlace.z)) {
            result.add(positionToPlace);
        }
        toPlaceCached = result;
        return result;
    }

    public List<BlockPos> toWalkInto(BlockStateInterface bsi) { // overridden by movementdiagonal
        if (toWalkIntoCached == null) {
            toWalkIntoCached = new ArrayList<>();
        }
        return toWalkIntoCached;
    }

    public BlockPos[] toBreakAll() {
        return positionsToBreak;
    }
}
