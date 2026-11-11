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
import android.graphics.Insets;
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
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.gitesh.sbmods.ClockFormat;
import dev.gitesh.sbmods.Config;
import dev.gitesh.sbmods.Habits;
import dev.gitesh.sbmods.Helper;
import dev.gitesh.sbmods.PanelLayout;
import dev.gitesh.sbmods.Ui;
import dev.gitesh.sbmods.ZoneLayout;
import dev.gitesh.sbmods.items.TempItem;

/**
 * The settings screen.
 *
 *   header: name + status pill
 *   live preview (pinned; only on the first four tabs)
 *   tabs: Layout · Clock · Temperature · Back button · Gestures · Overview · More
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
        ACT_LABEL[Config.ACT_CLOSE_APPS] = "Close background apps";
    }
    private static final String[] ITEM_TITLES = {"Battery icon", "Clock", "Temperature", "Front camera hole", "Wi-Fi icon", "Mobile signal icon"};
    private static final String[] GESTURE_TITLES = {"Tap", "Double-tap", "Long-press"};
    private static final String[] TABS = {"Layout", "Clock", "Panel", "System"};
    private static final int T_LAYOUT = 0, T_CLOCK = 1, T_PANEL = 2, T_SYSTEM = 3;
    private static final int[] COLORS = {0xFFFF9800, 0xFFF44336, 0xFFFFEB3B, 0xFFE91E63, 0xFF00BCD4, 0xFF4CAF50};

    // ------------------------------------------------------------------ state

    private Ui.Pal pal;
    private int cBg, cCard, cText, cOnAccent, cSub, cAccent, cAccentSoft, cDivider, cOk, cErr, cWarn, cField;

    private SharedPreferences prefs;
    private Config cfg;
    private int tab;
    private ScrollView scroll;
    private LinearLayout page, cur, previewHolder;
    private TextView statusPill;
    private PositionView posView;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean gotPong, simulateHot, pingDone, modTripped, modDisabled, resumed;
    // self-check (see SelfCheck): the last result is cached; root checks run only on start / resume (at most every 30 s) and on "Check again"
    private SelfCheck.Facts scFacts;
    private java.util.List<SelfCheck.Finding> findings = new java.util.ArrayList<>();
    private boolean scRunning;
    private long scAt;
    private String scSig = "";
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
        // We place the content ourselves (see the insets listener on the root view below).
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        pal = Ui.pal(this);
        cBg = pal.bg; cCard = pal.card; cText = pal.tx; cSub = pal.tx2;
        cAccent = pal.acc; cAccentSoft = pal.accSoft; cOnAccent = pal.onAcc;
        cDivider = pal.line; cOk = pal.ok; cErr = pal.err; cWarn = pal.warn; cField = pal.card2;

        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getDecorView().setBackgroundColor(cBg);
        WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) ic.setSystemBarsAppearance(night ? 0 : light, light);

        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        Helper.setAtBoot(this, prefs.getBoolean("helperAtBoot", false));
        cfg = Config.load(prefs);
        tab = prefs.getInt("tab", T_LAYOUT);
        int tabv = prefs.getInt("tabv", 1);
        if (tabv < 3) {   // older versions had 6 or 7 tabs; fold the saved one into the four we have now
            if (tabv < 2 && tab >= 5) tab = 6;   // the first versions had no Overview tab: their index 5 was "More"
            tab = new int[]{T_LAYOUT, T_CLOCK, T_CLOCK, T_LAYOUT, T_SYSTEM, T_PANEL, T_SYSTEM}[Math.max(0, Math.min(tab, 6))];
            prefs.edit().putInt("tab", tab).putInt("tabv", 3).apply();
        }
        tab = Math.max(0, Math.min(tab, TABS.length - 1));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(cBg);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(buildHeader(), new LinearLayout.LayoutParams(-1, -2));
        previewHolder = new LinearLayout(this);
        previewHolder.setOrientation(LinearLayout.VERTICAL);
        content.addView(previewHolder, new LinearLayout.LayoutParams(-1, -2));
        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(Ui.S4), dp(4), dp(Ui.S4), dp(Ui.NAV_H + 32));
        scroll.addView(page);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        final View nav = buildNav();
        final FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, dp(Ui.NAV_H), Gravity.BOTTOM);
        np.setMargins(dp(Ui.S4), 0, dp(Ui.S4), dp(Ui.S4));
        root.addView(nav, np);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = in.getInsets(WindowInsets.Type.ime());
            boolean kb = ime.bottom > bars.bottom;   // keyboard up: the floating bar steps aside
            content.setPadding(bars.left, bars.top, bars.right, kb ? ime.bottom : 0);
            np.setMargins(dp(Ui.S4) + bars.left, 0, dp(Ui.S4) + bars.right, dp(Ui.S4) + bars.bottom);
            nav.setLayoutParams(np);
            nav.setVisibility(kb ? View.GONE : View.VISIBLE);
            page.setPadding(dp(Ui.S4), dp(4), dp(Ui.S4), kb ? dp(Ui.S5) : dp(Ui.NAV_H + 32) + bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        buildPreviewArea();
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        registerReceiver(pong, new IntentFilter(Config.ACTION_PONG), Context.RECEIVER_EXPORTED);
        ping();
        selfCheck(false);
        h.post(previewTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        h.removeCallbacks(previewTick);
        try { unregisterReceiver(pong); } catch (Exception ignored) {}
    }

    // ================================================================ frame: header, preview, bottom bar

    private static final String[] TAB_TITLES = {"Status bar", "Clock", "Panel", "System"};
    private static final String[] TAB_ICONS = {"layout", "clock", "panel", "sys"};
    private TextView titleView;
    private LinearLayout navBar;

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(12), dp(16), dp(8));
        titleView = Ui.text(this, TAB_TITLES[tab], Ui.T_TITLE, cText, 700);
        titleView.setLetterSpacing(-0.02f);
        titleView.setSingleLine(true);
        row.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        statusPill = Ui.text(this, "", 13, cOk, 600);
        statusPill.setGravity(Gravity.CENTER);
        statusPill.setMinHeight(dp(32));
        statusPill.setPadding(dp(12), 0, dp(12), 0);
        statusPill.setOnClickListener(v -> statusDialog());
        row.addView(statusPill, new LinearLayout.LayoutParams(-2, -2));
        refreshStatusPill();
        return row;
    }

    /** The pinned preview. Rebuilt when the settings object is replaced (reset all). */
    private void buildPreviewArea() {
        previewHolder.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(this, pal));
        card.setPadding(dp(Ui.S4), 0, dp(Ui.S4), dp(Ui.S3));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "Live preview", Ui.T_SECTION, cSub, 600), new LinearLayout.LayoutParams(0, dp(40), 1));
        head.addView(Ui.text(this, "Drag to reorder", Ui.T_CAPTION, cSub, 500));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));
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
                else switchTab(element == ZoneLayout.TEMP || element == ZoneLayout.DATE ? T_CLOCK : T_LAYOUT);
            }
        }, screenW, holeL, holeR, cAccent, cText, cSub, cField);
        card.addView(posView, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(Ui.S4), dp(2), dp(Ui.S4), dp(Ui.S3));
        previewHolder.addView(card, lp);
        updatePreview();
    }

    /** The floating bottom bar: frosted glass, four tabs, the open one in a soft accent pill. */
    private View buildNav() {
        navBar = new LinearLayout(this);
        navBar.setPadding(dp(6), dp(6), dp(6), dp(6));
        navBar.setBackground(Ui.glass(this, pal, Ui.NAV_H / 2f));
        fillTabs();
        return navBar;
    }

    private void fillTabs() {
        navBar.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            final int at = i;
            boolean on = i == tab;
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            t.setBackground(on ? round(pal.accSoft, dp(Ui.NAV_TAB_H / 2f), 0, 0) : null);
            t.addView(Ui.icon(this, TAB_ICONS[i], on ? pal.accInk : cSub, 22));
            TextView l = Ui.text(this, TABS[i], Ui.T_TAB, on ? pal.accInk : cSub, 600);
            l.setSingleLine(true);
            t.addView(l, new LinearLayout.LayoutParams(-2, -2));
            t.setContentDescription(TABS[i]);
            t.setSelected(on);
            t.setOnClickListener(v -> switchTab(at));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(Ui.NAV_TAB_H), 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            navBar.addView(t, lp);
        }
    }

    private void switchTab(int t) {
        if (t == tab) return;
        tab = t;
        prefs.edit().putInt("tab", t).apply();
        titleView.setText(TAB_TITLES[t]);
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
        previewHolder.setVisibility(tab == T_LAYOUT || tab == T_CLOCK ? View.VISIBLE : View.GONE);   // the preview only helps these two
        switch (tab) {
            case T_CLOCK: buildClock(); buildTemp(); break;
            case T_PANEL: buildPanel(); break;
            case T_SYSTEM: buildSystem(); break;
            default: buildLayout(); buildBack(); break;
        }
    }

    /** Notifications card (System tab): how the notification panel opens its notifications. */
    private void buildNotifs() {
        group("Notifications");
        cardReset((c, d) -> { c.notifOpen = d.notifOpen; c.groupOpen = d.groupOpen; c.openSkipSilent = d.openSkipSilent; });
        switchRow("Open notifications fully", "Every notification in the notification panel shows its full size right away. You can still close one by hand.",
                cfg.notifOpen, v -> { cfg.notifOpen = v; apply(); });
        divider();
        switchRow("Groups open fully with one tap", "One tap on a group opens it, and the notifications inside are already full size.",
                cfg.groupOpen, v -> { cfg.groupOpen = v; apply(); });
        divider();
        switchRow("Keep silent ones small", "Silent notifications stay closed. The lock screen and pop-ups always stay small.",
                cfg.openSkipSilent, v -> { cfg.openSkipSilent = v; apply(); });
        endGroup("Applies the next time the notification panel updates: open it again or wait for a new notification.");
    }

    /** Smart suggestions card (System tab): the master switch for everything the mod notices by itself. */
    private void buildSmart() {
        group("Smart suggestions");
        cardResetAction(() -> { cfg.smartOn = new Config().smartOn; apply(); putGl("sbmods_smart", cfg.smartOn ? "1" : "0"); });
        switchRow("Smart suggestions", "The mod notices things and offers a fix. Off = it never checks anything.",
                cfg.smartOn, v -> { cfg.smartOn = v; apply(); putGl("sbmods_smart", v ? "1" : "0"); });   // the Overview panel (launcher process) reads the global
        if (cfg.smartOn) {
            divider();
            switchRow("Restart apps still on OpenGL", "When Vulkan is on and an app you open is still using the old renderer, it is closed and reopened once so it can switch. Each app at most once until the phone restarts. Unsaved work in that app is lost.",
                    !"0".equals(glStr("sbmods_gfx_restart")), v -> putGl("sbmods_gfx_restart", v ? "1" : "0"));
            divider();
            switchRow("Game mode: full speed for games", "When you open a game, any speed limit is lifted for as long as it is in front, then your earlier limit comes back. A game with its own automatic rule keeps that rule.",
                    !"0".equals(glStr("sbmods_game")), v -> putGl("sbmods_game", v ? "1" : "0"));
            divider();
            switchRow("Close other apps when a game starts", "Closes background apps once when you open a game, except the ones you keep reopening.",
                    "1".equals(glStr("sbmods_gamesclean")), v -> putGl("sbmods_gamesclean", v ? "1" : "0"));
            divider();
            actionRow("Ask about Vulkan again", "Use this after you chose “Don't ask again” on the Vulkan question.", cAccent, v -> {
                putGl("sbmods_smart_noask", "0");
                Toast.makeText(this, "It may ask about Vulkan again", Toast.LENGTH_SHORT).show();
            });
            divider();
            java.util.List<Habits.R> rl = Habits.parseRules(glStr("sbmods_rules"));
            if (rl.isEmpty()) infoRow("Automatic rules: none yet. When you often slow the phone down in the same app, the Overview panel offers to do it for you.", null);
            for (Habits.R r : rl) {
                LinearLayout row = rowShell(null);
                String nm = r.pkg;
                try { nm = getPackageManager().getApplicationInfo(r.pkg, 0).loadLabel(getPackageManager()).toString(); } catch (Throwable ignored) {}
                TextView t = Ui.text(this, nm + ": " + Habits.describe(r), Ui.T_BODY, cText, 400);
                row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
                final String pk = r.pkg;
                row.addView(textButton("Remove", v -> putGl("sbmods_rules", Habits.removeRule(glStr("sbmods_rules"), pk))));
            }
            divider();
            java.util.List<Habits.K> kl = Habits.kept(glStr("sbmods_keep"));
            infoRow(kl.isEmpty() ? "Apps kept when cleaning: none yet. An app you open right after cleaning twice is spared by the next clean."
                    : "Apps kept when cleaning (spared when you close background apps):", null);
            for (Habits.K k : kl) {
                LinearLayout row = rowShell(null);
                String nm = k.pkg;
                try { nm = getPackageManager().getApplicationInfo(k.pkg, 0).loadLabel(getPackageManager()).toString(); } catch (Throwable ignored) {}
                row.addView(Ui.text(this, nm, Ui.T_BODY, cText, 400), new LinearLayout.LayoutParams(0, -2, 1));
                final String pk = k.pkg;
                row.addView(textButton("Remove", v -> putGl("sbmods_keep", Habits.removeKeep(glStr("sbmods_keep"), pk))));
            }
            divider();
            actionRow("Forget what it learned", "Clears the apps it noticed, the ones you said No to and the apps kept when cleaning. Your automatic rules stay.", cAccent, v -> {
                putGl("sbmods_habits", "", "sbmods_keep", "");
                Toast.makeText(this, "Forgotten", Toast.LENGTH_SHORT).show();
            });
        }
        endGroup("When an app comes to the front, the mod asks the root helper (see below) how that app draws the screen, once per app, and tells you in a short message. "
                + "If it uses the older OpenGL, it can offer to switch the phone to Vulkan. If you often slow the phone down by hand in the same app, the Overview panel can offer to do it for you (nothing happens until you say Yes; each time it does, a message tells you). It only looks when you open an app, never in the background, and it needs the root helper to be running.");
    }

    /** System tab: what taps do, the root helper and speed limits, the Overview chips, help, and starting over. */
    private void buildSystem() {
        buildSelfCheck();
        buildGestures();
        buildNotifs();
        buildSmart();
        buildHelperSpeed();
        buildMore();
    }

    // ---------------------------------------------------------------- Layout tab

    private void buildLayout() {
        group("Samsung's layout");
        cardReset((c, d) -> c.plan = new ZoneLayout.Plan());
        infoRow(movedText(), null);
        divider();
        infoRow("How to arrange the bar",
                "Press an item in the preview at the top and drag it along the bar. It settles at the nearest edge, beside the camera, or next to another item. "
                + "Drop it on the grey tray to send it back to Samsung's place. You can also just tap an item to pick its place from a list.\n\n"
                + "You can move the clock, the date (turn on “Date as its own item” in the Clock tab), the temperature, the battery, the network speed and the back button.");
        endGroup(null);

        group("space", "Space", null, true);
        cardReset((c, d) -> { c.edgeMode = d.edgeMode; c.edgeDp = d.edgeDp; c.itemGap = d.itemGap; });
        segmentedBlock("Space at the screen edges", new String[]{"Samsung's", "Tight", "Custom"}, cfg.edgeMode,
                v -> { cfg.edgeMode = v; apply(); refresh(); });
        if (cfg.edgeMode == 2) {
            divider();
            slider("Distance from the screen edge", 0, 24, cfg.edgeDp, v -> v + " dp", v -> { cfg.edgeDp = v; posView.invalidate(); }, 4);
        }
        if (advanced("space")) {
            divider();
            slider("Space between items", -1, 16, cfg.itemGap, v -> v < 0 ? "Samsung's" : v + " dp",
                    v -> { cfg.itemGap = v; posView.invalidate(); }, -1);
        }
        endGroup("Less space leaves more room for icons. “Tight” and “Custom” let the status bar reach the curved edges of your screen.");

        group("notif", "Notification icons", null, true);
        cardReset((c, d) -> c.notifMax = d.notifMax);
        slider("Icons before the “•” dot", 0, 12, cfg.notifMax, v -> v == 0 ? "Samsung's (3)" : v + " icons",
                v -> cfg.notifMax = v, 0);
        endGroup("Samsung shows 3 app icons, then a dot for the rest. It applies after the next status bar restart.");

        buildStatusIcons();

        group("locks", "Recents: kept-open apps", null, true);
        cardReset((c, d) -> c.lockMax = d.lockMax);
        slider("Apps you can keep open", 0, 12, cfg.lockMax, v -> v == 0 ? "Samsung's (3)" : v + " apps",
                v -> cfg.lockMax = v, 0);
        endGroup("Samsung allows 3 locked apps in the recent apps screen. Apps you keep open use memory.");

        group("Connection icon");
        cardReset((c, d) -> c.mergeNet = d.mergeNet);
        switchRow("One icon for Wi-Fi and mobile data", "While Wi-Fi is connected only the Wi-Fi icon shows. When it drops, the mobile signal icon comes back by itself.",
                cfg.mergeNet, v -> { cfg.mergeNet = v; apply(); });
        endGroup(null);
    }

    /** One line: how many items are away from Samsung's own place, and which. */
    private String movedText() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++)
            if (cfg.plan.mode[i] != ZoneLayout.STOCK) names.add(ZoneLayout.ELEMENT_NAMES[i].toLowerCase(Locale.ROOT));
        if (names.isEmpty()) return "Everything is in Samsung's own place";
        StringBuilder sb = new StringBuilder();
        for (String n : names) { if (sb.length() > 0) sb.append(", "); sb.append(n); }
        return names.size() + (names.size() == 1 ? " item moved: " : " items moved: ") + sb;
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
        group("icons", "Status icons", null, false);
        if (advanced("icons", "Choose which icons show")) {
            // The root read only starts when the list is open.
            if (hiddenIcons == null && !hiddenIconsNoRoot && !hiddenIconsLoading) loadHiddenIcons();
            divider();
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
                                else Toast.makeText(this, "Couldn't change that – is root allowed?", Toast.LENGTH_LONG).show();
                                refresh();
                            });
                        }).start();
                    });
                }
            }
        }
        endGroup("Hidden icons still work – only the picture in the status bar goes away. Changes show up within a few seconds.");
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
        Ui.picker(this, pal, "Where should the " + what + " go?", names.toArray(new String[0]), -1,
                which -> posView.place(element, codes.get(which))).show();
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

        group("clktime", "Time", null, true);
        cardReset((c, d) -> { c.hourMode = d.hourMode; c.ampm = d.ampm; c.ampmLower = d.ampmLower; c.seconds = d.seconds; c.leadingZero = d.leadingZero; });
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
        }
        divider();
        switchRow("Show seconds", "Updates every second", cfg.seconds, v -> { cfg.seconds = v; apply(); refresh(); });
        if (advanced("clktime")) {
            if (amOn) {
                divider();
                switchRow("Lowercase am / pm", "“pm” instead of “PM”", cfg.ampmLower, v -> { cfg.ampmLower = v; apply(); refresh(); });
            }
            divider();
            switchRow("Leading zero", "Shows 09:05 instead of 9:05", cfg.leadingZero, v -> { cfg.leadingZero = v; apply(); refresh(); });
        }
        endGroup(null);

        final int[] dayVals = {Config.WEEKDAY_NONE, Config.WEEKDAY_TWO, Config.WEEKDAY_SHORT, Config.WEEKDAY_FULL};
        boolean hasDay = cfg.weekday != Config.WEEKDAY_NONE, hasDate = cfg.date > 0;
        group("clkdate", "Day and date", null, true);
        cardReset((c, d) -> { c.weekday = d.weekday; c.date = d.date; c.year = d.year; c.dateFirst = d.dateFirst; c.dateSeparate = d.dateSeparate;
            c.dateAfterTime = d.dateAfterTime; c.dateSizePct = d.dateSizePct; c.gapDayDate = d.gapDayDate; c.gapDateTime = d.gapDateTime; });
        segmentedBlock("Day of the week", new String[]{"Hide", "Su", "Sun", "Sunday"}, idx(dayVals, cfg.weekday),
                v -> { cfg.weekday = dayVals[v]; apply(); refresh(); });
        divider();
        choiceRow("Date", ClockFormat.DATE_LABELS, cfg.date, v -> { cfg.date = v; apply(); refresh(); });
        if (hasDate) {
            divider();
            segmentedBlock("Year", new String[]{"Hide", "26", "2026"}, cfg.year, v -> { cfg.year = v; apply(); refresh(); });
        }
        if ((hasDay || hasDate) && advanced("clkdate")) {
            if (hasDay && hasDate) {
                divider();
                String dayEx = sample(cfg.weekday, 0), dateEx = sample(-1, cfg.date);
                segmentedBlock("Order", new String[]{dayEx + " " + dateEx, dateEx + " " + dayEx}, cfg.dateFirst ? 1 : 0,
                        v -> { cfg.dateFirst = v == 1; apply(); refresh(); });
            }
            divider();
            switchRow("Date as its own item", "Lets you place the day and date anywhere, apart from the time (Layout tab)", cfg.dateSeparate,
                    v -> { cfg.dateSeparate = v; apply(); refresh(); });
            divider();
            String dd = hasDay ? sample(cfg.weekday, 0) : sample(-1, cfg.date);
            segmentedBlock("Placement", new String[]{dd + "  12:30", "12:30  " + dd}, cfg.dateAfterTime ? 1 : 0,
                    v -> { cfg.dateAfterTime = v == 1; apply(); refresh(); });
            divider();
            slider("Size of day and date", 50, 120, cfg.dateSizePct, v -> v + "%", v -> cfg.dateSizePct = v, 100);
            if (hasDay && hasDate) {
                divider();
                slider("Space between day and date", 0, 300, cfg.gapDayDate, v -> v == 0 ? "None" : v + "%", v -> cfg.gapDayDate = v, 100);
            }
            divider();
            slider("Space between " + (hasDay && hasDate ? "day / date" : hasDay ? "day" : "date") + " and time", 0, 300, cfg.gapDateTime,
                    v -> v == 0 ? "None" : v + "%", v -> cfg.gapDateTime = v, 100);
        }
        endGroup(hasDay || hasDate ? "100% is a normal space. Set 0 to close the gap completely." : "Pick a day or a date to show it next to the time.");
    }

    private void buildClockPattern() {
        group("Your pattern");
        cardReset((c, d) -> c.pattern = d.pattern);
        patternEditor();
        endGroup("The preview above updates as you type.\n\n"
                + "h  hour 1-12\nH  hour 0-23\nmm  minutes\nss  seconds\na  AM / PM\nEEE  Sun\nEEEE  Sunday\nd  day 1\ndd  day 01\nMMM  Nov\nMMMM  November\n"
                + "yy  26\nyyyy  2026\n'text'  shows your own text\n\nExamples:\nEEE d MMM h:mm a\nHH:mm:ss\n'⏰' h:mm");
        group("clkampm", "AM / PM", null, true);
        cardReset((c, d) -> { c.ampm = d.ampm; c.ampmLower = d.ampmLower; });
        segmentedBlock("Show AM / PM", new String[]{"Hide", "Normal size", "Small"}, cfg.ampm,
                v -> { cfg.ampm = v; apply(); refresh(); });
        if (advanced("clkampm")) {
            divider();
            switchRow("Lowercase am / pm", "“pm” instead of “PM”", cfg.ampmLower, v -> { cfg.ampmLower = v; apply(); });
        }
        endGroup(null);
    }

    private void patternEditor() {
        LinearLayout box = block("Pattern");
        EditText e = field(cfg.pattern);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setTypeface(Typeface.MONOSPACE);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        TextView err = new TextView(this);
        err.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.T_CAPTION);
        err.setPadding(0, dp(6), 0, 0);
        box.addView(err);
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
        cardReset((c, d) -> c.tempEnabled = d.tempEnabled);
        switchRow("Show the phone's temperature", "A small number next to the clock", cfg.tempEnabled,
                v -> { cfg.tempEnabled = v; apply(); refresh(); });
        endGroup(null);
        if (!cfg.tempEnabled) return;

        int si = cfg.sensor == Config.SENSOR_CPU ? 1 : cfg.sensor == Config.SENSOR_BATTERY ? 2 : 0;
        final int[] sv = {Config.SENSOR_SKIN, Config.SENSOR_CPU, Config.SENSOR_BATTERY};

        group("tmpwhat", "What it shows", null, true);
        cardReset((c, d) -> { c.sensor = d.sensor; c.suffix = d.suffix; c.decimal = d.decimal; c.showAbove = d.showAbove; });
        choiceRow("Measure the", new String[]{"Phone body (recommended)", "Chip (CPU)", "Battery"}, si,
                v -> { cfg.sensor = sv[v]; apply(); refresh(); });
        divider();
        segmentedBlock("Format", new String[]{"34", "34°", "34°C"}, cfg.suffix, v -> { cfg.suffix = v; apply(); refresh(); });
        if (advanced("tmpwhat")) {
            divider();
            switchRow("Show one decimal", "34.2 instead of 34", cfg.decimal, v -> { cfg.decimal = v; apply(); refresh(); });
            divider();
            slider("Only show when warmer than", 0, 60, cfg.showAbove, v -> v == 0 ? "Always show" : v + " °C", v -> cfg.showAbove = v, 0);
        }
        endGroup(null);

        group("tmpheat", "Heat warning", null, true);
        cardReset((c, d) -> { c.warnAt = d.warnAt; c.warnColor = d.warnColor; });
        slider("Change colour from", 0, 60, cfg.warnAt, v -> v == 0 ? "Off" : v + " °C", v -> cfg.warnAt = v, 42);
        if (cfg.warnAt > 0 && advanced("tmpheat")) {
            colorDots();
            divider();
            switchRow("Preview the warning", "Shows the preview as if the phone were hot", simulateHot,
                    v -> { simulateHot = v; updatePreview(); });
        }
        endGroup("The number turns this colour when the phone gets that warm.");

        group("tmplook", "Look and position", null, true);
        cardReset((c, d) -> { c.sizePct = d.sizePct; c.labelPos = d.labelPos; c.gapDp = d.gapDp; });
        slider("Size", 50, 150, cfg.sizePct, v -> v + "% of the clock", v -> cfg.sizePct = v, 100);
        if (advanced("tmplook")) {
            divider();
            segmentedBlock("Next to the clock", new String[]{"Automatic", "After it", "Before it"}, cfg.labelPos,
                    v -> { cfg.labelPos = v; apply(); refresh(); });
            divider();
            slider("Space between temperature and clock", 0, 16, cfg.gapDp, v -> v + " dp", v -> cfg.gapDp = v, 4);
        }
        endGroup("To put the temperature somewhere else, use the Layout tab.");

        group("tmpsave", "Battery saving", null, true);
        cardReset((c, d) -> { c.intervalSec = d.intervalSec; c.pauseScreenOff = d.pauseScreenOff; });
        final int[] secs = {1, 2, 5, 10, 15, 30, 60};
        choiceRow("Refresh every", new String[]{"1 second", "2 seconds", "5 seconds", "10 seconds", "15 seconds", "30 seconds", "1 minute"},
                idx(secs, cfg.intervalSec), v -> { cfg.intervalSec = secs[v]; apply(); refresh(); });
        if (advanced("tmpsave")) {
            divider();
            switchRow("Pause while the screen is off", "Saves a little battery", cfg.pauseScreenOff,
                    v -> { cfg.pauseScreenOff = v; apply(); refresh(); });
        }
        endGroup(null);
    }

    private void colorDots() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(14));
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
        new Ui.Dlg(this, pal).title("Your own colour").message("Type a colour code, like #FF9800")
                .view(wrap)
                .button(0, "Cancel", null)
                .button(2, "Use it", () -> {
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
        cardReset((c, d) -> c.backEnabled = d.backEnabled);
        switchRow("Show a back button", "Shows the app you used before. Tap it to jump back to that app. It appears at the left of the status bar and only updates when you switch apps, so it costs no battery. "
                + "You can move it with the Layout tab; it stays hidden on the home screen and the lock screen.", cfg.backEnabled,
                v -> { cfg.backEnabled = v; apply(); refresh(); });
        endGroup(null);
        if (!cfg.backEnabled) return;

        group("backlook", "Back button look", null, true);
        cardReset((c, d) -> { c.backStyle = d.backStyle; c.backSizePct = d.backSizePct; c.backChars = d.backChars; });
        segmentedBlock("Style", new String[]{"Capsule", "Classic"}, cfg.backStyle == 1 ? 0 : 1,
                v -> { cfg.backStyle = v == 0 ? 1 : 0; apply(); refresh(); });
        divider();
        slider("Size", 70, 140, cfg.backSizePct, v -> v + "%", v -> cfg.backSizePct = v, 100);
        if (advanced("backlook")) {
            divider();
            slider("Letters of the app name", 3, 14, cfg.backChars, v -> v + " letters", v -> cfg.backChars = v, 7);
        }
        endGroup(cfg.backStyle == 1 ? "“Capsule” copies One UI's live activity look: the app's icon in a pill of the app's colour."
                : "“Classic” is a simple soft pill with an arrow.");

        group("backfade", "When it appears", null, true);
        cardReset((c, d) -> c.backFadeSec = d.backFadeSec);
        segmentedBlock("Fade to plain text after", new String[]{"Never", "5 s", "10 s", "30 s"},
                cfg.backFadeSec <= 0 ? 0 : cfg.backFadeSec <= 5 ? 1 : cfg.backFadeSec <= 10 ? 2 : 3,
                v -> { cfg.backFadeSec = new int[]{0, 5, 10, 30}[v]; apply(); });
        endGroup("The pill melts into dim text after this long, as if it's been too long to go back. It returns in full when you switch apps.");

        final String[] liveNames = {"Keep both side by side", "Back button as plain text", "Hide the back button",
                "Stack (flick to swap)", "Merge (a small back tab on the live activity)"};
        group("backlive", "Live activities", null, true);
        cardReset((c, d) -> c.backLive = d.backLive);
        choiceRow("While one is showing", liveNames, cfg.backLive,
                v -> { cfg.backLive = v; apply(); refresh(); });
        endGroup("Music and other live activities share the left side with this button. More about Stack and Merge is in the cheat sheet (System tab).");
    }

    // ---------------------------------------------------------------- Gestures tab

    private void buildGestures() {
        group("Status bar");
        cardReset((c, d) -> c.dtSleep = d.dtSleep);
        switchRow("Double-tap to turn the screen off", "Double-tap any empty spot in the status bar to lock the screen. Swiping down still opens the notification panel as usual. Items such as the clock keep their own tap actions below.", cfg.dtSleep,
                v -> { cfg.dtSleep = v; apply(); });
        endGroup(null);
        for (int item = 0; item < ITEM_TITLES.length; item++) {
            final int it = item;
            group("gest" + item, ITEM_TITLES[item], null, true);
            cardReset((c, d) -> { for (int g = 0; g < 3; g++) { int sl = it * 3 + g; c.setAction(sl, d.action(sl)); c.apps[sl] = d.apps[sl]; } });
            gestureRow(item * 3);
            divider();
            gestureRow(item * 3 + 2);
            if (advanced("gest" + item)) {
                divider();
                gestureRow(item * 3 + 1);
            }
            endGroup(item == 3 ? "Tap the front camera hole in the status bar. Samsung Finder opens by default. "
                    + "Double-tap makes a single tap wait a moment, so only use it if you need it." : "Double-tap makes a single tap wait a moment, so only use it if you need it.");
        }
        group("Feedback");
        cardReset((c, d) -> { c.tapHaptic = d.tapHaptic; c.tipsMode = d.tipsMode; });
        switchRow("Vibrate on tap", "A small buzz when an action runs. Nothing opens over the lock screen.", cfg.tapHaptic, v -> { cfg.tapHaptic = v; apply(); });
        if (advanced("feedback")) {
            divider();
            segmentedBlock("Remind me what I set up", new String[]{"Never", "Sometimes", "Often"}, cfg.tipsMode,
                    v -> { cfg.tipsMode = v; apply(); });
            divider();
            actionRow("Show a reminder now", "A tip scrolls across the left of the status bar", cAccent,
                    v -> send(new Intent(Config.ACTION_TIP)));
        }
        endGroup(null);
    }

    /** "Tap: Samsung Finder \u00b7 Long-press: Close background apps", or "Nothing set". */
    private String gestureSummary(int item) {
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < 3; g++) {
            int slot = item * 3 + g;
            if (cfg.action(slot) == Config.ACT_NONE) continue;
            String n = actionName(slot);
            int p = n.indexOf(" (");
            if (p > 0) n = n.substring(0, p);
            if (sb.length() > 0) sb.append(" \u00b7 ");
            sb.append(GESTURE_TITLES[g]).append(": ").append(n);
        }
        return sb.length() == 0 ? "Nothing set" : sb.toString();
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
        r.addView(labelBlock("tap", GESTURE_TITLES[slot % 3], null, cText, dp(130)), new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = Ui.text(this, actionName(slot), Ui.T_SECTION, a == Config.ACT_NONE ? cSub : pal.accInk, 600);
        val.setMaxWidth(dp(190));
        val.setGravity(Gravity.END);
        r.addView(val);
        if (a != Config.ACT_NONE) {
            TextView tryIt = textButton("Test", v -> send(new Intent(Config.ACTION_TEST)
                    .putExtra("act", cfg.action(slot)).putExtra("app", cfg.app(slot))));
            tryIt.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.T_VALUE);
            r.addView(tryIt);
        }
    }

    /** Grouped list of actions in a dialog. */
    private void actionPicker(int slot) {
        Ui.Dlg[] dlg = new Ui.Dlg[1];
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(8));
        int current = cfg.action(slot);
        LinearLayout saved = cur;
        cur = list;
        pickRow(ACT_LABEL[Config.ACT_NONE], current == Config.ACT_NONE, v -> { pick(slot, Config.ACT_NONE); dlg[0].dismiss(); });
        for (Object[] g : ACT_GROUPS) {
            TextView t = new TextView(this);
            t.setText((String) g[0]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.T_VALUE);
            t.setTypeface(Ui.font(500));
            t.setTextColor(cSub);
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
        String when = GESTURE_TITLES[slot % 3].toLowerCase(Locale.ROOT);
        String what = ITEM_TITLES[slot / 3].toLowerCase(Locale.ROOT);
        dlg[0] = new Ui.Dlg(this, pal).title("On " + when + " of the " + what)
                .view(list).button(0, "Cancel", null).show();
    }

    private void pickRow(String label, boolean selected, View.OnClickListener l) {
        cur.addView(Ui.pickRow(this, pal, label, selected, l), new LinearLayout.LayoutParams(-1, -2));
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
        Ui.Dlg[] dlg = new Ui.Dlg[1];
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        String curApp = cfg.action(slot) == Config.ACT_APP ? cfg.app(slot) : null;
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
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.T_BODY);
            boolean isSel = a[1].equals(curApp);
            t.setTextColor(isSel ? pal.accInk : cText);
            if (isSel) t.setTypeface(Ui.font(700));
            t.setPadding(dp(14), 0, 0, 0);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            if (isSel) r.addView(Ui.icon(this, "check", pal.accInk, 20));
            r.setOnClickListener(v -> {
                cfg.apps[slot] = (String) a[1];
                dlg[0].dismiss();
                pick(slot, Config.ACT_APP);
            });
            list.addView(r, new LinearLayout.LayoutParams(-1, -2));
        }
        dlg[0] = new Ui.Dlg(this, pal).title("Which app should open?").view(list)
                .button(0, "Cancel", null).show();
    }

    private Boolean helperRunning;   // null = not checked yet
    private boolean helperChecking;

    private void checkHelper() {
        if (helperChecking) return;
        helperChecking = true;
        new Thread(() -> {
            boolean on0 = Helper.isRunning();
            final boolean on = on0 && !Helper.isCurrent(this) ? Helper.start(this) : on0;   // app was updated: run the new helper
            runOnUiThread(() -> { helperChecking = false; boolean changed = helperRunning == null || helperRunning != on; helperRunning = on; if (changed && tab == T_SYSTEM) refresh(); });
        }).start();
    }

    private int gl(String key, int def) {
        try { return android.provider.Settings.Global.getInt(getContentResolver(), key, def); } catch (Throwable t) { return def; }
    }

    private String glStr(String key) {
        try { String v = android.provider.Settings.Global.getString(getContentResolver(), key); return v == null ? "" : v; } catch (Throwable t) { return ""; }
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

    private void buildPanel() {
        String setting = null;
        try { setting = android.provider.Settings.Global.getString(getContentResolver(), PanelLayout.SETTING); } catch (Throwable ignored) {}
        final Set<String> hidden = new HashSet<>();
        final List<String> all = PanelLayout.everything(setting, PanelLayout.DEFAULTS, hidden);
        final List<String> pinned = PanelLayout.pins(gs(PanelLayout.PINS));
        pinned.removeAll(hidden);
        int total = PanelLayout.DEFAULTS.length;

        group("panelcards", "Panel cards", null, true);
        cardResetAction(() -> putGl(PanelLayout.SETTING, "", PanelLayout.PINS, ""));
        for (int page = PanelLayout.PAGE_ALL; page < PanelLayout.PAGE_NAMES.length; page++) {
            TextView hd = Ui.text(this, page == PanelLayout.PAGE_ALL ? "All page only" : PanelLayout.PAGE_NAMES[page], Ui.T_VALUE, cSub, 600);
            hd.setPadding(0, dp(page == PanelLayout.PAGE_ALL ? 0 : Ui.S3), 0, dp(2));
            cur.addView(hd);
            List<String> ids = new ArrayList<>();
            for (String id : all) if (PanelLayout.PAGES[PanelLayout.indexOf(id)] == page) ids.add(id);
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) divider();
                panelRow(all, hidden, ids, i, pinned);
            }
        }
        endGroup((total - hidden.size()) + " of " + total + " cards shown" + (setting == null || setting.isEmpty() ? ", standard order" : ", your order")
                + ".\n\nSwitch a card off to hide it. Press and hold the handle on the left of a card and drag it up or down to move it within its page. "
                + "Tap the pin to keep a card at the top of the All page (up to " + PanelLayout.PIN_MAX + "; they show in the order you pinned them). "
                + "Reset shows every card again in the standard order.");

        final String tiles = gs(PanelLayout.TILES);
        group("tiles", "Tile size", null, true);
        cardResetAction(() -> putGl(PanelLayout.TILES, ""));
        for (int i = 0; i < PanelLayout.TILE_CARDS.length; i++) {
            final String id = PanelLayout.TILE_CARDS[i];
            if (i > 0) divider();
            segmentedBlock(PanelLayout.TITLES[PanelLayout.indexOf(id)], new String[]{"Wide", "Small (icon only)"}, PanelLayout.small(tiles, id) ? 1 : 0,
                    v -> putGl(PanelLayout.TILES, PanelLayout.withSmall(tiles, id, v == 1)));
        }
        endGroup("Switch cards show as tiles in the panel. Wide shows an icon, the name and the state, two to a row; Small shows only the icon, four to a row. "
                + "A tile is solid when it is on and frosted glass when it is off. Tap a tile to switch it; tap the \u201ci\u201d on a wide tile, or press and hold a small one, for help.");

        group("allpage", "The All page", null, true);
        cardResetAction(() -> putGl(PanelLayout.ALLFILL, "0"));
        switchRow("Fill it with the cards I use most", "The All page lists every card that is shown, in your order, and its order never changes. "
                + "Switch this on and it collects the five cards you used last instead, newest first.",
                gl(PanelLayout.ALLFILL, 0) == 1, v -> putGl(PanelLayout.ALLFILL, v ? "1" : "0"));
        if (advanced("allpage")) {
            divider();
            actionRow("Empty the used-cards list", "Starts the list of the cards you used last again from nothing", cAccent, v -> putGl(PanelLayout.RECENT, ""));
        }
        endGroup(null);

        group("aboutpanel", "About the panel", null, true);
        infoRow("The Performance panel", "It opens from the CPU or GPU chip on the recent apps screen and has four pages: All, Speed, Battery and Memory.\n\n"
                + "All = every shown card in your order (or the five cards you used last, if you chose that below). "
                + "Speed = modes, CPU and GPU limits, resting cores, Vulkan graphics, options. "
                + "Battery = power saving switch, screen smoothness (120 / 60 Hz), battery report, battery health with a shortcut to Samsung's battery protection. "
                + "Memory = memory and swap, clean-up buttons, busiest apps with Stop.\n\n"
                + "The panel opens on the page you used last.");
        endGroup(null);
    }

    /** The root helper, the CPU / GPU speed limits and the Overview chips (System tab). */
    private void buildHelperSpeed() {
        group("helper", "Root helper", null, true);
        cardResetAction(() -> { prefs.edit().putBoolean("helperAtBoot", false).apply(); Helper.setAtBoot(this, false); refresh(); });
        statusRow("Status", helperRunning == null ? "Not checked yet. Checking needs root." : helperRunning ? "Running" : "Not running");
        divider();
        actionRow("Check again", null, cAccent, v -> { helperRunning = null; checkHelper(); });
        divider();
        switchRow("Run the helper", "Closing apps and speed limits need root, so this small helper does them for the Overview screen. Allow root for this app when asked. "
                        + (helperRunning == null ? "It has not been checked yet." : helperRunning ? "It is running." : "It is not running – the RAM, swap and speed buttons won't do anything."),
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
        if (advanced("helper")) {
            divider();
            switchRow("Start it when the phone starts", "Only if the status bar mod is on. Speed limits never survive a restart.",
                    prefs.getBoolean("helperAtBoot", false), v -> { prefs.edit().putBoolean("helperAtBoot", v).apply(); Helper.setAtBoot(this, v); });
        }
        endGroup("Idle, it only checks once a second whether you pressed something; it doesn't keep the phone awake.");

        // ---- speed limits
        final int[] pcts = {85, 70, 55, 40};
        final String[] pctNames = {"85% – barely noticeable", "70% – cooler, still smooth", "55% – battery saver", "40% – lowest (slow)"};
        int cpuPick = gl("sbmods_cpu_pick", 55), gpuPick = gl("sbmods_gpu_pick", 55);
        group("speed", "CPU and GPU speed", null, true);
        cardResetAction(() -> putGl("sbmods_cpu_pick", "55", "sbmods_gpu_pick", "55", "sbmods_cpu_ps", "0", "sbmods_limit_min", "15", "sbmods_cool_at", "360"));
        statusRow("Right now", speedNow());
        divider();
        int ci = 2, gi = 2;
        for (int i = 0; i < pcts.length; i++) { if (pcts[i] == cpuPick) ci = i; if (pcts[i] == gpuPick) gi = i; }
        choiceRow("CPU quick limit", pctNames, ci, i -> putGl("sbmods_cpu_pick", String.valueOf(pcts[i])));
        divider();
        choiceRow("GPU quick limit", pctNames, gi, i -> putGl("sbmods_gpu_pick", String.valueOf(pcts[i])));
        divider();
        actionRow("Full speed now", "Ends any CPU / GPU limit and cool-down. Samsung's own heat protection always stays on.", cAccent, v ->
                putGl("sbmods_cpu_pct", "0", "sbmods_gpu_pct", "0", "sbmods_cool", "0"));
        if (advanced("speed")) {
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
        }
        endGroup("A limit is a share of the chip's real maximum, and only speeds the phone lists are used. "
                + "Samsung lowers the maximum by itself when the phone is warm – that's the “heat limit” you may see; this app never removes it. "
                + "The quick limit is what a long-press on the CPU / GPU chip turns on; the Performance panel (tap) lets you pick any level.");

        group("Overview screen");
        cardResetAction(() -> putGl("sbmods_recents_chip", "1"));
        switchRow("Show the chips", "RAM, swap and temperature at the top; CPU and GPU at the bottom of the recent apps screen.",
                gl("sbmods_recents_chip", 1) != 0, v -> putGl("sbmods_recents_chip", v ? "1" : "0"));
        endGroup(null);
    }

    // ---------------------------------------------------------------- More tab

    private static final String[] PANEL_ICONS = {"spark", "bolt", "cpu", "gauge", "cpu", "layers", "sys", "leaf", "gauge", "batt", "shield", "mem", "apps"};

    /** One card of the Performance panel: a drag handle, its name and a shown / hidden switch. */
    private void panelRow(final List<String> all, final Set<String> hidden, final List<String> ids, final int i, final List<String> pinned) {
        final String id = ids.get(i);
        final int n = ids.size();
        Ui.Switch sw = new Ui.Switch(this, pal);
        LinearLayout r = rowShell(v -> sw.toggle());
        // the drag handle: press, move up or down by whole rows, let go
        FrameLayout grip = new FrameLayout(this);
        ImageView gi = Ui.icon(this, "grip", cSub, 22);
        ((FrameLayout.LayoutParams) gi.getLayoutParams()).gravity = Gravity.CENTER;
        grip.addView(gi);
        grip.setContentDescription("Move " + PanelLayout.TITLES[PanelLayout.indexOf(id)]);
        grip.setOnTouchListener(new View.OnTouchListener() {
            float downY;
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                float pitch = r.getHeight() + dp(0.7f);
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        r.setElevation(dp(6));
                        r.setBackground(round(pal.accSoft, dp(Ui.R_ICON), 0, 0));
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float dy = Math.max(-i * pitch, Math.min((n - 1 - i) * pitch, e.getRawY() - downY));
                        r.setTranslationY(dy);
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL: {
                        float dy = r.getTranslationY();
                        r.setTranslationY(0);
                        r.setElevation(0);
                        r.setBackground(ripple(0));
                        int target = Math.max(0, Math.min(n - 1, i + Math.round(dy / pitch)));
                        if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP && target != i) moveCard(all, hidden, ids, id, target);
                        return true;
                    }
                }
                return false;
            }
        });
        // the same move for TalkBack and keyboards: two actions on the handle
        grip.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (i > 0) info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(ACT_MOVE_UP, "Move up"));
                if (i < n - 1) info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(ACT_MOVE_DOWN, "Move down"));
            }
            @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == ACT_MOVE_UP && i > 0) { moveCard(all, hidden, ids, id, i - 1); return true; }
                if (action == ACT_MOVE_DOWN && i < n - 1) { moveCard(all, hidden, ids, id, i + 1); return true; }
                return super.performAccessibilityAction(host, action, args);
            }
        });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(40), dp(Ui.TAP));
        gp.setMarginStart(dp(-8));
        r.addView(grip, gp);
        final String title = PanelLayout.TITLES[PanelLayout.indexOf(id)];
        final boolean isPinned = pinned.contains(id);
        r.addView(labelBlock(PANEL_ICONS[PanelLayout.indexOf(id)], title, null, cText, dp(56 + 40 + 40)), new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout pin = new FrameLayout(this);
        ImageView pi = Ui.icon(this, "pin", isPinned ? cAccent : cSub, 20);
        ((FrameLayout.LayoutParams) pi.getLayoutParams()).gravity = Gravity.CENTER;
        pin.addView(pi);
        pin.setBackground(ripple(0));
        pin.setClickable(true);
        pin.setContentDescription(isPinned ? "Unpin " + title + " from the All page" : "Pin " + title + " to the All page");
        pin.setOnClickListener(v -> {
            String cur = gs(PanelLayout.PINS);
            if (PanelLayout.pins(cur).contains(id)) putGl(PanelLayout.PINS, PanelLayout.withoutPin(cur, id));   // read fresh: the panel may have changed the pins
            else if (hidden.contains(id)) Toast.makeText(this, "Switch this card on first.", Toast.LENGTH_SHORT).show();
            else if (PanelLayout.pins(cur).size() >= PanelLayout.PIN_MAX) Toast.makeText(this, "You can pin up to " + PanelLayout.PIN_MAX + ". Unpin one first.", Toast.LENGTH_SHORT).show();
            else putGl(PanelLayout.PINS, PanelLayout.withPin(cur, id));
        });
        r.addView(pin, new LinearLayout.LayoutParams(dp(40), dp(Ui.TAP)));
        sw.setChecked(!hidden.contains(id));
        sw.setContentDescription(PanelLayout.TITLES[PanelLayout.indexOf(id)]);
        sw.setOnChange(v -> {
            if (v) hidden.remove(id); else hidden.add(id);
            if (v) putGl(PanelLayout.SETTING, PanelLayout.write(all, hidden));
            else putGl(PanelLayout.SETTING, PanelLayout.write(all, hidden), PanelLayout.PINS, PanelLayout.withoutPin(gs(PanelLayout.PINS), id));   // hiding a card unpins it
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(56), dp(Ui.TAP));
        sp.setMarginEnd(dp(-4));
        r.addView(sw, sp);
    }

    private static final int ACT_MOVE_UP = 0x7f0b0001, ACT_MOVE_DOWN = 0x7f0b0002;

    /** Moves a card to a new place among the cards of its page; the other pages keep their places. */
    private void moveCard(List<String> all, Set<String> hidden, List<String> ids, String id, int target) {
        List<String> order = new ArrayList<>(ids);
        order.remove(id);
        order.add(target, id);
        int k = 0;
        for (int j = 0; j < all.size(); j++) if (ids.contains(all.get(j))) all.set(j, order.get(k++));
        putGl(PanelLayout.SETTING, PanelLayout.write(all, hidden));
    }

    /** Everything back to the starting values: the status bar options, the panel's cards, and which "Advanced" rows are open. */
    private void restoreDefaults() {
        cfg = new Config();
        apply();
        SharedPreferences.Editor ed = prefs.edit();
        for (String k : prefs.getAll().keySet()) if (k.startsWith("adv_") || k.startsWith("open_")) ed.remove(k);
        ed.apply();
        buildPreviewArea();
        putGl(PanelLayout.SETTING, "", PanelLayout.TILES, "", PanelLayout.ALLFILL, "0", PanelLayout.PINS, "");
    }

    private String gs(String key) {
        try { return android.provider.Settings.Global.getString(getContentResolver(), key); } catch (Throwable t) { return null; }
    }

    private void buildMore() {
        group("cheat", "Cheat sheet: what taps do", "In the app, on the status bar, on the Overview screen", false);
        infoRow("In this app",
                "Preview: press an item and drag it; drop it on the grey tray to put it back in Samsung's place.\n"
                + "Sliders: tap “Reset” next to a value to go back to the starting value.\n"
                + "System tab: every status bar item can have a tap, double-tap and long-press action. “Test” tries it.");
        divider();
        infoRow("Status bar",
                "Clock, temperature and battery: tap, double-tap and long-press do whatever you chose in the System tab.\n"
                + "Back button: tap = jump back to the previous app.\n"
                + "Camera hole: tap = Samsung Finder (or your choice). Empty spot, double-tap (if on) = lock the screen.");
        divider();
        infoRow("Back button and live activities",
                "\u201cMerge\u201d fuses a small back tab (the chevron and the previous app's icon, no name) onto the right end of the live activity; tap the tab to go back.\n"
                + "\u201cStack\u201d lets them share one spot, with the back button's edge peeking out underneath. Flick sideways on the live activity (or long-press it) to bring the back button forward. "
                + "Tap the button to go back, flick it away or double-tap it to return to the live activity.");
        divider();
        infoRow("Overview screen (recent apps)",
                "RAM: tap = close background apps. Swap: tap = deep clean. Temp: tap = cool-down on / off. Long-press any of them = Device care.\n"
                + "CPU (bottom right) and GPU (bottom left) show the live speed and what holds it back (full speed, Samsung's heat limit, or your limit). "
                + "Tap = Performance panel, long-press = quick limit on / off.\n"
                + "Performance panel: four pages. All = every shown card (or the five you used last, if you chose that). "
                + "Speed = modes, CPU and GPU limits, resting cores, Vulkan graphics, options. "
                + "Battery = power saving switch, screen smoothness (120 / 60 Hz), battery report, battery health with a shortcut to Samsung's battery protection. "
                + "Memory = memory and swap, clean-up buttons, busiest apps with Stop. "
                + "The panel opens on the page you used last. Choose which cards show and in what order in the Panel tab.");
        endGroup(null);

        group("Screen care");
        switchRow("Protect against screen burn-in", "Nudges the status bar a few pixels every couple of minutes so fixed icons don't leave marks. Only runs while the screen is on.",
                cfg.pixelShift, v -> { cfg.pixelShift = v; apply(); });
        endGroup(null);

        group("wrong", "If something looks wrong", "Restart the status bar, turn the mod back on", false);
        actionRow("Restart the status bar", "Reloads it. Use this after installing an update.", cAccent, v -> restartSystemUi());
        divider();
        actionRow("Turn the mod back on", "If the status bar crashed and the mod switched itself off, tap this and then restart the status bar.",
                cAccent, v -> {
                    send(new Intent(Config.ACTION_RESET_GUARD));
                    Toast.makeText(this, "Done. Now restart the status bar.", Toast.LENGTH_LONG).show();
                });
        endGroup("After you restart the phone without root, the status bar goes back to Samsung's own.");

        group("Start over");
        actionRow("Restore defaults", "Every option goes back to how it was at the start, and the Performance panel shows all its cards again", cErr, v ->
                new Ui.Dlg(this, pal)
                        .title("Restore defaults?")
                        .message("Every option in this app goes back to its starting value, and the Performance panel goes back to its standard cards and order. You can't undo this.")
                        .button(0, "Cancel", null)
                        .button(2, "Restore", () -> restoreDefaults()).show());
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
        gotPong = false; pingDone = false;
        setStatus("Checking…", "Checking the status bar…", 0);
        send(new Intent(Config.ACTION_APPLY).putExtras(cfg.toBundle())); // also re-syncs settings
        h.postDelayed(() -> {
            pingDone = true;
            if (!gotPong) rescore();
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
        int c = statusState == 1 ? pal.accInk : statusState == 2 ? cErr : cWarn;
        String label = statusState == 1 && "Working".equals(statusShort) ? "Active" : statusShort;
        boolean tint = statusState != 1;
        int probs = 0;
        for (SelfCheck.Finding f : findings) if (f.severity == SelfCheck.PROBLEM) probs++;
        if (!findings.isEmpty() && !"Checking…".equals(statusShort)) {   // the self-check's worst state wins
            int n = probs > 0 ? probs : findings.size();
            label = n + (probs > 0 ? (n == 1 ? " problem" : " problems") : (n == 1 ? " thing to check" : " things to check"));
            c = probs > 0 ? cErr : cWarn;
            tint = true;
        }
        statusPill.setText("●  " + label);
        statusPill.setTextColor(c);
        statusPill.setBackground(round(tint ? (c & 0x00FFFFFF) | 0x22000000 : pal.accSoft, dp(16), 0, 0));
    }

    private void showStatus(Intent i) {
        modVersion = String.valueOf(i.getStringExtra("version"));
        String side = i.getStringExtra("clockSide");
        if (side != null && !"-".equals(side)) clockSide = side;
        String shortText, detail;
        int state = 1;
        pingDone = true;
        modTripped = i.getBooleanExtra("guardTripped", false);
        modDisabled = !modTripped && i.getBooleanExtra("disabled", false);
        if (modTripped) {
            state = 2;
            shortText = "Switched off";
            detail = "The mod switched itself off because the status bar crashed three times right after loading it.\n\n"
                    + "Open the System tab, tap “Turn the mod back on”, then restart the status bar.";
        } else if (modDisabled) {
            state = 2;
            shortText = "Stopped";
            detail = SelfCheck.MOD_STOPPED;
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
        rescore();
        updatePreview();
    }

    private void statusDialog() {
        final Ui.Dlg d = new Ui.Dlg(this, pal).title("Status").message(statusDetail)
                .button(2, "Check again", () -> { ping(); selfCheck(true); })
                .button(1, "Restart status bar", () -> restartSystemUi())
                .button(0, "Close", null);
        if (!findings.isEmpty()) {
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(Ui.S5), 0, dp(Ui.S4), 0);
            for (SelfCheck.Finding f : findings) body.addView(findingView(f, d::dismiss));
            d.view(body);
        }
        d.show();
    }

    // ---------------------------------------------------------------- self-check

    /** Runs the checks on a background thread (several need root) and caches the result. Only while this screen is open. */
    private void selfCheck(boolean force) {
        long t = android.os.SystemClock.elapsedRealtime();
        if (scRunning || !resumed || (!force && scFacts != null && t - scAt < 30000)) return;
        scRunning = true;
        final boolean smart = cfg.smartOn;
        new Thread(() -> {
            SelfCheck.Facts f = SelfCheck.gather(this, smart);
            runOnUiThread(() -> {
                scRunning = false; scFacts = f; scAt = android.os.SystemClock.elapsedRealtime();
                helperRunning = f.rootOk && f.helperRunning;
                rescore();
            });
        }).start();
    }

    /** Re-applies the rules to the cached facts (no root), then redraws the pill and, if the list changed, the System tab. */
    private void rescore() {
        if (scFacts != null) {
            scFacts.modReplied = gotPong; scFacts.pingDone = pingDone; scFacts.modTripped = modTripped; scFacts.modDisabled = modDisabled;
            findings = SelfCheck.evaluate(scFacts);
        }
        StringBuilder sb = new StringBuilder(scFacts == null ? "-" : "+");
        for (SelfCheck.Finding f : findings) sb.append(f.fix).append(f.severity);
        boolean changed = !sb.toString().equals(scSig);
        scSig = sb.toString();
        refreshStatusPill();
        if (changed && tab == T_SYSTEM) refresh();
    }

    private View findingView(SelfCheck.Finding f, Runnable before) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(Ui.S2), 0, dp(Ui.S2));
        android.text.SpannableString st = new android.text.SpannableString("●  " + f.text);
        st.setSpan(new android.text.style.ForegroundColorSpan(f.severity == SelfCheck.PROBLEM ? cErr : cWarn), 0, 1, 0);
        row.addView(Ui.text(this, st, Ui.T_BODY, cText, 400), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(textButton(f.fixLabel, v -> { if (before != null) before.run(); runFix(f.fix); }));
        return row;
    }

    /** The System tab's Self-check card: the same list as the status dialog. */
    private void buildSelfCheck() {
        group("Self-check");
        if (scFacts == null) statusRow("Checking…", null);
        else if (findings.isEmpty()) statusRow("Everything is working", null);
        else for (SelfCheck.Finding f : findings) { cur.addView(findingView(f, null)); divider(); }
        if (scFacts != null && findings.isEmpty()) divider();
        actionRow("Check again", null, cAccent, v -> selfCheck(true));
        endGroup(null);
    }

    private void runFix(String fix) {
        if (SelfCheck.RESTART_UI.equals(fix) || SelfCheck.RESET_GUARD.equals(fix)) {
            if (SelfCheck.RESET_GUARD.equals(fix)) { send(new Intent(Config.ACTION_RESET_GUARD)); send(new Intent(Config.ACTION_RESET_GUARD)); }
            restartSystemUi();
            h.postDelayed(() -> selfCheck(true), 9000);   // once, after the status bar has come back and answered
            return;
        }
        new Thread(() -> {
            boolean ok;
            switch (fix) {
                case SelfCheck.START_HELPER: ok = Helper.start(this); break;
                case SelfCheck.CLEAR_CMD: ok = Helper.putGlobal("sbmods_cmd", "none"); break;
                case SelfCheck.VULKAN_ON: ok = Helper.putGlobal("sbmods_cmd", "gfx:vulkan"); break;
                default:   // FULL_SPEED: helper down = the existing restore script; helper up = zero the controls like "Full speed now"
                    if (Helper.isRunning()) ok = Helper.putGlobal("sbmods_cpu_pct", "0", "sbmods_gpu_pct", "0", "sbmods_cool", "0", "sbmods_cores", "0", "sbmods_until", "0");
                    else ok = Helper.stopAndRestore() || Helper.putGlobal("sbmods_cpu_pct", "0", "sbmods_gpu_pct", "0", "sbmods_cool", "0", "sbmods_cores", "0", "sbmods_until", "0");
            }
            final boolean fOk = ok;
            runOnUiThread(() -> {
                if (!fOk) Toast.makeText(this, SelfCheck.START_HELPER.equals(fix) ? "The helper did not start" : "That did not work", Toast.LENGTH_LONG).show();
                selfCheck(true);
            });
        }).start();
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

    private int dp(float v) { return Ui.dp(this, v); }

    private GradientDrawable round(int fill, int radius, int stroke, int strokeColor) { return Ui.round(fill, radius, stroke, strokeColor); }

    private RippleDrawable ripple(int fill) {
        GradientDrawable content = fill == 0 ? null : round(fill, 0, 0, 0);
        return Ui.ripple(pal, content, round(Color.WHITE, 0, 0, 0));
    }

    private boolean groupOpen = true;     // kept for endGroup: cards are always open now (less-used options sit behind an "Advanced" row)
    private LinearLayout sink;
    private View infoSlot;                // the card header's info button (hidden until endGroup gets a help text)
    private View resetSlot;               // the card header's Reset button (hidden until cardReset / cardResetAction)
    private String cardTitle;

    /** A card with its title inside. */
    private void group(String title) {
        group(null, title, null, true);
    }

    /**
     * A solid rounded card with its title inside (14 sp, grey). Its header has an info "i" (the long caption that used
     * to sit under a card, see endGroup) and a Reset button (see cardReset). `key` / `summary` / `openByDefault` are
     * kept for the callers; cards no longer collapse - the less-used options sit behind an "Advanced" row (see advanced).
     */
    private void group(String key, String title, String summary, boolean openByDefault) {
        groupOpen = true;
        cardTitle = title;
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.card(this, pal));
        c.setClipToOutline(true);
        c.setPadding(dp(Ui.S4), 0, dp(Ui.S4), dp(4));
        page.addView(c, cardParams());

        LinearLayout hdr = new LinearLayout(this);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setMinimumHeight(dp(44));
        hdr.addView(Ui.text(this, title == null ? "" : title, Ui.T_SECTION, cSub, 600), new LinearLayout.LayoutParams(-2, -2));
        infoSlot = Ui.infoButton(this, pal, title == null ? "this card" : title, null);
        infoSlot.setVisibility(View.GONE);
        hdr.addView(infoSlot, new LinearLayout.LayoutParams(dp(Ui.TAP), dp(44)));
        hdr.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        LinearLayout reset = new LinearLayout(this);
        reset.setGravity(Gravity.CENTER_VERTICAL);
        reset.setPadding(dp(10), 0, dp(10), 0);
        reset.setBackground(ripple2(null, dp(20)));
        reset.addView(Ui.icon(this, "reset", pal.accInk, 16));
        TextView rt = Ui.text(this, "Reset", Ui.T_SECTION, pal.accInk, 600);
        rt.setPadding(dp(4), 0, 0, 0);
        reset.addView(rt);
        reset.setContentDescription("Reset " + (title == null ? "this card" : title));
        reset.setVisibility(View.GONE);
        resetSlot = reset;
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, dp(Ui.TAP));
        rp.setMargins(0, dp(-2), dp(-10), dp(-2));
        hdr.addView(reset, rp);
        c.addView(hdr, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        c.addView(body, new LinearLayout.LayoutParams(-1, -2));
        cur = body;
    }

    /** This card's Reset button: sets the options of this card (and only these) back to their starting values. `fn` copies them from a fresh Config (second argument). */
    private void cardReset(java.util.function.BiConsumer<Config, Config> fn) {
        cardResetAction(() -> { fn.accept(cfg, new Config()); apply(); refresh(); });
    }

    private void cardResetAction(Runnable r) {
        if (resetSlot == null) return;
        resetSlot.setVisibility(View.VISIBLE);
        resetSlot.setOnClickListener(v -> r.run());
    }

    /** A row "Advanced" (or `label`) that opens or closes the less-used options below it; the state is remembered per card. Returns true when open. */
    private boolean advanced(String key) { return advanced(key, "Advanced"); }

    private boolean advanced(String key, String label) {
        final boolean open = prefs.getBoolean("adv_" + key, false);
        if (cur.getChildCount() > 0) divider();
        LinearLayout r = rowShell(v -> { prefs.edit().putBoolean("adv_" + key, !open).apply(); refresh(); });
        r.addView(labelBlock("sys", label, null, cText, dp(28)), new LinearLayout.LayoutParams(0, -2, 1));
        ImageView chev = Ui.icon(this, "chev", cSub, 20);
        if (open) chev.setRotation(90);
        r.addView(chev, new LinearLayout.LayoutParams(dp(20), dp(20)));
        r.setStateDescription(open ? "Open" : "Closed");
        r.setContentDescription(label);
        return open;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(Ui.S3);
        return lp;
    }

    /** Closes the card. The plain-English help that used to sit under it opens from the "i" in its header. */
    private void endGroup(String help) {
        cur = page;
        if (help != null && groupOpen && infoSlot != null) {
            final String title = cardTitle == null || cardTitle.isEmpty() ? "About this" : cardTitle, text = help;
            infoSlot.setVisibility(View.VISIBLE);
            infoSlot.setOnClickListener(v -> showInfo(title, text));
        }
        groupOpen = true;
    }

    /** The info sheet: a frosted bottom sheet with a title, the text and "Got it". No realtime blur. */
    private void showInfo(String title, String text) {
        final android.app.Dialog dl = new android.app.Dialog(this);
        dl.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(Ui.S5), dp(10), dp(Ui.S5), dp(Ui.S5));
        sheet.setBackground(new Ui.Glass(pal.night ? 0xD11E242E : 0xE0FFFFFF, pal.glassBd, pal.glassHi, dp(36), Math.max(1, dp(1))));
        View handle = new View(this);
        handle.setBackground(round((pal.tx2 & 0x00FFFFFF) | 0x8C000000, dp(3), 0, 0));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(40), dp(5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(20);
        sheet.addView(handle, hp);
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(round(pal.accSoft, dp(16), 0, 0));
        ImageView ic = Ui.icon(this, "info", pal.accInk, 26);
        ((FrameLayout.LayoutParams) ic.getLayoutParams()).gravity = Gravity.CENTER;
        badge.addView(ic);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(48), dp(48));
        bp.bottomMargin = dp(Ui.S4);
        sheet.addView(badge, bp);
        TextView t = Ui.text(this, title, Ui.T_SHEET, cText, 700);
        t.setLetterSpacing(-0.01f);
        sheet.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView body = Ui.text(this, text, Ui.T_BODY, cSub, 400);
        body.setLineSpacing(0, 1.25f);
        body.setPadding(0, dp(Ui.S2), 0, dp(Ui.S5));
        int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.5f);
        sheet.addView(new MaxHeightScroll(this, maxH, body), new LinearLayout.LayoutParams(-1, -2));
        TextView got = Ui.text(this, "Got it", Ui.T_BODY, pal.onAcc, 700);
        got.setGravity(Gravity.CENTER);
        got.setBackground(ripple2(round(cAccent, dp(Ui.R_BUTTON), 0, 0), dp(Ui.R_BUTTON)));
        got.setOnClickListener(v -> dl.dismiss());
        sheet.addView(got, new LinearLayout.LayoutParams(-1, dp(56)));
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(8), 0, dp(8), dp(8));
        wrap.addView(sheet);
        dl.setContentView(wrap);
        android.view.Window w = dl.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(pal.night ? 0.55f : 0.42f);
        }
        dl.show();
    }

    /** A scroll view that never grows past maxH (long help texts scroll inside the sheet). */
    private static final class MaxHeightScroll extends ScrollView {
        private final int maxH;
        MaxHeightScroll(Context c, int maxH, View child) { super(c); this.maxH = maxH; addView(child); setVerticalScrollBarEnabled(false); }
        @Override protected void onMeasure(int w, int h) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)); }
    }

    private RippleDrawable ripple2(GradientDrawable fill, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fill, round(Color.WHITE, radius, 0, 0));
    }

    private void divider() {
        View d = new View(this);
        d.setBackgroundColor(cDivider);
        cur.addView(d, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.7f))));
    }

    /** One 56 dp row: icon badge, label, info "i" (when there is help), filling the space up to the control that follows. */
    private LinearLayout rowShell(View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(Ui.ROW_H));
        if (click != null) {
            r.setBackground(ripple(0));
            r.setOnClickListener(click);
        }
        cur.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    /** Icons for rows, picked from the words of the label (one place to change them). */
    private static String autoIcon(String t) {
        String s = t.toLowerCase(Locale.ROOT);
        String[][] m = {{"clock", "clock"}, {"time", "clock"}, {"am / pm", "clock"}, {"second", "clock"}, {"hour", "clock"},
                {"date", "date"}, {"day", "date"}, {"year", "date"}, {"temperature", "temp"}, {"heat", "temp"}, {"cool", "snow"}, {"measure", "temp"},
                {"back", "back"}, {"wi-fi", "wifi"}, {"mobile", "signal"}, {"network", "signal"}, {"connection", "wifi"},
                {"battery", "batt"}, {"power", "leaf"}, {"screen", "screen"}, {"notification", "bell"}, {"alarm", "bell"},
                {"bluetooth", "link"}, {"camera", "hole"}, {"edge", "compact"}, {"space", "compact"}, {"spacing", "compact"}, {"distance", "compact"},
                {"size", "text"}, {"letters", "text"}, {"format", "text"}, {"look", "eye"}, {"style", "eye"}, {"colour", "drop"}, {"color", "drop"},
                {"vibrate", "tap"}, {"tap", "tap"}, {"press", "tap"}, {"double", "tap"}, {"test", "tap"}, {"remind", "bell"},
                {"helper", "cpu"}, {"cpu", "cpu"}, {"gpu", "gauge"}, {"speed", "gauge"}, {"limit", "gauge"}, {"full speed", "bolt"},
                {"group", "layers"}, {"silent", "bell"}, {"restart", "reset"}, {"reset", "reset"}, {"back on", "power"}, {"start", "power"}, {"run", "power"}, {"chips", "layers"}, {"fade", "eye"},
                {"lock", "lock"}, {"protect", "shield"}, {"pattern", "text"}, {"arrange", "layout"}, {"samsung", "layout"}, {"hide", "eye"}, {"icon", "eye"}};
        for (String[] e : m) if (s.contains(e[0])) return e[1];
        return "sys";
    }

    /** Badge + label (+ info "i" when `help` is given), as one block that leaves `reserve` px for the control at the row's end. */
    private LinearLayout labelBlock(String icon, String title, String help, int labelColor, int reserve) {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        if (icon != null) {
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(36), dp(36));
            ip.setMarginEnd(dp(Ui.S3));
            b.addView(Ui.iconBadge(this, pal, icon), ip);
        }
        TextView l = Ui.text(this, title, Ui.T_BODY, labelColor, 500);
        int cardW = getResources().getDisplayMetrics().widthPixels - dp(2 * Ui.S4 + 2 * Ui.S4);
        l.setMaxWidth(Math.max(dp(80), cardW - (icon != null ? dp(36 + Ui.S3) : 0) - (help != null ? dp(Ui.TAP) : 0) - reserve));
        b.addView(l, new LinearLayout.LayoutParams(-2, -2));
        if (help != null) {
            final String tt = title, hh = help;
            View i = Ui.infoButton(this, pal, title, v -> showInfo(tt, hh));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(Ui.TAP), dp(Ui.TAP));
            ilp.setMarginStart(dp(-6));   // glyph sits right after the label text
            b.addView(i, ilp);
        }
        return b;
    }

    /** A line of status or explanation inside a card; with `text` it is a labelled row whose text opens in the info sheet. */
    private void infoRow(String title, String text) {
        if (text == null) {
            LinearLayout r = rowShell(null);
            TextView t = Ui.text(this, title, Ui.T_BODY, cText, 400);
            t.setPadding(0, dp(Ui.S3), 0, dp(Ui.S3));
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            return;
        }
        LinearLayout r = rowShell(v -> showInfo(title, text));
        r.addView(labelBlock(autoIcon(title), title, null, cText, 0), new LinearLayout.LayoutParams(0, -2, 1));
        View i = Ui.icon(this, "chev", cSub, 20);
        r.addView(i, new LinearLayout.LayoutParams(dp(20), dp(20)));
        r.setContentDescription(title);
    }

    /** Live status text under a label, shown inline (it is a reading, not an explanation). */
    private void statusRow(String title, String text) {
        LinearLayout r = rowShell(null);
        r.setPadding(0, dp(Ui.S2), 0, dp(Ui.S2));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, title, Ui.T_BODY, cText, 500));
        if (text != null && !text.isEmpty()) {
            TextView s = Ui.text(this, text, Ui.T_VALUE, cSub, 400);
            s.setPadding(0, dp(2), 0, 0);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private LinearLayout block(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(Ui.S3), 0, dp(Ui.S3));
        TextView t = Ui.text(this, title, Ui.T_BODY, cText, 500);
        t.setPadding(0, 0, 0, dp(10));
        box.addView(t);
        cur.addView(box, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private void switchRow(String title, String sub, boolean value, BoolSetter s) {
        Ui.Switch sw = new Ui.Switch(this, pal);
        LinearLayout r = rowShell(v -> sw.toggle());
        r.addView(labelBlock(autoIcon(title), title, sub, cText, dp(56)), new LinearLayout.LayoutParams(0, -2, 1));
        sw.setChecked(value);
        sw.setContentDescription(title);
        sw.setOnChange(v -> s.set(v));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(56), dp(Ui.TAP));
        sp.setMarginEnd(dp(-4));
        r.addView(sw, sp);
    }

    private void choiceRow(String title, String[] labels, int current, IntSetter s) {
        final int sel = current < 0 || current >= labels.length ? 0 : current;
        LinearLayout r = rowShell(v -> Ui.picker(this, pal, title, labels, sel, which -> {
            if (which != sel) s.set(which);
        }).show());
        r.addView(labelBlock(autoIcon(title), title, null, cText, dp(130)), new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = Ui.text(this, labels[sel], Ui.T_SECTION, pal.accInk, 600);
        value.setGravity(Gravity.END);
        value.setMaxWidth(dp(150));
        r.addView(value);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(20), dp(20));
        cp.setMarginStart(dp(2));
        r.addView(Ui.icon(this, "chev", cSub, 20), cp);
    }

    /** A pill track with the chosen option lifted on a card-coloured pill. */
    private LinearLayout segmented(String[] labels, int selected, IntSetter s) {
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        seg.setBackground(round(pal.seg, dp(Ui.R_SEG), 0, 0));
        for (int i = 0; i < labels.length; i++) {
            final int at = i;
            TextView b = Ui.text(this, labels[i], 14, i == selected ? cText : cSub, 600);
            b.setGravity(Gravity.CENTER);
            b.setSingleLine(true);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (i == selected) b.setBackground(round(pal.night ? pal.card2 : pal.card, dp(20), pal.night ? 0 : 1, pal.line));
            b.setOnClickListener(v -> { if (at != selected) { s.set(at); refresh(); } });
            b.setSelected(i == selected);
            seg.addView(b, new LinearLayout.LayoutParams(0, dp(40), 1));
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
        box.setPadding(0, 0, 0, dp(Ui.S3));
        box.addView(segmented(labels, selected, s), new LinearLayout.LayoutParams(-1, -2));
        cur.addView(box);
    }

    private void actionRow(String title, String sub, int color, View.OnClickListener l) {
        LinearLayout r = rowShell(l);
        r.addView(labelBlock(autoIcon(title), title, sub, color == cAccent ? pal.accInk : color, 0), new LinearLayout.LayoutParams(0, -2, 1));
    }

    private TextView textButton(String t, View.OnClickListener l) {
        TextView b = Ui.text(this, t, Ui.T_SECTION, pal.accInk, 600);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        b.setMinHeight(dp(40));
        b.setBackground(ripple2(null, dp(18)));
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
        e.setBackground(round(cField, dp(Ui.R_FIELD), 0, 0));
        return e;
    }

    /** Slider with its value on the right and a "Reset" button whenever it differs from the starting value. */
    private void slider(String t, int min, int max, int current, Label label, IntSetter s, int def) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(Ui.S2), 0, dp(2));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.text(this, t, Ui.T_BODY, cText, 500), new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = Ui.text(this, label.of(current), Ui.T_SECTION, pal.accInk, 600);
        val.setPadding(0, 0, dp(Ui.S2), 0);
        top.addView(val);
        TextView reset = textButton("Reset", v -> { s.set(def); apply(); refresh(); });
        reset.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.T_VALUE);
        reset.setPadding(dp(10), dp(6), dp(10), dp(6));
        reset.setVisibility(current == def ? View.INVISIBLE : View.VISIBLE);
        top.addView(reset);
        box.addView(top, new LinearLayout.LayoutParams(-1, dp(40)));
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        GradientDrawable trk = round(pal.night ? 0xFF4A5463 : 0xFFB4BCC8, dp(3), 0, 0);
        trk.setSize(-1, dp(6));
        GradientDrawable prog = round(cAccent, dp(3), 0, 0);
        prog.setSize(-1, dp(6));
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new Drawable[]{trk,
                new android.graphics.drawable.ClipDrawable(prog, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        sb.setProgressDrawable(ld);
        GradientDrawable thumb = round(cCard, dp(14), dp(3), cAccent);
        thumb.setSize(dp(28), dp(28));
        sb.setThumb(thumb);
        sb.setSplitTrack(false);
        sb.setProgress(Math.max(current, min) - min);
        sb.setPadding(dp(14), 0, dp(14), 0);
        sb.setContentDescription(t);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                val.setText(label.of(p + min));
                reset.setVisibility(p + min == def ? View.INVISIBLE : View.VISIBLE);
                if (user) { s.set(p + min); updatePreview(); }
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) { apply(); refresh(); }
        });
        box.addView(sb, new LinearLayout.LayoutParams(-1, dp(Ui.TAP)));
        cur.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }
}
