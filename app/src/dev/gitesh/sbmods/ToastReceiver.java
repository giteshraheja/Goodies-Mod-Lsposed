// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Shows a short toast, or a "3, 2, 1" countdown, sent from the shell:
 *   am broadcast -n dev.gitesh.sbmods/.ToastReceiver --es t "Opening Overview" --ei count 3
 */
public final class ToastReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        final Context app = c.getApplicationContext();
        final String text = i.getStringExtra("t");
        final int count = Math.max(0, Math.min(5, i.getIntExtra("count", 0)));
        final PendingResult pr = goAsync();
        Handler h = new Handler(Looper.getMainLooper());
        for (int n = count; n >= 0; n--) {
            final int at = n;
            h.postDelayed(() -> {
                String msg = at > 0 ? (text != null ? text + " in " : "") + at + "…" : text;
                if (msg != null) Toast.makeText(app, msg, Toast.LENGTH_SHORT).show();
                if (at == 0) pr.finish();
            }, (count - n) * 1000L);
        }
    }
}
