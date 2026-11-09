// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.UiModeManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Toast;

import java.lang.ref.WeakReference;

import dev.gitesh.sbmods.Config;

/**
 * Tap / double-tap / long-press actions for one status bar item (battery icon, clock, our label).
 * A target is only made clickable if at least one action is set, and only if Samsung has not
 * already put its own click listener on it. Everything is undone when actions are cleared.
 * Double-tap only delays a single tap when a double-tap action is actually set.
 */
final class TapActions {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Controller owner;
    private final String name;
    private final int item; // 0 battery, 1 clock, 2 temp, 3 camera hole -> slots item*3 + {0 tap, 1 double, 2 long}
    private WeakReference<View> target = new WeakReference<>(null);
    private Runnable pendingTap;

    TapActions(Controller owner, String name, int item) {
        this.owner = owner;
        this.name = name;
        this.item = item;
    }

    View view() { return target.get(); }

    private int act(Config c, int gesture) { return c.action(item * 3 + gesture); }

    /**
     * Remember the view to watch. The view is NOT made clickable: a clickable view would swallow
     * the touch and the status bar could no longer be pulled down. Touches are only observed (see
     * Controller.onStatusBarTouch), so swipes always reach the panel.
     */
    void bind(View v, Config c) {
        if (v != target.get()) { cancel(); target = new WeakReference<>(v); }
    }

    void unbind() { cancel(); target = new WeakReference<>(null); }

    boolean wants(Config c) {
        return act(c, 0) != Config.ACT_NONE || act(c, 1) != Config.ACT_NONE || act(c, 2) != Config.ACT_NONE;
    }

    private Runnable longPress;
    private boolean longFired;

    /** Finger down on this item. */
    void down() {
        cancelLong();
        longFired = false;
        View v = target.get();
        if (v == null || act(owner.config(), 2) == Config.ACT_NONE) return;
        longPress = () -> {
            longPress = null;
            longFired = true;
            cancelPending();
            run(v, 2);
        };
        MAIN.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
    }

    /** Finger lifted without moving: a tap (or the second tap of a double-tap). */
    void up() {
        cancelLong();
        View v = target.get();
        if (longFired || v == null) return;
        Config c = owner.config();
        if (act(c, 1) == Config.ACT_NONE) { run(v, 0); return; }
        if (pendingTap != null) { cancelPending(); run(v, 1); return; }
        pendingTap = () -> { pendingTap = null; run(v, 0); };
        MAIN.postDelayed(pendingTap, ViewConfiguration.getDoubleTapTimeout());
    }

    /** Finger moved (a swipe) or the gesture was cancelled. */
    void cancel() {
        cancelLong();
    }

    private void cancelLong() {
        if (longPress != null) MAIN.removeCallbacks(longPress);
        longPress = null;
    }

    private void cancelPending() {
        if (pendingTap != null) MAIN.removeCallbacks(pendingTap);
        pendingTap = null;
    }

    private boolean run(View v, int gesture) {
        Config c = owner.config();
        int slot = item * 3 + gesture;
        int action = c.action(slot);
        try {
            Log.i(name + " gesture " + gesture + " action " + action);
            if (action == Config.ACT_NONE) return false;
            if (c.tapHaptic) v.performHapticFeedback(gesture == 2
                    ? HapticFeedbackConstants.LONG_PRESS : HapticFeedbackConstants.CONTEXT_CLICK);
            perform(v.getContext(), action, c.app(slot));
            return true;
        } catch (Throwable t) {
            owner.fail("tap " + name, t);
            return false;
        }
    }

    // ================================================================ actions

    /** Same as the RAM chip on the Overview screen: the root helper closes background apps and says how much RAM it freed. */
    private static void closeApps(Context ctx) {
        android.content.ContentResolver cr = ctx.getContentResolver();
        Settings.Global.putString(cr, "sbmods_result", "");
        Settings.Global.putString(cr, "sbmods_cmd", "ramclean");
        toast(ctx, "Closing background apps\u2026");
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final long until = android.os.SystemClock.uptimeMillis() + 15000;
        h.postDelayed(new Runnable() {
            @Override public void run() {
                String r = Settings.Global.getString(cr, "sbmods_result");
                if (r != null && !r.isEmpty()) { toast(ctx, r); return; }
                if (android.os.SystemClock.uptimeMillis() < until) { h.postDelayed(this, 400); return; }
                toast(ctx, "No answer from the helper \u2013 turn it on in StatusBar Mods \u203a More");
            }
        }, 1500);
    }

    /** Runs one action. Also used by the app's "Try it" button (Config.ACTION_TEST). */
    static void perform(Context ctx, int action, String app) {
        try {
            switch (action) {
                case Config.ACT_POWER_SAVER: togglePowerSaver(ctx); break;
                case Config.ACT_BATTERY_USAGE: open(ctx, new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)); break;
                case Config.ACT_ALARMS: open(ctx, new Intent(AlarmClock.ACTION_SHOW_ALARMS)); break;
                case Config.ACT_CALENDAR:
                    open(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)); break;
                case Config.ACT_SETTINGS: open(ctx, new Intent(Settings.ACTION_SETTINGS)); break;
                case Config.ACT_FLASHLIGHT: toggleTorch(ctx); break;
                case Config.ACT_WIFI: toggleWifi(ctx); break;
                case Config.ACT_BLUETOOTH: toggleBluetooth(ctx); break;
                case Config.ACT_DND: toggleDnd(ctx); break;
                case Config.ACT_ROTATE: toggleRotate(ctx); break;
                case Config.ACT_DARK: toggleDark(ctx); break;
                case Config.ACT_SOUND: cycleSound(ctx); break;
                case Config.ACT_MEDIA: mediaPlayPause(ctx); break;
                case Config.ACT_LOCK: lockScreen(ctx); break;
                case Config.ACT_QS: statusBar(ctx, "expandSettingsPanel"); break;
                case Config.ACT_NOTIFS: statusBar(ctx, "expandNotificationsPanel"); break;
                case Config.ACT_CAMERA: open(ctx, new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); break;
                case Config.ACT_TIMER: open(ctx, new Intent(AlarmClock.ACTION_SHOW_TIMERS)); break;
                case Config.ACT_CALCULATOR:
                    open(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)); break;
                case Config.ACT_INTERNET: open(ctx, new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)); break;
                case Config.ACT_APP: openApp(ctx, app); break;
                case Config.ACT_FINDER: openFinder(ctx); break;
                case Config.ACT_CLOSE_APPS: closeApps(ctx); break;
                default: break;
            }
            Log.i("performed action " + action);
        } catch (Throwable t) {
            Throwable c = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            Log.w("action " + action + " failed: " + c);
            toast(ctx, "Couldn't do that (" + c.getClass().getSimpleName() + ")");
        }
    }

    private static void toast(Context ctx, String s) {
        MAIN.post(() -> Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show());
    }

    /** SystemUI's BatteryController, captured by a hook in {@link Entry} (fallback only). */
    static volatile Object batteryController;
    private static final Uri DC_API = Uri.parse("content://com.samsung.android.sm.dcapi");

    /**
     * One UI's power saving belongs to Device Care: toggle it through its dcapi provider
     * (psm_turn_on / psm_turn_off, same as the QS tile). PowerManager.setPowerSaveModeEnabled only
     * drives AOSP's saver, which Samsung ignores. BatteryController is the fallback.
     */
    private static void togglePowerSaver(Context ctx) {
        boolean on = Settings.Global.getInt(ctx.getContentResolver(), "low_power", 0) == 0;
        toast(ctx, on ? "Power saving on" : "Power saving off");
        new Thread(() -> {
            String method = on ? "psm_turn_on" : "psm_turn_off";
            try {
                Bundle r = ctx.getContentResolver().call(DC_API, method, null, null);
                Log.i(method + " -> " + r);
                if (r != null && r.getBoolean("result", false)) return;
            } catch (Throwable t) {
                Log.w(method + " failed: " + t);
            }
            Object bc = batteryController;
            if (bc == null) return;
            try {
                Class<?> exp = bc.getClass().getClassLoader().loadClass("com.android.systemui.animation.Expandable");
                bc.getClass().getMethod("setPowerSaveMode", boolean.class, exp).invoke(bc, on, null);
                Log.i("fallback BatteryController.setPowerSaveMode(" + on + ")");
            } catch (Throwable t) {
                Log.w("fallback failed: " + t);
            }
        }, "sbmods-psm").start();
    }

    // ---- flashlight: track state with a torch callback, registered once
    private static volatile boolean torchOn;
    private static String torchId;

    private static void toggleTorch(Context ctx) throws Exception {
        CameraManager cm = ctx.getSystemService(CameraManager.class);
        if (torchId == null) {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchId = id;
                    break;
                }
            }
            if (torchId == null) throw new IllegalStateException("no flash");
            cm.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String id, boolean enabled) {
                    if (id.equals(torchId)) torchOn = enabled;
                }
            }, MAIN);
        }
        cm.setTorchMode(torchId, !torchOn);
    }

    @SuppressWarnings("deprecation")
    private static void toggleWifi(Context ctx) {
        WifiManager wm = ctx.getSystemService(WifiManager.class);
        boolean on = !wm.isWifiEnabled();
        boolean ok = wm.setWifiEnabled(on); // allowed for SystemUI (system app)
        toast(ctx, ok ? (on ? "Wi-Fi on" : "Wi-Fi off") : "Wi-Fi: not allowed");
    }

    @SuppressWarnings({"deprecation", "MissingPermission"})
    private static void toggleBluetooth(Context ctx) {
        BluetoothAdapter a = ctx.getSystemService(BluetoothManager.class).getAdapter();
        if (a == null) throw new IllegalStateException("no bluetooth");
        boolean on = !a.isEnabled();
        boolean ok = on ? a.enable() : a.disable();
        toast(ctx, ok ? (on ? "Bluetooth on" : "Bluetooth off") : "Bluetooth: not allowed");
    }

    private static void toggleDnd(Context ctx) throws Exception {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        boolean on = nm.getCurrentInterruptionFilter() <= NotificationManager.INTERRUPTION_FILTER_ALL;
        try { // hidden API used by the QS tile (zen mode 1 = priority only)
            NotificationManager.class.getMethod("setZenMode", int.class, Uri.class, String.class)
                    .invoke(nm, on ? 1 : 0, null, "sbmods");
        } catch (NoSuchMethodException e) {
            nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    : NotificationManager.INTERRUPTION_FILTER_ALL);
        }
        toast(ctx, on ? "Do not disturb on" : "Do not disturb off");
    }

    private static void toggleRotate(Context ctx) {
        boolean on = Settings.System.getInt(ctx.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 0;
        Settings.System.putInt(ctx.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0);
        toast(ctx, on ? "Auto rotate on" : "Auto rotate off");
    }

    private static void toggleDark(Context ctx) {
        UiModeManager um = ctx.getSystemService(UiModeManager.class);
        boolean dark = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        um.setNightMode(dark ? UiModeManager.MODE_NIGHT_NO : UiModeManager.MODE_NIGHT_YES);
    }

    private static void cycleSound(Context ctx) throws Exception {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        int cur;
        try { cur = (int) AudioManager.class.getMethod("getRingerModeInternal").invoke(am); }
        catch (NoSuchMethodException e) { cur = am.getRingerMode(); }
        int next = cur == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE
                : cur == AudioManager.RINGER_MODE_VIBRATE ? AudioManager.RINGER_MODE_SILENT
                : AudioManager.RINGER_MODE_NORMAL;
        try { AudioManager.class.getMethod("setRingerModeInternal", int.class).invoke(am, next); }
        catch (NoSuchMethodException e) { am.setRingerMode(next); }
        toast(ctx, next == AudioManager.RINGER_MODE_NORMAL ? "Sound" : next == AudioManager.RINGER_MODE_VIBRATE ? "Vibrate" : "Mute");
    }

    private static void mediaPlayPause(Context ctx) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
    }

    private static void lockScreen(Context ctx) throws Exception {
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        PowerManager.class.getMethod("goToSleep", long.class).invoke(pm, SystemClock.uptimeMillis());
    }

    private static void statusBar(Context ctx, String method) throws Exception {
        Object sb = ctx.getSystemService("statusbar");
        sb.getClass().getMethod(method).invoke(sb);
    }

    /** Samsung Finder lives inside One UI Home; its SearchActivity answers ACTION_SEARCH with the Finder screen. */
    private static void openFinder(Context ctx) {
        Intent i = new Intent(Intent.ACTION_SEARCH).setClassName("com.sec.android.app.launcher",
                "com.sec.android.app.launcher.search.SearchActivity");
        open(ctx, i);
    }

    private static void openApp(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) { toast(ctx, "No app chosen"); return; }
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { toast(ctx, "App not found"); return; }
        open(ctx, i);
    }

    private static void open(Context ctx, Intent i) {
        KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) return; // never open things over the lock screen
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ctx.startActivity(i);
    }
}
