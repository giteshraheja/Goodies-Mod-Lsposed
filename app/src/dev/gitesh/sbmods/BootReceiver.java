// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Starts the root helper after the phone has finished starting, only if the user switched "Start it when the phone starts" on AND the status bar mod is on.
 * "On" is read from a marker file in the app's files folder (the settings file may be a stale copy at boot), with the settings value as a fallback.
 * It waits up to 20 s for the status bar mod to stamp itself, tries to start the helper twice, and writes the outcome to files/boot_result.txt.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean enabled = Helper.atBoot(ctx);
        if (!enabled) {
            try {
                enabled = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("helperAtBoot", false);
            } catch (Throwable ignored) {
            }
        }
        if (!enabled) return;
        final Context app = ctx.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            int boot = -2;
            String outcome;
            try {
                // Only when the status bar mod is on: it stamps global setting sbmods_alive when it loads in SystemUI (and clears it if it
                // switches itself off). A stamp older than this boot, or none, means the mod is off: leave everything alone.
                // compare boot counts, not clock times: the clock can jump right after boot (network time)
                android.content.ContentResolver cr = app.getContentResolver();
                boot = android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.BOOT_COUNT, -2);
                boolean alive = false;
                for (int waited = 0; waited <= 20000; waited += 2000) {   // give SystemUI time to load and stamp
                    if (android.provider.Settings.Global.getLong(cr, "sbmods_alive_boot", -1) == boot) { alive = true; break; }
                    if (waited < 20000) Thread.sleep(2000);
                }
                if (!alive) {
                    outcome = "mod off";
                } else {
                    boolean ok = Helper.start(app);
                    if (!ok) {
                        Thread.sleep(5000);
                        ok = Helper.start(app);
                    }
                    outcome = ok ? "started" : "start failed";
                }
            } catch (Throwable t) {
                outcome = "error: " + t;
            }
            record(app, boot, outcome);   // never throws; written before finish() so the process can't be frozen first
            pr.finish();
        }).start();
    }

    private static void record(Context app, int boot, String outcome) {
        try (FileOutputStream o = new FileOutputStream(new File(app.getFilesDir(), "boot_result.txt"), false)) {
            o.write((System.currentTimeMillis() + " boot=" + boot + " " + outcome + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }
}
