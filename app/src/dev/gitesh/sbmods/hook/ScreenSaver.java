// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.provider.Settings;

/**
 * Screen speed caps and "maximum power saving while the screen is off", in the status bar process. Nothing here runs on a timer:
 * it acts when one of its settings changes, when power saving is switched, and when the screen turns on or off (receivers that
 * already exist).
 *
 * Top speed = the lowest of: the owner's choice (sbmods_hz_max), the cap while power saving is on (sbmods_hz_psm) and the cap
 * under a low battery (sbmods_hz_low below sbmods_hz_low_at percent, not while charging). Android's own peak_refresh_rate holds it.
 */
final class ScreenSaver {
    private ScreenSaver() {}

    private static ContentObserver obs;

    static void start(Context c, Handler main) {
        try {
            if (obs != null) return;
            obs = new ContentObserver(main) {
                @Override public void onChange(boolean self) { apply(c); }
            };
            for (String k : new String[]{"low_power", "sbmods_hz_max", "sbmods_hz_psm", "sbmods_hz_low", "sbmods_hz_low_at"})
                c.getContentResolver().registerContentObserver(Settings.Global.getUriFor(k), false, obs);
            apply(c);
        } catch (Throwable t) { Log.w("screen saver start: " + t); }
    }

    private static int batteryPct(Context c, boolean[] charging) {
        try {
            Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return 100;
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging[0] = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            int l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100), s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            return s > 0 ? l * 100 / s : 100;
        } catch (Throwable t) { return 100; }
    }

    /** Works out the top speed that applies now and writes it only if it differs from what is set. */
    static void apply(Context c) {
        try {
            int user = Global.gint(c, "sbmods_hz_max", 0), eff = user > 0 ? user : 120;
            if (Global.gint(c, "low_power", 0) == 1) { int p = Global.gint(c, "sbmods_hz_psm", 0); if (p > 0) eff = Math.min(eff, p); }
            int low = Global.gint(c, "sbmods_hz_low", 0);
            if (low > 0) {
                boolean[] ch = {false};
                if (batteryPct(c, ch) <= Global.gint(c, "sbmods_hz_low_at", 20) && !ch[0]) eff = Math.min(eff, low);
            }
            if (eff >= 120) eff = 0;
            float cur = Settings.System.getFloat(c.getContentResolver(), "peak_refresh_rate", 0f);
            int curCap = cur >= 119f || cur <= 0f ? 0 : Math.round(cur);
            if (curCap == eff) return;
            ContentResolver cr = c.getContentResolver();
            if (eff == 0) { if (cr.delete(Settings.System.getUriFor("peak_refresh_rate"), null, null) < 1) Settings.System.putString(cr, "peak_refresh_rate", null); }
            else Settings.System.putString(cr, "peak_refresh_rate", eff + ".0");
        } catch (Throwable t) { Log.w("hz apply: " + t); }
    }

    /** Screen went off: if the owner wants maximum saving, switch it on. */
    static void screenOff(Context c) {
        if (Global.gint(c, "sbmods_off_max", 0) == 1) saveOn(c);
    }

    /** A gesture: switch maximum saving on (true) or, if it is on, put everything back (false). It also ends when the screen next comes on. */
    static boolean saveNow(Context c) {
        if (Global.gint(c, "sbmods_off_ps", 0) == 1 || Global.gint(c, "sbmods_off_sync", 0) == 1) { screenOn(c); return false; }
        saveOn(c);
        return true;
    }

    /** Power saving on and automatic sync off, noting what was ours so it can be put back exactly. */
    private static void saveOn(Context c) {
        try {
            if (Global.gint(c, "low_power", 0) == 0 && Global.gint(c, "sbmods_off_ps", 0) == 0) {
                Global.gput(c, "sbmods_off_ps", "1");   // noted first: if anything fails later, screen-on still puts it back
                Global.gset(c, "low_power", "1");
            }
            if (ContentResolver.getMasterSyncAutomatically() && Global.gint(c, "sbmods_off_sync", 0) == 0) {
                Global.gput(c, "sbmods_off_sync", "1");
                ContentResolver.setMasterSyncAutomatically(false);
            }
        } catch (Throwable t) { Log.w("max saving on: " + t); }
    }

    /** Screen came on: put back only what we changed (and only if it is still as we left it), then re-check the speed cap. */
    static void screenOn(Context c) {
        try {
            if (Global.gint(c, "sbmods_off_ps", 0) == 1) {
                Global.gput(c, "sbmods_off_ps", "0");
                Global.gset(c, "low_power", "0");
            }
            if (Global.gint(c, "sbmods_off_sync", 0) == 1) {
                Global.gput(c, "sbmods_off_sync", "0");
                ContentResolver.setMasterSyncAutomatically(true);
            }
            apply(c);
        } catch (Throwable t) { Log.w("max saving off: " + t); }
    }
}
