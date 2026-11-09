// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

/**
 * Free placement of the movable status bar items, shared by the SystemUI hook (real pixels) and the
 * settings preview (dp), so what you drag in the app is what the status bar does.
 *
 * Every item is in one of three states:
 *  - STOCK: stays where Samsung put it;
 *  - FREE:  a distance from the left or the right screen edge (anchored to that edge, so an item
 *           at the right edge stays at the right edge however wide its text gets);
 *  - LINK:  stuck to the left or right side of another item and follows it.
 * Samsung's own icons (notifications on the left, system icons on the right) make room by themselves.
 */
public final class ZoneLayout {
    public static final int CLOCK = 0, TEMP = 1, BATTERY = 2, BACK = 3, DATE = 4, NETSPEED = 5, ELEMENTS = 6;
    public static final String[] ELEMENT_NAMES = {"Clock", "Temperature", "Battery", "Back button", "Date", "Network speed"};
    public static final int STOCK = 0, FREE = 1, LINK = 2;

    private ZoneLayout() {}

    /** The placement of all items (arrays indexed by element). */
    public static final class Plan {
        public int[] mode = new int[ELEMENTS];
        /** FREE: what the distance is measured from: 0 left screen edge, 1 right screen edge, 2 left side of the camera, 3 right side of the camera. */
        public int[] right = new int[ELEMENTS];
        /** FREE: distance from that anchor (screen edges: inside Samsung's side padding) to the near side of the item. */
        public int[] off = new int[ELEMENTS];
        /** LINK: the other item, and which side of it (0 = left of it, 1 = right of it). */
        public int[] to = new int[ELEMENTS];
        public int[] side = new int[ELEMENTS];

        public Plan copy() {
            Plan p = new Plan();
            for (int i = 0; i < ELEMENTS; i++) {
                p.mode[i] = mode[i]; p.right[i] = right[i]; p.off[i] = off[i]; p.to[i] = to[i]; p.side[i] = side[i];
            }
            return p;
        }

        public boolean any() {
            for (int m : mode) if (m != STOCK) return true;
            return false;
        }
    }

    /**
     * Left x of every item, in the unit of the arguments (px or dp), NaN for items that stay in Samsung's place.
     * @param w item widths (0 = not showing; such an item takes no room, but items stuck to it keep its spot)
     */
    public static float[] solve(Plan pl, float[] w, float screenW, float holeL, float holeR, float holeGap, float edgeL, float edgeR, float gap) {
        return solve(pl, w, screenW, holeL, holeR, holeGap, edgeL, edgeR, gap, null);
    }

    /** @param nat where items that stay in Samsung's place are right now (NaN = unknown): other items can be stuck to them */
    public static float[] solve(Plan pl, float[] w, float screenW, float holeL, float holeR, float holeGap, float edgeL, float edgeR, float gap, float[] nat) {
        float[] x = new float[ELEMENTS];
        boolean[] done = new boolean[ELEMENTS], busy = new boolean[ELEMENTS];
        for (int i = 0; i < ELEMENTS; i++) x[i] = Float.NaN;
        for (int i = 0; i < ELEMENTS; i++) resolve(pl, w, screenW, holeL, holeR, holeGap, edgeL, edgeR, gap, i, x, done, busy, nat);
        for (int i = 0; i < ELEMENTS; i++)
            if (!Float.isNaN(x[i])) x[i] = Math.max(0, Math.min(Math.max(0, screenW - w[i]), x[i]));
        return x;
    }

    private static void resolve(Plan pl, float[] w, float screenW, float holeL, float holeR, float holeGap, float edgeL, float edgeR, float gap,
                                int i, float[] x, boolean[] done, boolean[] busy, float[] nat) {
        if (done[i]) return;
        if (busy[i]) return;           // a loop of links: leave it in Samsung's place
        busy[i] = true;
        if (pl.mode[i] == FREE) {
            int a = pl.right[i];
            x[i] = a == 1 ? screenW - edgeR - pl.off[i] - w[i]
                    : a == 2 ? holeL - holeGap - pl.off[i] - w[i]
                    : a == 3 ? holeR + holeGap + pl.off[i]
                    : edgeL + pl.off[i];
        } else if (pl.mode[i] == LINK) {
            int t = pl.to[i];
            if (t >= 0 && t < ELEMENTS && t != i) {
                float xt;
                if (pl.mode[t] != STOCK) {
                    resolve(pl, w, screenW, holeL, holeR, holeGap, edgeL, edgeR, gap, t, x, done, busy, nat);
                    xt = x[t];
                } else xt = nat != null ? nat[t] : Float.NaN;   // stuck to an item in Samsung's place: where it is now
                if (!Float.isNaN(xt)) {
                    float g = w[t] > 0 && w[i] > 0 ? gap : 0;
                    float onRight = xt + w[t] + g, onLeft = xt - g - w[i];
                    boolean right = pl.side[i] == 1;
                    // no room on its side of the other item (it grew, or the row got long): go to the other side instead of piling on top
                    if (right && onRight + w[i] > screenW && onLeft >= 0) right = false;
                    else if (!right && onLeft < 0 && onRight + w[i] <= screenW) right = true;
                    x[i] = right ? onRight : onLeft;
                }
            }
        }
        busy[i] = false;
        done[i] = true;
    }

    /**
     * How much room Samsung's own icons must give. Returns {leftStart, leftClipRight, rightEnd, rightClipLeft}:
     * the notification icons begin at leftStart and are cut off at leftClipRight; the system icons end at rightEnd
     * and are cut off from the left at rightClipLeft. Items at an edge push the icons over, items further in cut them.
     */
    public static float[] lanes(float[] x, float[] w, float screenW, float holeL, float holeR,
                                float edgeL, float edgeR, float gap, float holeGap, float threshold) {
        return lanes(x, w, screenW, holeL, holeR, edgeL, edgeR, gap, holeGap, threshold, edgeL);
    }

    /** @param leftBase where the notification icons really start (after a back button in Samsung's place) */
    public static float[] lanes(float[] x, float[] w, float screenW, float holeL, float holeR,
                                float edgeL, float edgeR, float gap, float holeGap, float threshold, float leftBase) {
        float s = leftBase, clipR = holeL - holeGap, e = screenW - edgeR, clipL = holeR + holeGap;
        // left lane: items that start inside it, nearest the edge first
        java.util.List<Integer> idx = new java.util.ArrayList<>();
        for (int i = 0; i < ELEMENTS; i++) if (!Float.isNaN(x[i]) && w[i] > 0) idx.add(i);
        idx.sort((a, b) -> Float.compare(x[a], x[b]));
        for (int i : idx) {
            if (x[i] >= holeL - holeGap) break;
            if (x[i] <= s + threshold) s = Math.max(s, x[i] + w[i] + gap);
            else { clipR = Math.min(clipR, x[i] - gap); break; }
        }
        idx.sort((a, b) -> Float.compare(x[b] + w[b], x[a] + w[a]));
        for (int i : idx) {
            if (x[i] + w[i] <= holeR + holeGap) break;
            if (x[i] + w[i] >= e - threshold) e = Math.min(e, x[i] - gap);
            else { clipL = Math.max(clipL, x[i] + w[i] + gap); break; }
        }
        return new float[]{s, clipR, e, clipL};
    }
}
