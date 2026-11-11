// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.graphics.Color;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * The one visual style of the settings app and the Performance panel: day / night palette, radii, spacing, type
 * scale and a few drawing helpers (glass, card, accent switch, line icons). Plain Views and code, no XML, no
 * fonts of its own (the system font). Everything is drawn on demand: nothing here polls, runs a timer or animates
 * by itself, so it costs no battery.
 */
public final class Ui {
    private Ui() {}

    // ------------------------------------------------------------------ palette (cool-grey surfaces, one teal accent)

    /** All colours of one theme. App fields first, then the panel's glass fields (the panel sits over the wallpaper). */
    public static final class Pal {
        public final boolean night;
        public final int bg, card, card2, tx, tx2, line, line2, acc, accInk, accSoft, onAcc;
        public final int glass, glassBd, glassHi, swOff, swOffBd, seg, trk, field;
        public final int ok, warn, err;
        // the panel
        public final int sheet, sheetSolid, sheetBd, sheetHi, tile, tileBd, tileIco, dim, shadow;

        Pal(boolean night, int bg, int card, int card2, int tx, int tx2, int line, int line2, int acc, int accInk, int accSoft, int onAcc,
            int glass, int glassBd, int glassHi, int swOff, int swOffBd, int seg, int trk, int ok, int warn, int err,
            int sheet, int sheetSolid, int sheetBd, int sheetHi, int tile, int tileBd, int tileIco, int dim, int shadow) {
            this.night = night; this.bg = bg; this.card = card; this.card2 = card2; this.tx = tx; this.tx2 = tx2;
            this.line = line; this.line2 = line2; this.acc = acc; this.accInk = accInk; this.accSoft = accSoft; this.onAcc = onAcc;
            this.glass = glass; this.glassBd = glassBd; this.glassHi = glassHi; this.swOff = swOff; this.swOffBd = swOffBd;
            this.seg = seg; this.trk = trk; this.field = card2; this.ok = ok; this.warn = warn; this.err = err;
            this.sheet = sheet; this.sheetSolid = sheetSolid; this.sheetBd = sheetBd; this.sheetHi = sheetHi;
            this.tile = tile; this.tileBd = tileBd; this.tileIco = tileIco; this.dim = dim; this.shadow = shadow;
        }
    }

    public static final Pal LIGHT = new Pal(false,
            0xFFF1F3F6, 0xFFFFFFFF, 0xFFF1F3F6, 0xFF12161C, 0xFF4B5563, 0x1712161C, 0x5712161C,
            0xFF0B7A85, 0xFF08545C, 0xFFDDF2F4, 0xFFFFFFFF,
            0xADFFFFFF, 0xBFFFFFFF, 0xE6FFFFFF, 0xFFE6E9EE, 0xFF68727F, 0xFFE2E6EB, 0xFFB4BCC8,
            0xFF167A4A, 0xFFA85F00, 0xFFC42B2B,
            0x75FFFFFF, 0xF2F4F7FA, 0xCCFFFFFF, 0xF2FFFFFF, 0xA3FFFFFF, 0xD9FFFFFF, 0xCCFFFFFF, 0x3812161C, 0x331E3246);
    public static final Pal DARK = new Pal(true,
            0xFF0C0F14, 0xFF161A21, 0xFF1F2530, 0xFFEEF1F6, 0xFFA6AFBD, 0x14FFFFFF, 0x57FFFFFF,
            0xFF5CD0DB, 0xFF8EE4EC, 0xFF123A40, 0xFF031F23,
            0x94222934, 0x24FFFFFF, 0x2EFFFFFF, 0xFF222834, 0xFF8F99A8, 0xFF1E232C, 0xFF4A5463,
            0xFF6EE7A8, 0xFFFFC857, 0xFFFF7A70,
            0x800C111A, 0xF2161A21, 0x24FFFFFF, 0x33FFFFFF, 0x17FFFFFF, 0x29FFFFFF, 0x1EFFFFFF, 0x47FFFFFF, 0x80000000);

    /** The palette for a context's current day / night setting. */
    public static Pal pal(Context c) { return isNight(c) ? DARK : LIGHT; }

    public static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** The palette the Performance panel uses right now (hook side: one process, set when the panel opens and on a theme change). */
    public static volatile Pal cur = DARK;

    /** Re-reads day / night from the context into {@link #cur}; true when it changed. */
    public static boolean useTheme(Context c) {
        Pal p = pal(c);
        boolean changed = p != cur;
        cur = p;
        return changed;
    }

    /** The back chip and the status bar items sit over whatever the status bar shows, so they keep fixed colours. */
    public static final int CHIP_TINT = 0xFF3A3A3E, CHIP_FG = 0xFFFFFFFF, CHIP_INK = 0xFF1B1B1F, CHIP_PILL = 0x33FFFFFF;

    // ------------------------------------------------------------------ radii, spacing, type scale (dp / sp)

    public static final int R_CARD = 24, R_TILE = 26, R_SHEET = 38, R_ICON = 12, R_CHIP = 16, R_SEG = 24, R_FIELD = 12, R_BUTTON = 28;
    public static final int S1 = 4, S2 = 8, S3 = 12, S4 = 16, S5 = 24;
    public static final int ROW_H = 56, TAP = 48, NAV_H = 68, NAV_TAB_H = 56;
    public static final float T_TITLE = 28, T_PANEL = 24, T_SHEET = 22, T_BODY = 16, T_LABEL = 15, T_SECTION = 14, T_VALUE = 13, T_CAPTION = 12, T_TAB = 11;

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** System font in a weight (400 regular, 500 medium, 600 semibold, 700 bold). */
    public static Typeface font(int weight) { return Typeface.create(Typeface.DEFAULT, weight, false); }

    public static TextView text(Context c, CharSequence s, float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font(weight));
        return t;
    }

    // ------------------------------------------------------------------ drawables

    public static GradientDrawable round(int fill, int radiusPx, int strokePx, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    /** A solid content card. */
    public static Drawable card(Context c, Pal p) { return round(p.card, dp(c, R_CARD), 0, 0); }

    /**
     * The glass recipe: a translucent cool fill, a hairline border and a bright highlight along the top edge that fades
     * out. It is a plain drawable (no blur, no work per frame). Where the window itself can blur the scene behind
     * (the panel) that does the rest.
     */
    public static final class Glass extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), hi = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float radius, sw;
        private final int hiColor;

        public Glass(int fillColor, int borderColor, int highlight, float radiusPx, float strokePx) {
            radius = radiusPx; sw = strokePx; hiColor = highlight;
            fill.setStyle(Paint.Style.FILL); fill.setColor(fillColor);
            stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(strokePx); stroke.setColor(borderColor);
            hi.setStyle(Paint.Style.STROKE); hi.setStrokeWidth(strokePx);
        }

        public void setFill(int color) { fill.setColor(color); invalidateSelf(); }

        @Override protected void onBoundsChange(android.graphics.Rect b) {
            float h = Math.max(1, b.height());
            hi.setShader(new LinearGradient(0, b.top, 0, b.top + Math.min(h, radius * 1.6f + 8), hiColor, hiColor & 0x00FFFFFF, Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas c) {
            r.set(getBounds());
            r.inset(sw / 2f, sw / 2f);
            float rad = Math.max(0, radius - sw / 2f);
            c.drawRoundRect(r, rad, rad, fill);
            c.drawRoundRect(r, rad, rad, stroke);
            c.drawRoundRect(r, rad, rad, hi);
        }

        @Override public void getOutline(Outline o) { o.setRoundRect(getBounds(), radius); }
        @Override public void setAlpha(int a) { fill.setAlpha(a); stroke.setAlpha(a); hi.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Glass for the settings app (nav bar, info sheet): frosted fill plus the highlight border, no realtime blur. */
    public static Glass glass(Context c, Pal p, float radiusDp) {
        return new Glass(p.glass, p.glassBd, p.glassHi, dp(c, radiusDp), Math.max(1, dp(c, 1)));
    }

    /** Glass for the Performance panel's sheet and tiles; `fill` is p.sheet or p.tile (or a more opaque one when the window cannot blur). */
    public static Glass panelGlass(Context c, Pal p, int fill, int border, float radiusDp) {
        return new Glass(fill, border, p.sheetHi, dp(c, radiusDp), Math.max(1, dp(c, 1)));
    }

    public static RippleDrawable ripple(Pal p, Drawable content, Drawable mask) {
        return new RippleDrawable(ColorStateList.valueOf(p.accSoft), content, mask);
    }

    // ------------------------------------------------------------------ accent switch

    /** The spec's switch: 52 x 32 track in a 56 x 48 touch target. Off = outlined with a small dot; on = accent with a big thumb. */
    public static final class Switch extends View {
        public interface Listener { void onChanged(boolean checked); }
        private final Pal p;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rc = new RectF();
        private boolean checked;
        private Listener listener;

        public Switch(Context c, Pal p) {
            super(c);
            this.p = p;
            setClickable(true);
            super.setOnClickListener(v -> toggle());
        }

        public boolean isChecked() { return checked; }

        /** Sets the state without telling the listener. */
        public void setChecked(boolean v) { checked = v; invalidate(); }

        public void setOnChange(Listener l) { listener = l; }

        public void toggle() {
            checked = !checked;
            invalidate();
            if (listener != null) listener.onChanged(checked);
        }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(dp(getContext(), 56), dp(getContext(), 48)); }

        @Override protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f, cy = getHeight() / 2f, tw = 52 * d, th = 32 * d, bw = 2 * d;
            rc.set(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(checked ? p.acc : p.swOff);
            c.drawRoundRect(rc, th / 2, th / 2, paint);
            if (!checked) {
                rc.inset(bw / 2, bw / 2);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(bw);
                paint.setColor(p.swOffBd);
                c.drawRoundRect(rc, th / 2, th / 2, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(checked ? p.onAcc : p.swOffBd);
            float r = (checked ? 11 : 7) * d;
            c.drawCircle(checked ? cx + tw / 2 - 16 * d : cx - tw / 2 + 12 * d, cy, r, paint);
        }

        @Override public CharSequence getAccessibilityClassName() { return "android.widget.Switch"; }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setCheckable(true);
            info.setChecked(checked);
        }
    }

    // ------------------------------------------------------------------ line icons (vector paths, drawn in code)

    private static String circ(float cx, float cy, float r) {
        return "M" + (cx - r) + " " + cy + "a" + r + " " + r + " 0 1 0 " + 2 * r + " 0a" + r + " " + r + " 0 1 0 " + (-2 * r) + " 0z";
    }

    private static String rr(float x, float y, float w, float h, float r) {
        return "M" + (x + r) + " " + y + "H" + (x + w - r) + "a" + r + " " + r + " 0 0 1 " + r + " " + r + "V" + (y + h - r)
                + "a" + r + " " + r + " 0 0 1 " + (-r) + " " + r + "H" + (x + r) + "a" + r + " " + r + " 0 0 1 " + (-r) + " " + (-r)
                + "V" + (y + r) + "a" + r + " " + r + " 0 0 1 " + r + " " + (-r) + "z";
    }

    /** name -> segments separated by '|'; a segment starting with '*' is filled, the rest are stroked (24 x 24 grid, 1.75 stroke). */
    private static final Map<String, String> ICONS = new HashMap<>();
    static {
        ICONS.put("clock", circ(12, 12, 9) + "|M12 7v5l3.2 2");
        ICONS.put("temp", "M14 14.8V5a2 2 0 0 0-4 0v9.8a4 4 0 1 0 4 0z");
        ICONS.put("speed", "M8 19V5M4.5 8.5 8 5l3.5 3.5M16 5v14M12.5 15.5 16 19l3.5-3.5");
        ICONS.put("batt", rr(2.5f, 7, 16, 10, 2.5f) + "|M21.5 10.5v3");
        ICONS.put("info", circ(12, 12, 9) + "|M12 11v5.2M12 7.8v.01");
        ICONS.put("grip", "*" + circ(9, 6, 1.6f) + "|*" + circ(15, 6, 1.6f) + "|*" + circ(9, 12, 1.6f) + "|*" + circ(15, 12, 1.6f)
                + "|*" + circ(9, 18, 1.6f) + "|*" + circ(15, 18, 1.6f));
        ICONS.put("pin", "M9 4h6l-1 6 3 3H7l3-3z|M12 13v7");
        ICONS.put("chev", "M9.5 6l6 6-6 6");
        ICONS.put("chevd", "M6 9.5l6 6 6-6");
        ICONS.put("layout", rr(3, 4, 18, 6, 2) + "|" + rr(3, 14, 8, 6, 2) + "|" + rr(15, 14, 6, 6, 2));
        ICONS.put("panel", rr(3.5f, 3.5f, 7, 7, 2) + "|" + rr(13.5f, 3.5f, 7, 7, 2) + "|" + rr(3.5f, 13.5f, 7, 7, 2) + "|" + rr(13.5f, 13.5f, 7, 7, 2));
        ICONS.put("sys", "M4 7h9M17 7h3M4 17h3M11 17h9|" + circ(15, 7, 2) + "|" + circ(9, 17, 2));
        ICONS.put("reset", "M4.5 4.5v4h4M5.3 8.5A8 8 0 1 1 4 12");
        ICONS.put("hole", rr(4, 4, 16, 16, 4.5f) + "|" + circ(12, 9, 1.7f));
        ICONS.put("compact", "M4 12h16M8 8l-4 4 4 4M16 8l4 4-4 4");
        ICONS.put("check", "M5 12.5l4.5 4.5L19 7.5");
        ICONS.put("bolt", "M13 3 5.5 13.5H11L10 21l7.5-10.5H12z");
        ICONS.put("gauge", "M4.2 17a8.5 8.5 0 1 1 15.6 0M12 14l3.2-4.2");
        ICONS.put("leaf", "M5 19c0-9 5-14 15-14 0 10-5 15-13 15M5 19c3-5 6-8 10-10");
        ICONS.put("snow", "M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9");
        ICONS.put("mem", rr(3, 7, 18, 10, 2) + "|M7 7V4M12 7V4M17 7V4M7 17v3M12 17v3M17 17v3");
        ICONS.put("moon", "M20 14.5A8 8 0 1 1 9.5 4 6.5 6.5 0 0 0 20 14.5z");
        ICONS.put("cpu", rr(7, 7, 10, 10, 2) + "|M10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4");
        ICONS.put("sun", circ(12, 12, 4) + "|M12 2.5V5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8");
        ICONS.put("back", "M15 5l-7 7 7 7");
        ICONS.put("tap", "M9 11V5.5a1.5 1.5 0 0 1 3 0V11M12 11V9.5a1.5 1.5 0 0 1 3 0V11M15 11v-.5a1.5 1.5 0 0 1 3 0V15a6 6 0 0 1-6 6h-1a6 6 0 0 1-5-2.7L4.5 15a1.5 1.5 0 0 1 2.4-1.8L9 15.5V11");
        ICONS.put("bell", "M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15zM10 21h4");
        ICONS.put("wifi", "M2.5 9.5a14 14 0 0 1 19 0M5.5 13a9.5 9.5 0 0 1 13 0M8.8 16.4a5 5 0 0 1 6.4 0|*" + circ(12, 19.5f, 1.1f));
        ICONS.put("signal", "M5 19v-4M10 19v-8M15 19V8M20 19V4");
        ICONS.put("eye", "M2.5 12c2.5-4.5 6-6.5 9.5-6.5s7 2 9.5 6.5c-2.5 4.5-6 6.5-9.5 6.5s-7-2-9.5-6.5z|" + circ(12, 12, 3));
        ICONS.put("drop", "M12 3.5c3.5 4 6 7 6 10a6 6 0 0 1-12 0c0-3 2.5-6 6-10z");
        ICONS.put("text", "M4.5 18 9.5 6l5 12M6.3 14h6.4M17.5 10v8");
        ICONS.put("lock", rr(5, 10.5f, 14, 10, 2.5f) + "|M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5");
        ICONS.put("power", "M12 3v8M6.7 6.5a8 8 0 1 0 10.6 0");
        ICONS.put("shield", "M12 3l7.5 3v5.5c0 4.5-3.2 8-7.5 9.5-4.3-1.5-7.5-5-7.5-9.5V6z");
        ICONS.put("plus", "M12 5v14M5 12h14");
        ICONS.put("minus", "M5 12h14");
        ICONS.put("date", rr(3.5f, 5, 17, 15.5f, 3) + "|M3.5 10h17M8 3v4M16 3v4");
        ICONS.put("layers", "M12 3 3 8l9 5 9-5zM3 13l9 5 9-5");
        ICONS.put("trash", "M5 7h14M10 7V4.5h4V7M7 7l1 13h8l1-13M10.5 11v5M13.5 11v5");
        ICONS.put("pill", rr(3, 8, 18, 8, 4));
        ICONS.put("screen", rr(6, 3, 12, 18, 3) + "|M10.5 18h3");
        ICONS.put("spark", "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z");
        ICONS.put("apps", circ(7, 7, 2) + "|" + circ(17, 7, 2) + "|" + circ(7, 17, 2) + "|" + circ(17, 17, 2));
        ICONS.put("gear", circ(12, 12, 3) + "|M12 3v2.5M12 18.5V21M3 12h2.5M18.5 12H21M5.6 5.6l1.8 1.8M16.6 16.6l1.8 1.8M5.6 18.4l1.8-1.8M16.6 7.4l1.8-1.8");
        ICONS.put("help", circ(12, 12, 9) + "|M9.6 9.4a2.5 2.5 0 1 1 3.6 2.2c-.8.4-1.2 1-1.2 1.9M12 17v.01");
    }

    private static final Map<String, Object[]> PARSED = new HashMap<>();   // name -> {Path[], boolean[] filled}

    private static Object[] parsed(String name) {
        Object[] o = PARSED.get(name);
        if (o != null) return o;
        String spec = ICONS.get(name);
        if (spec == null) spec = ICONS.get("info");
        String[] segs = spec.split("\\|");
        Path[] ps = new Path[segs.length];
        boolean[] fill = new boolean[segs.length];
        for (int i = 0; i < segs.length; i++) {
            String s = segs[i];
            if (s.startsWith("*")) { fill[i] = true; s = s.substring(1); }
            ps[i] = parsePath(s);
        }
        o = new Object[]{ps, fill};
        PARSED.put(name, o);
        return o;
    }

    /** A minimal SVG path reader: M L H V C A Z, absolute and relative (arcs without rotation, which is all the icons use). */
    static Path parsePath(String d) {
        Path path = new Path();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z]|-?(?:\\d+\\.?\\d*|\\.\\d+)").matcher(d);
        java.util.List<String> tok = new java.util.ArrayList<>();
        while (m.find()) tok.add(m.group());
        int i = 0;
        char cmd = 'M';
        float x = 0, y = 0, sx = 0, sy = 0;
        while (i < tok.size()) {
            String t = tok.get(i);
            if (Character.isLetter(t.charAt(0))) { cmd = t.charAt(0); i++; if (cmd == 'z' || cmd == 'Z') { path.close(); x = sx; y = sy; continue; } }
            boolean rel = Character.isLowerCase(cmd);
            char u = Character.toUpperCase(cmd);
            if (i >= tok.size()) break;
            switch (u) {
                case 'M': { float nx = f(tok, i) + (rel ? x : 0), ny = f(tok, i + 1) + (rel ? y : 0); i += 2; path.moveTo(nx, ny); x = sx = nx; y = sy = ny; cmd = rel ? 'l' : 'L'; break; }
                case 'L': { float nx = f(tok, i) + (rel ? x : 0), ny = f(tok, i + 1) + (rel ? y : 0); i += 2; path.lineTo(nx, ny); x = nx; y = ny; break; }
                case 'H': { float nx = f(tok, i) + (rel ? x : 0); i++; path.lineTo(nx, y); x = nx; break; }
                case 'V': { float ny = f(tok, i) + (rel ? y : 0); i++; path.lineTo(x, ny); y = ny; break; }
                case 'C': {
                    float ox = rel ? x : 0, oy = rel ? y : 0;
                    float x1 = f(tok, i) + ox, y1 = f(tok, i + 1) + oy, x2 = f(tok, i + 2) + ox, y2 = f(tok, i + 3) + oy, nx = f(tok, i + 4) + ox, ny = f(tok, i + 5) + oy;
                    i += 6; path.cubicTo(x1, y1, x2, y2, nx, ny); x = nx; y = ny; break;
                }
                case 'A': {
                    float rx = Math.abs(f(tok, i)), ry = Math.abs(f(tok, i + 1));
                    boolean fa = f(tok, i + 3) != 0, fs = f(tok, i + 4) != 0;
                    float nx = f(tok, i + 5) + (rel ? x : 0), ny = f(tok, i + 6) + (rel ? y : 0);
                    i += 7;
                    arc(path, x, y, rx, ry, fa, fs, nx, ny);
                    x = nx; y = ny; break;
                }
                default: i = tok.size();
            }
        }
        return path;
    }

    private static float f(java.util.List<String> t, int i) { return i < t.size() ? Float.parseFloat(t.get(i)) : 0; }

    private static void arc(Path p, float x0, float y0, float rx, float ry, boolean fa, boolean fs, float x, float y) {
        if (rx == 0 || ry == 0 || (x0 == x && y0 == y)) { p.lineTo(x, y); return; }
        double dx = (x0 - x) / 2.0, dy = (y0 - y) / 2.0;
        double lam = dx * dx / ((double) rx * rx) + dy * dy / ((double) ry * ry);
        double rrx = rx, rry = ry;
        if (lam > 1) { double s = Math.sqrt(lam); rrx *= s; rry *= s; }
        double num = rrx * rrx * rry * rry - rrx * rrx * dy * dy - rry * rry * dx * dx;
        double den = rrx * rrx * dy * dy + rry * rry * dx * dx;
        double coef = (fa == fs ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * (rrx * dy / rry), cyp = coef * -(rry * dx / rrx);
        double cx = cxp + (x0 + x) / 2.0, cy = cyp + (y0 + y) / 2.0;
        double t1 = Math.atan2((dy - cyp) / rry, (dx - cxp) / rrx);
        double t2 = Math.atan2((-dy - cyp) / rry, (-dx - cxp) / rrx);
        double delta = t2 - t1;
        if (!fs && delta > 0) delta -= 2 * Math.PI;
        else if (fs && delta < 0) delta += 2 * Math.PI;
        p.arcTo(new RectF((float) (cx - rrx), (float) (cy - rry), (float) (cx + rrx), (float) (cy + rry)),
                (float) Math.toDegrees(t1), (float) Math.toDegrees(delta));
    }

    /** One line icon: scales with its bounds, takes its colour from {@link #setColor}. */
    public static final class IconDrawable extends Drawable {
        private final Path[] paths;
        private final boolean[] filled;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Matrix mx = new android.graphics.Matrix();
        private final Path tmp = new Path();

        IconDrawable(String name, int color) {
            Object[] o = parsed(name);
            paths = (Path[]) o[0];
            filled = (boolean[]) o[1];
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        public void setColor(int color) { paint.setColor(color); invalidateSelf(); }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / 24f;
            mx.setScale(s, s);
            mx.postTranslate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
            paint.setStrokeWidth(1.75f * s);
            for (int i = 0; i < paths.length; i++) {
                paths[i].transform(mx, tmp);
                paint.setStyle(filled[i] ? Paint.Style.FILL : Paint.Style.STROKE);
                c.drawPath(tmp, paint);
            }
        }

        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    public static IconDrawable iconDrawable(String name, int color) { return new IconDrawable(name, color); }

    public static ImageView icon(Context c, String name, int color, float sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(new IconDrawable(name, color));
        v.setLayoutParams(new FrameLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    /** The 36 dp rounded badge that leads a row: a soft accent square with a 20 dp icon. */
    public static View iconBadge(Context c, Pal p, String name) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(round(p.accSoft, dp(c, R_ICON), 0, 0));
        ImageView i = icon(c, name, p.accInk, 20);
        ((FrameLayout.LayoutParams) i.getLayoutParams()).gravity = Gravity.CENTER;
        f.addView(i);
        return f;
    }

    /** The info "i" button (48 dp target, 20 dp icon). */
    public static View infoButton(Context c, Pal p, String about, View.OnClickListener l) {
        return infoButton(c, p.tx2, about, l);
    }

    public static View infoButton(Context c, int color, String about, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(c);
        ImageView i = icon(c, "info", color, 20);
        ((FrameLayout.LayoutParams) i.getLayoutParams()).gravity = Gravity.CENTER;
        f.addView(i);
        f.setContentDescription("About " + about);
        f.setOnClickListener(l);
        f.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(c, TAP), dp(c, TAP)));
        return f;
    }

    // ------------------------------------------------------------------ styled dialogs (replace stock AlertDialog)

    /** A rounded card dialog: title, optional message, content view, accent text buttons. Nothing animates or polls. */
    public static final class Dlg {
        private final Context c; private final Pal p;
        private String title, msg; private View body;
        private String[] btnText = new String[3]; private View.OnClickListener[] btnClick = new View.OnClickListener[3];
        private boolean[] btnDismiss = new boolean[3];
        private android.app.Dialog dl;
        public Dlg(Context c, Pal p) { this.c = c; this.p = p; }
        public Dlg title(String t) { title = t; return this; }
        public Dlg message(String m) { msg = m; return this; }
        public Dlg view(View v) { body = v; return this; }
        /** slot 0 = negative (left), 1 = neutral, 2 = positive (right). */
        public Dlg button(int slot, String text, Runnable r) {
            btnText[slot] = text; btnDismiss[slot] = true;
            btnClick[slot] = r == null ? null : v -> r.run();
            return this;
        }
        public android.app.Dialog dialog() { return dl; }
        public void dismiss() { if (dl != null) dl.dismiss(); }
        public Dlg show() {
            dl = new android.app.Dialog(c);
            dl.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(0, dp(c, S5), 0, dp(c, S2));
            card.setBackground(round(p.card, dp(c, R_CARD + 4), 0, 0));
            if (title != null) {
                TextView t = text(c, title, 20, p.tx, 700);
                t.setPadding(dp(c, S5), 0, dp(c, S5), dp(c, S3));
                card.addView(t, new LinearLayout.LayoutParams(-1, -2));
            }
            if (msg != null) {
                TextView m = text(c, msg, T_BODY, p.tx2, 400);
                m.setLineSpacing(0, 1.2f);
                m.setPadding(dp(c, S5), 0, dp(c, S5), dp(c, S3));
                card.addView(m, new LinearLayout.LayoutParams(-1, -2));
            }
            if (body != null) {
                int maxH = Math.round(c.getResources().getDisplayMetrics().heightPixels * 0.6f);
                android.widget.ScrollView sv = new android.widget.ScrollView(c) {
                    @Override protected void onMeasure(int w, int h) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)); }
                };
                sv.setVerticalScrollBarEnabled(false);
                sv.addView(body);
                card.addView(sv, new LinearLayout.LayoutParams(-1, -2));
            }
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            row.setPadding(dp(c, S2), dp(c, S2), dp(c, S2), 0);
            int[] order = {1, 0, 2};
            for (int k : order) {
                if (btnText[k] == null) continue;
                final int kk = k;
                TextView b = text(c, btnText[k], 15, p.accInk, 700);
                b.setGravity(Gravity.CENTER);
                b.setMinHeight(dp(c, TAP));
                b.setPadding(dp(c, S4), 0, dp(c, S4), 0);
                b.setBackground(ripple(p, null, round(Color.WHITE, dp(c, R_BUTTON), 0, 0)));
                b.setOnClickListener(v -> { dl.dismiss(); if (btnClick[kk] != null) btnClick[kk].onClick(v); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(c, TAP));
                if (k == 1) lp.weight = 0;
                row.addView(b, lp);
            }
            if (row.getChildCount() > 0) card.addView(row, new LinearLayout.LayoutParams(-1, -2));
            FrameLayout wrap = new FrameLayout(c);
            wrap.setPadding(dp(c, S4), 0, dp(c, S4), 0);
            wrap.addView(card);
            dl.setContentView(wrap);
            android.view.Window w = dl.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setDimAmount(p.night ? 0.55f : 0.42f);
            }
            dl.show();
            return this;
        }
    }

    /** One selectable row for a picker: label, accent colour plus a check when selected. */
    public static View pickRow(Context c, Pal p, String label, boolean selected, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, TAP));
        r.setPadding(dp(c, S5), dp(c, S2), dp(c, S5), dp(c, S2));
        r.setBackground(ripple(p, selected ? round(p.accSoft, 0, 0, 0) : null, round(Color.WHITE, 0, 0, 0)));
        r.setOnClickListener(l);
        TextView t = text(c, label, T_BODY, selected ? p.accInk : p.tx, selected ? 700 : 400);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (selected) r.addView(icon(c, "check", p.accInk, 20));
        return r;
    }

    /** A simple single-choice picker; `selected` < 0 marks nothing. */
    public static Dlg picker(Context c, Pal p, String title, String[] items, int selected, java.util.function.IntConsumer onPick) {
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        final Dlg[] d = new Dlg[1];
        for (int i = 0; i < items.length; i++) {
            final int k = i;
            list.addView(pickRow(c, p, items[i], i == selected, v -> { d[0].dismiss(); onPick.accept(k); }));
        }
        d[0] = new Dlg(c, p).title(title).view(list).button(0, "Cancel", null);
        return d[0];
    }
}
