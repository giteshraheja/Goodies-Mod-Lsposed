// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import dev.gitesh.sbmods.Config;

/** Xposed entry point. Only acts inside the main SystemUI process. */
public final class Entry implements IXposedHookLoadPackage {
    static XC_MethodHook.Unhook attachHook;
    static final String BATTERY_CONTROLLER = "com.android.systemui.statusbar.policy.BatteryControllerImpl";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        try {
            if ("android".equals(lp.packageName) || LAUNCHER.equals(lp.packageName)) { hookLockLimit(lp); return; }
            if (!Config.SYSUI.equals(lp.packageName)) return;
            if (lp.processName != null && !Config.SYSUI.equals(lp.processName)) return;
            if (attachHook != null) return;

            // One framework-level hook: every TextView attach. The callback filters down to the
            // status bar clock in a few cheap checks, so it never depends on Samsung class internals.
            attachHook = XposedHelpers.findAndHookMethod(TextView.class, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Controller.get().onTextViewAttached((TextView) p.thisObject);
                            } catch (Throwable t) {
                                Controller.get().fail("attach", t);
                            }
                        }
                    });
            // Capture SystemUI's BatteryController: on One UI, power saving must be toggled through it
            // (PowerManager.setPowerSaveModeEnabled only drives AOSP's saver, which Samsung bypasses).
            try {
                XposedHelpers.findAndHookMethod(BATTERY_CONTROLLER, lp.classLoader, "updatePowerSave",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                TapActions.batteryController = p.thisObject;
                            }
                        });
            } catch (Throwable t) {
                Log.w("BatteryController hook failed: " + t);
            }
            // Watch (never consume) touches on the status bar window for taps / long-presses.
            try {
                XposedHelpers.findAndHookMethod(android.view.ViewGroup.class, "dispatchTouchEvent",
                        android.view.MotionEvent.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                if (p.thisObject != Controller.statusRoot) return;
                                try {
                                    Controller.get().onStatusBarTouch((android.view.MotionEvent) p.args[0]);
                                } catch (Throwable t) {
                                    Controller.get().fail("touch", t);
                                }
                            }
                        });
            } catch (Throwable t) {
                Log.w("touch hook failed: " + t);
            }
            // Samsung ticks every visible clock each minute: lets us recover if the clock was replaced.
            try {
                ClassLoader cl = lp.classLoader;
                Class<?> bell = cl.loadClass("com.android.systemui.statusbar.policy.QSClockBellSound");
                XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.policy.QSClockIndicatorView",
                        cl, "notifyTimeChanged", bell, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                try {
                                    Controller.get().considerClock((TextView) p.thisObject);
                                } catch (Throwable t) {
                                    Controller.get().fail("tick", t);
                                }
                            }
                        });
            } catch (Throwable t) {
                Log.w("tick hook failed: " + t);
            }
            // Follow the clock's light / dark tint immediately (no white-on-white).
            try {
                XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.policy.QSClockIndicatorView",
                        lp.classLoader, "onDarkChanged", java.util.ArrayList.class, float.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                try {
                                    Controller.get().onClockTint((TextView) p.thisObject);
                                } catch (Throwable t) {
                                    Controller.get().fail("tint", t);
                                }
                            }
                        });
            } catch (Throwable t) {
                Log.w("tint hook failed: " + t);
            }
            // Samsung re-centres the clock by its glyph bounds, so "pm" (descender) lifts the digits.
            try {
                XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.policy.QSClockIndicatorView",
                        lp.classLoader, "realignText", new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                try {
                                    Controller.get().fixClockAlign((TextView) p.thisObject);
                                } catch (Throwable t) {
                                    Controller.get().fail("realign", t);
                                }
                            }
                        });
            } catch (Throwable t) { Log.w("realign hook failed: " + t); }
            // Notification icon cap: Samsung reads it from an integer resource. Answer with the user's number.
            try {
                XposedHelpers.findAndHookMethod(android.content.res.Resources.class, "getInteger", int.class,
                        new XC_MethodHook() {
                            private int idStatic = -2, idInfo = -2;
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                try {
                                    int want = Controller.get().config().notifMax;
                                    if (want <= 0) want = 0;
                                    android.content.res.Resources r = (android.content.res.Resources) p.thisObject;
                                    if (idStatic == -2) {
                                        idStatic = r.getIdentifier("max_notif_static_icons", "integer", Config.SYSUI);
                                        idInfo = r.getIdentifier("status_bar_notification_info_maxnum", "integer", Config.SYSUI);
                                    }
                                    int id = (Integer) p.args[0];
                                    if (id != 0 && id == idStatic) { if (want > 0) p.setResult(want); }
                                } catch (Throwable ignored) {}
                            }
                        });
            } catch (Throwable t) { Log.w("icon cap hook failed: " + t); }
            // The real cap (One UI 8.5+): shouldForceOverflow(index, float, maxVisible) and the status bar view model's maxIcons.
            try {
                Class<?> nic = Class.forName("com.android.systemui.statusbar.phone.NotificationIconContainer", false, lp.classLoader);
                try { // callee is inlined into these callers; force them back to the interpreter so the hook is seen
                    for (java.lang.reflect.Method dm : de.robv.android.xposed.XposedBridge.class.getDeclaredMethods()) {
                        if (!dm.getName().equals("deoptimizeMethod")) continue;
                        for (java.lang.reflect.Method m : nic.getDeclaredMethods())
                            if (m.getName().equals("calculateIconXTranslations") || m.getName().equals("applyIconStates"))
                                dm.invoke(null, m);
                    }
                } catch (Throwable t) { Log.w("deopt failed: " + t); }
                de.robv.android.xposed.XposedBridge.hookAllMethods(nic, "shouldForceOverflow", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            int want = Controller.get().config().notifMax;
                            if (want <= 0) return;
                            int n = p.args.length;
                            if (n > 0 && p.args[n - 1] instanceof Integer) p.args[n - 1] = want;
                        } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable t) { Log.w("force-overflow hook failed: " + t); }
            try {
                Class<?> vm = Class.forName("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel", false, lp.classLoader);
                for (java.lang.reflect.Constructor<?> c : vm.getDeclaredConstructors())
                    de.robv.android.xposed.XposedBridge.hookMethod(c, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (Controller.get().config().notifMax <= 0) return;
                                java.lang.reflect.Field f = p.thisObject.getClass().getDeclaredField("maxIcons");
                                f.setAccessible(true);
                                f.setInt(p.thisObject, Integer.MAX_VALUE);
                            } catch (Throwable ignored) {}
                        }
                    });
            } catch (Throwable t) { Log.w("icon viewmodel hook failed: " + t); }
            Log.i("loaded in " + lp.processName);
        } catch (Throwable t) {
            Log.w("init failed: " + t);
        }
    }

    static final String LAUNCHER = "com.sec.android.app.launcher";

    /** "Keep open" cap in Recents: the launcher and the system server both ask getMaxLongLiveApps(). Answer with the user's number. */
    private static void hookLockLimit(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> cls = Class.forName(LAUNCHER.equals(lp.packageName)
                    ? "com.android.systemui.shared.system.ActivityManagerWrapper" : "com.android.server.am.ActivityManagerService",
                    false, lp.classLoader);
            de.robv.android.xposed.XposedBridge.hookAllMethods(cls, "getMaxLongLiveApps", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        android.content.Context c = (android.content.Context) Class.forName("android.app.ActivityThread")
                                .getMethod("currentApplication").invoke(null);
                        if (c == null) {
                            java.lang.reflect.Field f = p.thisObject.getClass().getDeclaredField("mContext");
                            f.setAccessible(true);
                            c = (android.content.Context) f.get(p.thisObject);
                        }
                        int want = android.provider.Settings.Global.getInt(c.getContentResolver(), Config.LOCK_SETTING, 0);
                        if (want > 0) p.setResult(want);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) { Log.w("lock limit hook failed: " + t); }
    }

    static void unhook() {
        try {
            if (attachHook != null) attachHook.unhook();
        } catch (Throwable ignored) {}
        attachHook = null;
    }
}
