package com.coflnet.pathing.sim;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Baritone settings found by the evolution (tools/sim/evolve.py). A gene is a Baritone setting
 * name and a number (booleans: >= 0.5 is true). Applied last in {@link com.coflnet.pathing.PathfinderTuning#apply},
 * so it wins over the Pathfinder tab.
 *
 * <p>Source: the running simulation job, else {@code config/baritone-genome.json} (the evolved
 * best, installed with {@code evolve.py install}), else nothing (normal settings).
 */
public final class Genome {
    public static final String FILE = "config/baritone-genome.json";
    private static volatile Map<String, Double> genes;
    private static final Set<String> WARNED = new HashSet<>();
    /** Value of each touched setting before any genome changed it (restored when the genome changes). */
    private static final Map<String, Object> ORIGINAL = new java.util.HashMap<>();

    private Genome() {
    }

    public static Map<String, Double> get() {
        Map<String, Double> g = genes;
        if (g == null) {
            g = load(FabricLoader.getInstance().getGameDir().resolve(FILE));
            genes = g;
        }
        return g;
    }

    /**
     * The simulation sets the genome of the job it runs. Settings the old genome changed go back
     * first: a gene missing in the new genome must not keep the last candidate's value.
     */
    @SuppressWarnings("unchecked")
    public static synchronized void set(Map<String, Double> fresh) {
        try {
            baritone.api.Settings s = baritone.api.BaritoneAPI.getSettings();
            for (Map.Entry<String, Object> e : ORIGINAL.entrySet()) {
                baritone.api.Settings.Setting<?> setting = s.byLowerName.get(e.getKey());
                if (setting != null) ((baritone.api.Settings.Setting<Object>) setting).value = e.getValue();
            }
        } catch (RuntimeException ignored) {
        }
        genes = Map.copyOf(fresh);
    }

    public static Map<String, Double> parse(JsonObject o) {
        JsonObject g = o.has("genes") ? o.getAsJsonObject("genes") : o;
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : g.entrySet()) {
            if (e.getValue().isJsonPrimitive()) {
                var p = e.getValue().getAsJsonPrimitive();
                out.put(e.getKey(), p.isBoolean() ? (p.getAsBoolean() ? 1.0 : 0.0) : p.getAsDouble());
            }
        }
        return out;
    }

    private static Map<String, Double> load(Path file) {
        if (!Files.isRegularFile(file)) return Map.of();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Double> g = parse(JsonParser.parseReader(in).getAsJsonObject());
            System.out.println("[Genome] " + g.size() + " evolved Baritone settings from " + file);
            return g;
        } catch (Exception e) {
            System.out.println("[Genome] cannot read " + file + ": " + e);
            return Map.of();
        }
    }

    /**
     * @param hard          a hard path part (PathDifficulty): yaw smoothing / path smoothing stay off there
     * @param extraAirTicks added to the jump penalty as PathfinderTuning does (jump boost = longer jumps)
     */
    @SuppressWarnings("unchecked")
    public static synchronized void applyTo(baritone.api.Settings s, boolean hard, double extraAirTicks) {
        Map<String, Double> g = get();
        if (g.isEmpty()) return;
        for (Map.Entry<String, Double> e : g.entrySet()) {
            String name = e.getKey();
            double v = e.getValue();
            if (hard && (name.equals("moveYawSmoothing") || name.equals("pathSmoothing"))) continue;
            baritone.api.Settings.Setting<?> setting = s.byLowerName.get(name.toLowerCase(java.util.Locale.ROOT));
            if (setting == null) {
                if (WARNED.add(name)) System.out.println("[Genome] unknown Baritone setting " + name);
                continue;
            }
            if (name.equals("jumpPenalty")) {
                if (s.jumpPenalty.value >= com.coflnet.pathing.PathfinderTuning.NO_JUMP) continue; // jumps forbidden
                v += extraAirTicks;
            }
            if (name.equals("maxYawChangePerTick")) {
                v = Math.clamp(v, 2.0, com.coflnet.pathing.TurnProfile.HUMAN_MAX_DEG_PER_TICK);
            }
            ORIGINAL.putIfAbsent(name.toLowerCase(java.util.Locale.ROOT), setting.value);
            Class<?> type = setting.getValueClass();
            Object value;
            if (type == Double.class) value = v;
            else if (type == Float.class) value = (float) v;
            else if (type == Integer.class) value = (int) Math.round(v);
            else if (type == Long.class) value = Math.round(v);
            else if (type == Boolean.class) value = v >= 0.5;
            else {
                if (WARNED.add(name)) System.out.println("[Genome] setting " + name + " is not a number");
                continue;
            }
            ((baritone.api.Settings.Setting<Object>) setting).value = value;
        }
    }
}
