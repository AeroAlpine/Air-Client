package com.coflnet.interactions;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import pingplus.voicechat.client.gui.glass.GlassStyle;

/**
 * Opens after a recording stops: name it ("Villager Interaction, Step 1") and save it.
 * Enter saves, Esc also saves (with the name typed so far) so a recording is never lost by accident;
 * only "Discard" (clicked twice) throws it away.
 */
final class InteractionNameScreen extends Screen {
    private final InteractionLogger.Recording recording;
    private EditBox name;
    private boolean done;
    private boolean confirmDiscard;
    private int left, top, panelWidth, panelHeight;

    InteractionNameScreen(InteractionLogger.Recording recording) {
        super(Component.literal("Name this interaction"));
        this.recording = recording;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(320, width - 24);
        panelHeight = 118;
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        String before = name == null ? "" : name.getValue();
        name = addRenderableWidget(new EditBox(font, left + 12, top + 40, panelWidth - 24, 20,
                Component.literal("Name")) {
            @Override
            public boolean keyPressed(KeyEvent event) {
                if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                    save();
                    return true;
                }
                return super.keyPressed(event);
            }
        });
        name.setMaxLength(80);
        name.setHint(Component.literal("e.g. Villager Interaction, Step 1"));
        name.setValue(before);
        setInitialFocus(name);
        int buttonWidth = (panelWidth - 24 - 6) / 2;
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(left + 12, top + panelHeight - 30, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal(confirmDiscard ? "Really discard?" : "Discard"), b -> {
            if (!confirmDiscard) {
                confirmDiscard = true;
                rebuildWidgets();
                return;
            }
            done = true;
            InteractionLogger.message("§7Recording discarded.");
            minecraft.gui.setScreen(null);
        }).bounds(left + 18 + buttonWidth, top + panelHeight - 30, buttonWidth, 20).build());
    }

    private void save() {
        if (done) return;
        done = true;
        InteractionLogger.save(recording, name.getValue());
        minecraft.gui.setScreen(null);
    }

    @Override
    public void onClose() {
        save();
    }

    @Override
    public void removed() {
        // Closed by something else (death, server GUI, disconnect): still keep the data.
        if (!done) {
            done = true;
            InteractionLogger.save(recording, name == null ? "" : name.getValue());
        }
        super.removed();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float dt) {
        super.extractBackground(g, mx, my, dt);
        GlassStyle.surface(g, left, top, panelWidth, panelHeight, 1, .2f);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        super.extractRenderState(g, mx, my, dt);
        g.nextStratum();
        g.text(font, GlassStyle.label("NAME THIS INTERACTION"), left + 12, top + 10, GlassStyle.TEXT, false);
        long secs = Math.max(0, (recording.endedAt - recording.startedAt) / 1000);
        String summary = secs + " s  ·  " + recording.events.size() + " events  ·  "
                + recording.guiSnapshots.size() + " GUIs  ·  " + recording.map.size() + " blocks";
        g.text(font, font.plainSubstrByWidth(summary, panelWidth - 24), left + 12, top + 24, GlassStyle.MUTED, false);
        g.text(font, font.plainSubstrByWidth("Enter = save   Esc = save", panelWidth - 24),
                left + 12, top + 66, GlassStyle.MUTED, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
