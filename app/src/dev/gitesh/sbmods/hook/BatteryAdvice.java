// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import java.util.ArrayList;
import java.util.List;

/** Reads the battery numbers in plain words: what each one means and what to do. No Android classes, so it can be tested alone. */
final class BatteryAdvice {
    private BatteryAdvice() {}

    static final int OK = 0, TIP = 1, ACT = 2;

    static final class Item {
        final int level;
        final String title, why, fix;
        Item(int level, String title, String why, String fix) { this.level = level; this.title = title; this.why = why; this.fix = fix; }
    }

    /** The numbers the rules use. Unknown = -1 (or null / empty lists). */
    static final class Input {
        boolean charging;
        double standbyPerHour = -1;    // % per hour with the screen off
        double screenOnPerHour = -1;   // % per hour with the screen on
        int sleepPct = -1;             // share of screen-off time asleep
        String topAwake;               // name of the app that keeps the phone awake most, or null
        List<String> woken = new ArrayList<>();
        int holdsPct = -1;             // health: % of new capacity the battery holds
        int stopPct = -1;              // health: charging stops at this %
    }

    /** Most important first. */
    static List<Item> build(Input in) {
        List<Item> out = new ArrayList<>();
        if (in.holdsPct >= 0) {
            if (in.holdsPct < 80) {
                out.add(new Item(ACT, "The battery is wearing out",
                        "It holds only " + in.holdsPct + "% of the charge it had when new. It runs down faster and can shut off early when cold.",
                        "Set the charge limit to 85% in Battery protection, and avoid charging overnight or in heat."));
            } else if (in.holdsPct < 90 && (in.stopPct == 100 || in.stopPct < 0)) {
                out.add(new Item(TIP, "A charge limit would help",
                        "It holds " + in.holdsPct + "%. Charging to 100% every time wears the battery faster.",
                        "Set the charge limit to 85% in Battery protection (under Health)."));
            }
        }
        if (!in.charging && in.standbyPerHour >= 0) {
            if (in.standbyPerHour >= 2.5) {
                out.add(new Item(ACT, "The phone uses a lot of power while idle",
                        String.format(java.util.Locale.US, "With the screen off it loses %.1f%% an hour. Normal is under 1%%. Something keeps it awake.", in.standbyPerHour),
                        in.topAwake != null
                                ? "In the System tab, Apps allowed to stay awake: remove " + in.topAwake + " if you don't need it running in the background."
                                : "In the System tab, turn on Sleep sooner so the phone goes into deep sleep sooner."));
            } else if (in.standbyPerHour >= 1) {
                out.add(new Item(TIP, "Idle drain is a bit high",
                        String.format(java.util.Locale.US, "With the screen off it loses %.1f%% an hour. Normal is under 1%%.", in.standbyPerHour),
                        in.topAwake != null
                                ? "Check " + in.topAwake + " in Apps allowed to stay awake (System tab)."
                                : "Turn on Sleep sooner in the System tab."));
            }
        }
        if (in.sleepPct >= 0 && in.sleepPct < 60) {
            String who = in.woken.isEmpty() ? "" : " Woken most by " + String.join(" and ", in.woken) + ".";
            out.add(new Item(ACT, "The phone barely sleeps when the screen is off",
                    "It slept only " + in.sleepPct + "% of the time it was idle, so it stays on and uses power." + who,
                    "In the System tab, turn on Sleep sooner. Then check Apps allowed to stay awake."));
        }
        if (!in.charging && in.screenOnPerHour >= 20) {
            out.add(new Item(in.screenOnPerHour >= 30 ? ACT : TIP, "Screen use drains the battery fast",
                    String.format(java.util.Locale.US, "With the screen on it loses %.1f%% an hour.", in.screenOnPerHour),
                    "On the Battery page, set Screen smoothness to 60 Hz, or turn on Power saving."));
        }
        if (out.isEmpty() && in.standbyPerHour < 0 && in.screenOnPerHour < 0 && in.sleepPct < 0 && in.holdsPct < 0) {
            out.add(new Item(OK, "Not enough data yet",
                    "The phone needs a few hours of normal use, with the screen on and off, before it can judge.", ""));
        } else if (out.isEmpty()) {
            out.add(new Item(OK, "Nothing unusual",
                    "The battery numbers look normal for now. Check again after a day of normal use.", ""));
        }
        return out;
    }

    /** Health answer "holds|cycles|stopPct" (any part -1 when unknown) into {holds, stop}; null when the answer is missing. */
    static int[] parseHealth(String s) {
        if (s == null) return null;
        String[] p = s.split("\\|");
        if (p.length < 3) return null;
        try { return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[2].trim())}; } catch (NumberFormatException e) { return null; }
    }
}
