// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.ui;

import android.content.Context;
import android.provider.Settings;

import dev.gitesh.sbmods.Helper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The self-check: looks at a plain snapshot of facts (Facts) and says what is wrong in one plain sentence each, with one fix.
 * evaluate() has no views and no I/O. gather() collects the facts; it runs only while the settings app is open
 * (on open, on resume at most every 30 s, on "Check again") and never on the main thread (it uses root).
 */
final class SelfCheck {
    static final int NOTICE = 1, PROBLEM = 2;
    // fix ids
    static final String RESTART_UI = "restartUi", RESET_GUARD = "resetGuard", START_HELPER = "startHelper",
            FULL_SPEED = "fullSpeed", CLEAR_CMD = "clearCmd", VULKAN_ON = "vulkanOn";

    /** Existing wordings, shared with the status dialog. */
    static final String MOD_STOPPED = "The mod stopped itself after repeated errors. Restarting the status bar starts it again.";
    static final String MOD_SWITCHED_OFF = "The mod switched itself off because the status bar crashed three times right after loading it. "
            + "Turn the mod back on, then restart the status bar.";

    static final class Finding {
        final int severity; final String text, fix, fixLabel;
        Finding(int severity, String text, String fix, String fixLabel) { this.severity = severity; this.text = text; this.fix = fix; this.fixLabel = fixLabel; }
    }

    /** Everything the rules need. -1 / null = unknown. */
    static final class Facts {
        long now;                       // seconds since 1970
        int boot = -1;                  // Settings.Global.BOOT_COUNT now
        long aliveBoot = -1;            // sbmods_alive_boot: the boot in which the status bar mod loaded
        boolean modReplied, pingDone;   // the status bar answered the last ping / the ping has finished (answered or timed out)
        boolean modTripped, modDisabled;// the mod's own "switched off" / "stopped itself" flags from its reply
        boolean rootOk;                 // `su -c id -u` printed 0
        boolean helperRunning;          // only meaningful when rootOk
        boolean helperOutdatedFailed;   // helper was from an older version and the automatic restart did not work
        boolean atBoot, smartOn;
        String bootResult;              // contents of files/boot_result.txt, null = missing
        int cpuPct, gpuPct, cool; String cores; long until;
        String gfx, cmd; int gfxKeep;
        String modMisses, launcherMisses;   // lookups of Samsung's that failed, one "what|feature" per line (status bar part, launcher part)
    }

    static List<Finding> evaluate(Facts f) {
        List<Finding> out = new ArrayList<>();
        boolean notAlive = f.boot >= 0 && f.aliveBoot != f.boot && !f.modReplied && f.pingDone;
        if (f.modTripped) out.add(new Finding(PROBLEM, MOD_SWITCHED_OFF, RESET_GUARD, "Turn on and restart"));
        else if (f.modDisabled) out.add(new Finding(PROBLEM, MOD_STOPPED, RESTART_UI, "Restart the status bar"));
        else if (notAlive) out.add(new Finding(PROBLEM, "The status bar part is not running. Restart the status bar.", RESTART_UI, "Restart the status bar"));
        // (the mod rules come first: they are the more serious ones)
        for (String list : new String[]{f.modMisses, f.launcherMisses}) {   // no root needed for these
            if (list == null) continue;
            for (String line : list.split("\n")) {
                int bar = line.lastIndexOf('|');
                if (bar <= 0 || bar == line.length() - 1) continue;
                out.add(new Finding(NOTICE, "Samsung changed something: the mod could not find '" + line.substring(0, bar) + "', so " + line.substring(bar + 1) + " may not work.", null, null));
            }
        }
        if (!f.rootOk) {
            out.add(new Finding(PROBLEM, "This app was not given root access, so the helper can't start.", START_HELPER, "Try again"));
            return out;   // every rule below needs root to check or to fix
        }
        boolean needed = f.atBoot || f.smartOn;
        boolean bootMissed = false; String bootText = null;
        if (f.atBoot && !f.helperRunning) {
            int b = -3; String outcome = "";
            if (f.bootResult != null) {
                String[] p = f.bootResult.trim().split("\\s+", 3);
                if (p.length >= 2 && p[1].startsWith("boot=")) {
                    try { b = Integer.parseInt(p[1].substring(5)); } catch (NumberFormatException ignored) {}
                    outcome = p.length > 2 ? p[2] : "";
                }
            }
            if (b != f.boot) {
                bootMissed = true;
                bootText = "The phone did not tell this app that it had started, so the helper was not started automatically. Opening this app once after a restart fixes it for now.";
            } else if (outcome.startsWith("start failed")) {
                bootMissed = true; bootText = "When the phone started, the helper could not be started.";
            } else if (outcome.startsWith("error")) {
                bootMissed = true; bootText = "Something went wrong when the phone tried to start the helper.";
            } else if (outcome.startsWith("mod off")) {
                bootMissed = true; bootText = "When the phone started, the status bar part was not ready, so the helper was not started.";
            }
        }
        if (!f.helperRunning && needed && !bootMissed)
            out.add(new Finding(PROBLEM, "The helper is not running.", START_HELPER, "Start it"));
        if (bootMissed) out.add(new Finding(PROBLEM, bootText, START_HELPER, "Start the helper"));
        if (f.helperOutdatedFailed)
            out.add(new Finding(NOTICE, "The helper is from an older version of this app and could not be restarted.", START_HELPER, "Restart it"));
        boolean limitOn = f.cpuPct > 0 || f.gpuPct > 0 || f.cool == 1 || (f.cores != null && f.cores.matches("\\d,\\d,\\d"));
        if (limitOn && (!f.helperRunning || (f.until > 0 && f.now > f.until + 120)))
            out.add(new Finding(PROBLEM, "A speed limit is stuck on.", FULL_SPEED, "Back to full speed"));
        if (!f.helperRunning && f.cmd != null && !f.cmd.isEmpty() && !f.cmd.equals("none") && !f.cmd.equals("0") && !f.cmd.equals("null"))
            out.add(new Finding(NOTICE, "A request to the helper has been waiting, and the helper is not running.", CLEAR_CMD, "Clear it"));
        if (f.helperRunning && f.gfxKeep == 1 && !"vulkan".equals(f.gfx))
            out.add(new Finding(NOTICE, "You chose to keep Vulkan, but it is off right now.", VULKAN_ON, "Turn it on"));
        return out;
    }

    /** Collects the facts. Background thread only. Costs 1 root call (no root), 2 (helper off) or 3 (helper on); after an app update +3 more for the restart. */
    static Facts gather(Context c, boolean smartOn) {
        Facts f = new Facts();
        android.content.ContentResolver cr = c.getContentResolver();
        f.now = System.currentTimeMillis() / 1000;
        f.boot = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1);
        f.aliveBoot = Settings.Global.getLong(cr, "sbmods_alive_boot", -1);
        f.atBoot = Helper.atBoot(c);
        f.smartOn = smartOn;
        try (BufferedReader r = new BufferedReader(new FileReader(new File(c.getFilesDir(), "boot_result.txt")))) { f.bootResult = r.readLine(); } catch (Throwable ignored) {}
        f.cpuPct = Settings.Global.getInt(cr, "sbmods_cpu_pct", 0);
        f.gpuPct = Settings.Global.getInt(cr, "sbmods_gpu_pct", 0);
        f.cool = Settings.Global.getInt(cr, "sbmods_cool", 0);
        f.cores = Settings.Global.getString(cr, "sbmods_cores");
        f.until = Settings.Global.getLong(cr, "sbmods_until", 0);
        f.gfx = Settings.Global.getString(cr, "sbmods_gfx");
        f.gfxKeep = Settings.Global.getInt(cr, "sbmods_gfx_keep", 0);
        f.cmd = Settings.Global.getString(cr, "sbmods_cmd");
        f.launcherMisses = Settings.Global.getString(cr, "sbmods_miss_launcher");
        f.rootOk = Helper.rootGranted();
        if (f.rootOk) {
            f.helperRunning = Helper.isRunning();
            if (f.helperRunning && !Helper.isCurrent(c)) {   // app was updated: run the new helper (as the Helper card always did)
                boolean ok = Helper.start(c) && Helper.isCurrent(c);
                f.helperRunning = Helper.isRunning();
                f.helperOutdatedFailed = !ok && f.helperRunning;
            }
        }
        return f;
    }

    private SelfCheck() {}
}
