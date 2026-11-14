// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The panel's pure number work (no Android classes, so it is easy to test): modes, battery drain, power draw, limit time. */
final class PanelMath {
    private PanelMath() {}

    // ---- modes: presets of the existing controls; the selected one is always derived from the current values
    static final String[] MODE_NAMES = {"Full", "Balanced", "Cool", "Saver"};
    static final int[] MODE_CPU = {0, 85, 70, 55};
    static final int[] MODE_GPU = {0, 85, 70, 55};
    static final String[] MODE_CORES = {"0", "0", "3,3,1", "3,2,1"};
    static final String[] MODE_TEXT = {
        "Full: no limits - fastest, uses the most power",
        "Balanced: a little less top speed, hardly noticeable - saves some power",
        "Cool: lower top speed and one core resting - for long sessions",
        "Saver: much lower speed and two cores resting - longest battery life"};

    /** "", null, "0" and anything malformed mean no resting; otherwise the cleaned "S,M,P". */
    static String normCores(String s) {
        if (s == null) return "0";
        s = s.replace(" ", "");
        if (s.isEmpty() || s.equals("0")) return "0";
        String[] p = s.split(",");
        if (p.length != 3) return "0";
        try { return Integer.parseInt(p[0]) + "," + Integer.parseInt(p[1]) + "," + Integer.parseInt(p[2]); } catch (NumberFormatException e) { return "0"; }
    }

    /** Index into MODE_NAMES that matches the current values, or -1 ("Custom"). Cool-down on = always custom. */
    static int mode(int cpuPct, int gpuPct, String cores, boolean cool) {
        if (cool) return -1;
        if (cpuPct >= 100) cpuPct = 0;   // 100 = "full on purpose", same as none when not cooling
        if (gpuPct >= 100) gpuPct = 0;
        String c = normCores(cores);
        for (int i = 0; i < MODE_NAMES.length; i++)
            if (MODE_CPU[i] == cpuPct && MODE_GPU[i] == gpuPct && MODE_CORES[i].equals(c)) return i;
        return -1;
    }

    /** Mid cores awake (1..4) from the "S,M,P" setting; 4 when nothing rests. */
    static int midAwake(String cores) {
        String c = normCores(cores);
        if (c.equals("0")) return 4;
        int m = Integer.parseInt(c.split(",")[1]);
        return Math.max(1, Math.min(4, m));
    }

    /** What to write for n mid cores awake. */
    static String coresFor(int n) { return n >= 4 ? "0" : "3," + Math.max(1, n) + ",1"; }

    // ---- battery report: left_min|on_sec|off_sec|discharge_mah|off_discharge_mah|capacity_mah
    static long[] parseBatt(String s) {
        if (s == null) return null;
        String[] p = s.trim().split("\\|");
        if (p.length < 6) return null;
        try {
            long[] v = new long[6];
            for (int i = 0; i < 6; i++) v[i] = Long.parseLong(p[i].trim());
            return v;
        } catch (NumberFormatException e) { return null; }
    }

    /** The sleep part of the battery report: fields 7..12 after the six above (light_sleep_sec|deep_sleep_sec|light_mah|deep_mah|awake|woken). */
    static final class Sleep {
        long lightSec = -1, deepSec = -1;
        int lightMah = -1, deepMah = -1;
        final List<String[]> awake = new ArrayList<>();   // {package, seconds}, longest first
        final List<String> woken = new ArrayList<>();     // plain words, most first
    }

    /** null when the answer has no sleep fields (an older helper) or they are unknown. */
    static Sleep parseSleep(String s) {
        if (s == null) return null;
        String[] p = s.trim().split("\\|", -1);
        if (p.length < 12) return null;
        try {
            Sleep r = new Sleep();
            r.lightSec = Long.parseLong(p[6].trim()); r.deepSec = Long.parseLong(p[7].trim());
            r.lightMah = Integer.parseInt(p[8].trim()); r.deepMah = Integer.parseInt(p[9].trim());
            if (r.lightSec < 0 || r.deepSec < 0) return null;
            for (String e : p[10].split(",")) {
                int c = e.lastIndexOf(':');
                if (c > 0) { try { r.awake.add(new String[]{e.substring(0, c).trim(), String.valueOf(Long.parseLong(e.substring(c + 1).trim()))}); } catch (NumberFormatException ignored) {} }
            }
            for (String w : p[11].split(",")) if (!w.trim().isEmpty()) r.woken.add(w.trim());
            return r;
        } catch (NumberFormatException e) { return null; }
    }

    /** Share of the screen-off time spent in light or deep sleep, 0..100; -1 when there is under 10 minutes of data. */
    static int sleepPct(long offSec, Sleep s) {
        if (s == null || offSec < 600) return -1;
        return (int) Math.min(100, Math.round((s.lightSec + s.deepSec) * 100.0 / offSec));
    }

    /** 0 = good (85 % or more), 1 = fair, 2 = poor (under 60 %); -1 = unknown. */
    static int sleepVerdict(int pct) { return pct < 0 ? -1 : pct >= 85 ? 0 : pct >= 60 ? 1 : 2; }

    /** Battery percent per hour used over a period; -1 when there is under 5 minutes of data or a value is unknown. */
    static double drainPerHour(long mah, long sec, long capacityMah) {
        if (mah < 0 || sec < 300 || capacityMah <= 0) return -1;
        return mah * 100.0 / capacityMah / (sec / 3600.0);
    }

    static double screenOnRate(long[] b) {
        if (b[3] < 0 || b[4] < 0) return -1;
        return drainPerHour(Math.max(0, b[3] - b[4]), b[1], b[5]);
    }

    static double screenOffRate(long[] b) { return drainPerHour(b[4], b[2], b[5]); }

    /** 0 = normal, 1 = a bit high, 2 = high; -1 = unknown. */
    static int standbyVerdict(double offRate) { return offRate < 0 ? -1 : offRate < 1 ? 0 : offRate <= 2.5 ? 1 : 2; }

    /** Seconds to whole minutes, rounded (3719 s is 62 min). */
    static long minutes(long sec) { return (Math.max(0, sec) + 30) / 60; }

    /** "6 h 59 min", "1 h", "2 min". */
    static String duration(long minutes) {
        if (minutes < 0) minutes = 0;
        long h = minutes / 60, m = minutes % 60;
        return h == 0 ? m + " min" : m == 0 ? h + " h" : h + " h " + m + " min";
    }

    // ---- power draw
    /** Current in mA from the raw reading: this phone gives mA, but anything above 20000 can only be microamps. Sign kept. */
    static double milliamps(long raw) { return Math.abs(raw) > 20000 ? raw / 1000.0 : raw; }

    /** Watts (always positive) from the raw current reading and the battery voltage in mV. */
    static double watts(long rawCurrent, int millivolts) { return Math.abs(milliamps(rawCurrent)) * millivolts / 1e6; }

    static String wattsText(double w, boolean charging) {
        return (charging ? "+" : "") + (w >= 10 ? String.valueOf(Math.round(w)) : String.format(Locale.US, "%.1f", w)) + " W";
    }

    /** Whole minutes until the limit ends; 0 when none (missing, 0 or already past). */
    static long limitMinutes(long untilSec, long nowSec) {
        if (untilSec <= 0 || untilSec <= nowSec) return 0;
        return Math.max(1, (untilSec - nowSec + 59) / 60);
    }
}
