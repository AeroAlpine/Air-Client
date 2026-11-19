package pingplus.voicechat.client;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IFollowProcess;
import com.coflnet.pathing.EntityObstacles;
import com.coflnet.pathing.PlayerFollowProcess;
import com.coflnet.pathing.SkyblockTargets;
import com.coflnet.pathing.TargetSwitch;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Combat (formerly Follow): while {@link PlayerSettings#combatEnabled} (GUI toggle or hotkey),
 * follows a player or entity matching one of {@link PlayerSettings#combatTargets}.
 * Named players in follow-only mode use a moving Baritone goal and maintain
 * {@link PlayerSettings#followDistance} when the route is clear.
 *
 * <p>A name may be a player name, part of an entity's name ("Zealot", also SkyBlock mobs whose
 * name is on a nametag armor stand) or an entity type ("zombie", "minecraft:zombie"). With
 * {@link PlayerSettings#followAttack} the followed target is attacked through KillAura,
 * restricted to that one target; when leaves or other blocks hide it, the bot walks closer
 * until it can see it.
 */
public final class FollowFeature {
    private static final double MAX_DISTANCE = 96.0;
    private static final PlayerFollowProcess PLAYER_FOLLOW = new PlayerFollowProcess();
    private static Entity followed;
    private static Entity issued;
    private static boolean active;
    private static int searchCooldown;
    private static long lastSwitchAt;
    private static int switchCheckCooldown;
    private static UUID idleLookTargetId;
    /** Where the camera should look (set per tick, applied smoothly per frame). */
    private static boolean hasLook;
    private static float lookYaw;
    private static float lookPitch;
    private static long lastFrameNanos;
    private static net.minecraft.client.KeyMapping toggleKey;
    /** Ticks the attacked target was hidden / visible in a row, and whether we walk closer. */
    private static int hiddenTicks;
    private static int visibleTicks;
    private static boolean closeIn;
    /** Walking straight at a close mob without Baritone (see DirectChase). */
    private static boolean directChase;
    /** We hold the forward key ourselves during a direct chase. */
    private static boolean pressingForward;
    private static boolean pressingSprint;
    /** The wanted camera direction after its glide (see frame()). */
    private static float glideYaw;
    private static float glidePitch;
    private static boolean glideValid;
    /** The look follows the path (walking): the only time the view may wobble a little. */
    private static boolean walkingLook;
    private static final double wobblePhaseA = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
    private static final double wobblePhaseB = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
    private static long nextGlanceAt = System.currentTimeMillis() + 5_000L;
    private static long glanceFrom;
    private static long glanceUntil;
    private static float glanceYaw;
    /** Camera as this feature left it last frame: anything else moving it shows up as a difference. */
    private static float lastCameraYaw;
    private static float lastCameraPitch;
    private static final int HIDDEN_SWITCH_TICKS = 10;
    /** Ticks between searches while no target is around. */
    private static final int SEARCH_RETRY_TICKS = 4;
    /** Ticks between "is another mob clearly closer?" checks. */
    private static final int SWITCH_CHECK_TICKS = 2;
    /** After the last target is gone: how long the bot keeps looking around for the next one. */
    private static final long IDLE_LOOK_MS = 4_000L;
    private static net.minecraft.world.phys.Vec3 lastLostPos;
    private static long lastLostAt;
    private static long nextIdleLookAt;
    private static boolean idleLooking;
    /** Filtered look point while walking, and whether the camera is in the middle of a turn. */
    private static net.minecraft.world.phys.Vec3 smoothLook;
    /** The next targets, picked ahead: nearest to the current target, then nearest to that one. */
    private static final int QUEUE_SIZE = 2;
    private static final int QUEUE_REFRESH_TICKS = 10;
    private static final List<Entity> queue = new java.util.ArrayList<>();
    private static int queueCooldown;
    /** One-hit mode: mobs already clicked once, ignored until the time stored here. */
    private static final java.util.Map<UUID, Long> HIT_ONCE = new java.util.HashMap<>();
    /** Targets nothing can hit from any reachable spot: skipped for a while. */
    private static final java.util.Map<UUID, Long> UNREACHABLE = new java.util.HashMap<>();
    private static final long UNREACHABLE_MS = 20_000L;
    private static final long SEEK_GIVE_UP_MS = 8_000L;
    /** Walking to a spot with a free line to {@link #seekFor} (custom goal, not the follow process). */
    private static boolean seekingSpot;
    /** Walking towards a kill-map spot while no target is in sight (null = not roaming). */
    private static net.minecraft.world.phys.Vec3 roamTarget;
    private static long roamSince;
    private static final long ROAM_DELAY_MS = 2_500L;
    private static final long ROAM_REPICK_MS = 30_000L;
    private static Entity seekFor;
    private static long seekStartedAt;
    private static long seekGoalAt;
    /** Combat panel: targets finished (killed, or clicked once in one-hit mode) since start. */
    private static int done;
    private static long startedAt;
    private static final long HIT_ONCE_IGNORE_MS = 6_000L;

    /** Targets set by another bot (Commission Bot) that borrows Combat; null = not borrowed. */
    private static volatile List<String> borrowed;

    private FollowFeature() {
    }

    /**
     * Another bot uses Combat with its own targets, without touching the Combat settings or
     * toggle: always attacks, never one-hit. {@link #giveBack()} ends it.
     */
    public static void borrow(List<String> targets) {
        borrowed = List.copyOf(targets);
        followed = null;
        done = 0;
        startedAt = System.currentTimeMillis();
    }

    public static void giveBack() {
        if (borrowed == null) {
            return;
        }
        borrowed = null;
        release();
    }

    public static boolean isBorrowed() {
        return borrowed != null;
    }

    /** Kills counted since Combat was borrowed (or switched on). */
    public static int doneCount() {
        return done;
    }

    /** Walking to a spot for another bot (Commission Bot, miner); null = not walking. */
    private static volatile net.minecraft.core.BlockPos walkGoal;
    private static int walkRadius = 2;
    private static boolean walkWarps;
    /** The one path engine for walking (plan once, follow the tightened line, warps included). */
    private static final com.coflnet.pathing.PathEngine WALK = new com.coflnet.pathing.PathEngine();
    /** Combat's own engine: to a far mob and to the kill zone (no warps). */
    private static final com.coflnet.pathing.PathEngine CHASE = new com.coflnet.pathing.PathEngine();
    private static Entity chaseTarget;
    private static volatile com.coflnet.pathing.PathEngine.Result walkResult = com.coflnet.pathing.PathEngine.Result.IDLE;

    /** Walks to {@code goal} with the path engine (no warps). */
    public static void walkTo(net.minecraft.core.BlockPos goal, int radius) {
        walkTo(goal, radius, false);
    }

    /** Walks to {@code goal}; {@code warps} lets the plan use teleports and etherwarps. */
    public static void walkTo(net.minecraft.core.BlockPos goal, int radius, boolean warps) {
        if (goal == null) return;
        if (walkGoal == null || !walkGoal.equals(goal)) walkResult = com.coflnet.pathing.PathEngine.Result.PLANNING;
        walkGoal = goal;
        walkRadius = Math.max(0, radius);
        walkWarps = warps;
    }

    public static void stopWalking() {
        WALK.stop();
        if (walkGoal == null) {
            return;
        }
        walkGoal = null;
        walkResult = com.coflnet.pathing.PathEngine.Result.IDLE;
        active = false;
        hasLook = false;
        smoothLook = null;
        walkingLook = false;
    }

    public static boolean isWalking() {
        return walkGoal != null;
    }

    /** True while the walk is planned or followed (not arrived, not failed). */
    public static boolean walkPathing() {
        return walkGoal != null && (walkResult == com.coflnet.pathing.PathEngine.Result.PLANNING
                || walkResult == com.coflnet.pathing.PathEngine.Result.RUNNING);
    }

    public static com.coflnet.pathing.PathEngine.Result walkResult() {
        return walkResult;
    }

    public static String walkStatus() {
        return WALK.status();
    }

    private static void walkTick(Minecraft client, LocalPlayer player) {
        IBaritone baritone = primary();
        if (baritone != null && baritone.getCustomGoalProcess().isActive()) {
            // Baritone only plans now - it never walks a path itself
            baritone.getCustomGoalProcess().onLostControl();
            baritone.getPathingBehavior().cancelEverything();
        }
        net.minecraft.core.BlockPos goal = walkGoal;
        if (goal == null) return;
        WALK.goTo(goal, Math.max(1, walkRadius), walkWarps);
        walkResult = WALK.tick(client);
        active = true;
        idleLooking = false;
        KillAuraFeature.setForcedTarget(null);
        engineLook(player, WALK);
    }

    /** The camera follows the engine's point on the line (smoothly, like before). */
    private static boolean engineLook(LocalPlayer player, com.coflnet.pathing.PathEngine engine) {
        net.minecraft.world.phys.Vec3 look = engine.lookPoint();
        if (look == null) {
            if (engine == WALK) {
                hasLook = false; // e.g. while warping: no turn back to an old look afterwards
            }
            smoothLook = null;
            return false;
        }
        smoothLook = look;
        setLook(player, look);
        walkingLook = true;
        return true;
    }

    private static boolean attackOn() {
        return borrowed != null || PlayerSettings.followAttack || PlayerSettings.combatOneHit;
    }

    private static boolean oneHit() {
        return borrowed == null && PlayerSettings.combatOneHit;
    }

    public static void setToggleKey(net.minecraft.client.KeyMapping key) {
        toggleKey = key;
    }

    public static net.minecraft.client.KeyMapping toggleKey() {
        return toggleKey;
    }

    /** The target names, in the order they were added. */
    public static List<String> targets() {
        String raw = PlayerSettings.combatTargets == null ? "" : PlayerSettings.combatTargets;
        List<String> result = new java.util.ArrayList<>();
        for (String part : raw.split(";")) {
            String name = part.trim();
            if (!name.isEmpty() && result.stream().noneMatch(name::equalsIgnoreCase)) {
                result.add(name);
            }
        }
        return result;
    }

    public static boolean addTarget(String name) {
        String clean = name == null ? "" : name.replace(";", "").trim();
        List<String> list = targets();
        if (clean.isEmpty() || list.stream().anyMatch(clean::equalsIgnoreCase)) {
            return false;
        }
        list.add(clean);
        PlayerSettings.combatTargets = String.join(";", list);
        followed = null;
        return true;
    }

    public static boolean removeTarget(String name) {
        List<String> list = targets();
        boolean removed = list.removeIf(entry -> entry.equalsIgnoreCase(name));
        PlayerSettings.combatTargets = String.join(";", list);
        followed = null;
        return removed;
    }

    /** What the Combat panel shows. */
    public record Status(boolean on, String state, String target, double distance, List<String> next,
                         int done, long runningMs) {
    }

    public static Status status() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        boolean on = PlayerSettings.combatEnabled;
        long running = on && startedAt > 0 ? System.currentTimeMillis() - startedAt : 0;
        if (!on || player == null) {
            return new Status(false, "Off", "", 0, List.of(), done, running);
        }
        List<String> next = new java.util.ArrayList<>();
        for (Entity entity : queue) {
            if (entity != followed && !entity.isRemoved()) {
                next.add(name(entity) + "  " + Math.round(player.distanceTo(entity)) + "m");
            }
        }
        if (targets().isEmpty()) {
            return new Status(true, "No targets set", "", 0, next, done, running);
        }
        if (otherBotUsesBaritone()) {
            return new Status(true, "Paused (other bot)", "", 0, next, done, running);
        }
        if (!attackOn() && PLAYER_FOLLOW.targetName() != null) {
            Player target = PLAYER_FOLLOW.target();
            return new Status(true, PLAYER_FOLLOW.status(), PLAYER_FOLLOW.targetName(),
                    target == null ? -1 : player.distanceTo(target), List.of(), done, running);
        }
        if (followed == null) {
            return new Status(true, "Searching", "", 0, next, done, running);
        }
        double distance = player.distanceTo(followed);
        String state = PlayerSettings.followAttack || PlayerSettings.combatOneHit
                ? (distance <= PlayerSettings.followDistance + 1.5 ? "Fighting" : "Walking") : "Following";
        return new Status(true, state, name(followed), distance, next, done, running);
    }

    private static String name(Entity entity) {
        String shown = ChatFormatting.stripFormatting(entity.getName().getString());
        return shown == null || shown.isBlank() ? entity.getType().getDescription().getString() : shown;
    }

    /** GUI toggle and hotkey. */
    public static void toggle() {
        PlayerSettings.combatEnabled = !PlayerSettings.combatEnabled;
        PlayerSettings.save();
        if (PlayerSettings.combatEnabled) {
            done = 0;
            startedAt = System.currentTimeMillis();
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            String state = PlayerSettings.combatEnabled
                    ? (targets().isEmpty() ? "\u00a7aan \u00a77(keine Ziele eingetragen)"
                            : "\u00a7aan \u00a77- " + String.join(", ", targets()))
                    : "\u00a7caus";
            client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("\u00a7c[Combat] \u00a77" + state));
        }
        if (!PlayerSettings.combatEnabled) {
            release();
            com.coflnet.pathing.KillMap.clear(); // the kill map is per session
        }
    }

    public static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (com.coflnet.failsafe.Failsafe.holding()) {
            release(); // the fail-safe reacts: stand still, go on afterwards
            return;
        }
        if (walkGoal != null && borrowed == null && player != null && client.level != null) {
            stopPlayerFollow();
            walkTick(client, player);
            return;
        }
        List<String> names = borrowed != null ? borrowed : PlayerSettings.combatEnabled ? targets() : List.of();
        if (player == null || client.level == null || names.isEmpty() || otherBotUsesBaritone()) {
            release();
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (!attackOn() && followPlayer(client, player, names, nowMs)) return;
        stopPlayerFollow();

        HIT_ONCE.values().removeIf(until -> until <= nowMs);
        UNREACHABLE.values().removeIf(until -> until <= nowMs);
        if (oneHit() && followed != null && !(followed instanceof Player)
                && KillAuraFeature.lastAttackId() == followed.getId()
                && KillAuraFeature.lastAttackAt() >= lastSwitchAt) {
            // One-hit mode: clicked once, done. No waiting for damage or death: straight on.
            HIT_ONCE.put(followed.getUUID(), nowMs + HIT_ONCE_IGNORE_MS);
        }
        Entity lost = null;
        if (!valid(followed, player)) {
            lost = followed;
            followed = null;
            if (lost != null) {
                done++;
                if (KillAuraFeature.lastAttackId() == lost.getId()
                        && nowMs - KillAuraFeature.lastAttackAt() < 15_000L) {
                    // our kill (or our one hit): remember where, for when nothing is in sight
                    com.coflnet.pathing.KillMap.record(lost.position());
                }
            }
            Entity next = pollQueue(player);
            if (next != null) {
                // the next target was picked ahead of time: no search, no pause
                followed = next;
                searchCooldown = 0;
                idleLooking = false;
                lastSwitchAt = nowMs;
                switchCheckCooldown = SWITCH_CHECK_TICKS;
                queueCooldown = 0;
            }
        }
        if (followed == null) {
            // A target that just died / vanished: look for the next one right away. Only after
            // a search found nothing, retry every few ticks. (Before: a fixed 10 tick wait plus a
            // full Baritone stop after every kill, so the bot stood still for half a second.)
            if (lost == null && searchCooldown-- > 0) {
                // between two searches: keep walking to the kill zone if we already do
                if (roamTarget == null || !roam(client, player, nowMs)) {
                    idle(client, player);
                }
                return;
            }
            followed = findTarget(client, player, names);
            if (followed == null) {
                searchCooldown = SEARCH_RETRY_TICKS;
                if (lost != null) {
                    lastLostPos = lost.position();
                    lastLostAt = System.currentTimeMillis();
                }
                // nothing in sight: head for where the kills were densest (after a moment of
                // looking around, like a player who just finished a mob)
                if (nowMs - lastLostAt < ROAM_DELAY_MS || !roam(client, player, nowMs)) {
                    idle(client, player);
                }
                return;
            }
            stopRoam(primary());
            searchCooldown = 0;
            idleLooking = false;
            lastSwitchAt = System.currentTimeMillis();
            switchCheckCooldown = SWITCH_CHECK_TICKS;
            queueCooldown = 0;
        } else if (!(followed instanceof Player) && --switchCheckCooldown <= 0) {
            // mobs: move on to another one that is clearly closer (with hysteresis)
            switchCheckCooldown = SWITCH_CHECK_TICKS;
            Entity closer = findTarget(client, player, names);
            long now = System.currentTimeMillis();
            if (closer != null && closer != followed && TargetSwitch.shouldSwitch(
                    effectiveDistance(player, followed), effectiveDistance(player, closer), now - lastSwitchAt)) {
                followed = closer;
                lastSwitchAt = now;
                queueCooldown = 0;
            }
        }
        if (--queueCooldown <= 0) {
            queueCooldown = QUEUE_REFRESH_TICKS;
            rebuildQueue(client, player, names, followed);
        }
        IBaritone baritone = primary();
        if (baritone == null) {
            return;
        }
        Entity current = followed;
        // one-hit mode attacks by definition, also with "Attack target" off
        LivingEntity attack = attackOn() ? attackable(current) : null;
        // Leaves or a wall between us and the target: get closer until it is visible. Only after
        // it stayed hidden / visible for half a second and only on the ground: switching on every
        // flicker of the line of sight (each jump) re-planned the path constantly.
        // "Hidden" = no part of the hitbox can be hit from here without a block in between
        // (leaves and half slabs count: a hit does not go through them).
        if (attack != null && !com.coflnet.pathing.VisibleGoal.seesAny(client.level, player.getEyePosition(), current)) {
            hiddenTicks++;
            visibleTicks = 0;
        } else {
            visibleTicks++;
            hiddenTicks = 0;
        }
        if (player.onGround()) {
            if (!closeIn && hiddenTicks >= HIDDEN_SWITCH_TICKS) {
                closeIn = true;
            } else if (closeIn && visibleTicks >= HIDDEN_SWITCH_TICKS) {
                closeIn = false;
            }
        }
        configure(closeIn && attack != null);
        IFollowProcess follow = baritone.getFollowProcess();
        EntityObstacles.setTarget(current);
        // Hidden behind a wall, leaves or a slab: getting 3 blocks close is no use when no hit
        // gets through. Walk to a spot in reach with a free line - or give the target up.
        if (seekingSpot && (attack == null || visibleTicks >= HIDDEN_SWITCH_TICKS || seekFor != current)) {
            stopSeeking(baritone);
        }
        if (attack != null && (seekingSpot || (hiddenTicks >= HIDDEN_SWITCH_TICKS && player.onGround()))) {
            if (seekVisibleSpot(client, player, baritone, follow, current, attack, nowMs)) {
                return;
            }
        }
        // Last few blocks on open, level ground: walk straight at the mob. Re-planning a
        // path for every step the mob takes made the bot run circles in front of it.
        if (attack != null && com.coflnet.pathing.DirectChase.possible(client.level, player, current, directChase)) {
            if (!directChase) {
                directChase = true;
                if (issued != null) {
                    follow.cancel();
                }
                baritone.getPathingBehavior().cancelEverything();
                issued = null;
            }
            active = true;
            KillAuraFeature.setForcedTarget(entity -> entity == attack,
                    PlayerSettings.followCpsMin, PlayerSettings.followCpsMax);
            updateLook(client, player, baritone, current, true);
            steerStraight(client, player, current);
            return;
        }
        if (directChase) {
            endDirectChase(client);
        }
        if (player.distanceTo(current) > 9.0 && !seekingSpot) {
            // far away: one planned path to it (only the end is re-planned when it runs off)
            if (issued != null) {
                follow.cancel();
                issued = null;
            }
            if (chaseTarget != current) {
                CHASE.stop(); // another target: drop the old path right away
                chaseTarget = current;
            }
            CHASE.goTo(current.blockPosition(), 3, false);
            CHASE.tick(client);
            active = true;
            KillAuraFeature.setForcedTarget(attack == null ? null : entity -> entity == attack,
                    PlayerSettings.followCpsMin, PlayerSettings.followCpsMax);
            if (!engineLook(player, CHASE)) {
                updateLook(client, player, baritone, current, attack != null);
            }
            return;
        }
        if (CHASE.busy()) {
            CHASE.stop();
        }
        chaseTarget = null;
        if (issued != current || !follow.isActive()) {
            follow.follow(entity -> entity == current);
            issued = current;
        }
        active = true;

        KillAuraFeature.setForcedTarget(attack == null ? null : entity -> entity == attack,
                PlayerSettings.followCpsMin, PlayerSettings.followCpsMax);
        updateLook(client, player, baritone, current, attack != null);
    }

    private static boolean followPlayer(Minecraft client, LocalPlayer player, List<String> names, long now) {
        IBaritone baritone = primary();
        if (baritone == null) return false;

        boolean wasTracking = PLAYER_FOLLOW.targetName() != null;
        PLAYER_FOLLOW.attach(baritone);
        if (!PLAYER_FOLLOW.update(client.level, player, names, Math.clamp(PlayerSettings.followDistance, 1, 16), now)) {
            if (wasTracking) {
                followed = null;
                stopBaritone();
            }
            return false;
        }
        if (!wasTracking) {
            stopBaritone();
            CHASE.stop();
            chaseTarget = null;
            queue.clear();
            EntityObstacles.setQueue(null);
        }
        Player target = PLAYER_FOLLOW.target();
        if (target != followed) lastSwitchAt = now;
        followed = target;
        active = true;
        idleLooking = false;
        EntityObstacles.setTarget(target);
        KillAuraFeature.setForcedTarget(null);
        configure(false);
        updateLook(client, player, baritone, target, false);
        return true;
    }

    private static void stopPlayerFollow() {
        if (PLAYER_FOLLOW.targetName() != null) {
            PLAYER_FOLLOW.reset();
            followed = null;
            stopBaritone();
        }
    }

    /**
     * Walking: look where we go (a point a bit ahead on the path). Standing at
     * the target: look at it (or at nearby players). While KillAura has the
     * target in range it aims itself and this stays out of the way.
     */
    private static void updateLook(Minecraft client, LocalPlayer player, IBaritone baritone,
                                   Entity target, boolean attacking) {
        walkingLook = false;
        if (attacking && KillAuraFeature.hasTarget()) {
            hasLook = false;
            return;
        }
        // one-hit mode: on to the next mob quickly, zack zack
        long turnToNewTarget = oneHit() ? 250 : 450;
        if (target != null && System.currentTimeMillis() - lastSwitchAt < turnToNewTarget) {
            // Just picked a new target: turn towards it while the new path is planned, like a
            // player who spots the next mob, instead of following the old path's direction.
            smoothLook = null;
            setLook(player, new net.minecraft.world.phys.Vec3(target.getX(),
                    target instanceof LivingEntity living ? living.getEyeY() : target.getY(), target.getZ()));
            return;
        }
        net.minecraft.world.phys.Vec3 ahead = baritone.getPathingBehavior().isPathing()
                ? pathAhead(baritone, player) : null;
        if (ahead != null) {
            // Low-pass the look point: the camera follows the course of the path, not every
            // block of it (that was the left-right-left twitching on diagonal paths).
            smoothLook = smoothLook == null ? ahead : smoothLook.lerp(ahead, com.coflnet.pathing.MovementStyle.lookBlend());
            net.minecraft.world.phys.Vec3 look = smoothLook;
            if (target != null && player.hasLineOfSight(target)) {
                // a player keeps an eye on the mob he walks to: a fifth of the way towards it
                net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(target.getX(),
                        target instanceof LivingEntity living ? living.getEyeY() : target.getY(), target.getZ());
                look = look.lerp(at, 0.2);
            }
            setLook(player, look);
            keepNearWalkingDirection(baritone);
            walkingLook = true;
            return;
        }
        smoothLook = null;
        if (target == null) {
            hasLook = false;
            return;
        }
        if (!attacking && PlayerSettings.followLookAtNearbyPlayers && lookAtNearbyPlayer(client, player, target)) {
            return;
        }
        setLook(player, new net.minecraft.world.phys.Vec3(target.getX(),
                target instanceof LivingEntity living ? living.getEyeY() : target.getY(), target.getZ()));
    }

    /**
     * The camera looks ahead along the path, Baritone walks with its own (silent) rotation. The
     * path recorder showed both 125 degrees apart after a failed jump - the camera fought the
     * walking direction and every warp started with a big jump of the rotation the server
     * sees. So the camera stays within 90 degrees of where we really walk.
     */
    private static void keepNearWalkingDirection(IBaritone baritone) {
        if (!hasLook) {
            return;
        }
        float walkYaw = baritone.getPlayerContext().playerRotations().getYaw();
        float diff = net.minecraft.util.Mth.wrapDegrees(lookYaw - walkYaw);
        if (Math.abs(diff) > 90.0f) {
            lookYaw = walkYaw + Math.signum(diff) * 90.0f;
        }
    }

    private static void setLook(LocalPlayer player, net.minecraft.world.phys.Vec3 point) {
        double dx = point.x - player.getX();
        double dy = point.y - player.getEyeY();
        double dz = point.z - player.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 0.3 && Math.abs(dy) < 0.3) {
            return;
        }
        lookYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        lookPitch = Math.clamp((float) Math.toDegrees(-Math.atan2(dy, flat)), -60.0f, 60.0f);
        hasLook = true;
    }

    /**
     * A point a few blocks ahead along the path (how far: "Calm movement"), at eye height
     * (natural, level look).
     */
    private static net.minecraft.world.phys.Vec3 pathAhead(IBaritone baritone, LocalPlayer player) {
        try {
            baritone.api.pathing.path.IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor == null || executor.getPath() == null) {
                return null;
            }
            List<baritone.api.utils.BetterBlockPos> positions = executor.getPath().positions();
            net.minecraft.world.phys.Vec3 point = com.coflnet.pathing.MovementStyle.pointAlongPath(positions,
                    executor.getPosition() + 1, player.position(), com.coflnet.pathing.MovementStyle.lookAheadBlocks());
            if (point == null || Math.hypot(point.x - player.getX(), point.z - player.getZ()) < 1.0) {
                return null;
            }
            return new net.minecraft.world.phys.Vec3(point.x, Math.max(player.getEyeY() - 0.4, point.y + 1.4), point.z);
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /** Every rendered frame: ease the camera towards the wanted look. */
    public static void frame(Minecraft client) {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : Math.min(0.1, (now - lastFrameNanos) / 1e9);
        lastFrameNanos = now;
        LocalPlayer player = client.player;
        if ((!active && !idleLooking) || !hasLook || player == null || client.gui.screen() != null) {
            glideValid = false; // start the next glide from where the camera really is
            return;
        }
        float speed = (float) (Math.clamp(PlayerSettings.combatRotationSpeed, 30.0f, 1080.0f)
                * com.coflnet.pathing.MovementStyle.turnSpeedFactor());
        double rate = com.coflnet.pathing.MovementStyle.easeRate();
        // Two smooth stages instead of the old dead zone (wait until 5 degrees off, then turn
        // fast, then stop - that start/stop was the jerky camera): the wanted direction itself
        // glides (~0.25 s), and the camera eases continuously after it.
        boolean movedByOthers = glideValid
                && (Math.abs(net.minecraft.util.Mth.wrapDegrees(player.getYRot() - lastCameraYaw)) > 2.0f
                || Math.abs(player.getXRot() - lastCameraPitch) > 2.0f);
        if (!glideValid || movedByOthers) {
            // first frame, or a warp / KillAura turned the camera: glide on from there
            glideYaw = player.getYRot();
            glidePitch = player.getXRot();
            glideValid = true;
        }
        boolean quick = oneHit() && System.currentTimeMillis() - lastSwitchAt < 600;
        double glide = 1.0 - Math.exp(-dt / (quick ? 0.1 : 0.25));
        float[] wobble = wobble(quick);
        float wantYaw = lookYaw + wobble[0];
        float wantPitch = Math.clamp(lookPitch + wobble[1], -60.0f, 60.0f);
        glideYaw += (float) (net.minecraft.util.Mth.wrapDegrees(wantYaw - glideYaw) * glide);
        glidePitch += (float) ((wantPitch - glidePitch) * glide);
        if (quick) {
            speed *= 1.6f;
        }
        float yawDelta = Math.abs(net.minecraft.util.Mth.wrapDegrees(glideYaw - player.getYRot()));
        float pitchDelta = Math.abs(glidePitch - player.getXRot());
        if (Math.max(yawDelta, pitchDelta) >= 0.15f) { // below: close enough, no micro jitter
            player.setYRot(AimSmoothing.step(player.getYRot(), glideYaw, speed, dt, rate));
            player.setXRot(Math.clamp(AimSmoothing.step(player.getXRot(), glidePitch, speed, dt, rate), -90.0f, 90.0f));
            player.setYHeadRot(player.getYRot());
        }
        lastCameraYaw = player.getYRot();
        lastCameraPitch = player.getXRot();
    }

    /**
     * A little life in the view while walking: a slow drift of a few degrees (two overlaid
     * waves, never the same twice) and now and then a short glance to the side. Off on hard path
     * parts, right after a target switch and while fighting.
     */
    private static float[] wobble(boolean quick) {
        long nowMs = System.currentTimeMillis();
        double amount = com.coflnet.pathing.MovementStyle.lookAround();
        if (!walkingLook || quick || amount <= 0.0 || com.coflnet.pathing.PathDifficulty.isHard()
                || com.coflnet.pathing.AotvAssist.isBusy()) {
            glanceUntil = 0;
            return new float[]{0, 0};
        }
        double t = System.nanoTime() / 1e9;
        float yaw = (float) (amount * (2.6 * Math.sin(t * 0.83 + wobblePhaseA) + 1.3 * Math.sin(t * 2.1 + wobblePhaseB)));
        float pitch = (float) (amount * 1.5 * Math.sin(t * 0.61 + wobblePhaseB));
        if (glanceUntil == 0 && nowMs >= nextGlanceAt) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            glanceFrom = nowMs;
            glanceUntil = nowMs + random.nextLong(550, 850);
            glanceYaw = (float) ((random.nextBoolean() ? 1 : -1) * random.nextDouble(10.0, 18.0) * amount);
            // fewer glances the lower the setting (100 %: every 4-9 s)
            nextGlanceAt = glanceUntil + (long) (random.nextLong(4_000, 9_000) / Math.max(0.2, amount));
        }
        if (glanceUntil != 0) {
            if (nowMs >= glanceUntil) {
                glanceUntil = 0;
            } else {
                double progress = (nowMs - glanceFrom) / (double) (glanceUntil - glanceFrom);
                yaw += glanceYaw * (float) Math.sin(Math.PI * progress);
            }
        }
        return new float[]{yaw, pitch};
    }

    private static double effectiveDistance(LocalPlayer player, Entity entity) {
        return TargetSwitch.effectiveDistance(player.distanceTo(entity), player.hasLineOfSight(entity));
    }

    /**
     * While attacking, player names come first in list order. Otherwise the nearest eligible
     * entity whose name, nametag or type matches any of the names is selected. A SkyBlock nametag stand resolves to the mob under it; a stand
     * without a living mob (the mob just died) is skipped, not followed.
     */
    static Entity findTarget(Minecraft client, LocalPlayer player, List<String> names) {
        boolean attacking = attackOn();
        if (attacking) {
            for (String name : names) {
                for (Player other : client.level.players()) {
                    if (other != player && !other.isSpectator() && valid(other, player)
                            && other.getGameProfile().name().equalsIgnoreCase(name)) {
                        return other;
                    }
                }
            }
        }
        List<String> wanted = names.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();
        Entity best = null;
        double bestDistance = MAX_DISTANCE * MAX_DISTANCE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == player || !entity.isAlive() || entity.isRemoved()
                    || (entity instanceof Player other && (!attacking || other.isSpectator()))) {
                continue;
            }
            if (wanted.stream().noneMatch(w -> nameMatches(entity, w))) {
                continue;
            }
            Entity target = SkyblockTargets.resolve(client.level, entity);
            if (target == null || target == player || SkyblockTargets.isGone(client.level, target)
                    || hitOnce(target)) {
                continue;
            }
            double distance = player.distanceToSqr(target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = target;
            }
        }
        return best;
    }

    static boolean nameMatches(Entity entity, String wanted) {
        String shown = ChatFormatting.stripFormatting(entity.getName().getString());
        if (shown != null && shown.toLowerCase(Locale.ROOT).contains(wanted)) {
            return true;
        }
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
        return type.equals(wanted) || ("minecraft:" + type).equals(wanted);
    }

    private static LivingEntity attackable(Entity entity) {
        return entity instanceof LivingEntity living && !(entity instanceof ArmorStand) ? living : null;
    }

    private static boolean valid(Entity entity, LocalPlayer player) {
        if (entity == null || player.distanceToSqr(entity) >= MAX_DISTANCE * MAX_DISTANCE) {
            return false;
        }
        if (entity instanceof Player) {
            return entity.isAlive() && !entity.isRemoved() && entity.level() == player.level();
        }
        // mobs: also gone when dying or their nametag shows 0 health, or (one-hit) already clicked
        return !SkyblockTargets.isGone(player.level(), entity) && !hitOnce(entity);
    }

    /** The lowballer and the mob pathfinder steer Baritone themselves. */
    private static boolean otherBotUsesBaritone() {
        if (com.coflnet.lowballing.LowballingMod.isRunning()) {
            return true;
        }
        com.coflnet.ahflipper.AhAutomationConfig mob = com.coflnet.ahflipper.AhAutomationConfig.get();
        return mob.enabled && !mob.targetName.isBlank();
    }

    private static void configure(boolean hidden) {
        baritone.api.Settings s = BaritoneAPI.getSettings();
        // no random offsets on the rotation either: the view follows the path, nothing else
        s.randomLooking.value = 0.0;
        s.randomLooking113.value = 0.0;
        s.allowBreak.value = false;
        s.allowPlace.value = false;
        s.allowInventory.value = false;
        s.antiCheatCompatibility.value = true;
        s.followRadius.value = hidden ? 1 : Math.clamp(PlayerSettings.followDistance, 1, 16);
        s.followOffsetDistance.value = 0.0;
        // The camera is steered here (walking direction) or by KillAura, never by Baritone.
        s.freeLook.value = true;
        com.coflnet.pathing.PathfinderTuning.apply(s, false);
    }

    /** "Look at nearby players" while the followed player stands next to us. */
    private static boolean lookAtNearbyPlayer(Minecraft client, LocalPlayer player, Entity target) {
        if (!(target instanceof Player) || player.distanceToSqr(target) > Math.pow(PlayerSettings.followDistance + 1.5, 2)) {
            idleLookTargetId = null;
            return false;
        }
        List<Player> nearby = client.level.players().stream()
                .filter(p -> p != player && p != target && !p.isSpectator() && p.distanceToSqr(player) <= 49.0)
                .map(p -> (Player) p)
                .toList();
        if (nearby.isEmpty()) {
            idleLookTargetId = null;
            return false;
        }
        Player look = nearby.stream().filter(p -> p.getUUID().equals(idleLookTargetId)).findFirst()
                .orElseGet(() -> nearby.get(ThreadLocalRandom.current().nextInt(nearby.size())));
        idleLookTargetId = look.getUUID();
        setLook(player, new net.minecraft.world.phys.Vec3(look.getX(), look.getEyeY(), look.getZ()));
        return true;
    }

    private static IBaritone primary() {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Forward while the mob is out of reach and roughly in front of us; the camera turns to it. */
    private static void steerStraight(Minecraft client, LocalPlayer player, Entity target) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double reach = Math.min(Math.clamp(PlayerSettings.followDistance, 1, 16), PlayerSettings.killAuraRange);
        double stopAt = Math.max(1.5, reach - 0.5);
        float yawTo = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float off = Math.abs(net.minecraft.util.Mth.wrapDegrees(yawTo - player.getYRot()));
        double distance = Math.sqrt(dx * dx + dz * dz);
        boolean forward = distance > stopAt && off < 45.0f && client.gui.screen() == null;
        if (forward) {
            // every tick: opening a menu releases all keys
            client.options.keyUp.setDown(true);
        } else if (pressingForward) {
            client.options.keyUp.setDown(false);
        }
        pressingForward = forward;
        // sprint the last metres too (it only walked before); stop sprinting right before
        // the mob so we do not run past it
        boolean sprint = forward && distance > stopAt + 1.5 && off < 30.0f;
        if (sprint) {
            client.options.keySprint.setDown(true);
        } else if (pressingSprint) {
            client.options.keySprint.setDown(false);
            player.setSprinting(false);
        }
        pressingSprint = sprint;
    }

    private static void endDirectChase(Minecraft client) {
        directChase = false;
        releaseForward(client);
    }

    private static void releaseForward(Minecraft client) {
        if (pressingForward) {
            client.options.keyUp.setDown(false);
            pressingForward = false;
        }
        if (pressingSprint) {
            client.options.keySprint.setDown(false);
            pressingSprint = false;
        }
    }

    private static void stopBaritone() {
        if (seekingSpot) {
            stopSeeking(primary());
        }
        stopRoam(primary());
        if (directChase) {
            endDirectChase(Minecraft.getInstance());
        }
        if (!active) {
            return;
        }
        IBaritone baritone = primary();
        if (baritone != null && issued != null) {
            baritone.getFollowProcess().cancel();
        }
        issued = null;
        active = false;
        hasLook = false;
        KillAuraFeature.setForcedTarget(null);
        EntityObstacles.setTarget(null);
        EntityObstacles.setQueue(null);
    }

    /**
     * No target in range. Baritone stops, but for a few seconds after the last target died the
     * camera keeps glancing around (a few calm turns left / right) as if looking for the next
     * mob, instead of freezing on the spot.
     */
    private static void idle(Minecraft client, LocalPlayer player) {
        stopBaritone();
        long now = System.currentTimeMillis();
        if (lastLostPos == null || now - lastLostAt > IDLE_LOOK_MS || client.gui.screen() != null) {
            idleLooking = false;
            hasLook = false;
            return;
        }
        if (com.coflnet.pathing.MovementStyle.lookAround() <= 0.0) {
            idleLooking = false; // "Look around" off: no glancing for the next mob either
            hasLook = false;
            return;
        }
        if (!idleLooking || now >= nextIdleLookAt) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double glance = com.coflnet.pathing.MovementStyle.idleGlanceDegrees();
            lookYaw = player.getYRot() + (float) random.nextDouble(-glance, glance);
            lookPitch = (float) random.nextDouble(-4.0, 14.0);
            nextIdleLookAt = now + random.nextLong(com.coflnet.pathing.MovementStyle.idleGlanceMinMs(),
                    com.coflnet.pathing.MovementStyle.idleGlanceMaxMs());
            idleLooking = true;
        }
        hasLook = true;
    }

    private static boolean hitOnce(Entity entity) {
        long now = System.currentTimeMillis();
        Long until = HIT_ONCE.get(entity.getUUID());
        if (until != null && until > now) {
            return true;
        }
        Long blocked = UNREACHABLE.get(entity.getUUID());
        return blocked != null && blocked > now;
    }

    /**
     * The target can not be hit from where we are: path to a standable spot in reach that has a
     * free line to its hitbox (re-planned every 1.5 s as it moves). No such spot, or not there
     * after {@value #SEEK_GIVE_UP_MS} ms: the target counts as unreachable for a while and the
     * next one is taken. Returns true when this tick is handled.
     */
    private static boolean seekVisibleSpot(Minecraft client, LocalPlayer player, IBaritone baritone,
                                           IFollowProcess follow, Entity current, LivingEntity attack, long nowMs) {
        if (!seekingSpot || seekFor != current || nowMs - seekGoalAt > 1_500L) {
            double reach = Math.min(Math.clamp(PlayerSettings.followDistance, 1, 16), PlayerSettings.killAuraRange);
            baritone.api.pathing.goals.Goal goal = com.coflnet.pathing.VisibleGoal.of(client.level, current, 2, reach);
            if (!(goal instanceof com.coflnet.pathing.VisibleGoal)) {
                giveUp(baritone, current, "kein Platz mit freier Schlaglinie");
                return true;
            }
            if (!seekingSpot || seekFor != current) {
                seekStartedAt = nowMs;
                System.out.println("[Combat] Ziel verdeckt - laufe zu einem Platz mit freier Schlaglinie");
            }
            if (issued != null) {
                follow.cancel();
                issued = null;
            }
            if (directChase) {
                endDirectChase(client);
            }
            baritone.getCustomGoalProcess().setGoalAndPath(goal);
            seekingSpot = true;
            seekFor = current;
            seekGoalAt = nowMs;
        }
        if (nowMs - seekStartedAt > SEEK_GIVE_UP_MS) {
            giveUp(baritone, current, "Platz mit freier Schlaglinie nicht erreicht");
            return true;
        }
        active = true;
        KillAuraFeature.setForcedTarget(entity -> entity == attack,
                PlayerSettings.followCpsMin, PlayerSettings.followCpsMax);
        updateLook(client, player, baritone, current, true);
        return true;
    }

    /**
     * No target in sight: walk towards a spot picked from the session's kill map (denser = far
     * likelier), re-picked when reached or after half a minute. False without any kills.
     */
    private static boolean roam(Minecraft client, LocalPlayer player, long nowMs) {
        if (com.coflnet.pathing.KillMap.size() == 0) {
            return false;
        }
        IBaritone baritone = primary();
        if (baritone == null) {
            return false;
        }
        boolean reached = roamTarget != null
                && Math.hypot(player.getX() - roamTarget.x, player.getZ() - roamTarget.z) < 3.0;
        if (roamTarget == null || reached || nowMs - roamSince > ROAM_REPICK_MS
                || !baritone.getCustomGoalProcess().isActive()) {
            net.minecraft.world.phys.Vec3 pick = com.coflnet.pathing.KillMap.pick(player.position(), 8.0,
                    () -> ThreadLocalRandom.current().nextDouble());
            if (pick == null) {
                return false;
            }
            if (issued != null) {
                baritone.getFollowProcess().cancel();
                issued = null;
            }
            if (directChase) {
                endDirectChase(client);
            }
            if (roamTarget == null) {
                System.out.println("[Combat] Kein Ziel in Sicht - laufe zur Kill-Zone ("
                        + com.coflnet.pathing.KillMap.size() + " Kills gemerkt)");
            }
            roamTarget = pick;
            roamSince = nowMs;
        }
        CHASE.goTo(net.minecraft.core.BlockPos.containing(roamTarget), 2, false);
        CHASE.tick(client);
        active = true;
        idleLooking = false;
        KillAuraFeature.setForcedTarget(null);
        if (engineLook(player, CHASE)) {
            return true;
        }
        net.minecraft.world.phys.Vec3 ahead = baritone.getPathingBehavior().isPathing()
                ? pathAhead(baritone, player) : null;
        if (ahead != null) {
            smoothLook = smoothLook == null ? ahead : smoothLook.lerp(ahead, com.coflnet.pathing.MovementStyle.lookBlend());
            setLook(player, smoothLook);
            keepNearWalkingDirection(baritone);
            walkingLook = true;
        }
        return true;
    }

    private static void stopRoam(IBaritone baritone) {
        if (roamTarget != null) CHASE.stop();
        if (roamTarget != null && baritone != null && !seekingSpot) {
            baritone.getCustomGoalProcess().onLostControl();
        }
        roamTarget = null;
    }

    private static void giveUp(IBaritone baritone, Entity target, String why) {
        UNREACHABLE.put(target.getUUID(), System.currentTimeMillis() + UNREACHABLE_MS);
        System.out.println("[Combat] Ziel aufgegeben (" + why + "): " + name(target));
        stopSeeking(baritone);
        if (followed == target) {
            followed = null;
            issued = null;
        }
    }

    private static void stopSeeking(IBaritone baritone) {
        if (seekingSpot && baritone != null) {
            baritone.getCustomGoalProcess().onLostControl();
        }
        seekingSpot = false;
        seekFor = null;
    }

    /** First queued target that is still a valid target, or null. */
    private static Entity pollQueue(LocalPlayer player) {
        while (!queue.isEmpty()) {
            Entity next = queue.remove(0);
            if (valid(next, player)) {
                EntityObstacles.setQueue(queue);
                return next;
            }
        }
        EntityObstacles.setQueue(queue);
        return null;
    }

    /**
     * Picks the next {@value #QUEUE_SIZE} targets ahead: the mob nearest to the current target
     * (where we will be standing when it is done), then the mob nearest to that one. When the
     * current target is done the bot walks on at once instead of searching first.
     */
    private static void rebuildQueue(Minecraft client, LocalPlayer player, List<String> names, Entity current) {
        queue.clear();
        if (current == null || current instanceof Player) {
            EntityObstacles.setQueue(queue);
            return;
        }
        List<String> wanted = names.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();
        List<Entity> pool = new java.util.ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == player || !entity.isAlive() || entity.isRemoved()
                    || wanted.stream().noneMatch(w -> nameMatches(entity, w))) {
                continue;
            }
            Entity mob = SkyblockTargets.resolve(client.level, entity);
            if (mob == null || mob == player || mob == current || mob instanceof Player || pool.contains(mob)
                    || player.distanceToSqr(mob) >= MAX_DISTANCE * MAX_DISTANCE || !valid(mob, player)) {
                continue;
            }
            pool.add(mob);
        }
        net.minecraft.world.phys.Vec3 from = current.position();
        while (queue.size() < QUEUE_SIZE && !pool.isEmpty()) {
            Entity best = null;
            double bestDistance = Double.MAX_VALUE;
            for (Entity candidate : pool) {
                double distance = candidate.position().distanceToSqr(from);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
            queue.add(best);
            pool.remove(best);
            from = best.position();
        }
        EntityObstacles.setQueue(queue);
    }

    private static void release() {
        PLAYER_FOLLOW.reset();
        CHASE.stop();
        WALK.releaseKeys(Minecraft.getInstance());
        queue.clear();
        EntityObstacles.setQueue(null);
        stopBaritone();
        followed = null;
        idleLooking = false;
        lastLostPos = null;
        searchCooldown = 0;
        idleLookTargetId = null;
        lastFrameNanos = 0;
    }
}
