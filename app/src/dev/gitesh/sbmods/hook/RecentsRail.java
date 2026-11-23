// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Overview: a column of open-app icons in the empty strip right of the cards. The newest app sits at the bottom and older
 * ones stack upwards; one tap opens that app (same as tapping its card), long-press shows its name.
 * Samsung's card list only keeps the visible cards, so the list comes from the system's recent tasks.
 * Built when Overview opens and re-checked on the chips' tick while it shows (nothing runs otherwise).
 * Off with: settings put global sbmods_recents_rail 0
 */
final class RecentsRail {
    private RecentsRail() {}

    static final String TAG = "sbmods_recents_rail";
    private static final HashMap<String, Drawable> icons = new HashMap<>();
    private static String shownKey = "";
    static final int ICON = 46;   // dp; the column is ICON + 8 dp wide

    /** Add / remove the column; call from Overview's onResume. */
    static void attach(Activity a, FrameLayout box) {
        View old = box.findViewWithTag(TAG);
        if (Global.gint(a, "sbmods_recents_rail", 1) == 0) {
            if (old != null) box.removeView(old);
            return;
        }
        if (old == null) {
            float d = a.getResources().getDisplayMetrics().density;
            LinearLayout rail = new LinearLayout(a);
            rail.setTag(TAG);
            rail.setOrientation(LinearLayout.VERTICAL);
            rail.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            int pad = Math.round(4 * d);
            rail.setPadding(0, pad, 0, pad);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x33000000);
            bg.setCornerRadius((ICON + 8) / 2f * d);
            rail.setBackground(bg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round((ICON + 8) * d), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            lp.rightMargin = Math.round(1 * d);
            box.addView(rail, lp);
        }
        shownKey = "";
    }

    /** Place the column between the top chips and "Close all", and refresh its icons. Cheap when nothing changed. */
    static void fit(Activity a, FrameLayout box, int top, int bottom) {
        View v = box.findViewWithTag(TAG);
        if (!(v instanceof LinearLayout) || bottom - top < 100) return;
        LinearLayout rail = (LinearLayout) v;
        float d = a.getResources().getDisplayMetrics().density;
        int slot = Math.round((ICON + 6) * d);   // icon + 6 dp gap
        boolean clear = RecentsNotifs.on(a);   // "Clear" (notifications of the apps with cards) sits at the bottom
        int max = Math.max(1, (bottom - top - Math.round(8 * d)) / slot - (clear ? 1 : 0));   // as many as fit beside the cards
        List<ActivityManager.RecentTaskInfo> tasks = recent(a, max);
        StringBuilder key = new StringBuilder().append(clear);
        for (ActivityManager.RecentTaskInfo t : tasks) key.append(t.taskId).append(',');
        if (!key.toString().equals(shownKey)) {
            shownKey = key.toString();
            rail.removeAllViews();
            for (int i = tasks.size() - 1; i >= 0; i--) rail.addView(icon(a, tasks.get(i), d));   // oldest on top, newest at the bottom
            if (clear) rail.addView(clearButton(a, d));
            rail.setVisibility(tasks.isEmpty() ? View.GONE : View.VISIBLE);
        }
        // grow upward from "Close all"
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) rail.getLayoutParams();
        int h = rail.getPaddingTop() + rail.getPaddingBottom() + (tasks.size() + (clear ? 1 : 0)) * slot;
        int want = Math.max(top, bottom - h);
        if (lp.topMargin != want || lp.gravity != (Gravity.TOP | Gravity.END)) {
            lp.topMargin = want;
            lp.gravity = Gravity.TOP | Gravity.END;
            rail.setLayoutParams(lp);
        }
    }

    static List<ActivityManager.RecentTaskInfo> recent(Context c, int max) {
        List<ActivityManager.RecentTaskInfo> out = new ArrayList<>();
        try {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            List<ActivityManager.RecentTaskInfo> all = am.getRecentTasks(max + 6, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
            for (ActivityManager.RecentTaskInfo t : all) {
                ComponentName cn = comp(t);
                if (cn == null || cn.getPackageName().equals(Entry.LAUNCHER)) continue;
                if (t.baseIntent != null && t.baseIntent.hasCategory(Intent.CATEGORY_HOME)) continue;
                out.add(t);
                if (out.size() >= max) break;
            }
        } catch (Throwable t) { Log.w("recents rail tasks: " + t); }
        return out;
    }

    static ComponentName comp(ActivityManager.RecentTaskInfo t) {
        if (t.baseIntent != null && t.baseIntent.getComponent() != null) return t.baseIntent.getComponent();
        if (t.baseActivity != null) return t.baseActivity;
        return t.topActivity;
    }

    private static View icon(Activity a, ActivityManager.RecentTaskInfo t, float d) {
        ImageView iv = new ImageView(a);
        int size = Math.round(ICON * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.topMargin = lp.bottomMargin = Math.round(3 * d);
        iv.setLayoutParams(lp);
        ComponentName cn = comp(t);
        String pkg = cn.getPackageName();
        Drawable dr = icons.get(pkg);
        PackageManager pm = a.getPackageManager();
        if (dr == null) {
            try { dr = pm.getActivityIcon(cn); } catch (Throwable e) {
                try { dr = pm.getApplicationIcon(pkg); } catch (Throwable ignored) { dr = pm.getDefaultActivityIcon(); }
            }
            icons.put(pkg, dr);
        }
        iv.setImageDrawable(dr);
        CharSequence name;
        try { name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)); } catch (Throwable e) { name = pkg; }
        iv.setContentDescription(name);
        final int id = t.taskId;
        final CharSequence label = name;
        iv.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); open(a, id, pkg); });
        iv.setOnLongClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); popup(a, v, pkg, label, id); return true; });
        return iv;
    }

    /** "Clear": dismiss the notifications of every app that has a card; the apps stay open. */
    private static View clearButton(Activity a, float d) {
        TextView t = new TextView(a);
        t.setText("Clear");
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x66FFFFFF & 0x55FFFFFF);
        bg.setCornerRadius(ICON / 2f * d);
        t.setBackground(bg);
        t.setContentDescription("Clear notifications of the apps shown");
        int size = Math.round(ICON * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.topMargin = lp.bottomMargin = Math.round(3 * d);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); CardNotifs.clearAll(a); });
        return t;
    }

    /** Long-press: the app's notifications (tap opens, swipe dismisses) and its shortcuts, like a home screen icon's menu. */
    private static void popup(Activity a, View anchor, String pkg, CharSequence label, int taskId) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(8 * d);
        col.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2202022);
        bg.setCornerRadius(20 * d);
        col.setBackground(bg);
        final android.widget.PopupWindow[] pw = new android.widget.PopupWindow[1];
        Runnable close = () -> { if (pw[0] != null) pw[0].dismiss(); };
        TextView head = new TextView(a);
        head.setText(label);
        head.setTextColor(0xFFFFFFFF);
        head.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.setPadding(Math.round(4 * d), 0, 0, Math.round(6 * d));
        head.setOnClickListener(v -> { close.run(); open(a, taskId, pkg); });
        col.addView(head);
        java.util.List<RecentsNotifs.N> ns = RecentsNotifs.active(java.util.Collections.singleton(pkg));
        if (!ns.isEmpty()) {
            LinearLayout list = new LinearLayout(a);
            list.setOrientation(LinearLayout.VERTICAL);
            for (RecentsNotifs.N s : ns) {
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rl.bottomMargin = Math.round(4 * d);
                list.addView(RecentsNotifs.live(s) ? RecentsNotifs.liveBlock(a, s, d, null) : RecentsNotifs.row(a, s, d, close), rl);
            }
            android.widget.ScrollView sv = new android.widget.ScrollView(a);
            sv.addView(list);
            int maxH = Math.round(300 * d);
            list.measure(View.MeasureSpec.makeMeasureSpec(Math.round(244 * d), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxH, list.getMeasuredHeight())));
            if (ns.size() > 1) {
                TextView all = CardNotifs.pill(a, "Clear " + ns.size() + " notifications", d, () -> { RecentsNotifs.clear(a, java.util.Collections.singleton(pkg)); close.run(); CardNotifs.update(); });
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cl.bottomMargin = Math.round(6 * d);
                col.addView(all, cl);
            }
        } else {
            TextView none = new TextView(a);
            none.setText("No notifications");
            none.setTextColor(0x99FFFFFF);
            none.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            none.setPadding(Math.round(4 * d), 0, 0, Math.round(6 * d));
            col.addView(none);
        }
        for (android.content.pm.ShortcutInfo sc : RecentsNotifs.shortcuts(a, pkg)) {
            LinearLayout r = new LinearLayout(a);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Math.round(4 * d), Math.round(8 * d), Math.round(4 * d), Math.round(8 * d));
            ImageView iv = new ImageView(a);
            iv.setImageDrawable(RecentsNotifs.shortcutIcon(a, sc));
            int is = Math.round(24 * d);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(is, is);
            il.rightMargin = Math.round(10 * d);
            r.addView(iv, il);
            TextView t = new TextView(a);
            CharSequence nm = sc.getShortLabel() != null ? sc.getShortLabel() : sc.getLongLabel();
            t.setText(nm);
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            t.setSingleLine(true);
            r.addView(t);
            r.setOnClickListener(v -> { close.run(); RecentsNotifs.startShortcut(a, sc); });
            col.addView(r);
        }
        android.widget.PopupWindow p = new android.widget.PopupWindow(col, Math.round(260 * d), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        p.setElevation(12 * d);
        p.setOutsideTouchable(true);
        pw[0] = p;
        col.measure(View.MeasureSpec.makeMeasureSpec(Math.round(260 * d), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int x = at[0] - Math.round(260 * d) - Math.round(6 * d);
        int y = Math.max(Math.round(40 * d), Math.min(at[1] + anchor.getHeight() - col.getMeasuredHeight(), a.getWindow().getDecorView().getHeight() - col.getMeasuredHeight() - Math.round(40 * d)));
        p.showAtLocation(anchor, Gravity.TOP | Gravity.START, Math.max(0, x), y);
    }

    static void openPkg(Activity a, String pkg) {
        try {
            Intent i = a.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) { Log.w("recents open pkg: " + t); }
    }

    /** Bring the task back like its card does; if it is gone, start the app fresh. */
    private static void open(Activity a, int taskId, String pkg) {
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            int r = (Integer) atm.getClass().getMethod("startActivityFromRecents", int.class, android.os.Bundle.class).invoke(atm, taskId, null);
            if (r >= 0) return;
        } catch (Throwable t) { Log.w("recents rail open: " + t); }
        try {
            Intent i = a.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) { Log.w("recents rail launch: " + t); }
    }
}
