// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * All user options. Shared by the settings app (writes) and the SystemUI hook (reads).
 * To add an option: add a field + default, then one line in write() and read().
 */
public final class Config {
    public static final String PKG = "dev.gitesh.sbmods";
    public static final String SYSUI = "com.android.systemui";
    public static final String PERM = PKG + ".permission.CONTROL";

    public static final String ACTION_APPLY = PKG + ".APPLY";
    public static final String ACTION_PING = PKG + ".PING";
    public static final String ACTION_PONG = PKG + ".PONG";
    public static final String ACTION_RESET_GUARD = PKG + ".RESET_GUARD";
    public static final String ACTION_TEST = PKG + ".TEST_ACTION";
    public static final String ACTION_RESTART = PKG + ".RESTART";
    public static final String ACTION_TIP = PKG + ".SHOW_TIP";
    public static final String ACTION_TOAST = PKG + ".TOAST";

    // Thermal sensor types (android.os.Temperature.TYPE_*)
    public static final int SENSOR_CPU = 0, SENSOR_BATTERY = 2, SENSOR_SKIN = 3;
    public static final int SUFFIX_NONE = 0, SUFFIX_DEG = 1, SUFFIX_DEG_C = 2;
    public static final int CLOCK_STOCK = 0, CLOCK_BUILDER = 1, CLOCK_PATTERN = 2;
    public static final int HOUR_SYSTEM = 0, HOUR_12 = 1, HOUR_24 = 2;
    public static final int AMPM_HIDDEN = 0, AMPM_NORMAL = 1, AMPM_SMALL = 2;
    public static final int WEEKDAY_NONE = 0, WEEKDAY_SHORT = 1, WEEKDAY_FULL = 2, WEEKDAY_TWO = 3;
    public static final int POS_AUTO = 0, POS_AFTER = 1, POS_BEFORE = 2;
    // Tap actions (see hook/TapActions)
    // Values are stored, so only ever append.
    public static final int ACT_NONE = 0, ACT_POWER_SAVER = 1, ACT_BATTERY_USAGE = 2,
            ACT_ALARMS = 3, ACT_CALENDAR = 4, ACT_SETTINGS = 5, ACT_FLASHLIGHT = 6, ACT_WIFI = 7,
            ACT_BLUETOOTH = 8, ACT_DND = 9, ACT_ROTATE = 10, ACT_DARK = 11, ACT_SOUND = 12,
            ACT_MEDIA = 13, ACT_LOCK = 14, ACT_QS = 15, ACT_NOTIFS = 16, ACT_CAMERA = 17,
            ACT_TIMER = 18, ACT_CALCULATOR = 19, ACT_INTERNET = 20, ACT_APP = 21, ACT_FINDER = 22;
    public static final int ACT_MAX = 22;
    public static final int SLOTS = 18; // 6 items x 3 gestures
    /** Short action names (index = ACT_*), shared by the app and the status bar tips. */
    public static final String[] ACT_NAMES = {"Nothing", "Power saving", "Battery usage", "Alarms", "Calendar",
            "Settings", "Flashlight", "Wi-Fi", "Bluetooth", "Do not disturb", "Auto rotate", "Dark mode",
            "Sound → Vibrate → Mute", "Play / pause music", "Lock screen", "Quick settings panel",
            "Notification panel", "Camera", "Timer", "Calculator", "Internet panel", "An app…", "Samsung Finder"};
    /** Gesture slots: item x gesture. Index = item * 3 + gesture. */
    public static final String[] ITEMS = {"battery", "clock", "temp", "camera hole", "wifi", "mobile signal"};
    public static final String[] GESTURES = {"Tap", "Double-tap", "Long-press"};

    // ---- temperature ----
    public boolean tempEnabled = true;
    public int sensor = SENSOR_SKIN;
    public int intervalSec = 15;
    public boolean decimal = false;
    public int suffix = SUFFIX_NONE;
    public int showAbove = 0;          // 0 = always show
    public int warnAt = 42;            // 0 = off
    public int warnColor = 0xFFFF9800; // orange

    // ---- clock ----
    public int clockMode = CLOCK_STOCK;
    public int hourMode = HOUR_SYSTEM;
    public boolean leadingZero = false;
    public boolean seconds = false;
    public int ampm = AMPM_SMALL;
    public boolean ampmLower = false;
    public int weekday = WEEKDAY_NONE;
    public int date = 0;               // index into ClockFormat.DATE_PATTERNS, 0 = none
    public int year = 0;               // 0 none, 1 yy, 2 yyyy
    public boolean dateAfterTime = false;
    public int dateSizePct = 100;
    public String pattern = "EEE h:mm a";
    public boolean dateFirst = false;  // "1 Sun" instead of "Sun 1"
    public int gapDayDate = 100;       // space between day and date, % of a normal space
    public int gapDateTime = 100;      // space between day/date and time, % of a normal space

    // ---- look & position ----
    public int labelPos = POS_AUTO;
    public int gapDp = 4;
    public int sizePct = 100;

    // ---- positions: where each movable item sits (see ZoneLayout) ----
    public ZoneLayout.Plan plan = new ZoneLayout.Plan();
    /** Show the day/date as its own item (only with the Custom clock) so it can be placed on its own. */
    public boolean dateSeparate = false;
    /** Margin for the left / right edge zones in dp; -1 = keep Samsung's own side padding (curved screens can go tighter). */
    public int edgeDp = 4;          // manual value (dp), used when edgeMode == 2
    /** Space between neighbouring status bar items in dp; -1 = Samsung's own. */
    public int itemGap = -1;
    public int edgeMode = 0;
    /** Most notification icons shown on the left; 0 = Samsung's own limit. */
    public int notifMax = 0;
    /** Most apps that can be kept open (locked) in Recents; 0 = Samsung's limit (3). */
    public int lockMax = 0;
    public static final String LOCK_SETTING = "sbmods_lock_max";
    /** Double-tap an empty spot of the status bar to turn the screen off. */
    public boolean dtSleep = false;
    /** Show one connection icon: while Wi-Fi is connected, Samsung's separate mobile-signal icon is hidden. */
    public boolean mergeNet = false;        // 0 Samsung's padding, 1 tight (8 dp), 2 manual

    /** Side margin in dp, or -1 to leave Samsung's own padding alone. */
    public int edgeEff() { return edgeMode == 0 ? -1 : edgeMode == 1 ? 8 : edgeDp; }

    // ---- tap actions ----
    public int batteryTap = ACT_POWER_SAVER;
    public int batteryLong = ACT_BATTERY_USAGE;
    public int clockTap = ACT_NONE;
    public int clockLong = ACT_NONE;
    public int tempTap = ACT_NONE;
    public int tempLong = ACT_NONE;
    public int batteryDouble = ACT_NONE, clockDouble = ACT_NONE, tempDouble = ACT_NONE;
    /** Punch-hole (front camera cutout) gestures. Tap opens Samsung Finder by default. */
    public int cameraTap = ACT_FINDER, cameraDouble = ACT_NONE, cameraLong = ACT_NONE;
    /** Slots 12-14 Wi-Fi icon, 15-17 mobile signal icon (tap, double-tap, long-press). */
    public int[] netActs = new int[6];
    /** Package to open for ACT_APP, per slot (item * 3 + gesture). */
    public String[] apps = new String[SLOTS];
    public boolean tapHaptic = true;

    // ---- back to last app ----
    public boolean backEnabled = false;
    public int backChars = 7;
    /** 0 classic translucent pill with a chevron, 1 One UI live-activity capsule (app icon + app colour). */
    public int backStyle = 1;
    /** Seconds until the back pill dulls to plain text ("been too long to go back"); 0 = never. */
    public int backFadeSec = 0;
    /** While a live activity (music...) shows: 0 keep both, 1 back button shrinks to plain text, 2 back button hides, 3 stack: flick / long-press the live activity to swap in the back button, 4 merge: a small back tab joined to the live activity. */
    public int backLive = 0;
    public int backSizePct = 100;

    // ---- burn-in protection ----
    public boolean pixelShift = true;

    // ---- reminders ----
    public int tipsMode = 1; // 0 off, 1 sometimes, 2 often

    // ---- battery ----
    public boolean pauseScreenOff = true;

    public Config sanitize() {
        if (sensor != SENSOR_CPU && sensor != SENSOR_BATTERY && sensor != SENSOR_SKIN) sensor = SENSOR_SKIN;
        intervalSec = clamp(intervalSec, 1, 120);
        suffix = clamp(suffix, 0, 2);
        showAbove = clamp(showAbove, 0, 80);
        warnAt = clamp(warnAt, 0, 80);
        clockMode = clamp(clockMode, 0, 2);
        hourMode = clamp(hourMode, 0, 2);
        ampm = clamp(ampm, 0, 2);
        weekday = clamp(weekday, 0, 3);
        gapDayDate = clamp(gapDayDate, 0, 300); gapDateTime = clamp(gapDateTime, 0, 300);
        date = clamp(date, 0, ClockFormat.DATE_PATTERNS.length - 1);
        year = clamp(year, 0, 2);
        dateSizePct = clamp(dateSizePct, 50, 120);
        if (pattern == null || pattern.length() > 64) pattern = "EEE h:mm a";
        labelPos = clamp(labelPos, 0, 2);
        gapDp = clamp(gapDp, 0, 24);
        sizePct = clamp(sizePct, 50, 150);
        for (int i = 0; i < SLOTS; i++) setAction(i, clamp(action(i), 0, ACT_MAX));
        if (apps == null || apps.length != SLOTS) apps = new String[SLOTS];
        backChars = clamp(backChars, 3, 14);
        if (plan == null) plan = new ZoneLayout.Plan();
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) {
            plan.mode[i] = clamp(plan.mode[i], 0, 2);
            plan.right[i] = clamp(plan.right[i], 0, 3);
            plan.off[i] = clamp(plan.off[i], -400, 400);
            plan.to[i] = clamp(plan.to[i], 0, ZoneLayout.ELEMENTS - 1);
            plan.side[i] = clamp(plan.side[i], 0, 1);
            if (plan.mode[i] == ZoneLayout.LINK && plan.to[i] == i) plan.mode[i] = ZoneLayout.STOCK;
        }
        edgeDp = clamp(edgeDp, 0, 24);
        edgeMode = clamp(edgeMode, 0, 2);
        itemGap = clamp(itemGap, -1, 16);
        notifMax = clamp(notifMax, 0, 12);
        lockMax = clamp(lockMax, 0, 12);
        backFadeSec = clamp(backFadeSec, 0, 120);
        backSizePct = clamp(backSizePct, 70, 140);
        backStyle = clamp(backStyle, 0, 1);
        backLive = clamp(backLive, 0, 4);
        tipsMode = clamp(tipsMode, 0, 2);
        return this;
    }

    /** Action for a slot (item * 3 + gesture; gesture 0 tap, 1 double, 2 long). */
    public int action(int slot) {
        switch (slot) {
            case 0: return batteryTap; case 1: return batteryDouble; case 2: return batteryLong;
            case 3: return clockTap; case 4: return clockDouble; case 5: return clockLong;
            case 6: return tempTap; case 7: return tempDouble; case 8: return tempLong;
            case 9: return cameraTap; case 10: return cameraDouble; case 11: return cameraLong;
            default: return slot >= 12 && slot < 18 ? netActs[slot - 12] : 0;
        }
    }

    public void setAction(int slot, int v) {
        switch (slot) {
            case 0: batteryTap = v; break; case 1: batteryDouble = v; break; case 2: batteryLong = v; break;
            case 3: clockTap = v; break; case 4: clockDouble = v; break; case 5: clockLong = v; break;
            case 6: tempTap = v; break; case 7: tempDouble = v; break; case 8: tempLong = v; break;
            case 9: cameraTap = v; break; case 10: cameraDouble = v; break; case 11: cameraLong = v; break;
            default: if (slot >= 12 && slot < 18) netActs[slot - 12] = v;
        }
    }

    public String app(int slot) { return apps[slot] == null ? "" : apps[slot]; }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    public Config copy() { return fromBundle(toBundle()); }

    // ------------------------------------------------------------ persistence

    /** Generic key/value sink so Bundle and SharedPreferences share one field list. */
    private interface Sink {
        void b(String k, boolean v); void i(String k, int v); void s(String k, String v);
    }

    private interface Source {
        boolean b(String k, boolean d); int i(String k, int d); String s(String k, String d);
    }

    private void write(Sink o) {
        o.b("tempEnabled", tempEnabled); o.i("sensor", sensor); o.i("intervalSec", intervalSec);
        o.b("decimal", decimal); o.i("suffix", suffix); o.i("showAbove", showAbove);
        o.i("warnAt", warnAt); o.i("warnColor", warnColor);
        o.i("clockMode", clockMode); o.i("hourMode", hourMode); o.b("leadingZero", leadingZero);
        o.b("seconds", seconds); o.i("ampm", ampm); o.b("ampmLower", ampmLower);
        o.i("weekday", weekday); o.i("date", date); o.i("year", year);
        o.b("dateAfterTime", dateAfterTime); o.i("dateSizePct", dateSizePct); o.s("pattern", pattern);
        o.i("labelPos", labelPos); o.i("gapDp", gapDp); o.i("sizePct", sizePct);
        o.b("pauseScreenOff", pauseScreenOff);
        o.i("batteryTap", batteryTap); o.i("batteryLong", batteryLong); o.i("clockTap", clockTap);
        o.i("clockLong", clockLong); o.i("tempTap", tempTap); o.i("tempLong", tempLong);
        o.b("tapHaptic", tapHaptic);
        o.i("batteryDouble", batteryDouble); o.i("clockDouble", clockDouble); o.i("tempDouble", tempDouble);
        o.i("backLive", backLive); o.i("edgeDp", edgeDp); o.i("edgeMode", edgeMode); o.i("notifMax", notifMax); o.i("lockMax", lockMax); o.b("dtSleep", dtSleep); o.b("mergeNet", mergeNet); o.i("itemGap", itemGap); o.i("backFadeSec", backFadeSec); o.i("backSizePct", backSizePct); o.i("backStyle", backStyle);
        o.b("dateSeparate", dateSeparate);
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) { o.i("pm" + i, plan.mode[i]); o.i("pr" + i, plan.right[i]); o.i("po" + i, plan.off[i]); o.i("pt" + i, plan.to[i]); o.i("ps" + i, plan.side[i]); }
        o.i("cameraTap", cameraTap); o.i("cameraDouble", cameraDouble); o.i("cameraLong", cameraLong);
        for (int i = 0; i < 6; i++) o.i("net" + i, netActs[i]);
        for (int i = 0; i < SLOTS; i++) o.s("app" + i, app(i));
        o.b("pixelShift", pixelShift); o.i("tipsMode", tipsMode);
        o.b("backEnabled", backEnabled); o.i("backChars", backChars);
        o.b("dateFirst", dateFirst); o.i("gapDayDate", gapDayDate); o.i("gapDateTime", gapDateTime);
    }

    private Config read(Source in) {
        tempEnabled = in.b("tempEnabled", tempEnabled); sensor = in.i("sensor", sensor);
        intervalSec = in.i("intervalSec", intervalSec); decimal = in.b("decimal", decimal);
        suffix = in.i("suffix", suffix); showAbove = in.i("showAbove", showAbove);
        warnAt = in.i("warnAt", warnAt); warnColor = in.i("warnColor", warnColor);
        clockMode = in.i("clockMode", clockMode); hourMode = in.i("hourMode", hourMode);
        leadingZero = in.b("leadingZero", leadingZero); seconds = in.b("seconds", seconds);
        ampm = in.i("ampm", ampm); ampmLower = in.b("ampmLower", ampmLower);
        weekday = in.i("weekday", weekday); date = in.i("date", date); year = in.i("year", year);
        dateAfterTime = in.b("dateAfterTime", dateAfterTime);
        dateSizePct = in.i("dateSizePct", dateSizePct); pattern = in.s("pattern", pattern);
        labelPos = in.i("labelPos", labelPos); gapDp = in.i("gapDp", gapDp);
        sizePct = in.i("sizePct", sizePct); pauseScreenOff = in.b("pauseScreenOff", pauseScreenOff);
        batteryTap = in.i("batteryTap", batteryTap); batteryLong = in.i("batteryLong", batteryLong);
        clockTap = in.i("clockTap", clockTap); clockLong = in.i("clockLong", clockLong);
        tempTap = in.i("tempTap", tempTap); tempLong = in.i("tempLong", tempLong);
        tapHaptic = in.b("tapHaptic", tapHaptic);
        batteryDouble = in.i("batteryDouble", batteryDouble); clockDouble = in.i("clockDouble", clockDouble);
        tempDouble = in.i("tempDouble", tempDouble);
        backLive = in.i("backLive", backLive); edgeDp = in.i("edgeDp", edgeDp); edgeMode = in.i("edgeMode", edgeMode); notifMax = in.i("notifMax", notifMax); lockMax = in.i("lockMax", lockMax); dtSleep = in.b("dtSleep", dtSleep); mergeNet = in.b("mergeNet", mergeNet); itemGap = in.i("itemGap", itemGap);
        backFadeSec = in.i("backFadeSec", backFadeSec); backSizePct = in.i("backSizePct", backSizePct); backStyle = in.i("backStyle", backStyle);
        dateSeparate = in.b("dateSeparate", dateSeparate);
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) {
            plan.mode[i] = in.i("pm" + i, plan.mode[i]); plan.right[i] = in.i("pr" + i, plan.right[i]); plan.off[i] = in.i("po" + i, plan.off[i]);
            plan.to[i] = in.i("pt" + i, plan.to[i]); plan.side[i] = in.i("ps" + i, plan.side[i]);
        }
        cameraTap = in.i("cameraTap", cameraTap); cameraDouble = in.i("cameraDouble", cameraDouble);
        cameraLong = in.i("cameraLong", cameraLong);
        for (int i = 0; i < 6; i++) netActs[i] = in.i("net" + i, netActs[i]);
        for (int i = 0; i < SLOTS; i++) apps[i] = in.s("app" + i, app(i));
        pixelShift = in.b("pixelShift", pixelShift); tipsMode = in.i("tipsMode", tipsMode);
        backEnabled = in.b("backEnabled", backEnabled); backChars = in.i("backChars", backChars);
        dateFirst = in.b("dateFirst", dateFirst); gapDayDate = in.i("gapDayDate", gapDayDate);
        gapDateTime = in.i("gapDateTime", gapDateTime);
        return sanitize();
    }

    public Bundle toBundle() {
        final Bundle b = new Bundle();
        write(new Sink() {
            public void b(String k, boolean v) { b.putBoolean(k, v); }
            public void i(String k, int v) { b.putInt(k, v); }
            public void s(String k, String v) { b.putString(k, v); }
        });
        return b;
    }

    public static Config fromBundle(final Bundle b) {
        Config c = new Config();
        if (b == null) return c;
        return c.read(new Source() {
            public boolean b(String k, boolean d) { return b.getBoolean(k, d); }
            public int i(String k, int d) { return b.getInt(k, d); }
            public String s(String k, String d) { return b.getString(k, d); }
        });
    }

    public void save(SharedPreferences p) {
        final SharedPreferences.Editor e = p.edit();
        write(new Sink() {
            public void b(String k, boolean v) { e.putBoolean(k, v); }
            public void i(String k, int v) { e.putInt(k, v); }
            public void s(String k, String v) { e.putString(k, v); }
        });
        e.apply();
    }

    public static Config load(final SharedPreferences p) {
        return new Config().read(new Source() {
            public boolean b(String k, boolean d) { return p.getBoolean(k, d); }
            public int i(String k, int d) { return p.getInt(k, d); }
            public String s(String k, String d) { return p.getString(k, d); }
        });
    }
}
