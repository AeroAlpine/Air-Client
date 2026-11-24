package com.coflnet.pathing;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommandType;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import pingplus.voicechat.client.FollowFeature;
import pingplus.voicechat.client.PlayerSettings;

import java.util.List;
import java.util.UUID;

public final class PlayerFollowGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> client.player != null && client.level != null && client.player.onGround());
            context.runOnClient(client -> verifyGoals(client.level, client.player));
            verifyCombatEntryPoint(context);
        }
        System.out.println("PASS: named player follow, cover detour, movement prediction, reacquisition and Baritone handoff");
    }

    private static void verifyGoals(ClientLevel level, LocalPlayer player) {
        PlayerFollowProcess follow = new PlayerFollowProcess();
        Vec3 start = Vec3.atBottomCenterOf(player.blockPosition());
        RemotePlayer primary = addPlayer(level, "RouteLeader", 1_999_980, start.add(2, 0, 0));
        RemotePlayer backup = addPlayer(level, "BackupLeader", 1_999_981, start.add(0, 0, 7));
        List<String> names = List.of("routeleader", "BackupLeader");
        try {
            check(!follow.update(level, player, List.of("Route"), 3, 500), "A partial username should not match");
            check(follow.update(level, player, names, 3, 1_000) && follow.target() == primary,
                    "The exact matching username in the first slot should win");
            check(!follow.isActive(), "A visible player inside the follow distance should not cause movement");

            BlockPos wall = player.blockPosition().offset(1, 0, 0);
            level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(wall.above(), Blocks.STONE.defaultBlockState(), 3);
            try {
                check(!player.hasLineOfSight(primary), "The test wall must hide the player");
                follow.update(level, player, names, 3, 1_050);
                var command = follow.onTick(false, true);
                check(!command.goal.isInGoal(player.blockPosition()), "A wall must not satisfy the follow goal");
                check(command.commandType == PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH,
                        "An obsolete route should be revalidated at a safe step");
                check(follow.onTick(false, false).commandType == PathingCommandType.SET_GOAL_AND_PATH,
                        "Updating the destination should let a jump finish");
            } finally {
                level.setBlock(wall, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(wall.above(), Blocks.AIR.defaultBlockState(), 3);
            }

            primary.setPos(start.add(13, 0, 0));
            follow.update(level, player, names, 3, 1_400);
            var far = follow.onTick(false, true).goal;
            primary.setPos(start.add(14, 0, 0));
            follow.update(level, player, names, 3, 1_450);
            check(follow.onTick(false, true).goal.equals(far), "One step should not immediately invalidate the far route");
            primary.setPos(start.add(18, 0, 0));
            follow.update(level, player, names, 3, 1_800);
            check(!follow.onTick(false, true).goal.equals(far), "A moved player should get a fresh Baritone goal");
            check(follow.onTick(true, true).goal == null && follow.status().equals("Waiting for a route"),
                    "A failed route should back off instead of immediately searching again");

            BlockPos lastKnown = primary.blockPosition();
            UUID id = primary.getUUID();
            primary.discard();
            follow.update(level, player, names, 3, 1_850);
            check(follow.target() == null && follow.targetName().equals("RouteLeader")
                            && follow.onTick(false, true).goal.equals(new GoalNear(lastKnown, 2)),
                    "A briefly unloaded player should be followed to their last known position");
            primary = addPlayer(level, new GameProfile(id, "RouteLeader"), 1_999_982, start.add(6, 0, 0));
            follow.update(level, player, List.of("BackupLeader", "RouteLeader"), 3, 2_000);
            check(follow.target() == primary, "The same UUID should be reacquired ahead of another configured player");
            primary.discard();
            follow.update(level, player, List.of("RouteLeader"), 3, 5_001);
            check(!follow.isActive() && follow.targetName().equals("RouteLeader"),
                    "After three seconds, stop moving and wait for the named player");
            check(!follow.update(level, player, List.of("DifferentName"), 3, 5_100) && !follow.isActive(),
                    "Removing a target must cancel its route and clear tracking");

            check(PlayerFollowProcess.lead(new Vec3(1, 0, 0), Vec3.ZERO, 100).equals(new Vec3(3.5, 0, 0)),
                    "The prediction must be capped at 2.5 blocks");
            check(PlayerFollowProcess.lead(new Vec3(10, 0, 0), Vec3.ZERO, 100).equals(new Vec3(10, 0, 0)),
                    "A position jump should not be extrapolated");
        } finally {
            follow.reset();
            primary.discard();
            backup.discard();
        }
    }

    private static void verifyCombatEntryPoint(ClientGameTestContext context) {
        boolean enabled = PlayerSettings.combatEnabled;
        boolean attack = PlayerSettings.followAttack;
        boolean oneHit = PlayerSettings.combatOneHit;
        String targets = PlayerSettings.combatTargets;
        int radius = PlayerSettings.followDistance;
        var leader = context.computeOnClient(client -> addPlayer(client.level, "BaritoneLeader", 1_999_983,
                Vec3.atBottomCenterOf(client.player.blockPosition()).add(12, 0, 0)));
        try {
            context.runOnClient(client -> {
                PlayerSettings.combatEnabled = true;
                PlayerSettings.followAttack = false;
                PlayerSettings.combatOneHit = false;
                PlayerSettings.combatTargets = "BaritoneLeader";
                PlayerSettings.followDistance = 3;
            });
            context.waitFor(client -> BaritoneAPI.getProvider().getPrimaryBaritone().getPathingControlManager()
                    .mostRecentInControl().orElse(null) instanceof PlayerFollowProcess);
            try {
                context.waitFor(client -> client.player.distanceTo(leader) <= 3.75
                        && FollowFeature.status().state().equals("Keeping distance"));
            } catch (AssertionError failure) {
                String state = context.computeOnClient(client -> {
                    var pathing = BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior();
                    return FollowFeature.status() + ", goal=" + pathing.getGoal() + ", hasPath=" + pathing.hasPath();
                });
                throw new AssertionError("Baritone did not finish following the named player: " + state, failure);
            }
        } finally {
            context.runOnClient(client -> {
                PlayerSettings.combatEnabled = enabled;
                PlayerSettings.followAttack = attack;
                PlayerSettings.combatOneHit = oneHit;
                PlayerSettings.combatTargets = targets;
                PlayerSettings.followDistance = radius;
                leader.discard();
                FollowFeature.tick(client);
            });
        }
    }

    private static RemotePlayer addPlayer(ClientLevel level, String name, int id, Vec3 position) {
        return addPlayer(level, new GameProfile(UUID.randomUUID(), name), id, position);
    }

    private static RemotePlayer addPlayer(ClientLevel level, GameProfile profile, int id, Vec3 position) {
        RemotePlayer player = new RemotePlayer(level, profile);
        player.setId(id);
        player.setPos(position);
        player.setNoGravity(true);
        level.addEntity(player);
        return player;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
