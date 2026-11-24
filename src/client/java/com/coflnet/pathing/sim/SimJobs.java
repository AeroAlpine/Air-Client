package com.coflnet.pathing.sim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;

/**
 * File protocol between tools/sim/evolve.py and one game instance (folder {@code <gameDir>/sim}):
 * <ul>
 *   <li>{@code job.json} (written by the runner): {@code {"id": "...", "genes": {...}, "runs": [[level, seed], ...]}}</li>
 *   <li>{@code result-<id>.jsonl}: one line per finished run</li>
 *   <li>{@code done-<id>}: the job is finished</li>
 *   <li>{@code heartbeat}: wall time, simulated ticks, runs - the runner restarts a silent instance</li>
 * </ul>
 */
public final class SimJobs {
    public static final boolean ENABLED = TurboClock.ENABLED;

    public record Run(int level, long seed) {
    }

    private static String jobId;
    /** Fixed effects of the job (amplifiers, -1 = none / level decides): same for every run. */
    private static volatile int profileSpeed = -1;
    private static volatile int profileJump = -1;

    public static int profileSpeed() {
        return ENABLED ? profileSpeed : -1;
    }

    public static int profileJump() {
        return ENABLED ? profileJump : -1;
    }
    private static String lastJobId;
    private static final Deque<Run> QUEUE = new ArrayDeque<>();
    private static Run current;
    private static BufferedWriter out;
    private static long lastPoll;
    private static long lastBeat;
    private static long runsDone;

    private SimJobs() {
    }

    private static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("sim");
    }

    /** The next run to do, or null (no job yet). Applies the job's genome when a job starts. */
    public static Run next() {
        if (jobId != null && QUEUE.isEmpty()) finishJob();
        if (jobId == null) {
            long now = System.currentTimeMillis();
            if (now - lastPoll < 250L) return null;
            lastPoll = now;
            if (!loadJob()) return null;
        }
        current = QUEUE.poll();
        return current;
    }

    private static boolean loadJob() {
        Path file = dir().resolve("job.json");
        if (!Files.isRegularFile(file)) return false;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            String id = o.get("id").getAsString();
            if (id.equals(lastJobId)) return false;
            Map<String, Double> genes = Genome.parse(o);
            JsonObject profile = o.has("profile") ? o.getAsJsonObject("profile") : null;
            profileSpeed = profile != null && profile.has("speed") ? profile.get("speed").getAsInt() : -1;
            profileJump = profile != null && profile.has("jump") ? profile.get("jump").getAsInt() : -1;
            Genome.set(genes);
            QUEUE.clear();
            JsonArray runs = o.getAsJsonArray("runs");
            for (JsonElement e : runs) {
                JsonArray r = e.getAsJsonArray();
                QUEUE.add(new Run(r.get(0).getAsInt(), r.get(1).getAsLong()));
            }
            jobId = id;
            lastJobId = id;
            out = Files.newBufferedWriter(dir().resolve("result-" + id + ".jsonl"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("[Sim] job " + id + ": " + QUEUE.size() + " runs, " + genes.size() + " genes");
            if (QUEUE.isEmpty()) finishJob();
            return jobId != null;
        } catch (IOException | RuntimeException e) {
            return false; // half written: next poll
        }
    }

    public static void report(int level, long seed, String result, int ticks, String status, double humanSeconds) {
        report(level, seed, result, ticks, status, humanSeconds, null);
    }

    /** {@code end}: where the player was when the run ended (the report clusters failures by it). */
    public static void report(int level, long seed, String result, int ticks, String status, double humanSeconds,
                              net.minecraft.world.phys.Vec3 end) {
        if (out == null) return;
        try {
            String pos = end == null ? "null"
                    : String.format(Locale.ROOT, "[%.1f,%.1f,%.1f]", end.x, end.y, end.z);
            out.write(String.format(Locale.ROOT,
                    "{\"level\":%d,\"seed\":%d,\"result\":\"%s\",\"ticks\":%d,\"human\":%s,\"pos\":%s,\"status\":\"%s\"}%n",
                    level, seed, result, ticks, Double.isNaN(humanSeconds) ? "null" : String.valueOf(humanSeconds), pos,
                    status.replace("\\", "/").replace("\"", "'")));
            out.flush();
        } catch (IOException ignored) {
        }
        runsDone++;
        current = null;
    }

    private static void finishJob() {
        try {
            if (out != null) out.close();
            Files.writeString(dir().resolve("done-" + jobId), String.valueOf(runsDone));
        } catch (IOException ignored) {
        }
        out = null;
        jobId = null;
    }

    /**
     * Once at the start: the Baritone values the bot really plans with (after PathfinderTuning),
     * so the evolution starts from today's settings.
     */
    public static void writeSettings() {
        try {
            baritone.api.Settings s = baritone.api.BaritoneAPI.getSettings();
            com.coflnet.pathing.PathEngine.applyWalkSettings(); // also runs PathfinderTuning
            JsonObject o = new JsonObject();
            for (baritone.api.Settings.Setting<?> setting : s.allSettings) {
                Object v = setting.value;
                if (v instanceof Boolean b) o.addProperty(setting.getName(), b);
                else if (v instanceof Number n) o.addProperty(setting.getName(), n.doubleValue());
            }
            Files.createDirectories(dir());
            Files.writeString(dir().resolve("settings.json"), o.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            System.out.println("[Sim] cannot write settings: " + e);
        }
    }

    private static final int KEEP_LOGS = 60;

    /**
     * A long evolution writes ~120 KB of logs per run (500k runs: 60 GB): the per-tick log of a run
     * that arrived is deleted right away, of the others and of the path recorder only the newest
     * {@value #KEEP_LOGS} stay (enough to look at recent failures).
     */
    public static void cleanLogs(Path runLog, boolean arrived) {
        try {
            if (arrived && runLog != null) Files.deleteIfExists(runLog);
            if (runsDone % 20 != 0) return;
            Path game = FabricLoader.getInstance().getGameDir();
            prune(game.resolve("astar-tests"), "auto-bot-");
            prune(game.resolve("logs/pathfinder"), "path-");
        } catch (IOException ignored) {
        }
    }

    private static void prune(Path dir, String prefix) throws IOException {
        if (!Files.isDirectory(dir)) return;
        java.util.List<Path> files;
        try (var s = Files.list(dir)) {
            files = s.filter(f -> f.getFileName().toString().startsWith(prefix))
                    .sorted(java.util.Comparator.comparing((Path f) -> {
                        try {
                            return Files.getLastModifiedTime(f).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).reversed())
                    .toList();
        }
        // path recorder writes a .jsonl and a -summary.txt per walk
        int keep = prefix.equals("path-") ? KEEP_LOGS * 2 : KEEP_LOGS;
        for (int i = keep; i < files.size(); i++) Files.deleteIfExists(files.get(i));
    }

    /** Every client tick: tells the runner this instance is alive. */
    public static void heartbeat() {
        long now = System.currentTimeMillis();
        if (now - lastBeat < 3_000L) return;
        lastBeat = now;
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve("heartbeat"), now + " " + TurboClock.turboTicks() + " " + runsDone
                    + " " + (jobId == null ? "-" : jobId) + " " + TurboClock.timeSplit());
        } catch (IOException ignored) {
        }
    }
}
