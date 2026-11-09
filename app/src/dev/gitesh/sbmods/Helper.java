// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * The small root helper behind the Overview screen chips (close apps, deep clean, CPU / GPU speed limits).
 * The Overview screen is an ordinary app process and cannot do these things, so the chips send a request
 * through global settings and this helper (a shell script running as root) carries it out.
 * Everything here needs this app to be allowed to use root; nothing runs at boot unless the user switches that on.
 */
public final class Helper {
    private static final String DIR = "/data/local/tmp";

    private Helper() {}

    /** Runs a command as root; returns its exit code, or -1 if root is not available / it timed out. Never call on the main thread. */
    static int su(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1; }
            return p.exitValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Runs a command as root and returns its trimmed stdout, or null if root is not available / it failed / it timed out. Never call on the main thread. */
    static String suOut(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.getOutputStream().close();
            final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            Thread t = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] b = new byte[1024];
                    int n;
                    while ((n = in.read(b)) > 0) synchronized (buf) { buf.write(b, 0, n); }
                } catch (Throwable ignored) {}
            });
            t.start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            t.join(2000);
            if (p.exitValue() != 0) return null;
            synchronized (buf) { return buf.toString("UTF-8").trim(); }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Reads a secure setting as root. Returns null if root is not available; "" if the setting is not set. Never call on the main thread. */
    public static String readSecure(String key) {
        String v = suOut("settings get secure " + key.replace("'", ""));
        if (v == null) return null;
        return v.equals("null") ? "" : v;
    }

    /** Reads a secure setting as root; "" if it is not set or root is not available. Never call on the main thread. */
    public static String getSecure(String key) {
        String v = readSecure(key);
        return v == null ? "" : v;
    }

    /** Writes a secure setting as root. An empty value removes the setting. Never call on the main thread. */
    public static boolean putSecure(String key, String value) {
        key = key.replace("'", "");
        String v = value == null ? "" : value.replace("'", "");
        if (v.isEmpty()) return su("settings delete secure " + key) == 0;
        return su("settings put secure " + key + " '" + v + "'") == 0;
    }

    private static boolean copyAsset(Context c, String name) {
        try (InputStream in = c.getAssets().open(name); FileOutputStream out = new FileOutputStream(new File(c.getCacheDir(), name))) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Installs the scripts and starts the helper. Returns true if it is running afterwards. */
    public static boolean start(Context c) {
        if (!copyAsset(c, "sbmods_cpud.sh") || !copyAsset(c, "sbmods_restore.sh")) return false;
        String cache = c.getCacheDir().getAbsolutePath();
        int rc = su("cp " + cache + "/sbmods_cpud.sh " + DIR + "/sbmods_cpud.sh && cp " + cache + "/sbmods_restore.sh " + DIR + "/sbmods_restore.sh"
                + " && chmod 755 " + DIR + "/sbmods_cpud.sh " + DIR + "/sbmods_restore.sh"
                + " && { p=$(cat " + DIR + "/sbmods_cpud.pid 2>/dev/null); [ -n \"$p\" ] && grep -q sbmods_cpud /proc/$p/cmdline 2>/dev/null && kill $p; true; }"
                + " && { setsid nohup " + DIR + "/sbmods_cpud.sh >/dev/null 2>&1 < /dev/null & }");
        if (rc != 0) return false;
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        return isRunning();
    }

    public static boolean isRunning() {
        // the PID file survives a reboot: only count it if that process really is our script
        return su("p=$(cat " + DIR + "/sbmods_cpud.pid 2>/dev/null); [ -n \"$p\" ] && grep -q sbmods_cpud /proc/$p/cmdline") == 0;
    }

    /** Writes global settings (the helper's controls) as root, since this app may not write them itself. Never call on the main thread. */
    public static boolean putGlobal(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append(" && ");
            sb.append("settings put global ").append(kv[i]).append(" '").append(kv[i + 1].replace("'", "")).append("'");
        }
        return su(sb.toString()) == 0;
    }

    /** True if the running helper is this app version's script (after an update the old one keeps running until restarted). */
    public static boolean isCurrent(Context c) {
        if (!copyAsset(c, "sbmods_cpud.sh")) return true;
        return su("cmp -s " + c.getCacheDir().getAbsolutePath() + "/sbmods_cpud.sh " + DIR + "/sbmods_cpud.sh") == 0;
    }

    /** Stops the helper and puts every CPU / GPU speed and the power saving switch back to normal. */
    public static boolean stopAndRestore() {
        return su("[ -x " + DIR + "/sbmods_restore.sh ] && " + DIR + "/sbmods_restore.sh") == 0;
    }
}
