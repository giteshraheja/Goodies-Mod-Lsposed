// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.content.Context;
import android.provider.Settings;

/** Bridge to the Settings.Global keys shared with the root helper. */
final class Global {
    private Global() {}

    static boolean gput(Context c, String key, String val) {
        try { return Settings.Global.putString(c.getContentResolver(), key, val); } catch (Throwable t) { Log.w("setting " + key + ": " + t); return false; }
    }

    static int gint(Context c, String key, int def) {
        try { return Settings.Global.getInt(c.getContentResolver(), key, def); } catch (Throwable t) { return def; }
    }

    static String gstr(Context c, String key) {
        try { return Settings.Global.getString(c.getContentResolver(), key); } catch (Throwable t) { return null; }
    }

    /** Write only when the value really differs (every write wakes the helper and everyone watching the setting). */
    static boolean gset(Context c, String key, String val) {
        String cur = gstr(c, key);
        if (val.equals(cur)) return true;
        return gput(c, key, val);
    }

    /** Settings.Secure (the phone's own switches, such as Samsung's refresh_rate_mode); same guarded style. */
    static int sint(Context c, String key, int def) {
        try { return Settings.Secure.getInt(c.getContentResolver(), key, def); } catch (Throwable t) { return def; }
    }

    static boolean sput(Context c, String key, String val) {
        try { return Settings.Secure.putString(c.getContentResolver(), key, val); } catch (Throwable t) { Log.w("secure " + key + ": " + t); return false; }
    }

    /** Top screen speed we ask for: 60 or 96, 0 = no cap (Android's own system setting, which the launcher may write). */
    static int hzCap(Context c) {
        try {
            float f = Settings.System.getFloat(c.getContentResolver(), "peak_refresh_rate", 0f);
            return f >= 119f || f <= 0f ? 0 : Math.round(f);
        } catch (Throwable t) { return 0; }
    }

    static boolean hzSet(Context c, int cap) {
        try {
            android.content.ContentResolver cr = c.getContentResolver();
            if (cap == 60 || cap == 96) Settings.System.putString(cr, "peak_refresh_rate", cap + ".0");
            else { cap = 0; if (cr.delete(Settings.System.getUriFor("peak_refresh_rate"), null, null) < 1) Settings.System.putString(cr, "peak_refresh_rate", null); }
            gset(c, "sbmods_hz_max", String.valueOf(cap));   // marks the cap as ours, for "Undo everything"
            return true;
        } catch (Throwable t) { Log.w("hz cap: " + t); return false; }
    }

    /** A clean-up (close apps / deep clean) was started by the owner: Smart learns which app is opened next. One settings write per tap. */
    static void markClean(Context c) { if (gint(c, "sbmods_smart", 1) != 0) gput(c, "sbmods_clean_at", String.valueOf(System.currentTimeMillis() / 1000)); }
}
