// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import de.robv.android.xposed.XposedBridge;

/** Logs to the Vector/LSPosed log and logcat (tag SBMods). Never throws. */
final class Log {
    static void i(String m) { out("I", m); }
    static void w(String m) { out("W", m); }

    private static void out(String lvl, String m) {
        try { XposedBridge.log("[SBMods] " + m); } catch (Throwable ignored) {}
        try {
            if ("W".equals(lvl)) android.util.Log.w("SBMods", m);
            else android.util.Log.i("SBMods", m);
        } catch (Throwable ignored) {}
    }

    private Log() {}
}
