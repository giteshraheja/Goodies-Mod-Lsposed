// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class PerfPanel {
    private PerfPanel() {}

    static android.app.Dialog panel;
    private static TextView pCpu, pGpu, pTemp, pWarn;
    static long pChangedAt;

    /** Tap on the CPU / GPU chip: one panel for both speeds, power saving and cool-down. */
    static void showPanel(Activity a) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Math.round(20 * d), Math.round(18 * d), Math.round(20 * d), Math.round(14 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2202326);
        bg.setCornerRadius(26 * d);
        col.setBackground(bg);

        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(a, "Performance", 20, Color.WHITE, true);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        pTemp = text(a, "", 15, 0xCCFFFFFF, false);
        head.addView(pTemp);
        col.addView(head);

        pCpu = section(a, col, "CPU", "cpu");
        pGpu = section(a, col, "GPU", "gpu");

        col.addView(toggle(a, "Power saving while the CPU is limited", "sbmods_cpu_ps"));
        int at = Global.gint(a, "sbmods_cool_at", 360);
        col.addView(toggle(a, "Cool-down: use the levels above until " + at / 10 + "°C, then stop", "sbmods_cool"));

        pWarn = text(a, "", 13, RecentsChip.RED, false);
        pWarn.setPadding(0, Math.round(6 * d), 0, 0);
        pWarn.setVisibility(View.GONE);
        col.addView(pWarn);

        LinearLayout foot = new LinearLayout(a);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, Math.round(10 * d), 0, 0);
        int min = Global.gint(a, "sbmods_limit_min", 15);
        TextView note = text(a, "Limits end by themselves after " + (min >= 60 ? min / 60 + " h" : min + " min") + " and never survive a restart.", 12, 0x99FFFFFF, false);
        foot.addView(note, new LinearLayout.LayoutParams(0, -2, 1));
        TextView reset = text(a, "Full speed", 14, RecentsChip.CYAN, true);
        reset.setPadding(Math.round(12 * d), Math.round(8 * d), 0, Math.round(8 * d));
        reset.setOnClickListener(v -> {
            for (String k : new String[]{"sbmods_cpu_pct", "sbmods_gpu_pct", "sbmods_cool"}) Global.gput(a, k, "0");
            pChangedAt = android.os.SystemClock.uptimeMillis();
            rebuildPanel(a);
        });
        foot.addView(reset);
        col.addView(foot);

        if (panel != null) try { panel.dismiss(); } catch (Throwable ignored) {}
        android.app.Dialog dl = new android.app.Dialog(a);
        dl.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        FrameLayout wrap = new FrameLayout(a);
        wrap.setPadding(Math.round(12 * d), 0, Math.round(12 * d), Math.round(12 * d));
        wrap.addView(col);
        dl.setContentView(wrap);
        android.view.Window w = dl.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.45f);
        }
        dl.setOnDismissListener(x -> { if (panel == dl) panel = null; });
        panel = dl;
        dl.show();
        panelLive(a, Perf.read());
    }

    private static void rebuildPanel(Activity a) {
        RecentsChip.main.postDelayed(() -> { if (panel != null && panel.isShowing()) { showPanel(a); } RecentsChip.refresh(a); }, 350);
    }

    private static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    /** "CPU  1.9 GHz · max 3.4 GHz" and a row of limit choices, each showing the speed it allows. */
    private static TextView section(Activity a, LinearLayout col, String name, String which) {
        float d = a.getResources().getDisplayMetrics().density;
        TextView live = text(a, name, 14, 0xDDFFFFFF, false);
        live.setPadding(0, Math.round(16 * d), 0, Math.round(8 * d));
        col.addView(live);
        boolean cpu = which.equals("cpu");
        Perf.Snap s = Perf.read();
        int hw = cpu ? s.cpuHw : s.gpuHw;
        int cur = RecentsChip.effPct(a, which, Global.gint(a, "sbmods_" + which + "_pct", 0), Global.gint(a, "sbmods_cool", 0) == 1);   // what is really in force
        LinearLayout seg = new LinearLayout(a);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(0x22FFFFFF);
        sb.setCornerRadius(18 * d);
        seg.setBackground(sb);
        seg.setPadding(Math.round(3 * d), Math.round(3 * d), Math.round(3 * d), Math.round(3 * d));
        for (int i = 0; i < RecentsChip.PCTS.length; i++) {
            final int pct = RecentsChip.PCTS[i];
            boolean on = pct == cur || (i == 0 && cur == 0);
            int mhz = Perf.pick(cpu ? Perf.cpuSteps : Perf.gpuSteps, hw, pct);
            SpannableStringBuilder t = new SpannableStringBuilder(RecentsChip.PCT_NAMES[i]);
            if (mhz > 0) {
                int st = t.length();
                t.append("\n").append(cpu ? String.format(java.util.Locale.US, "%.1f", mhz / 1000f) : String.valueOf(mhz));
                t.setSpan(new RelativeSizeSpan(0.78f), st, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                t.setSpan(new ForegroundColorSpan(on ? 0xCC000000 : 0x99FFFFFF), st, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            TextView b = text(a, "", 14, on ? Color.BLACK : Color.WHITE, on);
            b.setText(t);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, Math.round(6 * d), 0, Math.round(6 * d));
            if (on) { GradientDrawable ob = new GradientDrawable(); ob.setColor(i == 0 ? RecentsChip.GREEN : RecentsChip.CYAN); ob.setCornerRadius(15 * d); b.setBackground(ob); }
            b.setOnClickListener(v -> {
                if (pct > 0) Global.gput(a, "sbmods_" + which + "_pick", String.valueOf(pct));
                boolean cooling = Global.gint(a, "sbmods_cool", 0) == 1;
                // cooling: a level just sets the cool-down level (ends with it); Full = this one at full speed while cooling (100)
                Global.gput(a, "sbmods_" + which + "_pct", cooling ? (pct > 0 ? "0" : "100") : String.valueOf(pct));
                pChangedAt = android.os.SystemClock.uptimeMillis();
                rebuildPanel(a);
            });
            seg.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        col.addView(seg);
        TextView unit = text(a, cpu ? "Speeds in GHz (fastest core)" : "Speeds in MHz", 11, 0x77FFFFFF, false);
        unit.setPadding(Math.round(4 * d), Math.round(4 * d), 0, 0);
        col.addView(unit);
        return live;
    }

    private static View toggle(Activity a, String label, String key) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Math.round(10 * d), 0, 0);
        TextView t = text(a, label, 14, Color.WHITE, false);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.Switch sw = new android.widget.Switch(a);
        sw.setChecked(Global.gint(a, key, 0) == 1);
        sw.setOnCheckedChangeListener((b, v) -> {
            Global.gput(a, key, v ? "1" : "0");
            pChangedAt = android.os.SystemClock.uptimeMillis();
            RecentsChip.main.postDelayed(() -> RecentsChip.refresh(a), 1500);
        });
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());
        return r;
    }

    /** Live numbers in the open panel, and a clear warning when a limit was asked for but nothing happened (helper off). */
    static void panelLive(Context c, Perf.Snap s) {
        if (pCpu == null) return;
        pCpu.setText(liveLine("CPU", s.cpuCur > 0 ? Perf.ghz(s.cpuCur) : "—", s.cpuCap, s.cpuHw, true, -1));
        pGpu.setText(liveLine("GPU", s.gpuCur > 0 ? s.gpuCur + " MHz" : "—", s.gpuCap, s.gpuHw, false, RecentsChip.gpuAvg(-2)));
        float temp = 0;
        try {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            temp = b == null ? 0 : b.getIntExtra("temperature", 0) / 10f;
        } catch (Throwable ignored) {}
        pTemp.setText(Math.round(temp) + "°C");
        int cp = Global.gint(c, "sbmods_cpu_pct", 0), gp = Global.gint(c, "sbmods_gpu_pct", 0);
        boolean cool = Global.gint(c, "sbmods_cool", 0) == 1;
        cp = RecentsChip.effPct(c, "cpu", cp, cool); gp = RecentsChip.effPct(c, "gpu", gp, cool);
        boolean missed = (cp > 0 && s.cpuCap > Perf.pick(Perf.cpuSteps, s.cpuHw, cp)) || (gp > 0 && s.gpuCap > Perf.pick(Perf.gpuSteps, s.gpuHw, gp));
        boolean late = android.os.SystemClock.uptimeMillis() - pChangedAt > 4000;
        pWarn.setVisibility(missed && late ? View.VISIBLE : View.GONE);
        pWarn.setText("The limit isn't being applied: the root helper seems to be off. Turn it on in StatusBar Mods › More.");
    }

    private static CharSequence liveLine(String name, String now, int cap, int hw, boolean cpu, int busy) {
        SpannableStringBuilder t = new SpannableStringBuilder(name + "  ");
        t.setSpan(new StyleSpan(Typeface.BOLD), 0, name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int st = t.length();
        t.append(now);
        t.setSpan(new ForegroundColorSpan(Color.WHITE), st, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (busy >= 3) t.append("  ·  ").append(String.valueOf(busy)).append("% busy (10 s avg)");
        else if (busy >= 0) t.append("  ·  idle");
        if (cap > 0 && hw > 0) {
            t.append("  ·  ");
            int s2 = t.length();
            boolean held = cap < hw * 0.97f;
            t.append(held ? "allowed up to " : "max ").append(cpu ? Perf.ghz(cap) : cap + " MHz");
            if (held) t.setSpan(new ForegroundColorSpan(RecentsChip.AMBER), s2, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return t;
    }
}
