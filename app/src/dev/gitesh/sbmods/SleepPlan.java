// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

/**
 * "Sleep sooner" decisions. No Android classes (the settings app shares it and it can be tested on a plain JVM).
 * Smart mode learns, for each hour of the day, how often the phone is left alone for a long time once the screen has gone off
 * (not the average: one long night must not make a busy hour look quiet), and picks the doze timing for the next screen-off.
 * Levels: 0 = Samsung's own timing, 1 = sooner, 2 = soonest.
 */
public final class SleepPlan {
    private SleepPlan() {}

    public static final int HOURS = 24;
    public static final int MIN_SAMPLES = 4;      // an hour needs this many observations before it is trusted
    public static final int LONG_MIN = 60;        // "left alone" = the screen stayed off at least this long
    public static final int LONGER_MIN = 180;     // ... and this long
    public static final int TRUST_PCT = 70;       // sooner only when at least this share of the recent screen-offs in that hour were that long
    private static final int SAMPLE_CAP = 50;
    private static final double KEEP = 0.8;       // weight of the past; the newest observation counts 20 %

    /** Stats are "pctLong:pctLonger:count" for hours 0..23 joined by ","; missing or broken parts count as "no data". */
    private static double[][] parse(String s) {
        double[][] r = new double[3][HOURS];
        if (s == null || s.isEmpty()) return r;
        String[] p = s.split(",");
        for (int i = 0; i < HOURS && i < p.length; i++) {
            String[] x = p[i].split(":");
            if (x.length != 3) continue;
            try { for (int k = 0; k < 3; k++) r[k][i] = Double.parseDouble(x[k]); } catch (NumberFormatException e) { r[0][i] = r[1][i] = r[2][i] = 0; }
        }
        return r;
    }

    /** The stats after one more observation: the screen was off for gapMinutes, starting in this hour (0..23). */
    public static String learn(String stats, int hour, long gapMinutes) {
        if (hour < 0 || hour >= HOURS || gapMinutes < 0) return stats == null ? "" : stats;
        double[][] r = parse(stats);
        double a = gapMinutes >= LONG_MIN ? 100 : 0, b = gapMinutes >= LONGER_MIN ? 100 : 0;
        boolean first = r[2][hour] < 1;
        r[0][hour] = first ? a : r[0][hour] * KEEP + a * (1 - KEEP);
        r[1][hour] = first ? b : r[1][hour] * KEEP + b * (1 - KEEP);
        r[2][hour] = Math.min(SAMPLE_CAP, r[2][hour] + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HOURS; i++) sb.append(i > 0 ? "," : "").append(Math.round(r[0][i])).append(':').append(Math.round(r[1][i])).append(':').append((int) r[2][i]);
        return sb.toString();
    }

    /** The level for a screen-off that starts in this hour; 0 while there is too little data. */
    public static int level(String stats, int hour) {
        if (hour < 0 || hour >= HOURS) return 0;
        double[][] r = parse(stats);
        if (r[2][hour] < MIN_SAMPLES) return 0;
        return r[1][hour] >= TRUST_PCT ? 2 : r[0][hour] >= TRUST_PCT ? 1 : 0;
    }

    /** Mode as chosen in the app (0 Samsung's, 1 Smart, 2 Sooner, 3 Soonest) -> level to apply. */
    public static int forMode(int mode, String stats, int hour) {
        return mode == 1 ? level(stats, hour) : mode == 2 ? 1 : mode == 3 ? 2 : 0;
    }

    /** Plain words for the level now in force. */
    public static String describe(int level) {
        return level == 1 ? "Sooner: deep sleep after about 10 minutes" : level == 2 ? "Soonest: deep sleep after about 3 minutes" : "Samsung's own timing (about 35 minutes)";
    }
}
