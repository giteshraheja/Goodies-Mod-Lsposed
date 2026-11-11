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

    /** views: clock, temperature label, battery, back button, date (any may be null), in ZoneLayout element order. */
    void update(Config c, View root, View... viewsIn) {
        if (root == null) { releaseZones(); return; }   // status bar not attached (yet / any more)
        View[] views = java.util.Arrays.copyOf(viewsIn, ZoneLayout.ELEMENTS);
        if (views[ZoneLayout.NETSPEED] == null) {
            int nid = id(root, "networkSpeed");
            views[ZoneLayout.NETSPEED] = nid == 0 ? null : root.findViewById(nid);
        }
        applyEdge(c, root);   // uses rightReserve from the previous frame
        applyItemGap(c, root);
        applyNetMerge(c, root);
        ZoneLayout.Plan plan = c.plan.copy();
        if ((c.backLive == 3 || c.backLive == 4) && BackChip.stackLive) plan.mode[ZoneLayout.BACK] = ZoneLayout.STOCK;   // Stack: shares the live activity's spot
        if (!c.dateSeparate) plan.mode[ZoneLayout.DATE] = ZoneLayout.STOCK;
        // stuck to an item that isn't showing: take that item's place in the chain (stick to whatever IT was stuck to, or its edge spot).
        // Going back to Samsung's own place instead put it on top of the items that are still placed (the temperature over the battery
        // whenever the date was not showing). Only an item whose missing neighbour sat in Samsung's place goes back there.
        // (except the back button: without it, things stuck to it take its spot, right after the live activity)
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) {
            for (int hop = 0; hop < ZoneLayout.ELEMENTS && plan.mode[i] == ZoneLayout.LINK; hop++) {
                int t = plan.to[i];
                if (t == ZoneLayout.BACK) break;
                View tv = t >= 0 && t < ZoneLayout.ELEMENTS ? views[t] : null;
                if (tv != null && tv.isShown() && tv.getWidth() > 0 && tv.getAlpha() >= 0.05f) break;   // the neighbour is there
                if (t < 0 || t >= ZoneLayout.ELEMENTS) { plan.mode[i] = ZoneLayout.STOCK; break; }
                if (c.plan.mode[t] == ZoneLayout.LINK && c.plan.to[t] != i && !(t == ZoneLayout.DATE && !c.dateSeparate)) { plan.to[i] = c.plan.to[t]; plan.side[i] = c.plan.side[t]; }
                else if (c.plan.mode[t] == ZoneLayout.FREE && !(t == ZoneLayout.DATE && !c.dateSeparate)) { plan.mode[i] = ZoneLayout.FREE; plan.right[i] = c.plan.right[t]; plan.off[i] = c.plan.off[t]; }
                else plan.mode[i] = ZoneLayout.STOCK;
            }
        }
        rightReserve = 0;
        netPlaced = plan.mode[ZoneLayout.NETSPEED] != ZoneLayout.STOCK;
        java.util.Arrays.fill(movedViews, null);
        boolean any = plan.any();
        if (!any && applied.isEmpty()) return;
        if (!any || root == null) { BackChip.extraPad = 0; releaseZones(); return; }

        if (endId == -1) {
            endId = id(root, "status_bar_end_side_content");
            startId = id(root, "status_bar_start_side_except_heads_up");
        }
        float density = root.getResources().getDisplayMetrics().density;
        root.getLocationOnScreen(rloc);

        // which items are actually placed (showing + not in Samsung's place)
        float[] w = new float[ZoneLayout.ELEMENTS];
        boolean[] mv = new boolean[ZoneLayout.ELEMENTS];
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) {
            View v = i < views.length ? views[i] : null;
            if (v != null && plan.mode[i] != ZoneLayout.STOCK && v.isShown() && v.getWidth() > 0) {
                mv[i] = true;
                w[i] = v.getWidth() * v.getScaleX();
            }
        }
        // where items that stay in Samsung's place are right now (other items can be stuck to them, e.g. "right of the back button")
        float[] nat = new float[ZoneLayout.ELEMENTS];
        java.util.Arrays.fill(nat, Float.NaN);
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) {
            View v = views[i];
            if (v == null || plan.mode[i] != ZoneLayout.STOCK || !v.isShown() || v.getWidth() <= 0) continue;
            v.getLocationOnScreen(loc);
            nat[i] = loc[0] - rloc[0];   // where it really is right now (the back button is nudged sideways by its own code, so don't undo that)
            w[i] = v.getWidth() * v.getScaleX();
        }

        // ---- 1. take placed items out of their row's width, so Samsung's own layout closes the gap
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
        if (startBox == null) startBox = (ViewGroup) root.findViewById(startId);
        if (endBox == null) endBox = (ViewGroup) root.findViewById(endId);
        float gapPx = (c.itemGap >= 0 ? c.itemGap : 6) * density;

        // ---- 2. where the edges are
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
        // edges follow Samsung's own side padding, so an item at the edge lines up with where the clock normally starts
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

        // ---- 3. solve: positions of the placed items, and how much room Samsung's icons give up
        ZoneLayout.Plan px = plan.copy();
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) px.off[i] = Math.round(plan.off[i] * density);
        // no back button right now: things stuck to it take its spot - right after the live activity if one shows, else the far left
        View notifBox = null;
        boolean backGone = plan.mode[ZoneLayout.BACK] == ZoneLayout.STOCK && Float.isNaN(nat[ZoneLayout.BACK]);
        if (backGone) {
            nat[ZoneLayout.BACK] = leftEdge; w[ZoneLayout.BACK] = 0;
            int lid = id(root, "samsung_ongoing_activity_chip_container");
            View live = lid == 0 ? null : root.findViewById(lid);
            if (BackChip.capsuleShown(live)) {
                live.getLocationOnScreen(loc);
                nat[ZoneLayout.BACK] = loc[0] - rloc[0] - live.getTranslationX() + live.getWidth() + gapPx;
                int nbid = id(root, "start_side_notif_and_chip_container");
                notifBox = nbid == 0 ? null : root.findViewById(nbid);
            }
        }
        // the network-speed text has blank padding inside its box: space it by the drawn text so the gap to its neighbours is exactly "space between items"
        float spInset = 0;
        if (mv[ZoneLayout.NETSPEED] && views[ZoneLayout.NETSPEED] != null) {
            View sv = views[ZoneLayout.NETSPEED];
            float ink0, ink1;
            ignoreMoved = true;
            try { ink0 = nearestLeafLeftAfter(sv, null, -1e9f); ink1 = lastLeafRight(sv); } finally { ignoreMoved = false; }
            sv.getLocationOnScreen(loc);
            if (ink0 == Float.MAX_VALUE || ink1 < 0) w[ZoneLayout.NETSPEED] = 0;   // nothing drawn (no traffic): takes no room
            else if (ink1 > ink0) {
                float l0 = Math.max(0, ink0 - loc[0]), r0 = Math.max(0, loc[0] + w[ZoneLayout.NETSPEED] - ink1);
                if (l0 + r0 < w[ZoneLayout.NETSPEED] - 4 * density) { spInset = l0; w[ZoneLayout.NETSPEED] -= l0 + r0; }
            }
        }
        float[] x = ZoneLayout.solve(px, w, screenW, holeL, holeR, 8 * density, leftEdge, rightEdge, gapPx, nat);
        // (the blank inside the speed's box is taken off only when it is positioned, further down: until then x is where its drawn text starts,
        //  so the room Samsung's icons give up is measured from the text - measuring from the box left the first icon on top of the numbers)
        float safe = 0;   // keep inside the screen only: the edge distance you chose is honoured exactly (burn-in protection may still nudge the bar up to 3 px)
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++)
            if (!Float.isNaN(x[i]) && w[i] > 0) x[i] = Math.max(safe, Math.min(screenW - safe - w[i], x[i]));
        float leftBase = leftEdge;   // Samsung's notification icons start after a back button that stays in its place
        if (!Float.isNaN(nat[ZoneLayout.BACK]) && w[ZoneLayout.BACK] > 0) leftBase = Math.max(leftEdge, nat[ZoneLayout.BACK] + w[ZoneLayout.BACK] + gapPx);
        if (notifBox != null) leftBase = Math.max(leftEdge, nat[ZoneLayout.BACK]);   // no back button, live activity showing: the icons start right after it
        float[] ln = ZoneLayout.lanes(x, w, screenW, holeL, holeR, leftEdge, rightEdge, gapPx, 8 * density, 14 * density, leftBase);

        IdentityHashMap<View, Float> shift = new IdentityHashMap<>();
        float pushLeft = ln[0] - leftBase;
        boolean chipBase = !backGone && leftBase > leftEdge + 0.5f;   // a back button in Samsung's place: its own row padding makes the room (a push would drag the button along)
        BackChip.extraPad = chipBase && pushLeft > 0.5f ? pushLeft : 0;
        if (notifBox != null) { if (pushLeft > 0.5f) add(shift, notifBox, pushLeft); }   // only the icons make room: the live activity stays put
        else if (startBox != null && !chipBase && pushLeft > 0.5f) pushChildren(shift, startBox, pushLeft);
        float rightPush = (screenW - rightEdge) - ln[2];
        if (endBox != null && rightPush > 0.5f) pushChildren(shift, endBox, -rightPush);
        rightReserve = Math.max(0, rightPush);
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) if (mv[i]) movedViews[i] = views[i];
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) if (mv[i]) shift.remove(views[i]);   // placed items get absolute positions below

        // ---- 4. apply shifts; reset whatever we shifted before and no longer need
        IdentityHashMap<View, Boolean> keep = new IdentityHashMap<>();
        for (Map.Entry<View, Float> e : shift.entrySet()) {
            View v = e.getKey();
            keep.put(v, true);
            float tx = Math.round(e.getValue());
            if (Math.abs(v.getTranslationX() - tx) > 0.5f) v.setTranslationX(tx);
            if (!applied.containsKey(v)) { applied.put(v, true); unclip(v, root); }
        }

        // ---- 5. absolute positions for the placed items (their parents are shifted by now)
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) {
            if (!mv[i] || Float.isNaN(x[i])) continue;
            View v = views[i];
            keep.put(v, true);
            v.getLocationOnScreen(loc);
            float natural = loc[0] - rloc[0] - v.getTranslationX();
            float tx = x[i] - (i == ZoneLayout.NETSPEED ? spInset : 0) - natural;
            if (Math.abs(v.getTranslationX() - tx) > 0.5f) v.setTranslationX(tx);
            if (!applied.containsKey(v)) { applied.put(v, true); unclip(v, root); }
        }

        // ---- 6. Samsung's icons that still reach into the space of placed items are cut off cleanly
        // (only Samsung's icons themselves: the battery lives in the same box and must not be cut)
        String[] left = {"notificationIcons"}, right = netPlaced ? new String[]{"statusIcons"} : new String[]{"networkSpeed", "statusIcons"};
        for (String n : left) { int id = id(root, n); clipTo(id == 0 ? null : root.findViewById(id), false, ln[1], rloc[0]); }
        for (String n : right) { int id = id(root, n); clipTo(id == 0 ? null : root.findViewById(id), true, ln[3], rloc[0]); }
        if (netPlaced && views[ZoneLayout.NETSPEED] != null && views[ZoneLayout.NETSPEED].getClipBounds() != null) views[ZoneLayout.NETSPEED].setClipBounds(null);   // placed: drop a cut left from when it sat in Samsung's place

        for (View v : new java.util.ArrayList<>(applied.keySet())) {
            if (v != null && !keep.containsKey(v)) { applied.remove(v); if (v.getTranslationX() != 0) v.setTranslationX(0); }
        }
    }

    private final java.util.ArrayList<java.lang.ref.WeakReference<View>> clipped = new java.util.ArrayList<>();

    /** Cuts v at a screen-x limit (from its right side: keep left part; or from its left side: keep right part). */
    private void clipTo(View v, boolean cutLeft, float limit, int rootLeft) {
        if (v == null || v.getWidth() <= 0) return;
        v.getLocationOnScreen(loc);
        float lx = limit + rootLeft - loc[0];            // limit in the view's own coordinates
        Rect cb = null;
        if (!cutLeft && lx < v.getWidth()) cb = new Rect(0, 0, Math.max(0, Math.round(lx)), v.getHeight());
        if (cutLeft && lx > 0) cb = new Rect(Math.min(v.getWidth(), Math.round(lx)), 0, v.getWidth(), v.getHeight());
        Rect cur = v.getClipBounds();
        if (cb == null) { if (cur != null) v.setClipBounds(null); return; }
        if (!cb.equals(cur)) v.setClipBounds(cb);
        for (java.lang.ref.WeakReference<View> r : clipped) if (r.get() == v) return;
        clipped.add(new java.lang.ref.WeakReference<>(v));
    }

    /** Placed items (clock, temperature, battery, back pill, date): not icons, ignored when lining icons up with the edge. */
    private final View[] movedViews = new View[ZoneLayout.ELEMENTS];

    /** "Space between items": the gap in front of the clock row (between the battery and the temperature / clock). */
    private void applyItemGap(Config c, View root) {
        int id = id(root, "right_clock_container");
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
            int mid = id(root, "mobile_signal");
            int sid = id(root, "statusIcons");
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
        int wid = id(root, "wifi_signal");
        View wifi = wid == 0 ? null : root.findViewById(wid);
        boolean wifiUp = wifi != null && wifi.isShown() && wifi.getWidth() > 0;
        if (c.mergeNet && wifiUp) {
            if (hookMobile(box)) {   // Samsung's own layout leaves the icon out: no room kept for it
                if (origMargins.containsKey(box)) uncollapse(box);
                if (!mergeHide) { mergeHide = true; relayout(box); }
            } else collapse(box);
            mergeApplied = true;
            if (box.getAlpha() != 0f) box.setAlpha(0f);
        } else if (mergeApplied || mergeHide) {
            mergeApplied = false;
            if (mergeHide) { mergeHide = false; relayout(box); }
            if (origMargins.containsKey(box)) uncollapse(box);
            if (box.getAlpha() != 1f) box.setAlpha(1f);
        }
    }

    private boolean mergeApplied;
    private static volatile boolean mergeHide;   // read by the hook below
    private static Class<?> mobileCls;
    private static boolean mergeHooked, mergeHookFailed;

    private static void relayout(View box) {
        box.requestLayout();
        if (box.getParent() instanceof View) ((View) box.getParent()).requestLayout();
    }

    /**
     * Samsung's icon row asks every icon "are you visible?" when it measures and places them (it ignores margins, so an icon
     * can't be folded away from outside). While the connection icons are merged, the mobile icon answers "no" and so gets no
     * room at all. Hooked once, on the first use; costs one flag read per question. False = not possible on this phone.
     */
    private static boolean hookMobile(View box) {
        if (mergeHooked) return true;
        if (mergeHookFailed) return false;
        try {
            java.lang.reflect.Method m = null;
            for (Class<?> k = box.getClass(); k != null && m == null; k = k.getSuperclass())
                try { m = k.getDeclaredMethod("isIconVisible"); } catch (NoSuchMethodException ignored) {}
            if (m == null || m.getReturnType() != boolean.class || java.lang.reflect.Modifier.isAbstract(m.getModifiers())) { mergeHookFailed = true; return false; }
            mobileCls = box.getClass();
            de.robv.android.xposed.XposedBridge.hookMethod(m, new de.robv.android.xposed.XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (mergeHide && mobileCls.isInstance(p.thisObject)) p.setResult(false);
                }
            });
            mergeHooked = true;
            return true;
        } catch (Throwable t) { mergeHookFailed = true; Log.w("net merge hook: " + t); return false; }
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
            int id = id(root, "status_bar_contents");
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

    /** Width kept free at the right edge for items parked at "Far right" (trimRightGap must not undo the push). */
    private float rightReserve;
    private boolean netPlaced;
    private java.lang.ref.WeakReference<View> trimView = new java.lang.ref.WeakReference<>(null);
    private final int[] tloc = new int[2];

    /**
     * Samsung's icon groups have their own inner padding, and the slots of items we moved stay behind
     * as empty space, so the last visible icon never reaches the screen edge even with the padding at 0.
     * Slide the right-hand content over until the last visible icon is exactly the chosen distance from the edge.
     */
    private void trimRightGap(Config c, View root, int edgeDp) {
        int id = id(root, "status_bar_end_side_content");
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
        float gap = (rloc[0] + root.getWidth()) - leafR - edgeDp * density - rightReserve;   // > 0: too far from the edge
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
        if (netPlaced) return;   // the speed text is placed by the layout, not lined up with Wi-Fi
        View sp = speedBox.get();
        if (sp == null || !sp.isAttachedToWindow()) {
            int id = id(root, "networkSpeed");
            sp = id == 0 ? null : root.findViewById(id);
            speedBox = new java.lang.ref.WeakReference<>(sp);
        }
        if (sp == null) return;
        if (c.itemGap < 0 || !sp.isShown() || sp.getWidth() <= 0) { if (sp.getTranslationX() != 0) sp.setTranslationX(0); return; }
        int wid = id(root, "wifi_signal");
        View wifi = wid == 0 ? null : root.findViewById(wid);
        if (wifi == null || !wifi.isShown() || wifi.getWidth() <= 0) return;
        float sr = lastLeafRight(sp);
        if (sr < 0) return;
        float wl = glyphBounds(wifi)[0];
        // other icons (mute, hotspot...) may sit between the speed text and Wi-Fi: keep the gap to the nearest one
        int eid = id(root, "status_bar_end_side_content");
        View eb = eid == 0 ? null : root.findViewById(eid);
        if (eb != null) {
            sp.getLocationOnScreen(tloc);
            float natL = tloc[0] - sp.getTranslationX() + sp.getWidth() / 2f;
            wl = Math.min(wl, nearestLeafLeftAfter(eb, sp, natL));
        }
        float gap = c.itemGap * root.getResources().getDisplayMetrics().density;
        float delta = (wl - gap) - sr;
        if (Math.abs(delta) >= 1f) sp.setTranslationX(sp.getTranslationX() + Math.round(delta));
    }

    /** Left edge of the left-most drawn leaf (other than skip) that lies right of x; MAX if none. */
    private float nearestLeafLeftAfter(View v, View skip, float x) {
        if (v == skip || v.getVisibility() != View.VISIBLE || v.getWidth() <= 0 || v.getHeight() <= 0 || v.getAlpha() < 0.05f) return Float.MAX_VALUE;
        boolean group = v instanceof ViewGroup && !v.getClass().getName().contains("Compose");
        if (!group) {
            if (v instanceof android.widget.TextView && ((android.widget.TextView) v).length() == 0) return Float.MAX_VALUE;
            if (v instanceof android.widget.ImageView && ((android.widget.ImageView) v).getDrawable() == null) return Float.MAX_VALUE;
            float[] gb = glyphBounds(v);
            return gb[0] > x ? gb[0] : Float.MAX_VALUE;
        }
        ViewGroup g = (ViewGroup) v;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < g.getChildCount(); i++) best = Math.min(best, nearestLeafLeftAfter(g.getChildAt(i), skip, x));
        return best;
    }

    /** Right edge (screen px) of the right-most thing actually drawn inside v, or -1 if nothing is. */
    private boolean ignoreMoved;
    private float lastLeafRight(View v) {
        if (!ignoreMoved) for (View m : movedViews) if (m == v) return -1;
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

    private final java.util.HashMap<String, Integer> ids = new java.util.HashMap<>();

    /** SystemUI view id by name, looked up once (by-name lookups are slow and this runs every frame). */
    private int id(View root, String name) {
        Integer v = ids.get(name);
        if (v == null) { v = root.getResources().getIdentifier(name, "id", "com.android.systemui"); ids.put(name, v); }
        return v;
    }

    /** Put everything back (mod detaching / switching off / all zones Default). */
    void releaseAll() {
        BackChip.extraPad = 0;   // no leftover room after the back button
        ignoreMoved = false;
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
        if (mergeHide) { mergeHide = false; View mb = mobileBox.get(); if (mb != null) { relayout(mb); mb.setAlpha(1f); } mergeApplied = false; }
        for (java.lang.ref.WeakReference<View> r : clipped) { View cv = r.get(); if (cv != null && cv.getClipBounds() != null) cv.setClipBounds(null); }
        clipped.clear();
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
