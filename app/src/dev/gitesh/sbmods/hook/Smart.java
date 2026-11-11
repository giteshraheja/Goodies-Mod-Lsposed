// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.gitesh.sbmods.Config;
import dev.gitesh.sbmods.Habits;
import dev.gitesh.sbmods.Ui;

/**
 * "Smart suggestions": when an app comes to the front, ask the root helper (once per app version) whether it draws with Vulkan
 * or OpenGL, say so in a toast, and offer to switch the phone to Vulkan. Everything is event-driven: the only trigger is the
 * task-stack listener (an app came to the front); the waits are one-shot posts. Every entry point starts with the cached
 * `on` check, so with the switch off nothing here runs (the listener is not even registered).
 */
final class Smart {
    private Smart() {}

    private static final int CAP = 300;
    private static final long DELAY_MS = 4000, WAIT_MS = 15000, POPUP_MS = 8000, BLOCK_MS = 10 * 60_000L;
    private static final String K_CHK = "sm_chk", K_ASKDAY = "sm_askday";
    private static final String G_NOASK = "sbmods_smart_noask", G_KEEP = "sbmods_gfx_keep";

    /** Cached master switch (Config.smartOn). */
    static volatile boolean on;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context ctx;
    private static SharedPreferences prefs;
    private static Object hub, listener;
    private static Class<?> itf;

    private static String top, homePkg;
    private static LinkedHashMap<String, String> seen;            // pkg -> "versionCode,v|o|x" (v vulkan, o opengl, x can't use Vulkan), oldest first
    private static final Map<String, Integer> unknownTries = new HashMap<>();
    private static final Set<String> pidDone = new HashSet<>();   // "pkg:pid" already re-checked under Vulkan
    private static final Map<String, Integer> snap = new HashMap<>();   // pkg -> pid when Vulkan was first seen on
    private static boolean snapOn;
    private static long blockedUntil;                             // the helper did not answer: leave it alone for a while

    private static String pendingPkg, pendingVer;
    private static int pendingMode;                               // 1 = ask the helper, 2 = offer only
    private static String checkPkg;                               // check in flight
    private static long checkVer;
    private static ContentObserver observer;
    private static View popup;

    // automatic speed rules (sbmods_rules): parsed copy, one observer, and which limit is ours
    private static final String K_OWN = "sm_own", G_RULES = "sbmods_rules";
    private static final Map<String, Habits.R> rules = new HashMap<>();
    private static ContentObserver rulesObs;
    private static String ruleTop;          // last front app the rules looked at (a repeat event for the same app does nothing)
    private static String owned;            // app whose limit WE set, else null
    private static int ownCpu, ownGpu;

    // game mode: switches sbmods_game (default 1) and sbmods_gamesclean (default 0); sbmods_games = the games used (panel's Games card)
    private static final String K_GAME = "sm_game", G_GAME = "sbmods_game", G_GAMES = "sbmods_games", G_GCLEAN = "sbmods_gamesclean";
    private static final Map<String, String> gameCache = new HashMap<>();   // pkg -> "versionCode,0|1" (category Game), per package version
    private static String gameFull;         // game we gave full speed to, else null (persisted in K_GAME with the limits it replaced)
    private static String gamePrev;         // "cpu|gpu|cool|cores" as they were before
    private static String cleanedFor;       // game the clean start was sent for (this launch)
    private static long awayAt;             // uptime when another app first came to the front after that
    private static boolean gameEnabled = true, gameClean;   // sbmods_game / sbmods_gamesclean, kept by gameObs (no settings read per front-app event)
    private static Set<String> manualSet = new HashSet<>(); // sbmods_gamelist, parsed when it changes
    private static ContentObserver gameObs;
    private static boolean resumeGame;      // the screen went off with a game at full speed: look again when it comes back

    // apps kept when cleaning (sbmods_keep): the clean time is cached by ONE observer (only while Smart is on), so a front-app event compares two longs
    private static final String G_CLEAN = "sbmods_clean_at", G_KEEPS = "sbmods_keep", K_KEEPAT = "sm_keepat";
    private static ContentObserver cleanObs;
    private static long cleanAt, keepCounted;   // cached sbmods_clean_at (epoch s); the clean_at value already counted

    private static final Runnable fire = Smart::fire;
    private static final Runnable timeout = () -> finishCheck(null);
    private static final Runnable dismiss = Smart::dismissPopup;

    /** Called on every config arrival (and at start-up). */
    static void apply(Context c, SharedPreferences p, boolean want) {
        if (want == on) return;
        on = want;
        try {
            if (want) start(c, p); else stop();
        } catch (Throwable t) { Log.w("smart: " + t); }
    }

    private static void start(Context c, SharedPreferences p) throws Exception {
        ctx = c; prefs = p;
        seen = new LinkedHashMap<>();
        String s = p.getString(K_CHK, "");
        for (String e : s.split(";")) { String[] f = e.split(","); if (f.length == 3) seen.put(f[0], f[1] + "," + f[2]); }
        ClassLoader cl = c.getClassLoader();
        itf = cl.loadClass("com.android.systemui.shared.system.TaskStackChangeListener");
        Class<?> h = cl.loadClass("com.android.systemui.shared.system.TaskStackChangeListeners");
        listener = Proxy.newProxyInstance(cl, new Class<?>[]{itf}, (proxy, m, args) -> {
            String n = m.getName();
            if (n.equals("equals")) return proxy == args[0];
            if (n.equals("hashCode")) return System.identityHashCode(proxy);
            if (n.equals("toString")) return "SBModsSmart";
            if (!on) return m.getReturnType() == boolean.class ? Boolean.FALSE : null;
            if (n.equals("onTaskStackChanged") || n.equals("onTaskMovedToFront")) main.post(Smart::onFront);
            return m.getReturnType() == boolean.class ? Boolean.FALSE : null;
        });
        hub = h.getField("INSTANCE").get(null);
        hub.getClass().getMethod("registerTaskStackListener", itf).invoke(hub, listener);
        loadRules();
        rulesObs = new ContentObserver(main) {
            @Override public void onChange(boolean self) { loadRules(); }
        };
        c.getContentResolver().registerContentObserver(Settings.Global.getUriFor(G_RULES), false, rulesObs);
        loadClean();
        keepCounted = p.getLong(K_KEEPAT, 0);
        cleanObs = new ContentObserver(main) {
            @Override public void onChange(boolean self) { loadClean(); }
        };
        c.getContentResolver().registerContentObserver(Settings.Global.getUriFor(G_CLEAN), false, cleanObs);
        String[] o = p.getString(K_OWN, "").split("\\|");   // a limit of ours that outlived a status bar restart
        if (o.length == 3) try { owned = o[0]; ownCpu = Integer.parseInt(o[1]); ownGpu = Integer.parseInt(o[2]); } catch (NumberFormatException e) { owned = null; }
        String[] g = p.getString(K_GAME, "").split("\\|", -1);   // game full speed that outlived a status bar restart
        if (g.length == 5) { gameFull = g[0]; gamePrev = g[1] + "|" + g[2]; }
        loadGame();
        gameObs = new ContentObserver(main) {
            @Override public void onChange(boolean self) { loadGame(); }
        };
        for (String k : new String[]{G_GAME, G_GCLEAN, "sbmods_gamelist"}) c.getContentResolver().registerContentObserver(Settings.Global.getUriFor(k), false, gameObs);
        main.post(Smart::onFront);
    }

    private static void stop() {
        main.removeCallbacks(fire); main.removeCallbacks(timeout);
        finishCheck("stop");
        dismissPopup();
        try { if (hub != null && listener != null) hub.getClass().getMethod("unregisterTaskStackListener", itf).invoke(hub, listener); }
        catch (Throwable t) { Log.w("smart unregister: " + t); }
        listener = null; top = null; pendingPkg = null;
        try { if (rulesObs != null) ctx.getContentResolver().unregisterContentObserver(rulesObs); } catch (Throwable ignored) {}
        rulesObs = null; rules.clear(); ruleTop = null;
        try { if (cleanObs != null) ctx.getContentResolver().unregisterContentObserver(cleanObs); } catch (Throwable ignored) {}
        cleanObs = null; cleanAt = 0;
        try { if (gameObs != null) ctx.getContentResolver().unregisterContentObserver(gameObs); } catch (Throwable ignored) {}
        gameObs = null;
        if (gameFull != null) endGame();
        gameCache.clear(); cleanedFor = null; awayAt = 0; resumeGame = false;
        if (owned != null) { if (stillOurs()) restoreFull(); forgetOwned(); }   // switched off while our limit is on: give full speed back
    }

    // ------------------------------------------------------------------ a different app came to the front

    @SuppressWarnings("deprecation")
    private static void onFront() {
        if (!on) return;
        try {
            List<ActivityManager.RunningTaskInfo> r = ctx.getSystemService(ActivityManager.class).getRunningTasks(1);
            if (r == null || r.isEmpty()) return;
            ComponentName cn = r.get(0).topActivity;
            String p = cn != null ? cn.getPackageName() : null;
            if (p == null) return;
            ruleFront(p, cn.getClassName());
            keepFront(p, cn.getClassName());
            if (p.equals(top)) return;
            top = p;
            main.removeCallbacks(fire); pendingPkg = null;
            if (checkPkg != null && !checkPkg.equals(p)) finishCheck("moved");
            dismissPopup();
            if (p.equals(Config.SYSUI) || p.equals(Config.PKG) || p.equals(home())) return;
            if (locked() || inCall()) return;
            long ver = versionOf(p);
            if (ver < 0) return;
            boolean vk = vulkanOn();
            if (vk) takeSnap(); else snapOn = false;
            int mode = decide(p, ver, vk);
            if (mode == 0) return;
            pendingPkg = p; pendingMode = mode;
            main.postDelayed(fire, DELAY_MS);
        } catch (Throwable t) { Log.w("smart front: " + t); }
    }

    // ------------------------------------------------------------------ apps kept when cleaning

    private static void loadClean() {
        try { String s = Global.gstr(ctx, G_CLEAN); cleanAt = s == null ? 0 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { cleanAt = 0; }
    }

    /**
     * The first normal app that comes to the front within a minute after a clean-up is counted in sbmods_keep (once per clean).
     * Cost on every front-app event: one clock read and two long compares; the settings write happens at most once per clean.
     */
    private static void keepFront(String p, String cls) {
        long now = System.currentTimeMillis() / 1000;
        if (!Habits.cleanFresh(cleanAt, keepCounted, now)) return;
        try {
            if (transientSurface(p, cls) || p.equals(home()) || locked()) return;
            keepCounted = cleanAt;
            prefs.edit().putLong(K_KEEPAT, cleanAt).apply();
            String cur = Global.gstr(ctx, G_KEEPS);
            if (cur == null) cur = "";
            Global.gset(ctx, G_KEEPS, Habits.learnKeep(cur, p, today()));
        } catch (Throwable t) { Log.w("smart keep: " + t); }
    }

    // ------------------------------------------------------------------ automatic speed rules

    private static void loadRules() {
        rules.clear();
        for (Habits.R r : Habits.parseRules(Global.gstr(ctx, G_RULES))) rules.put(r.pkg, r);
    }

    /** Surfaces that are not "the app the owner is using": they neither start nor end a rule (permission dialogs, our own screens, Overview). */
    private static boolean transientSurface(String p, String cls) {
        if (p.equals(Config.SYSUI) || p.equals(Config.PKG) || p.contains("permissioncontroller")) return true;
        return p.equals(home()) && cls != null && cls.contains("ecents");   // launcher's Overview; the plain home screen counts as leaving the app
    }

    /** An app came to the front (called from onFront only). Cost with no rules and no limit of ours: two field checks. */
    private static void ruleFront(String p, String cls) {
        if (!on) return;
        if (rules.isEmpty() && owned == null && gameFull == null && !gameOn()) return;   // nothing to do: no rule, no limit of ours, game mode off
        try {
            if (transientSurface(p, cls)) return;
            if (locked()) { ruleTop = null; return; }   // screen off / lock: leave everything; the first event after unlock decides
            if (p.equals(ruleTop)) return;
            ruleTop = p;
            if (owned != null) {
                if (p.equals(owned)) return;
                if (stillOurs()) { restoreFull(); toast("Back to full speed"); }
                forgetOwned();   // (if the owner changed the limit meanwhile: just forget, leave it alone)
            }
            boolean game = gameOn() && isGame(p);
            if (gameFull != null && !p.equals(gameFull)) endGame();   // a non-game (or another game) came to the front: give the earlier limits back
            if (cleanedFor != null && awayAt == 0 && !p.equals(cleanedFor)) awayAt = android.os.SystemClock.uptimeMillis();
            Habits.R r = rules.get(p);
            if (game) {
                gameStart(p);
                if (r == null) { gameFullSpeed(p); return; }   // no rule of the owner for this game: full speed while it is open
            }
            if (r == null || ctx.getSystemService(PowerManager.class).isPowerSaveMode() || limitActive()) return;
            Global.gput(ctx, "sbmods_cpu_pct", String.valueOf(r.cpu));
            Global.gput(ctx, "sbmods_gpu_pct", String.valueOf(r.gpu));
            owned = p; ownCpu = r.cpu; ownGpu = r.gpu;
            prefs.edit().putString(K_OWN, p + "|" + r.cpu + "|" + r.gpu).apply();
            toast(nameOf(p) + ": slowed down to save battery");
        } catch (Throwable t) { Log.w("smart rules: " + t); }
    }

    // ------------------------------------------------------------------ game mode

    /** The app's category is Game, or the owner listed it (cached per package version; the cache is dropped when the list changes). Samsung's Game Launcher list is not read. */
    private static boolean isGame(String p) {
        long ver = versionOf(p);
        String c = gameCache.get(p);
        if (c != null && c.startsWith(ver + ",")) return c.endsWith("1");
        boolean g = false;
        try { g = ver >= 0 && !protectedPkg(p) && (manualSet.contains(p) || ctx.getPackageManager().getApplicationInfo(p, 0).category == ApplicationInfo.CATEGORY_GAME); }
        catch (Throwable t) { Log.w("smart game: " + t); }
        gameCache.put(p, ver + "," + (g ? "1" : "0"));
        return g;
    }

    /** The three game settings, read when one of them changes (observer) and at start. Switching game mode off while a game is in front gives the limits back at once. */
    private static void loadGame() {
        gameEnabled = Global.gint(ctx, G_GAME, 1) == 1; gameClean = Global.gint(ctx, G_GCLEAN, 0) == 1;
        Set<String> m = new HashSet<>();
        String raw = Global.gstr(ctx, "sbmods_gamelist");
        if (raw != null) for (String e : raw.split(";")) { e = e.trim(); if (e.matches("[A-Za-z0-9._]+") && e.indexOf('.') > 0) m.add(e); }
        manualSet = m;
        gameCache.clear();
        if (gameFull != null && !gameEnabled) endGame();
    }

    private static boolean gameOn() { return gameEnabled || gameClean; }

    /** Screen went off (called by the status bar's screen receiver): a game at full speed gives the earlier limits back; unlocking looks at the front app again. */
    static void screenOff() {
        if (!on || gameFull == null) return;
        try { endGame(); resumeGame = true; ruleTop = null; } catch (Throwable t) { Log.w("smart screen off: " + t); }
    }

    /** Screen on / unlocked (called by the same receiver): only does anything after screenOff() ended a game. */
    static void resume() {
        if (!on || !resumeGame) return;
        try { if (!locked()) { resumeGame = false; main.post(Smart::onFront); } } catch (Throwable t) { Log.w("smart resume: " + t); }
    }

    /** A game came to the front (once per front change): list it for the panel's Games card, and send the one clean start per launch if wanted. */
    private static void gameStart(String p) {
        try {
            if (!gameOn()) return;
            String cur = Global.gstr(ctx, G_GAMES);
            String n = Habits.addGame(cur, p);
            if (!n.equals(cur == null ? "" : cur)) Global.gput(ctx, G_GAMES, n);
            if (p.equals(cleanedFor)) {
                if (awayAt > 0 && android.os.SystemClock.uptimeMillis() - awayAt > DELAY_MS) cleanedFor = null;   // away for longer than the debounce: a new launch
                awayAt = 0;
            }
            if (p.equals(cleanedFor) || !gameClean) return;
            cleanedFor = p; awayAt = 0;
            if (Jobs.pending(ctx)) return;
            Jobs.send(ctx, "ramclean");   // the job spares the owner's keep list and never touches the app in front
            toast("Closing background apps for " + nameOf(p));
        } catch (Throwable t) { Log.w("smart game start: " + t); }
    }

    /** Remember the limits in force, then switch them off (the same four settings as "Full speed"); undone by endGame(). */
    private static void gameFullSpeed(String p) {
        if (gameFull != null || !gameEnabled) return;
        if (Global.gint(ctx, "sbmods_cpu_pct", 0) <= 0 && Global.gint(ctx, "sbmods_gpu_pct", 0) <= 0) return;   // no CPU/GPU cap to lift (cool-down or core parking alone stay as they are)
        if (ctx.getSystemService(PowerManager.class).isPowerSaveMode()) return;   // battery first: power saving keeps its cap
        // (a timed limit is fine: the helper clears its timer when the limit is lifted and starts a new one when it comes back)
        String prev = Global.gint(ctx, "sbmods_cpu_pct", 0) + "|" + Global.gint(ctx, "sbmods_gpu_pct", 0);
        prefs.edit().putString(K_GAME, p + "|" + prev + "||").commit();   // saved before anything changes
        gameFull = p; gamePrev = prev;
        Global.gset(ctx, "sbmods_cpu_pct", "0"); Global.gset(ctx, "sbmods_gpu_pct", "0");   // only the CPU/GPU caps; heat protection and cores stay
        toast(nameOf(p) + ": full speed");
    }

    /** Give the earlier limits back, unless the owner changed them meanwhile (then leave them alone). */
    private static void endGame() {
        String prev = gamePrev;
        gameFull = null; gamePrev = null;
        try {
            prefs.edit().remove(K_GAME).apply();
            String[] f = prev == null ? new String[0] : prev.split("\\|", -1);
            if (f.length != 2 || Global.gint(ctx, "sbmods_cpu_pct", 0) != 0 || Global.gint(ctx, "sbmods_gpu_pct", 0) != 0) return;   // the owner changed them meanwhile: leave them
            Global.gset(ctx, "sbmods_cpu_pct", f[0]); Global.gset(ctx, "sbmods_gpu_pct", f[1]);
            toast("Back to your earlier speed limit");
        } catch (Throwable t) { Log.w("smart game end: " + t); }
    }

    private static boolean limitActive() {
        return Global.gint(ctx, "sbmods_cpu_pct", 0) > 0 || Global.gint(ctx, "sbmods_gpu_pct", 0) > 0 || Global.gint(ctx, "sbmods_cool", 0) == 1
                || !PanelMath.normCores(Global.gstr(ctx, "sbmods_cores")).equals("0");
    }

    /** The limit in force is exactly the one we set (nothing else on). */
    private static boolean stillOurs() {
        return Global.gint(ctx, "sbmods_cpu_pct", 0) == ownCpu && Global.gint(ctx, "sbmods_gpu_pct", 0) == ownGpu
                && Global.gint(ctx, "sbmods_cool", 0) == 0 && PanelMath.normCores(Global.gstr(ctx, "sbmods_cores")).equals("0");
    }

    /** Same as the Options card's "Full speed". */
    private static void restoreFull() {
        for (String k : new String[]{"sbmods_cpu_pct", "sbmods_gpu_pct", "sbmods_cool", "sbmods_cores"}) Global.gset(ctx, k, "0");
    }

    private static void forgetOwned() {
        owned = null;
        prefs.edit().remove(K_OWN).apply();
    }

    /** 0 nothing to do, 1 ask the helper, 2 only offer (answer already known). */
    private static int decide(String p, long ver, boolean vk) {
        String e = seen.get(p);
        if (e != null && e.startsWith(ver + ",")) {
            char res = e.charAt(e.length() - 1);
            if (res == 'v' || res == 'x') return 0;
            if (!vk) return askable(p) ? 2 : 0;
            return restarted(p) || mayRestart(p) ? 1 : 0;   // opengl before the switch: look again once its process restarted, or once per boot to restart it ourselves
        }
        if (unknownTries.getOrDefault(p, 0) >= 2) return 0;
        if (vk && !pidDone.isEmpty() && pidDone.contains(p + ":" + pidOf(p))) return 0;
        return helperFree() ? 1 : 0;
    }

    private static boolean helperFree() { return android.os.SystemClock.uptimeMillis() >= blockedUntil; }

    private static void fire() {
        if (!on) return;
        try {
            String p = pendingPkg;
            if (p == null || !p.equals(top) || locked() || inCall() || !ctx.getSystemService(PowerManager.class).isInteractive()) return;
            if (pendingMode == 2) { offer(p); return; }
            if (!helperFree() || checkPkg != null) return;
            if (Jobs.pending(ctx) || Global.gstr(ctx, "sbmods_gfx") == null) return;   // helper script busy / root jobs never ran
            checkPkg = p; checkVer = versionOf(p);
            String old = Global.gstr(ctx, "sbmods_appgfx");
            if (old != null && old.startsWith(p + ":")) Global.gput(ctx, "sbmods_appgfx", "-");   // an old answer must not be taken for this one
            observer = new ContentObserver(main) {
                @Override public void onChange(boolean self) { onAnswer(); }
            };
            ctx.getContentResolver().registerContentObserver(Settings.Global.getUriFor("sbmods_appgfx"), false, observer);
            main.postDelayed(timeout, WAIT_MS);
            Jobs.send(ctx, "appgfx:" + p);
        } catch (Throwable t) { Log.w("smart fire: " + t); finishCheck("error"); }
    }

    private static void onAnswer() {
        if (!on || checkPkg == null) return;
        String r = Global.gstr(ctx, "sbmods_appgfx");
        if (r == null || !r.startsWith(checkPkg + ":")) return;
        finishCheck(r.substring(checkPkg.length() + 1));
    }

    /** End the pending check. `how` = vulkan | opengl | unknown (an answer), null = timed out, anything else = abandoned. */
    private static void finishCheck(String how) {
        String p = checkPkg;
        if (p == null) return;
        checkPkg = null;
        main.removeCallbacks(timeout);
        try { if (observer != null) ctx.getContentResolver().unregisterContentObserver(observer); } catch (Throwable ignored) {}
        observer = null;
        if (!on) return;
        try {
            if (how == null) {
                if (("appgfx:" + p).equals(Global.gstr(ctx, "sbmods_cmd"))) Global.gput(ctx, "sbmods_cmd", "none");   // withdraw: never runs later by surprise
                blockedUntil = android.os.SystemClock.uptimeMillis() + BLOCK_MS;
                return;
            }
            if (how.equals("vulkan") || how.equals("opengl")) result(p, checkVer, how.equals("vulkan"));
            else if (how.equals("unknown")) unknownTries.merge(p, 1, Integer::sum);
        } catch (Throwable t) { Log.w("smart result: " + t); }
    }

    private static void result(String p, long ver, boolean vulkan) {
        String name = nameOf(p);
        String before = seen.get(p);
        if (vulkan) {
            remember(p, ver, 'v');
            toast(name + ": Vulkan");
            return;
        }
        if (vulkanOn()) {   // still OpenGL although the whole phone is on Vulkan
            int pid = pidOf(p);
            boolean started = snapOn && pid != 0 && !(snap.containsKey(p) && snap.get(p) == pid);
            if (started) { remember(p, ver, 'x'); toast(name + " can't use Vulkan"); }
            else if (restartOk(p)) restartApp(p, name, pid);
            else { pidDone.add(p + ":" + pid); toast(name + ": OpenGL"); }   // it predates the switch: tells us nothing
            return;
        }
        remember(p, ver, 'o');
        if (before == null || !before.startsWith(ver + ",")) toast(name + ": OpenGL");
        if (p.equals(top) && askable(p)) offer(p);
    }

    // ------------------------------------------------------------------ restart an app that predates the Vulkan switch (once per app per boot)

    private static final String K_BOOT = "sm_boot", K_GFXR = "sm_gfxr";
    private static String bootMark;                 // read once, lazily ("" = unreadable: memory-only set)
    private static final Set<String> restarted1 = new HashSet<>();   // apps restarted since this boot (mirrors K_GFXR)

    private static boolean protectedPkg(String p) {
        if (p.equals("android") || p.equals(Config.SYSUI) || p.equals(Entry.LAUNCHER) || p.equals(home()) || p.equals(Config.PKG) || p.equals("com.termux")
                || p.startsWith("com.android.providers.") || p.startsWith("com.google.android.gms") || p.equals("com.sec.imsservice") || p.equals("com.sec.epdg")) return true;
        String ime = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return ime != null && ime.startsWith(p + "/");
    }

    /** Loads the once-per-boot set the first time it is needed; drops it when the boot marker changed. */
    private static void loadBoot() {
        if (bootMark != null) return;
        String m = "";
        try {
            String id = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/sys/kernel/random/boot_id"))).trim();
            String sc = "";
            try { sc = String.valueOf(Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, "sys.system_server.start_count", "")); } catch (Throwable ignored) {}
            m = id + ":" + sc;
        } catch (Throwable t) { Log.w("smart boot marker: " + t); }
        bootMark = m;
        if (m.isEmpty()) return;
        try {
            if (m.equals(prefs.getString(K_BOOT, ""))) {
                for (String e : prefs.getString(K_GFXR, "").split(";")) if (!e.isEmpty()) restarted1.add(e);
            } else prefs.edit().putString(K_BOOT, m).remove(K_GFXR).apply();
        } catch (Throwable t) { Log.w("smart boot set: " + t); }
    }

    /** Cheap pre-check for decide(): this app was OpenGL before the switch, its process is unchanged, and we have not restarted it since boot. */
    private static boolean mayRestart(String p) {
        try {
            if (!helperFree() || Global.gint(ctx, "sbmods_gfx_restart", 1) != 1) return false;
            loadBoot();
            return !restarted1.contains(p) && !pidDone.contains(p + ":" + pidOf(p));
        } catch (Throwable t) { return false; }
    }

    private static boolean restartOk(String p) {
        try {
            if (!on || Global.gint(ctx, "sbmods_gfx_restart", 1) != 1) return false;
            if (!p.equals(top) || locked() || inCall() || ctx.getSystemService(AudioManager.class).isMusicActive()) return false;
            if (protectedPkg(p) || ctx.getPackageManager().getLaunchIntentForPackage(p) == null) return false;
            if (Jobs.pending(ctx)) return false;
            loadBoot();
            return !restarted1.contains(p);
        } catch (Throwable t) { Log.w("smart restartOk: " + t); return false; }
    }

    private static void restartApp(String p, String name, int pid) {
        try {
            restarted1.add(p); pidDone.add(p + ":" + pid);
            if (!bootMark.isEmpty()) prefs.edit().putString(K_GFXR, String.join(";", restarted1)).apply();
            toast("Restarting " + name + " to use Vulkan");
            Global.gput(ctx, "sbmods_result", "");
            Jobs.send(ctx, "stop:" + p);
            main.postDelayed(() -> {   // one shot: reopen once the helper has answered, else give up quietly
                try {
                    String r = Global.gstr(ctx, "sbmods_result");
                    // no answer yet: withdraw a request still waiting for the helper script, and reopen anyway (never leave the app closed)
                    if ((r == null || r.isEmpty()) && ("stop:" + p).equals(Global.gstr(ctx, "sbmods_cmd"))) Global.gput(ctx, "sbmods_cmd", "none");
                    Intent li = ctx.getPackageManager().getLaunchIntentForPackage(p);
                    if (li == null) return;
                    top = null;   // so the front-app check runs again for the reopened app
                    ctx.startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable t) { Log.w("smart reopen: " + t); }
            }, 3000);
        } catch (Throwable t) { Log.w("smart restart: " + t); }
    }

    // ------------------------------------------------------------------ the offer

    private static boolean askable(String p) {
        if (vulkanOn() || Global.gint(ctx, G_NOASK, 0) == 1) return false;
        String e = seen.get(p);
        if (e != null && e.endsWith(",x")) return false;
        return prefs.getLong(K_ASKDAY, -1) != today();
    }

    private static long today() { return java.time.LocalDate.now().toEpochDay(); }

    private static void offer(String p) {
        if (!on || popup != null || !askable(p) || locked() || immersive()) return;
        final Context c = ctx;
        final float d = c.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Math.round(18 * d), Math.round(14 * d), Math.round(10 * d), Math.round(6 * d));
        box.setBackground(new Ui.Glass(0xF01B1B1F, 0x33FFFFFF, 0x40FFFFFF, 24 * d, Math.max(1, d)));
        TextView t = Ui.text(c, nameOf(p) + " is drawing with OpenGL. Switch apps to Vulkan? It is phone-wide and each app picks it up the next time it opens.", 14, Ui.CHIP_FG, 500);
        box.addView(t, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.END);
        row.addView(button(c, "Not now", Ui.CHIP_FG & 0xCCFFFFFF, () -> {
            if (on) prefs.edit().putLong(K_ASKDAY, today()).apply();
        }));
        row.addView(button(c, "Don't ask again", Ui.CHIP_FG & 0xCCFFFFFF, () -> { if (on) Global.gput(c, G_NOASK, "1"); }));
        row.addView(button(c, "Switch", Ui.DARK.acc, () -> {
            if (!on) return;
            takeSnap();
            PanelCmd.post(c, "gfx:vulkan", null);
            Global.gput(c, G_KEEP, "1");
            toast("Vulkan: apps use it the next time they open");
        }));
        box.addView(row, new LinearLayout.LayoutParams(-1, -2));
        int w = Math.min(c.getResources().getDisplayMetrics().widthPixels - Math.round(32 * d), Math.round(380 * d));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = Math.round(48 * d);
        lp.setTitle("SBModsSmart");
        try {
            c.getSystemService(WindowManager.class).addView(box, lp);
            popup = box;
            main.postDelayed(dismiss, POPUP_MS);
        } catch (Throwable t2) { Log.w("smart popup: " + t2); }
    }

    private static TextView button(Context c, String label, int color, Runnable action) {
        TextView b = Ui.text(c, label, 14, color, 600);
        b.setGravity(Gravity.CENTER);
        float d = c.getResources().getDisplayMetrics().density;
        b.setPadding(Math.round(10 * d), Math.round(10 * d), Math.round(10 * d), Math.round(10 * d));
        b.setMinHeight(Math.round(40 * d));
        b.setOnClickListener(v -> { dismissPopup(); try { action.run(); } catch (Throwable t) { Log.w("smart button: " + t); } });
        return b;
    }

    private static void dismissPopup() {
        main.removeCallbacks(dismiss);
        View v = popup;
        popup = null;
        if (v == null) return;
        try { ctx.getSystemService(WindowManager.class).removeView(v); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ helpers

    private static boolean vulkanOn() { return "vulkan".equals(Global.gstr(ctx, "sbmods_gfx")); }

    private static boolean locked() {
        KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
        return km != null && km.isKeyguardLocked();
    }

    private static boolean inCall() {
        try {
            int m = ctx.getSystemService(AudioManager.class).getMode();
            if (m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE) return true;
        } catch (Throwable ignored) {}
        try { return ctx.getSystemService(android.telephony.TelephonyManager.class).getCallState() != android.telephony.TelephonyManager.CALL_STATE_IDLE; }
        catch (Throwable t) { return false; }
    }

    /** Best guess that the status bar is hidden (full-screen video, game, immersive): landscape, or the bar's own window is not on screen. */
    private static boolean immersive() {
        if (ctx.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) return true;
        View r = Controller.statusRoot;
        if (r == null || !r.isAttachedToWindow() || !r.isShown() || r.getAlpha() < 0.1f) return true;
        int[] xy = new int[2];
        r.getLocationOnScreen(xy);
        return xy[1] + r.getHeight() <= 0;
    }

    private static String home() {
        if (homePkg == null) {
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            homePkg = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : Entry.LAUNCHER;
        }
        return homePkg;
    }

    private static long versionOf(String p) {
        try { return ctx.getPackageManager().getPackageInfo(p, 0).getLongVersionCode(); } catch (Throwable t) { return -1; }
    }

    private static String nameOf(String p) {
        try { PackageManager pm = ctx.getPackageManager(); return pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString(); }
        catch (Throwable t) { return p; }
    }

    private static int pidOf(String p) {
        try {
            for (ActivityManager.RunningAppProcessInfo i : ctx.getSystemService(ActivityManager.class).getRunningAppProcesses())
                if (p.equals(i.processName)) return i.pid;
        } catch (Throwable ignored) {}
        return 0;
    }

    /** Remember which process each app had when Vulkan was first seen on, so a later restart of it can be told apart. */
    private static void takeSnap() {
        if (snapOn) return;
        snapOn = true; snap.clear(); pidDone.clear();
        try {
            for (ActivityManager.RunningAppProcessInfo i : ctx.getSystemService(ActivityManager.class).getRunningAppProcesses())
                snap.put(i.processName, i.pid);
        } catch (Throwable ignored) {}
    }

    private static boolean restarted(String p) {
        if (!snapOn) return false;
        int pid = pidOf(p);
        return pid != 0 && !(snap.containsKey(p) && snap.get(p) == pid) && !pidDone.contains(p + ":" + pid);
    }

    private static void remember(String p, long ver, char res) {
        seen.remove(p);
        seen.put(p, ver + "," + res);
        while (seen.size() > CAP) seen.remove(seen.keySet().iterator().next());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : seen.entrySet()) sb.append(e.getKey()).append(',').append(e.getValue()).append(';');
        prefs.edit().putString(K_CHK, sb.toString()).apply();
    }

    private static void toast(String m) { Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show(); }
}
