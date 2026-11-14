// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * One-off jobs without the always-running helper: the Overview chips / Performance panel send a request (Config.ACTION_JOB),
 * this receiver runs ONE root command for it on a worker thread (assets/sbmods_job.sh), the script writes the usual answer
 * settings (sbmods_result, sbmods_batt, sbmods_top, ...), and then nothing is left running: no loop, no timer, no wakelock.
 *
 * Who may ask: the receiver is protected by WRITE_SECURE_SETTINGS, the same permission a sender needed to write the old
 * request setting (sbmods_cmd), so exactly the same processes (status bar, launcher, shell/root) can ask as before.
 * (This app's own signature permission can't be used in this direction: the senders are Samsung's processes.)
 *
 * Flow: the request is an ordered broadcast; it is answered at once with JOB_TAKEN (so the sender knows not to fall back to the
 * helper script) and handed on as ACTION_JOB_RUN, which keeps this process awake (goAsync) only until the job has ended.
 *
 * The only timed thing here is the Wi-Fi calling check after a switch to Vulkan (~60 s and ~5 min later): a one-shot,
 * non-wakeup alarm that the job script asks for ("ALARM n") - it never wakes a sleeping phone, and none is set otherwise.
 */
public final class JobReceiver extends BroadcastReceiver {
    private static final String SCRIPT = "sbmods_job.sh", LIB = "sbmods_lib.sh";
    private static final Pattern OK = Pattern.compile(
            "ramclean|deepclean|battreport|mem|wlsys|sleep:[012]|top|health|shot|gfx:vulkan|gfx:opengl|gfxcheck|boot"
                    + "|(stop|appgfx):[A-Za-z0-9._]{1,200}|chargelimit:(80|85|90|95|100)");
    private static final Pattern PKG = Pattern.compile("[A-Za-z0-9._]+");

    /** One job at a time, in the order asked; the thread ends by itself 3 s after the last job. */
    private static final ExecutorService one = queue();

    private static ExecutorService queue() {
        ThreadPoolExecutor e = new ThreadPoolExecutor(1, 1, 3, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> new Thread(r, "sbmods-job"));
        e.allowCoreThreadTimeOut(true);
        return e;
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        final String cmd = intent.getStringExtra(Config.EXTRA_JOB);
        if (cmd == null || !OK.matcher(cmd).matches()) return;   // not ours: the result stays "not taken"
        final Context app = ctx.getApplicationContext();
        if (Config.ACTION_JOB.equals(action) && isOrderedBroadcast()) {
            // answer at once, then run it under a second broadcast (the ordered one must not be held open for the length of the job)
            try {
                app.sendBroadcast(new Intent(app, JobReceiver.class).setAction(Config.ACTION_JOB_RUN).putExtra(Config.EXTRA_JOB, cmd));
                setResultCode(Config.JOB_TAKEN);
            } catch (Throwable ignored) {
            }
            return;
        }
        if (!Config.ACTION_JOB.equals(action) && !Config.ACTION_JOB_RUN.equals(action)) return;
        final PendingResult pr = goAsync();
        try {
            one.execute(() -> {
                try { run(app, cmd); } catch (Throwable ignored) {
                } finally { pr.finish(); }
            });
        } catch (Throwable t) {
            pr.finish();
        }
    }

    /**
     * Runs one job as root and waits for it (up to 40 s). Returns false if root is not available or the job failed.
     * Never call on the main thread. Also used by the boot receiver.
     */
    public static boolean run(Context app, String cmd) {
        if (cmd == null || !OK.matcher(cmd).matches()) return false;
        File script = script(app);
        if (script == null) return false;
        String keep = cmd.equals("ramclean") || cmd.equals("deepclean") ? kept(app) : " ";
        String out = Helper.suOut("sh " + script.getAbsolutePath() + " " + cmd + " '" + keep + "'", 40);
        if (out == null) return false;
        for (String line : out.split("\n")) {
            line = line.trim();
            if (!line.startsWith("ALARM ")) continue;
            try { alarm(app, Integer.parseInt(line.substring(6).trim())); } catch (Throwable ignored) {}
        }
        return true;
    }

    /** The job script in the cache folder, copied from the app again after an update (or when the cache was cleared). */
    private static File script(Context c) {
        try {
            File f = new File(c.getCacheDir(), SCRIPT), lib = new File(c.getCacheDir(), LIB);
            long updated = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
            if (f.length() > 0 && lib.length() > 0 && f.lastModified() >= updated && lib.lastModified() >= updated) return f;
            return copy(c, LIB, lib) && copy(c, SCRIPT, f) ? f : null;   // the script sources the shared functions from its own folder
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean copy(Context c, String asset, File to) throws java.io.IOException {
        File tmp = new File(c.getCacheDir(), asset + ".new");
        try (InputStream in = c.getAssets().open(asset); FileOutputStream o = new FileOutputStream(tmp)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        return tmp.renameTo(to);
    }

    /**
     * Apps a clean-up spares, as " a b c " (" " = none): the ones the owner keeps reopening right after a clean-up
     * (global sbmods_keep = "pkg|count|day;...", count >= 2), only while Smart suggestions is on. Same rule as the helper script.
     */
    private static String kept(Context c) {
        StringBuilder sb = new StringBuilder(" ");
        try {
            if (Settings.Global.getInt(c.getContentResolver(), "sbmods_smart", 1) == 0) return " ";
            String raw = Settings.Global.getString(c.getContentResolver(), "sbmods_keep");
            if (raw == null) return " ";
            for (String e : raw.split(";")) {
                String[] p = e.split("\\|");
                if (p.length < 2 || !PKG.matcher(p[0]).matches()) continue;
                try { if (Integer.parseInt(p[1]) >= 2) sb.append(p[0]).append(' '); } catch (NumberFormatException ignored) {}
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** Sets (sec > 0) or drops (0) the one pending Wi-Fi calling check. Non-wakeup and inexact: it costs nothing while the phone sleeps. */
    private static void alarm(Context c, int sec) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1,
                new Intent(c, JobReceiver.class).setAction(Config.ACTION_JOB_RUN).putExtra(Config.EXTRA_JOB, "gfxcheck"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (sec <= 0) am.cancel(pi);
        else am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + Math.min(sec, 600) * 1000L, pi);
    }
}
