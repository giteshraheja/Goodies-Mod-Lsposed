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
                long bootAt = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
                long alive = android.provider.Settings.Global.getLong(app.getContentResolver(), "sbmods_alive", 0);
                if (alive < bootAt) return;
                Thread.sleep(3000);
                Helper.start(app);
            } catch (Throwable ignored) {
            } finally {
                pr.finish();
            }
        }).start();
    }
}
