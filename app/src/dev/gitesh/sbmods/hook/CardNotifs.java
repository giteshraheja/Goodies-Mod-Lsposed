// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Overview: each app card shows that app's notifications over its lower left part, newest at the bottom, up to 5 rows
 * (the 5th becomes a "+N more" stack that opens the full list). A strip on the card's right stays free: tapping there opens the app.
 * One layer over the cards follows them every frame while Overview shows (positions only); contents refresh on the chips' tick.
 */
final class CardNotifs {
    private CardNotifs() {}

    static final String TAG = "sbmods_card_notifs";
    private static final int MAX_ROWS = 5, STRIP_DP = 34;
    private static FrameLayout layer;
    private static ViewTreeObserver.OnPreDrawListener follow;
    private static final IdentityHashMap<View, Panel> panels = new IdentityHashMap<>();
    private static Map<String, List<RecentsNotifs.N>> byPkg = new HashMap<>();
    private static int sceneId, taskViewId;
    private static Method pkgOf;
    private static final ArrayList<View> found = new ArrayList<>();

    private static final class Panel {
        FrameLayout root;
        String pkg = "", key = "";
        boolean expanded;
    }

    /** Overview opened: add the layer (below our chips, above the cards). */
    static void attach(Activity a, FrameLayout box) {
        View old = box.findViewWithTag(TAG);
        if (!RecentsNotifs.on(a)) { if (old != null) box.removeView(old); detach(); return; }
        if (old instanceof FrameLayout) layer = (FrameLayout) old;
        else {
            layer = new FrameLayout(a);
            layer.setTag(TAG);
            layer.setClipChildren(true);
            int at = 0;   // just above Samsung's views, below anything of ours added later
            for (int i = 0; i < box.getChildCount(); i++) { Object t = box.getChildAt(i).getTag(); if (t instanceof String && ((String) t).startsWith("sbmods")) { at = i; break; } at = i + 1; }
            box.addView(layer, at, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        sceneId = a.getResources().getIdentifier("task_scene", "id", Entry.LAUNCHER);
        taskViewId = a.getResources().getIdentifier("taskView", "id", Entry.LAUNCHER);
        RecentsNotifs.bind(a, CardNotifs::update);
        update();
        refresh(a);
        if (follow == null) {
            final View root = box.getRootView();
            follow = () -> { try { place(a); } catch (Throwable t) { Log.w("card notifs place: " + t); } return true; };
            root.getViewTreeObserver().addOnPreDrawListener(follow);
        }
    }

    /** Overview left: stop following (no work while it is closed). */
    static void detach() {
        if (follow != null && layer != null) { try { layer.getRootView().getViewTreeObserver().removeOnPreDrawListener(follow); } catch (Throwable ignored) {} }
        follow = null;
    }

    /** Ask the status bar for a fresh list (Overview open / chips' tick); the answer calls update(). */
    static void refresh(Activity a) {
        if (layer == null) return;
        RecentsNotifs.request(a);
    }

    /** A list arrived (or we dropped some): regroup by app and redraw. */
    static void update() {
        Map<String, List<RecentsNotifs.N>> m = new HashMap<>();
        for (RecentsNotifs.N s : RecentsNotifs.active(null)) {
            List<RecentsNotifs.N> l = m.get(s.getPackageName());
            if (l == null) m.put(s.getPackageName(), l = new ArrayList<>());
            l.add(s);
        }
        byPkg = m;
        if (layer != null) layer.invalidate();
    }


    private static String pkg(View taskView) {
        try {
            if (pkgOf == null) pkgOf = taskView.getClass().getMethod("getTaskPackageName");
            Object r = pkgOf.invoke(taskView);
            return r == null ? "" : r.toString();
        } catch (Throwable t) { return ""; }
    }

    private static void collect(View v, int depth) {
        if (v.getId() == taskViewId && taskViewId != 0) { found.add(v); return; }
        if (!(v instanceof ViewGroup) || depth > 7 || v == layer) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), depth + 1);
    }

    private static final int[] lo = new int[2], so = new int[2];

    /** Every frame: put each card's panel over its card (translation only, so cheap). */
    private static void place(Activity a) {
        FrameLayout L = layer;
        if (L == null || !L.isAttachedToWindow() || !L.isShown()) return;
        float d = a.getResources().getDisplayMetrics().density;
        found.clear();
        collect((View) L.getParent(), 0);
        L.getLocationInWindow(lo);
        IdentityHashMap<View, Boolean> seen = new IdentityHashMap<>();
        for (View tv : found) {
            View scene = sceneId == 0 ? null : tv.findViewById(sceneId);
            if (scene == null || !tv.isShown() || scene.getWidth() <= 0) continue;
            seen.put(tv, Boolean.TRUE);
            String pkg = pkg(tv);
            List<RecentsNotifs.N> ns = byPkg.get(pkg);
            Panel p = panels.get(tv);
            if (ns == null || ns.isEmpty()) { if (p != null) p.root.setVisibility(View.GONE); continue; }
            if (p == null) { p = new Panel(); p.root = new FrameLayout(a); L.addView(p.root, new FrameLayout.LayoutParams(0, 0)); panels.put(tv, p); }
            int w = scene.getWidth(), h = scene.getHeight();   // whole card; the list keeps off the right strip (empty space passes taps to the card)
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) p.root.getLayoutParams();
            if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; p.root.setLayoutParams(lp); }
            StringBuilder k = new StringBuilder(pkg).append(p.expanded);
            for (RecentsNotifs.N s : ns) {
                k.append('|').append(s.getKey()).append(s.getPostTime());
                if (s.session == null) k.append(s.actsKey());   // play <-> pause swaps the button: rebuild (music updates itself)
            }
            if (!k.toString().equals(p.key)) { p.key = k.toString(); p.pkg = pkg; build(a, p, ns, d); }
            scene.getLocationInWindow(so);
            float sx = so[0] - lo[0], sy = so[1] - lo[1];
            if (p.root.getTranslationX() != sx) p.root.setTranslationX(sx);
            if (p.root.getTranslationY() != sy) p.root.setTranslationY(sy);
            float al = tv.getAlpha() * scene.getAlpha();
            if (p.root.getAlpha() != al) p.root.setAlpha(al);
            if (p.root.getVisibility() != View.VISIBLE) p.root.setVisibility(View.VISIBLE);
        }
        // panels of cards that scrolled away or were recycled
        List<View> gone = new ArrayList<>();
        for (Map.Entry<View, Panel> e : panels.entrySet()) if (!seen.containsKey(e.getKey())) gone.add(e.getKey());
        for (View v : gone) { Panel p = panels.remove(v); if (p != null) L.removeView(p.root); }
    }

    private static void build(Activity a, Panel p, List<RecentsNotifs.N> ns, float d) {
        p.root.removeAllViews();
        int gap = Math.round(4 * d), pad = Math.round(6 * d);
        Runnable again = () -> { update(); p.key = ""; RecentsChip.main.postDelayed(() -> refresh(a), 400); };
        if (p.expanded) {   // full list over the card, newest at the bottom, scrollable; header collapses it
            ScrollView sv = new ScrollView(a);
            sv.setVerticalScrollBarEnabled(false);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.BOTTOM);
            col.setPadding(pad, pad, 0, pad);
            col.addView(pill(a, "Show less", d, () -> { p.expanded = false; p.key = ""; if (layer != null) layer.invalidate(); }));
            for (int i = ns.size() - 1; i >= 0; i--) col.addView(RecentsNotifs.row(a, ns.get(i), d, again), rowLp(gap));
            sv.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            GradientDrawable shade = new GradientDrawable();
            shade.setColor(0x99000000);
            shade.setCornerRadius(14 * d);
            sv.setBackground(shade);
            FrameLayout.LayoutParams svl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            svl.rightMargin = Math.round(STRIP_DP * d);
            p.root.addView(sv, svl);
            sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
            return;
        }
        Fit col = new Fit(a);   // shrinks long notification texts until the list fits the card
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, 0, 0, pad);
        List<RecentsNotifs.N> live = new ArrayList<>();   // music / calls / scores: their own bigger block at the very bottom
        List<RecentsNotifs.N> rest = new ArrayList<>();
        for (RecentsNotifs.N s : ns) (RecentsNotifs.live(s) ? live : rest).add(s);
        ns = rest;
        int shown = ns.size() > MAX_ROWS ? MAX_ROWS - 1 : ns.size();
        if (ns.size() > MAX_ROWS) {   // 5th slot: the rest, stacked (looks like cards behind each other); tap opens the full list
            int more = ns.size() - shown;
            col.addView(stack(a, ns.get(shown), more, d, () -> { p.expanded = true; p.key = ""; if (layer != null) layer.invalidate(); }), rowLp(gap));
        }
        for (int i = shown - 1; i >= 0; i--) col.addView(RecentsNotifs.row(a, ns.get(i), d, again), rowLp(gap));   // newest at the bottom
        View[] strip = new View[1];   // music buttons: first player's controls go into the card's right strip
        View[] music = new View[1];
        for (int i = Math.min(2, live.size()) - 1; i >= 0; i--) {
            RecentsNotifs.N s = live.get(i);
            View v = null;
            if (s.session != null && strip[0] == null) { try { v = MediaBlock.make(a, s, d, strip); music[0] = v; } catch (Throwable t) { Log.w("media block: " + t); } }
            col.addView(v != null ? v : RecentsNotifs.liveBlock(a, s, d, again), rowLp(gap));
        }
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        cl.rightMargin = Math.round(STRIP_DP * d);
        p.root.addView(col, cl);
        boolean any = false;
        for (RecentsNotifs.N s : rest) if (s.clear) any = true;
        for (RecentsNotifs.N s : live) if (s.clear) any = true;
        if (any) {   // clear this card's notifications: a small round button just right of the list, in the card's free strip
            TextView x = new TextView(a);
            x.setText("\u2715");
            x.setTextColor(0xFFFFFFFF);
            x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            x.setGravity(Gravity.CENTER);
            GradientDrawable xb = new GradientDrawable();
            xb.setShape(GradientDrawable.OVAL);
            xb.setColor(0xE61C1C1E);
            x.setBackground(xb);
            x.setContentDescription("Clear this app's notifications");
            int xs = Math.round(28 * d);
            FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(xs, xs, Gravity.BOTTOM | Gravity.END);
            xl.rightMargin = Math.round((STRIP_DP - 28) / 2f * d);   // centred in the card's free strip
            xl.bottomMargin = pad;
            final String pkg = p.pkg;
            x.setOnClickListener(v -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); RecentsNotifs.clear(a, java.util.Collections.singleton(pkg)); again.run(); });
            p.root.addView(x, xl);
        }
        if (strip[0] != null && music[0] != null) {   // music buttons: a pill exactly beside the player (same top and bottom); the clear button goes above it
            int sw = Math.round(30 * d);
            FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(sw, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            ml.rightMargin = Math.round((STRIP_DP - 30) / 2f * d);
            ml.bottomMargin = pad;
            p.root.addView(strip[0], ml);
            View pill = strip[0], mv = music[0];
            View xv = any ? p.root.getChildAt(p.root.getChildCount() - 2) : null;
            col.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int top = col.getTop() + mv.getTop(), bot = col.getTop() + mv.getBottom(), rootH = p.root.getHeight();
                if (bot <= top || rootH <= 0) return;
                int h = bot - top, bm = rootH - bot;
                FrameLayout.LayoutParams pl = (FrameLayout.LayoutParams) pill.getLayoutParams();
                boolean ch = pl.height != h || pl.bottomMargin != bm;
                pl.height = h; pl.bottomMargin = bm;
                if (xv != null) {
                    FrameLayout.LayoutParams xl2 = (FrameLayout.LayoutParams) xv.getLayoutParams();
                    int xb2 = bm + h + gap;
                    if (xl2.bottomMargin != xb2) { xl2.bottomMargin = xb2; ch = true; }
                }
                if (ch) v.post(() -> { pill.requestLayout(); if (xv != null) xv.requestLayout(); });
            });
        }
    }

    /** Lets notification texts use up to 4 lines, then trims them (oldest rows first) until everything fits the card. */
    private static final class Fit extends LinearLayout {
        Fit(Activity a) { super(a); }
        @Override protected void onMeasure(int w, int h) {
            List<TextView> bodies = new ArrayList<>();
            bodies(this, bodies);
            for (TextView t : bodies) if (t.getMaxLines() != RecentsNotifs.BODY_LINES) t.setMaxLines(RecentsNotifs.BODY_LINES);
            super.onMeasure(w, h);
            int room = MeasureSpec.getSize(h);
            if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) return;
            for (int lines = RecentsNotifs.BODY_LINES; getMeasuredHeight() > room && lines > 1; ) {
                boolean cut = false;   // oldest (top) row first, one line at a time
                for (TextView t : bodies) if (t.getMaxLines() == lines && t.getLineCount() > lines - 1) { t.setMaxLines(lines - 1); cut = true; break; }
                if (!cut) { lines--; continue; }
                super.onMeasure(w, h);
            }
        }
        private static void bodies(View v, List<TextView> out) {
            if (v instanceof TextView && RecentsNotifs.BODY.equals(v.getTag())) { out.add((TextView) v); return; }
            if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) bodies(g.getChildAt(i), out); }
        }
    }

    private static LinearLayout.LayoutParams rowLp(int gap) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = gap;
        return l;
    }

    private static View stack(Activity a, RecentsNotifs.N s, int more, float d, Runnable open) {
        FrameLayout f = new FrameLayout(a);
        View back = new View(a);   // the edge of the cards underneath
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(0x991C1C1E);
        bb.setCornerRadius(12 * d);
        back.setBackground(bb);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        bl.leftMargin = bl.rightMargin = Math.round(6 * d);
        f.addView(back, bl);
        LinearLayout front = (LinearLayout) RecentsNotifs.row(a, s, d, null);
        TextView n = new TextView(a);
        n.setText("+" + more);
        n.setTextColor(0xFFFFFFFF);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        n.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        n.setPadding(Math.round(4 * d), 0, 0, 0);
        front.addView(n);
        front.setOnTouchListener(null);
        front.setOnClickListener(v -> open.run());
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.bottomMargin = Math.round(4 * d);
        f.addView(front, fl);
        f.setPadding(0, 0, 0, 0);
        bl.topMargin = Math.round(4 * d);
        return f;
    }

    static TextView pill(Activity a, String text, float d, Runnable tap) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setGravity(Gravity.CENTER);
        int p = Math.round(6 * d);
        t.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC2C2C2E);
        bg.setCornerRadius(12 * d);
        t.setBackground(bg);
        t.setOnClickListener(v -> tap.run());
        return t;
    }

    /** Packages that have a card in Overview (for "Clear"). */
    static List<String> cardPkgs(Activity a) {
        List<String> out = new ArrayList<>();
        for (android.app.ActivityManager.RecentTaskInfo t : RecentsRail.recent(a, 60)) {
            android.content.ComponentName cn = RecentsRail.comp(t);
            if (cn != null && !out.contains(cn.getPackageName())) out.add(cn.getPackageName());
        }
        return out;
    }

    static void clearAll(Activity a) {
        int n = RecentsNotifs.clear(a, cardPkgs(a));
        android.widget.Toast.makeText(a, n == 0 ? "No notifications to clear" : "Cleared " + n + (n == 1 ? " notification" : " notifications"), android.widget.Toast.LENGTH_SHORT).show();
        update();
        RecentsChip.main.postDelayed(() -> refresh(a), 400);
    }

    static boolean any(String pkg) { List<RecentsNotifs.N> l = byPkg.get(pkg); return l != null && !l.isEmpty(); }

    static List<RecentsNotifs.N> of(String pkg) { List<RecentsNotifs.N> l = byPkg.get(pkg); return l == null ? Collections.emptyList() : l; }
}
