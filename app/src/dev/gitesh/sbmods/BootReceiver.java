// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the root helper after the phone has finished starting, only if the user switched "Start it when the phone starts" on AND the status bar mod is on. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("helperAtBoot", false)) return;
        final Context app = ctx.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                // Only when the status bar mod is on: it stamps global setting sbmods_alive when it loads in SystemUI (and clears it if it
                // switches itself off). A stamp older than this boot, or none, means the mod is off: leave everything alone.
                // compare boot counts, not clock times: the clock can jump right after boot (network time)
                android.content.ContentResolver cr = app.getContentResolver();
                int boot = android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.BOOT_COUNT, -2);
                Thread.sleep(3000);   // give SystemUI time to load and stamp
                if (android.provider.Settings.Global.getLong(cr, "sbmods_alive_boot", -1) != boot) return;
                Helper.start(app);
            } catch (Throwable ignored) {
            } finally {
                pr.finish();
            }
        }).start();
    }
}
