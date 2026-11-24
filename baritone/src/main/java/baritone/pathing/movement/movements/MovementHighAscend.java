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
import com.google.common.collect.ImmutableSet;
import net.minecraft.core.Direction;

import java.util.Set;

/**
 * A straight jump onto a ledge 2 or more blocks higher than the player, for players with a boosted
 * jump (see {@link baritone.api.Settings#jumpHeight}). The plain one-block case stays
 * {@link MovementAscend}. Never breaks or places blocks.
 */
public class MovementHighAscend extends Movement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};

    private final Direction direction;
    private final int height;

    private MovementHighAscend(IBaritone baritone, BetterBlockPos src, Direction direction, int height) {
        super(baritone, src, src.relative(direction).above(height), EMPTY, src.relative(direction).above(height - 1));
        this.direction = direction;
        this.height = height;
    }

    public static MovementHighAscend create(CalculationContext context, BetterBlockPos src, Direction direction) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, direction, res);
        int height = res.cost < COST_INF ? res.y - src.y : 2;
        return new MovementHighAscend(context.getBaritone(), src, direction, Math.max(2, height));
    }

    /**
     * Finds the lowest ledge (2..maxHighJump blocks up) in {@code direction} that can be reached with one
     * straight jump. Leaves {@code res.cost} at COST_INF if there is none.
     */
    public static void cost(CalculationContext context, int x, int y, int z, Direction direction, MutableMoveResult res) {
        res.x = x + direction.getStepX();
        res.y = y + 2;
        res.z = z + direction.getStepZ();
        res.cost = COST_INF;
        int max = context.maxHighJump;
        if (!context.allowJumpAtBuildLimit && y >= context.world.getMaxY()) {
            return;
        }
        if (!MovementHelper.canWalkOn(context, x, y - 1, z)) {
            return; // need solid ground to jump from
        }
        if (MovementHelper.isBottomSlab(context.get(x, y - 1, z))) {
            // a jump from a bottom slab starts half a block lower: one block less reach. With
            // SkyBlock's 6-block jump that is still 4 up (level 10/11: from the slab onto the
            // log pillar) - it used to be refused altogether.
            max = Math.min(max, (int) Math.floor(Baritone.settings().jumpHeight.value - 0.2D - 0.5D));
        }
        if (max < 2) {
            return;
        }
        int destX = res.x;
        int destZ = res.z;
        // the column we jump in must be free up to head height above the ledge
        if (!MovementHelper.fullyPassable(context, x, y + 2, z)) {
            return;
        }
        for (int h = 2; h <= max; h++) {
            if (!MovementHelper.fullyPassable(context, x, y + h + 1, z)) {
                return; // ceiling - every higher jump is blocked too
            }
            if (!MovementHelper.canWalkOn(context, destX, y + h - 1, destZ)) {
                continue;
            }
            if (!MovementHelper.canWalkThrough(context, destX, y + h, destZ)
                    || !MovementHelper.canWalkThrough(context, destX, y + h + 1, destZ)) {
                continue;
            }
            if (h == 2 && MovementHelper.canWalkThrough(context, destX, y + 1, destZ)
                    && MovementHelper.canWalkOn(context, destX, y, destZ)) {
                continue; // a normal ascend + ascend covers this
            }
            res.y = y + h;
            res.cost = WALK_ONE_BLOCK_COST + JUMP_ONE_BLOCK_COST + context.jumpPenalty + h * 1.5D;
            return;
        }
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, direction, res);
        if (res.y != dest.y) {
            return COST_INF;
        }
        return res.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        ImmutableSet.Builder<BetterBlockPos> builder = ImmutableSet.builder();
        for (int i = 0; i <= height + 1; i++) {
            builder.add(src.above(i));
        }
        builder.add(dest);
        builder.add(dest.above());
        builder.add(new BetterBlockPos(src.subtract(getDirection())));
        return builder.build();
    }

    @Override
    public MovementState updateState(MovementState state) {
        if (ctx.playerFeet().y < src.y) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        if (ctx.playerFeet().equals(dest)) {
            // Air Client: like MovementAscend, finish only after the landing.
            if (ctx.player().onGround()) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            MovementHelper.steerWhileLanding(ctx, state, src, dest);
            state.setInput(Input.SPRINT, false);
            return state;
        }

        MovementHelper.moveTowards(ctx, state, dest);
        state.setInput(Input.SPRINT, false);

        double px = ctx.player().position().x;
        double pz = ctx.player().position().z;
        if (!ctx.player().onGround() || ctx.playerFeet().y > src.y) {
            // Air Client: in the air only push onto the ledge once we are above it (or at the top
            // of the jump). Pushing at once moved the head under the ledge's edge and the jump
            // ended there (level 11: "high ascend without gain" again and again under a roof).
            boolean aboveLedge = ctx.player().position().y >= dest.getY() - 0.1;
            boolean atTop = ctx.player().getDeltaMovement().y < 0.05;
            if (!aboveLedge && !atTop) {
                state.setInput(Input.MOVE_FORWARD, false);
            }
            return state;
        }

        int xAxis = Math.abs(direction.getStepX());
        int zAxis = Math.abs(direction.getStepZ());
        // Air Client: jump only with the whole body in the take-off column, next to the ledge -
        // not under it. At SkyBlock speed the run-up overshot into the next column (under the roof
        // of level 11) and the jump hit the ceiling. Line up sneaking (slow), then jump.
        double alongOff = xAxis * Math.abs((src.getX() + 0.5D) - px) + zAxis * Math.abs((src.getZ() + 0.5D) - pz);
        double sideDist = zAxis * Math.abs((dest.getX() + 0.5D) - px) + xAxis * Math.abs((dest.getZ() + 0.5D) - pz);
        net.minecraft.world.phys.Vec3 v = ctx.player().getDeltaMovement();
        if (alongOff > 0.2D || sideDist > 0.2D || Math.hypot(v.x, v.z) > 0.12D) {
            MovementHelper.moveTowards(ctx, state, src);
            state.setInput(Input.SNEAK, true);
            state.setInput(Input.SPRINT, false);
            if (alongOff <= 0.2D && sideDist <= 0.2D) {
                state.setInput(Input.MOVE_FORWARD, false); // in place: only let the speed die down
            }
            return state;
        }
        state.setInput(Input.MOVE_FORWARD, false);
        return state.setInput(Input.JUMP, true);
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        return ctx.player().onGround();
    }
}
