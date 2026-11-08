// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

/**
 * One-tap looks for Simple mode. A preset only touches "what is shown" fields; appearance
 * tweaks made in Expert mode (sensor, size, gap, colors...) are kept.
 */
public final class Presets {
    public static final class Preset {
        public final String name, example;
        final boolean temp; final int clockMode, hourMode, ampm, weekday, date, suffix, dateSize;
        final boolean seconds;

        Preset(String name, String example, boolean temp, int clockMode, int hourMode, int ampm,
               boolean seconds, int weekday, int date, int suffix, int dateSize) {
            this.name = name; this.example = example; this.temp = temp; this.clockMode = clockMode;
            this.hourMode = hourMode; this.ampm = ampm; this.seconds = seconds; this.weekday = weekday;
            this.date = date; this.suffix = suffix; this.dateSize = dateSize;
        }

        public void apply(Config c) {
            c.tempEnabled = temp; c.clockMode = clockMode; c.hourMode = hourMode; c.ampm = ampm;
            c.seconds = seconds; c.weekday = weekday; c.date = date; c.suffix = suffix;
            c.dateSizePct = dateSize; c.year = 0; c.dateAfterTime = false; c.leadingZero = false;
            c.ampmLower = false;
        }

        public boolean matches(Config c) {
            if (c.tempEnabled != temp || c.clockMode != clockMode) return false;
            if (temp && c.suffix != suffix) return false;
            if (clockMode == Config.CLOCK_STOCK) return true;
            return c.hourMode == hourMode && c.ampm == ampm && c.seconds == seconds
                    && c.weekday == weekday && c.date == date && c.dateSizePct == dateSize
                    && c.year == 0 && !c.dateAfterTime && !c.leadingZero && !c.ampmLower;
        }
    }

    private static final int B = Config.CLOCK_BUILDER, S = Config.CLOCK_STOCK;

    public static final Preset[] ALL = {
            new Preset("Temperature only", "12:30  34", true, S, 0, Config.AMPM_SMALL, false, 0, 0, Config.SUFFIX_NONE, 100),
            new Preset("Clock with AM/PM", "12:30pm  34", true, B, Config.HOUR_12, Config.AMPM_SMALL, false, 0, 0, Config.SUFFIX_NONE, 100),
            new Preset("Day + time", "Sun 12:30pm  34°", true, B, Config.HOUR_12, Config.AMPM_SMALL, false, Config.WEEKDAY_SHORT, 0, Config.SUFFIX_DEG, 100),
            new Preset("Full info", "Sun 1 Nov 12:30pm  34°C", true, B, Config.HOUR_12, Config.AMPM_SMALL, false, Config.WEEKDAY_SHORT, 2, Config.SUFFIX_DEG_C, 85),
            new Preset("With seconds", "12:30:45  34", true, B, Config.HOUR_SYSTEM, Config.AMPM_HIDDEN, true, 0, 0, Config.SUFFIX_NONE, 100),
            new Preset("Stock (all off)", "12:30", false, S, 0, Config.AMPM_SMALL, false, 0, 0, Config.SUFFIX_NONE, 100),
    };

    private Presets() {}
}
