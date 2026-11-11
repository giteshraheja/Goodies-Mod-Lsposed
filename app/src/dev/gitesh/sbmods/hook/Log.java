// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import de.robv.android.xposed.XposedBridge;

/** Logs to the Vector/LSPosed log and logcat (tag SBMods). Never throws. */
final class Log {
    static void i(String m) { out("I", m); }
    static void w(String m) { out("W", m); }

    // ---- broken lookups: things of Samsung's the mod needs but could not find (a view id, a class, a hook). In memory only, no timers.
    private static final java.util.LinkedHashSet<String> misses = new java.util.LinkedHashSet<>();
    private static android.content.Context launcherCtx;   // set only in the launcher process: its list is handed to the app through one global setting

    /** Records "could not find `what`, so `feature` may not work" once (at most 20). `feature` is plain words for the owner. Never throws. */
    static synchronized void miss(String what, String feature) {
        try {
            if (misses.size() >= 20 || !misses.add(what + "|" + feature)) return;
            w("missing: " + what + " (" + feature + ")");
            push();
        } catch (Throwable ignored) {}
    }

    /** `miss` when a getIdentifier result is 0. */
    static void missId(int id, String name, String feature) { if (id == 0) miss(name, feature); }

    /** The list, one "what|feature" per line (empty = nothing missing). */
    static synchronized String misses() { return String.join("\n", misses); }

    /** Launcher process: from now on changes of the list are written to the global setting sbmods_miss_launcher (once at first, so an old list is replaced). */
    static synchronized void bindLauncher(android.content.Context c) {
        if (launcherCtx != null) return;
        launcherCtx = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        push();
    }

    private static void push() {
        if (launcherCtx == null) return;
        String s = misses(), cur = Global.gstr(launcherCtx, "sbmods_miss_launcher");
        if (!s.equals(cur == null || cur.equals("null") ? "" : cur)) Global.gput(launcherCtx, "sbmods_miss_launcher", s);
    }

    private static void out(String lvl, String m) {
        try { XposedBridge.log("[SBMods] " + m); } catch (Throwable ignored) {}
        try {
            if ("W".equals(lvl)) android.util.Log.w("SBMods", m);
            else android.util.Log.i("SBMods", m);
        } catch (Throwable ignored) {}
    }

    private Log() {}
}
