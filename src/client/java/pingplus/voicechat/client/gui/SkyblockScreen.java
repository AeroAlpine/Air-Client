package pingplus.voicechat.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import pingplus.voicechat.client.PlayerSettings;
import pingplus.voicechat.client.gui.SkyblockModules.Action;
import pingplus.voicechat.client.gui.SkyblockModules.Choice;
import pingplus.voicechat.client.gui.SkyblockModules.Group;
import pingplus.voicechat.client.gui.SkyblockModules.Info;
import pingplus.voicechat.client.gui.SkyblockModules.Key;
import pingplus.voicechat.client.gui.SkyblockModules.Module;
import pingplus.voicechat.client.gui.SkyblockModules.Option;
import pingplus.voicechat.client.gui.SkyblockModules.Picker;
import pingplus.voicechat.client.gui.SkyblockModules.Section;
import pingplus.voicechat.client.gui.SkyblockModules.Setting;
import pingplus.voicechat.client.gui.SkyblockModules.Slider;
import pingplus.voicechat.client.gui.SkyblockModules.Toggle;
import pingplus.voicechat.client.gui.glass.GlassButtonRenderer;
import pingplus.voicechat.client.gui.glass.GlassStyle;
import pingplus.voicechat.client.gui.glass.MenuBackground;
import pingplus.voicechat.client.hud.editor.HudEditorScreen;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The SkyBlock menu: a sidebar (modules by section, routes, command binds, documentation,
 * customize) and module cards; a card opens its settings page with grouped, described rows.
 * Drawn in the same small glass style as the Controller Center and scaled with the window.
 */
public final class SkyblockScreen extends Screen {
    private enum Page { MODULES, MODULE, ROUTES, BINDS, DOCS }

    private static final int SIDEBAR = 150, GAP = 8, TOP = 34, CARD_MIN = 190, CARD_H = 64;
    private static final int ROW_PAD = 10, CONTROL_SLIDER = 120, CHIP_H = 14;
    private static final int DROP_W = 230, DROP_ROW = 15, DROP_ROWS = 11;
    /** Three text colors only: names, descriptions, small headings. */
    private static final int NAME = GlassStyle.TEXT, DESC = 0xFFBDBDC6, LABEL = 0xFF8E8E98;

    // kept between openings
    private static Page lastPage = Page.MODULES;
    private static String lastModule;
    private static int lastScroll;

    private final Screen parent;
    private float uiScale = ClickGuiScreen.UI_SCALE;
    private int cw, ch;
    private int x0, y0, winW, winH;
    private float opacity;
    private final long opened = System.nanoTime();

    private Page page = lastPage;
    private Module module;
    private List<Group> groups = List.of();
    private int scroll, maxScroll;
    private final Map<Section, Integer> sectionY = new EnumMap<>(Section.class);

    private EditBox search;
    private final Map<Picker, EditBox> listBoxes = new IdentityHashMap<>();
    private final List<EditBox> visibleBoxes = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable click, IntConsumer drag) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> dropHits = new ArrayList<>();
    private Hit dragging;
    private int mouseX, mouseY;

    // dropdown
    private Setting dropFor;
    private List<Option> dropOptions = List.of();
    private Section pendingSection;
    private int dropX, dropY, dropScroll, dropMax;
    private EditBox dropBox;
    private KeyMapping pendingKey;

    // clip of the scrolling content
    private int clipTop, clipBottom;

    public SkyblockScreen(Screen parent) {
        super(Component.literal("SkyBlock"));
        this.parent = parent;
        if (page == Page.MODULE) {
            module = lastModule == null ? null : SkyblockModules.byId(lastModule);
            if (module == null) {
                page = Page.MODULES;
            } else {
                groups = module.groups();
            }
        }
        scroll = lastScroll;
    }

    @Override
    protected void init() {
        // snapped so that text is pixel-exact (sharp font, see GlassStyle)
        uiScale = GlassStyle.sharpScale(ClickGuiScreen.UI_SCALE * ClickGuiLayout.scaleFactor(minecraft));
        cw = (int) (width / uiScale);
        ch = (int) (height / uiScale);
        winW = Math.min(cw - 40, 1180);
        winH = Math.min(ch - 40, 720);
        x0 = (cw - winW) / 2;
        y0 = (ch - winH) / 2;
        String query = search == null ? "" : search.getValue();
        search = box(200, "Search");
        search.setValue(query);
        search.setResponder(text -> {
            scroll = 0;
        });
        dropBox = box(DROP_W - 16, "Search");
        dropBox.setResponder(text -> dropScroll = 0);
        listBoxes.clear();
    }

    // ------------------------------------------------------------------ helpers

    private EditBox box(int w, String hint) {
        EditBox box = new EditBox(font, 0, 0, w, 14, Component.literal(hint)) {
            @Override
            public boolean keyPressed(KeyEvent event) {
                if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && onEnter(this)) {
                    return true;
                }
                return super.keyPressed(event);
            }
        };
        box.addFormatter((text, index) -> net.minecraft.util.FormattedCharSequence.forward(text, GlassStyle.font()));
        box.setBordered(false);
        box.setTextShadow(false);
        box.setTextColor(GlassStyle.TEXT);
        box.setMaxLength(64);
        box.setHint(GlassStyle.label(hint));
        addWidget(box);
        return box;
    }

    /** Enter in a text box: adds the typed name to its list. */
    private boolean onEnter(EditBox box) {
        String value = box.getValue().trim();
        if (box == dropBox && dropFor instanceof Picker picker) {
            if (!value.isEmpty() && picker.add() != null) {
                addTo(picker, value);
                box.setValue("");
            }
            return true;
        }
        for (Map.Entry<Picker, EditBox> entry : listBoxes.entrySet()) {
            if (entry.getValue() == box) {
                if (!value.isEmpty() && entry.getKey().add() != null) {
                    addTo(entry.getKey(), value);
                    box.setValue("");
                }
                return true;
            }
        }
        return false;
    }

    private void addTo(Picker picker, String value) {
        boolean added = picker.add().apply(value);
        picker.save().run();
        if (!added) {
            com.coflnet.ahflipper.AhFlipperMod.message("§e[" + picker.name() + "] §f" + value + " §7is already in the list");
        }
    }

    private void removeFrom(Picker picker, String value) {
        if (picker.remove() != null) {
            picker.remove().apply(value);
            picker.save().run();
        }
    }

    private int color(int c) {
        return GlassStyle.alpha(c, opacity);
    }

    /** True while drawing the scrolling content: text only where it fits completely. */
    private boolean clipping;

    private boolean cut(int y, int h) {
        return clipping && (y < clipTop || y + h > clipBottom);
    }

    private void text(GuiGraphicsExtractor g, String s, int x, int y, int c) {
        if (cut(y, 9)) {
            return;
        }
        g.text(font, GlassStyle.label(s), x, y, color(c), false);
    }

    private int textWidth(String s) {
        return font.width(GlassStyle.label(s));
    }

    private void line(GuiGraphicsExtractor g, String s, int x, int y, int maxWidth, int c) {
        if (cut(y, 9)) {
            return;
        }
        GlassStyle.line(g, s, x, y, maxWidth, color(c));
    }

    /** Word wrap with the UI font. */
    private List<String> wrap(String s, int width) {
        List<String> lines = new ArrayList<>();
        if (s == null || s.isBlank()) {
            return lines;
        }
        StringBuilder current = new StringBuilder();
        for (String word : s.split(" ")) {
            String next = current.isEmpty() ? word : current + " " + word;
            if (textWidth(next) > width && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(next);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        GlassButtonRenderer.control(g, x, y, w, h, color(c));
    }

    /**
     * Liquid-glass panel, the same material as the Controller Center's categories. (Rounded
     * boxes rasterized row by row overlapped at this scale: dark and light stripes.)
     */
    private void glass(GuiGraphicsExtractor g, int x, int y, int w, int h, float light) {
        GlassStyle.surface(g, x, y, w, h, opacity, light);
    }

    /** Glass control (buttons, hover rows, chips), as in the Controller Center. */
    private void control(GuiGraphicsExtractor g, int x, int y, int w, int h, float strength) {
        GlassButtonRenderer.control(g, x, y, w, h, GlassStyle.alpha(0xFF858585, opacity * strength));
    }

    private void icon(GuiGraphicsExtractor g, net.minecraft.world.item.ItemStack stack, int x, int y, float scale) {
        if (!cut(y, 16)) {
            com.coflnet.gui.RenderUtils.drawItemStack(g, stack, x, y, scale);
        }
    }

    /** The mob's face, cut from its vanilla skin (pixel art like a player head). */
    private void head(GuiGraphicsExtractor g, String mob, int x, int y, int size) {
        if (cut(y, size)) {
            return;
        }
        SkyblockMobs.Face f = SkyblockMobs.face(mob);
        float k = size / (float) Math.max(f.w(), f.h());
        int dw = Math.max(1, Math.round(f.w() * k)), dh = Math.max(1, Math.round(f.h() * k));
        int dx = x + (size - dw) / 2, dy = y + (size - dh) / 2;
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, f.texture(), dx, dy, (float) f.u(), (float) f.v(),
                dw, dh, f.w(), f.h(), f.texW(), f.texH());
        if (f.overlay() != null) {
            g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, f.overlay(), dx, dy, (float) f.u(), (float) f.v(),
                    dw, dh, f.w(), f.h(), f.texW(), f.texH());
        }
    }

    private void separator(GuiGraphicsExtractor g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, color(0x1FFFFFFF));
    }

    /** Small gear icon (same shape as the Controller Center's option gears). */
    private void gear(GuiGraphicsExtractor g, int cx, int cy, int c) {
        int col = color(c);
        for (int y = -6; y <= 6; y++) {
            for (int x = -6; x <= 6; x++) {
                double radius = Math.hypot(x, y);
                double edge = 4.7 + 1.2 * Math.max(0, Math.cos(Math.atan2(y, x) * 8));
                if (radius >= 2.1 && radius <= edge) {
                    g.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, col);
                }
            }
        }
    }

    private boolean hovered(int x, int y, int w, int h) {
        return dropFor == null && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private void hit(int x, int y, int w, int h, Runnable click) {
        hits.add(new Hit(x, y, w, h, click, null));
    }

    /** A hit inside the scrolling content: clipped to the visible part. */
    private void contentHit(int x, int y, int w, int h, Runnable click, IntConsumer drag) {
        int top = Math.max(y, clipTop), bottom = Math.min(y + h, clipBottom);
        if (bottom > top) {
            hits.add(new Hit(x, top, w, bottom - top, click, drag));
        }
    }

    private String query() {
        return search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String query, String... texts) {
        if (query.isEmpty()) {
            return true;
        }
        for (String t : texts) {
            if (t != null && t.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }

    private boolean moduleMatches(Module m, String query) {
        if (matches(query, m.name(), m.description(), m.section().title)) {
            return true;
        }
        for (Group group : m.groups()) {
            for (Setting s : group.settings()) {
                if (matches(query, s.name(), s.description())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void open(Page target) {
        closeDropdown();
        page = target;
        scroll = 0;
        if (target != Page.MODULE) {
            module = null;
            groups = List.of();
        }
        clearListBoxes();
    }

    private void openModule(Module m) {
        open(Page.MODULE);
        module = m;
        groups = m.groups();
        search.setValue("");
    }

    private void clearListBoxes() {
        for (EditBox box : listBoxes.values()) {
            removeWidget(box);
        }
        listBoxes.clear();
    }

    // ------------------------------------------------------------------ render

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float dt) {
        if (minecraft.level == null) {
            MenuBackground.drawWallpaper(g);
        }
        g.fill(0, 0, width, height, GlassStyle.alpha(0x50000000, opacity));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        opacity = Math.clamp((System.nanoTime() - opened) / 200_000_000f, 0, 1);
        g.pose().pushMatrix();
        g.pose().scale(uiScale);
        GlassStyle.textScale((float) minecraft.getWindow().getGuiScale() * uiScale);
        mouseX = (int) (mx / uiScale);
        mouseY = (int) (my / uiScale);
        hits.clear();
        visibleBoxes.clear();
        if (module == null && page == Page.MODULE) {
            page = Page.MODULES;
        }
        if (pendingSection != null && page == Page.MODULES && sectionY.containsKey(pendingSection) && modulesDrawn) {
            scroll = Math.clamp(sectionY.get(pendingSection), 0, Math.max(0, maxScroll));
            pendingSection = null;
        }
        modulesDrawn = false;

        // glass panels
        GlassStyle.surface(g, x0, y0, SIDEBAR, winH, opacity, .16f);
        GlassStyle.surface(g, x0 + SIDEBAR + GAP, y0, winW - SIDEBAR - GAP, winH, opacity, .12f);

        int mainX = x0 + SIDEBAR + GAP, mainW = winW - SIDEBAR - GAP;
        clipTop = y0 + TOP + 4;
        clipBottom = y0 + winH - 6;
        for (int pass = 0; pass < 3; pass++) {
            g.nextStratum();
            sidebar(g, pass);
            topBar(g, pass, mainX, mainW);
            g.enableScissor(mainX + 2, clipTop, mainX + mainW - 2, clipBottom);
            clipping = true;
            int contentX = mainX + 14, contentW = mainW - 28;
            int y = clipTop + 6 - scroll;
            int end = switch (page) {
                case MODULES -> modules(g, pass, contentX, y, contentW);
                case MODULE -> modulePage(g, pass, contentX, y, contentW);
                case ROUTES -> placeholder(g, pass, contentX, y, contentW, "Routes",
                        "Nothing here yet. Saved walking routes for the bots will show up here.");
                case BINDS -> placeholder(g, pass, contentX, y, contentW, "Command Binds",
                        "Nothing here yet. Chat commands on a key will be set up here.");
                case DOCS -> docs(g, pass, contentX, y, contentW);
            };
            clipping = false;
            g.disableScissor();
            if (pass == 0) {
                maxScroll = Math.max(0, end + scroll - (clipBottom - 8));
                scroll = Math.clamp(scroll, 0, maxScroll);
            }
        }
        // scrollbar
        if (maxScroll > 0) {
            int track = clipBottom - clipTop - 8;
            int thumb = Math.max(16, track * track / (track + maxScroll));
            int ty = clipTop + 4 + (track - thumb) * scroll / maxScroll;
            rect(g, mainX + mainW - 6, ty, 2, thumb, GlassStyle.alpha(GlassStyle.MUTED, .6f));
        }
        // text boxes (their own text on top)
        g.nextStratum();
        for (EditBox box : visibleBoxes) {
            box.extractRenderState(g, mouseX, mouseY, dt);
        }
        for (EditBox box : listBoxes.values()) {
            box.visible = visibleBoxes.contains(box);
        }
        search.visible = true;
        dropdown(g, dt);
        g.pose().popMatrix();
        GlassStyle.textScale(0);
    }

    private void sidebar(GuiGraphicsExtractor g, int pass) {
        int x = x0 + 10, w = SIDEBAR - 20;
        int y = y0 + 12;
        if (pass == 1) {
            text(g, "SKYBLOCK", x + 2, y + 2, NAME);
        }
        y += 24;
        y = navLabel(g, pass, "MAIN", x, y);
        y = navItem(g, pass, "Modules", x, y, w, page == Page.MODULES || page == Page.MODULE, 0, () -> open(Page.MODULES));
        Section active = activeSection();
        for (Section s : Section.values()) {
            final Section section = s;
            boolean on = page == Page.MODULES && section == active;
            y = navItem(g, pass, s.title, x, y, w, on, 14, () -> {
                if (page != Page.MODULES || !search.getValue().isEmpty()) {
                    open(Page.MODULES);
                    search.setValue("");
                    pendingSection = section; // positions are known after the next frame
                } else {
                    Integer target = sectionY.get(section);
                    scroll = target == null ? 0 : Math.clamp(target, 0, Math.max(0, maxScroll));
                }
            });
        }
        y += 4;
        y = navItem(g, pass, "Routes", x, y, w, page == Page.ROUTES, 0, () -> open(Page.ROUTES));
        y = navItem(g, pass, "Command Binds", x, y, w, page == Page.BINDS, 0, () -> open(Page.BINDS));
        y = navItem(g, pass, "Documentation", x, y, w, page == Page.DOCS, 0, () -> open(Page.DOCS));
        y += 10;
        y = navLabel(g, pass, "OPTIONS", x, y);
        navItem(g, pass, "Customize", x, y, w, false, 0, () -> minecraft.gui.setScreen(new HudEditorScreen().withParent(this)));
        if (pass == 1) {
            boolean premium = com.coflnet.ahflipper.PriorityAccess.isLocalPriority();
            line(g, premium ? "Premium" : "Standard", x + 2, y0 + winH - 28, w, premium ? GlassStyle.STATUS : DESC);
            text(g, "ESC  /  BACK", x + 2, y0 + winH - 16, LABEL);
        }
    }

    private int navLabel(GuiGraphicsExtractor g, int pass, String label, int x, int y) {
        if (pass == 1) {
            text(g, label, x + 2, y, LABEL);
        }
        return y + 14;
    }

    private int navItem(GuiGraphicsExtractor g, int pass, String label, int x, int y, int w, boolean selected,
                        int indent, Runnable click) {
        int h = indent > 0 ? 15 : 18;
        boolean hover = hovered(x, y, w, h);
        if (pass == 0 && (selected || hover)) {
            control(g, x, y, w, h, selected ? .62f : .38f);
            if (selected && indent > 0) {
                g.fill(x + 1, y + 3, x + 3, y + h - 3, color(0xFFE8E8E8));
            }
        }
        if (pass == 1) {
            text(g, label, x + 7 + indent, y + (h - 8) / 2, selected ? NAME : DESC);
            hit(x, y, w, h, click);
        }
        return y + h + 1;
    }

    private Section activeSection() {
        Section best = Section.GENERAL;
        for (Section s : Section.values()) {
            Integer y = sectionY.get(s);
            if (y != null && y <= scroll + 20) {
                best = s;
            }
        }
        return best;
    }

    private void topBar(GuiGraphicsExtractor g, int pass, int mainX, int mainW) {
        int y = y0 + 10;
        String title = switch (page) {
            case MODULES -> "Modules";
            case MODULE -> module.name();
            case ROUTES -> "Routes";
            case BINDS -> "Command Binds";
            case DOCS -> "Documentation";
        };
        int tx = mainX + 14;
        if (page == Page.MODULE) {
            boolean hover = hovered(tx, y - 3, 16, 15);
            if (pass == 0 && hover) {
                control(g, tx, y - 3, 16, 15, .5f);
            }
            if (pass == 1) {
                text(g, "<", tx + 5, y, NAME);
                hit(tx, y - 3, 16, 15, () -> open(Page.MODULES));
            }
            tx += 22;
        }
        if (pass == 1) {
            text(g, title, tx, y, NAME);
        }
        // search box
        int sw = 200, sx = mainX + mainW - sw - 40, sy = y - 3;
        if (pass == 0) {
            GlassButtonRenderer.control(g, sx - 5, sy - 1, sw + 10, 17,
                    color(search.isFocused() ? 0xFF555555 : 0xFF303030));
        }
        if (pass == 1) {
            search.setPosition(sx, sy + 3);
            visibleBoxes.add(search);
            // close
            boolean hover = hovered(mainX + mainW - 26, y - 3, 16, 15);
            text(g, "x", mainX + mainW - 21, y, hover ? NAME : LABEL);
            hit(mainX + mainW - 26, y - 3, 16, 15, this::onClose);
        }
        if (pass == 0) {
            separator(g, mainX + 14, y0 + TOP - 2, mainW - 28);
        }
    }

    // ------------------------------------------------------------------ modules grid

    private boolean modulesDrawn;

    private int modules(GuiGraphicsExtractor g, int pass, int x, int y, int w) {
        modulesDrawn = true;
        String query = query();
        int columns = Math.max(1, (w + GAP) / (CARD_MIN + GAP));
        int cardW = (w - (columns - 1) * GAP) / columns;
        boolean any = false;
        int lastStart = y;
        for (Section section : Section.values()) {
            List<Module> list = SkyblockModules.in(section).stream().filter(m -> moduleMatches(m, query)).toList();
            if (!query.isEmpty() && list.isEmpty()) {
                continue;
            }
            any = true;
            lastStart = y;
            if (pass == 0) {
                sectionY.put(section, y + scroll - (clipTop + 6));
            }
            // header
            if (pass == 1) {
                text(g, section.title, x, y + 4, NAME);
            }
            if (pass == 0) {
                int lx = x + textWidth(section.title) + 8;
                separator(g, lx, y + 8, Math.max(0, x + w - lx));
            }
            y += 20;
            if (list.isEmpty()) {
                if (pass == 0) {
                    glass(g, x, y, cardW, 30, .04f);
                }
                if (pass == 1) {
                    text(g, "Nothing here yet", x + 10, y + 11, LABEL);
                }
                y += 30 + GAP + 6;
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                int col = i % columns, row = i / columns;
                card(g, pass, list.get(i), x + col * (cardW + GAP), y + row * (CARD_H + GAP), cardW);
            }
            y += ((list.size() + columns - 1) / columns) * (CARD_H + GAP) + 6;
        }
        if (!any && pass == 1) {
            text(g, "No module matches \"" + search.getValue() + "\"", x, y + 4, DESC);
            y += 20;
        }
        // room below the last section, so every section (also the last) can be scrolled to the top
        return y + Math.max(0, (clipBottom - clipTop - 14) - (y - lastStart));
    }

    private void card(GuiGraphicsExtractor g, int pass, Module m, int x, int y, int w) {
        boolean hover = hovered(x, y, w, CARD_H) && mouseY >= clipTop && mouseY < clipBottom;
        boolean on = m.toggleable() && m.enabled().getAsBoolean();
        if (pass == 0) {
            glass(g, x, y, w, CARD_H, hover ? .42f : .12f);
        }
        int px = x + w - 36, py = y + 9;
        if (pass == 1) {
            icon(g, m.icon().get(), x + 9, y + 7, 1f);
            line(g, m.name(), x + 31, y + 11, w - 31 - (m.toggleable() ? 44 : 12), NAME);
            List<String> desc = wrap(m.description(), w - 40);
            for (int i = 0; i < Math.min(2, desc.size()); i++) {
                line(g, desc.get(i), x + 10, y + 30 + i * 10, w - 40, DESC);
            }
            gear(g, x + w - 15, y + CARD_H - 13, hover ? NAME : LABEL);
            contentHit(x, y, w, CARD_H, () -> openModule(m), null);
            if (m.toggleable()) {
                pill(g, px, py, on);
                contentHit(px - 3, py - 3, 32, 19, m.toggle(), null);
            }
        }
        if (pass == 2 && m.toggleable()) {
            knob(g, px, py, on);
        }
    }

    private void pill(GuiGraphicsExtractor g, int x, int y, boolean on) {
        rect(g, x, y, 26, 13, on ? 0xFF9FD8B4 : 0xFF3A3A40);
    }

    private void knob(GuiGraphicsExtractor g, int x, int y, boolean on) {
        rect(g, x + 2 + (on ? 13 : 0), y + 2, 9, 9, 0xFFF3F3F3);
    }

    // ------------------------------------------------------------------ module page

    private int modulePage(GuiGraphicsExtractor g, int pass, int x, int y, int w) {
        Module m = module;
        // header card
        int headerH = 40;
        if (pass == 0) {
            glass(g, x, y, w, headerH, .14f);
        }
        if (pass == 1) {
            icon(g, m.icon().get(), x + 10, y + 12, 1f);
            text(g, m.name(), x + 34, y + 9, NAME);
            line(g, m.description(), x + 34, y + 22, w - 34 - 90, DESC);
        }
        if (m.toggleable()) {
            boolean on = m.enabled().getAsBoolean();
            int px = x + w - 38, py = y + 14;
            if (pass == 1) {
                text(g, on ? "On" : "Off", px - textWidth(on ? "On" : "Off") - 6, py + 3, on ? GlassStyle.STATUS : DESC);
                pill(g, px, py, on);
                contentHit(px - 3, py - 3, 32, 19, m.toggle(), null);
            }
            if (pass == 2) {
                knob(g, px, py, on);
            }
        }
        y += headerH + 12;
        String query = query();
        for (Group group : groups) {
            List<Setting> rows = group.settings().stream().filter(s -> matches(query, s.name(), s.description())).toList();
            if (rows.isEmpty()) {
                continue;
            }
            if (pass == 1) {
                text(g, group.title().toUpperCase(Locale.ROOT), x + 2, y, LABEL);
            }
            y += 13;
            int top = y;
            int height = 0;
            for (Setting s : rows) {
                height += rowHeight(s, w);
            }
            if (pass == 0) {
                glass(g, x, top, w, height, .08f);
            }
            int ry = top;
            for (int i = 0; i < rows.size(); i++) {
                Setting s = rows.get(i);
                int rh = rowHeight(s, w);
                if (pass == 0 && i > 0) {
                    separator(g, x + ROW_PAD, ry, w - 2 * ROW_PAD);
                }
                row(g, pass, s, x, ry, w, rh);
                ry += rh;
            }
            y = top + height + 14;
        }
        if (y == clipTop + 6 - scroll + headerH + 12 && pass == 1) {
            text(g, "No setting matches \"" + search.getValue() + "\"", x + 2, y, DESC);
            y += 16;
        }
        return y;
    }

    private int controlWidth(Setting s) {
        return switch (s) {
            case Toggle t -> 26;
            case Slider sl -> CONTROL_SLIDER + 60;
            case Choice c -> 150;
            case Picker p -> 150;
            case Action a -> Math.max(70, textWidth(a.button()) + 24);
            case Info i -> 160;
            case Key k -> 100;
        };
    }

    private int baseHeight(Setting s, int w) {
        int textW = w - 2 * ROW_PAD - controlWidth(s) - 20;
        int lines = Math.max(1, wrap(s.description(), textW).size());
        return Math.max(30, 10 + 10 + lines * 10 + 6);
    }

    private int rowHeight(Setting s, int w) {
        int h = baseHeight(s, w);
        if (s instanceof Picker p) {
            h += chipsHeight(p, w - 2 * ROW_PAD) + 8;
        }
        return h;
    }

    private int chipWidth(Picker p, String item) {
        return textWidth(item) + 22 + (p.heads() ? 13 : 0);
    }

    private int chipsHeight(Picker p, int w) {
        List<String> items = p.selected().get();
        if (items.isEmpty()) {
            return CHIP_H;
        }
        int x = 0, lines = 1;
        for (String item : items) {
            int cw = chipWidth(p, item);
            if (x > 0 && x + cw > w) {
                lines++;
                x = 0;
            }
            x += cw + 4;
        }
        return lines * (CHIP_H + 4) - 4;
    }

    private void row(GuiGraphicsExtractor g, int pass, Setting s, int x, int y, int w, int h) {
        int cwid = controlWidth(s);
        int textW = w - 2 * ROW_PAD - cwid - 20;
        int cx = x + w - ROW_PAD - cwid;
        int cy = y + 8;
        if (pass == 1) {
            text(g, s.name(), x + ROW_PAD, y + 8, NAME);
            List<String> desc = wrap(s.description(), textW);
            for (int i = 0; i < desc.size(); i++) {
                text(g, desc.get(i), x + ROW_PAD, y + 20 + i * 10, DESC);
            }
        }
        switch (s) {
            case Toggle t -> {
                boolean on = t.get().getAsBoolean();
                if (pass == 1) {
                    pill(g, cx, cy, on);
                    contentHit(x, y, w, Math.min(h, 30), t.flip(), null);
                }
                if (pass == 2) {
                    knob(g, cx, cy, on);
                }
            }
            case Slider sl -> slider(g, pass, sl, cx, cy, cwid);
            case Choice c -> button(g, pass, c.get().get() + "  v", cx, cy - 2, cwid, () -> openDropdown(c, cx, cy + 15));
            case Picker p -> picker(g, pass, p, x, y, w, cx, cy, cwid);
            case Action a -> button(g, pass, a.button(), cx, cy - 2, cwid, a.run());
            case Info i -> {
                if (pass == 1) {
                    String value = i.text().get();
                    line(g, value, Math.max(cx, x + w - ROW_PAD - textWidth(value)), cy + 1, cwid, i.color().getAsInt());
                }
            }
            case Key k -> {
                KeyMapping key = k.key().get();
                String label = key == null ? "-" : key == pendingKey ? "press a key..."
                        : key.isUnbound() ? "none" : key.getTranslatedKeyMessage().getString();
                button(g, pass, label, cx, cy - 2, cwid, () -> pendingKey = key);
            }
        }
    }

    private void button(GuiGraphicsExtractor g, int pass, String label, int x, int y, int w, Runnable click) {
        boolean hover = hovered(x, y, w, 16);
        if (pass == 1) {
            control(g, x, y, w, 16, hover ? .85f : .5f);
            line(g, label, x + 8, y + 4, w - 16, NAME);
            contentHit(x, y, w, 16, click, null);
        }
    }

    private void slider(GuiGraphicsExtractor g, int pass, Slider s, int x, int y, int w) {
        int trackX = x + w - CONTROL_SLIDER, trackW = CONTROL_SLIDER, ty = y + 5;
        double value = s.get().getAsDouble();
        double t = Math.clamp((value - s.min()) / (s.max() - s.min()), 0, 1);
        if (pass == 1) {
            String shown = format(value, s.step(), s.unit());
            text(g, shown, trackX - 8 - textWidth(shown), y + 2, DESC);
            rect(g, trackX, ty, trackW, 3, 0xFF55555C);
            rect(g, trackX, ty, Math.max(1, (int) (trackW * t)), 3, GlassStyle.ACCENT);
            contentHit(trackX - 4, y - 2, trackW + 8, 16, null, mx -> {
                double raw = s.min() + Math.clamp((mx - trackX) / (double) trackW, 0, 1) * (s.max() - s.min());
                double snapped = Math.clamp(s.min() + Math.round((raw - s.min()) / s.step()) * s.step(), s.min(), s.max());
                s.set().accept(snapped);
            });
        }
        if (pass == 2) {
            rect(g, trackX + (int) Math.round((trackW - 8) * t), ty - 3, 8, 9, GlassStyle.TEXT);
        }
    }

    private static String format(double value, double step, String unit) {
        String number = step >= 1 ? String.valueOf(Math.round(value))
                : step >= 0.1 ? String.format(Locale.ROOT, "%.1f", value) : String.format(Locale.ROOT, "%.2f", value);
        if (unit == null || unit.isEmpty()) {
            return number;
        }
        return unit.equals("%") || unit.startsWith("°") ? number + unit : number + " " + unit;
    }

    private void picker(GuiGraphicsExtractor g, int pass, Picker p, int x, int y, int w, int cx, int cy, int cwid) {
        List<String> items = p.selected().get();
        if (p.hasOptions()) {
            button(g, pass, "Select", cx, cy - 2, cwid, () -> openDropdown(p, cx + cwid - DROP_W, cy + 15));
        } else if (p.add() != null) {
            if (pass == 1) {
                GlassButtonRenderer.control(g, cx, cy - 2, cwid, 16, color(0xFF303030));
                EditBox box = listBoxes.computeIfAbsent(p, key -> box(cwid - 12, p.hint()));
                box.setPosition(cx + 6, cy + 2);
                if (cy >= clipTop && cy + 14 <= clipBottom) {
                    visibleBoxes.add(box);
                }
            }
        }
        // chips
        int chipY = y + baseHeight(p, w) - 2;
        int chipX = x + ROW_PAD;
        if (items.isEmpty()) {
            if (pass == 1) {
                text(g, p.add() == null ? "Empty" : "None yet", chipX, chipY + 3, LABEL);
            }
            return;
        }
        int lineX = chipX;
        for (String item : items) {
            int cw = chipWidth(p, item);
            if (lineX > chipX && lineX + cw > x + w - ROW_PAD) {
                lineX = chipX;
                chipY += CHIP_H + 4;
            }
            boolean hover = hovered(lineX, chipY, cw, CHIP_H);
            if (pass == 1) {
                GlassButtonRenderer.control(g, lineX, chipY, cw, CHIP_H,
                        color(hover ? 0xFF7A4E4E : GlassStyle.alpha(0xFF858585, .55f)));
                int tx = lineX + 6;
                if (p.heads()) {
                    head(g, item, lineX + 4, chipY + 2, 10);
                    tx += 13;
                }
                text(g, item, tx, chipY + 3, NAME);
                text(g, "x", lineX + cw - 10, chipY + 3, hover ? 0xFFFF9B99 : LABEL);
                final String value = item;
                contentHit(lineX, chipY, cw, CHIP_H, () -> removeFrom(p, value), null);
            }
            lineX += cw + 4;
        }
    }

    // ------------------------------------------------------------------ dropdown

    private void openDropdown(Setting s, int x, int y) {
        dropFor = s;
        dropX = Math.clamp(x, x0 + SIDEBAR + GAP + 4, x0 + winW - DROP_W - 4);
        int height = 22 + DROP_ROWS * DROP_ROW + 6;
        dropY = y + height > y0 + winH - 4 ? Math.max(y0 + 4, y - height - 18) : y;
        dropScroll = 0;
        dropBox.setValue("");
        dropOptions = s instanceof Picker p && p.hasOptions() ? p.options().get() : List.of();
        if (s instanceof Picker) {
            setFocused(dropBox);
        }
    }

    private void closeDropdown() {
        dropFor = null;
        if (getFocused() == dropBox) {
            setFocused(null);
        }
    }

    private record DropRow(String value, String detail, boolean checked, Runnable click, boolean head) {
    }

    private List<DropRow> dropRows() {
        List<DropRow> rows = new ArrayList<>();
        if (dropFor instanceof Choice c) {
            String current = c.get().get();
            for (String option : c.options()) {
                rows.add(new DropRow(option, "", option.equals(current), () -> {
                    c.set().accept(option);
                    closeDropdown();
                }, false));
            }
        } else if (dropFor instanceof Picker p) {
            String q = dropBox.getValue().trim();
            String ql = q.toLowerCase(Locale.ROOT);
            List<String> selected = p.selected().get();
            boolean exact = false;
            for (Option option : dropOptions) {
                if (!matches(ql, option.value(), option.detail())) {
                    continue;
                }
                exact |= option.value().equalsIgnoreCase(q);
                boolean checked = selected.stream().anyMatch(option.value()::equalsIgnoreCase);
                rows.add(new DropRow(option.value(), option.detail(), checked, () -> {
                    if (checked) {
                        String stored = selected.stream().filter(option.value()::equalsIgnoreCase).findFirst().orElse(option.value());
                        removeFrom(p, stored);
                    } else {
                        addTo(p, option.value());
                    }
                }, p.heads()));
            }
            if (!q.isEmpty() && !exact && p.add() != null) {
                rows.add(0, new DropRow("+ Add \"" + q + "\"", "Enter", false, () -> {
                    addTo(p, q);
                    dropBox.setValue("");
                }, false));
            }
        }
        return rows;
    }

    private void dropdown(GuiGraphicsExtractor g, float dt) {
        dropHits.clear();
        if (dropFor == null) {
            return;
        }
        boolean searchable = dropFor instanceof Picker;
        List<DropRow> rows = dropRows();
        int listTop = dropY + (searchable ? 24 : 4);
        int visible = Math.min(DROP_ROWS, Math.max(1, rows.size()));
        int height = (listTop - dropY) + visible * DROP_ROW + 6;
        dropMax = Math.max(0, rows.size() - DROP_ROWS);
        dropScroll = Math.clamp(dropScroll, 0, dropMax);
        g.nextStratum();
        GlassStyle.surface(g, dropX, dropY, DROP_W, height, opacity, .25f);
        g.nextStratum();
        if (searchable) {
            GlassButtonRenderer.control(g, dropX + 6, dropY + 5, DROP_W - 12, 16,
                    color(dropBox.isFocused() ? 0xFF555555 : 0xFF303030));
        }
        if (rows.isEmpty()) {
            text(g, "Nothing found", dropX + 10, listTop + 4, DESC);
        }
        for (int i = 0; i < visible && i + dropScroll < rows.size(); i++) {
            DropRow row = rows.get(i + dropScroll);
            int ry = listTop + i * DROP_ROW;
            boolean hover = mouseX >= dropX + 4 && mouseX < dropX + DROP_W - 4 && mouseY >= ry && mouseY < ry + DROP_ROW;
            if (hover) {
                control(g, dropX + 4, ry, DROP_W - 8, DROP_ROW, .6f);
            }
            rect(g, dropX + 9, ry + 4, 7, 7, row.checked() ? 0xFF9FD8B4 : 0xFF4A4A52);
            int detailW = row.detail().isEmpty() ? 0 : Math.min(90, textWidth(row.detail()));
            int tx = dropX + 22;
            if (row.head()) {
                head(g, row.value(), tx, ry + 2, 11);
                tx += 15;
            }
            line(g, row.value(), tx, ry + 4, dropX + DROP_W - 12 - detailW - tx, row.checked() ? NAME : DESC);
            if (detailW > 0) {
                line(g, row.detail(), dropX + DROP_W - 10 - detailW, ry + 4, detailW, LABEL);
            }
            dropHits.add(new Hit(dropX + 4, ry, DROP_W - 8, DROP_ROW, row.click(), null));
        }
        if (dropMax > 0) {
            int track = visible * DROP_ROW;
            int thumb = Math.max(10, track * DROP_ROWS / rows.size());
            int ty = listTop + (track - thumb) * dropScroll / dropMax;
            rect(g, dropX + DROP_W - 5, ty, 2, thumb, GlassStyle.alpha(GlassStyle.MUTED, .6f));
        }
        if (searchable) {
            g.nextStratum();
            dropBox.setPosition(dropX + 12, dropY + 9);
            dropBox.visible = true;
            dropBox.extractRenderState(g, mouseX, mouseY, dt);
        }
    }

    private boolean insideDropdown(double x, double y) {
        if (dropFor == null) {
            return false;
        }
        boolean searchable = dropFor instanceof Picker;
        int rows = Math.min(DROP_ROWS, Math.max(1, dropRows().size()));
        int height = (searchable ? 24 : 4) + rows * DROP_ROW + 6;
        return x >= dropX && x < dropX + DROP_W && y >= dropY && y < dropY + height;
    }

    // ------------------------------------------------------------------ other pages

    private int placeholder(GuiGraphicsExtractor g, int pass, int x, int y, int w, String title, String body) {
        if (pass == 0) {
            glass(g, x, y, w, 50, .1f);
        }
        if (pass == 1) {
            text(g, title, x + 12, y + 12, NAME);
            line(g, body, x + 12, y + 28, w - 24, DESC);
        }
        return y + 60;
    }

    /** Documentation: every module and setting with its description, built from the menu itself. */
    private int docs(GuiGraphicsExtractor g, int pass, int x, int y, int w) {
        String query = query();
        for (Module m : SkyblockModules.MODULES) {
            if (!moduleMatches(m, query)) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            for (Group group : m.groups()) {
                for (Setting s : group.settings()) {
                    if (!(s instanceof Info) || !s.description().isBlank()) {
                        lines.add(s.name() + "  -  " + s.description());
                    }
                }
            }
            List<String> wrapped = new ArrayList<>();
            for (String l : lines) {
                wrapped.addAll(wrap(l, w - 40));
            }
            int h = 34 + wrapped.size() * 10 + 8;
            if (pass == 0) {
                glass(g, x, y, w, h, .1f);
            }
            if (pass == 1) {
                icon(g, m.icon().get(), x + 10, y + 8, 1f);
                text(g, m.name() + "  ·  " + m.section().title, x + 32, y + 9, NAME);
                line(g, m.description(), x + 32, y + 20, w - 44, DESC);
                for (int i = 0; i < wrapped.size(); i++) {
                    text(g, wrapped.get(i), x + 20, y + 36 + i * 10, DESC);
                }
                contentHit(x, y, w, 30, () -> openModule(m), null);
            }
            y += h + 8;
        }
        return y;
    }

    // ------------------------------------------------------------------ input

    private MouseButtonEvent logical(MouseButtonEvent event) {
        return new MouseButtonEvent(event.x() / uiScale, event.y() / uiScale, event.buttonInfo());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        MouseButtonEvent e = logical(event);
        double x = e.x(), y = e.y();
        if (pendingKey != null) {
            pendingKey = null;
        }
        if (dropFor != null) {
            if (insideDropdown(x, y)) {
                if (dropFor instanceof Picker && dropBox.isMouseOver(x, y)) {
                    dropBox.mouseClicked(e, twice);
                    setFocused(dropBox);
                    return true;
                }
                for (Hit hit : dropHits) {
                    if (hit.contains(x, y)) {
                        hit.click().run();
                        return true;
                    }
                }
                return true;
            }
            closeDropdown();
            return true;
        }
        for (EditBox box : visibleBoxes) {
            if (box.isMouseOver(x, y)) {
                box.mouseClicked(e, twice);
                setFocused(box);
                return true;
            }
        }
        if (getFocused() instanceof EditBox) {
            setFocused(null);
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(x, y)) {
                if (hit.drag() != null) {
                    dragging = hit;
                    hit.drag().accept((int) x);
                } else if (hit.click() != null) {
                    hit.click().run();
                }
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging != null) {
            dragging.drag().accept((int) (event.x() / uiScale));
            return true;
        }
        return super.mouseDragged(logical(event), dx / uiScale, dy / uiScale);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
            PlayerSettings.save();
            return true;
        }
        return super.mouseReleased(logical(event));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        double x = mx / uiScale, y = my / uiScale;
        if (dropFor != null) {
            if (insideDropdown(x, y)) {
                dropScroll = Math.clamp(dropScroll - (int) Math.signum(vertical) * 2, 0, dropMax);
            }
            return true;
        }
        scroll = Math.clamp(scroll - (int) (vertical * 28), 0, maxScroll);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (pendingKey != null) {
            if (e.key() == GLFW.GLFW_KEY_ESCAPE) {
                pendingKey.setKey(InputConstants.UNKNOWN);
            } else {
                pendingKey.setKey(InputConstants.getKey(e));
            }
            KeyMapping.resetMapping();
            try {
                minecraft.options.save();
            } catch (Exception ignored) {
            }
            pendingKey = null;
            return true;
        }
        if (e.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (dropFor != null) {
                closeDropdown();
            } else if (getFocused() instanceof EditBox box && !box.getValue().isEmpty() && box == search) {
                search.setValue("");
            } else if (page == Page.MODULE) {
                open(Page.MODULES);
            } else {
                onClose();
            }
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        PlayerSettings.save();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void removed() {
        lastPage = page;
        lastModule = module == null ? null : module.id();
        lastScroll = scroll;
        super.removed();
    }
}
