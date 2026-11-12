// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import dev.gitesh.sbmods.Config;

/**
 * Notification panel: open notifications fully, and groups that open fully with one tap.
 *
 * Both hooks are event-driven and only run when the panel itself changes a row, so there is no timer, thread or
 * per-frame work. Each body starts with a check of cached booleans and returns at once when both settings are off.
 *
 * How it works on One UI 8.5 (checked in SystemUI's dex):
 *  - ExpandableNotificationRowController.setSystemExpanded(boolean) is what RowAppearanceCoordinator calls for every row after
 *    each list build. The row's own method was inlined away; the row keeps the flag in mIsSystemExpanded and
 *    isExpanded(false) honours it only when the user has not toggled the row and the row is not on the lock screen.
 *  - A tap on a collapsed group summary already expands the group (ExpandableNotificationRow.performClick -> toggleExpansionState).
 *    GroupExpansionManagerImpl.setGroupExpanded then calls summaryRow.setChildrenExpanded(boolean); we hook that to
 *    expand / un-expand the children together with the group, so a collapsed group never shows expanded children.
 */
final class NotifExpand {
    private static volatile boolean openAll, groupOpen, skipSilent;

    private static final String ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
    private static final String ROW_CTRL = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRowController";
    private static final String ENTRY_ADAPTER = "com.android.systemui.statusbar.notification.collection.EntryAdapter";
    private static final String CONTAINER = "com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer";

    private static boolean installed;
    private static Field ctrlView, sysExpanded, userChanged, isBundle, isSummary, children, attached, entryAdapter;
    private static Method rowIsChildInGroup, rowIsGroupExpanded, rowIsExpanded, rowNotifyHeight, rowOnExpansionChanged;
    private static Method adapterImportance, adapterAmbient;

    private NotifExpand() {}

    /** Called whenever the settings arrive (and once at start): just caches three booleans. */
    static void apply(Config c) {
        if (c == null) return;
        openAll = c.notifOpen;
        groupOpen = c.groupOpen;
        skipSilent = c.openSkipSilent;
    }

    /** The mod switched itself off: stop changing notifications. */
    static void off() { openAll = false; groupOpen = false; skipSilent = false; }

    static synchronized void install(ClassLoader cl) {
        if (installed) return;
        installed = true;
        try {
            Class<?> row = Class.forName(ROW, false, cl);
            Class<?> ctrl = Class.forName(ROW_CTRL, false, cl);
            Class<?> container = Class.forName(CONTAINER, false, cl);
            ctrlView = field(ctrl, "mView");
            sysExpanded = field(row, "mIsSystemExpanded");
            userChanged = field(row, "mHasUserChangedExpansion");
            isBundle = field(row, "mIsBundle");
            isSummary = field(row, "mIsSummaryWithChildren");
            children = field(row, "mChildrenContainer");
            entryAdapter = field(row, "mEntryAdapter");
            attached = field(container, "mAttachedChildren");
            rowIsChildInGroup = method(row, "isChildInGroup");
            rowIsGroupExpanded = method(row, "isGroupExpanded");
            rowIsExpanded = method(row, "isExpanded", boolean.class);
            rowNotifyHeight = method(row, "notifyHeightChanged", String.class, boolean.class);
            rowOnExpansionChanged = method(row, "onExpansionChanged", boolean.class, boolean.class);
            try {   // only needed for "skip silent"; if missing that option simply does nothing
                Class<?> ea = Class.forName(ENTRY_ADAPTER, false, cl);
                adapterImportance = method(ea, "getImportance");
                adapterAmbient = method(ea, "isAmbient");
            } catch (Throwable t) { Log.w("notif expand: silent check unavailable: " + t); Log.miss("the notification importance check", "skipping silent notifications"); }

            Method setSys = ctrl.getDeclaredMethod("setSystemExpanded", boolean.class);
            XposedBridge.hookMethod(setSys, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!openAll && !groupOpen) return;
                    try {
                        beforeSystemExpanded(p);
                    } catch (Throwable t) { failOnce("setSystemExpanded", t); }
                }
            });
        } catch (Throwable t) {
            Log.w("notif expand: setSystemExpanded hook failed: " + t); Log.miss("the notification row (ExpandableNotificationRow)", "opening notifications automatically");
        }
        try {
            Class<?> row = Class.forName(ROW, false, cl);
            Method setKids = row.getDeclaredMethod("setChildrenExpanded", boolean.class);
            XposedBridge.hookMethod(setKids, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!groupOpen) return;
                    try {
                        afterChildrenExpanded(p.thisObject, (Boolean) p.args[0]);
                    } catch (Throwable t) { failOnce("setChildrenExpanded", t); }
                }
            });
        } catch (Throwable t) {
            Log.w("notif expand: setChildrenExpanded hook failed: " + t); Log.miss("setChildrenExpanded", "opening notification groups automatically");
        }
    }

    private static void beforeSystemExpanded(XC_MethodHook.MethodHookParam p) throws Throwable {
        Object r = ctrlView.get(p.thisObject);
        if (r == null) return;
        if (isBundle.getBoolean(r) || isSummary.getBoolean(r)) return;   // group summaries and bundles keep Samsung's behaviour
        if ((Boolean) rowIsChildInGroup.invoke(r)) {
            // A child follows its group: expanded while the group is open, otherwise untouched (never peeks out of a collapsed group).
            if (groupOpen && (Boolean) rowIsGroupExpanded.invoke(r)) p.args[0] = true;
            return;
        }
        if (!openAll) return;
        if (skipSilent && isSilent(r)) return;
        p.args[0] = true;   // isExpanded() still lets the user's own choice and the lock screen win
    }

    private static boolean isSilent(Object r) {
        try {
            if (adapterImportance == null) return false;
            Object ea = entryAdapter.get(r);
            if (ea == null) return false;
            if (adapterAmbient != null && (Boolean) adapterAmbient.invoke(ea)) return true;
            return (Integer) adapterImportance.invoke(ea) < 3;   // IMPORTANCE_DEFAULT: LOW and MIN are the silent section
        } catch (Throwable t) { return false; }
    }

    /** The group opened or closed: open or close the children's own system expansion the same way setSystemExpanded does. */
    private static void afterChildrenExpanded(Object summary, boolean open) throws Throwable {
        if (!isSummary.getBoolean(summary) || isBundle.getBoolean(summary)) return;
        Object cont = children.get(summary);
        if (cont == null) return;
        List<?> kids = (List<?>) attached.get(cont);
        if (kids == null) return;
        for (int i = 0; i < kids.size(); i++) {
            Object k = kids.get(i);
            if (userChanged.getBoolean(k) || sysExpanded.getBoolean(k) == open) continue;   // keep what the user chose
            boolean was = (Boolean) rowIsExpanded.invoke(k, false);
            sysExpanded.setBoolean(k, open);
            rowNotifyHeight.invoke(k, "SBMods.setChildrenExpanded", false);
            rowOnExpansionChanged.invoke(k, false, was);
        }
    }

    private static Field field(Class<?> c, String n) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { Field f = k.getDeclaredField(n); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(c.getName() + "." + n);
    }

    private static Method method(Class<?> c, String n, Class<?>... a) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { Method m = k.getDeclaredMethod(n, a); m.setAccessible(true); return m; } catch (NoSuchMethodException ignored) {}
        }
        return c.getMethod(n, a);
    }

    private static boolean failed;
    private static void failOnce(String where, Throwable t) {
        if (failed) return;
        failed = true;
        Log.w("notif expand " + where + " failed: " + t);
    }
}
