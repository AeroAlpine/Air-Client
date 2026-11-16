package pingplus.voicechat.client.gui;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * What the Controller Center (Right Shift) looked like when it was closed: where each category
 * was dragged and which ones were open. The screen is created anew every time it opens, so
 * this lives here and in a file (also kept across restarts).
 */
final class ClickGuiLayout {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("air-clickgui-layout.properties");
    static final Map<String, int[]> POSITIONS = new HashMap<>();
    static final Set<String> EXPANDED = new HashSet<>();
    /** Use the classic floating SkyBlock categories instead of the dedicated menu. */
    static boolean legacySkyblockUi = false;
    /** Client game tests start from the default layout and leave no file behind. */
    private static final boolean PERSIST = System.getProperty("fabric.client.gametest") == null;

    static {
        load();
    }

    private ClickGuiLayout() {
    }

    static synchronized void save() {
        if (!PERSIST) {
            return;
        }
        Properties values = new Properties();
        POSITIONS.forEach((title, pos) -> values.setProperty("pos." + title, pos[0] + "," + pos[1]));
        values.setProperty("expanded", String.join("\n", EXPANDED));
        values.setProperty("legacySkyblockUi", Boolean.toString(legacySkyblockUi));
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(tmp)) {
                values.store(out, "Controller Center layout");
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    private static void load() {
        if (!PERSIST || !Files.exists(FILE)) {
            return;
        }
        Properties values = new Properties();
        try (var in = Files.newInputStream(FILE)) {
            values.load(in);
        } catch (Exception ignored) {
            return;
        }
        legacySkyblockUi = Boolean.parseBoolean(values.getProperty("legacySkyblockUi", "false"));
        for (String key : values.stringPropertyNames()) {
            if (!key.startsWith("pos.")) {
                continue;
            }
            String[] parts = values.getProperty(key).split(",");
            try {
                POSITIONS.put(key.substring(4), new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())});
            } catch (Exception ignored) {
            }
        }
        String expanded = values.getProperty("expanded", "");
        for (String title : expanded.split("\n")) {
            if (!title.isBlank()) {
                EXPANDED.add(title);
            }
        }
    }

    /**
     * Extra scale for the Controller Center so that it takes the same share of the window as
     * in fullscreen: 1 in fullscreen; in a window of 60 % of the screen it is drawn at 60 % of
     * its fullscreen size (F11 used to leave it as big as in fullscreen). Minecraft's own GUI
     * scale may also change with the window ("Auto"); that is taken out, so it is not shrunk twice.
     */
    static float scaleFactor(Minecraft client) {
        try {
            float window = windowFactor(client);
            if (window >= 0.999f) {
                return 1f;
            }
            int current = Math.max(1, client.getWindow().getGuiScale());
            int[] monitor = monitorSize(client);
            int fullscreenScale = autoGuiScale(monitor[0], monitor[1]);
            int option = client.options.guiScale().get();
            if (option > 0) {
                fullscreenScale = Math.min(option, fullscreenScale);
            }
            return window * fullscreenScale / current;
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    /** Minecraft's "Auto" GUI scale for this resolution. */
    static int autoGuiScale(int width, int height) {
        int scale = 1;
        while (scale < width && scale < height && width / (scale + 1) >= 320 && height / (scale + 1) >= 240) {
            scale++;
        }
        return scale;
    }

    private static int[] monitorSize(Minecraft client) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long window = client.getWindow().handle();
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            GLFW.glfwGetWindowSize(window, w, h);
            GLFWVidMode mode = monitorMode(window, w.get(0), h.get(0), stack);
            return mode == null ? new int[]{1920, 1080} : new int[]{mode.width(), mode.height()};
        }
    }

    /** Window size relative to the monitor it is on: 1 in fullscreen. */
    static float windowFactor(Minecraft client) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long window = client.getWindow().handle();
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            GLFW.glfwGetWindowSize(window, w, h);
            int winW = w.get(0), winH = h.get(0);
            if (winW <= 0 || winH <= 0) {
                return 1f;
            }
            GLFWVidMode mode = monitorMode(window, winW, winH, stack);
            if (mode == null || mode.width() <= 0 || mode.height() <= 0) {
                return 1f;
            }
            float factor = Math.min(winW / (float) mode.width(), winH / (float) mode.height());
            return Math.clamp(factor, 0.45f, 1f);
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    /** Video mode of the monitor the window is (mostly) on. */
    private static GLFWVidMode monitorMode(long window, int winW, int winH, MemoryStack stack) {
        long monitor = GLFW.glfwGetWindowMonitor(window);
        if (monitor != 0) {
            return GLFW.glfwGetVideoMode(monitor);
        }
        IntBuffer x = stack.mallocInt(1);
        IntBuffer y = stack.mallocInt(1);
        GLFW.glfwGetWindowPos(window, x, y);
        int cx = x.get(0) + winW / 2, cy = y.get(0) + winH / 2;
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors != null) {
            for (int i = 0; i < monitors.limit(); i++) {
                long m = monitors.get(i);
                GLFWVidMode mode = GLFW.glfwGetVideoMode(m);
                if (mode == null) {
                    continue;
                }
                IntBuffer mx = stack.mallocInt(1);
                IntBuffer my = stack.mallocInt(1);
                GLFW.glfwGetMonitorPos(m, mx, my);
                if (cx >= mx.get(0) && cx < mx.get(0) + mode.width()
                        && cy >= my.get(0) && cy < my.get(0) + mode.height()) {
                    return mode;
                }
            }
        }
        long primary = GLFW.glfwGetPrimaryMonitor();
        return primary == 0 ? null : GLFW.glfwGetVideoMode(primary);
    }
}
