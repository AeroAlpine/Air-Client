package com.coflnet.pathing;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.BetterBlockPos;
import com.coflnet.mixin.LocalPlayerRotationAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Flight recorder for the pathfinder: while Baritone walks, every client tick is written next
 * to what the path planned (current movement, source, destination, planned cost), together with
 * what the player really did (position, speed, ground, collisions, keys, camera and the rotation
 * sent to the server). Anomalies are marked as events: server setbacks, the rotation sent to the
 * server jumping, standing still while walking, leaving the path, movements taking far longer than
 * planned, long jumps. Each run ends with a summary of where and in which movement things went
 * wrong.
 * <p>
 * Files: {@code logs/pathfinder/path-<time>.jsonl} (one JSON object per line: start, path, tick,
 * move, jump, event, end) and {@code path-<time>-summary.txt}. The newest {@value #KEEP_RUNS}
 * runs are kept.
 */
public final class PathRecorder {
    private static final int KEEP_RUNS = 40;
    /** A run ends this many ticks after Baritone stopped (landing / setbacks after the stop count). */
    private static final int END_AFTER_IDLE_TICKS = 40;
    /** Shorter runs are not worth a file. */
    private static final int MIN_TICKS = 20;
    private static final double SNAP_DEGREES = 45.0;
    private static final int STALL_TICKS = 6;
    private static final double STALL_SPEED = 0.03;
    private static final double OFF_PATH_DISTANCE = 1.2;
    private static final int OFF_PATH_TICKS = 5;
    private static final double TELEPORT_DISTANCE = 12.0;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static Run run;
    private static volatile String lastSummary = "";
    private static volatile Path lastSummaryFile;

    private PathRecorder() {
    }

    public static Path folder() {
        return FabricLoader.getInstance().getGameDir().resolve("logs").resolve("pathfinder");
    }

    /** One line about the last finished run, for the GUI. */
    public static String lastSummary() {
        return lastSummary;
    }

    public static Path lastSummaryFile() {
        return lastSummaryFile;
    }

    public static boolean isRecording() {
        return run != null;
    }

    /** Client thread, end of every client tick. */
    static void tick(Minecraft client, IBaritone baritone, long now) {
        if (!PathfinderConfig.get().recordPaths || client.player == null || client.level == null) {
            end("aufgehört");
            return;
        }
        boolean pathing = false;
        try {
            pathing = baritone != null && baritone.getPathingBehavior().isPathing();
        } catch (RuntimeException ignored) {
        }
        if (pathing) {
            if (run == null) {
                run = Run.start(client, baritone, now);
                if (run == null) {
                    return;
                }
            }
            run.idleTicks = 0;
        } else if (run == null) {
            return;
        } else if (++run.idleTicks >= END_AFTER_IDLE_TICKS) {
            end(run.stopReason == null ? "Pfad beendet" : run.stopReason);
            return;
        }
        try {
            run.sample(client, pathing ? baritone : null, now);
        } catch (RuntimeException e) {
            System.out.println("[Pathfinder] Recorder-Fehler: " + e);
            end("Fehler");
        }
    }

    /** A fault learned or skipped by the watchdog. */
    static void onFault(String text) {
        if (run != null) {
            run.event("FAULT", run.lastX, run.lastY, run.lastZ, text);
        }
    }

    /** Baritone's path was cancelled by the watchdog / a bot. */
    static void onCancel(String why) {
        if (run != null) {
            run.stopReason = why;
        }
    }

    /** Client thread, after the server moved the own player (setback or warp). */
    public static void onServerTeleport(Minecraft client, double fromX, double fromY, double fromZ) {
        if (run == null || client.player == null) {
            return;
        }
        run.teleport(client.player, fromX, fromY, fromZ);
    }

    private static void end(String reason) {
        Run current = run;
        run = null;
        if (current != null) {
            current.finish(reason);
        }
    }

    // ------------------------------------------------------------------ one run

    private static final class Run {
        final BufferedWriter out;
        final Path file;
        final Path summaryFile;
        final long startMs;
        final String bot;
        int tick;
        int idleTicks;
        String stopReason;

        IPath lastPath;
        int paths;
        IMovement move;
        int moveStartTick;
        double moveCost;

        float lastServerYaw = Float.NaN;
        int stallTicks;
        int offPathTicks;
        boolean lastGround = true;
        boolean airborne;
        int jumpStartTick;
        double jumpStartY;
        double lastGroundX;
        double lastGroundY;
        double lastGroundZ;
        int lastJumpTick = -1000;
        int lastLandTick = -1000;
        double lastX;
        double lastY;
        double lastZ;
        double maxSpeed;
        double distance;

        final Map<String, Integer> eventCounts = new TreeMap<>();
        /** Per movement type: count, ticks, planned cost, setbacks, slow. */
        final Map<String, double[]> moveStats = new TreeMap<>();
        /** Events per block, for the hotspot list. */
        final Map<String, Integer> hotspots = new HashMap<>();
        final Map<String, String> hotspotKinds = new HashMap<>();
        final List<String> notable = new ArrayList<>();

        private Run(BufferedWriter out, Path file, Path summaryFile, long startMs, String bot) {
            this.out = out;
            this.file = file;
            this.summaryFile = summaryFile;
            this.startMs = startMs;
            this.bot = bot;
        }

        static Run start(Minecraft client, IBaritone baritone, long now) {
            try {
                Path dir = folder();
                Files.createDirectories(dir);
                String stamp = LocalDateTime.now().format(FILE_TIME);
                Path file = dir.resolve("path-" + stamp + ".jsonl");
                Path summary = dir.resolve("path-" + stamp + "-summary.txt");
                BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
                Run run = new Run(out, file, summary, now, activeBot());
                run.writeStart(client, baritone);
                return run;
            } catch (IOException e) {
                System.out.println("[Pathfinder] Recorder kann nicht schreiben: " + e);
                return null;
            }
        }

        private void writeStart(Minecraft client, IBaritone baritone) {
            Settings s = BaritoneAPI.getSettings();
            PlayerMovementStats stats = PathfinderTuning.stats();
            Goal goal = baritone.getPathingBehavior().getGoal();
            LocalPlayer p = client.player;
            Json j = new Json("start")
                    .str("time", LocalDateTime.now().toString())
                    .str("bot", bot)
                    .str("world", PathfinderSystem.worldKey(client))
                    .str("goal", String.valueOf(goal))
                    .pos("pos", p.getX(), p.getY(), p.getZ())
                    .num("jumpPenalty", s.jumpPenalty.value)
                    .bool("allowParkour", s.allowParkour.value)
                    .bool("allowParkourAscend", s.allowParkourAscend.value)
                    .bool("allowSprint", s.allowSprint.value)
                    .bool("sprintAscends", s.sprintAscends.value)
                    .bool("freeLook", s.freeLook.value)
                    .bool("smoothLook", s.smoothLook.value)
                    .bool("assumeStep", s.assumeStep.value)
                    .num("jumpHeight", s.jumpHeight.value)
                    .num("movementTimeout", s.movementTimeoutTicks.value)
                    .str("stats", stats.summary());
            write(j);
        }

        void sample(Minecraft client, IBaritone baritone, long now) {
            LocalPlayer p = client.player;
            tick++;
            IPathExecutor executor = null;
            IPath path = null;
            int position = -1;
            if (baritone != null) {
                executor = baritone.getPathingBehavior().getCurrent();
                if (executor != null) {
                    path = executor.getPath();
                    position = executor.getPosition();
                }
            }
            if (path != null && path != lastPath) {
                if (lastPath != null) {
                    boolean reachedEnd = move != null && lastPath.movements().indexOf(move) >= lastPath.movements().size() - 1;
                    if (!reachedEnd && !path.getSrc().equals(lastPath.getDest())) {
                        event("REPLAN", p.getX(), p.getY(), p.getZ(), "neuer Pfad mitten im alten");
                    }
                }
                writePath(path);
                lastPath = path;
            }
            IMovement current = null;
            if (path != null && position >= 0 && position < path.movements().size()) {
                current = path.movements().get(position);
            }
            if (current != move) {
                finishMove(p);
                move = current;
                moveStartTick = tick;
                moveCost = safeCost(current);
            }

            Vec3 v = p.getDeltaMovement();
            double horizontal = Math.sqrt(v.x * v.x + v.z * v.z);
            maxSpeed = Math.max(maxSpeed, horizontal);
            if (tick > 1) {
                double dx = p.getX() - lastX;
                double dz = p.getZ() - lastZ;
                double step = Math.sqrt(dx * dx + dz * dz);
                if (step < TELEPORT_DISTANCE) {
                    distance += step;
                }
            }
            LocalPlayerRotationAccessor rotations = (LocalPlayerRotationAccessor) p;
            float serverYaw = rotations.cofl$getYRotLast();
            float serverPitch = rotations.cofl$getXRotLast();
            float yawStep = Float.isNaN(lastServerYaw) ? 0 : Mth.wrapDegrees(serverYaw - lastServerYaw);
            lastServerYaw = serverYaw;
            Input keys = p.input == null ? Input.EMPTY : p.input.keyPresses;
            boolean ground = p.onGround();
            String moveName = moveName(move);

            if (Math.abs(yawStep) > SNAP_DEGREES) {
                event("SNAP", p.getX(), p.getY(), p.getZ(),
                        String.format(Locale.ROOT, "Server-Yaw springt %.0f Grad in einem Tick", yawStep));
            }
            if (keys.forward() && ground && horizontal < STALL_SPEED && move != null) {
                if (++stallTicks == STALL_TICKS) {
                    event("STALL", p.getX(), p.getY(), p.getZ(), "vorwärts gedrückt, steht aber"
                            + (p.horizontalCollision ? " (läuft gegen Block)" : ""));
                }
            } else {
                stallTicks = 0;
            }
            double offPath = move == null ? 0 : distanceToSegment(p, move);
            if (offPath > OFF_PATH_DISTANCE) {
                if (++offPathTicks == OFF_PATH_TICKS) {
                    event("OFF_PATH", p.getX(), p.getY(), p.getZ(),
                            String.format(Locale.ROOT, "%.2f Blöcke neben dem Pfad", offPath));
                }
            } else {
                offPathTicks = 0;
            }
            trackJump(p, ground, v);

            BlockPos feet = p.blockPosition();
            Json j = new Json("tick")
                    .num("tick", tick)
                    .num("ms", now - startMs)
                    .pos("pos", p.getX(), p.getY(), p.getZ())
                    .pos("vel", v.x, v.y, v.z)
                    .num("yaw", Mth.wrapDegrees(p.getYRot()))
                    .num("pitch", p.getXRot())
                    .num("syaw", Mth.wrapDegrees(serverYaw))
                    .num("spitch", serverPitch)
                    .num("dyaw", yawStep)
                    .bool("ground", ground)
                    .bool("hcol", p.horizontalCollision)
                    .bool("vcol", p.verticalCollision)
                    .bool("sprint", p.isSprinting())
                    .str("keys", keyString(keys))
                    .str("move", moveName)
                    .num("idx", position)
                    .num("mt", tick - moveStartTick)
                    .num("off", offPath)
                    .str("feet", blockId(client, feet))
                    .str("below", blockId(client, feet.below()))
                    .num("tps", LagMonitor.tps());
            if (move != null) {
                j.blockPos("src", move.getSrc()).blockPos("dest", move.getDest());
            }
            write(j);
            lastX = p.getX();
            lastY = p.getY();
            lastZ = p.getZ();
        }

        private void trackJump(LocalPlayer p, boolean ground, Vec3 v) {
            if (ground) {
                lastGroundX = p.getX();
                lastGroundY = p.getY();
                lastGroundZ = p.getZ();
            }
            if (lastGround && !ground && v.y > 0.2 && !p.isInWater() && !p.onClimbable()) {
                airborne = true;
                jumpStartTick = tick;
                jumpStartY = p.getY();
                lastJumpTick = tick;
            } else if (airborne && ground) {
                airborne = false;
                lastLandTick = tick;
                int air = tick - jumpStartTick;
                double gain = p.getY() - jumpStartY;
                String moveName = moveName(move);
                write(new Json("jump").num("tick", tick).num("air", air).num("gain", gain)
                        .str("move", moveName).pos("pos", p.getX(), p.getY(), p.getZ()));
                if (air > 14) {
                    event("JUMP_LONG", p.getX(), p.getY(), p.getZ(),
                            air + " Ticks in der Luft (normal ~12), Höhe " + fmt(gain));
                }
                if (moveName.contains("Ascend") && gain < 0.5) {
                    event("JUMP_FAILED", p.getX(), p.getY(), p.getZ(),
                            "Sprung bei " + moveName + " ohne Höhengewinn (" + fmt(gain) + ")");
                }
            }
            lastGround = ground;
        }

        void teleport(LocalPlayer p, double fromX, double fromY, double fromZ) {
            double dx = p.getX() - fromX;
            double dy = p.getY() - fromY;
            double dz = p.getZ() - fromZ;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > TELEPORT_DISTANCE) {
                event("TELEPORT", p.getX(), p.getY(), p.getZ(), "Warp/Teleport über " + fmt(dist) + " Blöcke");
                return;
            }
            String detail = String.format(Locale.ROOT,
                    "zurückgesetzt um %.2f (dy %.2f) von %.2f,%.2f,%.2f%s, %d Ticks nach Sprung, %d nach Landung",
                    dist, dy, fromX, fromY, fromZ, airborne ? " in der Luft" : "",
                    tick - lastJumpTick, tick - lastLandTick);
            event("SETBACK", fromX, fromY, fromZ, detail);
            if (move != null) {
                stat(moveName(move))[3]++;
            }
        }

        private void writePath(IPath path) {
            paths++;
            StringBuilder positions = new StringBuilder("[");
            List<BetterBlockPos> list = path.positions();
            for (int i = 0; i < list.size(); i++) {
                BetterBlockPos pos = list.get(i);
                if (i > 0) {
                    positions.append(',');
                }
                positions.append('[').append(pos.x).append(',').append(pos.y).append(',').append(pos.z).append(']');
            }
            positions.append(']');
            StringBuilder moves = new StringBuilder("[");
            List<IMovement> movements = path.movements();
            double cost = 0;
            for (int i = 0; i < movements.size(); i++) {
                if (i > 0) {
                    moves.append(',');
                }
                moves.append('"').append(moveName(movements.get(i))).append('"');
                cost += safeCost(movements.get(i));
            }
            moves.append(']');
            write(new Json("path").num("tick", tick).num("n", paths).str("goal", String.valueOf(path.getGoal()))
                    .num("cost", cost).raw("positions", positions.toString()).raw("moves", moves.toString()));
        }

        private void finishMove(LocalPlayer p) {
            if (move == null) {
                return;
            }
            int ticks = tick - moveStartTick;
            String name = moveName(move);
            double[] s = stat(name);
            s[0]++;
            s[1] += ticks;
            s[2] += moveCost;
            write(new Json("move").num("tick", tick).str("move", name).blockPos("src", move.getSrc())
                    .blockPos("dest", move.getDest()).num("cost", moveCost).num("ticks", ticks));
            if (moveCost > 0 && moveCost < 1_000_000 && ticks > moveCost * 1.8 + 8) {
                s[4]++;
                event("SLOW_MOVE", p.getX(), p.getY(), p.getZ(), String.format(Locale.ROOT,
                        "%s %s -> %s brauchte %d Ticks, geplant %.1f", name, shortPos(move.getSrc()),
                        shortPos(move.getDest()), ticks, moveCost));
            }
        }

        double[] stat(String name) {
            return moveStats.computeIfAbsent(name, k -> new double[5]);
        }

        void event(String kind, double x, double y, double z, String detail) {
            eventCounts.merge(kind, 1, Integer::sum);
            String block = Mth.floor(x) + "," + Mth.floor(y) + "," + Mth.floor(z);
            if (!kind.equals("TELEPORT")) {
                hotspots.merge(block, 1, Integer::sum);
                hotspotKinds.merge(block, kind, (a, b) -> a.contains(b) ? a : a + "+" + b);
            }
            String moveName = moveName(move);
            write(new Json("event").num("tick", tick).str("kind", kind).pos("pos", x, y, z)
                    .str("move", moveName).str("detail", detail));
            if (notable.size() < 60) {
                notable.add(String.format(Locale.ROOT, "t%-5d %-11s %-22s %s  %s", tick, kind, block,
                        moveName.isEmpty() ? "-" : moveName, detail));
            }
        }

        void finish(String reason) {
            if (move != null && lastPath != null) {
                LocalPlayer p = Minecraft.getInstance().player;
                if (p != null) {
                    finishMove(p);
                }
            }
            double seconds = (System.currentTimeMillis() - startMs) / 1000.0;
            Json end = new Json("end").num("tick", tick).num("seconds", seconds).str("reason", reason)
                    .num("paths", paths).num("distance", distance);
            for (Map.Entry<String, Integer> e : eventCounts.entrySet()) {
                end.num(e.getKey(), e.getValue());
            }
            write(end);
            try {
                out.close();
            } catch (IOException ignored) {
            }
            if (tick < MIN_TICKS && eventCounts.isEmpty()) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
                return;
            }
            String text = summaryText(seconds, reason);
            try {
                Files.writeString(summaryFile, text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.out.println("[Pathfinder] Zusammenfassung nicht geschrieben: " + e);
            }
            String line = shortLine(seconds);
            lastSummary = line;
            lastSummaryFile = summaryFile;
            System.out.println("[Pathfinder] Pfad-Log: " + line + " -> " + file.getFileName());
            if (PathfinderConfig.get().recorderChat) {
                com.coflnet.ahflipper.AhFlipperMod.message("§8[Pfad-Log] §7" + line);
            }
            prune();
        }

        private String shortLine(double seconds) {
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%.0fs, %.0f Blöcke", seconds, distance));
            for (String kind : List.of("SETBACK", "STALL", "SNAP", "SLOW_MOVE", "JUMP_FAILED", "OFF_PATH", "REPLAN")) {
                Integer n = eventCounts.get(kind);
                if (n != null) {
                    sb.append(", ").append(n).append(' ').append(kind);
                }
            }
            return sb.toString();
        }

        private String summaryText(double seconds, String reason) {
            StringBuilder sb = new StringBuilder();
            sb.append("Pfad-Log ").append(file.getFileName()).append('\n');
            sb.append(String.format(Locale.ROOT, "Bot: %s  Dauer: %.1fs  Ticks: %d  Strecke: %.1f Blöcke  max. %.2f B/Tick  Pfade: %d  Ende: %s%n",
                    bot, seconds, tick, distance, maxSpeed, paths, reason));
            sb.append("\nEreignisse:\n");
            if (eventCounts.isEmpty()) {
                sb.append("  keine\n");
            }
            Map<String, String> meaning = new LinkedHashMap<>();
            meaning.put("SETBACK", "Server hat den Spieler zurückgesetzt (Anticheat / Lag)");
            meaning.put("SNAP", "Rotation an den Server sprang > 45 Grad in einem Tick");
            meaning.put("STALL", "Vorwärts gedrückt, aber kaum Bewegung");
            meaning.put("OFF_PATH", "Mehr als 1.2 Blöcke neben der geplanten Bewegung");
            meaning.put("SLOW_MOVE", "Bewegung dauerte viel länger als geplant");
            meaning.put("JUMP_LONG", "Sprung länger als normal in der Luft");
            meaning.put("JUMP_FAILED", "Sprung ohne Höhengewinn");
            meaning.put("REPLAN", "Neuer Pfad mitten im alten");
            meaning.put("FAULT", "Watchdog hat einen Fehler erkannt");
            meaning.put("TELEPORT", "Warp / großer Teleport");
            for (Map.Entry<String, Integer> e : eventCounts.entrySet()) {
                sb.append(String.format(Locale.ROOT, "  %-11s %4d  %s%n", e.getKey(), e.getValue(),
                        meaning.getOrDefault(e.getKey(), "")));
            }
            sb.append("\nBewegungen (Anzahl, Ticks Ø, geplant Ø, Verhältnis, Setbacks, zu langsam):\n");
            for (Map.Entry<String, double[]> e : moveStats.entrySet()) {
                double[] s = e.getValue();
                double avgTicks = s[0] == 0 ? 0 : s[1] / s[0];
                double avgCost = s[0] == 0 ? 0 : s[2] / s[0];
                sb.append(String.format(Locale.ROOT, "  %-22s %4.0f  %6.1f  %6.1f  x%.2f  %3.0f  %3.0f%n", e.getKey(),
                        s[0], avgTicks, avgCost, avgCost > 0 ? avgTicks / avgCost : 0, s[3], s[4]));
            }
            sb.append("\nHotspots (Block: Ereignisse):\n");
            hotspots.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                    .limit(10)
                    .forEach(e -> sb.append(String.format(Locale.ROOT, "  %-22s %3d  %s%n", e.getKey(), e.getValue(),
                            hotspotKinds.get(e.getKey()))));
            sb.append("\nVerlauf (erste 60 Ereignisse):\n");
            for (String line : notable) {
                sb.append("  ").append(line).append('\n');
            }
            sb.append("\nDetails pro Tick: ").append(file.getFileName()).append('\n');
            return sb.toString();
        }

        private void write(Json json) {
            try {
                out.write(json.toString());
                out.write('\n');
            } catch (IOException e) {
                System.out.println("[Pathfinder] Recorder-Schreibfehler: " + e);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void prune() {
        try (Stream<Path> files = Files.list(folder())) {
            List<Path> logs = files.filter(f -> f.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing(f -> f.getFileName().toString()))
                    .toList();
            for (int i = 0; i < logs.size() - KEEP_RUNS; i++) {
                Path log = logs.get(i);
                Files.deleteIfExists(log);
                String name = log.getFileName().toString();
                Files.deleteIfExists(log.resolveSibling(name.replace(".jsonl", "-summary.txt")));
            }
        } catch (IOException ignored) {
        }
    }

    private static String activeBot() {
        try {
            if (com.coflnet.lowballing.LowballingMod.isRunning()) {
                return "lowballer";
            }
            com.coflnet.ahflipper.AhAutomationConfig mob = com.coflnet.ahflipper.AhAutomationConfig.get();
            if (mob.enabled && !mob.targetName.isBlank()) {
                return "mob-pathfinder";
            }
            if (pingplus.voicechat.client.PlayerSettings.combatEnabled) {
                return "combat";
            }
        } catch (RuntimeException ignored) {
        }
        return "baritone";
    }

    private static double safeCost(IMovement movement) {
        if (movement == null) {
            return 0;
        }
        try {
            return movement.getCost();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String moveName(IMovement movement) {
        if (movement == null) {
            return "";
        }
        String name = movement.getClass().getSimpleName();
        return name.startsWith("Movement") ? name.substring("Movement".length()) : name;
    }

    /** Horizontal distance from the player to the line between the movement's start and end. */
    private static double distanceToSegment(LocalPlayer p, IMovement movement) {
        double ax = movement.getSrc().x + 0.5;
        double az = movement.getSrc().z + 0.5;
        double bx = movement.getDest().x + 0.5;
        double bz = movement.getDest().z + 0.5;
        double px = p.getX();
        double pz = p.getZ();
        double abx = bx - ax;
        double abz = bz - az;
        double len = abx * abx + abz * abz;
        double t = len == 0 ? 0 : Math.clamp(((px - ax) * abx + (pz - az) * abz) / len, 0.0, 1.0);
        double cx = ax + abx * t;
        double cz = az + abz * t;
        return Math.sqrt((px - cx) * (px - cx) + (pz - cz) * (pz - cz));
    }

    private static String keyString(Input keys) {
        StringBuilder sb = new StringBuilder();
        if (keys.forward()) sb.append('W');
        if (keys.left()) sb.append('A');
        if (keys.backward()) sb.append('S');
        if (keys.right()) sb.append('D');
        if (keys.jump()) sb.append('J');
        if (keys.shift()) sb.append('N');
        if (keys.sprint()) sb.append('R');
        return sb.toString();
    }

    private static String blockId(Minecraft client, BlockPos pos) {
        String id = BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock()).getPath();
        return id.equals("air") ? "" : id;
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** Tiny JSON object builder (one line). */
    private static final class Json {
        private final StringBuilder sb = new StringBuilder("{");

        Json(String type) {
            sb.append("\"t\":\"").append(type).append('"');
        }

        Json num(String key, double value) {
            sb.append(",\"").append(key).append("\":");
            if (value == Math.rint(value) && Math.abs(value) < 1e12) {
                sb.append((long) value);
            } else {
                sb.append(String.format(Locale.ROOT, "%.3f", value));
            }
            return this;
        }

        Json bool(String key, boolean value) {
            sb.append(",\"").append(key).append("\":").append(value);
            return this;
        }

        Json str(String key, String value) {
            sb.append(",\"").append(key).append("\":\"");
            String text = value == null ? "" : value;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '"' || c == '\\') {
                    sb.append('\\').append(c);
                } else if (c < 0x20) {
                    sb.append(' ');
                } else {
                    sb.append(c);
                }
            }
            sb.append('"');
            return this;
        }

        Json pos(String key, double x, double y, double z) {
            sb.append(",\"").append(key).append("\":[")
                    .append(String.format(Locale.ROOT, "%.3f,%.3f,%.3f", x, y, z)).append(']');
            return this;
        }

        Json blockPos(String key, BlockPos pos) {
            sb.append(",\"").append(key).append("\":[").append(pos.getX()).append(',').append(pos.getY())
                    .append(',').append(pos.getZ()).append(']');
            return this;
        }

        Json raw(String key, String json) {
            sb.append(",\"").append(key).append("\":").append(json);
            return this;
        }

        @Override
        public String toString() {
            return sb + "}";
        }
    }
}
