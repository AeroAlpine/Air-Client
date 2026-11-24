package com.coflnet.pathing.astar;

import com.coflnet.pathing.PathEngine;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * Deterministic client test mode. Movement is the real product path: FollowFeature drives the
 * Baritone walker. /airtest commands build courses and log every tick; with AIRCLIENT_ASTAR_TEST
 * set, the bot runs all seeds x levels automatically and writes a summary for the analyzer.
 */
public final class AStarTestHarness {
    private enum State { IDLE, BUILD_WAIT, RUNNING, HUMAN, DONE }

    private static State state = State.IDLE;
    private static BlockPos base;
    private static BlockPos target;
    private static int wait;
    private static int ticks;
    private static long startedAt;
    private static boolean auto;
    private static boolean autoRun;
    private static boolean refreshed;
    private static int autoSeed;
    private static int autoLevel;
    private static long autoAt;
    private static BufferedWriter out;
    private static BufferedWriter summary;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /**
     * Only level 5 matters; 6 and 7 are the recorded player levels in this world; 8 and 9 are
     * rebuilt 1:1 from recordings (8: kill every recorded wolf, 9: reach a random recorded spot).
     */
    private static final int FIRST_AUTO_LEVEL = 5;
    private static final int LAST_AUTO_LEVEL = 11;
    /** Seed and level of the run in progress (auto batch or /airtest bot). */
    private static long runSeed;
    private static int runLevel;
    /** Below this the bot fell off the level. */
    private static double fallY = 95.0;
    private static long timeoutMs = 90_000L;
    /** Player's time for the same task, NaN if there is none to compare with. */
    private static double humanSeconds = Double.NaN;
    /** Level 8: Combat kills the spawned mobs instead of walking to a goal. */
    private static boolean killRun;
    private static volatile int mobsAlive = -1;
    private static volatile Vec3 nearestMob;
    private static int lastAlive;
    private static int lastKillTick;
    /** Level 8: Combat saw no wolf for a while, the walker goes to the nearest one. */
    private static boolean approaching;
    private static int approachTicks;
    private static boolean simPrepared;
    /** Per-tick log of the run in progress (the simulation deletes it when the run arrived). */
    private static Path currentLog;
    /** /airtest level: build the level and put the player at the start, no bot (to try it yourself). */
    private static boolean playOnly;
    /** Levels 10+: done only when standing on exactly the goal block (for a moment). */
    private static boolean exactGoal;
    private static int standTicks;
    private static int goalRetries;
    /** False while the server thread still places a recorded map. */
    private static volatile boolean worldReady = true;
    /** Where the player has to be before the run starts (teleport done, chunks there). */
    private static Vec3 expectedStart;
    /** Simulation: courses always at the same place, away from the rebuilt levels 8 and 9. */
    private static final BlockPos SIM_COURSE_BASE = new BlockPos(1809, 100, 19);
    private static long readySince;
    private static final int AUTO_SEEDS = 10;
    /** Recorded player runs: start xyz, goal xyz (feet) and their time in seconds. */
    private static final double[][] RECORDED_START = {
            {1792.8, 105.0, -50.1},
            {1787.5, 97.0, -191.2}
    };
    private static final double[][] RECORDED_GOAL = {
            {1787.5, 90.0, -108.5},
            {1796.6, 87.0, -239.2}
    };
    private static final double[] HUMAN_SECONDS = {22.5, 13.25};

    private AStarTestHarness() {
    }

    public static void init() {
        auto = System.getProperty("airclient.astarTest") != null
                || System.getenv("AIRCLIENT_ASTAR_TEST") != null;
        try {
            Files.deleteIfExists(FabricLoader.getInstance().getGameDir().resolve("astar-tests/BATCH_DONE"));
        } catch (IOException ignored) {
        }
        System.out.println("[AStarTest] harness loaded build v5");
        if (auto) {
            try {
                baritone.api.BaritoneAPI.getSettings().chunkCaching.value = true;
            } catch (RuntimeException ignored) {
            }
        }
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (auto) {
                autoSeed = 0;
                autoLevel = FIRST_AUTO_LEVEL;
                try {
                    Path progress = FabricLoader.getInstance().getGameDir().resolve("astar-tests/progress.txt");
                    if (Files.exists(progress)) {
                        String[] parts = Files.readString(progress).trim().split("\\s+");
                        if (parts.length >= 2) {
                            autoSeed = Integer.parseInt(parts[0]);
                            autoLevel = Integer.parseInt(parts[1]);
                        }
                    }
                } catch (IOException | NumberFormatException ignored) {
                }
                autoAt = com.coflnet.pathing.sim.SimClock.millis() + 2_000L;
                if (com.coflnet.pathing.sim.SimJobs.ENABLED) {
                    com.coflnet.pathing.sim.SimJobs.writeSettings();
                    say("Simulation worker ready, waiting for jobs in sim/job.json");
                } else {
                    say("Auto bot batch armed (resume after " + autoSeed + "/" + autoLevel + ")");
                }
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(AStarTestHarness::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("airtest")
                        .then(ClientCommands.literal("course").executes(c -> {
                            buildCourse();
                            return 1;
                        }))
                        .then(ClientCommands.literal("run").executes(c -> {
                            run();
                            return 1;
                        }))
                        .then(ClientCommands.literal("warp").executes(c -> {
                            warpTest();
                            return 1;
                        }))
                        .then(ClientCommands.literal("play")
                                .then(ClientCommands.argument("seed", com.mojang.brigadier.arguments.LongArgumentType.longArg())
                                        .then(ClientCommands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 5))
                                                .executes(c -> {
                                                    playCourse(com.mojang.brigadier.arguments.LongArgumentType.getLong(c, "seed"),
                                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "level"));
                                                    return 1;
                                                }))))
                        .then(ClientCommands.literal("bot")
                                .then(ClientCommands.argument("seed", com.mojang.brigadier.arguments.LongArgumentType.longArg())
                                        .then(ClientCommands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, LAST_AUTO_LEVEL))
                                                .executes(c -> {
                                                    botCourse(com.mojang.brigadier.arguments.LongArgumentType.getLong(c, "seed"),
                                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "level"));
                                                    return 1;
                                                }))))
                        .then(ClientCommands.literal("level")
                                .then(ClientCommands.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(6, LAST_AUTO_LEVEL))
                                        .executes(c -> {
                                            tryLevel(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "level"), 1);
                                            return 1;
                                        })
                                        .then(ClientCommands.argument("seed", com.mojang.brigadier.arguments.LongArgumentType.longArg())
                                                .executes(c -> {
                                                    tryLevel(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "level"),
                                                            com.mojang.brigadier.arguments.LongArgumentType.getLong(c, "seed"));
                                                    return 1;
                                                }))))
                        .then(ClientCommands.literal("stop").executes(c -> {
                            stop();
                            return 1;
                        }))));
    }

    /** The same set-up the bot gets (map, effects, wolves + sword, goal), then the player plays. */
    private static void tryLevel(int level, long seed) {
        stop();
        if (!startRecorded(seed, level) || state == State.IDLE) return;
        playOnly = true;
        say("Level " + level + " (seed " + seed + ") wird aufgebaut - gleich geht's los. /airtest stop beendet.");
    }

    private static void buildCourse() {
        playCourse(1, 1);
    }

    private static void playCourse(long seed) {
        playCourse(seed, 1);
    }

    private static void playCourse(long seed, int level) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        stop();
        base = p.blockPosition();
        int y = 100;
        com.coflnet.pathing.astar.fuzz.Course course =
                com.coflnet.pathing.astar.fuzz.Course.generate(seed, y, true, level);
        buildFromCourse(course);
        applyEffects(course);
        command("tp " + (base.getX() + 0.5) + " " + y + " " + (base.getZ() + 0.5));
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("astar-tests");
            Files.createDirectories(dir);
            out = Files.newBufferedWriter(dir.resolve("human-" + seed + "-" + LocalDateTime.now().format(STAMP) + ".jsonl"),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            out.write("{\"seed\":" + seed + ",\"level\":" + level + ",\"goal\":[" + (base.getX() + course.goalX())
                    + "," + course.goalY() + "," + base.getZ() + "]}\n");
        } catch (IOException e) {
            say("Cannot open human log: " + e.getMessage());
            return;
        }
        ticks = 0;
        state = State.HUMAN;
        say("Play seed " + seed + " level " + level + ". /airtest stop to save the run.");
    }

    private static void botCourse(long seed) {
        botCourse(seed, 1);
    }

    private static void botCourse(long seed, int level) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        stop();
        if (level >= 6) {
            startRecorded(seed, level);
            return;
        }
        setRun(seed, level, 95.0, 90_000L, Double.NaN);
        base = p.blockPosition();
        int y = 100;
        com.coflnet.pathing.astar.fuzz.Course course =
                com.coflnet.pathing.astar.fuzz.Course.generate(seed, y, true, level);
        buildFromCourse(course);
        applyEffects(course);
        target = new BlockPos(base.getX() + course.goalX(), course.goalY(), base.getZ());
        openLog("bot-" + seed + "-" + level + "-" + LocalDateTime.now().format(STAMP) + ".jsonl", seed, level, course);
        command("tp " + (base.getX() + 0.5) + " " + y + " " + (base.getZ() + 0.5));
        wait = 30;
        ticks = 0;
        refreshed = false;
        startedAt = com.coflnet.pathing.sim.SimClock.millis();
        state = State.BUILD_WAIT;
        say("Bot seed " + seed + " level " + level);
    }

    /** Manual /airtest run uses the last built course and target. */
    private static void run() {
        Minecraft mc = Minecraft.getInstance();
        if (base == null || target == null || mc.player == null) {
            say("Build a course first: /airtest bot <seed> <level>");
            return;
        }
        openLog("bot-astar-" + LocalDateTime.now().format(STAMP) + ".jsonl", -1, -1, null);
        command("tp " + (base.getX() + 0.5) + " " + base.getY() + " " + (base.getZ() + 0.5));
        wait = 30;
        ticks = 0;
        startedAt = com.coflnet.pathing.sim.SimClock.millis();
        state = State.BUILD_WAIT;
    }

    private static void openLog(String name, long seed, int level, com.coflnet.pathing.astar.fuzz.Course course) {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("astar-tests");
            Files.createDirectories(dir);
            currentLog = dir.resolve(name);
            out = Files.newBufferedWriter(currentLog,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            if (course != null) {
                out.write("{\"seed\":" + seed + ",\"level\":" + level + ",\"goal\":["
                        + (base.getX() + course.goalX()) + "," + course.goalY() + "," + base.getZ() + "]}\n");
            }
        } catch (IOException e) {
            say("Cannot open log: " + e.getMessage());
        }
    }

    private static void applyEffects(com.coflnet.pathing.astar.fuzz.Course course) {
        command("difficulty peaceful");
        command("gamerule doMobSpawning false");
        command("effect clear @s");
        int speed = com.coflnet.pathing.sim.SimJobs.profileSpeed(), jump = com.coflnet.pathing.sim.SimJobs.profileJump();
        if (speed >= 0 || jump >= 0) {
            // simulation: the fixed profile instead of the course's random effects
            if (speed >= 0) command("effect give @s minecraft:speed 1000000 " + speed + " true");
            if (jump >= 0) command("effect give @s minecraft:jump_boost 1000000 " + jump + " true");
            return;
        }
        if (course.speedLevel() > 0) {
            command("effect give @s minecraft:speed 1000000 " + course.speedLevel() + " true");
        }
        if (course.jumpLevel() > 0) {
            command("effect give @s minecraft:jump_boost 1000000 " + course.jumpLevel() + " true");
        }
    }

    private static void buildFromCourse(com.coflnet.pathing.astar.fuzz.Course course) {
        int x = base.getX(), y = 100, z = base.getZ();
        command("fill " + (x - 2) + " " + (y - 4) + " " + (z - 6) + " " + (x + 60) + " " + (y + 14) + " " + (z + 6) + " air");
        command("fill " + (x - 1) + " " + (y - 1) + " " + (z - 1) + " " + (x + 1) + " " + (y - 1) + " " + (z + 1) + " stone");
        for (com.coflnet.pathing.astar.fuzz.Course.Fill f : course.fills()) {
            command("fill " + (x + f.x1()) + " " + f.y1() + " " + (z + f.z1()) + " "
                    + (x + f.x2()) + " " + f.y2() + " " + (z + f.z2()) + " " + f.block());
        }
    }

    private static void warpTest() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        stop();
        base = p.blockPosition();
        int x = base.getX(), y = base.getY(), z = base.getZ();
        command("fill " + (x - 4) + " " + y + " " + (z - 2) + " " + (x + 4) + " " + (y + 10) + " " + (z + 12) + " air");
        command("fill " + (x - 4) + " " + (y - 1) + " " + (z - 2) + " " + (x + 4) + " " + (y - 1) + " " + (z + 12) + " stone");
        command("fill " + (x - 2) + " " + (y + 5) + " " + (z + 5) + " " + (x + 2) + " " + (y + 5) + " " + (z + 9) + " stone");
        command("fill " + (x - 2) + " " + (y + 6) + " " + (z + 5) + " " + (x + 2) + " " + (y + 8) + " " + (z + 9) + " air");
        command("give @s minecraft:carrot_on_a_stick[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1b}] 1");
        command("tp " + (x + 0.5) + " " + y + " " + (z + 1.5));
        target = new BlockPos(x, y + 6, z + 7);
        openLog("warp-" + LocalDateTime.now().format(STAMP) + ".jsonl", -1, -1, null);
        wait = 50;
        ticks = 0;
        startedAt = com.coflnet.pathing.sim.SimClock.millis();
        state = State.BUILD_WAIT;
        say("Starting etherwarp test");
    }

    private static void stop() {
        if (state == State.HUMAN && out != null) {
            say(String.format(java.util.Locale.ROOT, "Gestoppt nach %.2fs", ticks / 20.0));
        }
        playOnly = false;
        pingplus.voicechat.client.FollowFeature.stopWalking();
        if (killRun) {
            pingplus.voicechat.client.FollowFeature.giveBack();
            killRun = false;
            serverForceloadRemoveAll(); // the wolf area does not need to tick any more
        }
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
            out = null;
        }
        state = State.IDLE;
        autoRun = false;
    }

    private static void nextAuto() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            autoAt = com.coflnet.pathing.sim.SimClock.millis() + 2_000L;
            return;
        }
        if (com.coflnet.pathing.sim.SimJobs.ENABLED) {
            nextSimRun();
            return;
        }
        autoSeed++;
        if (autoSeed > AUTO_SEEDS) {
            autoSeed = 1;
            autoLevel++;
        }
        if (autoLevel > LAST_AUTO_LEVEL) {
            say("Auto batch done");
            closeSummary();
            try {
                Path dir = FabricLoader.getInstance().getGameDir().resolve("astar-tests");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("BATCH_DONE"), LocalDateTime.now().toString());
            } catch (IOException ignored) {
            }
            auto = false;
            return;
        }
        if (autoLevel >= 6) {
            if (!startRecorded(autoSeed, autoLevel)) {
                autoSeed = AUTO_SEEDS; // level not available: skip it
                autoAt = com.coflnet.pathing.sim.SimClock.millis() + 400L;
                return;
            }
            if (state != State.IDLE) autoRun = true;
            return;
        }
        startCourse(autoSeed, autoLevel, p.blockPosition());
        autoRun = true;
    }

    /** Simulation: the next run of the job the runner gave this instance. */
    private static void nextSimRun() {
        com.coflnet.pathing.sim.SimJobs.Run r = com.coflnet.pathing.sim.SimJobs.next();
        if (r == null) {
            com.coflnet.pathing.sim.TurboClock.want(false);
            autoAt = com.coflnet.pathing.sim.SimClock.millis() + 250L;
            return;
        }
        if (!simPrepared) {
            command("save-off"); // no autosave every few seconds at turbo speed
            // less server work per tick (old and new game rule names; the unknown one just fails)
            for (String rule : new String[]{"randomTickSpeed 0", "random_tick_speed 0", "doDaylightCycle false",
                    "advance_time false", "doWeatherCycle false", "advance_weather false", "doMobSpawning false",
                    "spawn_mobs false"}) {
                command("gamerule " + rule);
            }
            serverForceloadRemoveAll(); // left over from an earlier wolf level
            simPrepared = true;
        }
        boolean started;
        if (r.level() >= 6) {
            started = startRecorded(r.seed(), r.level()) && state != State.IDLE;
        } else {
            startCourse(r.seed(), r.level(), SIM_COURSE_BASE);
            started = true;
        }
        if (!started) {
            com.coflnet.pathing.sim.SimJobs.report(r.level(), r.seed(), "FAILED", 0, "cannot start level " + r.level(), Double.NaN);
            autoAt = com.coflnet.pathing.sim.SimClock.millis() + 250L;
            return;
        }
        command("effect give @s minecraft:instant_health 1 5 true"); // falls of the run before
        autoRun = true;
        com.coflnet.pathing.sim.TurboClock.want(true);
    }

    private static void startCourse(long seed, int level, BlockPos at) {
        setRun(seed, level, 95.0, 90_000L, Double.NaN);
        base = at;
        int y = 100;
        com.coflnet.pathing.astar.fuzz.Course course =
                com.coflnet.pathing.astar.fuzz.Course.generate(seed, y, true, level);
        buildFromCourse(course);
        applyEffects(course);
        target = new BlockPos(base.getX() + course.goalX(), course.goalY(), base.getZ());
        openLog("auto-bot-" + seed + "-" + level + "-" + LocalDateTime.now().format(STAMP) + ".jsonl",
                seed, level, course);
        command("tp " + (base.getX() + 0.5) + " " + y + " " + (base.getZ() + 0.5));
        expectedStart = new Vec3(base.getX() + 0.5, y, base.getZ() + 0.5);
        beginRun(30);
        say("Auto " + seed + "/" + level);
    }

    private static void setRun(long seed, int level, double fall, long timeout, double human) {
        runSeed = seed;
        runLevel = level;
        fallY = fall;
        timeoutMs = timeout;
        humanSeconds = human;
        killRun = false;
        exactGoal = false;
        standTicks = 0;
        goalRetries = 0;
        mobsAlive = -1;
        nearestMob = null;
        lastAlive = -1;
        lastKillTick = 0;
        approaching = false;
    }

    /** Levels 6 to 9; false if the level cannot run (no recording, not singleplayer). */
    private static boolean startRecorded(long seed, int level) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            autoAt = com.coflnet.pathing.sim.SimClock.millis() + 2_000L;
            return true;
        }
        if (level <= 7) {
            startPlacedLevel(seed, level);
            return true;
        }
        return startRebuiltLevel(seed, level);
    }

    /**
     * Levels 6 and 7 are the player's recorded courses: they already exist in the world,
     * the bot just teleports to the recorded start and has to reach the recorded stop.
     * Effects are at least what the player had, randomized slightly higher per try.
     */
    private static void startPlacedLevel(long seed, int level) {
        int idx = level - 6;
        double[] start = RECORDED_START[idx];
        double[] goal = RECORDED_GOAL[idx];
        // the courses go down to y 83: the old fixed 95 limit failed them half way
        setRun(seed, level, Math.min(start[1], goal[1]) - 10.0, 90_000L, HUMAN_SECONDS[idx]);
        Random rnd = new Random(seed * 9_176L + level * 43L);
        // Player recordings peaked at ~3.26 blocks jump height (Jump Boost III) and
        // sprint speed; give at least that, at most one level more.
        int speedAmp = rnd.nextInt(2);      // Speed I .. II
        int jumpAmp = 2 + rnd.nextInt(2);   // Jump Boost III .. IV
        // simulation: one fixed profile for all runs (the evolution calibrates for it)
        if (com.coflnet.pathing.sim.SimJobs.profileSpeed() >= 0) speedAmp = com.coflnet.pathing.sim.SimJobs.profileSpeed();
        if (com.coflnet.pathing.sim.SimJobs.profileJump() >= 0) jumpAmp = com.coflnet.pathing.sim.SimJobs.profileJump();
        command("difficulty peaceful");
        command("effect clear @s");
        command("effect give @s minecraft:speed 1000000 " + speedAmp + " true");
        command("effect give @s minecraft:jump_boost 1000000 " + jumpAmp + " true");
        command("tp " + start[0] + " " + start[1] + " " + start[2]);
        expectedStart = new Vec3(start[0], start[1], start[2]);
        target = BlockPos.containing(goal[0], goal[1], goal[2]);
        openLog("auto-bot-" + seed + "-" + level + "-" + LocalDateTime.now().format(STAMP) + ".jsonl",
                seed, level, null);
        header("{\"seed\":" + seed + ",\"level\":" + level
                + ",\"goal\":[" + target.getX() + "," + target.getY() + "," + target.getZ()
                + "],\"start\":[" + start[0] + "," + start[1] + "," + start[2]
                + "],\"humanSeconds\":" + humanSeconds
                + ",\"speedAmp\":" + speedAmp + ",\"jumpAmp\":" + jumpAmp + "}");
        beginRun(40);
        say("Auto " + seed + "/" + level + " recorded (speed " + speedAmp + ", jump " + jumpAmp + ")");
    }

    /**
     * Levels 8 and 9 come from the newest "level 8" / "level 9" recording in logs/interactions:
     * the recorded map is placed 1:1 at its original coordinates (singleplayer only).
     * 8: every recorded wolf is spawned where it was first seen and Combat has to kill them all.
     * 9: the goal is a random spot the player stood on; the player's time to it is the reference.
     * Speed is the recorded SkyBlock speed, no jump boost (as on SkyBlock).
     */
    private static boolean startRebuiltLevel(long seed, int level) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.server.MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            say("Level " + level + " needs a singleplayer world");
            return false;
        }
        RecordedLevel rec = RecordedLevel.load(level);
        if (rec == null) {
            say("Level " + level + ": no recording named \"level " + level + "\" in logs/interactions");
            return false;
        }
        boolean kill = level == 8;
        // levels 10+ (parkour recorded with SkyBlock movement): from the recorded start to its end
        boolean toEnd = level >= 10;
        rec.fillGround = level == 8 || level == 9;
        RecordedLevel.Point goal = kill ? null
                : toEnd ? new RecordedLevel.Point(rec.durationSeconds, rec.end.x, rec.end.y, rec.end.z)
                : rec.randomGoal(seed, 20.0);
        if (!kill && goal == null) {
            say("Level " + level + ": recording has no ground points");
            return false;
        }
        int wolves = kill ? rec.mobs("Wolf").size() : 0;
        if (kill && wolves == 0) {
            say("Level " + level + ": recording has no wolves");
            return false;
        }
        setRun(seed, level, rec.minY - 3.0, kill ? 300_000L : toEnd ? 60_000L : 120_000L, kill ? Double.NaN : goal.t());
        killRun = kill;
        worldReady = false;
        server.execute(() -> {
            net.minecraft.server.level.ServerLevel world = server.overworld();
            rec.build(world);
            if (kill) {
                // keep every wolf loaded, also the ones far from the player
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                        "forceload add " + rec.minX + " " + rec.minZ + " " + rec.maxX + " " + rec.maxZ);
                mobsAlive = rec.spawnMobs(server, world, "Wolf", "minecraft:wolf");
            } else {
                RecordedLevel.removeMobs(world);
            }
            worldReady = true;
        });
        command("difficulty peaceful");
        command("effect clear @s");
        int speedAmp = rec.speedAmplifier();
        // no tab list in a singleplayer recording: the profile's (SkyBlock) speed
        if (speedAmp < 0 && com.coflnet.pathing.sim.SimJobs.profileSpeed() >= 0) {
            speedAmp = com.coflnet.pathing.sim.SimJobs.profileSpeed();
        }
        if (speedAmp >= 0) command("effect give @s minecraft:speed 1000000 " + speedAmp + " true");
        // the profile's Jump Boost on the SkyBlock maps as well (the recorded speed stays)
        int jumpAmp = com.coflnet.pathing.sim.SimJobs.profileJump();
        if (jumpAmp >= 0) command("effect give @s minecraft:jump_boost 1000000 " + jumpAmp + " true");
        if (kill) {
            // a sword in slot 1, so a wolf takes 1-2 hits like on SkyBlock and not 8 punches
            command("item replace entity @s hotbar.0 with minecraft:diamond_sword");
        }
        // looking the way the player looked at the start (on a 1-wide course the first ticks count)
        command(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", rec.start.x, rec.start.y, rec.start.z,
                rec.startYaw, rec.startPitch));
        expectedStart = rec.start;
        target = kill ? null : toEnd ? rec.landingSpot() : BlockPos.containing(goal.x(), goal.y(), goal.z());
        exactGoal = toEnd;
        openLog("auto-bot-" + seed + "-" + level + "-" + LocalDateTime.now().format(STAMP) + ".jsonl",
                seed, level, null);
        header("{\"seed\":" + seed + ",\"level\":" + level
                + (kill ? ",\"task\":\"kill\",\"mobs\":" + wolves
                        : ",\"goal\":[" + target.getX() + "," + target.getY() + "," + target.getZ() + "]")
                + ",\"start\":[" + rec.start.x + "," + rec.start.y + "," + rec.start.z
                + "],\"humanSeconds\":" + (Double.isNaN(humanSeconds) ? "null" : String.valueOf(humanSeconds))
                + ",\"speedAmp\":" + speedAmp + ",\"blocks\":" + rec.positions.length
                + ",\"exactStates\":" + rec.exactStates + "}");
        beginRun(60);
        say("Auto " + seed + "/" + level + " " + rec.describe()
                + (kill ? " - kill " + wolves + " wolves"
                        : String.format(java.util.Locale.ROOT, " - goal %d %d %d (you: %.1fs)",
                                target.getX(), target.getY(), target.getZ(), humanSeconds)));
        return true;
    }

    /** The player stands at the run's start and the chunks around are loaded. */
    private static boolean startReady(Minecraft mc, LocalPlayer player) {
        if (expectedStart != null && player.position().distanceToSqr(expectedStart) > 2.5 * 2.5) return false;
        int cx = player.blockPosition().getX() >> 4, cz = player.blockPosition().getZ() >> 4;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (!mc.level.getChunkSource().hasChunk(cx + dx, cz + dz)) return false;
            }
        }
        return true;
    }

    /**
     * On the server thread, queued before the next run's build: as a chat command it could arrive
     * after the next wolf level's "forceload add" and unload the new wolves (counted 0 = done).
     */
    private static void serverForceloadRemoveAll() {
        net.minecraft.server.MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), "forceload remove all"));
    }

    private static void header(String json) {
        try {
            if (out != null) {
                out.write(json + "\n");
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static void beginRun(int waitTicks) {
        readySince = System.currentTimeMillis();
        wait = waitTicks;
        ticks = 0;
        refreshed = false;
        startedAt = com.coflnet.pathing.sim.SimClock.millis();
        state = State.BUILD_WAIT;
    }

    private static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (com.coflnet.pathing.sim.SimJobs.ENABLED) com.coflnet.pathing.sim.SimJobs.heartbeat();
        if (player == null || mc.level == null) return;
        if (com.coflnet.pathing.sim.SimJobs.ENABLED && state == State.IDLE && player.isDeadOrDying()) {
            player.respawn(); // died in the run before (fall, lava): back for the next one
            mc.gui.setScreen(null);
            return;
        }
        if (auto && state == State.IDLE && com.coflnet.pathing.sim.SimClock.millis() >= autoAt) {
            ensureSummary();
            nextAuto();
            if (state == State.IDLE) return;
        }
        if (state == State.IDLE || state == State.DONE) return;
        if (state == State.HUMAN) {
            ticks++;
            writeHuman(mc, player);
            return;
        }
        if (state == State.BUILD_WAIT) {
            if (--wait > 0 || !worldReady) return;
            if (!startReady(mc, player) && System.currentTimeMillis() - readySince < 15_000L) return; // teleport / chunks not there yet (wall time)
            if (!refreshed) {
                refreshBaritoneWorld(mc);
                refreshed = true;
                wait = 10;
                return;
            }
            if (playOnly) {
                playOnly = false;
                if (killRun) player.getInventory().setSelectedSlot(0);
                state = State.HUMAN; // ticks are logged like a human run
                say(killRun ? "Los: alle Wölfe töten (" + mobsAlive + ")"
                        : target != null ? "Los: Ziel " + target.getX() + " " + target.getY() + " " + target.getZ()
                        + (Double.isNaN(humanSeconds) ? "" : String.format(java.util.Locale.ROOT, " (deine Aufnahme: %.1fs)", humanSeconds))
                        : "Los");
                return;
            }
            if (killRun) {
                player.getInventory().setSelectedSlot(0); // the sword
                pingplus.voicechat.client.FollowFeature.borrow(java.util.List.of("wolf"));
            } else {
                pingplus.voicechat.client.FollowFeature.walkTo(target, exactGoal ? 0 : 1, false);
            }
            // the run's time starts now (waiting for chunks in turbo used up simulated time)
            startedAt = com.coflnet.pathing.sim.SimClock.millis();
            state = State.RUNNING;
            return;
        }
        if (state != State.RUNNING) return;
        ticks++;
        PathEngine.Result result;
        String status;
        if (killRun) {
            net.minecraft.server.MinecraftServer server = mc.getSingleplayerServer();
            Vec3 me = player.position();
            if (server != null && ticks % 10 == 0) {
                double minY = fallY;
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel w = server.overworld();
                    mobsAlive = RecordedLevel.aliveMobs(w, minY);
                    nearestMob = RecordedLevel.nearestMob(w, me);
                });
            }
            if (mobsAlive != lastAlive) {
                lastAlive = mobsAlive;
                lastKillTick = ticks;
            }
            Vec3 near = nearestMob;
            double dist = near == null ? 0.0 : near.distanceTo(me);
            if (!approaching && near != null && ticks - lastKillTick > 100 && dist > 12.0) {
                // Combat found nothing for 10 s: the walker goes to the nearest wolf (out of sight /
                // behind walls), then Combat again. The pathfinder is what is tested anyway.
                pingplus.voicechat.client.FollowFeature.giveBack();
                pingplus.voicechat.client.FollowFeature.walkTo(BlockPos.containing(near), 3, false);
                approaching = true;
                approachTicks = 0;
            } else if (approaching) {
                approachTicks++;
                PathEngine.Result walk = pingplus.voicechat.client.FollowFeature.walkResult();
                if (dist < 10.0 || walk == PathEngine.Result.ARRIVED || walk == PathEngine.Result.FAILED
                        || approachTicks > 600) {
                    pingplus.voicechat.client.FollowFeature.stopWalking();
                    pingplus.voicechat.client.FollowFeature.borrow(java.util.List.of("wolf"));
                    approaching = false;
                    lastKillTick = ticks;
                }
            }
            // 0 only counts after a real count (not before the spawn landed)
            result = mobsAlive == 0 && ticks > 20 ? PathEngine.Result.ARRIVED : PathEngine.Result.RUNNING;
            status = "wolves left " + mobsAlive + (approaching ? ", walking to the next wolf"
                    : pingplus.voicechat.client.KillAuraFeature.hasTarget() ? ", fighting" : ", searching");
        } else {
            result = pingplus.voicechat.client.FollowFeature.walkResult();
            if (exactGoal) {
                // jump up and stand on the very spot (the pillar top), not next to it
                boolean onSpot = player.onGround() && player.blockPosition().equals(target);
                standTicks = onSpot ? standTicks + 1 : 0;
                if (standTicks >= 10) {
                    result = PathEngine.Result.ARRIVED;
                } else if (result == PathEngine.Result.ARRIVED) {
                    // the walk thinks it is there but we do not stand on the spot (yet): go again
                    if (!onSpot && goalRetries++ < 3) {
                        pingplus.voicechat.client.FollowFeature.walkTo(target, 0, false);
                    }
                    result = onSpot || goalRetries <= 3 ? PathEngine.Result.RUNNING : PathEngine.Result.FAILED;
                }
            }
            status = pingplus.voicechat.client.FollowFeature.walkStatus();
        }
        if (player.position().y < fallY || player.isDeadOrDying()) {
            result = PathEngine.Result.FAILED;
            status = player.isDeadOrDying() ? "died" : "fell below " + fallY;
            say("FALL seed=" + runSeed + " level=" + runLevel);
        }
        write(player, result, status);
        boolean timedOut = com.coflnet.pathing.sim.SimClock.millis() - startedAt > timeoutMs;
        boolean done = result == PathEngine.Result.ARRIVED || result == PathEngine.Result.FAILED || timedOut;
        if (done) {
            if (result != PathEngine.Result.ARRIVED && timedOut) {
                result = PathEngine.Result.FAILED;
                status = "timeout " + (timeoutMs / 1000) + "s: " + status;
            }
            recordSummary(runSeed, runLevel, result, ticks, status);
            if (com.coflnet.pathing.sim.SimJobs.ENABLED) {
                com.coflnet.pathing.sim.SimJobs.report(runLevel, runSeed, result.name(), ticks, status, humanSeconds,
                        player.position());
            } else if (autoRun) {
                writeProgress(autoSeed, autoLevel);
            }
            say("Bot " + runSeed + "/" + runLevel + " " + result + " (" + ticks + " ticks)");
            if (!Double.isNaN(humanSeconds) && result == PathEngine.Result.ARRIVED) {
                double secs = ticks / 20.0;
                say(String.format(java.util.Locale.ROOT, "Time %.2fs vs human %.2fs -> %s",
                        secs, humanSeconds, secs < humanSeconds ? "FASTER" : "SLOWER"));
            }
            boolean arrived = result == PathEngine.Result.ARRIVED;
            stop();
            if (com.coflnet.pathing.sim.SimJobs.ENABLED) com.coflnet.pathing.sim.SimJobs.cleanLogs(currentLog, arrived);
            if (auto) autoAt = com.coflnet.pathing.sim.SimClock.millis() + 400L;
        }
    }

    private static void ensureSummary() {
        if (summary != null) return;
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("astar-tests");
            Files.createDirectories(dir);
            summary = Files.newBufferedWriter(dir.resolve("bot-summary.tsv"),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            summary.write("seed\tlevel\tresult\tticks\tstatus\n");
            summary.flush();
        } catch (IOException e) {
            say("Cannot open summary: " + e.getMessage());
        }
    }

    private static void recordSummary(long seed, int level, PathEngine.Result result, int tickCount, String status) {
        if (summary == null) return;
        try {
            summary.write(seed + "\t" + level + "\t" + result + "\t" + tickCount + "\t"
                    + status.replace("\t", " ") + "\n");
            summary.flush();
        } catch (IOException ignored) {
        }
    }

    private static void writeProgress(int seed, int level) {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("astar-tests");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("progress.txt"), seed + " " + level);
        } catch (IOException ignored) {
        }
    }

    private static void closeSummary() {
        if (summary != null) {
            try {
                summary.close();
            } catch (IOException ignored) {
            }
            summary = null;
        }
    }

    private static void writeHuman(Minecraft mc, LocalPlayer player) {
        if (out == null) return;
        try {
            Vec3 pos = player.position();
            Vec3 v = player.getDeltaMovement();
            String line = String.format(java.util.Locale.ROOT,
                    "{\"t\":%d,\"x\":%.3f,\"y\":%.3f,\"z\":%.3f,\"vx\":%.3f,\"vy\":%.3f,\"vz\":%.3f,"
                            + "\"yaw\":%.1f,\"pitch\":%.1f,\"ground\":%s,\"keys\":\"%s%s%s%s%s%s\"}%n",
                    ticks, pos.x, pos.y, pos.z, v.x, v.y, v.z,
                    player.getYRot(), player.getXRot(), player.onGround(),
                    mc.options.keyUp.isDown() ? "W" : "", mc.options.keyDown.isDown() ? "S" : "",
                    mc.options.keyLeft.isDown() ? "A" : "", mc.options.keyRight.isDown() ? "D" : "",
                    mc.options.keyJump.isDown() ? "J" : "", mc.options.keySprint.isDown() ? "R" : "");
            out.write(line);
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private static void write(LocalPlayer player, PathEngine.Result result, String status) {
        if (out == null) return;
        try {
            Vec3 pos = player.position();
            Vec3 v = player.getDeltaMovement();
            String line = String.format(java.util.Locale.ROOT,
                    "{\"t\":%d,\"x\":%.3f,\"y\":%.3f,\"z\":%.3f,\"vx\":%.3f,\"vy\":%.3f,\"vz\":%.3f,"
                            + "\"yaw\":%.1f,\"pitch\":%.1f,\"ground\":%s,\"status\":\"%s\",\"result\":\"%s\"}%n",
                    ticks, pos.x, pos.y, pos.z, v.x, v.y, v.z,
                    player.getYRot(), player.getXRot(), player.onGround(),
                    status.replace("\"", "'"), result);
            out.write(line);
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private static void refreshBaritoneWorld(Minecraft mc) {
        try {
            baritone.api.IBaritone b = baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
            if (b == null || mc.level == null) return;
            baritone.api.BaritoneAPI.getSettings().chunkCaching.value = true;
            baritone.api.cache.ICachedWorld cache = b.getWorldProvider().getCurrentWorld().getCachedWorld();
            int cx = mc.player.blockPosition().getX() >> 4;
            int cz = mc.player.blockPosition().getZ() >> 4;
            for (int dx = -4; dx <= 8; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    cache.queueForPacking(mc.level.getChunk(cx + dx, cz + dz));
                }
            }
            b.getPathingBehavior().cancelEverything();
        } catch (RuntimeException e) {
            say("refreshBaritoneWorld: " + e);
        }
    }

    private static void command(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) mc.getConnection().sendCommand(command);
    }

    private static void say(String message) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) player.sendSystemMessage(Component.literal("[AStarTest] " + message));
        System.out.println("[AStarTest] " + message);
    }
}
