// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dev.gitesh.sbmods.ClockFormat;
import dev.gitesh.sbmods.Config;
import dev.gitesh.sbmods.Helper;
import dev.gitesh.sbmods.ZoneLayout;
import dev.gitesh.sbmods.items.TempItem;

/**
 * The settings screen.
 *
 *   header: name + status pill
 *   live preview (pinned, always visible)
 *   tabs: Layout · Clock · Temperature · Back button · Gestures · More
 *   the settings of the selected tab (scrolls)
 *
 * Rules: one tab = one topic; every row says what it does in plain words, with at most one short
 * line of help; the preview shows every change as you make it; changes are saved and sent to the
 * status bar instantly.
 */
public final class SettingsActivity extends Activity {
    // ------------------------------------------------------------------ wording

    /** Action names, indexed by Config.ACT_*. */
    private static final String[] ACT_LABEL = new String[Config.ACT_MAX + 1];
    /** How actions are grouped in the picker: group title, then ids. */
    private static final Object[][] ACT_GROUPS = {
            {"Turn on or off", new int[]{Config.ACT_POWER_SAVER, Config.ACT_FLASHLIGHT, Config.ACT_WIFI,
                    Config.ACT_BLUETOOTH, Config.ACT_DND, Config.ACT_ROTATE, Config.ACT_DARK, Config.ACT_SOUND}},
            {"Do something", new int[]{Config.ACT_MEDIA, Config.ACT_LOCK, Config.ACT_CLOSE_APPS, Config.ACT_QS, Config.ACT_NOTIFS}},
            {"Open", new int[]{Config.ACT_FINDER, Config.ACT_BATTERY_USAGE, Config.ACT_INTERNET, Config.ACT_CAMERA,
                    Config.ACT_ALARMS, Config.ACT_TIMER, Config.ACT_CALENDAR, Config.ACT_CALCULATOR,
                    Config.ACT_SETTINGS, Config.ACT_APP}},
    };
    static {
        ACT_LABEL[Config.ACT_NONE] = "Do nothing";
        ACT_LABEL[Config.ACT_POWER_SAVER] = "Power saving mode";
        ACT_LABEL[Config.ACT_BATTERY_USAGE] = "Battery usage";
        ACT_LABEL[Config.ACT_ALARMS] = "Alarms";
        ACT_LABEL[Config.ACT_CALENDAR] = "Calendar";
        ACT_LABEL[Config.ACT_SETTINGS] = "Settings";
        ACT_LABEL[Config.ACT_FLASHLIGHT] = "Flashlight";
        ACT_LABEL[Config.ACT_WIFI] = "Wi-Fi";
        ACT_LABEL[Config.ACT_BLUETOOTH] = "Bluetooth";
        ACT_LABEL[Config.ACT_DND] = "Do not disturb";
        ACT_LABEL[Config.ACT_ROTATE] = "Auto-rotate";
        ACT_LABEL[Config.ACT_DARK] = "Dark mode";
        ACT_LABEL[Config.ACT_SOUND] = "Sound / vibrate / mute (cycle)";
        ACT_LABEL[Config.ACT_MEDIA] = "Play or pause music";
        ACT_LABEL[Config.ACT_LOCK] = "Lock the screen";
        ACT_LABEL[Config.ACT_QS] = "Quick settings panel";
        ACT_LABEL[Config.ACT_NOTIFS] = "Notification panel";
        ACT_LABEL[Config.ACT_CAMERA] = "Camera";
        ACT_LABEL[Config.ACT_TIMER] = "Timer";
        ACT_LABEL[Config.ACT_CALCULATOR] = "Calculator";
        ACT_LABEL[Config.ACT_INTERNET] = "Internet panel";
        ACT_LABEL[Config.ACT_APP] = "Open an app…";
        ACT_LABEL[Config.ACT_FINDER] = "Samsung Finder (search)";
        ACT_LABEL[Config.ACT_CLOSE_APPS] = "Close background apps (needs the helper)";
    }
    private static final String[] ITEM_TITLES = {"Battery icon", "Clock", "Temperature", "Front camera hole", "Wi-Fi icon", "Mobile signal icon"};
    private static final String[] GESTURE_TITLES = {"Tap", "Double-tap", "Long-press"};
    private static final String[] TABS = {"Layout", "Clock", "Temperature", "Back button", "Gestures", "More"};
    private static final int T_LAYOUT = 0, T_CLOCK = 1, T_TEMP = 2, T_BACK = 3, T_GESTURES = 4, T_MORE = 5;
    private static final int[] COLORS = {0xFFFF9800, 0xFFF44336, 0xFFFFEB3B, 0xFFE91E63, 0xFF00BCD4, 0xFF4CAF50};

    // ------------------------------------------------------------------ state

    private int cBg, cCard, cText, cSub, cAccent, cAccentSoft, cDivider, cOk, cErr, cWarn, cField;

    private SharedPreferences prefs;
    private Config cfg;
    private int tab;
    private ScrollView scroll;
    private LinearLayout page, cur, previewHolder, tabRow;
    private TextView statusPill;
    private PositionView posView;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean gotPong, simulateHot;
    private float liveTemp = Float.NaN;
    private int statusState = 0;                 // 0 waiting / needs attention, 1 ok, 2 not running
    private String statusShort = "Checking…", statusDetail = "Checking the status bar…", modVersion = "";
    private String clockSide = "left";

    private final BroadcastReceiver pong = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            gotPong = true;
            float t = i.getFloatExtra("tempValue", Float.NaN);
            if (!Float.isNaN(t)) liveTemp = t;
            showStatus(i);
        }
    };

    private final Runnable previewTick = new Runnable() {
        @Override public void run() {
            updatePreview();
            h.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000));
        }
    };

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.File(getCacheDir(), "crash.txt"))) {
                ex.printStackTrace(w);
            } catch (Exception ignored) {}
            if (prev != null) prev.uncaughtException(th, ex);
        });
        if (getActionBar() != null) getActionBar().hide();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        cBg = night ? 0xFF0F1012 : 0xFFF2F3F5;
        cCard = night ? 0xFF1C1E22 : 0xFFFFFFFF;
        cText = night ? 0xFFECEDEF : 0xFF16181B;
        cSub = night ? 0xFF9BA1A9 : 0xFF656B74;
        cAccent = night ? 0xFF7AA5FF : 0xFF2F6BEB;
        cAccentSoft = night ? 0x337AA5FF : 0x1A2F6BEB;
        cDivider = night ? 0xFF2B2E34 : 0xFFE6E8EB;
        cOk = night ? 0xFF4CC38A : 0xFF1E9E5A;
        cErr = night ? 0xFFFF7070 : 0xFFD33A3A;
        cWarn = night ? 0xFFFFC266 : 0xFFC77700;
        cField = night ? 0xFF26292E : 0xFFEEF0F3;

        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getDecorView().setBackgroundColor(cBg);
        WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) ic.setSystemBarsAppearance(night ? 0 : light, light);

        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        cfg = Config.load(prefs);
        tab = Math.min(prefs.getInt("tab", T_LAYOUT), TABS.length - 1);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        root.setFitsSystemWindows(true);
        root.addView(buildHeader(), new LinearLayout.LayoutParams(-1, -2));
        previewHolder = new LinearLayout(this);
        previewHolder.setOrientation(LinearLayout.VERTICAL);
        root.addView(previewHolder, new LinearLayout.LayoutParams(-1, -2));
        root.addView(buildTabBar(), new LinearLayout.LayoutParams(-1, -2));
        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(4), dp(16), dp(40));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        buildPreviewArea();
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(pong, new IntentFilter(Config.ACTION_PONG), Context.RECEIVER_EXPORTED);
        ping();
        h.post(previewTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(previewTick);
        try { unregisterReceiver(pong); } catch (Exception ignored) {}
    }

    // ================================================================ frame: header, preview, tabs

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(10), dp(16), dp(6));
        TextView title = new TextView(this);
        title.setText("StatusBar Mods");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(cText);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        statusPill = new TextView(this);
        statusPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusPill.setPadding(dp(12), dp(7), dp(12), dp(7));
        statusPill.setOnClickListener(v -> statusDialog());
        row.addView(statusPill);
        refreshStatusPill();
        return row;
    }

    /** The pinned preview. Rebuilt when the settings object is replaced (reset all). */
    private void buildPreviewArea() {
        previewHolder.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(cCard, dp(18), 0, 0));
        card.setPadding(dp(12), dp(10), dp(12), dp(8));
        float d = getResources().getDisplayMetrics().density;
        android.view.WindowMetrics wm = getWindowManager().getMaximumWindowMetrics();
        float screenW = Math.min(wm.getBounds().width(), wm.getBounds().height()) / d;
        float holeL = screenW / 2 - 5, holeR = screenW / 2 + 5;
        android.view.DisplayCutout dc = wm.getWindowInsets().getDisplayCutout();
        if (dc != null) for (android.graphics.Rect r : dc.getBoundingRects()) {
            if (r.top > 0 || r.isEmpty() || r.width() > wm.getBounds().width() / 2) continue;
            holeL = r.left / d; holeR = r.right / d;
            break;
        }
        posView = new PositionView(this, cfg, new PositionView.Host() {
            @Override public void changed() { apply(); if (tab == T_LAYOUT) refresh(); }
            @Override public void tapped(int element) {   // tap (no drag): the same choices as a list - easier than dragging, and works with TalkBack
                if (elementOn(element)) zonePicker(element);
                else switchTab(element == ZoneLayout.TEMP ? T_TEMP : element == ZoneLayout.DATE ? T_CLOCK : T_BACK);
            }
        }, screenW, holeL, holeR, cAccent, cText, cSub, cField);
        card.addView(posView, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(16), dp(4), dp(16), dp(4));
        previewHolder.addView(card, lp);
        updatePreview();
    }

    private View buildTabBar() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        tabRow = new LinearLayout(this);
        tabRow.setPadding(dp(16), dp(8), dp(16), dp(8));
        hs.addView(tabRow);
        fillTabs();
        return hs;
    }

    private void fillTabs() {
        tabRow.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            final int at = i;
            boolean on = i == tab;
            TextView t = new TextView(this);
            t.setText(TABS[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setSingleLine(true);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setTextColor(on ? Color.WHITE : cText);
            t.setBackground(round(on ? cAccent : cCard, dp(18), 0, 0));
            t.setPadding(dp(16), 0, dp(16), 0);
            t.setOnClickListener(v -> switchTab(at));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.setMarginEnd(dp(8));
            tabRow.addView(t, lp);
        }
    }

    private void switchTab(int t) {
        if (t == tab) return;
        tab = t;
        prefs.edit().putInt("tab", t).apply();
        fillTabs();
        build();
        scroll.scrollTo(0, 0);
        scroll.post(() -> scroll.scrollTo(0, 0));   // again after the new tab has been laid out
    }

    // ================================================================ content

    private void refresh() {
        final int y = scroll.getScrollY();
        build();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void build() {
        page.removeAllViews();
        cur = page;
        switch (tab) {
            case T_CLOCK: buildClock(); break;
            case T_TEMP: buildTemp(); break;
            case T_BACK: buildBack(); break;
            case T_GESTURES: buildGestures(); break;
            case T_MORE: buildMore(); break;
            default: buildLayout(); break;
        }
    }

    // ---------------------------------------------------------------- Layout tab

    private void buildLayout() {
        group("How to arrange the bar");
        infoRow("Drag and drop in the preview",
                "Press an item in the preview at the top and drag it along the bar. It settles at the nearest edge, beside the camera, or next to another item, and stays stuck to it. "
                + "Drop it between two items to slot it in; everything re-spaces itself and Samsung's own icons move out of the way.\n\n"
                + "You can move the clock, the date (turn on \u201cDate as its own item\u201d in the Clock tab), the temperature, the battery, the network speed and the back button. "
                + "Drop an item on the grey tray to send it back to Samsung's place.");
        endGroup(null);

        if (anyMoved()) {
            group("Positions");
            actionRow("Put everything back", "Return all items to Samsung's own places", cAccent, v -> {
                cfg.plan = new ZoneLayout.Plan();
                apply();
                refresh();
            });
            endGroup(null);
        }

        group("Notification icons");
        slider("Icons before the “•” dot", 0, 12, cfg.notifMax, v -> v == 0 ? "Samsung's (3)" : v + " icons",
                v -> cfg.notifMax = v, 0);
        endGroup("Samsung shows 3 app icons on the left, then a dot for the rest. Raise this to see more. It applies the next time the status bar restarts, and there must be room: a live activity takes some of it.");

        buildStatusIcons();

        group("Recents: kept-open apps");
        slider("Apps you can keep open", 0, 12, cfg.lockMax, v -> v == 0 ? "Samsung's (3)" : v + " apps",
                v -> cfg.lockMax = v, 0);
        endGroup("In the recent apps screen, tap an app's lock to keep it open. Samsung allows 3. Raise this to keep more. Apps you keep open use memory, so too many can slow the phone.");

        group("Connection icon");
        switchRow("One icon for Wi-Fi and mobile data", "While Wi-Fi is connected only the Wi-Fi icon shows. When it drops, the mobile signal icon comes back by itself.",
                cfg.mergeNet, v -> { cfg.mergeNet = v; apply(); });
        endGroup(null);

        group("Space");
        segmentedBlock("Space at the screen edges", new String[]{"Samsung's", "Tight", "Custom"}, cfg.edgeMode,
                v -> { cfg.edgeMode = v; apply(); refresh(); });
        if (cfg.edgeMode == 2) {
            divider();
            slider("Distance from the screen edge", 0, 24, cfg.edgeDp, v -> v + " dp", v -> { cfg.edgeDp = v; posView.invalidate(); }, 4);
        }
        divider();
        slider("Space between items", -1, 16, cfg.itemGap, v -> v < 0 ? "Samsung's" : v + " dp",
                v -> { cfg.itemGap = v; posView.invalidate(); }, -1);
        endGroup("Less space leaves more room for icons. “Tight” and “Custom” let the status bar reach the curved edges of your screen.");
    }

    // ---- Status icons: label -> status bar slots (all added / removed together). Easy to extend: add a row.
    private static final String[][] ICONS = {
        {"Alarm", "alarm_clock"},
        {"NFC", "nfc", "nfc_on"},
        {"Bluetooth", "bluetooth", "bluetooth_connected"},
        {"Sound mode (vibrate / mute)", "volume", "zen"},
        {"Do not disturb", "dnd"},
        {"Location", "location"},
        {"VoLTE / calling icons", "ims_volte", "ims_volte2", "volte", "vowifi", "ims_vowifi"},
        {"Headphones", "headset"},
        {"Auto rotate", "rotate"},
        {"Airplane mode", "airplane"},
        {"Hotspot", "hotspot"},
        {"Data saver", "data_saver"},
        {"VPN", "vpn"},
        {"Screen cast", "cast"},
        {"Power saving / battery leaf", "power_saver"},
    };

    private String hiddenIcons;          // cached list of hidden icons; null = not read yet
    private boolean hiddenIconsNoRoot;   // reading failed: root unavailable
    private boolean hiddenIconsLoading;

    private void loadHiddenIcons() {
        hiddenIconsLoading = true;
        new Thread(() -> {
            String v = Helper.readSecure("icon_blacklist");
            runOnUiThread(() -> {
                hiddenIconsLoading = false;
                hiddenIconsNoRoot = v == null;
                hiddenIcons = v;
                if (tab == T_LAYOUT) refresh();
            });
        }).start();
    }

    /** Adds (hide) or removes (show) the slots in a comma-separated list; every other entry keeps its place. Duplicates are dropped. */
    static String mergeHidden(String current, String[] slots, boolean hide) {
        List<String> out = new ArrayList<>();
        if (current != null && !current.trim().equals("null")) {
            for (String e : current.split(",")) {
                e = e.trim();
                if (e.isEmpty() || out.contains(e)) continue;
                boolean ours = false;
                for (String sl : slots) if (sl.equals(e)) ours = true;
                if (ours && !hide) continue;
                out.add(e);
            }
        }
        if (hide) for (String sl : slots) if (!out.contains(sl)) out.add(sl);
        StringBuilder sb = new StringBuilder();
        for (String e : out) { if (sb.length() > 0) sb.append(','); sb.append(e); }
        return sb.toString();
    }

    private void buildStatusIcons() {
        if (hiddenIcons == null && !hiddenIconsNoRoot && !hiddenIconsLoading) loadHiddenIcons();
        group("Status icons");
        if (hiddenIcons == null) {
            infoRow(hiddenIconsNoRoot ? "Needs root to read which icons are hidden" : "Checking…", null);
        } else {
            final String cur0 = hiddenIcons;
            List<String> have = new ArrayList<>();
            for (String e : cur0.split(",")) have.add(e.trim());
            for (int i = 0; i < ICONS.length; i++) {
                if (i > 0) divider();
                final String[] slots = java.util.Arrays.copyOfRange(ICONS[i], 1, ICONS[i].length);
                boolean shown = true;
                for (String sl : slots) if (have.contains(sl)) shown = false;
                switchRow(ICONS[i][0], null, shown, v -> {
                    final String next = mergeHidden(hiddenIcons, slots, !v);
                    new Thread(() -> {
                        boolean ok = Helper.putSecure("icon_blacklist", next);
                        runOnUiThread(() -> {
                            if (ok) hiddenIcons = next;
                            else Toast.makeText(this, "Couldn't change that \u2013 is root allowed?", Toast.LENGTH_LONG).show();
                            refresh();
                        });
                    }).start();
                });
            }
        }
        endGroup("Hidden icons still work \u2013 only the picture in the status bar goes away. Changes show up within a few seconds.");
    }

    private boolean elementOn(int element) {
        return !((element == ZoneLayout.TEMP && !cfg.tempEnabled) || (element == ZoneLayout.BACK && !cfg.backEnabled)
                || (element == ZoneLayout.DATE && !cfg.dateSeparate));
    }

    private boolean anyMoved() {
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) if (cfg.plan.mode[i] != ZoneLayout.STOCK) return true;
        return false;
    }

    /** The same choice as dragging, as a list. */
    private void zonePicker(int element) {
        String what = ZoneLayout.ELEMENT_NAMES[element].toLowerCase(Locale.ROOT);
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> codes = new java.util.ArrayList<>();
        names.add("Samsung's place"); codes.add(0);
        names.add("Far left"); codes.add(1);
        names.add("Left of the camera"); codes.add(2);
        names.add("Right of the camera"); codes.add(3);
        names.add("Far right"); codes.add(4);
        for (int j = 0; j < ZoneLayout.ELEMENTS; j++) {
            if (!posView.canLinkTo(element, j)) continue;
            String o = ZoneLayout.ELEMENT_NAMES[j].toLowerCase(Locale.ROOT);
            names.add("Left of the " + o); codes.add(10 + 2 * j);
            names.add("Right of the " + o); codes.add(11 + 2 * j);
        }
        new AlertDialog.Builder(this).setTitle("Where should the " + what + " go?")
                .setItems(names.toArray(new String[0]), (dlg, which) -> {
                    posView.place(element, codes.get(which));
                }).setNegativeButton("Cancel", null).show();
    }

    // ---------------------------------------------------------------- Clock tab

    private void buildClock() {
        group("Clock style");
        segmentedBlock("What the clock shows", new String[]{"Samsung's", "Custom", "Pattern"}, cfg.clockMode, v -> {
            cfg.clockMode = v; apply(); refresh();
        });
        endGroup(cfg.clockMode == Config.CLOCK_STOCK
                ? "You're using Samsung's own clock. Choose “Custom” to add seconds, the day or the date, or “Pattern” to write your own format."
                : cfg.clockMode == Config.CLOCK_BUILDER
                ? "“Custom” lets you pick what to show with simple switches."
                : "“Pattern” lets you write the exact format yourself.");

        if (cfg.clockMode == Config.CLOCK_BUILDER) buildClockCustom();
        else if (cfg.clockMode == Config.CLOCK_PATTERN) buildClockPattern();
    }

    private void buildClockCustom() {
        boolean sys24 = DateFormat.is24HourFormat(this);
        boolean amOn = cfg.ampm != Config.AMPM_HIDDEN && !ClockFormat.is24h(cfg, sys24);

        group("Time");
        segmentedBlock("Hour format", new String[]{"Phone's setting", "12-hour", "24-hour"}, cfg.hourMode,
                v -> { cfg.hourMode = v; apply(); refresh(); });
        divider();
        switchRow("Show AM / PM", "Only for the 12-hour format", amOn, v -> {
            if (v) {
                if (cfg.ampm == Config.AMPM_HIDDEN) cfg.ampm = Config.AMPM_SMALL;
                if (ClockFormat.is24h(cfg, sys24)) cfg.hourMode = Config.HOUR_12;
            } else cfg.ampm = Config.AMPM_HIDDEN;
            apply(); refresh();
        });
        if (amOn) {
            segmentedInline(new String[]{"Normal size", "Small"}, cfg.ampm == Config.AMPM_SMALL ? 1 : 0,
                    v -> { cfg.ampm = v == 1 ? Config.AMPM_SMALL : Config.AMPM_NORMAL; apply(); refresh(); });
            switchRow("Lowercase am / pm", "“pm” instead of “PM”", cfg.ampmLower, v -> { cfg.ampmLower = v; apply(); refresh(); });
        }
        divider();
        switchRow("Show seconds", "Updates every second", cfg.seconds, v -> { cfg.seconds = v; apply(); refresh(); });
        divider();
        switchRow("Leading zero", "Shows 09:05 instead of 9:05", cfg.leadingZero, v -> { cfg.leadingZero = v; apply(); refresh(); });
        endGroup(null);

        final int[] dayVals = {Config.WEEKDAY_NONE, Config.WEEKDAY_TWO, Config.WEEKDAY_SHORT, Config.WEEKDAY_FULL};
        boolean hasDay = cfg.weekday != Config.WEEKDAY_NONE, hasDate = cfg.date > 0;
        group("Day and date");
        segmentedBlock("Day of the week", new String[]{"Hide", "Su", "Sun", "Sunday"}, idx(dayVals, cfg.weekday),
                v -> { cfg.weekday = dayVals[v]; apply(); refresh(); });
        divider();
        choiceRow("Date", ClockFormat.DATE_LABELS, cfg.date, v -> { cfg.date = v; apply(); refresh(); });
        if (hasDate) {
            divider();
            segmentedBlock("Year", new String[]{"Hide", "26", "2026"}, cfg.year, v -> { cfg.year = v; apply(); refresh(); });
        }
        if (hasDay && hasDate) {
            divider();
            String dayEx = sample(cfg.weekday, 0), dateEx = sample(-1, cfg.date);
            segmentedBlock("Order", new String[]{dayEx + " " + dateEx, dateEx + " " + dayEx}, cfg.dateFirst ? 1 : 0,
                    v -> { cfg.dateFirst = v == 1; apply(); refresh(); });
        }
        if (hasDay || hasDate) {
            divider();
            switchRow("Date as its own item", "Lets you place the day and date anywhere, apart from the time (Layout tab)", cfg.dateSeparate,
                    v -> { cfg.dateSeparate = v; apply(); refresh(); });
            divider();
            String dd = hasDay ? sample(cfg.weekday, 0) : sample(-1, cfg.date);
            segmentedBlock("Placement", new String[]{dd + "  12:30", "12:30  " + dd}, cfg.dateAfterTime ? 1 : 0,
                    v -> { cfg.dateAfterTime = v == 1; apply(); refresh(); });
            divider();
            slider("Size of day and date", 50, 120, cfg.dateSizePct, v -> v + "%", v -> cfg.dateSizePct = v, 100);
        }
        endGroup(hasDay || hasDate ? null : "Pick a day or a date to show it next to the time.");

        if (hasDay || hasDate) {
            group("Spacing");
            if (hasDay && hasDate) {
                slider("Between day and date", 0, 300, cfg.gapDayDate, v -> v == 0 ? "None" : v + "%", v -> cfg.gapDayDate = v, 100);
                divider();
            }
            slider("Between " + (hasDay && hasDate ? "day / date" : hasDay ? "day" : "date") + " and time", 0, 300, cfg.gapDateTime,
                    v -> v == 0 ? "None" : v + "%", v -> cfg.gapDateTime = v, 100);
            endGroup("100% is a normal space. Set 0 to close the gap completely.");
        }
    }

    private void buildClockPattern() {
        group("Your pattern");
        patternEditor();
        endGroup("The preview above updates as you type.");
        group("AM / PM");
        segmentedBlock("Show AM / PM", new String[]{"Hide", "Normal size", "Small"}, cfg.ampm,
                v -> { cfg.ampm = v; apply(); refresh(); });
        divider();
        switchRow("Lowercase am / pm", "“pm” instead of “PM”", cfg.ampmLower, v -> { cfg.ampmLower = v; apply(); });
        endGroup(null);
    }

    private void patternEditor() {
        LinearLayout box = block("Pattern");
        EditText e = field(cfg.pattern);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setTypeface(Typeface.MONOSPACE);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        TextView err = new TextView(this);
        err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        err.setPadding(0, dp(6), 0, 0);
        box.addView(err);
        TextView help = new TextView(this);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        help.setTextColor(cSub);
        help.setTypeface(Typeface.MONOSPACE);
        help.setPadding(0, dp(8), 0, 0);
        help.setText("h  hour 1-12        H  hour 0-23\n"
                + "mm minutes          ss seconds\n"
                + "a  AM / PM          EEE Sun   EEEE Sunday\n"
                + "d  day 1            dd day 01\n"
                + "MMM Nov             MMMM November\n"
                + "yy 26               yyyy 2026\n"
                + "'text' shows your own text\n\n"
                + "Examples:  EEE d MMM h:mm a\n"
                + "           HH:mm:ss\n"
                + "           '⏰' h:mm");
        box.addView(help);
        e.addTextChangedListener(new TextWatcher() {
            final Runnable push = () -> {
                String p = e.getText().toString();
                String bad = ClockFormat.validate(p);
                err.setText(bad == null ? "✓ Looks good" : "✕ " + bad);
                err.setTextColor(bad == null ? cOk : cErr);
                if (bad == null && !p.equals(cfg.pattern)) { cfg.pattern = p; apply(); }
            };
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { h.removeCallbacks(push); h.postDelayed(push, 400); }
        });
    }

    /** Example text for the current day style (weekdayStyle>=0) or a date style. */
    private String sample(int weekdayStyle, int dateIdx) {
        Config c = new Config();
        c.clockMode = Config.CLOCK_BUILDER;
        c.weekday = weekdayStyle >= 0 ? weekdayStyle : Config.WEEKDAY_NONE;
        c.date = weekdayStyle >= 0 ? 0 : dateIdx;
        c.year = weekdayStyle >= 0 ? 0 : cfg.year;
        c.dateAfterTime = true;
        c.gapDateTime = 100;
        c.ampm = Config.AMPM_HIDDEN;
        c.hourMode = Config.HOUR_24;
        CharSequence full = ClockFormat.format(c, true, new Date(), Locale.getDefault());
        String f = full == null ? "" : full.toString();
        int sp = f.indexOf(' ');
        return sp < 0 ? f : f.substring(sp + 1);
    }

    // ---------------------------------------------------------------- Temperature tab

    private void buildTemp() {
        group("Temperature");
        switchRow("Show the phone's temperature", "A small number next to the clock", cfg.tempEnabled,
                v -> { cfg.tempEnabled = v; apply(); refresh(); });
        endGroup(null);
        if (!cfg.tempEnabled) return;

        int si = cfg.sensor == Config.SENSOR_CPU ? 1 : cfg.sensor == Config.SENSOR_BATTERY ? 2 : 0;
        final int[] sv = {Config.SENSOR_SKIN, Config.SENSOR_CPU, Config.SENSOR_BATTERY};

        group("What it shows");
        choiceRow("Measure the", new String[]{"Phone body (recommended)", "Chip (CPU)", "Battery"}, si,
                v -> { cfg.sensor = sv[v]; apply(); refresh(); });
        divider();
        segmentedBlock("Format", new String[]{"34", "34°", "34°C"}, cfg.suffix, v -> { cfg.suffix = v; apply(); refresh(); });
        divider();
        switchRow("Show one decimal", "34.2 instead of 34", cfg.decimal, v -> { cfg.decimal = v; apply(); refresh(); });
        divider();
        slider("Only show when warmer than", 0, 60, cfg.showAbove, v -> v == 0 ? "Always show" : v + " °C", v -> cfg.showAbove = v, 0);
        endGroup(null);

        group("Heat warning");
        slider("Change colour from", 0, 60, cfg.warnAt, v -> v == 0 ? "Off" : v + " °C", v -> cfg.warnAt = v, 42);
        if (cfg.warnAt > 0) {
            colorDots();
            divider();
            switchRow("Preview the warning", "Shows the preview as if the phone were hot", simulateHot,
                    v -> { simulateHot = v; updatePreview(); });
        }
        endGroup("The number turns this colour when the phone gets that warm.");

        group("Look and position");
        slider("Size", 50, 150, cfg.sizePct, v -> v + "% of the clock", v -> cfg.sizePct = v, 100);
        divider();
        segmentedBlock("Next to the clock", new String[]{"Automatic", "After it", "Before it"}, cfg.labelPos,
                v -> { cfg.labelPos = v; apply(); refresh(); });
        divider();
        slider("Space between temperature and clock", 0, 16, cfg.gapDp, v -> v + " dp", v -> cfg.gapDp = v, 4);
        endGroup("To put the temperature somewhere else, use the Layout tab.");

        group("Battery saving");
        final int[] secs = {1, 2, 5, 10, 15, 30, 60};
        choiceRow("Refresh every", new String[]{"1 second", "2 seconds", "5 seconds", "10 seconds", "15 seconds", "30 seconds", "1 minute"},
                idx(secs, cfg.intervalSec), v -> { cfg.intervalSec = secs[v]; apply(); refresh(); });
        divider();
        switchRow("Pause while the screen is off", "Saves a little battery", cfg.pauseScreenOff,
                v -> { cfg.pauseScreenOff = v; apply(); refresh(); });
        endGroup(null);
    }

    private void colorDots() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(2), dp(16), dp(14));
        for (int c : COLORS) {
            TextView dot = new TextView(this);
            dot.setGravity(Gravity.CENTER);
            dot.setTextColor(Color.BLACK);
            boolean sel = (cfg.warnColor | 0xFF000000) == c;
            if (sel) dot.setText("✓");
            dot.setBackground(round(c, dp(16), sel ? dp(3) : 0, cText));
            dot.setOnClickListener(v -> { cfg.warnColor = c; apply(); refresh(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
            lp.setMarginEnd(dp(10));
            row.addView(dot, lp);
        }
        TextView hex = new TextView(this);
        hex.setText("#");
        hex.setGravity(Gravity.CENTER);
        hex.setTextColor(cAccent);
        hex.setBackground(round(cField, dp(16), 0, 0));
        hex.setOnClickListener(v -> hexDialog());
        row.addView(hex, new LinearLayout.LayoutParams(dp(32), dp(32)));
        cur.addView(row);
    }

    private void hexDialog() {
        EditText e = field(String.format("#%06X", cfg.warnColor & 0xFFFFFF));
        e.setTypeface(Typeface.MONOSPACE);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Your own colour").setMessage("Type a colour code, like #FF9800")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use it", (d, w) -> {
                    try {
                        cfg.warnColor = Color.parseColor(e.getText().toString().trim());
                        apply();
                        refresh();
                    } catch (Exception x) {
                        Toast.makeText(this, "That isn't a colour code. Use the form #RRGGBB.", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    // ---------------------------------------------------------------- Back button tab

    private void buildBack() {
        group("Back button");
        switchRow("Show a back button", "Shows the app you used before. Tap it to jump back to that app.", cfg.backEnabled,
                v -> { cfg.backEnabled = v; apply(); refresh(); });
        endGroup(cfg.backEnabled ? "You can move it with the Layout tab. It stays hidden on the home screen and the lock screen."
                : "It appears at the left of the status bar and only updates when you switch apps, so it costs no battery.");
        if (!cfg.backEnabled) return;

        group("Look");
        segmentedBlock("Style", new String[]{"Capsule", "Classic"}, cfg.backStyle == 1 ? 0 : 1,
                v -> { cfg.backStyle = v == 0 ? 1 : 0; apply(); refresh(); });
        divider();
        slider("Size", 70, 140, cfg.backSizePct, v -> v + "%", v -> cfg.backSizePct = v, 100);
        divider();
        slider("Letters of the app name", 3, 14, cfg.backChars, v -> v + " letters", v -> cfg.backChars = v, 7);
        endGroup(cfg.backStyle == 1 ? "“Capsule” copies One UI's live activity look: the app's icon in a pill of the app's colour."
                : "“Classic” is a simple soft pill with an arrow.");

        group("When it appears");
        segmentedBlock("Fade to plain text after", new String[]{"Never", "5 s", "10 s", "30 s"},
                cfg.backFadeSec <= 0 ? 0 : cfg.backFadeSec <= 5 ? 1 : cfg.backFadeSec <= 10 ? 2 : 3,
                v -> { cfg.backFadeSec = new int[]{0, 5, 10, 30}[v]; apply(); });
        endGroup("The pill melts into dim text after this long, as if it's been too long to go back. It returns in full when you switch apps.");

        group("Live activities");
        choiceRow("While one is showing", new String[]{"Keep both side by side", "Back button as plain text", "Hide the back button",
                "Stack (flick to swap)", "Merge (a small back tab on the live activity)"}, cfg.backLive,
                v -> { cfg.backLive = v; apply(); refresh(); });
        endGroup("Music and other live activities share the left side with this button.\n\n“Merge” fuses a small back tab (the chevron and the previous app's icon, no name) onto the right end of the live activity, so they read as one shape; tap the tab to go back.\n\n“Stack” is the tidy choice, like One UI's own stacked cards: they share one spot, and the back button's edge peeks out underneath. Flick sideways on the live activity (or long-press it) to bring the back button forward. Tap the button to go back, flick it away or double-tap it to return to the live activity.");
    }

    // ---------------------------------------------------------------- Gestures tab

    private void buildGestures() {
        group("Status bar");
        switchRow("Double-tap to turn the screen off", "Double-tap any empty spot in the status bar to lock the screen", cfg.dtSleep,
                v -> { cfg.dtSleep = v; apply(); });
        endGroup("Swiping down still opens the notification panel as usual. Items such as the clock keep their own tap actions below.");
        for (int item = 0; item < ITEM_TITLES.length; item++) {
            group(ITEM_TITLES[item]);
            for (int g = 0; g < 3; g++) {
                if (g > 0) divider();
                gestureRow(item * 3 + g);
            }
            endGroup(item == 3 ? "Tap the front camera hole in the status bar. Samsung Finder opens by default." : null);
        }
        group("Feedback");
        switchRow("Vibrate on tap", "A small buzz when an action runs", cfg.tapHaptic, v -> { cfg.tapHaptic = v; apply(); });
        divider();
        segmentedBlock("Remind me what I set up", new String[]{"Never", "Sometimes", "Often"}, cfg.tipsMode,
                v -> { cfg.tipsMode = v; apply(); });
        divider();
        actionRow("Show a reminder now", "A tip scrolls across the left of the status bar", cAccent,
                v -> send(new Intent(Config.ACTION_TIP)));
        endGroup("Double-tap makes a single tap wait a moment, so only use it if you need it. Nothing opens over the lock screen.");
    }

    private String actionName(int slot) {
        int a = cfg.action(slot);
        if (a == Config.ACT_APP) {
            String label = appLabel(cfg.app(slot));
            return label == null ? "Choose an app" : label;
        }
        return ACT_LABEL[a];
    }

    private String appLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void gestureRow(int slot) {
        int a = cfg.action(slot);
        LinearLayout r = rowShell(v -> actionPicker(slot));
        r.setMinimumHeight(dp(52));
        r.setPadding(dp(16), dp(8), dp(12), dp(8));
        r.addView(twoLines(GESTURE_TITLES[slot % 3], null), new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = new TextView(this);
        val.setText(actionName(slot) + "  ›");
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        val.setTextColor(a == Config.ACT_NONE ? cSub : cAccent);
        val.setMaxWidth(dp(190));
        val.setGravity(Gravity.END);
        r.addView(val);
        if (a != Config.ACT_NONE) {
            TextView tryIt = textButton("Test", v -> send(new Intent(Config.ACTION_TEST)
                    .putExtra("act", cfg.action(slot)).putExtra("app", cfg.app(slot))));
            tryIt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            r.addView(tryIt);
        }
    }

    /** Grouped list of actions in a dialog. */
    private void actionPicker(int slot) {
        AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(8));
        int current = cfg.action(slot);
        LinearLayout saved = cur;
        cur = list;
        pickRow(ACT_LABEL[Config.ACT_NONE], current == Config.ACT_NONE, v -> { pick(slot, Config.ACT_NONE); dlg[0].dismiss(); });
        for (Object[] g : ACT_GROUPS) {
            TextView t = new TextView(this);
            t.setText(((String) g[0]).toUpperCase(Locale.ROOT));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setLetterSpacing(0.08f);
            t.setTextColor(cAccent);
            t.setPadding(dp(24), dp(14), dp(24), dp(4));
            list.addView(t);
            for (int id : (int[]) g[1]) {
                String label = id == Config.ACT_APP && current == id && appLabel(cfg.app(slot)) != null
                        ? "App: " + appLabel(cfg.app(slot)) : ACT_LABEL[id];
                pickRow(label, current == id, v -> {
                    dlg[0].dismiss();
                    if (id == Config.ACT_APP) appPicker(slot); else pick(slot, id);
                });
            }
        }
        cur = saved;
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        String when = GESTURE_TITLES[slot % 3].toLowerCase(Locale.ROOT);
        String what = ITEM_TITLES[slot / 3].toLowerCase(Locale.ROOT);
        dlg[0] = new AlertDialog.Builder(this)
                .setTitle("On " + when + " of the " + what)
                .setView(sv).setNegativeButton("Cancel", null).show();
    }

    private void pickRow(String label, boolean selected, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(24), dp(12), dp(24), dp(12));
        r.setBackground(ripple(selected ? cAccentSoft : 0));
        r.setOnClickListener(l);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(selected ? cAccent : cText);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (selected) {
            TextView ck = new TextView(this);
            ck.setText("✓");
            ck.setTextColor(cAccent);
            ck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            r.addView(ck);
        }
        cur.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    private void pick(int slot, int action) {
        cfg.setAction(slot, action);
        apply();
        refresh();
    }

    private void appPicker(int slot) {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        List<Object[]> apps = new ArrayList<>();
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            apps.add(new Object[]{ri.loadLabel(pm).toString(), pkg, ri});
        }
        Collections.sort(apps, (a, b) -> ((String) a[0]).compareToIgnoreCase((String) b[0]));
        AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (Object[] a : apps) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(24), dp(8), dp(24), dp(8));
            r.setBackground(ripple(0));
            ImageView icon = new ImageView(this);
            try {
                Drawable d = ((ResolveInfo) a[2]).loadIcon(pm);
                icon.setImageDrawable(d);
            } catch (Exception ignored) {}
            r.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
            TextView t = new TextView(this);
            t.setText((String) a[0]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t.setTextColor(cText);
            t.setPadding(dp(14), 0, 0, 0);
            r.addView(t);
            r.setOnClickListener(v -> {
                cfg.apps[slot] = (String) a[1];
                dlg[0].dismiss();
                pick(slot, Config.ACT_APP);
            });
            list.addView(r, new LinearLayout.LayoutParams(-1, -2));
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        dlg[0] = new AlertDialog.Builder(this).setTitle("Which app should open?").setView(sv)
                .setNegativeButton("Cancel", null).show();
    }

    // ---------------------------------------------------------------- More tab

    private void infoRow(String title, String text) {
        LinearLayout r = rowShell(null);
        r.addView(twoLines(title, text), new LinearLayout.LayoutParams(0, -2, 1));
    }

    private Boolean helperRunning;   // null = not checked yet

    private void checkHelper() {
        new Thread(() -> {
            boolean on0 = Helper.isRunning();
            final boolean on = on0 && !Helper.isCurrent(this) ? Helper.start(this) : on0;   // app was updated: run the new helper
            runOnUiThread(() -> { boolean changed = helperRunning == null || helperRunning != on; helperRunning = on; if (changed && tab == T_MORE) refresh(); });
        }).start();
    }

    private int gl(String key, int def) {
        try { return android.provider.Settings.Global.getInt(getContentResolver(), key, def); } catch (Throwable t) { return def; }
    }

    /** Writes the helper's controls (root), then redraws. */
    private void putGl(String... kv) {
        new Thread(() -> {
            boolean ok = Helper.putGlobal(kv);
            runOnUiThread(() -> { if (!ok) Toast.makeText(this, "Root access was not granted to this app", Toast.LENGTH_LONG).show(); refresh(); });
        }).start();
    }

    private static int readSys(String f, int div) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            return (int) (Long.parseLong(r.readLine().trim().split("\\s+")[0]) / div);
        } catch (Throwable t) { return -1; }
    }

    /** What really limits the CPU / GPU right now, in plain words (read from the kernel, no root needed). */
    private String speedNow() {
        java.io.File[] fs = new java.io.File("/sys/devices/system/cpu/cpufreq").listFiles((d, n) -> n.startsWith("policy"));
        String cpu = "CPU: unknown";
        if (fs != null && fs.length > 0) {
            java.util.Arrays.sort(fs, (x, y) -> Integer.compare(Integer.parseInt(x.getName().substring(6)), Integer.parseInt(y.getName().substring(6))));
            String p = fs[fs.length - 1].getPath() + "/";
            int cap = readSys(p + "scaling_max_freq", 1000), hw = readSys(p + "cpuinfo_max_freq", 1000);
            if (cap > 0 && hw > 0)
                cpu = String.format(Locale.US, "CPU fastest core: up to %.1f of %.1f GHz", cap / 1000f, hw / 1000f) + (cap < hw * 0.97f ? (gl("sbmods_cpu_pct", 0) > 0 || gl("sbmods_cool", 0) == 1 ? " (your limit)" : " (Samsung's heat limit)") : " (full speed)");
        }
        String g = "/sys/class/kgsl/kgsl-3d0/devfreq/";
        int gcap = readSys(g + "max_freq", 1000000), ghw = -1;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(g + "available_frequencies"))) {
            for (String s : r.readLine().trim().split("\\s+")) ghw = Math.max(ghw, (int) (Long.parseLong(s) / 1000000));
        } catch (Throwable ignored) {}
        String gpu = gcap > 0 && ghw > 0 ? "GPU: up to " + gcap + " of " + ghw + " MHz" + (gcap < ghw * 0.97f ? (gl("sbmods_gpu_pct", 0) > 0 || gl("sbmods_cool", 0) == 1 ? " (your limit)" : " (Samsung's heat limit)") : " (full speed)") : "GPU: unknown";
        return cpu + "\n" + gpu;
    }

    private void buildMore() {
        group("Cheat sheet: what taps do");
        infoRow("In this app",
                "Preview bar: press an item and drag it; drop it on the grey tray to put it back in Samsung's place.\n"
                + "Sliders: tap “Reset” next to a value to go back to the starting value.\n"
                + "Gestures tab: every status bar item can have a tap, double-tap and long-press action. “Test” tries it.");
        divider();
        infoRow("Status bar",
                "Clock, temperature and battery: tap, double-tap and long-press do whatever you chose in the Gestures tab.\n"
                + "Back button: tap = jump back to the previous app.\n"
                + "Camera hole: tap = Samsung Finder (or your choice). Empty spot, double-tap (if on) = lock the screen.");
        divider();
        infoRow("Overview screen (recent apps)",
                "RAM: tap = close background apps. Swap: tap = deep clean. Temp: tap = cool-down on / off. Long-press any of them = Device care.\n"
                + "CPU (bottom right) and GPU (bottom left) show the live speed and what holds it back (full speed, Samsung's heat limit, or your limit). "
                + "Tap = Performance panel, long-press = quick limit on / off.");
        endGroup(null);

        // ---- the helper
        if (helperRunning == null) checkHelper();
        group("Root helper");
        switchRow("Run the helper", "Closing apps and speed limits need root, so this small helper does them for the Overview screen. Allow root for this app when asked. "
                        + (helperRunning == null ? "Checking…" : helperRunning ? "It is running." : "It is not running – the RAM, swap and speed buttons won't do anything."),
                helperRunning != null && helperRunning, v -> {
                    new Thread(() -> {
                        boolean ok = v ? Helper.start(this) : Helper.stopAndRestore();
                        boolean now = Helper.isRunning();
                        runOnUiThread(() -> {
                            helperRunning = now;
                            if (v && !now) Toast.makeText(this, ok ? "The helper did not start" : "Root access was not granted to this app", Toast.LENGTH_LONG).show();
                            else Toast.makeText(this, v ? "Helper is running" : "Stopped, full speed restored", Toast.LENGTH_SHORT).show();
                            refresh();
                        });
                    }).start();
                });
        divider();
        switchRow("Start it when the phone starts", "Only if the status bar mod is on. Speed limits never survive a restart.",
                prefs.getBoolean("helperAtBoot", false), v -> prefs.edit().putBoolean("helperAtBoot", v).apply());
        endGroup("Idle, it only checks once a second whether you pressed something; it doesn't keep the phone awake.");

        // ---- speed limits
        group("CPU and GPU speed");
        infoRow("Right now", speedNow());
        divider();
        final int[] pcts = {85, 70, 55, 40};
        final String[] pctNames = {"85% – barely noticeable", "70% – cooler, still smooth", "55% – battery saver", "40% – lowest (slow)"};
        int cpuPick = gl("sbmods_cpu_pick", 55), gpuPick = gl("sbmods_gpu_pick", 55);
        int ci = 2, gi = 2;
        for (int i = 0; i < pcts.length; i++) { if (pcts[i] == cpuPick) ci = i; if (pcts[i] == gpuPick) gi = i; }
        choiceRow("CPU quick limit", pctNames, ci, i -> putGl("sbmods_cpu_pick", String.valueOf(pcts[i])));
        divider();
        choiceRow("GPU quick limit", pctNames, gi, i -> putGl("sbmods_gpu_pick", String.valueOf(pcts[i])));
        divider();
        switchRow("Power saving while the CPU is limited", "Also turns on Samsung's power saving mode for as long as the CPU limit is on.",
                gl("sbmods_cpu_ps", 0) == 1, v -> putGl("sbmods_cpu_ps", v ? "1" : "0"));
        divider();
        final int[] mins = {15, 30, 60, 120};
        int mi = 0;
        for (int i = 0; i < mins.length; i++) if (mins[i] == gl("sbmods_limit_min", 15)) mi = i;
        choiceRow("Limits end by themselves after", new String[]{"15 minutes", "30 minutes", "1 hour", "2 hours"}, mi, i -> putGl("sbmods_limit_min", String.valueOf(mins[i])));
        divider();
        final int[] temps = {340, 350, 360, 370, 380};
        int ti = 2;
        for (int i = 0; i < temps.length; i++) if (temps[i] == gl("sbmods_cool_at", 360)) ti = i;
        choiceRow("Cool-down stops at", new String[]{"34°C", "35°C", "36°C", "37°C", "38°C"}, ti, i -> putGl("sbmods_cool_at", String.valueOf(temps[i])));
        divider();
        actionRow("Full speed now", "Ends any CPU / GPU limit and cool-down. Samsung's own heat protection always stays on.", cAccent, v ->
                putGl("sbmods_cpu_pct", "0", "sbmods_gpu_pct", "0", "sbmods_cool", "0"));
        endGroup("A limit is a share of the chip's real maximum, and only speeds the phone lists are used. "
                + "Samsung lowers the maximum by itself when the phone is warm – that's the “heat limit” you may see; this app never removes it. "
                + "The quick limit is what a long-press on the CPU / GPU chip turns on; the Performance panel (tap) lets you pick any level.");

        group("Overview screen");
        switchRow("Show the chips", "RAM, swap and temperature at the top; CPU and GPU at the bottom of the recent apps screen.",
                gl("sbmods_recents_chip", 1) != 0, v -> putGl("sbmods_recents_chip", v ? "1" : "0"));
        endGroup(null);

        group("Screen care");
        switchRow("Protect against screen burn-in", "Nudges the status bar a few pixels every couple of minutes so fixed icons don't leave marks. Only runs while the screen is on.",
                cfg.pixelShift, v -> { cfg.pixelShift = v; apply(); });
        endGroup(null);

        group("If something looks wrong");
        actionRow("Restart the status bar", "Reloads it. Use this after installing an update.", cAccent, v -> restartSystemUi());
        divider();
        actionRow("Turn the mod back on", "If the status bar crashed and the mod switched itself off, tap this and then restart the status bar.",
                cAccent, v -> {
                    send(new Intent(Config.ACTION_RESET_GUARD));
                    Toast.makeText(this, "Done. Now restart the status bar.", Toast.LENGTH_LONG).show();
                });
        endGroup("After you restart the phone without root, the status bar goes back to Samsung's own.");

        group("Start over");
        actionRow("Reset all settings", "Every option goes back to how it was at the start", cErr, v ->
                new AlertDialog.Builder(this)
                        .setTitle("Reset all settings?")
                        .setMessage("Every option goes back to its starting value. You can't undo this.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Reset", (d, w) -> {
                            cfg = new Config();
                            apply();
                            buildPreviewArea();
                            refresh();
                        }).show());
        endGroup(modVersion.isEmpty() ? "StatusBar Mods for the Galaxy S23 Ultra"
                : "StatusBar Mods  v" + modVersion + "  ·  for the Galaxy S23 Ultra");
    }

    // ================================================================ preview + comms

    private CharSequence clockText() {
        Date now = new Date();
        CharSequence clock = ClockFormat.format(cfg, DateFormat.is24HourFormat(this), now, Locale.getDefault());
        if (clock == null) clock = DateFormat.getTimeFormat(this).format(now).replaceAll("\\s?[AaPp]\\.?[Mm]\\.?", "");
        return clock;
    }

    private void updatePreview() {
        if (posView == null) return;
        float t = Float.isNaN(liveTemp) ? 34f : liveTemp;
        if (simulateHot) t = Math.max(cfg.warnAt > 0 ? cfg.warnAt : 45, 45);
        CharSequence lab = cfg.tempEnabled ? TempItem.format(cfg, t) : null;
        int pct = 50;
        try {
            Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bi != null) pct = bi.getIntExtra("level", 50) * 100 / Math.max(1, bi.getIntExtra("scale", 100));
        } catch (Exception ignored) {}
        posView.setClockRight("right".equals(clockSide));
        posView.setDate(ClockFormat.dateText(cfg, new Date(), Locale.getDefault()));
        posView.setTexts(clockText(), lab, pct);
    }

    private void apply() {
        cfg.sanitize();
        cfg.save(prefs);
        send(new Intent(Config.ACTION_APPLY).putExtras(cfg.toBundle()));
        updatePreview();
    }

    private void ping() {
        gotPong = false;
        setStatus("Checking…", "Checking the status bar…", 0);
        send(new Intent(Config.ACTION_APPLY).putExtras(cfg.toBundle())); // also re-syncs settings
        h.postDelayed(() -> {
            if (!gotPong) setStatus("Not running",
                    "The mod isn't running inside the status bar.\n\n"
                            + "• Make sure root and Vector are running (they aren't after a restart without root).\n"
                            + "• In Vector, switch on StatusBar Mods for “System UI”.\n"
                            + "• Then restart the status bar.", 2);
        }, 1500);
    }

    private void send(Intent i) {
        i.setPackage(Config.SYSUI);
        sendBroadcast(i);
    }

    private void setStatus(String shortText, String detail, int state) {
        statusShort = shortText;
        statusDetail = detail;
        statusState = state;
        refreshStatusPill();
    }

    private void refreshStatusPill() {
        if (statusPill == null) return;
        int c = statusState == 1 ? cOk : statusState == 2 ? cErr : cWarn;
        statusPill.setText("●  " + statusShort);
        statusPill.setTextColor(c);
        statusPill.setBackground(round((c & 0x00FFFFFF) | 0x22000000, dp(18), 0, 0));
    }

    private void showStatus(Intent i) {
        modVersion = String.valueOf(i.getStringExtra("version"));
        String side = i.getStringExtra("clockSide");
        if (side != null && !"-".equals(side)) clockSide = side;
        String shortText, detail;
        int state = 1;
        if (i.getBooleanExtra("guardTripped", false)) {
            state = 2;
            shortText = "Switched off";
            detail = "The mod switched itself off because the status bar crashed three times right after loading it.\n\n"
                    + "Open the More tab, tap “Turn the mod back on”, then restart the status bar.";
        } else if (i.getBooleanExtra("disabled", false)) {
            state = 2;
            shortText = "Stopped";
            detail = "The mod stopped itself after repeated errors. Restarting the status bar starts it again.";
        } else if (i.getBooleanExtra("attached", false)) {
            shortText = "Working";
            detail = "The mod is running in your status bar."
                    + ("right".equals(clockSide) ? "\nYour clock is on the right side." : "");
            if (!i.getBooleanExtra("battery", false)) {
                state = 0;
                shortText = "Working (no battery)";
                detail += "\n\nThe battery icon wasn't found yet, so battery taps and moving the battery won't work. Tap “Check again” in a moment.";
            }
        } else {
            state = 0;
            shortText = "Starting…";
            detail = "The mod is loaded and waiting for the status bar clock to appear.";
        }
        int errors = i.getIntExtra("errors", 0);
        if (errors > 0) detail += "\n\n" + errors + " error(s). The last one: " + i.getStringExtra("lastError");
        detail += "\n\nVersion " + modVersion;
        setStatus(shortText, detail, state);
        updatePreview();
    }

    private void statusDialog() {
        new AlertDialog.Builder(this).setTitle("Status").setMessage(statusDetail)
                .setPositiveButton("Check again", (d, w) -> ping())
                .setNeutralButton("Restart status bar", (d, w) -> restartSystemUi())
                .setNegativeButton("Close", null)
                .show();
    }

    /**
     * Asks the mod inside the status bar to restart it (no root needed). If the mod isn't running
     * there is nothing to ask, so fall back to su.
     */
    private void restartSystemUi() {
        if (gotPong) {
            send(new Intent(Config.ACTION_RESTART));
            Toast.makeText(this, "Restarting the status bar…", Toast.LENGTH_SHORT).show();
            h.postDelayed(this::ping, 6000);
            return;
        }
        new Thread(() -> {
            boolean ok;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "killall com.android.systemui"});
                ok = p.waitFor() == 0;
            } catch (Exception e) {
                ok = false;
            }
            final boolean fOk = ok;
            h.post(() -> Toast.makeText(this, fOk ? "Restarting the status bar…"
                    : "The mod isn't running and this app has no root.\nIn Termux, run: su -c killall com.android.systemui",
                    Toast.LENGTH_LONG).show());
        }).start();
    }

    // ============================================================ UI building blocks

    interface IntSetter { void set(int v); }
    interface BoolSetter { void set(boolean v); }
    interface Label { String of(int v); }

    private static int idx(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable round(int fill, int radius, int stroke, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (stroke > 0) g.setStroke(stroke, strokeColor);
        return g;
    }

    private RippleDrawable ripple(int fill) {
        GradientDrawable content = fill == 0 ? null : round(fill, 0, 0, 0);
        return new RippleDrawable(ColorStateList.valueOf(cAccentSoft), content, round(Color.WHITE, 0, 0, 0));
    }

    /** A titled card. Rows go inside until endGroup(). */
    private void group(String title) {
        cur = page;
        if (title != null) {
            TextView hd = new TextView(this);
            hd.setText(title.toUpperCase(Locale.ROOT));
            hd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            hd.setLetterSpacing(0.06f);
            hd.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            hd.setTextColor(cSub);
            hd.setPadding(dp(8), dp(18), dp(8), dp(8));
            page.addView(hd);
        } else {
            View sp = new View(this);
            page.addView(sp, new LinearLayout.LayoutParams(1, dp(12)));
        }
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(cCard, dp(18), 0, 0));
        c.setClipToOutline(true);
        page.addView(c, new LinearLayout.LayoutParams(-1, -2));
        cur = c;
    }

    /** Closes the card; optional plain-English help goes underneath it. */
    private void endGroup(String help) {
        cur = page;
        if (help != null) {
            TextView t = new TextView(this);
            t.setText(help);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setTextColor(cSub);
            t.setLineSpacing(0, 1.1f);
            t.setPadding(dp(8), dp(8), dp(8), dp(2));
            page.addView(t);
        }
    }

    private void divider() {
        View d = new View(this);
        d.setBackgroundColor(cDivider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.7f)));
        lp.setMargins(dp(16), 0, dp(16), 0);
        cur.addView(d, lp);
    }

    private LinearLayout rowShell(View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(56));
        r.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (click != null) {
            r.setBackground(ripple(0));
            r.setOnClickListener(click);
        }
        cur.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    private LinearLayout twoLines(String title, String sub) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, dp(12), 0);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(cText);
        col.addView(t);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            s.setTextColor(cSub);
            s.setPadding(0, dp(2), 0, 0);
            col.addView(s);
        }
        return col;
    }

    private LinearLayout block(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(cText);
        t.setPadding(0, 0, 0, dp(10));
        box.addView(t);
        cur.addView(box, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private void switchRow(String title, String sub, boolean value, BoolSetter s) {
        Switch sw = new Switch(this);
        LinearLayout r = rowShell(v -> sw.toggle());
        r.addView(twoLines(title, sub), new LinearLayout.LayoutParams(0, -2, 1));
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((b, v) -> s.set(v));
        r.addView(sw);
    }

    private void choiceRow(String title, String[] labels, int current, IntSetter s) {
        final int sel = current < 0 || current >= labels.length ? 0 : current;
        LinearLayout r = rowShell(v -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, sel, (d, which) -> {
                    d.dismiss();
                    if (which != sel) s.set(which);
                })
                .setNegativeButton("Cancel", null)
                .show());
        r.addView(twoLines(title, null), new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = new TextView(this);
        value.setText(labels[sel] + "  ›");
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        value.setTextColor(cAccent);
        value.setGravity(Gravity.END);
        value.setMaxWidth(dp(200));
        r.addView(value);
    }

    private LinearLayout segmented(String[] labels, int selected, IntSetter s) {
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.setBackground(round(cField, dp(20), 0, 0));
        for (int i = 0; i < labels.length; i++) {
            final int at = i;
            TextView b = new TextView(this);
            b.setText(labels[i]);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            b.setSingleLine(true);
            boolean on = i == selected;
            b.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            b.setTextColor(on ? Color.WHITE : cText);
            if (on) b.setBackground(round(cAccent, dp(17), 0, 0));
            b.setOnClickListener(v -> { if (at != selected) { s.set(at); refresh(); } });
            seg.addView(b, new LinearLayout.LayoutParams(0, dp(36), 1));
        }
        return seg;
    }

    private void segmentedBlock(String title, String[] labels, int selected, IntSetter s) {
        LinearLayout box = block(title);
        box.addView(segmented(labels, Math.max(0, selected), s));
    }

    /** Segmented control directly under the previous row (no title). */
    private void segmentedInline(String[] labels, int selected, IntSetter s) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(16), 0, dp(16), dp(14));
        box.addView(segmented(labels, selected, s), new LinearLayout.LayoutParams(-1, -2));
        cur.addView(box);
    }

    private void actionRow(String title, String sub, int color, View.OnClickListener l) {
        LinearLayout r = rowShell(l);
        LinearLayout t = twoLines(title, sub);
        ((TextView) t.getChildAt(0)).setTextColor(color);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private TextView textButton(String t, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextColor(cAccent);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(cAccentSoft), null, round(Color.WHITE, dp(18), 0, 0)));
        b.setOnClickListener(l);
        return b;
    }

    private EditText field(String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        e.setTextColor(cText);
        e.setHintTextColor(cSub);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        e.setBackground(round(cField, dp(12), 0, 0));
        return e;
    }

    /** Slider with its value on the right and a "Reset" button whenever it differs from the starting value. */
    private void slider(String t, int min, int max, int current, Label label, IntSetter s, int def) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(8), dp(6));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(t);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTextColor(cText);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = new TextView(this);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        val.setTextColor(cAccent);
        val.setText(label.of(current));
        val.setPadding(0, 0, dp(8), 0);
        top.addView(val);
        TextView reset = textButton("Reset", v -> { s.set(def); apply(); refresh(); });
        reset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        reset.setPadding(dp(10), dp(6), dp(10), dp(6));
        reset.setBackground(round(cAccentSoft, dp(14), 0, 0));
        reset.setVisibility(current == def ? View.INVISIBLE : View.VISIBLE);
        top.addView(reset);
        box.addView(top, new LinearLayout.LayoutParams(-1, dp(36)));
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(current, min) - min);
        sb.setProgressTintList(ColorStateList.valueOf(cAccent));
        sb.setThumbTintList(ColorStateList.valueOf(cAccent));
        sb.setPadding(dp(4), dp(8), dp(12), dp(6));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                val.setText(label.of(p + min));
                reset.setVisibility(p + min == def ? View.INVISIBLE : View.VISIBLE);
                if (user) { s.set(p + min); updatePreview(); }
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) { apply(); refresh(); }
        });
        box.addView(sb);
        cur.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }
}
