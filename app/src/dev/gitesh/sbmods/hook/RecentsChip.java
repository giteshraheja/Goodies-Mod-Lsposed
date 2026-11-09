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
    private static final Handler main = new Handler(Looper.getMainLooper());
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
                    if (p.thisObject == owner) { main.removeCallbacks(tick); gput(owner, "sbmods_watch", "0"); if (panel != null) { try { panel.dismiss(); } catch (Throwable ignored) {} panel = null; } }
                }
            });
        } catch (Throwable t) { Log.w("recents chip hook failed: " + t); }
    }

    // ------------------------------------------------------------------ build

    private static void onResume(Activity a) {
        if (!a.getPackageName().equals(Entry.LAUNCHER)) return;
        if (Settings.Global.getInt(a.getContentResolver(), "sbmods_recents_chip", 1) == 0) return;
        int cid = a.getResources().getIdentifier("recentContainer", "id", Entry.LAUNCHER);
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

            gpuChip = chip(a, () -> showPanel(a), () -> quickLimit(a, "gpu"));
            cpuChip = chip(a, () -> showPanel(a), () -> quickLimit(a, "cpu"));
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
        gput(a, "sbmods_watch", "1");   // the helper measures CPU load only while this screen is open
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
    private static final int GREEN = 0xFF6EE7A8, AMBER = 0xFFFFC857, RED = 0xFFFF7A70, CYAN = 0xFF7CD5FF;

    /**
     * One clean line of text: a small coloured dot (green / amber / red / cyan = state), a light label and a bold value.
     * No box; a soft highlight shows only while it is pressed. Drawn by hand so nothing stretches, wraps or clips.
     */
    static final class ChipView extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private String label = "", value = "", sub = "";
        private int subColor = 0x99FFFFFF;
        private int color = Color.WHITE;
        private final float d;
        static final float PAD = 4;
        static final float BLOCK = 14 * 0.95f + 18 * 0.95f + 13 * 0.95f + 2;   // dp of the three text lines + tiny gaps   // dp between the chip's edge and its text
        boolean stacked, compact, bare;   // compact: small label; bare: no label at all (dot + value)
        int maxW, hgt;       // hgt: same height as "Close all" (0 = not known yet)            // stacked chips: never wider than this (room beside Samsung's "Close all"); text shrinks to fit
        private float k = 1; // that shrink factor   // compact: just dot + value (used when Samsung's own pill shares the corner)

        ChipView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            setClickable(true);
            setLongClickable(true);
        }

        void sub(String sub, int color) {
            if (!sub.equals(this.sub)) { this.sub = sub; requestLayout(); }
            if (subColor != color) { subColor = color; invalidate(); }
        }

        void set(String label, String value, int color, float frac) {
            boolean relayout = !label.equals(this.label) || !value.equals(this.value);
            this.label = label; this.value = value; this.color = color;
            if (relayout) requestLayout();
            invalidate();
        }

        private void fontLabel() { p.setTextSize((compact ? 11f : 12.5f) * d); p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); }
        private void fontValue() { p.setTextSize((bare ? 12f : 13f) * d); p.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD)); }

        @Override protected void onMeasure(int w, int h) {
            fontLabel(); float lw = bare ? 0 : p.measureText(label);
            fontValue(); float vw = p.measureText(value);
            if (compact) { setMeasuredDimension(Math.round(2 * d + 7 * d + 4 * d + (bare ? 0 : lw + 3 * d) + vw + 2 * d), Math.round(40 * d)); return; }   // short, but still says what it is
            if (stacked) {
                p.setTextSize(14f * d); lw = p.measureText(label);
                p.setTextSize(18f * d); vw = p.measureText(value);
                p.setTextSize(13f * d); float sw = p.measureText(sub);
                float content = Math.max(Math.max(13 * d + lw, vw), sw);
                k = maxW > 0 ? Math.max(0.6f, Math.min(1f, (maxW - 2 * PAD * d) / content)) : 1f;
                int hh = hgt > 0 ? hgt : Math.round(48 * d);
                k = Math.min(k, hh / (BLOCK * d));   // three lines must fit the button's height
                setMeasuredDimension(Math.round(2 * PAD * d + content * k), hh); return;
            }
            setMeasuredDimension(Math.round(4 * d + 7 * d + 5 * d + lw + 4 * d + vw + 2 * d), Math.round(40 * d));
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), cy = h / 2;
            if (isPressed()) {
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setColor(0x33FFFFFF);
                r.set(0, 4 * d, w, h - 4 * d);
                c.drawRoundRect(r, 14 * d, 14 * d, p);
            }
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setShadowLayer(3 * d, 0, 1 * d, 0x99000000);   // keeps it readable on any wallpaper
            if (stacked) {   // dot + label, value, then what holds it back. The right-hand chip is a mirror image: it reads from the right edge.
                boolean right = getLayoutParams() instanceof FrameLayout.LayoutParams && (((FrameLayout.LayoutParams) getLayoutParams()).gravity & Gravity.END) == Gravity.END;
                float x0 = right ? w - PAD * d : PAD * d, dir = right ? -1 : 1;
                p.setTextAlign(right ? android.graphics.Paint.Align.RIGHT : android.graphics.Paint.Align.LEFT);
                p.setColor(color);
                // same height as "Close all": label's top on the button's top edge, the speed centred on its text, the last line on its bottom edge
                float y1 = 0.74f * 14 * d * k, y2 = h / 2f + 0.36f * 18 * d * k, y3 = h - 0.22f * 13 * d * k;
                c.drawCircle(x0 + dir * 4f * d, y1 - 4.8f * d * k, 4f * d, p);
                p.setTextSize(14f * d * k);
                p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                p.setColor(0xCCFFFFFF);
                c.drawText(label, x0 + dir * 13 * d, y1, p);
                p.setTextSize(18f * d * k);
                p.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                p.setColor(Color.WHITE);
                c.drawText(value, x0, y2, p);
                p.setTextSize(13f * d * k);
                p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                p.setColor(subColor);
                c.drawText(sub, x0, y3, p);
                p.clearShadowLayer();
                return;
            }
            p.setColor(color);
            float in = compact ? 2 * d : 4 * d;
            c.drawCircle(in + 3.5f * d, cy, 3.5f * d, p);
            p.setTextAlign(android.graphics.Paint.Align.LEFT);
            float x = in + 7 * d + (compact ? 4 : 5) * d;
            if (!bare) {
                fontLabel();
                p.setColor(compact ? 0x99FFFFFF : 0xCCFFFFFF);
                c.drawText(label, x, cy + 4.6f * d, p);
                x += p.measureText(label) + (compact ? 3 : 4) * d;
            }
            fontValue();
            p.setColor(color == GREEN || color == CYAN || color == AMBER || color == RED ? Color.WHITE : color);
            c.drawText(value, x, cy + 4.6f * d, p);
            p.clearShadowLayer();
        }

        @Override public void setPressed(boolean pressed) { super.setPressed(pressed); invalidate(); }
    }

    // ------------------------------------------------------------------ live values

    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                Activity a = owner;
                if (a == null || topRow == null || !topRow.isAttachedToWindow()) return;
                align(a);
                if (topRow.isShown() && gint(a, "sbmods_watch", 0) != 1) gput(a, "sbmods_watch", "1");   // Samsung may pause / resume out of order
                refresh(a);
                main.postDelayed(this, 2500);
            } catch (Throwable t) { Log.w("recents chip tick: " + t); }
        }
    };

    /** Line the top chips up with the search / more buttons. */
    private static void align(Activity a) {
        alignBottom(a);
        int fid = a.getResources().getIdentifier("top_buttons_flow", "id", Entry.LAUNCHER);
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

    private static void refresh(Context ctx) {
        long availMb = 0, totalMb = 1, swapPct = 0;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            long st = 0, sf = 0;
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("MemTotal:")) totalMb = Math.max(1, kb(l) / 1024);
                else if (l.startsWith("MemAvailable:")) availMb = kb(l) / 1024;
                else if (l.startsWith("SwapTotal:")) st = kb(l);
                else if (l.startsWith("SwapFree:")) sf = kb(l);
            }
            swapPct = st > 0 ? (st - sf) * 100 / st : 0;
        } catch (Throwable ignored) {}
        float freeFrac = availMb / (float) totalMb;
        set(ramChip, "RAM", String.format(java.util.Locale.US, "%.1f GB", availMb / 1024f), freeFrac < 0.10f ? RED : freeFrac < 0.20f ? AMBER : GREEN, freeFrac);
        set(swapChip, "Swap", swapPct + "%", swapPct >= 90 ? RED : swapPct >= 60 ? AMBER : GREEN, swapPct / 100f);
        float temp = 0;
        try {
            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            temp = b == null ? 0 : b.getIntExtra("temperature", 0) / 10f;
        } catch (Throwable ignored) {}
        boolean cool = gint(ctx, "sbmods_cool", 0) == 1;
        set(tempChip, cool ? "Cooling" : "Temp", Math.round(temp) + "\u00b0C", cool ? CYAN : temp >= 42 ? RED : temp >= 38 ? AMBER : GREEN, Math.max(0f, Math.min(1f, (temp - 25f) / 25f)));

        Perf.Snap ps = Perf.read();
        int cp = gint(ctx, "sbmods_cpu_pct", 0), gp = gint(ctx, "sbmods_gpu_pct", 0);
        if (cool) { if (cp == 0) cp = gint(ctx, "sbmods_cpu_pick", 55); if (gp == 0) gp = gint(ctx, "sbmods_gpu_pick", 55); }   // cool-down uses your levels
        int load = gint(ctx, "sbmods_cpu_load", -1);
        chipFor(cpuChip, "CPU", ps.cpuCur > 0 ? Perf.ghz(ps.cpuCur) + (load >= 0 ? " \u00b7 " + load + "%" : "") : "\u2014", cp, ps.cpuCap, ps.cpuHw, true);
        chipFor(gpuChip, "GPU", ps.gpuCur > 0 ? ps.gpuCur + " MHz" + (gpuAvg(ps.gpuBusy) >= 3 ? " \u00b7 " + gpuAvg(-2) + "%" : "") : "\u2014", gp, ps.gpuCap, ps.gpuHw, false);
        if (panel != null && panel.isShowing()) panelLive(ctx, ps);
    }

    private static long kb(String line) {
        String[] p = line.trim().split("\\s+");
        return p.length > 1 ? Long.parseLong(p[1]) : 0;
    }

    // ------------------------------------------------------------------ actions

    /** Ask the root helper to do something (close apps / deep clean) and show what it reports. */
    private static void command(Activity a, String cmd, String msg, long waitMs) {
        if (!gput(a, "sbmods_result", "")) { Toast.makeText(a, "Can't reach the helper", Toast.LENGTH_SHORT).show(); return; }
        gput(a, "sbmods_cmd", cmd);
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
        // the helper checks every few seconds and the job itself takes a few more: keep looking until it answers
        final long until = android.os.SystemClock.uptimeMillis() + Math.max(15000, waitMs * 3);
        main.postDelayed(new Runnable() {
            @Override public void run() {
                String r = Settings.Global.getString(a.getContentResolver(), "sbmods_result");
                if (r != null && !r.isEmpty()) { Toast.makeText(a, r, Toast.LENGTH_LONG).show(); return; }
                if (android.os.SystemClock.uptimeMillis() < until) { main.postDelayed(this, 400); return; }
                gput(a, "sbmods_cmd", "none");   // withdraw it, so it never runs by surprise later
                Toast.makeText(a, "No answer from the helper – is it running?", Toast.LENGTH_LONG).show();
            }
        }, 1500);
    }

    private static void toggleCool(Activity a) {
        boolean on = gint(a, "sbmods_cool", 0) == 1;
        gput(a, "sbmods_cool", on ? "0" : "1");
        int at = gint(a, "sbmods_cool_at", 360);
        Toast.makeText(a, on ? "Cool-down off" : "Cool-down on: CPU " + gint(a, "sbmods_cpu_pick", 55) + "%, GPU " + gint(a, "sbmods_gpu_pick", 55) + "% until the phone is back to " + at / 10 + "\u00b0C", Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ CPU / GPU

    /** Limit choices, as a share of the hardware maximum (0 = no limit). */
    static final int[] PCTS = {0, 85, 70, 55, 40};
    private static final String[] PCT_NAMES = {"Full", "85%", "70%", "55%", "40%"};

    /** Live CPU / GPU numbers straight from the kernel (these files are readable without root). All in MHz. */
    static final class Perf {
        static final class Snap { int cpuCur, cpuCap, cpuHw, gpuCur, gpuCap, gpuHw, gpuBusy = -1; }
        private static String[] pol;
        static int[] cpuSteps, gpuSteps;   // the prime cluster's / the GPU's available speeds (MHz)
        private static final String G = "/sys/class/kgsl/kgsl-3d0/";

        static int readInt(String f, int div) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String l = r.readLine();
                if (l == null) return -1;
                l = l.trim().split("\\s+")[0];
                return (int) (Long.parseLong(l) / div);
            } catch (Throwable t) { return -1; }
        }

        private static int[] steps(String f, int div) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String[] p = r.readLine().trim().split("\\s+");
                int[] v = new int[p.length];
                for (int i = 0; i < p.length; i++) v[i] = (int) (Long.parseLong(p[i]) / div);
                java.util.Arrays.sort(v);
                return v;
            } catch (Throwable t) { return new int[0]; }
        }

        static Snap read() {
            if (pol == null) {
                java.io.File[] fs = new java.io.File("/sys/devices/system/cpu/cpufreq").listFiles((dir, n) -> n.startsWith("policy"));
                if (fs == null) fs = new java.io.File[0];
                java.util.Arrays.sort(fs, (x, y) -> Integer.compare(Integer.parseInt(x.getName().substring(6)), Integer.parseInt(y.getName().substring(6))));
                pol = new String[fs.length];
                for (int i = 0; i < fs.length; i++) pol[i] = fs[i].getPath() + "/";
                cpuSteps = pol.length == 0 ? new int[0] : steps(pol[pol.length - 1] + "scaling_available_frequencies", 1000);
                gpuSteps = steps(G + "devfreq/available_frequencies", 1000000);
            }
            Snap s = new Snap();
            for (String p : pol) s.cpuCur = Math.max(s.cpuCur, readInt(p + "scaling_cur_freq", 1000));
            if (pol.length > 0) {   // the prime core: the one a limit (or the heat) visibly holds back
                String p = pol[pol.length - 1];
                s.cpuCap = readInt(p + "scaling_max_freq", 1000);
                s.cpuHw = readInt(p + "cpuinfo_max_freq", 1000);
            }
            s.gpuCur = readInt(G + "devfreq/cur_freq", 1000000);
            s.gpuCap = readInt(G + "devfreq/max_freq", 1000000);
            s.gpuHw = gpuSteps.length > 0 ? gpuSteps[gpuSteps.length - 1] : -1;
            s.gpuBusy = readInt(G + "gpu_busy_percentage", 1);
            return s;
        }

        /** Same rule as the helper: the highest step at or below pct % of the hardware maximum. */
        static int pick(int[] steps, int hw, int pct) {
            if (steps == null || steps.length == 0 || hw <= 0) return -1;
            if (pct <= 0) return hw;
            int best = -1;
            for (int v : steps) if ((long) v * 100 <= (long) hw * pct && v > best) best = v;
            return best > 0 ? best : steps[0];
        }

        static String ghz(int mhz) { return String.format(java.util.Locale.US, "%.1f GHz", mhz / 1000f); }
    }

    private static final int[] gpuHist = new int[4];   // last ~10 s of GPU load (one sample per refresh)
    private static int gpuN, gpuAt;

    /** GPU load averaged over the last ~10 s (one reading is only the last instant). Pass -2 to read without adding a sample. */
    private static int gpuAvg(int sample) {
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
            boolean fresh = android.os.SystemClock.uptimeMillis() - pChangedAt < 4000;
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

    /** Long-press: limit on (at the level last chosen in the panel) / off. */
    private static void quickLimit(Activity a, String which) {
        int on = gint(a, "sbmods_" + which + "_pct", 0);
        int level = gint(a, "sbmods_" + which + "_pick", 55);
        gput(a, "sbmods_" + which + "_pct", on > 0 ? "0" : String.valueOf(level));
        pChangedAt = android.os.SystemClock.uptimeMillis();
        Toast.makeText(a, which.toUpperCase(java.util.Locale.ROOT) + (on > 0 ? ": full speed" : ": limited to " + level + "%"), Toast.LENGTH_SHORT).show();
        main.postDelayed(() -> refresh(a), 1500);
    }

    private static android.app.Dialog panel;
    private static TextView pCpu, pGpu, pTemp, pWarn;
    private static long pChangedAt;

    /** Tap on the CPU / GPU chip: one panel for both speeds, power saving and cool-down. */
    private static void showPanel(Activity a) {
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
        int at = gint(a, "sbmods_cool_at", 360);
        col.addView(toggle(a, "Cool-down: use the levels above until " + at / 10 + "°C, then stop", "sbmods_cool"));

        pWarn = text(a, "", 13, RED, false);
        pWarn.setPadding(0, Math.round(6 * d), 0, 0);
        pWarn.setVisibility(View.GONE);
        col.addView(pWarn);

        LinearLayout foot = new LinearLayout(a);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, Math.round(10 * d), 0, 0);
        int min = gint(a, "sbmods_limit_min", 15);
        TextView note = text(a, "Limits end by themselves after " + (min >= 60 ? min / 60 + " h" : min + " min") + " and never survive a restart.", 12, 0x99FFFFFF, false);
        foot.addView(note, new LinearLayout.LayoutParams(0, -2, 1));
        TextView reset = text(a, "Full speed", 14, CYAN, true);
        reset.setPadding(Math.round(12 * d), Math.round(8 * d), 0, Math.round(8 * d));
        reset.setOnClickListener(v -> {
            for (String k : new String[]{"sbmods_cpu_pct", "sbmods_gpu_pct", "sbmods_cool"}) gput(a, k, "0");
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
        main.postDelayed(() -> { if (panel != null && panel.isShowing()) { showPanel(a); } refresh(a); }, 350);
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
        int cur = gint(a, "sbmods_" + which + "_pct", 0);
        if (cur == 0 && gint(a, "sbmods_cool", 0) == 1) cur = gint(a, "sbmods_" + which + "_pick", 55);   // cooling: show the level it uses
        LinearLayout seg = new LinearLayout(a);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(0x22FFFFFF);
        sb.setCornerRadius(18 * d);
        seg.setBackground(sb);
        seg.setPadding(Math.round(3 * d), Math.round(3 * d), Math.round(3 * d), Math.round(3 * d));
        for (int i = 0; i < PCTS.length; i++) {
            final int pct = PCTS[i];
            boolean on = pct == cur || (i == 0 && cur == 0);
            int mhz = Perf.pick(cpu ? Perf.cpuSteps : Perf.gpuSteps, hw, pct);
            SpannableStringBuilder t = new SpannableStringBuilder(PCT_NAMES[i]);
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
            if (on) { GradientDrawable ob = new GradientDrawable(); ob.setColor(i == 0 ? GREEN : CYAN); ob.setCornerRadius(15 * d); b.setBackground(ob); }
            b.setOnClickListener(v -> {
                if (pct > 0) gput(a, "sbmods_" + which + "_pick", String.valueOf(pct));
                if (gint(a, "sbmods_cool", 0) == 1 && pct > 0) gput(a, "sbmods_" + which + "_pct", "0");   // cooling: just its level, ends with it
                else gput(a, "sbmods_" + which + "_pct", String.valueOf(pct));
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
        sw.setChecked(gint(a, key, 0) == 1);
        sw.setOnCheckedChangeListener((b, v) -> {
            gput(a, key, v ? "1" : "0");
            pChangedAt = android.os.SystemClock.uptimeMillis();
            main.postDelayed(() -> refresh(a), 1500);
        });
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());
        return r;
    }

    /** Live numbers in the open panel, and a clear warning when a limit was asked for but nothing happened (helper off). */
    private static void panelLive(Context c, Perf.Snap s) {
        if (pCpu == null) return;
        pCpu.setText(liveLine("CPU", s.cpuCur > 0 ? Perf.ghz(s.cpuCur) : "—", s.cpuCap, s.cpuHw, true, -1));
        pGpu.setText(liveLine("GPU", s.gpuCur > 0 ? s.gpuCur + " MHz" : "—", s.gpuCap, s.gpuHw, false, gpuAvg(-2)));
        float temp = 0;
        try {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            temp = b == null ? 0 : b.getIntExtra("temperature", 0) / 10f;
        } catch (Throwable ignored) {}
        pTemp.setText(Math.round(temp) + "°C");
        int cp = gint(c, "sbmods_cpu_pct", 0), gp = gint(c, "sbmods_gpu_pct", 0);
        if (gint(c, "sbmods_cool", 0) == 1) { if (cp == 0) cp = gint(c, "sbmods_cpu_pick", 55); if (gp == 0) gp = gint(c, "sbmods_gpu_pick", 55); }
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
            if (held) t.setSpan(new ForegroundColorSpan(AMBER), s2, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return t;
    }

    private static void deviceCare(Activity a) {
        try {
            Intent i = new Intent("com.samsung.android.sm.ACTION_RAM").setPackage("com.samsung.android.lool");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Throwable t) { Toast.makeText(a, "Device care not available", Toast.LENGTH_SHORT).show(); }
    }

    // ------------------------------------------------------------------ global settings bridge

    private static boolean gput(Context c, String key, String val) {
        try { return Settings.Global.putString(c.getContentResolver(), key, val); } catch (Throwable t) { Log.w("setting " + key + ": " + t); return false; }
    }

    private static int gint(Context c, String key, int def) {
        try { return Settings.Global.getInt(c.getContentResolver(), key, def); } catch (Throwable t) { return def; }
    }
}
