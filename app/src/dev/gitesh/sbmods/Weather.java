// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;

import java.util.Locale;

/**
 * The weather item: Samsung Weather's current conditions (no network of our own, no polling).
 * Samsung Weather sends SystemUI ACTION_SYNC after each of its own updates; only then is its data read (see fromDb).
 * Shared by the status bar (hook) and the settings preview, so both show the same text and icon.
 */
public final class Weather {
    public static final String ACTION_SYNC = "com.samsung.android.weather.action.WEATHER_DATA_SYNC";
    public static final String APP = "com.sec.android.daemonapp";
    /** Older than this: Samsung Weather stopped updating, so nothing is shown rather than old weather. */
    public static final long STALE_MS = 12 * 3600_000L;

    public static final int G_NONE = -1, G_SUN = 0, G_MOON = 1, G_PART_DAY = 2, G_PART_NIGHT = 3, G_CLOUD = 4,
            G_RAIN = 5, G_STORM = 6, G_SNOW = 7, G_FOG = 8, G_WIND = 9;

    private Weather() {}

    /** One reading (temperatures in °C, as Samsung stores them). */
    public static final class Snap {
        public float temp = Float.NaN, feels = Float.NaN, hi = Float.NaN, lo = Float.NaN;
        public int glyph = G_NONE;
        public String city = "", text = "";
        public long updated;   // Samsung's update time (wall clock ms)

        /** Stored in SharedPreferences (XML): a printable separator, since XML 1.0 can't hold control characters. */
        private static final String SEP = "|";

        public String encode() {
            return temp + SEP + feels + SEP + hi + SEP + lo + SEP + glyph + SEP + updated + SEP + clean(city) + SEP + clean(text);
        }

        public static Snap decode(String s) {
            if (s == null || s.isEmpty()) return null;
            try {
                String[] p = s.split("\\|", -1);
                if (p.length < 8) return null;
                Snap n = new Snap();
                n.temp = Float.parseFloat(p[0]); n.feels = Float.parseFloat(p[1]);
                n.hi = Float.parseFloat(p[2]); n.lo = Float.parseFloat(p[3]);
                n.glyph = Integer.parseInt(p[4]); n.updated = Long.parseLong(p[5]);
                n.city = p[6]; n.text = p[7];
                return n;
            } catch (RuntimeException e) { return null; }
        }

        private static String clean(String v) { return v == null ? "" : v.replace(SEP, "/"); }
    }

    // ------------------------------------------------------------------ reading (status bar side)

    /**
     * Reads Samsung Weather's main location (the top of its list) from a private copy of its database.
     * Samsung's own provider refuses SystemUI (it only serves its watch plug-ins), so the root helper copies the
     * database file when Samsung Weather says it updated; see Controller.wxAsk and sbmods_cpud.sh (wxcopy).
     */
    public static Snap fromDb(java.io.File file) {
        android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(file.getPath(), null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE | android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        try {
            String cp = "";
            try (Cursor c = db.rawQuery("SELECT * FROM TABLE_SETTING_INFO LIMIT 1", null)) {
                if (c.moveToFirst()) cp = str(c, "COL_SETTING_ACTIVE_CP_TYPE");
            } catch (RuntimeException ignored) {}   // no provider name: the icon then comes from Samsung's words
            try (Cursor c = db.rawQuery("SELECT * FROM TABLE_WEATHER_INFO", null)) {
                return best(c, cp);
            }
        } finally {
            db.close();
        }
    }

    /** The row with the lowest order (Samsung's first location: the current one, when it is used). */
    private static Snap best(Cursor c, String cp) {
        Snap best = null;
        int bestOrder = Integer.MAX_VALUE;
        while (c.moveToNext()) {
            int order = (int) num(c, "COL_WEATHER_ORDER", Integer.MAX_VALUE - 1);
            if (order >= bestOrder) continue;
            Snap s = new Snap();
            s.temp = (float) num(c, "COL_WEATHER_CURRENT_TEMP", Double.NaN);
            if (Float.isNaN(s.temp) || Math.abs(s.temp) > 90) continue;   // 999 = "no value" in Samsung's data
            s.feels = sane((float) num(c, "COL_WEATHER_FEELSLIKE_TEMP", Double.NaN));
            s.hi = sane((float) num(c, "COL_WEATHER_HIGH_TEMP", Double.NaN));
            s.lo = sane((float) num(c, "COL_WEATHER_LOW_TEMP", Double.NaN));
            s.city = str(c, "COL_WEATHER_NAME");
            if (s.city.isEmpty()) s.city = str(c, "COL_WEATHER_NAME_ENG");
            s.text = str(c, "COL_WEATHER_WEATHER_TEXT");
            s.updated = (long) num(c, "COL_WEATHER_UPDATE_TIME", 0);
            boolean night = num(c, "COL_WEATHER_IS_DAY_OR_NIGHT", 1) == 2;
            s.glyph = glyph(cp, (int) num(c, "COL_WEATHER_ICON_NUM", -1), night, s.text);
            best = s;
            bestOrder = order;
        }
        return best;
    }

    private static float sane(float v) { return Float.isNaN(v) || Math.abs(v) > 90 ? Float.NaN : v; }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) return "";
        String v = c.getString(i);
        return v == null ? "" : v.trim();
    }

    private static double num(Cursor c, String col, double def) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) return def;
        try { return c.getDouble(i); } catch (RuntimeException e) { return def; }
    }

    /** Samsung passes on its weather provider's own icon number: The Weather Channel's codes, or AccuWeather's. */
    static int glyph(String cp, int icon, boolean night, String text) {
        String p = cp == null ? "" : cp.toUpperCase(Locale.ROOT);
        int g = G_NONE;
        if (p.startsWith("TWC")) g = twc(icon);
        else if (p.contains("ACCU")) g = accu(icon, night);
        if (g == G_NONE) g = fromText(text, night);
        return g;
    }

    private static int twc(int i) {
        switch (i) {
            case 0: case 1: case 2: case 3: case 4: case 37: case 38: case 47: return G_STORM;
            case 6: case 8: case 9: case 10: case 11: case 12: case 17: case 35: case 39: case 40: case 45: return G_RAIN;
            case 5: case 7: case 13: case 14: case 15: case 16: case 18: case 25: case 41: case 42: case 43: case 46: return G_SNOW;
            case 19: case 20: case 21: case 22: return G_FOG;
            case 23: case 24: return G_WIND;
            case 26: case 27: case 28: return G_CLOUD;
            case 29: return G_PART_NIGHT;
            case 30: return G_PART_DAY;
            case 31: case 33: return G_MOON;
            case 32: case 34: case 36: return G_SUN;
            default: return G_NONE;
        }
    }

    private static int accu(int i, boolean night) {
        switch (i) {
            case 1: case 2: case 30: return G_SUN;
            case 3: case 4: return G_PART_DAY;
            case 5: case 11: case 37: return G_FOG;
            case 6: case 7: case 8: case 38: return G_CLOUD;
            case 12: case 13: case 14: case 18: case 26: case 39: case 40: return G_RAIN;
            case 15: case 16: case 17: case 41: case 42: return G_STORM;
            case 19: case 20: case 21: case 22: case 23: case 24: case 25: case 29: case 43: case 44: return G_SNOW;
            case 31: return night ? G_MOON : G_SUN;
            case 32: return G_WIND;
            case 33: case 34: return G_MOON;
            case 35: case 36: return G_PART_NIGHT;
            default: return G_NONE;
        }
    }

    private static int fromText(String t, boolean night) {
        String s = t == null ? "" : t.toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return G_NONE;
        if (s.contains("thunder") || s.contains("storm")) return G_STORM;
        if (s.contains("snow") || s.contains("sleet") || s.contains("flurr") || s.contains("ice")) return G_SNOW;
        if (s.contains("rain") || s.contains("shower") || s.contains("drizzle")) return G_RAIN;
        if (s.contains("fog") || s.contains("mist") || s.contains("haze") || s.contains("smoke") || s.contains("dust")) return G_FOG;
        if (s.contains("wind") || s.contains("breez")) return G_WIND;
        if (s.contains("partly") || s.contains("intermittent")) return night ? G_PART_NIGHT : G_PART_DAY;
        if (s.contains("cloud") || s.contains("overcast")) return G_CLOUD;
        if (s.contains("clear") || s.contains("sunny") || s.contains("fair")) return night ? G_MOON : G_SUN;
        return G_NONE;
    }

    // ------------------------------------------------------------------ text

    /** What the status bar shows, or null when hidden (switched off, no data, or too old). */
    public static CharSequence format(Config c, Snap s, long now) {
        if (!c.wxEnabled || s == null || Float.isNaN(s.temp)) return null;
        if (s.updated > 0 && now - s.updated > STALE_MS) return null;
        SpannableStringBuilder b = new SpannableStringBuilder();
        String city = cityText(c.wxCity, s.city);
        if (c.wxCityFirst && !city.isEmpty()) b.append(city);
        boolean icon = c.wxIcon != Config.WX_ICON_HIDE && s.glyph != G_NONE;
        if (icon) {
            if (b.length() > 0) b.append(' ');
            int at = b.length();
            b.append('☀');   // stand-in character, drawn by the span
            b.setSpan(new IconSpan(s.glyph, c.wxIcon == Config.WX_ICON_COLOUR), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (c.wxDeg != Config.WX_DEG_HIDE) {
            float t = c.wxFeels && !Float.isNaN(s.feels) ? s.feels : s.temp;
            if (b.length() > 0 && !icon) b.append(' ');   // the icon brings its own small gap
            b.append(deg(c, t, true));
            if (c.wxHiLo && !Float.isNaN(s.hi) && !Float.isNaN(s.lo)) {
                int at = b.length();
                b.append(' ').append(deg(c, s.hi, false)).append('/').append(deg(c, s.lo, false));
                b.setSpan(new RelativeSizeSpan(0.78f), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (c.wxCond && s.text != null && !s.text.isEmpty()) {
            String t = s.text.length() > 16 ? s.text.substring(0, 15).trim() + "…" : s.text;
            if (b.length() > 0) b.append(' ');
            b.append(t);
        }
        if (!c.wxCityFirst && !city.isEmpty()) {
            if (b.length() > 0) b.append(' ');
            b.append(city);
        }
        return b.length() == 0 ? null : b;
    }

    /** A temperature in the chosen unit and style ("14", "14°", "14°C"); the high/low pair never repeats the C / F. */
    private static String deg(Config c, float celsius, boolean main) {
        float v = c.wxUnitF ? celsius * 9f / 5f + 32f : celsius;
        int r = Math.round(v);
        String n = String.valueOf(r == 0 ? 0 : r);   // no "-0"
        if (c.wxDeg == Config.WX_DEG_NUM) return n;
        if (c.wxDeg == Config.WX_DEG_DEG || !main) return n + "°";
        return n + (c.wxUnitF ? "°F" : "°C");
    }

    /** Full name, or a 2 / 3 letter short form ("Sonipat" -> "SO" / "SON", "New Delhi" -> "ND" / "NDE"). */
    public static String cityText(int mode, String name) {
        if (mode == Config.WX_CITY_HIDE || name == null || name.trim().isEmpty()) return "";
        String full = name.trim();
        if (mode == Config.WX_CITY_FULL) return full;
        int n = mode == Config.WX_CITY_2 ? 2 : 3;
        java.util.List<String> words = new java.util.ArrayList<>();
        for (String w : full.split("[\\s\\-]+")) {
            StringBuilder l = new StringBuilder();
            for (int i = 0; i < w.length(); ) {
                int cp = w.codePointAt(i);
                if (Character.isLetter(cp)) l.appendCodePoint(cp);
                i += Character.charCount(cp);
            }
            if (l.length() > 0) words.add(l.toString());
        }
        if (words.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        if (words.size() >= n) for (int i = 0; i < n; i++) out.appendCodePoint(words.get(i).codePointAt(0));
        else if (words.size() == 1) out.append(cut(words.get(0), n));
        else { out.appendCodePoint(words.get(0).codePointAt(0)); out.append(cut(words.get(1), n - 1)); }
        return out.toString().toUpperCase(Locale.getDefault());
    }

    private static String cut(String s, int n) {
        int end = s.offsetByCodePoints(0, Math.min(n, s.codePointCount(0, s.length())));
        return s.substring(0, end);
    }

    // ------------------------------------------------------------------ icon

    /** The weather icon, drawn in the text's own size, colour and position, so it tints with the status bar like the text. */
    public static final class IconSpan extends ReplacementSpan {
        private final int glyph;
        private final boolean colour;
        private final Rect cap = new Rect();

        public IconSpan(int glyph, boolean colour) { this.glyph = glyph; this.colour = colour; }

        @Override public int getSize(Paint p, CharSequence t, int s, int e, Paint.FontMetricsInt fm) {
            return Math.round(p.getTextSize() * (BOX + GAP));
        }

        /** Runs inside the status bar's own drawing: it must never throw (that would take the status bar down), so a failure just skips the icon. */
        @Override public void draw(Canvas c, CharSequence t, int s, int e, float x, int top, int y, int bottom, Paint p) {
            try {
                p.getTextBounds("H", 0, 1, cap);
                float size = p.getTextSize() * BOX;
                drawGlyph(c, p, glyph, x + size / 2f, y + cap.exactCenterY(), size, colour);
            } catch (Throwable ignored) {}
        }
    }

    /** Icon box as a share of the text size, and the gap after it. */
    static final float BOX = 0.95f, GAP = 0.22f;

    private static final Path P1 = new Path(), P2 = new Path(), P3 = new Path();
    private static final RectF RF = new RectF();
    private static final Matrix M = new Matrix();

    /**
     * Draws glyph g centred on (cx, cy) in a box of side s. Simple = every part in the paint's colour (the status bar's
     * tint); colour = sun, moon, rain, lightning and snow in their own colours (deeper ones on a light status bar),
     * clouds, fog and wind stay in the text colour so they always show.
     */
    public static synchronized void drawGlyph(Canvas c, Paint p, int g, float cx, float cy, float s, boolean colour) {
        if (g == G_NONE) return;
        int ink = p.getColor();
        Paint.Style st = p.getStyle();
        float sw = p.getStrokeWidth();
        Paint.Cap capS = p.getStrokeCap();
        Paint.Join join = p.getStrokeJoin();
        boolean darkInk = lum(ink) < 0.5f;
        int a = Color.alpha(ink);
        int sun = colour ? tone(darkInk ? 0xFFF29900 : 0xFFFFC53D, a) : ink;
        int moon = colour ? tone(darkInk ? 0xFFD99A00 : 0xFFFFE08A, a) : ink;
        int drop = colour ? tone(darkInk ? 0xFF1E88E5 : 0xFF5EC8FF, a) : ink;
        int bolt = colour ? tone(darkInk ? 0xFFF29900 : 0xFFFFC53D, a) : ink;
        int snow = colour ? tone(darkInk ? 0xFF42A5F5 : 0xFFA8E4FF, a) : ink;
        try {
            p.setStyle(Paint.Style.FILL);
            switch (g) {
                case G_SUN:
                    sun(P1, cx, cy, s); fill(c, p, P1, sun); break;
                case G_MOON:
                    moon(P1, cx, cy, s * 0.92f); fill(c, p, P1, moon); break;
                case G_PART_DAY: case G_PART_NIGHT: {
                    float bx = cx - 0.17f * s, by = cy - 0.15f * s;
                    if (g == G_PART_DAY) sun(P1, bx, by, s * 0.66f); else moon(P1, bx, by, s * 0.60f);
                    cloud(P2, cx + 0.07f * s, cy + 0.12f * s, s * 0.82f, 0);
                    cloud(P3, cx + 0.07f * s, cy + 0.12f * s, s * 0.82f, 0.07f * s);   // a little gap between the two
                    P1.op(P3, Path.Op.DIFFERENCE);
                    fill(c, p, P1, g == G_PART_DAY ? sun : moon);
                    fill(c, p, P2, ink);
                    break;
                }
                case G_CLOUD:
                    cloud(P1, cx, cy + 0.04f * s, s, 0); fill(c, p, P1, ink); break;
                case G_RAIN: case G_STORM: case G_SNOW: {
                    cloud(P1, cx, cy - 0.12f * s, s * 0.86f, 0);
                    fill(c, p, P1, ink);
                    if (g == G_RAIN) {
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(0.08f * s);
                        p.setStrokeCap(Paint.Cap.ROUND);
                        p.setColor(drop);
                        for (int k = -1; k <= 1; k++) {
                            float x0 = cx + k * 0.2f * s + 0.04f * s;
                            c.drawLine(x0, cy + 0.17f * s, x0 - 0.07f * s, cy + 0.37f * s, p);
                        }
                    } else if (g == G_STORM) {
                        P2.reset();
                        P2.setFillType(Path.FillType.WINDING);
                        P2.moveTo(cx + 0.06f * s, cy + 0.10f * s);
                        P2.lineTo(cx - 0.11f * s, cy + 0.29f * s);
                        P2.lineTo(cx + 0.01f * s, cy + 0.29f * s);
                        P2.lineTo(cx - 0.06f * s, cy + 0.47f * s);
                        P2.lineTo(cx + 0.15f * s, cy + 0.23f * s);
                        P2.lineTo(cx + 0.03f * s, cy + 0.23f * s);
                        P2.lineTo(cx + 0.12f * s, cy + 0.10f * s);
                        P2.close();
                        fill(c, p, P2, bolt);
                    } else {
                        p.setColor(snow);
                        float r = 0.05f * s;
                        c.drawCircle(cx - 0.19f * s, cy + 0.21f * s, r, p);
                        c.drawCircle(cx, cy + 0.21f * s, r, p);
                        c.drawCircle(cx + 0.19f * s, cy + 0.21f * s, r, p);
                        c.drawCircle(cx - 0.095f * s, cy + 0.37f * s, r, p);
                        c.drawCircle(cx + 0.095f * s, cy + 0.37f * s, r, p);
                    }
                    break;
                }
                case G_FOG: {   // a cloud with two lines of mist under it (three bare lines read as a menu icon)
                    cloud(P1, cx, cy - 0.13f * s, s * 0.86f, 0);
                    fill(c, p, P1, ink);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(0.08f * s);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setColor(ink);
                    c.drawLine(cx - 0.34f * s, cy + 0.22f * s, cx + 0.26f * s, cy + 0.22f * s, p);
                    c.drawLine(cx - 0.22f * s, cy + 0.38f * s, cx + 0.36f * s, cy + 0.38f * s, p);
                    break;
                }
                case G_WIND: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(0.08f * s);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeJoin(Paint.Join.ROUND);
                    p.setColor(ink);
                    P1.reset();
                    P1.moveTo(cx - 0.40f * s, cy - 0.10f * s);
                    P1.lineTo(cx + 0.12f * s, cy - 0.10f * s);
                    P1.cubicTo(cx + 0.32f * s, cy - 0.10f * s, cx + 0.32f * s, cy - 0.36f * s, cx + 0.14f * s, cy - 0.32f * s);
                    P1.moveTo(cx - 0.40f * s, cy + 0.07f * s);
                    P1.lineTo(cx + 0.22f * s, cy + 0.07f * s);
                    P1.cubicTo(cx + 0.44f * s, cy + 0.07f * s, cx + 0.42f * s, cy + 0.34f * s, cx + 0.22f * s, cy + 0.29f * s);
                    c.drawPath(P1, p);
                    break;
                }
                default: break;
            }
        } finally {
            p.setColor(ink);
            p.setStyle(st);
            p.setStrokeWidth(sw);
            p.setStrokeCap(capS);
            p.setStrokeJoin(join);
        }
    }

    private static void fill(Canvas c, Paint p, Path path, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(path, p);
    }

    /** Disc and eight short rays. */
    private static void sun(Path out, float cx, float cy, float s) {
        out.reset();
        out.setFillType(Path.FillType.WINDING);
        out.addCircle(cx, cy, 0.19f * s, Path.Direction.CW);
        float t = 0.08f * s, r1 = 0.29f * s, r2 = 0.45f * s;
        for (int i = 0; i < 8; i++) {
            P3.reset();
            RF.set(cx - t / 2, cy - r2, cx + t / 2, cy - r1);
            P3.addRoundRect(RF, t / 2, t / 2, Path.Direction.CW);
            M.setRotate(i * 45f, cx, cy);
            P3.transform(M);
            out.addPath(P3);
        }
    }

    /** A crescent. */
    private static void moon(Path out, float cx, float cy, float s) {
        out.reset();
        out.setFillType(Path.FillType.WINDING);
        out.addCircle(cx, cy, 0.36f * s, Path.Direction.CW);
        P3.reset();
        P3.addCircle(cx + 0.19f * s, cy - 0.14f * s, 0.29f * s, Path.Direction.CW);
        out.op(P3, Path.Op.DIFFERENCE);
    }

    /** Three puffs on a flat base; grow > 0 makes a slightly bigger outline (used to cut a gap). */
    private static void cloud(Path out, float cx, float cy, float k, float grow) {
        out.reset();
        out.setFillType(Path.FillType.WINDING);
        out.addCircle(cx - 0.20f * k, cy + 0.04f * k, 0.17f * k + grow, Path.Direction.CW);
        out.addCircle(cx + 0.02f * k, cy - 0.07f * k, 0.23f * k + grow, Path.Direction.CW);
        out.addCircle(cx + 0.22f * k, cy + 0.06f * k, 0.16f * k + grow, Path.Direction.CW);
        RF.set(cx - 0.37f * k - grow, cy + 0.04f * k - grow, cx + 0.38f * k + grow, cy + 0.22f * k + grow);
        out.addRoundRect(RF, 0.09f * k + grow, 0.09f * k + grow, Path.Direction.CW);
    }

    private static float lum(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
    }

    private static int tone(int rgb, int alpha) { return (rgb & 0x00FFFFFF) | (alpha << 24); }
}
