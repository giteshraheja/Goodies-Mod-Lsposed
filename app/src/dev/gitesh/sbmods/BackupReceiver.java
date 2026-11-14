// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Runs the daily backup when the daily alarm fires. */
public final class BackupReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                SharedPreferences prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
                Backup.runDaily(app, prefs);
            } catch (Throwable ignored) {
            } finally {
                pr.finish();
            }
        }).start();
    }
}
