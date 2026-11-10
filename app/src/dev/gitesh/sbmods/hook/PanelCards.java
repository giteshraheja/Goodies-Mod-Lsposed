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
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;

import dev.gitesh.sbmods.PanelLayout;

/**
 * The cards of the Performance panel. A card builds its own view, and updates it on every refresh while the panel shows.
 * Which cards show, and in what order, comes from PanelLayout (the global setting). Nothing here may crash the launcher:
 * the panel calls build / live only through safeBuild / safeLive, and every click goes through safe().
 */
final class PanelCards {
    private PanelCards() {}

    static final int WHITE = Color.WHITE, DIM = 0x99FFFFFF;

    abstract static class Card {
        final String id, title;
        final int page;
        Card(String id) {
            int i = PanelLayout.indexOf(id);
            this.id = id; this.title = PanelLayout.TITLES[i]; this.page = PanelLayout.PAGES[i];
        }
        abstract View build(Activity a);
        /** Called on every refresh while the panel shows (about once a second). */
        void live(Activity a, Perf.Snap s) {}
        /** Changes when what the card shows was changed from elsewhere (for example a chip long-press): the panel then rebuilds it. */
        String sig(Activity a) { return ""; }
    }

    /** The registry, in default order. */
    static final List<Card> ALL = java.util.Arrays.asList(new Modes(), new Cpu(), new Cores(), new Gpu(), new Graphics(), new Switches(), new Battery(), new Health(), new Memory(), new TopApps());

    /** The visible cards of one page, in the owner's order. */
    static List<Card> visible(Context c, int page) {
        List<Card> out = new ArrayList<>();
        for (String id : PanelLayout.order(Global.gstr(c, PanelLayout.SETTING), PanelLayout.DEFAULTS))
            for (Card k : ALL) if (k.id.equals(id) && k.page == page) out.add(k);
        return out;
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

    /** A row of segments in one rounded track; sel = -1 selects none. Labels may carry spans (a small second line). */
    static LinearLayout seg(Activity a, CharSequence[] labels, int sel, IntUnaryOperator selColor, IntConsumer pick) {
        LinearLayout seg = new LinearLayout(a);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(0x22FFFFFF);
        sb.setCornerRadius(dp(a, 18));
        seg.setBackground(sb);
        seg.setPadding(dp(a, 3), dp(a, 3), dp(a, 3), dp(a, 3));
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean on = i == sel;
            TextView b = tv(a, labels[i], 14, on ? Color.BLACK : WHITE, on);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(a, 6), 0, dp(a, 6));
            if (on) { GradientDrawable ob = new GradientDrawable(); ob.setColor(selColor.applyAsInt(i)); ob.setCornerRadius(dp(a, 15)); b.setBackground(ob); }
            b.setOnClickListener(safe(() -> pick.accept(idx)));
            seg.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        return seg;
    }

    /** A flat pill button. */
    static TextView button(Activity a, String label, Runnable click) {
        TextView b = tv(a, label, 14, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(a, 12), dp(a, 9), dp(a, 12), dp(a, 9));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x22FFFFFF);
        g.setCornerRadius(dp(a, 18));
        b.setBackground(g);
        b.setOnClickListener(safe(click));
        return b;
    }

    static int loadColor(int pct) { return pct >= 85 ? RecentsChip.RED : pct >= 60 ? RecentsChip.AMBER : RecentsChip.GREEN; }

    /** "Now 1.9 GHz · allowed up to 2.4 GHz" (amber when something holds it back). */
    static CharSequence liveLine(String now, int cap, int hw, boolean cpu) {
        SpannableStringBuilder t = new SpannableStringBuilder("Now  ");
        int st = t.length();
        t.append(now);
        t.setSpan(new ForegroundColorSpan(WHITE), st, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (cap > 0 && hw > 0) {
            t.append("  ·  ");
            int s2 = t.length();
            boolean held = cap < hw * 0.97f;
            t.append(held ? "allowed up to " : "max ").append(cpu ? Perf.ghz(cap) : cap + " MHz");
            if (held) t.setSpan(new ForegroundColorSpan(RecentsChip.AMBER), s2, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return t;
    }

    /** The limit choices of the CPU or GPU, each showing the speed it allows. Behaviour as it always was, cool-down included. */
    static View limitRow(Activity a, String which) {
        boolean cpu = which.equals("cpu");
        Perf.Snap s = Perf.read();
        int hw = cpu ? s.cpuHw : s.gpuHw;
        int cur = RecentsChip.effPct(a, which, Global.gint(a, "sbmods_" + which + "_pct", 0), Global.gint(a, "sbmods_cool", 0) == 1);   // what is really in force
        CharSequence[] labels = new CharSequence[RecentsChip.PCTS.length];
        for (int i = 0; i < labels.length; i++) {
            int pct = RecentsChip.PCTS[i];
            boolean on = pct == cur || (i == 0 && cur == 0);
            int mhz = Perf.pick(cpu ? Perf.cpuSteps : Perf.gpuSteps, hw, pct);
            SpannableStringBuilder t = new SpannableStringBuilder(RecentsChip.PCT_NAMES[i]);
            if (mhz > 0) {
                int st = t.length();
                t.append("\n").append(cpu ? String.format(Locale.US, "%.1f", mhz / 1000f) : String.valueOf(mhz));
                t.setSpan(new RelativeSizeSpan(0.78f), st, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                t.setSpan(new ForegroundColorSpan(on ? 0xCC000000 : DIM), st, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            labels[i] = t;
        }
        int sel = -1;
        for (int i = 0; i < labels.length; i++) if (RecentsChip.PCTS[i] == cur || (i == 0 && cur == 0)) sel = i;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(seg(a, labels, sel, i -> i == 0 ? RecentsChip.GREEN : RecentsChip.CYAN, i -> {
            int pct = RecentsChip.PCTS[i];
            if (pct > 0) Global.gset(a, "sbmods_" + which + "_pick", String.valueOf(pct));
            boolean cooling = Global.gint(a, "sbmods_cool", 0) == 1;
            // cooling: a level just sets the cool-down level (ends with it); Full = this one at full speed while cooling (100)
            Global.gset(a, "sbmods_" + which + "_pct", cooling ? (pct > 0 ? "0" : "100") : String.valueOf(pct));
            PerfPanel.changed(a);
        }));
        TextView unit = tv(a, cpu ? "Speeds in GHz (fastest core)" : "Speeds in MHz", 11, 0x77FFFFFF, false);
        unit.setPadding(dp(a, 4), dp(a, 4), 0, 0);
        box.addView(unit);
        return box;
    }

    private static String limitSig(Activity a, String which) {
        return RecentsChip.effPct(a, which, Global.gint(a, "sbmods_" + which + "_pct", 0), Global.gint(a, "sbmods_cool", 0) == 1) + "";
    }

    /** Big number with a small caption beside it, then a bar. */
    private static TextView[] bigRow(Activity a, LinearLayout col, String caption, BarView bar) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.BOTTOM);
        TextView big = tv(a, "—", 30, WHITE, true);
        TextView cap = tv(a, caption, 13, DIM, false);
        cap.setPadding(dp(a, 8), 0, 0, dp(a, 5));
        r.addView(big);
        r.addView(cap);
        col.addView(r);
        col.addView(bar, lp(-1, dp(a, 6), 0, dp(a, 6), 0, dp(a, 8)));
        return new TextView[]{big, cap};
    }

    private static LinearLayout column(Activity a) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
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
            col.addView(seg(a, PanelMath.MODE_NAMES, m, i -> i == 0 ? RecentsChip.GREEN : RecentsChip.CYAN, i -> applyMode(a, i)));
            String say = m >= 0 ? PanelMath.MODE_TEXT[m]
                    : Global.gint(a, "sbmods_cool", 0) == 1 ? "Cool-down is on. Pick a mode to turn it off." : "Your own mix of the settings below.";
            LinearLayout r = new LinearLayout(a);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(a, 4), dp(a, 8), 0, 0);
            if (m < 0) {
                TextView custom = tv(a, "Custom", 12, RecentsChip.AMBER, true);
                custom.setPadding(0, 0, dp(a, 8), 0);
                r.addView(custom);
            }
            r.addView(tv(a, say, 13, DIM, false), new LinearLayout.LayoutParams(0, -2, 1));
            col.addView(r);
            return col;
        }
        @Override String sig(Activity a) { return currentMode(a) + "/" + Global.gint(a, "sbmods_cool", 0); }
    }

    // ------------------------------------------------------------------ 2. CPU

    static final class Cpu extends Card {
        private TextView big;
        private BarView bar;
        private TextView line;
        Cpu() { super("cpu"); }
        @Override View build(Activity a) {
            LinearLayout col = column(a);
            bar = new BarView(a, false);
            big = bigRow(a, col, "total load", bar)[0];
            line = tv(a, "", 13, DIM, false);
            col.addView(line, lp(-2, -2, 0, 0, 0, dp(a, 10)));
            col.addView(limitRow(a, "cpu"));
            return col;
        }
        @Override void live(Activity a, Perf.Snap s) {
            int load = Global.gint(a, "sbmods_cpu_load", -1);
            big.setText(load >= 0 ? load + "%" : "—");
            bar.set(Math.max(0, load), loadColor(load));
            line.setText(liveLine(s.cpuCur > 0 ? Perf.ghz(s.cpuCur) : "—", s.cpuCap, s.cpuHw, true));
        }
        @Override String sig(Activity a) { return limitSig(a, "cpu"); }
    }

    // ------------------------------------------------------------------ 3. cores

    static final class Cores extends Card {
        private final BarView[] bars = new BarView[16];
        private final TextView[] speed = new TextView[16];
        private final View[] tiles = new View[16];
        private TextView stepLabel, minus, plus;
        Cores() { super("cores"); }

        private static int mid() { return Perf.groups.length == 3 ? Perf.groups[1].length : 0; }

        @Override View build(Activity a) {
            LinearLayout col = column(a);
            LinearLayout row = new LinearLayout(a);
            String[] names = Perf.groups.length == 3 ? new String[]{"Small", "Mid", "Fast"} : null;
            for (int g = 0; g < Perf.groups.length; g++) {
                int[] cpus = Perf.groups[g];
                LinearLayout grp = column(a);
                LinearLayout tr = new LinearLayout(a);
                for (int cpu : cpus) {
                    if (cpu < 0 || cpu >= 16) continue;
                    LinearLayout tile = column(a);
                    tile.setGravity(Gravity.CENTER_HORIZONTAL);
                    bars[cpu] = new BarView(a, true);
                    tile.addView(bars[cpu], new LinearLayout.LayoutParams(dp(a, 12), dp(a, 46)));
                    speed[cpu] = tv(a, "—", 10, DIM, false);
                    speed[cpu].setGravity(Gravity.CENTER);
                    tile.addView(speed[cpu], lp(-2, -2, 0, dp(a, 3), 0, 0));
                    tiles[cpu] = tile;
                    tr.addView(tile, new LinearLayout.LayoutParams(0, -2, 1));
                }
                grp.addView(tr);
                if (names != null) {
                    TextView lab = tv(a, names[g], 10, 0x77FFFFFF, false);
                    lab.setGravity(Gravity.CENTER);
                    grp.addView(lab, lp(-1, -2, 0, dp(a, 2), 0, 0));
                }
                row.addView(grp, lp(0, -2, g == 0 ? 0 : dp(a, 10), 0, 0, 0, cpus.length));
            }
            col.addView(row);
            if (mid() > 0) {
                LinearLayout st = new LinearLayout(a);
                st.setGravity(Gravity.CENTER_VERTICAL);
                st.setPadding(0, dp(a, 12), 0, 0);
                stepLabel = tv(a, "", 14, WHITE, false);
                st.addView(stepLabel, new LinearLayout.LayoutParams(0, -2, 1));
                minus = stepButton(a, "−", -1);
                plus = stepButton(a, "+", 1);
                st.addView(minus, lp(dp(a, 40), dp(a, 36), 0, 0, dp(a, 8), 0));
                st.addView(plus, new LinearLayout.LayoutParams(dp(a, 40), dp(a, 36)));
                col.addView(st);
                col.addView(tv(a, "Resting cores save power. They wake by themselves if the phone gets busy, after the limit time, or on restart.", 12, 0x77FFFFFF, false), lp(-1, -2, 0, dp(a, 6), 0, 0));
            }
            return col;
        }

        private TextView stepButton(Activity a, String sign, int delta) {
            return button(a, sign, () -> {
                int n = Math.max(1, Math.min(mid(), PanelMath.midAwake(Global.gstr(a, "sbmods_cores")) + delta));
                Global.gset(a, "sbmods_cores", PanelMath.coresFor(n));
                PerfPanel.changed(a);
                live(a, Perf.read());
            });
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
                stepLabel.setText("Mid cores awake: " + awake);
                minus.setAlpha(awake <= 1 ? 0.35f : 1f);
                plus.setAlpha(awake >= mid() ? 0.35f : 1f);
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
        private TextView big, line;
        private BarView bar;
        Gpu() { super("gpu"); }
        @Override View build(Activity a) {
            LinearLayout col = column(a);
            bar = new BarView(a, false);
            big = bigRow(a, col, "busy (10 s average)", bar)[0];
            line = tv(a, "", 13, DIM, false);
            col.addView(line, lp(-2, -2, 0, 0, 0, dp(a, 10)));
            col.addView(limitRow(a, "gpu"));
            return col;
        }
        @Override void live(Activity a, Perf.Snap s) {
            int busy = RecentsChip.gpuAvg(-2);
            big.setText(busy >= 0 ? busy + "%" : "—");
            bar.set(Math.max(0, busy), loadColor(busy));
            line.setText(liveLine(s.gpuCur > 0 ? s.gpuCur + " MHz" : "—", s.gpuCap, s.gpuHw, false));
        }
        @Override String sig(Activity a) { return limitSig(a, "gpu"); }
    }

    // ------------------------------------------------------------------ 5. switches

    static final class Switches extends Card {
        Switches() { super("switches"); }
        @Override View build(Activity a) {
            LinearLayout col = column(a);
            col.addView(toggle(a, "Power saving while the CPU is limited", "sbmods_cpu_ps"));
            int at = Global.gint(a, "sbmods_cool_at", 360);
            col.addView(toggle(a, "Cool-down: use the levels above until " + at / 10 + "°C, then stop", "sbmods_cool"));
            LinearLayout foot = new LinearLayout(a);
            foot.setGravity(Gravity.CENTER_VERTICAL);
            foot.setPadding(0, dp(a, 10), 0, 0);
            int min = Global.gint(a, "sbmods_limit_min", 15);
            foot.addView(tv(a, "Limits end by themselves after " + (min >= 60 ? min / 60 + " h" : min + " min") + " and never survive a restart.", 12, DIM, false), new LinearLayout.LayoutParams(0, -2, 1));
            TextView reset = tv(a, "Full speed", 14, RecentsChip.CYAN, true);
            reset.setPadding(dp(a, 12), dp(a, 8), 0, dp(a, 8));
            reset.setOnClickListener(safe(() -> {
                for (String k : new String[]{"sbmods_cpu_pct", "sbmods_gpu_pct", "sbmods_cool", "sbmods_cores"}) Global.gset(a, k, "0");
                PerfPanel.changed(a);
            }));
            foot.addView(reset);
            col.addView(foot);
            return col;
        }

        private static View toggle(Activity a, String label, String key) {
            LinearLayout r = new LinearLayout(a);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(a, 6), 0, dp(a, 6));
            r.addView(tv(a, label, 14, WHITE, false), new LinearLayout.LayoutParams(0, -2, 1));
            android.widget.Switch sw = new android.widget.Switch(a);
            sw.setChecked(Global.gint(a, key, 0) == 1);
            sw.setOnCheckedChangeListener((b, v) -> {
                try {
                    Global.gset(a, key, v ? "1" : "0");
                    PerfPanel.pChangedAt = SystemClock.uptimeMillis();
                    RecentsChip.main.postDelayed(() -> RecentsChip.refresh(a), 1500);
                } catch (Throwable t) { Log.w("toggle: " + t); }
            });
            r.addView(sw);
            r.setOnClickListener(safe(sw::toggle));
            return r;
        }
    }

    // ------------------------------------------------------------------ 6. battery report

    static final class Battery extends Card {
        private TextView big, onOff, rateOn, rateOff, verdict, watts, err;
        private static long reqAt, sentAt;
        private static long[] lastGood;
        Battery() { super("battery"); }
        @Override View build(Activity a) {
            reqAt = 0; sentAt = 0;   // asks again as soon as the page opens
            LinearLayout col = column(a);
            big = tv(a, "—", 22, WHITE, true);
            col.addView(big);
            onOff = tv(a, "", 13, DIM, false);
            col.addView(onOff, lp(-2, -2, 0, dp(a, 4), 0, 0));
            rateOn = tv(a, "", 14, WHITE, false);
            col.addView(rateOn, lp(-2, -2, 0, dp(a, 10), 0, 0));
            rateOff = tv(a, "", 14, WHITE, false);
            col.addView(rateOff, lp(-2, -2, 0, dp(a, 2), 0, 0));
            verdict = tv(a, "", 14, WHITE, true);
            col.addView(verdict, lp(-2, -2, 0, dp(a, 6), 0, 0));
            watts = tv(a, "", 14, DIM, false);
            col.addView(watts, lp(-2, -2, 0, dp(a, 6), 0, 0));
            err = tv(a, "No answer from the helper – turn it on in StatusBar Mods › More.", 13, RecentsChip.AMBER, false);
            err.setVisibility(View.GONE);
            col.addView(err, lp(-2, -2, 0, dp(a, 6), 0, 0));
            return col;
        }
        @Override void live(Activity a, Perf.Snap s) {
            long now = SystemClock.uptimeMillis();
            if (now - reqAt >= 60000) {
                reqAt = now;
                PanelCmd.post(a, "battreport", () -> { sentAt = SystemClock.uptimeMillis(); Global.gset(a, "sbmods_batt", ""); });
            }
            long[] b = PanelMath.parseBatt(Global.gstr(a, "sbmods_batt"));
            if (b != null) lastGood = b;
            b = lastGood;
            err.setVisibility(b == null && sentAt > 0 && now - sentAt > 12000 ? View.VISIBLE : View.GONE);
            String pw = Power.text();
            watts.setText(pw == null ? "" : "Power now: " + pw);
            if (b == null) { big.setText(Power.charging ? "Charging" : "—"); onOff.setText(""); rateOn.setText(""); rateOff.setText(""); verdict.setText(""); return; }
            big.setText(Power.charging ? "Charging" : Power.status == BatteryManager.BATTERY_STATUS_FULL ? "Fully charged" : b[0] >= 0 ? "About " + PanelMath.duration(b[0]) + " left" : "—");
            onOff.setText(b[1] < 0 || b[2] < 0 ? "" : "Since the last full charge: screen on " + PanelMath.duration(PanelMath.minutes(b[1])) + " · screen off " + PanelMath.duration(PanelMath.minutes(b[2])));
            double on = PanelMath.screenOnRate(b), off = PanelMath.screenOffRate(b);
            rateOn.setText(on < 0 ? "Screen on: not enough data yet" : String.format(Locale.US, "Screen on: about %.1f%% of the battery per hour", on));
            rateOff.setText(off < 0 ? "Screen off: not enough data yet" : String.format(Locale.US, "Screen off: about %.1f%% of the battery per hour", off));
            int v = PanelMath.standbyVerdict(off);
            verdict.setVisibility(v < 0 ? View.GONE : View.VISIBLE);
            verdict.setText(v == 0 ? "Standby drain is normal" : v == 1 ? "Standby drain is a bit high" : "Standby drain is high – something keeps the phone awake");
            verdict.setTextColor(v == 0 ? RecentsChip.GREEN : v == 1 ? RecentsChip.AMBER : RecentsChip.RED);
        }
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
        @Override View build(Activity a) {
            LinearLayout col = column(a);
            final boolean vk = "vulkan".equals(Global.gstr(a, "sbmods_gfx"));
            col.addView(seg(a, new CharSequence[]{"Normal", "Vulkan"}, vk ? 1 : 0, i -> i == 0 ? RecentsChip.GREEN : RecentsChip.CYAN, i -> {
                if ((i == 1) == vk) return;
                PanelCmd.post(a, i == 1 ? "gfx:vulkan" : "gfx:opengl", null);
                android.widget.Toast.makeText(a, i == 1 ? "Vulkan: apps use it the next time they open" : "Normal drawing: apps switch the next time they open", android.widget.Toast.LENGTH_SHORT).show();
            }), lp(-1, -2, 0, dp(a, 4), 0, 0));
            line = tv(a, "", 13, WHITE, false);
            col.addView(line, lp(-1, -2, 0, dp(a, 8), 0, 0));
            col.addView(tv(a, "Vulkan is a newer way for apps to draw the screen. An app switches the next time it opens, and a restart of the phone goes back to normal. "
                    + "Calling is never touched; if Wi-Fi calling drops after switching, the phone goes back to normal by itself.", 12, 0x88FFFFFF, false), lp(-1, -2, 0, dp(a, 4), 0, 0));
            asked = lastApp(a);
            askedName = asked;
            if (asked != null) {
                try { PackageManager pm = a.getPackageManager(); askedName = pm.getApplicationInfo(asked, 0).loadLabel(pm); } catch (Throwable ignored) {}
                PanelCmd.post(a, "appgfx:" + asked, () -> Global.gset(a, "sbmods_appgfx", "-"));
            }
            return col;
        }
        @Override void live(Activity a, Perf.Snap s) {
            if (asked == null) { line.setVisibility(View.GONE); return; }
            String r = Global.gstr(a, "sbmods_appgfx");
            String how = r != null && r.startsWith(asked + ":") ? r.substring(asked.length() + 1) : "";
            line.setText(how.equals("vulkan") ? askedName + " is drawing with Vulkan right now"
                    : how.equals("opengl") ? askedName + " is drawing the normal way right now"
                    : how.equals("unknown") ? askedName + ": can't tell (it is asleep)" : "Checking " + askedName + "…");
            line.setTextColor(how.equals("vulkan") ? RecentsChip.CYAN : how.equals("opengl") ? WHITE : DIM);
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
            line = tv(a, "Checking…", 14, WHITE, false);
            col.addView(line);
            return col;
        }
        @Override void live(Activity a, Perf.Snap s) {
            long now = SystemClock.uptimeMillis();
            if (reqAt == 0 || now - reqAt >= 60000) { reqAt = now; PanelCmd.post(a, "health", null); }
            String r = Global.gstr(a, "sbmods_health");
            if (r == null) return;
            String[] p = r.split("\\|");
            if (p.length < 3) return;
            StringBuilder t = new StringBuilder();
            if (!p[0].equals("-1")) t.append("Holds about ").append(p[0]).append("% of what it did when new");
            if (!p[1].equals("-1")) t.append(t.length() > 0 ? "\n" : "").append("Charged ").append(p[1]).append(" times so far");
            if (!p[2].equals("-1") && !p[2].equals("100")) t.append(t.length() > 0 ? "\n" : "").append("Charging stops at ").append(p[2]).append("%");
            line.setText(t.length() > 0 ? t : "The phone doesn't report battery health");
        }
    }

    // ------------------------------------------------------------------ 7. memory

    static final class Memory extends Card {
        private TextView ramT, swapT;
        private BarView ramB, swapB;
        Memory() { super("memory"); }
        @Override View build(Activity a) {
            LinearLayout col = column(a);
            ramB = new BarView(a, false); swapB = new BarView(a, false);
            ramT = memRow(a, col, "Memory in use", ramB);
            swapT = memRow(a, col, "Swap in use (spill-over memory)", swapB);
            LinearLayout btns = new LinearLayout(a);
            TextView close = button(a, "Close apps", () -> RecentsChip.command(a, "ramclean", "Closing background apps…", 4000));
            TextView deep = button(a, "Deep clean", () -> RecentsChip.command(a, "deepclean", "Deep cleaning memory…", 6000));
            btns.addView(close, lp(0, -2, 0, dp(a, 4), dp(a, 8), 0, 1));
            btns.addView(deep, lp(0, -2, 0, dp(a, 4), 0, 0, 1));
            col.addView(btns);
            return col;
        }
        private static TextView memRow(Activity a, LinearLayout col, String label, BarView bar) {
            LinearLayout r = new LinearLayout(a);
            r.addView(tv(a, label, 14, WHITE, false), new LinearLayout.LayoutParams(0, -2, 1));
            TextView v = tv(a, "—", 14, WHITE, true);
            r.addView(v);
            col.addView(r, lp(-1, -2, 0, dp(a, 6), 0, 0));
            col.addView(bar, lp(-1, dp(a, 6), 0, dp(a, 5), 0, dp(a, 4)));
            return v;
        }
        @Override void live(Activity a, Perf.Snap s) {
            RecentsChip.Mem m = RecentsChip.readMem();
            int ram = (int) Math.round((m.totalMb - m.availMb) * 100.0 / m.totalMb);
            ramT.setText(ram + "%");
            ramB.set(ram, ram >= 90 ? RecentsChip.RED : ram >= 80 ? RecentsChip.AMBER : RecentsChip.GREEN);
            swapT.setText(m.swapPct + "%");
            swapB.set(m.swapPct, m.swapPct >= 90 ? RecentsChip.RED : m.swapPct >= 60 ? RecentsChip.AMBER : RecentsChip.GREEN);
        }
    }

    // ------------------------------------------------------------------ 8. busiest apps

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
            long now = SystemClock.uptimeMillis();
            if (now - reqAt >= 6000) {
                reqAt = now;
                PanelCmd.post(a, "top", () -> { sentAt = SystemClock.uptimeMillis(); Global.gset(a, "sbmods_top", "-"); });   // "-" = not answered yet
            }
            String raw = Global.gstr(a, "sbmods_top");
            if (raw == null || raw.equals("-")) {
                if (shownRaw == null) {
                    boolean late = sentAt > 0 && now - sentAt > 12000;
                    status.setText(late ? "No answer from the helper – turn it on in StatusBar Mods › More." : "Looking…");
                    status.setTextColor(late ? RecentsChip.AMBER : DIM);
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
            r.setPadding(0, dp(a, 6), 0, dp(a, 6));
            ImageView iv = new ImageView(a);
            if (icon != null) iv.setImageDrawable(icon);
            r.addView(iv, lp(dp(a, 32), dp(a, 32), 0, 0, dp(a, 12), 0));
            TextView name = tv(a, label, 14, WHITE, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(tv(a, pct + "%", 14, WHITE, true));
            boolean protectedApp = pkg.equals(Entry.LAUNCHER) || pkg.equals("com.android.systemui") || pkg.equals(a.getPackageName())
                    || pkg.startsWith("com.google.android.gms") || pkg.startsWith("com.android.providers.") || pkg.equals("android") || pkg.equals("dev.gitesh.sbmods") || pkg.equals("com.termux");   // the helper refuses these too
            if (!protectedApp) {
                TextView stop = tv(a, "Stop", 14, RecentsChip.CYAN, true);
                stop.setPadding(dp(a, 14), dp(a, 8), 0, dp(a, 8));
                stop.setOnClickListener(safe(() -> {
                    PanelCmd.stop(a, pkg);
                    reqAt = SystemClock.uptimeMillis() - 2000;   // look again soon after
                }));
                r.addView(stop);
            }
            return r;
        }
    }
}
