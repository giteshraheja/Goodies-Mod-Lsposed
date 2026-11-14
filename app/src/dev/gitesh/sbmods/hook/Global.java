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

    /** The owner's chosen top screen speed: 60 or 96, 0 = no cap (the status bar part may lower what is really set, see ScreenSaver). */
    static int hzCap(Context c) { int v = gint(c, "sbmods_hz_max", 0); return v == 60 || v == 96 ? v : 0; }

    static boolean hzSet(Context c, int cap) {
        if (!gset(c, "sbmods_hz_max", String.valueOf(cap == 60 || cap == 96 ? cap : 0))) return false;
        ScreenSaver.apply(c);
        return true;
    }

    /** A clean-up (close apps / deep clean) was started by the owner: Smart learns which app is opened next. One settings write per tap. */
    static void markClean(Context c) { if (gint(c, "sbmods_smart", 1) != 0) gput(c, "sbmods_clean_at", String.valueOf(System.currentTimeMillis() / 1000)); }
}
