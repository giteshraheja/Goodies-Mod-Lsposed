// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

import dev.gitesh.sbmods.SleepPlan;

/**
 * "Sleep sooner" in the status bar process. Runs only when the screen turns on or off (the receivers that already exist): no timer,
 * no polling. On screen-off it notes the time; on screen-on it learns how long the screen was off, works out the doze timing for the
 * next screen-off and, only if that differs from what is in force, asks the app for one root job (sleep:N). Android's own doze does the rest.
 */
final class SleepSmart {
    private SleepSmart() {}

    private static final long RETRY_MS = 5 * 60_000L;   // a job that did not change anything (no root...) is not asked again sooner than this

    static void screenOff(Context ctx, SharedPreferences prefs) {
        try {
            if (Global.gint(ctx, "sbmods_sleep_mode", 0) == 1) prefs.edit().putLong("sleep_off", System.currentTimeMillis()).apply();
        } catch (Throwable t) { Log.w("sleep off: " + t); }
    }

    static void screenOn(Context ctx, SharedPreferences prefs) {
        try {
            int mode = Global.gint(ctx, "sbmods_sleep_mode", 0);
            int have = Global.gint(ctx, "sbmods_sleep", 0);
            if (mode == 0 && have == 0) return;   // off and nothing of ours in force: nothing to do at all
            long now = System.currentTimeMillis();
            String stats = prefs.getString("sleep_stats", "");
            if (mode == 1) {
                long off = prefs.getLong("sleep_off", 0);
                if (off > 0) {
                    long gap = (now - off) / 60_000L;
                    if (gap >= 0 && gap < 3 * 24 * 60) {   // (a clock change can make nonsense gaps)
                        Calendar c = Calendar.getInstance();
                        c.setTimeInMillis(off);
                        stats = SleepPlan.learn(stats, c.get(Calendar.HOUR_OF_DAY), gap);
                    }
                    prefs.edit().putString("sleep_stats", stats).putLong("sleep_off", 0).apply();
                }
            }
            int want = SleepPlan.forMode(mode, stats, Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
            if (want == have) return;
            if (now - prefs.getLong("sleep_try", 0) < RETRY_MS) return;
            prefs.edit().putLong("sleep_try", now).apply();
            Jobs.send(ctx, "sleep:" + want);
            Log.i("sleep: level " + have + " -> " + want + " (mode " + mode + ")");
        } catch (Throwable t) { Log.w("sleep on: " + t); }
    }
}
