package com.coflnet.pathing;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Text log for the PathEngine (the planner/follower used by Commissions and Combat). Baritone's
 * own runs go to {@link PathRecorder}; the engine never walks through Baritone, so without this
 * its runs would leave no trace at all. One file per planned run under
 * {@code logs/pathfinder/engine}.
 *
 * <p>Every sample shows what the engine wanted and what really happened: waypoint, offset from
 * the line, heading error, required and allowed turn rate, look-ahead, keys and speed. Events
 * mark replans, warp aiming, skipped points and waits for the camera.
 */
public final class EngineRecorder {
    private static final int KEEP_FILES = 120;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Map<Integer, Session> SESSIONS = new HashMap<>();
    private static boolean disabled;
    private static int opens;

    private static final class Session {
        final BufferedWriter out;
        final Path file;
        final long startMs = System.currentTimeMillis();
        int plans;
        int ticks;
        int events;
        int offTicks;
        int turnWaits;
        double maxHeadingError;

        Session(BufferedWriter out, Path file) {
            this.out = out;
            this.file = file;
        }
    }

    private EngineRecorder() {
    }

    public static Path folder() {
        return FabricLoader.getInstance().getGameDir().resolve("logs").resolve("pathfinder").resolve("engine");
    }

    /** A new plan (fresh goal or replanned): closes the engine's previous run file. */
    public static synchronized void begin(int id, String engine, String goal, int points,
                                          String plan, String turns) {
        if (disabled) {
            return;
        }
        end(id, "neuer Plan");
        try {
            Path dir = folder();
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(STAMP);
            Path file = dir.resolve("engine-" + stamp + "-" + id + ".log");
            BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            Session s = new Session(out, file);
            s.plans = 1;
            SESSIONS.put(id, s);
            write(s, "# PathEngine " + engine + " #" + id + "  " + LocalDateTime.now());
            write(s, "# goal: " + goal);
            write(s, String.format(Locale.ROOT, "# plan: %d points | %s", points, plan));
            write(s, "# " + turns);
            if (++opens % 20 == 1) {
                prune();
            }
        } catch (Exception e) {
            disabled = true;
            System.out.println("[Pathfinder] Engine-Log kann nicht schreiben: " + e);
        }
    }

    /** A newly extended plan: same run, new points. */
    public static synchronized void replan(int id, String plan, String turns) {
        Session s = SESSIONS.get(id);
        if (s == null) {
            return;
        }
        try {
            s.plans++;
            write(s, "# replan " + s.plans + ": " + plan);
            write(s, "# " + turns);
        } catch (Exception e) {
            fail(e);
        }
    }

    /** One follow sample; the caller already throttles this to a few per second. */
    public static synchronized void sample(int id, String line, double headingError,
                                           boolean offPath, boolean waitingForTurn) {
        Session s = SESSIONS.get(id);
        if (s == null) {
            return;
        }
        try {
            s.ticks++;
            s.maxHeadingError = Math.max(s.maxHeadingError, Math.abs(headingError));
            if (offPath) {
                s.offTicks++;
            }
            if (waitingForTurn) {
                s.turnWaits++;
            }
            write(s, line);
        } catch (Exception e) {
            fail(e);
        }
    }

    public static synchronized void event(int id, String text) {
        Session s = SESSIONS.get(id);
        if (s == null) {
            return;
        }
        try {
            s.events++;
            write(s, "EVENT " + text);
        } catch (Exception e) {
            fail(e);
        }
    }

    /** Ends the current run file (stopped, arrived, failed or a new plan). */
    public static synchronized void end(int id, String why) {
        Session s = SESSIONS.remove(id);
        if (s == null) {
            return;
        }
        try {
            long ms = System.currentTimeMillis() - s.startMs;
            write(s, String.format(Locale.ROOT,
                    "# end: %s | %.1fs | plans %d | samples %d | events %d | off-path %d | turn-waits %d | max heading error %.0f deg",
                    why, ms / 1000.0, s.plans, s.ticks, s.events, s.offTicks, s.turnWaits,
                    s.maxHeadingError));
            s.out.close();
        } catch (Exception e) {
            fail(e);
        }
    }

    private static void write(Session s, String line) throws java.io.IOException {
        s.out.write(line);
        s.out.newLine();
        s.out.flush();
    }

    private static void fail(Exception e) {
        System.out.println("[Pathfinder] Engine-Log-Fehler: " + e);
    }

    /** Keeps the newest {@value #KEEP_FILES} engine files. */
    private static void prune() {
        try (Stream<Path> files = Files.list(folder())) {
            var list = files.filter(p -> p.getFileName().toString().startsWith("engine-"))
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .toList();
            for (int i = 0; i < list.size() - KEEP_FILES; i++) {
                Files.deleteIfExists(list.get(i));
            }
        } catch (Exception ignored) {
        }
    }
}
