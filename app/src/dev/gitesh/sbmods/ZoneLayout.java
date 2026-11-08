// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

/**
 * Where each movable status bar element goes. Shared by the SystemUI hook (real pixels) and the
 * settings preview (preview units), so what you see in the app is what the status bar does.
 * Elements in the same zone sit side by side in a fixed order: clock, temperature, battery.
 */
public final class ZoneLayout {
    public static final int STOCK = 0, LEFT = 1, HOLE_LEFT = 2, HOLE_RIGHT = 3, RIGHT = 4;
    public static final int COUNT = 5;
    public static final String[] NAMES = {"Samsung's place", "Far left", "Left of camera", "Right of camera", "Far right"};
    /** Movable elements (index = Config.clockZone / tempZone / batteryZone order). */
    public static final int CLOCK = 0, TEMP = 1, BATTERY = 2, BACK = 3, ELEMENTS = 4;
    public static final String[] ELEMENT_NAMES = {"Clock", "Temperature", "Battery", "Back button"};
    /** Order of items sharing a zone, left to right. */
    public static final int[] ORDER = {BACK, CLOCK, TEMP, BATTERY};

    private ZoneLayout() {}

    /**
     * @param zone     zone per element
     * @param w        element widths (0 = element not showing, ignored)
     * @param screenW  width of the bar
     * @param holeL    left side of the camera hole, holeR its right side
     * @param leftEdge  where the left edge zone starts, rightEdge the margin on the right side
     * @param gap      space between elements sharing a zone
     * @param holeGap  space between the camera hole and its neighbours
     * @return left x of each element, or NaN where the element stays where Samsung put it
     */
    public static float[] solve(int[] zone, float[] w, float screenW, float holeL, float holeR,
                                float leftEdge, float rightEdge, float gap, float holeGap) {
        float[] out = new float[zone.length];
        java.util.Arrays.fill(out, Float.NaN);
        for (int z = LEFT; z <= RIGHT; z++) {
            float total = 0;
            int n = 0;
            for (int i : ORDER)
                if (zone[i] == z && w[i] > 0) { total += w[i]; n++; }
            if (n == 0) continue;
            total += gap * (n - 1);
            float x = z == LEFT ? leftEdge
                    : z == HOLE_LEFT ? holeL - holeGap - total
                    : z == HOLE_RIGHT ? holeR + holeGap
                    : screenW - rightEdge - total;
            if (x < 0) x = 0;
            for (int i : ORDER)
                if (zone[i] == z && w[i] > 0) { out[i] = x; x += w[i] + gap; }
        }
        return out;
    }
}
