// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import dev.gitesh.sbmods.Config;

/**
 * Hands a one-off job (close apps, deep clean, battery report, busiest apps, stop an app, battery health, renderer) to the
 * PulseBar app, which runs it as root and goes idle again. The answer arrives in the same settings as always
 * (sbmods_result, sbmods_batt, sbmods_top, sbmods_appgfx, sbmods_health, sbmods_gfx), so the callers keep reading those.
 *
 * Fallback: if the app does not take the request (not installed, switched off, put to sleep by the phone), the request goes
 * the old way, into sbmods_cmd, for the helper script. Only ever one of the two gets it, so they can't both answer.
 */
final class Jobs {
    private Jobs() {}

    private static final Handler main = new Handler(Looper.getMainLooper());

    static void send(Context c, String cmd) {
        try {
            Intent i = new Intent(Config.ACTION_JOB)
                    .setComponent(new ComponentName(Config.PKG, Config.PKG + ".JobReceiver"))
                    .putExtra(Config.EXTRA_JOB, cmd)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            c.sendOrderedBroadcast(i, null, new BroadcastReceiver() {
                @Override public void onReceive(Context x, Intent in) {
                    if (getResultCode() != Config.JOB_TAKEN) old(c, cmd);
                }
            }, main, 0, null, null);
        } catch (Throwable t) {
            Log.w("job " + cmd + ": " + t);
            old(c, cmd);
        }
    }

    private static void old(Context c, String cmd) { Global.gput(c, "sbmods_cmd", cmd); }

    /** True while a request is waiting in the helper script's inbox (only ever the case after a fallback). */
    static boolean pending(Context c) {
        String v = Global.gstr(c, "sbmods_cmd");
        return v != null && !v.isEmpty() && !v.equals("none") && !v.equals("0") && !v.equals("null");
    }
}
