// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.content.Context;
import android.os.SystemClock;
import android.widget.Toast;

import java.util.ArrayDeque;

/**
 * The root helper reads ONE pending request at a time (sbmods_cmd), so the panel's requests (battery report, busiest apps,
 * stop an app) wait in a small line and are handed over at least 1.5 s apart, and never while a clean-up is still running.
 */
final class PanelCmd {
    private PanelCmd() {}

    private static final long GAP = 1500;
    private static final class Job { final String cmd; final Runnable sent; Job(String c, Runnable s) { cmd = c; sent = s; } }
    private static final ArrayDeque<Job> queue = new ArrayDeque<>();
    private static long lastSent;
    private static boolean cleaning, pumping;

    /** Queue a request. The same request already waiting is not added twice. onSent (may be null) runs right after it is handed over. */
    static void post(Context c, String cmd, Runnable onSent) {
        for (Job j : queue) if (j.cmd.equals(cmd)) return;
        queue.add(new Job(cmd, onSent));
        pump(c);
    }

    private static void pump(Context c) {
        if (pumping || queue.isEmpty()) return;
        long wait = waitMs();
        if (cleaning) wait = Math.max(wait, 1000);
        pumping = true;
        RecentsChip.main.postDelayed(() -> {
            pumping = false;
            try {
                if (queue.isEmpty()) return;
                if (cleaning || waitMs() > 0) { pump(c); return; }
                Job j = queue.poll();
                lastSent = SystemClock.uptimeMillis();
                if (j.cmd.startsWith("stop:")) Global.gput(c, "sbmods_result", "");   // so an old answer is never mistaken for this one
                Global.gput(c, "sbmods_cmd", j.cmd);
                if (j.sent != null) j.sent.run();
                pump(c);
            } catch (Throwable t) { Log.w("panel cmd: " + t); }
        }, wait);
    }

    /** Milliseconds still to wait before anything may be sent. */
    static long waitMs() { return Math.max(0, lastSent + GAP - SystemClock.uptimeMillis()); }

    /** A clean-up (close apps / deep clean) starts or ends: it owns the helper meanwhile. */
    static void cleaning(boolean on) { cleaning = on; if (on) lastSent = SystemClock.uptimeMillis(); }

    /** The panel closed: whatever still waits is dropped. */
    static void clear() { queue.clear(); }

    /** Stop one app, then show the helper's answer as a message. */
    static void stop(Context c, String pkg) {
        if (!pkg.matches("[A-Za-z0-9_.]+")) return;
        post(c, "stop:" + pkg, () -> {
            final long until = SystemClock.uptimeMillis() + 10000;
            RecentsChip.main.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        String r = Global.gstr(c, "sbmods_result");
                        if (r != null && !r.isEmpty()) { Toast.makeText(c, r, Toast.LENGTH_LONG).show(); return; }
                        if (SystemClock.uptimeMillis() < until) { RecentsChip.main.postDelayed(this, 400); return; }
                        if (("stop:" + pkg).equals(Global.gstr(c, "sbmods_cmd"))) Global.gput(c, "sbmods_cmd", "none");   // withdraw it so it never runs later by surprise
                        Toast.makeText(c, "No answer from the helper - is it running?", Toast.LENGTH_LONG).show();
                    } catch (Throwable t) { Log.w("stop poll: " + t); }
                }
            }, 1200);
        });
    }
}
