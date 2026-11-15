package pingplus.voicechat.client.gui.glass;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared palette and resource-independent geometry, also safe during a resource reload. */
public final class GlassStyle {
    /** Legacy single-resolution UI font (oversample 3). Prefer {@link #font()}. */
    public static final net.minecraft.network.chat.Style FONT = net.minecraft.network.chat.Style.EMPTY.withFont(
            new net.minecraft.network.chat.FontDescription.Resource(net.minecraft.resources.Identifier.fromNamespaceAndPath("voicechat", "ui")));

    /*
     * The UI font is a TTF that Minecraft pre-rasterizes at size x oversample. Shown at another
     * pixel size, rows of the raster are dropped unevenly: strokes look thicker/thinner and
     * lighter/darker from letter to letter. So the same font exists at several oversamples and
     * the one that maps 1:1 onto screen pixels is used (physical pixels per GUI unit).
     */
    private static final float[] OVERSAMPLES = {1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f, 3f, 3.5f, 4f, 5f, 6f};
    private static final net.minecraft.network.chat.Style[] STYLES = new net.minecraft.network.chat.Style[OVERSAMPLES.length];
    private static final net.minecraft.resources.Identifier[] IDS = new net.minecraft.resources.Identifier[OVERSAMPLES.length];
    static {
        for (int i = 0; i < OVERSAMPLES.length; i++) {
            IDS[i] = net.minecraft.resources.Identifier.fromNamespaceAndPath("voicechat", "ui_os" + Math.round(OVERSAMPLES[i] * 100));
            STYLES[i] = net.minecraft.network.chat.Style.EMPTY.withFont(new net.minecraft.network.chat.FontDescription.Resource(IDS[i]));
        }
    }
    /** Screen pixels per GUI unit of the text being drawn now; 0 = the window's GUI scale. */
    private static volatile float textScale;

    private static int nearest(float physical) {
        int best = 0;
        for (int i = 1; i < OVERSAMPLES.length; i++) {
            if (Math.abs(OVERSAMPLES[i] - physical) < Math.abs(OVERSAMPLES[best] - physical)) best = i;
        }
        return best;
    }
    private static float guiScale() {
        try {
            return (float) Math.max(1, net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale());
        } catch (Throwable ignored) {
            return 2f;
        }
    }
    /** The UI font style that is sharp at the current text scale. */
    public static net.minecraft.network.chat.Style font() {
        return STYLES[nearest(textScale > 0 ? textScale : guiScale())];
    }
    /** Font id for text drawn at {@code physical} screen pixels per GUI unit. */
    public static net.minecraft.resources.Identifier fontId(float physical) {
        return IDS[nearest(physical)];
    }
    /** Scaled screens: set while drawing (pose scale x GUI scale), reset with 0 afterwards. */
    public static void textScale(float physical) { textScale = physical; }
    /**
     * A menu scale close to {@code wanted} at which text is pixel-exact: GUI scale x menu scale
     * lands on one of the font resolutions.
     */
    public static float sharpScale(float wanted) {
        float gui = guiScale();
        return OVERSAMPLES[nearest(gui * wanted)] / gui;
    }
    public static net.minecraft.network.chat.Component label(String text) { return net.minecraft.network.chat.Component.literal(text).withStyle(font()); }
    public static final int TEXT = 0xFFF4F4F4, MUTED = 0xFFD0D0D0, ACCENT = 0xFFE8E8E8, STATUS = 0xFFA4F0C1;
    public static int alpha(int color, float alpha) {
        return (Math.round((color >>> 24) * Math.clamp(alpha, 0, 1)) << 24) | (color & 0xFFFFFF);
    }
    public static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        r = (int)Math.round(r * GlassCornerSettings.getScale());
        roundExact(g,x,y,w,h,r,color);
    }
    /** A material mask with its shader's radius, without applying corner scale twice. */
    static void roundExact(GuiGraphicsExtractor g, int x, int y, int w, int h, float r, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        // Rasterize the fallback silhouette at physical-pixel resolution, including edge coverage.
        int scale = Math.max(1, (int)Math.ceil(net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale()));
        int sx=x*scale, sy=y*scale, sw=w*scale, sh=h*scale;
        double radius=Math.min(r,Math.min(w,h)/2.0)*scale;
        g.pose().pushMatrix();
        g.pose().scale(1f/scale);
        for(int row=0;row<sh;row++) {
            double dy=Math.max(0,Math.max(radius-row-.5,row+.5-(sh-radius)));
            double inset=radius-Math.sqrt(Math.max(0,radius*radius-dy*dy));
            int whole=(int)Math.ceil(inset);
            g.fill(sx+whole,sy+row,sx+sw-whole,sy+row+1,color);
            float coverage=(float)(whole-inset);
            if(coverage>0) {
                int edge=alpha(color,coverage);
                g.fill(sx+whole-1,sy+row,sx+whole,sy+row+1,edge);
                g.fill(sx+sw-whole,sy+row,sx+sw-whole+1,sy+row+1,edge);
            }
        }
        g.pose().popMatrix();
    }
    public static void surface(GuiGraphicsExtractor g, int x, int y, int w, int h, float opacity, float light) {
        surface(g, x, y, w, h, opacity, light, true);
    }
    public static void surface(GuiGraphicsExtractor g, int x, int y, int w, int h, float opacity, float light, boolean edges) {
        GlassButtonRenderer.drawHudRect(g, x, y, w, h,
                (Math.round(opacity * 255) << 24) | (Math.round(light * 255) << 16) | 0xFF00, false, edges);
    }
    /** Liquid glass chrome for HUD widgets. Call from HudEditor; feature code only draws content. */
    public static void widget(GuiGraphicsExtractor g, int w, int h, boolean editing, boolean selected) {
        g.nextStratum();
        surface(g, 0, 0, w, h, editing ? .94f : .88f, selected ? .28f : .12f);
        g.nextStratum();
    }
    public static void line(GuiGraphicsExtractor g, String text, int x, int y, int maxWidth, int color) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        String shown = text == null ? "" : text;
        if (font.width(label(shown)) > maxWidth) {
            // too long for the panel: cut and mark with an ellipsis
            int ellipsis = font.width(label("…"));
            while (!shown.isEmpty() && font.width(label(shown)) > maxWidth - ellipsis) {
                shown = shown.substring(0, shown.length() - 1);
            }
            shown = shown.stripTrailing() + "…";
        }
        g.text(font, label(shown), x, y, color, false);
    }
    private GlassStyle() {}
}
