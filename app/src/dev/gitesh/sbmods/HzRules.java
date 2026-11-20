// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.provider.Settings;

/**
 * "Would this top screen speed really apply now?" The owner's automatic caps (while power saving is on, under a low battery)
 * win over the speed picked by hand, so a pick above a cap in force asks first. Read only when a speed is picked: no timer.
 */
public final class HzRules {
    private HzRules() {}

    /** A cap in force that is lower than the wanted speed. */
    public static final class Block {
        public int cap;              // the speed the screen is held at
        public boolean psm, low;     // which rule(s) hold it below the wanted speed
        /** "Power saving is on" / "The battery is low" / both. */
        public String why() { return psm && low ? "Power saving is on and the battery is low" : psm ? "Power saving is on" : "The battery is low"; }
        /** One line for the question. */
        public String question(int want) {
            return why() + ", and your own rule holds the screen at up to " + cap + " then. Change to " + want + " anyway? "
                    + "Yes switches that rule off, so " + want + " really applies.";
        }
    }

    /** Pure rule: want / caps in times a second (cap 0 = none); null when nothing holds `want` back. */
    public static Block check(int want, boolean psmOn, int psmCap, boolean lowNow, int lowCap) {
        Block b = new Block();
        b.cap = want;
        if (psmOn && psmCap > 0 && psmCap < want) { b.psm = true; b.cap = Math.min(b.cap, psmCap); }
        if (lowNow && lowCap > 0 && lowCap < want) { b.low = true; b.cap = Math.min(b.cap, lowCap); }
        return b.psm || b.low ? b : null;
    }

    /** The same, read from the phone. `cap` as stored: 0 = no cap (120), else 96 or 60. */
    public static Block check(Context c, int cap) {
        try {
            int want = cap > 0 ? cap : 120;
            boolean psm = g(c, "low_power", 0) == 1;
            int lowCap = g(c, "sbmods_hz_low", 0);
            boolean lowNow = false;
            if (lowCap > 0) {
                Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (i != null) {
                    int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1), l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100), s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
                    lowNow = !charging && s > 0 && l * 100 / s <= g(c, "sbmods_hz_low_at", 20);
                }
            }
            return check(want, psm, g(c, "sbmods_hz_psm", 0), lowNow, lowCap);
        } catch (Throwable t) { return null; }
    }

    private static int g(Context c, String k, int d) {
        try { return Settings.Global.getInt(c.getContentResolver(), k, d); } catch (Throwable t) { return d; }
    }
}
