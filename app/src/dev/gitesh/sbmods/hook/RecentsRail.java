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
            bg.setCornerRadius(17 * d);
            rail.setBackground(bg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(34 * d), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
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
        int slot = Math.round(34 * d);   // 26 dp icon + 8 dp gap
        int max = Math.max(1, (bottom - top - Math.round(8 * d)) / slot);
        List<ActivityManager.RecentTaskInfo> tasks = tasks(a, max);
        StringBuilder key = new StringBuilder();
        for (ActivityManager.RecentTaskInfo t : tasks) key.append(t.taskId).append(',');
        if (!key.toString().equals(shownKey)) {
            shownKey = key.toString();
            rail.removeAllViews();
            for (int i = tasks.size() - 1; i >= 0; i--) rail.addView(icon(a, tasks.get(i), d));   // oldest on top, newest at the bottom
            rail.setVisibility(tasks.isEmpty() ? View.GONE : View.VISIBLE);
        }
        // grow upward from "Close all"
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) rail.getLayoutParams();
        int h = rail.getPaddingTop() + rail.getPaddingBottom() + tasks.size() * slot;
        int want = Math.max(top, bottom - h);
        if (lp.topMargin != want || lp.gravity != (Gravity.TOP | Gravity.END)) {
            lp.topMargin = want;
            lp.gravity = Gravity.TOP | Gravity.END;
            rail.setLayoutParams(lp);
        }
    }

    private static List<ActivityManager.RecentTaskInfo> tasks(Context c, int max) {
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

    private static ComponentName comp(ActivityManager.RecentTaskInfo t) {
        if (t.baseIntent != null && t.baseIntent.getComponent() != null) return t.baseIntent.getComponent();
        if (t.baseActivity != null) return t.baseActivity;
        return t.topActivity;
    }

    private static View icon(Activity a, ActivityManager.RecentTaskInfo t, float d) {
        ImageView iv = new ImageView(a);
        int size = Math.round(26 * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.topMargin = lp.bottomMargin = Math.round(4 * d);
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
        iv.setOnLongClickListener(v -> { Toast.makeText(a, label, Toast.LENGTH_SHORT).show(); return true; });
        return iv;
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
