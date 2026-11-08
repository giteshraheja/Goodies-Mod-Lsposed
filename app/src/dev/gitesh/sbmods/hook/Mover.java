// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.graphics.Rect;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import dev.gitesh.sbmods.Config;
import dev.gitesh.sbmods.ZoneLayout;

/**
 * Moves the clock, the temperature label and the battery to the zone chosen in the app, and keeps
 * the rest of the status bar tidy while doing so:
 *  - an item that leaves its place frees its space: the items next to it (on the side of the screen
 *    edge they hug) slide over to close the gap;
 *  - an item placed at the left or right edge pushes that side's content over to make room.
 * All of it is translationX, so Samsung's own layout is never changed and "Default" just resets it.
 * Runs before every status bar frame; does nothing when every zone is Default.
 */
final class Mover {
    private final WeakHashMap<View, Boolean> applied = new WeakHashMap<>();
    private final WeakHashMap<ViewGroup, Boolean> clipOrig = new WeakHashMap<>();
    private final int[] loc = new int[2], rloc = new int[2];
    private int endId = -1, startId = -1;

    /** views: clock, temperature label, battery, back button (any may be null). */
    void update(Config c, View root, View... views) {
        applyEdge(c, root);
        applyItemGap(c, root);
        applyNetMerge(c, root);
        int backZ = ((c.backLive == 3 || c.backLive == 4) && BackChip.stackLive) ? 0 : c.backZone;   // Stack: shares the live activity's spot
        int[] zone = {c.clockZone, c.tempZone, c.batteryZone, backZ};
        boolean any = zone[0] != 0 || zone[1] != 0 || zone[2] != 0 || zone[3] != 0;
        if (!any && applied.isEmpty()) return;
        if (!any || root == null) { releaseZones(); return; }

        if (endId == -1) {
            endId = root.getResources().getIdentifier("status_bar_end_side_content", "id", "com.android.systemui");
            startId = root.getResources().getIdentifier("status_bar_start_side_except_heads_up", "id", "com.android.systemui");
        }
        float density = root.getResources().getDisplayMetrics().density;
        root.getLocationOnScreen(rloc);

        // which atoms are actually moving (showing + zone set)
        float[] w = new float[ZoneLayout.ELEMENTS];
        boolean[] mv = new boolean[ZoneLayout.ELEMENTS];
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) {
            View v = i < views.length ? views[i] : null;
            if (v != null && zone[i] != 0 && v.isShown() && v.getWidth() > 0) {
                mv[i] = true;
                w[i] = v.getWidth() * v.getScaleX();
            }
        }

        // ---- 1. free the layout space of moved items, so Samsung's own layout closes the gap
        //          (and gives Wi-Fi / signal icons their room back); find which side each lives on
        IdentityHashMap<View, Float> shift = new IdentityHashMap<>();
        ViewGroup endBox = null, startBox = null;
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) {
            View atom = i < views.length ? views[i] : null;
            if (atom == null) continue;
            if (!mv[i]) { uncollapse(atom); continue; }
            collapse(atom);
            ViewGroup side = sideOf(atom, root);
            if (side == null) continue;
            if (side.getId() == endId) endBox = side; else startBox = side;
        }
        float gapPx = (c.itemGap >= 0 ? c.itemGap : 6) * density;
        applyHoleRoom(root, zone, w, mv, gapPx);
        // make room for items parked at the left / right edge
        float[] edgeTotal = new float[2]; // [0] left zone, [1] right zone
        int[] cnt = new int[2];
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) {
            if (!mv[i]) continue;
            int z = zone[i] == ZoneLayout.LEFT ? 0 : zone[i] == ZoneLayout.RIGHT ? 1 : -1;
            if (z < 0) continue;
            edgeTotal[z] += w[i];
            cnt[z]++;
        }
        if (startBox == null) startBox = (ViewGroup) root.findViewById(startId);
        if (endBox == null) endBox = (ViewGroup) root.findViewById(endId);
        if (cnt[0] > 0 && startBox != null) pushChildren(shift, startBox, edgeTotal[0] + gapPx * (cnt[0] - 1) + gapPx);
        if (cnt[1] > 0 && endBox != null) pushChildren(shift, endBox, -(edgeTotal[1] + gapPx * (cnt[1] - 1) + gapPx));

        // moved atoms get absolute positions below; never shift them here
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) if (mv[i]) shift.remove(views[i]);

        // ---- 2. apply shifts; reset whatever we shifted before and no longer need
        IdentityHashMap<View, Boolean> keep = new IdentityHashMap<>();
        for (Map.Entry<View, Float> e : shift.entrySet()) {
            View v = e.getKey();
            keep.put(v, true);
            float tx = Math.round(e.getValue());
            if (Math.abs(v.getTranslationX() - tx) > 0.5f) v.setTranslationX(tx);
            if (!applied.containsKey(v)) { applied.put(v, true); unclip(v, root); }
        }

        // ---- 3. absolute positions for the moved atoms (their parents are shifted by now)
        float screenW = root.getWidth();
        float holeL = screenW / 2 - 6 * density, holeR = screenW / 2 + 6 * density;
        WindowInsets wi = root.getRootWindowInsets();
        DisplayCutout dc = wi == null ? null : wi.getDisplayCutout();
        if (dc != null) {
            for (Rect r : dc.getBoundingRects()) {
                if (r.top > 0 || r.isEmpty()) continue;
                holeL = r.left - rloc[0];
                holeR = r.right - rloc[0];
                break;
            }
        }
        // edges follow Samsung's own side padding, so "Left edge" lines up with where the clock normally starts
        float leftEdge = 16 * density, rightEdge = 16 * density;
        if (startBox != null && startBox.getWidth() > 0) {
            startBox.getLocationOnScreen(loc);
            leftEdge = loc[0] - rloc[0] - startBox.getTranslationX();
        }
        if (endBox != null && endBox.getWidth() > 0) {
            endBox.getLocationOnScreen(loc);
            rightEdge = screenW - (loc[0] - rloc[0] - endBox.getTranslationX() + endBox.getWidth());
        }
        if (c.edgeEff() >= 0) { leftEdge = c.edgeEff() * density; rightEdge = c.edgeEff() * density; }
        float[] x = ZoneLayout.solve(zone, w, screenW, holeL, holeR, leftEdge, rightEdge, gapPx, 8 * density);
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) {
            if (!mv[i] || Float.isNaN(x[i])) continue;
            View v = views[i];
            keep.put(v, true);
            v.getLocationOnScreen(loc);
            float natural = loc[0] - rloc[0] - v.getTranslationX();
            float tx = x[i] - natural;
            if (Math.abs(v.getTranslationX() - tx) > 0.5f) v.setTranslationX(tx);
            if (!applied.containsKey(v)) { applied.put(v, true); unclip(v, root); }
        }

        for (View v : new java.util.ArrayList<>(applied.keySet())) {
            if (v != null && !keep.containsKey(v)) { applied.remove(v); if (v.getTranslationX() != 0) v.setTranslationX(0); }
        }
    }

    private final WeakHashMap<View, int[]> origRoom = new WeakHashMap<>();

    private void setRoom(View v, boolean start, int px) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
        int[] o = origRoom.get(v);
        if (o == null) { o = new int[]{m.getMarginStart(), m.getMarginEnd()}; origRoom.put(v, o); }
        int wantStart = start ? o[0] + px : o[0], wantEnd = start ? o[1] : o[1] + px;
        if (m.getMarginStart() != wantStart || m.getMarginEnd() != wantEnd) {
            m.setMarginStart(wantStart);
            m.setMarginEnd(wantEnd);
            v.setLayoutParams(m);
        }
    }

    /**
     * Items parked beside the camera hole must not sit on top of Samsung's own icons: shrink the
     * left content (notification icons) or the right content (system icons) by the space they take.
     */
    private void applyHoleRoom(View root, int[] zone, float[] w, boolean[] mv, float gapPx) {
        float left = 0, right = 0;
        int nl = 0, nr = 0;
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) {
            if (!mv[i]) continue;
            if (zone[i] == ZoneLayout.HOLE_LEFT) { left += w[i]; nl++; }
            if (zone[i] == ZoneLayout.HOLE_RIGHT) { right += w[i]; nr++; }
        }
        if (nl > 0) left += gapPx * (nl - 1) + gapPx;
        if (nr > 0) right += gapPx * (nr - 1) + gapPx;
        int lid = root.getResources().getIdentifier("status_bar_left_container", "id", "com.android.systemui");
        int rid = root.getResources().getIdentifier("system_icon_area", "id", "com.android.systemui");
        View lb = lid == 0 ? null : root.findViewById(lid), rb = rid == 0 ? null : root.findViewById(rid);
        if (lb != null && (nl > 0 || origRoom.containsKey(lb))) setRoom(lb, false, Math.round(left));
        if (rb != null && (nr > 0 || origRoom.containsKey(rb))) setRoom(rb, true, Math.round(right));
    }

    /** "Space between items": the gap in front of the clock row (between the battery and the temperature / clock). */
    private void applyItemGap(Config c, View root) {
        int id = root.getResources().getIdentifier("right_clock_container", "id", "com.android.systemui");
        View v = id == 0 ? null : root.findViewById(id);
        if (v == null || !(v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        int[] o = origGap.get(v);
        if (c.itemGap < 0) {
            if (o != null) { origGap.remove(v); m.setMarginStart(o[0]); v.setLayoutParams(m); }
            return;
        }
        if (o == null) { o = new int[]{m.getMarginStart()}; origGap.put(v, o); }
        int px = Math.round(c.itemGap * v.getResources().getDisplayMetrics().density);
        if (m.getMarginStart() != px) { m.setMarginStart(px); v.setLayoutParams(m); }
    }
    private final WeakHashMap<View, int[]> origGap = new WeakHashMap<>();

    private java.lang.ref.WeakReference<View> mobileBox = new java.lang.ref.WeakReference<>(null);

    /**
     * One connection icon: while Wi-Fi is up, Samsung's mobile-signal icon next to it is folded away (zero width,
     * invisible), so the Wi-Fi icon alone says what is running. When Wi-Fi drops, the mobile icon returns by itself.
     */
    private void applyNetMerge(Config c, View root) {
        View box = mobileBox.get();
        if (box == null || !box.isAttachedToWindow()) {
            int mid = root.getResources().getIdentifier("mobile_signal", "id", "com.android.systemui");
            int sid = root.getResources().getIdentifier("statusIcons", "id", "com.android.systemui");
            View m = mid == 0 ? null : root.findViewById(mid);
            View row = sid == 0 ? null : root.findViewById(sid);
            box = null;
            if (m != null && row != null) {
                View n = m;
                while (n != null && n.getParent() != row) n = n.getParent() instanceof View ? (View) n.getParent() : null;
                box = n;
            }
            mobileBox = new java.lang.ref.WeakReference<>(box);
        }
        if (box == null) return;
        int wid = root.getResources().getIdentifier("wifi_signal", "id", "com.android.systemui");
        View wifi = wid == 0 ? null : root.findViewById(wid);
        boolean wifiUp = wifi != null && wifi.isShown() && wifi.getWidth() > 0;
        if (c.mergeNet && wifiUp) {
            collapse(box);
            if (box.getAlpha() != 0f) box.setAlpha(0f);
        } else if (origMargins.containsKey(box)) {
            uncollapse(box);
            if (box.getAlpha() != 1f) box.setAlpha(1f);
        }
    }

    private int[] origPad;
    private java.lang.ref.WeakReference<View> padView = new java.lang.ref.WeakReference<>(null);

    /**
     * Edge spacing: Samsung pads the whole status bar from the screen sides (clearing the rounded
     * corners). Reducing that padding lets everything - and the zones - sit nearer the curved edges,
     * and gives the left and right rows more room.
     */
    private void applyEdge(Config c, View root) {
        if (root == null) return;
        View v = padView.get();
        if (v == null || !v.isAttachedToWindow()) {
            int id = root.getResources().getIdentifier("status_bar_contents", "id", "com.android.systemui");
            v = id == 0 ? null : root.findViewById(id);
            padView = new java.lang.ref.WeakReference<>(v);
            origPad = null;
        }
        if (v == null) return;
        int e = c.edgeEff();
        if (e >= 0) {
            if (origPad == null) origPad = new int[]{v.getPaddingLeft(), v.getPaddingRight()};
            int px = Math.round(e * v.getResources().getDisplayMetrics().density);
            if (v.getPaddingLeft() != px || v.getPaddingRight() != px)
                v.setPadding(px, v.getPaddingTop(), px, v.getPaddingBottom());
        } else if (origPad != null) {
            v.setPadding(origPad[0], v.getPaddingTop(), origPad[1], v.getPaddingBottom());
            origPad = null;
        }
        trimRightGap(c, root, e);
        applyRightUniform(c, root);
    }

    private java.lang.ref.WeakReference<View> trimView = new java.lang.ref.WeakReference<>(null);
    private final int[] tloc = new int[2];

    /**
     * Samsung's icon groups have their own inner padding, and the slots of items we moved stay behind
     * as empty space, so the last visible icon never reaches the screen edge even with the padding at 0.
     * Slide the right-hand content over until the last visible icon is exactly the chosen distance from the edge.
     */
    private void trimRightGap(Config c, View root, int edgeDp) {
        int id = root.getResources().getIdentifier("status_bar_end_side_content", "id", "com.android.systemui");
        View box = trimView.get();
        if (box == null || !box.isAttachedToWindow()) {
            box = id == 0 ? null : root.findViewById(id);
            trimView = new java.lang.ref.WeakReference<>(box);
        }
        if (box == null) return;
        if (edgeDp < 0) { if (box.getTranslationX() != 0) box.setTranslationX(0); return; }
        float density = root.getResources().getDisplayMetrics().density;
        root.getLocationOnScreen(rloc);
        float leafR = lastLeafRight(box);
        if (leafR < 0) return;
        float gap = (rloc[0] + root.getWidth()) - leafR - edgeDp * density;   // > 0: too far from the edge
        if (Math.abs(gap) >= 1f) box.setTranslationX(box.getTranslationX() + Math.round(gap));
    }

    /** Where the visible ink of a text or image view really is (screen px): {left, right}. Other views: their box. */
    private float[] glyphBounds(View v) {
        v.getLocationOnScreen(tloc);
        float l = tloc[0], r = tloc[0] + v.getWidth();
        try {
            if (v instanceof android.widget.TextView) {
                android.widget.TextView t = (android.widget.TextView) v;
                android.text.Layout lay = t.getLayout();
                if (lay != null && lay.getLineCount() > 0) {
                    float a = Float.MAX_VALUE, b = -1;
                    for (int i = 0; i < lay.getLineCount(); i++) { a = Math.min(a, lay.getLineLeft(i)); b = Math.max(b, lay.getLineRight(i)); }
                    l = tloc[0] + t.getCompoundPaddingLeft() + a;
                    r = tloc[0] + t.getCompoundPaddingLeft() + b;
                }
            } else if (v instanceof android.widget.ImageView) {
                android.widget.ImageView iv = (android.widget.ImageView) v;
                android.graphics.drawable.Drawable d = iv.getDrawable();
                if (d != null) {
                    android.graphics.RectF rc = new android.graphics.RectF(d.getBounds());
                    if (rc.isEmpty()) rc.set(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
                    iv.getImageMatrix().mapRect(rc);
                    l = tloc[0] + iv.getPaddingLeft() + rc.left;
                    r = tloc[0] + iv.getPaddingLeft() + rc.right;
                }
            }
        } catch (Throwable ignored) {}
        return new float[]{l, r};
    }

    private java.lang.ref.WeakReference<View> speedBox = new java.lang.ref.WeakReference<>(null);

    /**
     * Even spacing on the right: the net-speed text sits exactly one "space between items" away from the Wi-Fi icon
     * (measured on the drawn glyphs, not on Samsung's padded boxes). The Wi-Fi icon itself is lined up with the screen edge
     * by trimRightGap, so spacing no longer depends on each icon's hidden padding.
     */
    private void applyRightUniform(Config c, View root) {
        View sp = speedBox.get();
        if (sp == null || !sp.isAttachedToWindow()) {
            int id = root.getResources().getIdentifier("networkSpeed", "id", "com.android.systemui");
            sp = id == 0 ? null : root.findViewById(id);
            speedBox = new java.lang.ref.WeakReference<>(sp);
        }
        if (sp == null) return;
        if (c.itemGap < 0 || !sp.isShown() || sp.getWidth() <= 0) { if (sp.getTranslationX() != 0) sp.setTranslationX(0); return; }
        int wid = root.getResources().getIdentifier("wifi_signal", "id", "com.android.systemui");
        View wifi = wid == 0 ? null : root.findViewById(wid);
        if (wifi == null || !wifi.isShown() || wifi.getWidth() <= 0) return;
        float sr = lastLeafRight(sp);
        if (sr < 0) return;
        float wl = glyphBounds(wifi)[0];
        float gap = c.itemGap * root.getResources().getDisplayMetrics().density;
        float delta = (wl - gap) - sr;
        if (Math.abs(delta) >= 1f) sp.setTranslationX(sp.getTranslationX() + Math.round(delta));
    }

    /** Right edge (screen px) of the right-most thing actually drawn inside v, or -1 if nothing is. */
    private float lastLeafRight(View v) {
        if (v.getVisibility() != View.VISIBLE || v.getWidth() <= 0 || v.getHeight() <= 0 || v.getAlpha() < 0.05f) return -1;
        boolean group = v instanceof ViewGroup && !v.getClass().getName().contains("Compose");
        if (!group) {
            if (v instanceof android.widget.TextView && ((android.widget.TextView) v).length() == 0) return -1;
            if (v instanceof android.widget.ImageView && ((android.widget.ImageView) v).getDrawable() == null) return -1;
            float[] gb = glyphBounds(v);
            return gb[1];
        }
        ViewGroup g = (ViewGroup) v;
        float best = -1;
        for (int i = 0; i < g.getChildCount(); i++) best = Math.max(best, lastLeafRight(g.getChildAt(i)));
        return best;
    }

    private static void add(IdentityHashMap<View, Float> m, View v, float d) {
        Float old = m.get(v);
        m.put(v, (old == null ? 0f : old) + d);
    }

    private static void pushChildren(IdentityHashMap<View, Float> shift, ViewGroup box, float d) {
        for (int k = 0; k < box.getChildCount(); k++) {
            View s = box.getChildAt(k);
            if (s.getVisibility() != View.GONE) add(shift, s, d);
        }
    }

    private final WeakHashMap<View, int[]> origMargins = new WeakHashMap<>();

    /** Take the item out of the row's width: a negative end margin cancels its own width. */
    private void collapse(View v) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams) || v.getWidth() <= 0) return;
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
        int[] o = origMargins.get(v);
        if (o == null) { o = new int[]{m.getMarginStart(), m.getMarginEnd()}; origMargins.put(v, o); }
        int want = -(v.getWidth() + o[0]);
        if (m.getMarginStart() != o[0] || m.getMarginEnd() != want) {
            m.setMarginStart(o[0]);
            m.setMarginEnd(want);
            v.setLayoutParams(m);
        }
    }

    private void uncollapse(View v) {
        int[] o = origMargins.remove(v);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (o == null || !(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
        m.setMarginStart(o[0]);
        m.setMarginEnd(o[1]);
        v.setLayoutParams(m);
    }

    /** The left or right status bar content row this item lives in (null if we can't tell). */
    private ViewGroup sideOf(View v, View root) {
        ViewParent p = v.getParent();
        while (p instanceof ViewGroup && p != root) {
            ViewGroup g = (ViewGroup) p;
            int id = g.getId();
            if (id != View.NO_ID && (id == endId || id == startId)) return g;
            p = g.getParent();
        }
        return null;
    }

    /** Put everything back (mod detaching / switching off / all zones Default). */
    void releaseAll() {
        View spx = speedBox.get();
        if (spx != null && spx.getTranslationX() != 0) spx.setTranslationX(0);
        View tb = trimView.get();
        if (tb != null && tb.getTranslationX() != 0) tb.setTranslationX(0);
        View pv = padView.get();
        if (pv != null && origPad != null) { pv.setPadding(origPad[0], pv.getPaddingTop(), origPad[1], pv.getPaddingBottom()); origPad = null; }
        releaseZones();
    }

    /** Undo item moves (translations, freed space) but leave the edge padding alone. */
    private void releaseZones() {
        for (java.util.Map.Entry<View, int[]> e : new java.util.ArrayList<>(origRoom.entrySet())) {
            View v = e.getKey();
            if (v != null && v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                m.setMarginStart(e.getValue()[0]); m.setMarginEnd(e.getValue()[1]); v.setLayoutParams(m);
            }
        }
        origRoom.clear();
        for (View v : new java.util.ArrayList<>(applied.keySet()))
            if (v != null && v.getTranslationX() != 0) v.setTranslationX(0);
        applied.clear();
        for (View v : new java.util.ArrayList<>(origMargins.keySet())) if (v != null) uncollapse(v);
        for (Map.Entry<ViewGroup, Boolean> e : new java.util.ArrayList<>(clipOrig.entrySet()))
            if (e.getKey() != null) e.getKey().setClipChildren(e.getValue());
        clipOrig.clear();
    }

    /** A moved view must not be cut off by the container it started in. */
    private void unclip(View v, View root) {
        ViewParent p = v.getParent();
        while (p instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) p;
            if (!clipOrig.containsKey(g)) clipOrig.put(g, g.getClipChildren());
            g.setClipChildren(false);
            if (g == root) break;
            p = g.getParent();
        }
    }
}
