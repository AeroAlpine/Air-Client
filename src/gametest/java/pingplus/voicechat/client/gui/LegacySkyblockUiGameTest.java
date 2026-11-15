package pingplus.voicechat.client.gui;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import pingplus.voicechat.client.VoicechatClient;
import pingplus.voicechat.client.hud.ArraylistHud;
import pingplus.voicechat.client.hud.CoordinatesHud;
import pingplus.voicechat.client.hud.FpsHud;

/** Exercises both menu routes, classic settings windows and the persistent layout preference. */
public final class LegacySkyblockUiGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        boolean original = ClickGuiLayout.legacySkyblockUi;
        var expanded = new HashSet<>(ClickGuiLayout.EXPANDED);
        var positions = new HashMap<>(ClickGuiLayout.POSITIONS);
        try {
            context.getInput().resizeWindow(1440, 900);
            context.runOnClient(client -> {
                ClickGuiLayout.legacySkyblockUi = false;
                ClickGuiLayout.EXPANDED.clear();
                ClickGuiLayout.EXPANDED.add("GENERAL");
                ClickGuiLayout.POSITIONS.clear();
            });
            open(context);
            context.runOnClient(client -> {
                var screen = client.gui.screen();
                check(!hasButton(screen, "Investment Tracker"), "Modern mode exposed classic controls");
                header(screen, "SKYBLOCK");
                check(client.gui.screen() instanceof SkyblockScreen, "Modern header did not open the menu");
                client.gui.screen().onClose();
                check(client.gui.screen() == screen, "Menu did not return to the Controller Center");
                button(screen, "Legacy SkyBlock UI").onPress(null);
                check(ClickGuiLayout.legacySkyblockUi, "Toggle did not enable classic layout");
                check(!hasButton(screen, "SkyBlock menu"), "Classic mode retained the large menu entry");
                for (String name : List.of("Auction Flipper", "Bazaar Flipper", "Lowballing", "Combat",
                        "Investment Tracker", "Fire Sale Skins", "Allow jumps"))
                    button(screen, name);
                header(screen, "SKYBLOCK");
                check(client.gui.screen() == screen, "Classic header opened the large menu");
                check(ClickGuiLayout.EXPANDED.contains("SKYBLOCK"), "Classic category did not expand");
            });
            context.waitTicks(12);
            context.takeScreenshot("skyblock-classic-categories");
            for (String name : List.of("Auction Flipper Settings", "Bids Settings", "Bazaar Flipper Settings",
                    "Lowballing Settings", "Combat options", "Experiment Table Settings", "General",
                    "Investment Settings", "Fire Sale Settings", "Investment Panel Settings", "Tuning")) {
                context.runOnClient(client -> {
                    var screen = client.gui.screen();
                    screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                            .filter(w -> w.getWidth() == 18 && w.getMessage().getString().equals(name))
                            .findFirst().orElseThrow().onPress(null);
                    button(screen, "Close " + name);
                });
                if (name.equals("Auction Flipper Settings")) {
                    context.waitTicks(3);
                    context.takeScreenshot("skyblock-classic-settings");
                }
                context.runOnClient(client -> button(client.gui.screen(), "Close " + name).onPress(null));
            }
            open(context);
            context.runOnClient(client -> {
                var screen = client.gui.screen();
                check(hasButton(screen, "Investment Tracker"), "Reopening lost the chosen layout");
                button(screen, "Bazaar Flips GUI").onPress(null);
                check(client.gui.screen() instanceof com.coflnet.ahflipper.BazaarFlipScreen,
                        "Classic investment shortcut did not open Bazaar flips");
                client.gui.screen().onClose();
                check(client.gui.screen() == screen, "Investment screen did not return to classic UI");
                button(screen, "All off").onPress(null);
                check(ClickGuiLayout.legacySkyblockUi, "All off changed the chosen layout");
                button(screen, "Legacy SkyBlock UI").onPress(null);
                check(!ClickGuiLayout.legacySkyblockUi, "Toggle did not restore modern layout");
                check(!hasButton(screen, "Investment Tracker"), "Switching back left classic controls behind");
                header(screen, "SKYBLOCK");
                check(client.gui.screen() instanceof SkyblockScreen, "Switching back did not restore menu routing");
            });
            System.out.println("PASS: classic/modern SkyBlock toggle, header routing, settings, reopen, return navigation and All off");
        } finally {
            context.runOnClient(client -> {
                client.gui.setScreen(null);
                ClickGuiLayout.legacySkyblockUi = original;
                ClickGuiLayout.EXPANDED.clear();
                ClickGuiLayout.EXPANDED.addAll(expanded);
                ClickGuiLayout.POSITIONS.clear();
                ClickGuiLayout.POSITIONS.putAll(positions);
            });
        }
    }

    private static void open(ClientGameTestContext context) {
        var fps = new FpsHud();
        var coordinates = new CoordinatesHud();
        context.setScreen(() -> new ClickGuiScreen(fps, VoicechatClient.openGuiKey(), coordinates,
                new ArraylistHud(fps, coordinates)));
    }

    private static boolean hasButton(Screen screen, String label) {
        return screen.children().stream().anyMatch(w -> w instanceof Button button
                && button.getMessage().getString().equals(label));
    }

    private static Button button(Screen screen, String label) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(w -> w.getMessage().getString().equals(label)).findFirst().orElseThrow();
    }

    private static void header(Screen screen, String title) {
        // Keep the target header clear of other floating categories, then use scaled mouse input.
        for (Object category : (List<?>) field(screen, "categories")) {
            if (!field(category, "title").equals(title)) continue;
            int x = (int) field(screen, "canvasWidth") - 160;
            set(category, "x", x);
            set(category, "y", (int) field(screen, "scroll") + 30);
            var event = new MouseButtonEvent((x + 20) * ClickGuiScreen.currentScale(),
                    38 * ClickGuiScreen.currentScale(), new MouseButtonInfo(1, 0));
            check(screen.mouseClicked(event, false), "Category header rejected input");
            screen.mouseReleased(event);
            return;
        }
        throw new AssertionError("Missing category: " + title);
    }

    private static Field declared(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object field(Object owner, String name) {
        try { return declared(owner, name).get(owner); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void set(Object owner, String name, Object value) {
        try { declared(owner, name).set(owner, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
