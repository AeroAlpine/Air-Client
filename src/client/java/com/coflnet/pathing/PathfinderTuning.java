package com.coflnet.pathing;

/**
 * Applies the player's real movement values and the Pathfinder tab settings to
 * Baritone. Called by every bot that paths (mob pathfinder, lowballer, follow)
 * after it set its own task specific settings.
 */
public final class PathfinderTuning {
    /** Baritone's ActionCosts.COST_INF: movements at or above it are never used. */
    public static final double NO_JUMP = 1_000_000.0;

    private static volatile PlayerMovementStats stats = PlayerMovementStats.VANILLA;
    /** Sprint was switched off because of a hard path part (and has to come back after it). */
    private static boolean sprintTakenByDifficulty;

    private PathfinderTuning() {
    }

    /** Refreshed by the watchdog once per second. */
    static void updateStats(PlayerMovementStats fresh) {
        if (fresh != null) {
            stats = fresh;
        }
    }

    public static PlayerMovementStats stats() {
        return stats;
    }

    /**
     * @param forbidJumps true for bots that must never jump (lowballer), whatever
     *                    the Pathfinder tab says
     */
    public static void apply(baritone.api.Settings s, boolean forbidJumps) {
        PathfinderConfig config = PathfinderConfig.get();
        PlayerMovementStats current = stats;
        boolean testEnv = System.getenv("AIRCLIENT_ASTAR_TEST") != null;
        // Keep a higher value a bot set itself (e.g. a configured jump boost).
        s.jumpHeight.value = Math.max(s.jumpHeight.value, Math.max(1.0, current.jumpHeight()));
        // Step height >= 1 block: walking up a full block needs no jump.
        s.assumeStep.value = current.stepHeight() >= 1.0;
        // Hard part ahead (gap, 1-block corridor, jump after jump): walk it exactly - no sprint
        // (overshoots the small blocks), no yaw smoothing (follow every block centre).
        boolean hard = PathDifficulty.isHard();
        if (hard) {
            if (s.allowSprint.value) {
                s.allowSprint.value = false;
                sprintTakenByDifficulty = true;
            }
        } else if (sprintTakenByDifficulty) {
            s.allowSprint.value = true; // the hard part is behind us: give it back
            sprintTakenByDifficulty = false;
        }
        s.allowSprint.value = s.allowSprint.value && config.allowSprint;
        s.moveYawSmoothing.value = hard ? 0f : (float) (0.55 - 0.3 * MovementStyle.calm());
        // straight lines instead of block-to-block zig-zags - not where every block counts
        s.pathSmoothing.value = !hard;
        s.costHeuristic.value = config.costHeuristic;
        s.movementTimeoutTicks.value = config.movementTimeoutTicks;
        // Same human ceiling for Baritone's silent (server) rotation: the path recorder showed
        // up to 95 deg jumps in one tick, which is what looked like spinning / instant turns.
        s.maxYawChangePerTick.value = (float) Math.clamp(config.maxTurnPerTick,
                2.0, TurnProfile.HUMAN_MAX_DEG_PER_TICK);
        s.failureTimeoutMS.value = config.failureTimeoutMs;
        s.planAheadFailureTimeoutMS.value = Math.max(config.failureTimeoutMs, 4000L);
        // Keep a gap to edges / walls when there is room (patched Baritone, EdgeSafety).
        s.edgeSafetyCost.value = config.edgeSafetyCost;
        s.wallSafetyCost.value = config.wallSafetyCost;
        // Mob avoidance puts a cost sphere around every mob on each path calculation:
        // slow in mob-heavy SkyBlock areas and it even made the target mob expensive.
        s.avoidance.value = false;
        // Baritone caches chunks per server address + dimension, so all Hypixel
        // islands would share one cache and plan through blocks of another island.
        // Plan with the loaded chunks only.
        if (!testEnv) s.chunkCaching.value = false;
        // Path and target are drawn by PathOverlayRenderer (on the floor, following the target
        // smoothly); Baritone's goal box lagged behind the target and its path cut into the ground.
        s.renderPath.value = false;
        s.renderGoal.value = false;
        s.renderGoalAnimated.value = false;

        boolean jumps = config.allowJumps && !forbidJumps;
        boolean guarded = jumps && config.setbackGuard && SetbackGuard.active() && !testEnv;
        if (jumps) {
            // A jump costs what it really takes: with Jump Boost the player hangs in the air for
            // many more ticks, so stairs, slabs and walking around win over hopping on every block.
            s.jumpPenalty.value = config.jumpPenalty + current.extraAirTicks();
            // Set explicitly: AND-ing with the current value kept parkour off forever after
            // the first setback guard, so Baritone stopped at every gap ("no path").
            s.allowParkour.value = config.allowParkour && !guarded;
            s.allowParkourAscend.value = config.allowParkour && !guarded;
            s.sprintAscends.value = !guarded;
        } else {
            s.jumpPenalty.value = NO_JUMP;
            s.allowParkour.value = false;
            s.allowParkourAscend.value = false;
            s.allowDiagonalAscend.value = false;
            s.sprintAscends.value = false;
        }
        // long jumps (MovementLongJump): sprint speed on the ground from the real speed attribute
        // (vanilla walking 0.1 -> sprinting 0.28 blocks per tick)
        s.longJumpGroundSpeed.value = Math.clamp(current.speed() * 2.8, 0.2, 1.5);
        // Baritone prices walking with vanilla speed. At 400 % SkyBlock speed walking is 4x
        // cheaper than it thinks, so warps looked like a bargain: chains of 5-6 AOTV teleports
        // for a few blocks and etherwarps up and straight down again (Mines path logs). Warps
        // keep their real cost relative to walking.
        double speedFactor = Math.clamp(current.speedPercent() / 100.0, 1.0, 5.0);
        s.teleportMoveCost.value = 12.0 * speedFactor;
        s.etherwarpMoveCost.value = 26.0 * speedFactor;
        // evolved values (simulation job or config/baritone-genome.json) win
        com.coflnet.pathing.sim.Genome.applyTo(s, hard, current.extraAirTicks());
    }
}
