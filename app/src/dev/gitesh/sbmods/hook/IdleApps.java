// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * "Running but not opened for days": runs only when the Performance panel's Insights card is built (never in the background).
 * One usage lookup (last 30 days) is kept for 10 minutes; the running-apps check is repeated each time (cheap). Any failure = nothing.
 */
final class IdleApps {
    private IdleApps() {}

    private static final long KEEP_MS = 10 * 60_000L;
    private static Map<String, Long> lastUsed;   // pkg -> lastTimeUsed (ms)
    private static long at = -KEEP_MS;

    /** {package, days} of the longest-idle running third-party app that may be stopped, else null. */
    static String[] find(Activity a, Predicate<String> ok) {
        try {
            long now = System.currentTimeMillis();
            if (lastUsed == null || SystemClock.uptimeMillis() - at >= KEEP_MS) {
                UsageStatsManager um = a.getSystemService(UsageStatsManager.class);
                Map<String, UsageStats> m = um == null ? null : um.queryAndAggregateUsageStats(now - Insights.IDLE_WINDOW_DAYS * 86_400_000L, now);
                if (m == null) return null;
                Map<String, Long> c = new HashMap<>();
                for (Map.Entry<String, UsageStats> e : m.entrySet()) c.put(e.getKey(), e.getValue().getLastTimeUsed());
                lastUsed = c; at = SystemClock.uptimeMillis();
            }
            java.util.List<ActivityManager.RunningAppProcessInfo> run = a.getSystemService(ActivityManager.class).getRunningAppProcesses();
            if (run == null) return null;
            PackageManager pm = a.getPackageManager();
            String best = null; int bestDays = -1;
            for (ActivityManager.RunningAppProcessInfo i : run) {
                if (i.pkgList == null) continue;
                for (String p : i.pkgList) {
                    Long lu = lastUsed.get(p);
                    int days = Insights.idleDays(lu == null ? 0 : lu, now);
                    if (days < Insights.IDLE_MIN_DAYS || days <= bestDays || !ok.test(p)) continue;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                        if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                        if (pm.getLaunchIntentForPackage(p) == null) continue;
                        if (now - pm.getPackageInfo(p, 0).firstInstallTime < Insights.IDLE_MIN_DAYS * 86_400_000L) continue;   // just installed: never opened is normal
                    } catch (Throwable t) { continue; }
                    best = p; bestDays = days;
                }
            }
            return best == null ? null : new String[]{best, String.valueOf(bestDays)};
        } catch (Throwable t) { Log.w("idle apps: " + t); return null; }
    }
}
