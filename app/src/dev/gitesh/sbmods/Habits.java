// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure string helpers for "what the owner slows down by hand" (global sbmods_habits) and "what may happen automatically"
 * (global sbmods_rules). No Android classes, nothing is read or written here: callers pass strings in and get strings back
 * (an unchanged result is the SAME string, so a caller can skip the write).
 *
 * habits = "pkg|cpu|gpu|count|lastDay;..."   oldest first, at most 30. count = days this level was chosen in this app; -1 = declined.
 * rules  = "pkg|cpu|gpu;..."                 at most 20.
 */
public final class Habits {
    private Habits() {}

    public static final int MAX_HABITS = 30, MAX_RULES = 20, NEED_DAYS = 3, DECLINED = -1;

    public static final class H {
        public final String pkg; public final int cpu, gpu, count; public final long day;
        H(String pkg, int cpu, int gpu, int count, long day) { this.pkg = pkg; this.cpu = cpu; this.gpu = gpu; this.count = count; this.day = day; }
        String str() { return pkg + "|" + cpu + "|" + gpu + "|" + count + "|" + day; }
    }

    public static final class R {
        public final String pkg; public final int cpu, gpu;
        R(String pkg, int cpu, int gpu) { this.pkg = pkg; this.cpu = cpu; this.gpu = gpu; }
        String str() { return pkg + "|" + cpu + "|" + gpu; }
    }

    private static boolean okPkg(String p) { return p != null && p.matches("[A-Za-z0-9_.]{1,150}"); }
    private static boolean okPct(int v) { return v >= 0 && v <= 100; }

    public static List<H> parseHabits(String s) {
        List<H> out = new ArrayList<>();
        if (s == null) return out;
        for (String e : s.split(";")) {
            String[] f = e.split("\\|");
            if (f.length != 5 || !okPkg(f[0])) continue;
            try { out.add(new H(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), Long.parseLong(f[4]))); }
            catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public static List<R> parseRules(String s) {
        List<R> out = new ArrayList<>();
        if (s == null) return out;
        for (String e : s.split(";")) {
            String[] f = e.split("\\|");
            if (f.length != 3 || !okPkg(f[0])) continue;
            try {
                int c = Integer.parseInt(f[1]), g = Integer.parseInt(f[2]);
                if (okPct(c) && okPct(g) && c + g > 0) out.add(new R(f[0], c, g));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private static String joinH(List<H> l) { StringBuilder b = new StringBuilder(); for (H h : l) b.append(h.str()).append(';'); return b.toString(); }
    private static String joinR(List<R> l) { StringBuilder b = new StringBuilder(); for (R r : l) b.append(r.str()).append(';'); return b.toString(); }

    /**
     * The owner just turned a limit on by hand while using `pkg`. Counts at most once per calendar day (`day` = epoch day); a
     * different level restarts the count at 1; a declined app stays declined. Returns the new string, or `habits` itself if nothing changed.
     */
    public static String learn(String habits, String pkg, int cpu, int gpu, long day) {
        if (!okPkg(pkg) || !okPct(cpu) || !okPct(gpu) || cpu + gpu == 0) return habits;
        List<H> l = parseHabits(habits);
        H old = null;
        for (H h : l) if (h.pkg.equals(pkg)) old = h;
        H now;
        if (old == null) now = new H(pkg, cpu, gpu, 1, day);
        else if (old.count == DECLINED) return habits;
        else if (old.cpu != cpu || old.gpu != gpu) now = new H(pkg, cpu, gpu, 1, day);
        else if (old.day == day) return habits;
        else now = new H(pkg, cpu, gpu, old.count + 1, day);
        if (old != null) l.remove(old);
        l.add(now);
        while (l.size() > MAX_HABITS) l.remove(0);
        return joinH(l);
    }

    /** The first habit that has reached NEED_DAYS and has no rule yet (declined ones have count -1 and never qualify); null if none. */
    public static H candidate(String habits, String rules) {
        List<R> rs = parseRules(rules);
        for (H h : parseHabits(habits)) {
            if (h.count < NEED_DAYS) continue;
            boolean has = false;
            for (R r : rs) if (r.pkg.equals(h.pkg)) has = true;
            if (!has) return h;
        }
        return null;
    }

    /** Mark an app as declined (the owner said No). */
    public static String decline(String habits, String pkg) {
        List<H> l = parseHabits(habits);
        for (int i = 0; i < l.size(); i++) {
            H h = l.get(i);
            if (h.pkg.equals(pkg)) { l.set(i, new H(h.pkg, h.cpu, h.gpu, DECLINED, h.day)); return joinH(l); }
        }
        return habits;
    }

    /** Add (or replace) the rule for an app; the oldest rule is dropped beyond MAX_RULES. */
    public static String addRule(String rules, String pkg, int cpu, int gpu) {
        if (!okPkg(pkg) || !okPct(cpu) || !okPct(gpu) || cpu + gpu == 0) return rules == null ? "" : rules;
        List<R> l = parseRules(rules);
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).pkg.equals(pkg)) l.remove(i);
        l.add(new R(pkg, cpu, gpu));
        while (l.size() > MAX_RULES) l.remove(0);
        return joinR(l);
    }

    public static String removeRule(String rules, String pkg) {
        List<R> l = parseRules(rules);
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).pkg.equals(pkg)) l.remove(i);
        return joinR(l);
    }

    /** "slow down to 55 %" or, when CPU and GPU differ, "slow down (CPU 70 %, GPU full speed)". */
    public static String describe(R r) {
        if (r.cpu == r.gpu) return "slow down to " + r.cpu + " %";
        return "slow down (CPU " + (r.cpu > 0 ? r.cpu + " %" : "full speed") + ", GPU " + (r.gpu > 0 ? r.gpu + " %" : "full speed") + ")";
    }

}
