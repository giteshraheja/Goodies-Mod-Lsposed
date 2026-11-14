// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;

import dev.gitesh.sbmods.Config;
import dev.gitesh.sbmods.Habits;
import dev.gitesh.sbmods.PanelLayout;
import dev.gitesh.sbmods.Ui;

/**
 * The cards of the Performance panel. A card builds its own view, and updates it on every refresh while the panel shows.
 * Which cards show, and in what order, comes from PanelLayout (the global setting). Nothing here may crash the launcher:
 * the panel calls build / live only through safeBuild / safeLive, and every click goes through safe().
 */
final class PanelCards {
    private PanelCards() {}

    /** Text colours of the current theme (see Ui.cur): main text and the dimmer secondary text. Set by theme() when the panel opens or the day / night setting changes. */
    static int WHITE = Ui.cur.tx, DIM = Ui.cur.tx2;

    /** Takes the system's day / night setting from the context; the panel then builds its views with these colours. */
    static void theme(Context c) { Ui.useTheme(c); retheme(); }

    static void retheme() { WHITE = Ui.cur.tx; DIM = Ui.cur.tx2; MODE_COLORS = modeColors(); }

    /** The fill of a control track / flat pill sitting on a tile. */
    static int track() { return Ui.cur.night ? 0x22FFFFFF : 0x1A12161C; }

    /** Text on an accent fill: the on-accent colour, and its softer second line. */
    static int onAcc() { return Ui.cur.onAcc; }
    static int onAccDim() { return (Ui.cur.onAcc & 0x00FFFFFF) | 0xCC000000; }

    abstract static class Card {
        final String id, title;
        final int page;
        /** The live value on the card's header line (right side); a card that has one creates it in build() with valueView(). */
        TextView val;
        /** Shows / hides the help text under the card; set by the panel before build(). */
        Runnable toggle;
        Card(String id) {
            int i = PanelLayout.indexOf(id);
            this.id = id; this.title = PanelLayout.TITLES[i]; this.page = PanelLayout.PAGES[i];
        }
        abstract View build(Activity a);
        /** Called on every refresh while the panel shows (about once a second). */
        void live(Activity a, Perf.Snap s) {}
        /** Changes when what the card shows was changed from elsewhere (for example a chip long-press): the panel then rebuilds it. */
        String sig(Activity a) { return ""; }
        /** A longer explanation, shown when the title is tapped (null = none). */
        String help(Activity a) { return null; }
        /** Small cards sit two to a row (quick-settings style) when two of them follow each other. */
        boolean half() { return false; }
        /** A toggle tile: the whole tile is the button, filled with the accent colour while on (long-press shows the help). */
        boolean tapTile() { return false; }
        boolean on(Activity a) { return false; }
        void tap(Activity a) {}
        /** A tile's line-icon name and state text ("On", "Standard · 60"); only tile cards have them. */
        String icon() { return "sys"; }
        String state(Activity a) { return ""; }
        /** True when the card draws its own title (a one-row card): the panel then adds no header line. */
        boolean ownHeader() { return false; }

        /** The title, with a small "ⓘ" when the card has help (tapping it shows the help). */
        TextView titleView(Activity a) {
            String h = null;
            try { h = help(a); } catch (Throwable t) { Log.w("card " + id + " help: " + t); }
            TextView t = tvm(a, h == null ? title : title + " \u24d8", 13, DIM);
            if (h != null) t.setOnClickListener(safe(() -> { if (toggle != null) toggle.run(); }));
            return t;
        }

        /** The value on the header line: one line, right-aligned. */
        TextView valueView(Activity a) {
            val = tv(a, "", 13, WHITE, false);
            val.setFontFeatureSettings("tnum");   // digits of equal width: nothing jiggles every second
            val.setGravity(Gravity.END);
            val.setSingleLine(true);
            val.setEllipsize(android.text.TextUtils.TruncateAt.END);
            return val;
        }
    }

    /** The registry, in default order (must match PanelLayout.DEFAULTS). */
    static final List<Card> ALL = java.util.Arrays.asList(new Insight(), new Modes(), new Cpu(), new Gpu(), new Cores(), new Graphics(), new Switches(), new Games(),
            new PowerSave(), new Smooth(), new Battery(), new Health(), new Memory(), new TopApps());

    /** The visible cards of one page, in the owner's order. */
    static List<Card> visible(Context c, int page) {
        List<Card> out = new ArrayList<>();
        for (String id : PanelLayout.order(Global.gstr(c, PanelLayout.SETTING), PanelLayout.DEFAULTS))
            for (Card k : ALL) if (k.id.equals(id) && k.page == page) out.add(k);
        return out;
    }

    /** The ids the All page shows: the cards used last, newest first, minus any the owner hid. */
    static List<String> recentVisible(Context c) {
        List<String> vis = PanelLayout.order(Global.gstr(c, PanelLayout.SETTING), PanelLayout.DEFAULTS);
        List<String> out = new ArrayList<>();
        for (String id : PanelLayout.recent(Global.gstr(c, PanelLayout.RECENT))) if (vis.contains(id)) out.add(id);
        return out;
    }

    /** Every shown card, in the owner's order (the All page unless it fills itself). */
    static List<Card> allFixed(Context c) {
        List<Card> out = new ArrayList<>();
        for (String id : PanelLayout.order(Global.gstr(c, PanelLayout.SETTING), PanelLayout.DEFAULTS)) { Card k = byId(id); if (k != null) out.add(k); }
        return out;
    }

    static boolean allFill(Context c) { return Global.gint(c, PanelLayout.ALLFILL, 0) == 1; }

    /** Is this tile card set to the small icon-only size? */
    static boolean small(Context c, Card k) { return PanelLayout.small(Global.gstr(c, PanelLayout.TILES), k.id); }

    static Card byId(String id) {
        for (Card k : ALL) if (k.id.equals(id)) return k;
        return null;
    }

    /** The owner used a card: remember it for the All page (written only when it changes the list). */
    static void used(Activity a, String cardId) {
        try { if (PerfPanel.pBox != null) PerfPanel.pBox.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); } catch (Throwable ignored) {}   // the same small tick as the stock quick panel
        try { Global.gset(a, PanelLayout.RECENT, PanelLayout.touch(Global.gstr(a, PanelLayout.RECENT), cardId)); } catch (Throwable t) { Log.w("used " + cardId + ": " + t); }
    }

    static View safeBuild(Card k, Activity a) {
        try { return k.build(a); } catch (Throwable t) { Log.w("card " + k.id + " build: " + t); return null; }
    }

    static void safeLive(Card k, Activity a, Perf.Snap s) {
        try { k.live(a, s); } catch (Throwable t) { Log.w("card " + k.id + " live: " + t); }
    }

    // ------------------------------------------------------------------ small helpers

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static TextView tv(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    /** Medium weight (not bold), for tile titles. */
    static TextView tvm(Context c, CharSequence s, float sp, int color) {
        TextView t = tv(c, s, sp, color, false);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** Mode colours: Full green, Balanced cyan, Cool soft blue, Saver amber (the panel header and the selected segment use them). */
    static int[] MODE_COLORS = modeColors();
    private static int[] modeColors() { return new int[]{Ui.cur.ok, Ui.cur.acc, Ui.cur.night ? 0xFF9DB4FF : 0xFF3F5FD0, Ui.cur.warn}; }

    /** A click handler that can never throw into the launcher. */
    static View.OnClickListener safe(Runnable r) {
        return v -> { try { r.run(); } catch (Throwable t) { Log.w("panel click: " + t); } };
    }

    static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b, float weight) {
        LinearLayout.LayoutParams p = lp(w, h, l, t, r, b);
        p.weight = weight;
        return p;
    }

    /** A row of segments in one rounded track; sel = -1 selects none. Labels are one line. */
    static LinearLayout seg(Activity a, CharSequence[] labels, int sel, IntUnaryOperator selColor, IntConsumer pick) {
        LinearLayout seg = new LinearLayout(a);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(track());
        sb.setCornerRadius(dp(a, 18));
        seg.setBackground(sb);
        seg.setPadding(dp(a, 3), dp(a, 3), dp(a, 3), dp(a, 3));
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean on = i == sel;
            TextView b = tv(a, labels[i], 14, on ? onAcc() : WHITE, on);
            b.setGravity(Gravity.CENTER);
            b.setSingleLine(true);
            b.setMinHeight(dp(a, 34));   // 34 + the 3 dp track padding: a 40 dp target
            if (on) { GradientDrawable ob = new GradientDrawable(); ob.setColor(selColor.applyAsInt(i)); ob.setCornerRadius(dp(a, 15)); b.setBackground(ob); }
            b.setOnClickListener(safe(() -> pick.accept(idx)));
            seg.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        return seg;
    }

    /** A flat pill button. */
    static TextView button(Activity a, String label, Runnable click) {
        TextView b = tv(a, label, 14, WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(a, 12), 0, dp(a, 12), 0);
        b.setMinHeight(dp(a, 40));
        GradientDrawable g = new GradientDrawable();
        g.setColor(track());
        g.setCornerRadius(dp(a, 18));
        b.setBackground(g);
        b.setOnClickListener(safe(click));
        return b;
    }

    /** A toggle pill: filled cyan with black text when on, the usual translucent pill when off. */
    static void pillStyle(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? Ui.cur.acc : track());
        g.setCornerRadius(dp(t.getContext(), 18));
        t.setBackground(g);
        t.setTextColor(on ? onAcc() : WHITE);
    }

    static TextView pill(Activity a, String label, boolean on, Runnable click) {
        TextView t = tv(a, label, 12, WHITE, false);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setMinHeight(dp(a, 44));
        t.setPadding(dp(a, 6), dp(a, 4), dp(a, 6), dp(a, 4));
        pillStyle(t, on);
        t.setOnClickListener(safe(click));
        return t;
    }

    /** Open one of Samsung's own screens (the fallback when that one is missing, may be null), then close the panel. A failure is a message, never a crash. */
    static void openScreen(Activity a, String cardId, Intent first, Intent fallback, String fail) {
        used(a, cardId);
        boolean ok = false;
        try { a.startActivity(first); ok = true; } catch (Throwable t) { Log.w("open screen: " + t); }
        if (!ok && fallback != null) try { a.startActivity(fallback); ok = true; } catch (Throwable t) { Log.w("open fallback: " + t); }
        if (!ok) { android.widget.Toast.makeText(a, fail, android.widget.Toast.LENGTH_SHORT).show(); return; }
        try { if (PerfPanel.panel != null) PerfPanel.panel.dismiss(); } catch (Throwable ignored) {}
    }

    static int loadColor(int pct) { return pct >= 85 ? Ui.cur.err : pct >= 60 ? Ui.cur.warn : Ui.cur.ok; }

    /** "34% · 1.9 GHz · max 2.4 GHz": the "max" part turns amber when something holds the speed below the hardware maximum. */
    static CharSequence rateText(String first, String now, int cap, int hw, boolean cpu) {
        SpannableStringBuilder t = new SpannableStringBuilder(first);
        t.append("  \u00b7  ").append(now);
        if (cap > 0 && hw > 0) {
            t.append("  \u00b7  ");
            int s2 = t.length();
            boolean held = cap < hw * 0.97f;
            t.append("max ").append(cpu ? Perf.ghz(cap) : cap + " MHz");
            if (held) t.setSpan(new ForegroundColorSpan(Ui.cur.warn), s2, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return t;
    }

    /** The limit choices of the CPU or GPU as one row of one-line labels. Behaviour as it always was, cool-down included. */
    static View limitRow(Activity a, String which) {
        int cur = RecentsChip.effPct(a, which, Global.gint(a, "sbmods_" + which + "_pct", 0), Global.gint(a, "sbmods_cool", 0) == 1);   // what is really in force
        int sel = -1;
        for (int i = 0; i < RecentsChip.PCTS.length; i++) if (RecentsChip.PCTS[i] == cur || (i == 0 && cur == 0)) sel = i;
        return seg(a, RecentsChip.PCT_NAMES, sel, i -> i == 0 ? Ui.cur.ok : Ui.cur.acc, i -> {
            int pct = RecentsChip.PCTS[i];
            if (pct > 0) Global.gset(a, "sbmods_" + which + "_pick", String.valueOf(pct));
            boolean cooling = Global.gint(a, "sbmods_cool", 0) == 1;
            // cooling: a level just sets the cool-down level (ends with it); Full = this one at full speed while cooling (100)
            Global.gset(a, "sbmods_" + which + "_pct", cooling ? (pct > 0 ? "0" : "100") : String.valueOf(pct));
            used(a, which);
            PerfPanel.changed(a);
        });
    }

    /** What each limit level allows, for the card's help. */
    private static String limitHelp(Activity a, String which) {
        boolean cpu = which.equals("cpu");
        Perf.Snap s = Perf.read();
        int hw = cpu ? s.cpuHw : s.gpuHw;
        StringBuilder t = new StringBuilder();
        for (int i = 1; i < RecentsChip.PCTS.length; i++) {
            int mhz = Perf.pick(cpu ? Perf.cpuSteps : Perf.gpuSteps, hw, RecentsChip.PCTS[i]);
            if (mhz <= 0) continue;
            t.append(t.length() > 0 ? ", " : "").append(RecentsChip.PCT_NAMES[i]).append(" = ").append(cpu ? Perf.ghz(mhz) : mhz + " MHz");
        }
        return "A limit caps how fast the " + (cpu ? "CPU (fastest core)" : "GPU") + " may run, so it stays cooler and saves power."
                + (t.length() > 0 ? "\n" + t : "");
    }

    private static String limitSig(Activity a, String which) {
        return RecentsChip.effPct(a, which, Global.gint(a, "sbmods_" + which + "_pct", 0), Global.gint(a, "sbmods_cool", 0) == 1) + "";
    }

    private static LinearLayout column(Activity a) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    /** One-line text that ends with an ellipsis instead of wrapping. */
    private static TextView oneLine(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    // ------------------------------------------------------------------ battery facts shared by the header and the report

    static final class Power {
        static int tempC, millivolts, status;
        static double watts = -1;
        static boolean charging;   // plugged in and charging right now

        static void read(Context c) {
            try {
                Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (b != null) {
                    tempC = Math.round(b.getIntExtra("temperature", 0) / 10f);
                    millivolts = b.getIntExtra("voltage", 0);
                    status = b.getIntExtra("status", 0);
                }
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING;
                BatteryManager bm = c.getSystemService(BatteryManager.class);
                long raw = bm == null ? Integer.MIN_VALUE : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                watts = raw == Integer.MIN_VALUE || millivolts <= 0 ? -1 : PanelMath.watts(raw, millivolts);
            } catch (Throwable t) { Log.w("power read: " + t); }
        }

        static String text() { return watts < 0 ? null : PanelMath.wattsText(watts, charging); }
    }

    // ------------------------------------------------------------------ 1. modes

    static void applyMode(Activity a, int m) {
        Global.gset(a, "sbmods_cool", "0");   // a mode is the whole picture: cool-down would otherwise keep deciding the levels
        Global.gset(a, "sbmods_cpu_pct", String.valueOf(PanelMath.MODE_CPU[m]));
        Global.gset(a, "sbmods_gpu_pct", String.valueOf(PanelMath.MODE_GPU[m]));
        if (PanelMath.MODE_CPU[m] > 0) Global.gset(a, "sbmods_cpu_pick", String.valueOf(PanelMath.MODE_CPU[m]));   // so a long-press on a chip returns to this level
        if (PanelMath.MODE_GPU[m] > 0) Global.gset(a, "sbmods_gpu_pick", String.valueOf(PanelMath.MODE_GPU[m]));
        Global.gset(a, "sbmods_cores", PanelMath.MODE_CORES[m]);
        learn(a, PanelMath.MODE_CPU[m], PanelMath.MODE_GPU[m]);   // Full (0/0) is ignored
        used(a, "modes");
        PerfPanel.changed(a);
    }

    static int currentMode(Activity a) {
        return PanelMath.mode(Global.gint(a, "sbmods_cpu_pct", 0), Global.gint(a, "sbmods_gpu_pct", 0), Global.gstr(a, "sbmods_cores"), Global.gint(a, "sbmods_cool", 0) == 1);
    }

    static final class Modes extends Card {
        Modes() { super("modes"); }
        @Override View build(Activity a) {
            LinearLayout col = column(a);
            int m = currentMode(a);
            TextView v = valueView(a);
            if (m >= 0) v.setText("");   // the panel's header already names the mode
            else { v.setText(Global.gint(a, "sbmods_cool", 0) == 1 ? "Cool-down" : "Custom"); v.setTextColor(Ui.cur.warn); }
            col.addView(seg(a, PanelMath.MODE_NAMES, m, i -> MODE_COLORS[i], i -> applyMode(a, i)));
            return col;
        }
        @Override String help(Activity a) {
            StringBuilder t = new StringBuilder();
            for (String s : PanelMath.MODE_TEXT) t.append(t.length() > 0 ? "\n" : "").append(s);
            return t + "\nCustom: your own mix of the settings below. Cool-down: pick a mode to turn it off.";
        }
        @Override String sig(Activity a) { return currentMode(a) + "/" + Global.gint(a, "sbmods_cool", 0); }
    }

    // ------------------------------------------------------------------ 2. CPU

    static final class Cpu extends Card {
        private BarView bar;
        Cpu() { super("cpu"); }
        @Override View build(Activity a) {
            LinearLayout col = column(a);
            valueView(a);
            bar = new BarView(a, false);
            col.addView(bar, lp(-1, dp(a, 4), 0, dp(a, 1), 0, dp(a, 7)));
            col.addView(limitRow(a, "cpu"));
            return col;
        }
        @Override void live(Activity a, Perf.Snap s) {
            int load = Global.gint(a, "sbmods_cpu_load", -1);
            if (bar != null) bar.set(Math.max(0, load), loadColor(load));
            if (val != null) val.setText(rateText(load >= 0 ? load + "%" : "—", s.cpuCur > 0 ? Perf.ghz(s.cpuCur) : "—", s.cpuCap, s.cpuHw, true));
        }
        @Override String help(Activity a) { return limitHelp(a, "cpu"); }
        @Override String sig(Activity a) { return limitSig(a, "cpu"); }
    }

    // ------------------------------------------------------------------ 3. cores

    static final class Cores extends Card {
        private final BarView[] bars = new BarView[16];
        private final TextView[] speed = new TextView[16];
        private final View[] tiles = new View[16];
        private TextView minus, plus;
        Cores() { super("cores"); }

        private static int mid() { return Perf.groups.length == 3 ? Perf.groups[1].length : 0; }

        @Override View build(Activity a) {
            java.util.Arrays.fill(bars, null); java.util.Arrays.fill(speed, null); java.util.Arrays.fill(tiles, null);
            minus = null; plus = null;
            valueView(a);
            // one row: the per-core bars (small / mid / fast cores, a gap between the groups) and, at the right end, the - and + buttons
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout bw = new LinearLayout(a);
            for (int g = 0; g < Perf.groups.length; g++) {
                int[] cpus = Perf.groups[g];
                LinearLayout grp = new LinearLayout(a);
                for (int cpu : cpus) {
                    if (cpu < 0 || cpu >= 16) continue;
                    LinearLayout tile = column(a);
                    tile.setGravity(Gravity.CENTER_HORIZONTAL);
                    bars[cpu] = new BarView(a, true);
                    tile.addView(bars[cpu], new LinearLayout.LayoutParams(dp(a, 12), dp(a, 26)));
                    speed[cpu] = tv(a, "—", 9, DIM, false);
                    speed[cpu].setFontFeatureSettings("tnum");
                    speed[cpu].setGravity(Gravity.CENTER);
                    speed[cpu].setSingleLine(true);
                    tile.addView(speed[cpu], lp(-2, -2, 0, dp(a, 1), 0, 0));
                    tiles[cpu] = tile;
                    grp.addView(tile, new LinearLayout.LayoutParams(0, -2, 1));
                }
                bw.addView(grp, lp(0, -2, g == 0 ? 0 : dp(a, 10), 0, 0, 0, Math.max(1, cpus.length)));
            }
            row.addView(bw, new LinearLayout.LayoutParams(0, -2, 1));
            if (mid() > 0) {
                minus = stepButton(a, "−", -1);
                plus = stepButton(a, "+", 1);
                row.addView(minus, lp(dp(a, 40), dp(a, 40), dp(a, 12), 0, dp(a, 8), 0));
                row.addView(plus, new LinearLayout.LayoutParams(dp(a, 40), dp(a, 40)));
            }
            return row;
        }

        private TextView stepButton(Activity a, String sign, int delta) {
            TextView b = button(a, sign, () -> {
                int n = Math.max(1, Math.min(mid(), PanelMath.midAwake(Global.gstr(a, "sbmods_cores")) + delta));
                Global.gset(a, "sbmods_cores", PanelMath.coresFor(n));
                used(a, "cores");
                PerfPanel.changed(a);
                live(a, Perf.read());
            });
            b.setPadding(0, 0, 0, 0);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            return b;
        }

        @Override String help(Activity a) {
            return mid() > 0 ? "Resting cores save power. They wake by themselves if the phone gets busy, after the limit time, or on restart." : null;
        }

        @Override void live(Activity a, Perf.Snap s) {
            int[] load = new int[16];
            java.util.Arrays.fill(load, -1);
            String cl = Global.gstr(a, "sbmods_core_load");
            if (cl != null) {
                String[] p = cl.split(",");
                for (int i = 0; i < p.length && i < 16; i++) try { load[i] = Integer.parseInt(p[i].trim()); } catch (NumberFormatException ignored) {}
            }
            int awake = PanelMath.midAwake(Global.gstr(a, "sbmods_cores"));
            boolean[] rest = new boolean[16];
            if (mid() > 0) {
                // the phone decides which mid cores rest, and we do not read kernel files for it: show the idlest ones (offline = -1 first) as resting
                Integer[] m = new Integer[mid()];
                for (int i = 0; i < m.length; i++) m[i] = Perf.groups[1][i];
                java.util.Arrays.sort(m, (x, y) -> Integer.compare(x < 16 ? load[x] : 0, y < 16 ? load[y] : 0));
                for (int i = 0; i < mid() - awake; i++) if (m[i] < 16) rest[m[i]] = true;
                if (val != null) val.setText("Mid cores awake: " + awake);
                if (minus != null) minus.setAlpha(awake <= 1 ? 0.35f : 1f);
                if (plus != null) plus.setAlpha(awake >= mid() ? 0.35f : 1f);
            }
            for (int g = 0; g < Perf.groups.length; g++) for (int cpu : Perf.groups[g]) {
                if (cpu < 0 || cpu >= 16 || bars[cpu] == null) continue;
                int cur = g < s.polCur.length ? s.polCur[g] : -1, hw = g < s.polHw.length ? s.polHw[g] : -1;
                int pct = cur > 0 && hw > 0 ? Math.min(100, Math.round(cur * 100f / hw)) : -1;
                tiles[cpu].setAlpha(rest[cpu] ? 0.4f : 1f);
                bars[cpu].set(rest[cpu] ? 0 : Math.max(0, load[cpu]), loadColor(load[cpu]));
                speed[cpu].setText(rest[cpu] ? "zz" : pct >= 0 ? pct + "%" : "—");
            }
        }
    }

    // ------------------------------------------------------------------ 4. GPU

    static final class Gpu extends Card {
        private BarView bar;
        Gpu() { super("gpu"); }
        @Override View build(Activity a) {
            LinearLayout col = column(a);
            valueView(a);
            bar = new BarView(a, false);
            col.addView(bar, lp(-1, dp(a, 4), 0, dp(a, 1), 0, dp(a, 7)));
            col.addView(limitRow(a, "gpu"));
            return col;
        }
        @Override void live(Activity a, Perf.Snap s) {
            int busy = RecentsChip.gpuAvg(-2);
            if (bar != null) bar.set(Math.max(0, busy), loadColor(busy));
            if (val != null) val.setText(rateText(busy >= 0 ? busy + "% busy" : "—", s.gpuCur > 0 ? s.gpuCur + " MHz" : "—", s.gpuCap, s.gpuHw, false));
        }
        @Override String help(Activity a) { return "Busy is the average of the last 10 seconds.\n" + limitHelp(a, "gpu"); }
        @Override String sig(Activity a) { return limitSig(a, "gpu"); }
    }

    // ------------------------------------------------------------------ 5. options

    static final class Switches extends Card {
        Switches() { super("switches"); }
        @Override View build(Activity a) {
            LinearLayout row = new LinearLayout(a);
            row.setBaselineAligned(false);   // one- and two-line buttons side by side: lining up their text pushed the short one up and the tall ones out of the card
            int at = Global.gint(a, "sbmods_cool_at", 360);
            row.addView(toggle(a, "Power saving with limit", "sbmods_cpu_ps"), lp(0, -1, 0, 0, dp(a, 6), 0, 1));
            row.addView(toggle(a, "Cool-down to " + at / 10 + "°C", "sbmods_cool"), lp(0, -1, 0, 0, dp(a, 6), 0, 1));
            TextView reset = pill(a, "Full speed", false, () -> {
                for (String k : new String[]{"sbmods_cpu_pct", "sbmods_gpu_pct", "sbmods_cool", "sbmods_cores"}) Global.gset(a, k, "0");
                used(a, "switches");
                PerfPanel.changed(a);
            });
            reset.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            row.addView(reset, new LinearLayout.LayoutParams(0, -1, 1));
            return row;
        }

        @Override String help(Activity a) {
            int min = Global.gint(a, "sbmods_limit_min", 15);
            return "Power saving with limit: also saves power while the CPU is limited.\n"
                    + "Cool-down: use the levels above until the phone is back to " + Global.gint(a, "sbmods_cool_at", 360) / 10 + "°C, then stop.\n"
                    + "Full speed: removes every limit.\n"
                    + "Limits end by themselves after " + (min >= 60 ? min / 60 + " h" : min + " min") + " and never survive a restart.";
        }

        @Override String sig(Activity a) { return Global.gint(a, "sbmods_cpu_ps", 0) + "/" + Global.gint(a, "sbmods_cool", 0) + "/" + Global.gint(a, "sbmods_cool_at", 360); }

        private static TextView toggle(Activity a, String label, String key) {
            TextView[] self = new TextView[1];
            self[0] = pill(a, label, Global.gint(a, key, 0) == 1, () -> {
                boolean v = Global.gint(a, key, 0) != 1;
                Global.gset(a, key, v ? "1" : "0");
                pillStyle(self[0], v);
                used(a, "switches");
                PerfPanel.pChangedAt = SystemClock.uptimeMillis();
                RecentsChip.main.postDelayed(() -> { try { RecentsChip.refresh(a); } catch (Throwable t) { Log.w("toggle refresh: " + t); } }, 1500);
            });
            return self[0];
        }
    }

    // ------------------------------------------------------------------ 5b. games (built only when the panel opens; nothing runs while it is closed)

    static final class Games extends Card {
        Games() { super("games"); }
        @Override String help(Activity a) {
            return "The games you opened lately. \"Full speed\" on: the game always runs without a limit. Off: it is slowed down to the level you picked last "
                    + "(the same automatic rule as in Smart suggestions). Needs Smart suggestions and Game mode in PulseBar.";
        }
        @Override View build(Activity a) {
            LinearLayout col = column(a);
            List<String> games = Habits.parseGames(Global.gstr(a, "sbmods_games"));
            if (games.isEmpty()) { col.addView(tv(a, "Open a game and it shows up here.", 14, DIM, false)); return col; }
            for (int i = 0; i < games.size(); i++) col.addView(row(a, games.get(i)), lp(-1, -2, 0, i == 0 ? 0 : dp(a, 4), 0, 0));
            return col;
        }
        private static String str(String s) { return s == null ? "" : s; }
        private View row(Activity a, String pkg) {
            LinearLayout r = new LinearLayout(a);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setMinimumHeight(dp(a, 44));
            String label = pkg;
            try { PackageManager pm = a.getPackageManager(); label = pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString(); } catch (Throwable ignored) {}
            LinearLayout names = column(a);
            names.addView(oneLine(tv(a, label, 14, WHITE, false)));
            TextView st = oneLine(tv(a, "", 12, DIM, false));
            names.addView(st);
            r.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            TextView[] self = new TextView[1];
            boolean full = Habits.ruleOf(Global.gstr(a, "sbmods_rules"), pkg) == null;
            st.setText(full ? "Full" : "Limited");
            self[0] = pill(a, "Full speed", full, () -> {
                String rules = str(Global.gstr(a, "sbmods_rules"));
                boolean wasFull = Habits.ruleOf(rules, pkg) == null;
                if (wasFull) {   // limited from now on: the level chosen last (55 % if none), applied by Smart like any rule
                    int pc = Global.gint(a, "sbmods_cpu_pick", 55), pg = Global.gint(a, "sbmods_gpu_pick", 55);
                    if (pc + pg == 0) { pc = 55; pg = 55; }
                    Global.gset(a, "sbmods_rules", Habits.addRule(rules, pkg, pc, pg));
                } else Global.gset(a, "sbmods_rules", Habits.removeRule(rules, pkg));
                pillStyle(self[0], !wasFull);
                st.setText(wasFull ? "Limited" : "Full");
                used(a, "games");
            });
            self[0].setMinWidth(dp(a, 96));
            r.addView(self[0], lp(-2, -2, dp(a, 10), 0, 0, 0));
            return r;
        }
    }

    // ------------------------------------------------------------------ 6. power saving (the phone's own switch)

    /** The text of a toggle tile: title 14 sp medium, state 12 sp; black on the accent fill while on. */
    private static View tileText(Activity a, Card k, boolean on, String state) {
        LinearLayout col = column(a);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(tvm(a, k.title, 14, on ? onAcc() : WHITE));
        col.addView(oneLine(tv(a, state, 12, on ? onAccDim() : DIM, false)));
        return col;
    }

    static final class PowerSave extends Card {
        PowerSave() { super("powersave"); }
        @Override boolean ownHeader() { return true; }
        @Override boolean half() { return true; }
        @Override boolean tapTile() { return true; }
        @Override boolean on(Activity a) { return Global.gint(a, "low_power", 0) == 1; }
        @Override void tap(Activity a) {
            Global.gset(a, "low_power", on(a) ? "0" : "1");   // the phone's own power saving, so quick settings and the battery screen agree
            used(a, "powersave");
        }
        @Override String icon() { return "leaf"; }
        @Override String state(Activity a) { return on(a) ? "On" : "Off"; }
        @Override View build(Activity a) { boolean on = on(a); return tileText(a, this, on, state(a)); }
        @Override String help(Activity a) { return "The phone's own power saving, the same switch as in quick settings."; }
        @Override String sig(Activity a) { return String.valueOf(Global.gint(a, "low_power", 0)); }
    }

    // ------------------------------------------------------------------ 7. screen smoothness (Samsung's own setting)

    static final class Smooth extends Card {
        Smooth() { super("smooth"); }
        @Override boolean ownHeader() { return true; }
        @Override boolean half() { return true; }
        @Override boolean tapTile() { return true; }
        @Override boolean on(Activity a) { return Global.sint(a, "refresh_rate_mode", 1) != 0 && Global.hzCap(a) != 60; }   // 0 = Standard (60 Hz), 1 = Adaptive (up to 120 Hz), 2 = High
        @Override void tap(Activity a) { pick(a); }

        /** A small list: Smooth 120 / 96 / 60. Applies at once; the screen still slows down by itself when nothing moves. */
        private void pick(final Activity a) {
            try {
                Ui.Pal p = Ui.cur;
                final int cur = Global.sint(a, "refresh_rate_mode", 1) == 0 ? 60 : Global.hzCap(a) == 0 ? 120 : Global.hzCap(a);
                LinearLayout box = new LinearLayout(a);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(a, 6), dp(a, 6), dp(a, 6), dp(a, 6));
                box.setBackground(Ui.round(p.night ? 0xFF2B3136 : 0xFFF6F8F9, dp(a, Ui.R_TILE), 1, p.night ? 0xFF434B52 : 0xFFD5DCE0));
                final android.widget.PopupWindow pw = new android.widget.PopupWindow(box, dp(a, 250), -2, true);
                pw.setOutsideTouchable(true);
                pw.setElevation(dp(a, 8));
                final int[] v = {120, 96, 60};
                String[] t = {"Smoothest · up to 120", "Balanced · up to 96", "Saver · up to 60"};
                for (int i = 0; i < 3; i++) {
                    final int hz = v[i];
                    TextView r = tv(a, (hz == cur ? "✓  " : "     ") + t[i], 15, hz == cur ? p.accInk : p.tx, hz == cur);
                    r.setGravity(Gravity.CENTER_VERTICAL);
                    r.setPadding(dp(a, 14), 0, dp(a, 14), 0);
                    r.setOnClickListener(safe(() -> {
                        pw.dismiss();
                        if (hz != 60 && Global.sint(a, "refresh_rate_mode", 1) == 0) Global.sput(a, "refresh_rate_mode", "1");   // Standard would hold it at 60 whatever the cap
                        Global.hzSet(a, hz == 120 ? 0 : hz);
                        used(a, "smooth");
                        PerfPanel.rebuild(a);
                    }));
                    box.addView(r, new LinearLayout.LayoutParams(-1, dp(a, 48)));
                }
                if (PerfPanel.pBox != null) pw.showAtLocation(PerfPanel.pBox, Gravity.CENTER, 0, 0);
            } catch (Throwable t) { Log.w("hz picker: " + t); }
        }
        @Override String icon() { return "gauge"; }
        @Override String state(Activity a) { int v = Global.sint(a, "refresh_rate_mode", 1), c = Global.hzCap(a); return v == 0 ? "Standard · 60" : c > 0 ? "Up to " + c : v == 2 ? "Smooth · 120" : "Smooth · up to 120"; }
        @Override View build(Activity a) { return tileText(a, this, on(a), state(a)); }
        @Override String help(Activity a) { return "Tap to choose the top speed of the screen: 120, 96 or 60 times a second. Lower saves battery; the screen still slows down by itself when nothing moves."; }
        @Override String sig(Activity a) { return Global.sint(a, "refresh_rate_mode", 1) + ":" + Global.hzCap(a); }
    }

    // ------------------------------------------------------------------ 8. battery report

    static final class Battery extends Card {
        private TextView onT, offT, verdict, err, sleepT, deepT, awakeT;
        private static long reqAt, sentAt;
        private static long[] lastGood;
        private static PanelMath.Sleep lastSleep;
        Battery() { super("battery"); }
        @Override View build(Activity a) {
            reqAt = 0; sentAt = 0;   // asks again as soon as the page opens
            LinearLayout col = column(a);
            valueView(a).setText("—");
            onT = tv(a, "", 13, WHITE, false);
            onT.setFontFeatureSettings("tnum");
            col.addView(onT, lp(-2, -2, 0, dp(a, 1), 0, 0));
            offT = tv(a, "", 13, WHITE, false);
            offT.setFontFeatureSettings("tnum");
            col.addView(offT, lp(-2, -2, 0, dp(a, 1), 0, 0));
            verdict = tv(a, "", 13, WHITE, true);
            col.addView(verdict, lp(-2, -2, 0, dp(a, 1), 0, 0));
            sleepT = tv(a, "", 13, WHITE, true);
            sleepT.setFontFeatureSettings("tnum");
            sleepT.setVisibility(View.GONE);
            col.addView(sleepT, lp(-2, -2, 0, dp(a, 6), 0, 0));
            deepT = tv(a, "", 13, WHITE, false);
            deepT.setFontFeatureSettings("tnum");
            deepT.setVisibility(View.GONE);
            col.addView(deepT, lp(-2, -2, 0, dp(a, 1), 0, 0));
            awakeT = tv(a, "", 13, WHITE, false);
            awakeT.setVisibility(View.GONE);
            col.addView(awakeT, lp(-2, -2, 0, dp(a, 1), 0, 0));
            err = tv(a, "No answer from the helper – turn it on in PulseBar › System.", 13, Ui.cur.warn, false);
            err.setVisibility(View.GONE);
            col.addView(err, lp(-2, -2, 0, dp(a, 2), 0, 0));
            col.addView(button(a, "Battery usage ›", () -> openScreen(a, "battery",
                    new Intent("com.samsung.android.sm.ACTION_BATTERY").setPackage("com.samsung.android.lool").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    new Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Battery settings aren't available")),
                    lp(-1, -2, 0, dp(a, 8), 0, 0));
            return col;
        }
        private static String screen(String what, long sec, double rate) {
            String d = sec < 0 ? null : PanelMath.duration(PanelMath.minutes(sec));
            String r = rate < 0 ? null : String.format(Locale.US, "%.1f%% per hour", rate);
            if (d == null && r == null) return what + ": not enough data yet";
            return what + (d == null ? "" : " " + d) + (r == null ? "" : "  ·  " + r);
        }
        @Override void live(Activity a, Perf.Snap s) {
            if (val == null || onT == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - reqAt >= 60000) {
                reqAt = now;
                PanelCmd.post(a, "battreport", () -> { sentAt = SystemClock.uptimeMillis(); Global.gset(a, "sbmods_batt", ""); });
            }
            long[] b = PanelMath.parseBatt(Global.gstr(a, "sbmods_batt"));
            if (b != null) lastGood = b;
            b = lastGood;
            err.setVisibility(b == null && sentAt > 0 && now - sentAt > 12000 ? View.VISIBLE : View.GONE);
            if (b == null) { val.setText(Power.charging ? "Charging" : "—"); onT.setText(""); offT.setText(""); verdict.setText(""); return; }
            val.setText(Power.charging ? "Charging" : Power.status == BatteryManager.BATTERY_STATUS_FULL ? "Fully charged" : b[0] >= 0 ? "About " + PanelMath.duration(b[0]) + " left" : "—");
            onT.setText(screen("Screen on", b[1], PanelMath.screenOnRate(b)));
            offT.setText(screen("Screen off", b[2], PanelMath.screenOffRate(b)));
            int v = PanelMath.standbyVerdict(PanelMath.screenOffRate(b));
            verdict.setVisibility(v < 0 ? View.GONE : View.VISIBLE);
            verdict.setText(v == 0 ? "Standby drain is normal" : v == 1 ? "Standby drain is a bit high" : "Standby drain is high – something keeps the phone awake");
            verdict.setTextColor(v == 0 ? Ui.cur.ok : v == 1 ? Ui.cur.warn : Ui.cur.err);
            sleepLines(a, Global.gstr(a, "sbmods_batt"), b[2]);
        }
        /** How well the phone slept with the screen off, and who kept it awake. Same answer as above: no extra request. */
        private void sleepLines(Activity a, String raw, long offSec) {
            PanelMath.Sleep sl = PanelMath.parseSleep(raw);
            if (sl != null) lastSleep = sl;
            sl = lastSleep;
            int pct = PanelMath.sleepPct(offSec, sl);
            if (sl == null || pct < 0) { sleepT.setVisibility(View.GONE); deepT.setVisibility(View.GONE); awakeT.setVisibility(View.GONE); return; }
            int sv = PanelMath.sleepVerdict(pct);
            sleepT.setVisibility(View.VISIBLE);
            sleepT.setText("Slept " + PanelMath.duration(PanelMath.minutes(sl.lightSec + sl.deepSec)) + " of " + PanelMath.duration(PanelMath.minutes(offSec)) + " with the screen off (" + pct + "%)");
            sleepT.setTextColor(sv == 0 ? Ui.cur.ok : sv == 1 ? Ui.cur.warn : Ui.cur.err);
            deepT.setVisibility(View.VISIBLE);
            deepT.setText("Deep sleep " + PanelMath.duration(PanelMath.minutes(sl.deepSec)) + "  ·  light sleep " + PanelMath.duration(PanelMath.minutes(sl.lightSec)));
            StringBuilder t = new StringBuilder();
            String topPkg = null;
            if (!sl.awake.isEmpty()) {
                t.append("Kept awake most by ");
                PackageManager pm = a.getPackageManager();
                for (int i = 0; i < sl.awake.size(); i++) {
                    String pkg = sl.awake.get(i)[0];
                    String name = pkg;
                    try { name = pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString(); } catch (Throwable ignored) {}
                    if (i == 0) topPkg = pkg;
                    t.append(i > 0 ? ", " : "").append(name).append(' ').append(Math.max(1, PanelMath.minutes(Long.parseLong(sl.awake.get(i)[1])))).append(" min");
                }
                t.append(" ›");
            }
            if (!sl.woken.isEmpty()) {
                t.append(t.length() > 0 ? "\n" : "").append("Woken most by ");
                for (int i = 0; i < sl.woken.size(); i++) t.append(i > 0 ? " and " : "").append(sl.woken.get(i));
            }
            awakeT.setVisibility(t.length() > 0 ? View.VISIBLE : View.GONE);
            awakeT.setText(t);
            final String open = topPkg;
            awakeT.setOnClickListener(open == null ? null : v -> openScreen(a, "battery-app",
                    new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + open)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    null, "App info isn't available"));
        }
        @Override String help(Activity a) { return "Since the last full charge. Power draw now is shown at the top of the panel. "
                + "\"Slept\" is the time the phone dozed with the screen off; most of it should be sleep. Tap the app line to open that app's info, "
                + "where Samsung's Battery setting can put it to sleep. Light sleep and deep sleep both save power."; }
    }

    // ------------------------------------------------------------------ graphics (Vulkan) and battery health

    /** The app used last (not the home screen), or null. */
    static String lastApp(Activity a) {
        try {
            android.app.ActivityManager am = a.getSystemService(android.app.ActivityManager.class);
            for (android.app.ActivityManager.RecentTaskInfo t : am.getRecentTasks(4, 0)) {
                android.content.ComponentName cn = t.baseIntent == null ? null : t.baseIntent.getComponent();
                if (cn != null && !cn.getPackageName().equals(Entry.LAUNCHER)) return cn.getPackageName();
            }
        } catch (Throwable t) { Log.w("last app: " + t); }
        return null;
    }

    /** Normal drawing or Vulkan for apps, and what the last app really uses. The helper never restarts anything and guards Wi-Fi calling. */
    static final class Graphics extends Card {
        private TextView line;
        private String asked;
        private CharSequence askedName;
        Graphics() { super("graphics"); }
        @Override boolean ownHeader() { return true; }
        @Override View build(Activity a) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout left = column(a);
            left.addView(titleView(a));
            line = tv(a, "", 12, WHITE, false);
            line.setMaxLines(2);
            line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            left.addView(line);
            row.addView(left, lp(0, -2, 0, 0, dp(a, 10), 0, 1));
            final boolean vk = "vulkan".equals(Global.gstr(a, "sbmods_gfx"));
            row.addView(seg(a, new CharSequence[]{"Normal", "Vulkan"}, vk ? 1 : 0, i -> i == 0 ? Ui.cur.ok : Ui.cur.acc, i -> {
                if ((i == 1) == vk) return;
                used(a, "graphics");
                PanelCmd.post(a, i == 1 ? "gfx:vulkan" : "gfx:opengl", null);
                android.widget.Toast.makeText(a, i == 1 ? "Vulkan: apps use it the next time they open" : "Normal drawing: apps switch the next time they open", android.widget.Toast.LENGTH_SHORT).show();
            }), new LinearLayout.LayoutParams(dp(a, 170), -2));
            asked = lastApp(a);
            askedName = asked;
            if (asked != null) {
                try { PackageManager pm = a.getPackageManager(); askedName = pm.getApplicationInfo(asked, 0).loadLabel(pm); } catch (Throwable ignored) {}
                PanelCmd.post(a, "appgfx:" + asked, () -> Global.gset(a, "sbmods_appgfx", "-"));
            }
            return row;
        }
        @Override String help(Activity a) {
            return "Vulkan is a newer way for apps to draw the screen. An app switches the next time it opens, and a restart of the phone goes back to normal. "
                    + "Calling is never touched; if Wi-Fi calling drops after switching, the phone goes back to normal by itself.";
        }
        @Override void live(Activity a, Perf.Snap s) {
            if (line == null) return;
            if (asked == null) { line.setVisibility(View.GONE); return; }
            String r = Global.gstr(a, "sbmods_appgfx");
            String how = r != null && r.startsWith(asked + ":") ? r.substring(asked.length() + 1) : "";
            line.setText(how.equals("vulkan") ? askedName + " is drawing with Vulkan right now"
                    : how.equals("opengl") ? askedName + " is drawing the normal way right now"
                    : how.equals("unknown") ? askedName + ": can't tell (it is asleep)" : "Checking " + askedName + "…");
            line.setTextColor(how.equals("vulkan") ? Ui.cur.acc : how.equals("opengl") ? WHITE : DIM);
        }
        @Override String sig(Activity a) { return String.valueOf(Global.gstr(a, "sbmods_gfx")); }
    }

    /** How worn the battery is, from the phone's own counters (asked once a minute while the page shows). */
    static final class Health extends Card {
        private TextView line;
        private long reqAt;
        Health() { super("health"); }
        @Override View build(Activity a) {
            reqAt = 0;
            LinearLayout col = column(a);
            valueView(a).setText("Checking…");
            line = tv(a, "", 13, WHITE, false);
            col.addView(line, lp(-2, -2, 0, dp(a, 1), 0, 0));
            col.addView(button(a, "Battery protection ›", () -> openScreen(a, "health",
                    new Intent("com.samsung.android.sm.ACTION_BATTERY_PROTECTION").setPackage("com.samsung.android.lool").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    null, "Battery protection isn't available")), lp(-1, -2, 0, dp(a, 8), 0, 0));
            return col;
        }
        @Override String help(Activity a) { return "How much charge the battery holds now compared with when it was new. The charge limit is set in Samsung's own battery protection screen."; }
        @Override void live(Activity a, Perf.Snap s) {
            if (val == null || line == null) return;
            long now = SystemClock.uptimeMillis();
            if (reqAt == 0 || now - reqAt >= 60000) { reqAt = now; PanelCmd.post(a, "health", null); }
            String r = Global.gstr(a, "sbmods_health");
            if (r == null) return;
            String[] p = r.split("\\|");
            if (p.length < 3) return;
            val.setText(!p[0].equals("-1") ? "Holds about " + p[0] + "%" : "");
            StringBuilder t = new StringBuilder();
            if (!p[1].equals("-1")) t.append("Charged ").append(p[1]).append(" times");
            if (!p[2].equals("-1") && !p[2].equals("100")) t.append(t.length() > 0 ? "  ·  charging" : "Charging").append(" stops at ").append(p[2]).append("%");
            line.setText(t.length() > 0 || !p[0].equals("-1") ? t : "The phone doesn't report battery health");
        }
    }

    // ------------------------------------------------------------------ 9. memory

    static final class Memory extends Card {
        private TextView ramT, swapT, pressT, zT, plusT;
        private BarView ramB, swapB;
        private long reqAt;
        Memory() { super("memory"); }
        @Override boolean ownHeader() { return true; }
        @Override String help(Activity a) {
            return "Samsung keeps part of memory squeezed (compressed), so Swap is often high and that is normal. "
                    + "Pressure is the number that matters: \"none\" means apps never waited for memory in the last minute. "
                    + "Closing apps only helps when pressure is high; otherwise they have to start from scratch again and that costs battery.";
        }
        @Override View build(Activity a) {
            reqAt = 0;
            LinearLayout col = column(a);
            ramB = new BarView(a, false); swapB = new BarView(a, false);
            LinearLayout row = new LinearLayout(a);
            ramT = memCol(a, row, "Memory", ramB, dp(a, 6));
            swapT = memCol(a, row, "Swap", swapB, dp(a, 6));
            col.addView(row);
            pressT = tv(a, "", 13, WHITE, false);
            pressT.setVisibility(View.GONE);
            col.addView(pressT, lp(-2, -2, 0, dp(a, 6), 0, 0));
            zT = tv(a, "", 13, WHITE, false);
            zT.setFontFeatureSettings("tnum");
            zT.setVisibility(View.GONE);
            col.addView(zT, lp(-2, -2, 0, dp(a, 1), 0, 0));
            plusT = tv(a, "", 13, DIM, false);
            plusT.setVisibility(View.GONE);
            col.addView(plusT, lp(-2, -2, 0, dp(a, 1), 0, 0));
            LinearLayout btns = new LinearLayout(a);
            TextView close = button(a, "Close apps", () -> { used(a, "memory"); RecentsChip.command(a, "ramclean", "Closing background apps…", 4000); });
            TextView deep = button(a, "Deep clean", () -> { used(a, "memory"); RecentsChip.command(a, "deepclean", "Deep cleaning memory…", 6000); });
            btns.addView(close, lp(0, -2, 0, dp(a, 8), dp(a, 8), 0, 1));
            btns.addView(deep, lp(0, -2, 0, dp(a, 8), 0, 0, 1));
            col.addView(btns);
            return col;
        }
        private static TextView memCol(Activity a, LinearLayout row, String label, BarView bar, int gap) {
            LinearLayout c = column(a);
            TextView v = oneLine(tvm(a, label + " —", 13, WHITE));
            v.setFontFeatureSettings("tnum");
            c.addView(v);
            c.addView(bar, lp(-1, dp(a, 5), 0, dp(a, 5), 0, 0));
            row.addView(c, lp(0, -2, 0, 0, row.getChildCount() == 0 ? gap * 2 : 0, 0, 1));
            return v;
        }
        @Override void live(Activity a, Perf.Snap s) {
            if (ramT == null) return;
            RecentsChip.Mem m = RecentsChip.readMem();
            int ram = m.totalMb > 0 ? (int) Math.round((m.totalMb - m.availMb) * 100.0 / m.totalMb) : 0;
            ramT.setText("Memory " + ram + "%");
            ramB.set(ram, ram >= 90 ? Ui.cur.err : ram >= 80 ? Ui.cur.warn : Ui.cur.ok);
            swapT.setText("Swap " + m.swapPct + "%");
            swapB.set(m.swapPct, ram >= 90 ? Ui.cur.err : ram >= 80 ? Ui.cur.warn : Ui.cur.ok);   // a full swap is normal on Samsung: the colour follows free memory, not swap
            long now = SystemClock.uptimeMillis();
            if (reqAt == 0 || now - reqAt >= 60000) { reqAt = now; PanelCmd.post(a, "mem", null); }   // one tiny read as root, only while this page shows
            String r = Global.gstr(a, "sbmods_mem");
            double some = -1;
            if (r != null) { String[] p = r.split("\\|"); try { some = Double.parseDouble(p[0].trim()); } catch (Throwable ignored) {} }
            pressT.setVisibility(some >= 0 ? View.VISIBLE : View.GONE);
            if (some >= 0) {
                int pv = some < 1 ? 0 : some < 10 ? 1 : 2;
                pressT.setText("Pressure: " + (pv == 0 ? "none" : pv == 1 ? "some" : "high"));
                pressT.setTextColor(pv == 0 ? Ui.cur.ok : pv == 1 ? Ui.cur.warn : Ui.cur.err);
            }
            long[] z = zram();
            zT.setVisibility(z != null ? View.VISIBLE : View.GONE);
            if (z != null) zT.setText(String.format(Locale.US, "Compressed: %.1f GB squeezed into %.1f GB (%.1fx)", z[0] / 1073741824.0, z[1] / 1073741824.0, z[0] / (double) Math.max(1, z[1])));
            int plus = Global.gint(a, "ram_expand_size", -1);
            plusT.setVisibility(plus >= 0 ? View.VISIBLE : View.GONE);
            if (plus >= 0) plusT.setText(plus == 0 ? "RAM Plus: off" : "RAM Plus: " + plus + " GB");
        }
        /** {original bytes, bytes of RAM it now takes} from the compressed-memory statistics (readable without root), or null. */
        private static long[] zram() {
            try {
                String[] f = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/sys/block/zram0/mm_stat"))).trim().split("\\s+");
                long orig = Long.parseLong(f[0]), used = Long.parseLong(f[2]);
                return orig > 0 && used > 0 ? new long[]{orig, used} : null;
            } catch (Throwable t) { return null; }
        }
    }

    /** May the owner stop this app from the panel? The helper refuses the protected ones too (stopok); also never the calling services. */
    static boolean stoppable(Activity a, String pkg) {
        return !(NeverStop.listed(pkg) || pkg.equals(Entry.LAUNCHER) || pkg.equals("com.android.systemui") || pkg.equals(a.getPackageName())
                || pkg.startsWith("com.google.android.gms") || pkg.startsWith("com.android.providers.") || pkg.equals("android") || pkg.equals("dev.gitesh.sbmods") || pkg.equals("com.termux")
                || pkg.equals("com.sec.imsservice") || pkg.equals("com.sec.epdg"));
    }

    // ------------------------------------------------------------------ insights (a few plain suggestions, worked out only when the panel opens)

    /** "Smart suggestions" from the settings app, mirrored into a global because this runs in the launcher; missing = on. */
    static boolean smartOn(Context c) { return Global.gint(c, "sbmods_smart", 1) != 0; }

    private static String str(String s) { return s == null ? "" : s; }

    /** The owner turned a speed limit on by hand (Mode pick or a chip's long-press): remember it for the app used last. One settings write, only when something changed. */
    static void learn(Activity a, int cpu, int gpu) {
        try {
            if (!smartOn(a)) return;
            String p = lastApp(a);
            if (p == null || p.equals(Config.PKG) || p.equals(Config.SYSUI)) return;
            String cur = str(Global.gstr(a, "sbmods_habits"));
            String n = Habits.learn(cur, p, cpu, gpu, java.time.LocalDate.now().toEpochDay());
            if (!n.equals(cur)) Global.gput(a, "sbmods_habits", n);
        } catch (Throwable t) { Log.w("learn: " + t); }
    }

    static final class Insight extends Card {
        private static final long TOP_KEEP = 20000;   // a busiest-apps answer is reused for this long (a panel closed and opened again at once asks nothing)
        private static String topRaw;                 // the helper's last "top" answer
        private static long topAt;
        private static final java.util.Set<String> done = new java.util.HashSet<>();   // lines already acted on, kept until the panel is opened again
        private LinearLayout col;
        private boolean waiting, sent;
        Insight() { super("insights"); }
        /** The panel was opened: forget what was acted on. */
        static void fresh() { done.clear(); }
        @Override View build(Activity a) {
            if (!smartOn(a)) return null;   // switched off: the card is simply not there
            col = column(a);
            waiting = false; sent = false;
            if (SystemClock.uptimeMillis() - topAt >= TOP_KEEP) {   // the only extra helper request: one "top", and only when this card is on screen
                topRaw = null; waiting = true;
                PanelCmd.post(a, "top", () -> { sent = true; Global.gset(a, "sbmods_top", "-"); });
            }
            fill(a);
            return col;
        }
        /** Called every second like all cards, but does nothing except until the one "top" answer has come (then the lines are worked out once more). */
        @Override void live(Activity a, Perf.Snap s) {
            if (!waiting || !sent || col == null) return;
            String r = Global.gstr(a, "sbmods_top");
            if (r == null || r.equals("-")) return;
            waiting = false; topRaw = r; topAt = SystemClock.uptimeMillis();
            fill(a);
        }
        @Override String help(Activity a) {
            return "A few plain checks on numbers the phone already has: heat, busy apps, memory, a forgotten speed limit. "
                    + "They are worked out when you open the panel, never in the background. Switch them off with Smart suggestions in PulseBar.";
        }
        private void fill(Activity a) {
            col.removeAllViews();
            Power.read(a);
            Insights.In in = new Insights.In();
            in.tempC = Power.tempC; in.coolAtTenths = Global.gint(a, "sbmods_cool_at", 360); in.charging = Power.charging;
            boolean cool = Global.gint(a, "sbmods_cool", 0) == 1;
            int cp = Global.gint(a, "sbmods_cpu_pct", 0), gp = Global.gint(a, "sbmods_gpu_pct", 0);
            boolean parked = !PanelMath.normCores(Global.gstr(a, "sbmods_cores")).equals("0");
            in.heldBack = !cool && ((cp > 0 && cp < 100) || (gp > 0 && gp < 100) || parked);
            in.limitOn = cool || in.heldBack;
            long until = 0;
            try { String u = Global.gstr(a, "sbmods_until"); if (u != null && !u.isEmpty()) until = Long.parseLong(u.trim()); } catch (NumberFormatException ignored) {}
            in.limitSecLeft = until > 0 ? until - System.currentTimeMillis() / 1000 : 0;
            RecentsChip.Mem m = RecentsChip.readMem();
            in.ramUsedPct = m.totalMb > 0 ? (int) Math.round((m.totalMb - m.availMb) * 100.0 / m.totalMb) : 0;
            in.top = topRaw;
            in.front = in.top == null ? null : lastApp(a);
            in.canStop = p -> stoppable(a, p);
            in.label = p -> { try { PackageManager pm = a.getPackageManager(); return pm.getApplicationInfo(p, 0).loadLabel(pm).toString(); } catch (Throwable t) { return p; } };
            Habits.H hb = Habits.candidate(Global.gstr(a, "sbmods_habits"), Global.gstr(a, "sbmods_rules"));
            if (hb != null) { in.habitPkg = hb.pkg; in.habitName = in.label.apply(hb.pkg); }
            String keep = Global.gstr(a, "sbmods_keep");
            String front = lastApp(a);
            String[] idle = IdleApps.find(a, p -> stoppable(a, p) && !p.equals(front) && !Habits.isKept(keep, p));
            if (idle != null) { in.idlePkg = idle[0]; in.idleDays = Integer.parseInt(idle[1]); }
            in.gfxKeep = Global.gint(a, "sbmods_gfx_keep", 0) == 1;
            in.gfxVulkan = "vulkan".equals(Global.gstr(a, "sbmods_gfx"));
            List<Insights.Item> items = Insights.rank(in, done::contains);
            if (items.isEmpty()) { col.addView(tv(a, "Everything looks fine", 14, WHITE, false)); return; }
            for (int i = 0; i < items.size(); i++) {
                final Insights.Item it = items.get(i);
                LinearLayout row = new LinearLayout(a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(a, 44));
                row.addView(tv(a, it.text, 14, WHITE, false), new LinearLayout.LayoutParams(0, -2, 1));
                if (it.button2 != null) row.addView(button(a, it.button2, () -> {   // "No": remembered as declined
                    done.add(it.key());
                    Global.gset(a, "sbmods_habits", Habits.decline(str(Global.gstr(a, "sbmods_habits")), it.arg));
                    col.removeView(row);
                    if (col.getChildCount() == 0) col.addView(tv(a, "Everything looks fine", 14, WHITE, false));
                }), lp(-2, -2, dp(a, 10), 0, 0, 0));
                row.addView(button(a, it.button, () -> {
                    done.add(it.key());
                    act(a, it);
                    col.removeView(row);   // the line is dropped; the result shows as a message
                    if (col.getChildCount() == 0) col.addView(tv(a, "Everything looks fine", 14, WHITE, false));
                }), lp(-2, -2, dp(a, 10), 0, 0, 0));
                col.addView(row, lp(-1, -2, 0, i == 0 ? 0 : dp(a, 4), 0, 0));
            }
        }
        /** Each button runs a path that already exists elsewhere in the panel. */
        private static void act(Activity a, Insights.Item it) {
            switch (it.kind) {
                case Insights.HOT: {   // like the chips' long-press quick limit, for CPU and GPU: the level last chosen, ending after the usual time
                    int pc = Global.gint(a, "sbmods_cpu_pick", 55), pg = Global.gint(a, "sbmods_gpu_pick", 55);
                    Global.gput(a, "sbmods_cpu_pct", String.valueOf(pc));
                    Global.gput(a, "sbmods_gpu_pct", String.valueOf(pg));
                    Toast.makeText(a, "Cooling down: CPU " + pc + "%, GPU " + pg + "%", Toast.LENGTH_SHORT).show();
                    PerfPanel.changed(a);
                    break;
                }
                case Insights.APP: case Insights.IDLE: PanelCmd.stop(a, it.arg); break;   // shows the helper's answer; the helper checks stopok again
                case Insights.MEMORY: RecentsChip.command(a, "deepclean", "Deep cleaning memory…", 6000); break;
                case Insights.LIMIT:   // same as the Options card's Full speed
                    for (String k : new String[]{"sbmods_cpu_pct", "sbmods_gpu_pct", "sbmods_cool", "sbmods_cores"}) Global.gset(a, k, "0");
                    Toast.makeText(a, "Full speed", Toast.LENGTH_SHORT).show();
                    PerfPanel.changed(a);
                    break;
                case Insights.HABIT: {   // the rule takes the levels chosen by hand; Smart (status bar process) applies it
                    for (Habits.H x : Habits.parseHabits(Global.gstr(a, "sbmods_habits"))) if (x.pkg.equals(it.arg)) {
                        Global.gset(a, "sbmods_rules", Habits.addRule(str(Global.gstr(a, "sbmods_rules")), x.pkg, x.cpu, x.gpu));
                        String n = it.arg;
                        try { PackageManager pm = a.getPackageManager(); n = pm.getApplicationInfo(n, 0).loadLabel(pm).toString(); } catch (Throwable ignored) {}
                        Toast.makeText(a, "Rule saved: " + n + " will slow down automatically", Toast.LENGTH_LONG).show();
                    }
                    break;
                }
                case Insights.VULKAN:   // same as the Graphics card's Vulkan button
                    PanelCmd.post(a, "gfx:vulkan", null);
                    Toast.makeText(a, "Vulkan: apps use it the next time they open", Toast.LENGTH_SHORT).show();
                    break;
                default: break;
            }
        }
    }

    // ------------------------------------------------------------------ 10. busiest apps

    static final class TopApps extends Card {
        private LinearLayout rows;
        private TextView status;
        private long reqAt, sentAt;
        private String shownRaw;
        TopApps() { super("topapps"); }
        @Override View build(Activity a) {
            reqAt = 0; sentAt = 0; shownRaw = null;   // asks again as soon as the page opens
            LinearLayout col = column(a);
            status = tv(a, "Looking…", 14, DIM, false);
            col.addView(status);
            rows = column(a);
            col.addView(rows);
            return col;
        }
        @Override void live(Activity a, Perf.Snap s) {
            if (rows == null || status == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - reqAt >= 6000) {
                reqAt = now;
                PanelCmd.post(a, "top", () -> { sentAt = SystemClock.uptimeMillis(); Global.gset(a, "sbmods_top", "-"); });   // "-" = not answered yet
            }
            String raw = Global.gstr(a, "sbmods_top");
            if (raw == null || raw.equals("-")) {
                if (shownRaw == null) {
                    boolean late = sentAt > 0 && now - sentAt > 12000;
                    status.setText(late ? "No answer from the helper – turn it on in PulseBar › System." : "Looking…");
                    status.setTextColor(late ? Ui.cur.warn : DIM);
                }
                return;
            }
            if (raw.equals(shownRaw)) return;
            shownRaw = raw;
            rows.removeAllViews();
            int n = 0;
            for (String e : raw.split(",")) {
                int c = e.lastIndexOf(':');
                if (c <= 0) continue;
                String pkg = e.substring(0, c).trim();
                String pct = e.substring(c + 1).trim().replace("%", "");
                if (pkg.isEmpty()) continue;
                rows.addView(row(a, pkg, pct));
                n++;
            }
            status.setText(n == 0 ? "Nothing is working hard" : "");
            status.setTextColor(DIM);
            status.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        }

        private View row(Activity a, String pkg, String pct) {
            CharSequence label = pkg;
            android.graphics.drawable.Drawable icon = null;
            try {
                PackageManager pm = a.getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                label = ai.loadLabel(pm);
                icon = ai.loadIcon(pm);
            } catch (Throwable ignored) {}   // an app we cannot look up shows its package name
            LinearLayout r = new LinearLayout(a);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setMinimumHeight(dp(a, 40));
            ImageView iv = new ImageView(a);
            if (icon != null) iv.setImageDrawable(icon);
            r.addView(iv, lp(dp(a, 28), dp(a, 28), 0, 0, dp(a, 12), 0));
            TextView name = oneLine(tv(a, label, 14, WHITE, false));
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(tv(a, pct + "%", 14, WHITE, true));
            if (stoppable(a, pkg)) {
                TextView stop = tv(a, "Stop", 14, Ui.cur.acc, true);
                stop.setPadding(dp(a, 14), dp(a, 8), 0, dp(a, 8));
                stop.setOnClickListener(safe(() -> {
                    used(a, "topapps");
                    PanelCmd.stop(a, pkg);
                    reqAt = SystemClock.uptimeMillis() - 2000;   // look again soon after
                }));
                r.addView(stop);
            }
            return r;
        }
    }
}
