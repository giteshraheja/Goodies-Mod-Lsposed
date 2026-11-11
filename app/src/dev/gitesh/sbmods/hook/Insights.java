// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The Insights card's simple rules: numbers in, up to three ranked suggestions out. No Android classes (easy to test),
 * nothing is read or started here; the caller passes in numbers the panel already has.
 */
final class Insights {
    private Insights() {}

    static final int MAX = 3;                   // at most this many lines on the card
    static final int TOP_MIN_PCT = 30;          // an app counts as heavy at this share of one CPU core (the helper's "top" list)
    static final int SWAP_MIN_PCT = 70;         // swap this full = memory is tight
    static final int RAM_MIN_PCT = 90;          // memory this full = tight (the Memory card turns red here)
    static final int LIMIT_END_SEC = 60;        // a limit with less than this left is "nearly over"

    static final int HOT = 0, APP = 1, MEMORY = 2, LIMIT = 3, VULKAN = 4;   // also the rank: lower comes first

    static final class Item {
        final int kind;
        final String text, button, arg;   // arg: the package for APP, else null
        Item(int kind, String text, String button, String arg) { this.kind = kind; this.text = text; this.button = button; this.arg = arg; }
        /** Stable id of this suggestion, so a line the owner already acted on stays gone while the panel is open. */
        String key() { return kind + ":" + (arg == null ? "" : arg); }
    }

    /** Everything the rules look at. Plain fields, filled by the card. */
    static final class In {
        int tempC, coolAtTenths = 360;     // battery temperature in whole degrees; the user's cool-down level in tenths of a degree
        boolean limitOn;                   // any CPU / GPU limit, resting cores or cool-down in force
        boolean heldBack;                  // a CPU / GPU limit or resting cores in force (cool-down itself not counted)
        boolean charging;
        long limitSecLeft;                 // seconds until the limit ends by itself; 0 = none / unknown
        int swapPct, ramUsedPct;
        String top;                        // helper's "pkg:cpu%,pkg:cpu%" answer; null = not available yet
        String front;                      // the app used last (not the home screen), or null
        Predicate<String> canStop;         // false for apps the helper refuses and for ours / the launcher
        Function<String, String> label;    // package -> name shown to the owner
        boolean gfxKeep, gfxVulkan;        // "keep Vulkan" was chosen / Vulkan is on now
    }

    /** The suggestions, most important first, at most MAX; skip(key) drops lines already dealt with. */
    static List<Item> rank(In in, Predicate<String> skip) {
        List<Item> out = new ArrayList<>();
        boolean hot = in.tempC * 10 >= in.coolAtTenths;
        if (hot && !in.limitOn) out.add(new Item(HOT, "Phone is " + in.tempC + "°C", "Cool down", null));   // 1: warm and nothing holds it back
        String app = heavyApp(in);
        if (app != null) out.add(new Item(APP, in.label.apply(app) + " is using a lot in the background", "Stop", app));   // 2
        if (in.swapPct >= SWAP_MIN_PCT || in.ramUsedPct >= RAM_MIN_PCT) out.add(new Item(MEMORY, "Memory is nearly full", "Deep clean", null));   // 3
        if (in.heldBack && !hot && (in.charging || (in.limitSecLeft > 0 && in.limitSecLeft < LIMIT_END_SEC)))
            out.add(new Item(LIMIT, in.charging ? "A speed limit is on while charging" : "A speed limit is about to end", "Full speed", null));   // 4
        if (in.gfxKeep && !in.gfxVulkan) out.add(new Item(VULKAN, "Vulkan is off after the restart", "Turn on", null));   // 5
        List<Item> res = new ArrayList<>();
        for (Item i : out) if ((skip == null || !skip.test(i.key())) && res.size() < MAX) res.add(i);
        return res;
    }

    /** The busiest app in the helper's list that is not in front and may be stopped, at TOP_MIN_PCT or more; null if none. */
    static String heavyApp(In in) {
        if (in.top == null || in.top.isEmpty() || in.top.equals("-")) return null;
        String best = null;
        double bestPct = TOP_MIN_PCT - 0.001;
        for (String e : in.top.split(",")) {
            int c = e.lastIndexOf(':');
            if (c <= 0) continue;
            String pkg = e.substring(0, c).trim();
            double pct;
            try { pct = Double.parseDouble(e.substring(c + 1).trim().replace("%", "")); } catch (NumberFormatException x) { continue; }
            if (pct > bestPct && !pkg.equals(in.front) && in.canStop.test(pkg)) { best = pkg; bestPct = pct; }
        }
        return best;
    }
}
