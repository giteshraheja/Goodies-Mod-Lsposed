// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.gitesh.sbmods.PanelLayout;

import static dev.gitesh.sbmods.hook.PanelCards.dp;
import static dev.gitesh.sbmods.hook.PanelCards.tv;

/**
 * The Performance popup: a frosted sheet at the bottom of the Overview screen with a header, a page switcher
 * (Performance / Battery / Memory) and the cards of the current page (see PanelCards). Shown inside the launcher's
 * process, so everything here is guarded: a failing card is skipped, never a crash.
 */
final class PerfPanel {
    private PerfPanel() {}

    static android.app.Dialog panel;
    static long pChangedAt;
    private static int page;   // last page used, kept for the next time the panel opens
    private static Activity pAct;
    private static LinearLayout pBox, pSwitch;
    private static ScrollView pScroll;
    private static TextView pHead, pWarn;
    private static final List<PanelCards.Card> shown = new ArrayList<>();
    private static final List<String> sigs = new ArrayList<>();
    private static boolean rebuilding;

    static boolean showing() { return panel != null && panel.isShowing(); }

    /** Tap on the CPU / GPU chip. */
    static void showPanel(Activity a) {
        try { show(a); } catch (Throwable t) { Log.w("performance panel: " + t); }
    }

    private static void show(Activity a) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(a, 20), dp(a, 18), dp(a, 20), dp(a, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(a, 28));
        bg.setStroke(1, 0x22FFFFFF);
        col.setBackground(bg);

        // header: title left; battery temperature, power draw and (when a limit is on) its end on the right
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tv(a, "Performance", 20, Color.WHITE, true), new LinearLayout.LayoutParams(-2, -2));   // the title never wraps: the facts on the right take what is left
        pHead = tv(a, "", 13, 0xCCFFFFFF, false);
        pHead.setGravity(Gravity.END);
        pHead.setPadding(dp(a, 10), 0, 0, 0);
        head.addView(pHead, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(head);

        pSwitch = new LinearLayout(a);
        pSwitch.setPadding(0, dp(a, 12), 0, dp(a, 4));
        col.addView(pSwitch);
        buildSwitch(a);

        pWarn = tv(a, "The limit isn't being applied: the root helper seems to be off. Turn it on in StatusBar Mods › More.", 13, RecentsChip.RED, false);
        pWarn.setPadding(0, dp(a, 6), 0, 0);
        pWarn.setVisibility(View.GONE);
        col.addView(pWarn);

        final int max = Math.round(a.getResources().getDisplayMetrics().heightPixels * 0.7f);
        pScroll = new ScrollView(a) {
            private int keep;   // tallest page seen: the sheet never shrinks, or the page buttons would jump away from under the finger
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
                keep = Math.max(keep, getMeasuredHeight());
                if (getMeasuredHeight() < keep) setMeasuredDimension(getMeasuredWidth(), keep);
            }
        };
        pScroll.setVerticalScrollBarEnabled(false);
        pBox = new LinearLayout(a);
        pBox.setOrientation(LinearLayout.VERTICAL);
        pScroll.addView(pBox, new ViewGroup.LayoutParams(-1, -2));
        col.addView(pScroll, new LinearLayout.LayoutParams(-1, -2));

        if (panel != null) try { panel.dismiss(); } catch (Throwable ignored) {}
        android.app.Dialog dl = new android.app.Dialog(a);
        dl.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout wrap = new FrameLayout(a);
        wrap.setPadding(dp(a, 12), 0, dp(a, 12), dp(a, 12));
        wrap.addView(col);
        dl.setContentView(wrap);

        // frosted glass: blur the scene behind when the system allows it (it does not while power saving), and fall back live
        WindowManager wm = a.getSystemService(WindowManager.class);
        Window w = dl.getWindow();
        final Consumer<Boolean> blurWatch = on -> { try { if (w != null) applyBlur(a, w, bg, on); } catch (Throwable t) { Log.w("blur: " + t); } };
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            boolean on = false;
            try { on = wm != null && wm.isCrossWindowBlurEnabled(); } catch (Throwable ignored) {}
            applyBlur(a, w, bg, on);
            try { if (wm != null) wm.addCrossWindowBlurEnabledListener(a.getMainExecutor(), blurWatch); } catch (Throwable t) { Log.w("blur listener: " + t); }
        }
        dl.setOnDismissListener(x -> {
            try { if (wm != null) wm.removeCrossWindowBlurEnabledListener(blurWatch); } catch (Throwable ignored) {}
            if (panel != dl) return;   // an older panel replaced by a new one: the new one owns everything below
            panel = null;
            PanelCmd.clear();
            RecentsChip.main.removeCallbacks(ticker);
            Global.gset(a, "sbmods_watch", RecentsChip.overviewShown() ? "1" : "0");   // per-core load is only measured while the panel shows
        });
        panel = dl;
        pAct = a;
        Global.gset(a, "sbmods_watch", "2");
        dl.show();
        rebuildCards(a);
        RecentsChip.main.removeCallbacks(ticker);
        RecentsChip.main.postDelayed(ticker, 1000);
    }

    private static void applyBlur(Context a, Window w, GradientDrawable bg, boolean on) {
        bg.setColor(on ? 0x99181A1E : 0xF2202326);   // glass when blurred, today's solid look otherwise
        WindowManager.LayoutParams lp = w.getAttributes();
        if (on) { w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND); lp.setBlurBehindRadius(dp(a, 60)); }
        else { w.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND); lp.setBlurBehindRadius(0); }
        w.setAttributes(lp);
        w.setDimAmount(on ? 0.25f : 0.45f);
    }

    /** One refresh a second while the panel shows (the Overview tick keeps its slower rhythm). */
    private static final Runnable ticker = new Runnable() {
        @Override public void run() {
            try {
                if (!showing() || pAct == null) return;
                panelLive(pAct, Perf.read());
            } catch (Throwable t) { Log.w("panel tick: " + t); }
            if (showing()) RecentsChip.main.postDelayed(this, 1000);
        }
    };

    /** Something was changed from the panel: show it, and let the chips catch up. */
    static void changed(Activity a) {
        pChangedAt = SystemClock.uptimeMillis();
        RecentsChip.main.postDelayed(() -> { try { if (showing()) rebuildCards(a); RecentsChip.refresh(a); } catch (Throwable t) { Log.w("panel refresh: " + t); } }, 350);
    }

    private static void buildSwitch(Activity a) {
        pSwitch.removeAllViews();
        pSwitch.addView(PanelCards.seg(a, PanelLayout.PAGE_NAMES, page, i -> RecentsChip.CYAN, i -> {
            if (i == page) return;
            page = i;
            buildSwitch(a);
            rebuildCards(a);   // only the card area changes
        }), new LinearLayout.LayoutParams(-1, -2));
    }

    /** Fill the card area with the visible cards of the current page. */
    private static void rebuildCards(Activity a) {
        if (pBox == null || rebuilding) return;
        rebuilding = true;
        try {
            int y = pScroll.getScrollY();
            pBox.removeAllViews();
            shown.clear(); sigs.clear();
            Perf.read();   // make sure the core groups are known before cards use them
            for (PanelCards.Card k : PanelCards.visible(a, page)) {
                View content = PanelCards.safeBuild(k, a);
                if (content == null) continue;
                if (pBox.getChildCount() > 0) {
                    View line = new View(a);
                    line.setBackgroundColor(0x14FFFFFF);
                    pBox.addView(line, new LinearLayout.LayoutParams(-1, 1));
                }
                LinearLayout frame = new LinearLayout(a);
                frame.setOrientation(LinearLayout.VERTICAL);
                frame.setPadding(0, dp(a, 14), 0, dp(a, 14));
                TextView title = tv(a, k.title, 13, 0x99FFFFFF, false);
                title.setPadding(0, 0, 0, dp(a, 8));
                frame.addView(title);
                frame.addView(content);
                pBox.addView(frame, new LinearLayout.LayoutParams(-1, -2));
                shown.add(k);
                String sg = "";
                try { sg = k.sig(a); } catch (Throwable t) { Log.w("card " + k.id + " sig: " + t); }
                sigs.add(sg);
            }
            if (shown.isEmpty()) pBox.addView(tv(a, "No cards on this page. Turn some on in StatusBar Mods.", 14, 0x99FFFFFF, false), new LinearLayout.LayoutParams(-1, -2));
            pScroll.post(() -> pScroll.scrollTo(0, y));
        } finally { rebuilding = false; }
        liveAll(a, Perf.read());
    }

    /** Live numbers in the open panel (called about once a second). */
    static void panelLive(Context c, Perf.Snap s) {
        if (pBox == null || !(pAct instanceof Activity)) return;
        liveAll(pAct, s);
    }

    private static void liveAll(Activity a, Perf.Snap s) {
        PanelCards.Power.read(a);
        try { header(a); warn(a, s); } catch (Throwable t) { Log.w("panel header: " + t); }
        boolean stale = false;
        for (int i = 0; i < shown.size(); i++) {
            PanelCards.Card k = shown.get(i);
            PanelCards.safeLive(k, a, s);
            try { if (!k.sig(a).equals(sigs.get(i))) stale = true; } catch (Throwable t) { Log.w("card " + k.id + " sig: " + t); }
        }
        if (stale) rebuildCards(a);   // changed from elsewhere (a chip long-press, the app)
    }

    private static void header(Activity a) {
        SpannableStringBuilder t = new SpannableStringBuilder(PanelCards.Power.tempC + "°C");
        String w = PanelCards.Power.text();
        if (w != null) t.append("  ·  ").append(w);
        long until = 0;
        try { String u = Global.gstr(a, "sbmods_until"); if (u != null && !u.isEmpty()) until = Long.parseLong(u.trim()); } catch (NumberFormatException ignored) {}
        long min = PanelMath.limitMinutes(until, System.currentTimeMillis() / 1000);
        if (min > 0) {
            t.append("  ·  ");
            int st = t.length();
            t.append("limit ").append(PanelMath.duration(min));
            t.setSpan(new ForegroundColorSpan(RecentsChip.CYAN), st, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        pHead.setText(t);
    }

    /** A clear warning when a limit was asked for but nothing happened (helper off). */
    private static void warn(Context c, Perf.Snap s) {
        int cp = Global.gint(c, "sbmods_cpu_pct", 0), gp = Global.gint(c, "sbmods_gpu_pct", 0);
        boolean cool = Global.gint(c, "sbmods_cool", 0) == 1;
        cp = RecentsChip.effPct(c, "cpu", cp, cool); gp = RecentsChip.effPct(c, "gpu", gp, cool);
        boolean missed = (cp > 0 && s.cpuCap > Perf.pick(Perf.cpuSteps, s.cpuHw, cp)) || (gp > 0 && s.gpuCap > Perf.pick(Perf.gpuSteps, s.gpuHw, gp));
        boolean late = SystemClock.uptimeMillis() - pChangedAt > 4000;
        pWarn.setVisibility(missed && late ? View.VISIBLE : View.GONE);
    }
}
