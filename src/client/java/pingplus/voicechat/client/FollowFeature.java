package pingplus.voicechat.client;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IFollowProcess;
import com.coflnet.pathing.EntityObstacles;
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
 * follows the nearest player or entity matching one of {@link PlayerSettings#combatTargets}
 * with Baritone's follow process (real pathing, keeps {@link PlayerSettings#followDistance}
 * blocks distance).
 *
 * <p>A name may be a player name, part of an entity's name ("Zealot", also SkyBlock mobs whose
 * name is on a nametag armor stand) or an entity type ("zombie", "minecraft:zombie"). With
 * {@link PlayerSettings#followAttack} the followed target is attacked through KillAura,
 * restricted to that one target; when leaves or other blocks hide it, the bot walks closer
 * until it can see it.
 */
public final class FollowFeature {
    private static final double MAX_DISTANCE = 96.0;
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
    private static final int HIDDEN_SWITCH_TICKS = 10;

    private FollowFeature() {
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

    /** GUI toggle and hotkey. */
    public static void toggle() {
        PlayerSettings.combatEnabled = !PlayerSettings.combatEnabled;
        PlayerSettings.save();
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
        }
    }

    public static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        List<String> names = PlayerSettings.combatEnabled ? targets() : List.of();
        if (player == null || client.level == null || names.isEmpty() || otherBotUsesBaritone()) {
            release();
            return;
        }
        if (!valid(followed, player)) {
            followed = null;
            if (searchCooldown-- > 0) {
                stopBaritone(); // dead / gone target: never keep walking after it
                return;
            }
            searchCooldown = 10;
            followed = findTarget(client, player, names);
            if (followed == null) {
                stopBaritone();
                return;
            }
            lastSwitchAt = System.currentTimeMillis();
        } else if (!(followed instanceof Player) && --switchCheckCooldown <= 0) {
            // mobs: move on to another one that is clearly closer (with hysteresis)
            switchCheckCooldown = 20;
            Entity closer = findTarget(client, player, names);
            long now = System.currentTimeMillis();
            if (closer != null && closer != followed && TargetSwitch.shouldSwitch(
                    effectiveDistance(player, followed), effectiveDistance(player, closer), now - lastSwitchAt)) {
                followed = closer;
                lastSwitchAt = now;
            }
        }
        IBaritone baritone = primary();
        if (baritone == null) {
            return;
        }
        Entity current = followed;
        LivingEntity attack = PlayerSettings.followAttack ? attackable(current) : null;
        // Leaves or a wall between us and the target: get closer until it is visible. Only after
        // it stayed hidden / visible for half a second and only on the ground: switching on every
        // flicker of the line of sight (each jump) re-planned the path constantly.
        if (attack != null && !player.hasLineOfSight(current)) {
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
        if (issued != current || !follow.isActive()) {
            follow.follow(entity -> entity == current);
            issued = current;
        }
        active = true;

        KillAuraFeature.setForcedTarget(attack == null ? null : entity -> entity == attack,
                PlayerSettings.followCpsMin, PlayerSettings.followCpsMax);
        updateLook(client, player, baritone, current, attack != null);
    }

    /**
     * Walking: look where we go (a point a bit ahead on the path). Standing at
     * the target: look at it (or at nearby players). While KillAura has the
     * target in range it aims itself and this stays out of the way.
     */
    private static void updateLook(Minecraft client, LocalPlayer player, IBaritone baritone,
                                   Entity target, boolean attacking) {
        if (attacking && KillAuraFeature.hasTarget()) {
            hasLook = false;
            return;
        }
        net.minecraft.world.phys.Vec3 ahead = baritone.getPathingBehavior().isPathing()
                ? pathAhead(baritone, player) : null;
        if (ahead != null) {
            setLook(player, ahead);
            return;
        }
        if (!attacking && PlayerSettings.followLookAtNearbyPlayers && lookAtNearbyPlayer(client, player, target)) {
            return;
        }
        setLook(player, new net.minecraft.world.phys.Vec3(target.getX(),
                target instanceof LivingEntity living ? living.getEyeY() : target.getY(), target.getZ()));
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

    /** First path point at least ~1.5 blocks away, at eye height (natural, level look). */
    private static net.minecraft.world.phys.Vec3 pathAhead(IBaritone baritone, LocalPlayer player) {
        try {
            baritone.api.pathing.path.IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor == null || executor.getPath() == null) {
                return null;
            }
            List<baritone.api.utils.BetterBlockPos> positions = executor.getPath().positions();
            for (int i = Math.max(0, executor.getPosition() + 1); i < positions.size(); i++) {
                baritone.api.utils.BetterBlockPos pos = positions.get(i);
                double dx = pos.getX() + 0.5 - player.getX();
                double dz = pos.getZ() + 0.5 - player.getZ();
                if (dx * dx + dz * dz > 2.25) {
                    return new net.minecraft.world.phys.Vec3(pos.getX() + 0.5,
                            Math.max(player.getEyeY() - 0.4, pos.getY() + 1.4), pos.getZ() + 0.5);
                }
            }
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
        if (!active || !hasLook || player == null || client.gui.screen() != null) {
            return;
        }
        float speed = Math.clamp(PlayerSettings.combatRotationSpeed, 30.0f, 1080.0f);
        player.setYRot(AimSmoothing.step(player.getYRot(), lookYaw, speed, dt));
        player.setXRot(Math.clamp(AimSmoothing.step(player.getXRot(), lookPitch, speed, dt), -90.0f, 90.0f));
        player.setYHeadRot(player.getYRot());
    }

    private static double effectiveDistance(LocalPlayer player, Entity entity) {
        return TargetSwitch.effectiveDistance(player.distanceTo(entity), player.hasLineOfSight(entity));
    }

    /**
     * Player names first (in list order), then the nearest entity whose name / nametag / type
     * matches any of the names. A SkyBlock nametag stand resolves to the mob under it; a stand
     * without a living mob (the mob just died) is skipped, not followed.
     */
    static Entity findTarget(Minecraft client, LocalPlayer player, List<String> names) {
        for (String name : names) {
            for (Player other : client.level.players()) {
                if (other != player && !other.isSpectator()
                        && other.getGameProfile().name().equalsIgnoreCase(name)) {
                    return other;
                }
            }
        }
        List<String> wanted = names.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();
        Entity best = null;
        double bestDistance = MAX_DISTANCE * MAX_DISTANCE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == player || !entity.isAlive() || entity.isRemoved()) {
                continue;
            }
            if (wanted.stream().noneMatch(w -> nameMatches(entity, w))) {
                continue;
            }
            Entity target = SkyblockTargets.resolve(client.level, entity);
            if (target == null || target == player || SkyblockTargets.isGone(client.level, target)) {
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
        // mobs: also gone when dying or their nametag shows 0 health
        return !SkyblockTargets.isGone(player.level(), entity);
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

    private static void stopBaritone() {
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
    }

    private static void release() {
        stopBaritone();
        followed = null;
        searchCooldown = 0;
        idleLookTargetId = null;
        lastFrameNanos = 0;
    }
}
