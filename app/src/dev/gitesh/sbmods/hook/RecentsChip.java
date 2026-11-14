// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Overview (Recents) screen extras, all in the free space around Samsung's own buttons:
 *  top left:    RAM · Swap · Temp chips (tap = close background apps / deep clean / cool-down mode)
 *  bottom left: GPU chip, bottom right: CPU chip - live speed + what limits it (tap = Performance panel, long-press = quick limit on / off)
 * Live speeds are read straight from the kernel (readable without root). Closing apps and speed limits need root, so they go
 * through the small root helper (/data/local/tmp/sbmods_cpud.sh), which listens to global settings. Turn the whole thing off with: settings put global sbmods_recents_chip 0
 */
final class RecentsChip {
    private static final String TAG = "sbmods_recents_chip";
    static final Handler main = new Handler(Looper.getMainLooper());
    private static LinearLayout topRow;
    private static ChipView ramChip, swapChip, tempChip, gpuChip, cpuChip;
    private static Activity owner;

    private RecentsChip() {}

    static void install(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { onResume((Activity) p.thisObject); } catch (Throwable t) { Log.w("recents chip: " + t); }
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject == owner) { main.removeCallbacks(tick); if (PerfPanel.panel != null) { try { PerfPanel.panel.dismiss(); } catch (Throwable ignored) {} PerfPanel.panel = null; } Global.gset(owner, "sbmods_watch", "0"); }   // panel first: closing it must not leave the watch on
                }
            });
        } catch (Throwable t) { Log.w("recents chip hook failed: " + t); Log.miss("Activity.onResume hook in the launcher", "the Overview screen chips"); }
    }

    // ------------------------------------------------------------------ build

    private static void onResume(Activity a) {
        if (!a.getPackageName().equals(Entry.LAUNCHER)) return;
        Log.bindLauncher(a);
        if (Settings.Global.getInt(a.getContentResolver(), "sbmods_recents_chip", 1) == 0) {   // switched off: take away chips already there
            int rid = a.getResources().getIdentifier("recentContainer", "id", Entry.LAUNCHER);
            View rc = rid == 0 ? null : a.findViewById(rid);
            if (rc instanceof ViewGroup) for (String t : new String[]{TAG, TAG + "_gpu", TAG + "_cpu"}) {
                View v = rc.findViewWithTag(t);
                if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            }
            main.removeCallbacks(tick); topRow = null;
            Global.gput(a, "sbmods_watch", "0");
            return;
        }
        int cid = a.getResources().getIdentifier("recentContainer", "id", Entry.LAUNCHER);
        Log.missId(cid, "recentContainer", "the Overview screen chips");
        View c = cid == 0 ? null : a.findViewById(cid);
        if (!(c instanceof FrameLayout)) return;   // not the Overview screen
        FrameLayout box = (FrameLayout) c;
        float d = a.getResources().getDisplayMetrics().density;
        View old = box.findViewWithTag(TAG);
        if (old instanceof LinearLayout) {
            topRow = (LinearLayout) old;
            ramChip = (ChipView) topRow.getChildAt(0); swapChip = (ChipView) topRow.getChildAt(1); tempChip = (ChipView) topRow.getChildAt(2);
            gpuChip = (ChipView) box.findViewWithTag(TAG + "_gpu"); cpuChip = (ChipView) box.findViewWithTag(TAG + "_cpu");
        } else {
            topRow = new LinearLayout(a);
            topRow.setTag(TAG);
            ramChip = chip(a, () -> command(a, "ramclean", "Closing background apps…", 4000), () -> deviceCare(a));
            swapChip = chip(a, () -> command(a, "deepclean", "Deep cleaning memory…", 6000), () -> deviceCare(a));
            tempChip = chip(a, () -> toggleCool(a), () -> deviceCare(a));
            for (ChipView t : new ChipView[]{ramChip, swapChip, tempChip}) topRow.addView(t);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            lp.leftMargin = Math.round((EDGE - 4) * d);   // the dot lines up with the CPU / GPU dots below
            lp.topMargin = Math.round(36 * d);
            box.addView(topRow, lp);

            gpuChip = chip(a, () -> PerfPanel.showPanel(a), () -> quickLimit(a, "gpu"));
            cpuChip = chip(a, () -> PerfPanel.showPanel(a), () -> quickLimit(a, "cpu"));
            gpuChip.setTag(TAG + "_gpu"); cpuChip.setTag(TAG + "_cpu");
            for (ChipView t : new ChipView[]{gpuChip, cpuChip}) {
                t.stacked = true;
                FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | (t == gpuChip ? Gravity.START : Gravity.END));
                bl.leftMargin = bl.rightMargin = Math.round((EDGE - ChipView.PAD) * d);
                bl.bottomMargin = Math.round(112 * d);   // level with Samsung's "Close all"
                box.addView(t, bl);
            }
        }
        owner = a;
        Global.gset(a, "sbmods_watch", "1");   // the helper measures CPU load only while this screen is open
        main.removeCallbacks(tick);
        main.post(tick);
    }

    private static ChipView chip(Activity a, Runnable tap, Runnable longTap) {
        float d = a.getResources().getDisplayMetrics().density;
        ChipView t = new ChipView(a);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(6 * d);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> tap.run());
        t.setOnLongClickListener(v -> { longTap.run(); return true; });
        return t;
    }

    private static void set(ChipView t, String label, String value, int color, float frac) { t.set(label, value, color, frac); }

    /** dp from the screen edges to the first / last thing drawn, the same for every chip. */
    private static final float EDGE = 18;
    static final int GREEN = 0xFF6EE7A8, AMBER = 0xFFFFC857, RED = 0xFFFF7A70, CYAN = 0xFF7CD5FF;


    // ------------------------------------------------------------------ live values

    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                Activity a = owner;
                if (a == null || topRow == null || !topRow.isAttachedToWindow()) { if (a != null) Global.gset(a, "sbmods_watch", "0"); return; }
                boolean shown = topRow.isShown();
                // CPU load only while Overview is really on screen (not the home screen); 2 = the Performance panel is open (per-core load too)
                Global.gset(a, "sbmods_watch", !shown ? "0" : PerfPanel.showing() ? "2" : "1");
                if (shown) { try { align(a); refresh(a); } catch (Throwable t) { Log.w("recents chip tick: " + t); } }
                main.postDelayed(this, 2500);
            } catch (Throwable t) { Log.w("recents chip tick: " + t); }
        }
    };

    /** Line the top chips up with the search / more buttons. */
    private static void align(Activity a) {
        alignBottom(a);
        int fid = a.getResources().getIdentifier("top_buttons_flow", "id", Entry.LAUNCHER);
        Log.missId(fid, "top_buttons_flow", "lining up the Overview chips");
        View flow = fid == 0 ? null : a.findViewById(fid);
        if (flow == null || flow.getHeight() <= 0 || !(topRow.getParent() instanceof View) || topRow.getHeight() <= 0) return;
        // Samsung's own pill (for example "1 inactive app") can sit in the same corner: start after it, with short chips
        int[] tr = new int[2];
        topRow.getLocationInWindow(tr);
        float d = a.getResources().getDisplayMetrics().density;
        int blockRight = 0;
        View parent0 = (View) topRow.getParent();
        blockRight = rightOfOthers(parent0, tr[1], tr[1] + topRow.getHeight(), 0);
        boolean compact = blockRight > 0;
        FrameLayout.LayoutParams lp0 = (FrameLayout.LayoutParams) topRow.getLayoutParams();
        int wantLeft = compact ? blockRight + Math.round(6 * d) : Math.round((EDGE - 4) * d);
        if (lp0.leftMargin != wantLeft) { lp0.leftMargin = wantLeft; topRow.setLayoutParams(lp0); }
        // never run into Samsung's search / more buttons: drop the least useful chips until the row fits
        {
            int limit = leftOfButtons(parent0, tr[1], tr[1] + topRow.getHeight(), wantLeft, 0);
            View[] order = {tempChip, ramChip, swapChip};   // dropped in this order (temperature first, swap last)
            ChipView[] all = {ramChip, swapChip, tempChip};
            for (View v : order) v.setVisibility(View.VISIBLE);
            int avail = limit > wantLeft ? limit - Math.round(2 * d) - wantLeft : Integer.MAX_VALUE;
            int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            // keep all three as long as possible: full labels -> short labels -> just dot + value; only then drop chips
            int start = compact ? 1 : 0;
            for (int lvl = start; lvl <= 2; lvl++) {
                for (ChipView cv : all) {
                    boolean cp = lvl >= 1, br = lvl == 2;
                    if (cv.compact != cp || cv.bare != br) { cv.compact = cp; cv.bare = br; cv.requestLayout(); }
                    LinearLayout.LayoutParams ml = (LinearLayout.LayoutParams) cv.getLayoutParams();
                    int gapPx = cv == tempChip ? 0 : Math.round((br ? 5 : cp ? 8 : 6) * d);   // no gap after the last one   // compact chips have less padding of their own, so a bit more gap
                    if (ml.rightMargin != gapPx) { ml.rightMargin = gapPx; cv.setLayoutParams(ml); }
                }
                topRow.measure(spec, spec);
                if (topRow.getMeasuredWidth() <= avail) break;
            }
            for (View v : order) {
                topRow.measure(spec, spec);
                if (topRow.getMeasuredWidth() <= avail) break;
                v.setVisibility(View.GONE);
            }
        }
        int[] f = new int[2], c = new int[2];
        flow.getLocationInWindow(f);
        ((View) topRow.getParent()).getLocationInWindow(c);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) topRow.getLayoutParams();
        int want = f[1] - c[1] + Math.max(0, (flow.getHeight() - topRow.getHeight()) / 2);
        if (lp.topMargin != want) { lp.topMargin = want; topRow.setLayoutParams(lp); }
    }

    /** CPU / GPU chips: vertically centred on Samsung's "Close all" and only as wide as the room beside it. */
    private static void alignBottom(Activity a) {
        if (gpuChip == null || cpuChip == null || !(gpuChip.getParent() instanceof View)) return;
        View box = (View) gpuChip.getParent();
        View btn = closeAll(box, 0);
        if (btn == null) return;
        float d = a.getResources().getDisplayMetrics().density;
        int[] b = new int[2], c = new int[2];
        btn.getLocationInWindow(b);
        box.getLocationInWindow(c);
        int left = b[0] - c[0], right = box.getWidth() - (left + btn.getWidth());
        int room = Math.min(left, right) - Math.round((EDGE - ChipView.PAD) * d) - Math.round(6 * d);
        int centre = box.getHeight() - (b[1] - c[1] + btn.getHeight() / 2);
        for (ChipView t : new ChipView[]{gpuChip, cpuChip}) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) t.getLayoutParams();
            int bottom = Math.max(0, centre - btn.getHeight() / 2);
            boolean change = false;
            if (t.hgt != btn.getHeight()) { t.hgt = btn.getHeight(); change = true; }
            if (room > 0 && t.maxW != room) { t.maxW = room; change = true; }
            if (lp.bottomMargin != bottom) { lp.bottomMargin = bottom; change = true; }
            if (change) t.setLayoutParams(lp);
        }
    }

    /** Samsung's "Close all": the wide clickable button in the middle of the bottom area. */
    private static View closeAll(View v, int depth) {
        if (!(v instanceof ViewGroup) || depth > 8) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == gpuChip || c == cpuChip || c == topRow || !c.isShown()) continue;
            if (c instanceof TextView && String.valueOf(((TextView) c).getText()).trim().equalsIgnoreCase("Close all")) {
                View p = c;   // the clickable pill around the text, if any
                while (p.getParent() instanceof View && !p.isClickable() && ((View) p.getParent()).getWidth() < g.getRootView().getWidth() * 0.8f) p = (View) p.getParent();
                return p;
            }
            View f = closeAll(c, depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    /** Left edge (window px) of the left-most small clickable view (search / more) right of minX in our row's strip; 0 if none. */
    /** Empty space inside an icon button before its picture starts. */
    private static int iconInset(View c) {
        if (!(c instanceof android.widget.ImageView)) return 0;
        android.graphics.drawable.Drawable dr = ((android.widget.ImageView) c).getDrawable();
        if (dr == null || dr.getIntrinsicWidth() <= 0) return 0;
        int inner = c.getWidth() - c.getPaddingLeft() - c.getPaddingRight();
        return Math.max(0, (inner - dr.getIntrinsicWidth()) / 2);
    }

    private static int leftOfButtons(View v, int top, int bottom, int minX, int depth) {
        if (!(v instanceof ViewGroup) || depth > 6) return 0;
        ViewGroup g = (ViewGroup) v;
        int best = 0;
        int[] l = new int[2];
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == topRow || c == gpuChip || c == cpuChip || !c.isShown() || c.getWidth() <= 0 || c.getHeight() <= 0) continue;
            c.getLocationInWindow(l);
            float d = c.getResources().getDisplayMetrics().density;
            boolean small = c.getWidth() < 100 * d && c.getHeight() < 70 * d;
            boolean row = l[1] < bottom && l[1] + c.getHeight() > top && l[0] > minX;
            int found = 0;
            if (small && row && c.isClickable()) found = l[0] + Math.min(c.getPaddingLeft() + iconInset(c), c.getWidth() / 3);   // the drawn icon, not its wider touch area
            else if (c instanceof ViewGroup) found = leftOfButtons(c, top, bottom, minX, depth + 1);
            if (found > 0) best = best == 0 ? found : Math.min(best, found);
        }
        return best;
    }

    /** Right edge (window px) of any small view of Samsung's in the top-left strip that our chips would overlap; 0 if none. */
    private static int rightOfOthers(View v, int top, int bottom, int depth) {
        if (!(v instanceof ViewGroup) || depth > 6) return 0;
        ViewGroup g = (ViewGroup) v;
        int best = 0;
        int[] l = new int[2];
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == topRow || c == gpuChip || c == cpuChip || !c.isShown() || c.getWidth() <= 0 || c.getHeight() <= 0) continue;
            c.getLocationInWindow(l);
            float d = c.getResources().getDisplayMetrics().density;
            boolean small = c.getWidth() < 260 * d && c.getHeight() < 70 * d && c.getHeight() > 14 * d;
            boolean strip = l[1] < bottom && l[1] + c.getHeight() > top && l[0] < 140 * d;   // in the top-left strip (not the search / more buttons), level with our row
            if (small && strip && c.getVisibility() == View.VISIBLE && (c instanceof TextView || c.isClickable())) best = Math.max(best, l[0] + c.getWidth());
            else if (c instanceof ViewGroup) best = Math.max(best, rightOfOthers(c, top, bottom, depth + 1));
        }
        return best;
    }

    static void refresh(Context ctx) {
        Mem m = readMem();
        long availMb = m.availMb, swapPct = m.swapPct;
        float freeFrac = availMb / (float) m.totalMb;
        int ramColor = freeFrac < 0.10f ? RED : freeFrac < 0.20f ? AMBER : GREEN;
        set(ramChip, "RAM", String.format(java.util.Locale.US, "%.1f GB", availMb / 1024f), ramColor, freeFrac);
        set(swapChip, "Swap", swapPct + "%", ramColor, swapPct / 100f);   // a full swap is normal on Samsung: the colour follows free RAM, the number stays
        float temp = 0;
        try {
            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            temp = b == null ? 0 : b.getIntExtra("temperature", 0) / 10f;
        } catch (Throwable ignored) {}
        boolean cool = Global.gint(ctx, "sbmods_cool", 0) == 1;
        set(tempChip, cool ? "Cooling" : "Temp", Math.round(temp) + "\u00b0C", cool ? CYAN : temp >= 42 ? RED : temp >= 38 ? AMBER : GREEN, Math.max(0f, Math.min(1f, (temp - 25f) / 25f)));

        Perf.Snap ps = Perf.read();
        int cp = Global.gint(ctx, "sbmods_cpu_pct", 0), gp = Global.gint(ctx, "sbmods_gpu_pct", 0);
        cp = effPct(ctx, "cpu", cp, cool); gp = effPct(ctx, "gpu", gp, cool);   // cool-down uses your levels; 100 = Full on purpose
        int load = Global.gint(ctx, "sbmods_cpu_load", -1);
        chipFor(cpuChip, "CPU", ps.cpuCur > 0 ? Perf.ghz(ps.cpuCur) + (load >= 0 ? " \u00b7 " + load + "%" : "") : "\u2014", cp, ps.cpuCap, ps.cpuHw, true);
        chipFor(gpuChip, "GPU", ps.gpuCur > 0 ? ps.gpuCur + " MHz" + (gpuAvg(ps.gpuBusy) >= 3 ? " \u00b7 " + gpuAvg(-2) + "%" : "") : "\u2014", gp, ps.gpuCap, ps.gpuHw, false);
    }

    /** RAM and swap from /proc/meminfo (shared by the chips and the panel's Memory card). */
    static final class Mem { long availMb, totalMb = 1; int swapPct; }

    static Mem readMem() {
        Mem m = new Mem();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            long st = 0, sf = 0;
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("MemTotal:")) m.totalMb = Math.max(1, kb(l) / 1024);
                else if (l.startsWith("MemAvailable:")) m.availMb = kb(l) / 1024;
                else if (l.startsWith("SwapTotal:")) st = kb(l);
                else if (l.startsWith("SwapFree:")) sf = kb(l);
            }
            m.swapPct = st > 0 ? (int) ((st - sf) * 100 / st) : 0;
        } catch (Throwable ignored) {}
        return m;
    }

    /** Is the Overview screen really on screen right now? */
    static boolean overviewShown() { return topRow != null && topRow.isShown(); }

    private static long kb(String line) {
        String[] p = line.trim().split("\\s+");
        return p.length > 1 ? Long.parseLong(p[1]) : 0;
    }

    // ------------------------------------------------------------------ actions

    /** Ask the root helper to do something (close apps / deep clean) and show what it reports. */
    static void command(Activity a, String cmd, String msg, long waitMs) {
        long gap = PanelCmd.waitMs();   // the helper reads one request at a time: let the panel's last one be picked up first
        if (gap > 0) { main.postDelayed(() -> command(a, cmd, msg, waitMs), gap); return; }
        PanelCmd.cleaning(true);
        if (!Global.gput(a, "sbmods_result", "")) { PanelCmd.cleaning(false); Toast.makeText(a, "Can't reach the helper", Toast.LENGTH_SHORT).show(); return; }
        if (cmd.equals("ramclean") || cmd.equals("deepclean")) Global.markClean(a);
        Jobs.send(a, cmd);
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
        // the helper checks every few seconds and the job itself takes a few more: keep looking until it answers
        final long until = android.os.SystemClock.uptimeMillis() + Math.max(15000, waitMs * 3);
        main.postDelayed(new Runnable() {
            @Override public void run() {
                String r = Settings.Global.getString(a.getContentResolver(), "sbmods_result");
                if (r != null && !r.isEmpty()) { PanelCmd.cleaning(false); Toast.makeText(a, r, Toast.LENGTH_LONG).show(); return; }
                if (android.os.SystemClock.uptimeMillis() < until) { main.postDelayed(this, 400); return; }
                PanelCmd.cleaning(false);
                if (cmd.equals(Global.gstr(a, "sbmods_cmd"))) Global.gput(a, "sbmods_cmd", "none");   // (went to the helper script:) withdraw it, so it never runs by surprise later
                Toast.makeText(a, "No answer from the helper – is it running?", Toast.LENGTH_LONG).show();
            }
        }, 1500);
    }

    private static void toggleCool(Activity a) {
        boolean on = Global.gint(a, "sbmods_cool", 0) == 1;
        Global.gput(a, "sbmods_cool", on ? "0" : "1");
        int at = Global.gint(a, "sbmods_cool_at", 360);
        Toast.makeText(a, on ? "Cool-down off" : "Cool-down on: CPU " + Global.gint(a, "sbmods_cpu_pick", 55) + "%, GPU " + Global.gint(a, "sbmods_gpu_pick", 55) + "% until the phone is back to " + at / 10 + "\u00b0C", Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ CPU / GPU

    /** Limit choices, as a share of the hardware maximum (0 = no limit). */
    static final int[] PCTS = {0, 85, 70, 55, 40};
    static final String[] PCT_NAMES = {"Full", "85%", "70%", "55%", "40%"};

    private static final int[] gpuHist = new int[4];   // last ~10 s of GPU load (one sample per refresh)
    private static int gpuN, gpuAt;

    /** GPU load averaged over the last ~10 s (one reading is only the last instant). Pass -2 to read without adding a sample. */
    static int gpuAvg(int sample) {
        if (sample >= 0) { gpuHist[gpuAt] = sample; gpuAt = (gpuAt + 1) % gpuHist.length; gpuN = Math.min(gpuN + 1, gpuHist.length); }
        if (gpuN == 0) return -1;
        int sum = 0;
        for (int i = 0; i < gpuN; i++) sum += gpuHist[i];
        return Math.round(sum / (float) gpuN);
    }

    /** Fill a CPU / GPU chip: live speed, and on the line below what (if anything) holds it back. */
    private static void chipFor(ChipView t, String name, String value, int pct, int cap, int hw, boolean cpu) {
        String sub; int dot, subC;
        int want = Perf.pick(cpu ? Perf.cpuSteps : Perf.gpuSteps, hw, pct);
        if (pct > 0) {
            boolean held = want > 0 && cap > 0 && cap <= want;
            boolean fresh = android.os.SystemClock.uptimeMillis() - PerfPanel.pChangedAt < 4000;
            sub = held ? "Limited · max " + (cpu ? Perf.ghz(cap) : cap + " MHz") : fresh ? "Applying…" : "Limit not applied";
            dot = held || fresh ? CYAN : RED; subC = dot;
        } else if (cap > 0 && hw > 0 && cap < hw * 0.97f) {
            sub = "Heat limit · " + (cpu ? Perf.ghz(cap) : cap + " MHz");
            dot = AMBER; subC = AMBER;
        } else {
            sub = "Full speed";
            dot = GREEN; subC = 0x99FFFFFF;
        }
        t.set(name, value, dot, -1);
        t.sub(sub, subC);
    }

    /** The level really in force: 0 / 100 = full speed; during cool-down "no choice" (0) means your picked level. */
    static int effPct(Context c, String which, int raw, boolean cool) {
        if (raw >= 100) return 0;
        if (raw == 0 && cool) return Global.gint(c, "sbmods_" + which + "_pick", 55);
        return raw;
    }

    /** Long-press: limit on (at the level last chosen in the panel) / off. */
    private static void quickLimit(Activity a, String which) {
        int on = Global.gint(a, "sbmods_" + which + "_pct", 0);
        if (on >= 100) on = 0;
        int level = Global.gint(a, "sbmods_" + which + "_pick", 55);
        Global.gput(a, "sbmods_" + which + "_pct", on > 0 ? "0" : String.valueOf(level));
        if (on == 0) PanelCards.learn(a, effPct(a, "cpu", Global.gint(a, "sbmods_cpu_pct", 0), false), effPct(a, "gpu", Global.gint(a, "sbmods_gpu_pct", 0), false));   // turned ON by hand: remember it for the app used last
        PerfPanel.pChangedAt = android.os.SystemClock.uptimeMillis();
        Toast.makeText(a, which.toUpperCase(java.util.Locale.ROOT) + (on > 0 ? ": full speed" : ": limited to " + level + "%"), Toast.LENGTH_SHORT).show();
        try { main.postDelayed(() -> { try { refresh(a); } catch (Throwable t) { Log.w("recents chip refresh: " + t); } }, 1500); } catch (Throwable t) { Log.w("recents chip: " + t); }
    }


    private static void deviceCare(Activity a) {
        try {
            Intent i = new Intent("com.samsung.android.sm.ACTION_RAM").setPackage("com.samsung.android.lool");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Throwable t) { Toast.makeText(a, "Device care not available", Toast.LENGTH_SHORT).show(); }
    }

}
