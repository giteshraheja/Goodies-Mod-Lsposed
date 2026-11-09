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
}
