// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.BroadcastOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Launcher side of "notifications in Recent apps". Samsung only hands the launcher's own listener the ongoing notifications,
 * so the list comes from the status bar part of the mod (SysNotifs) on request, only while Recent apps is open.
 * Rows: tap = open the notification, swipe sideways = dismiss. Also app shortcuts (LauncherApps: the launcher is the home app).
 */
final class RecentsNotifs {
    private RecentsNotifs() {}

    /** One notification as the status bar sent it. */
    static final class N {
        String key, pkg; CharSequence title, text; long time; int flags, color; boolean clear, media;
        PendingIntent tap; Icon icon; List<Bundle> acts = new ArrayList<>(); android.media.session.MediaSession.Token session;
        String getKey() { return key; }
        String getPackageName() { return pkg; }
        long getPostTime() { return time; }
        String actsKey() { StringBuilder b = new StringBuilder(); for (Bundle x : acts) b.append(x.getCharSequence("title")); return b.toString(); }
    }

    private static volatile List<N> cache = new ArrayList<>();
    private static boolean registered;
    private static Runnable onUpdate;

    static boolean on(Context c) { return Global.gint(c, "sbmods_recents_notifs", 1) != 0; }

    /** Listen for the status bar's answers; `update` runs on the main thread after each one. */
    static void bind(Context c, Runnable update) {
        onUpdate = update;
        if (registered) return;
        registered = true;
        Context app = c.getApplicationContext();
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                try {
                    if (!"com.android.systemui".equals(getSentFromPackage())) return;   // only the status bar's list
                    ArrayList<Bundle> l = i.getParcelableArrayListExtra("n", Bundle.class);
                    List<N> out = new ArrayList<>();
                    if (l != null) for (Bundle b : l) out.add(unpack(b));
                    Collections.sort(out, (x, y) -> Long.compare(y.time, x.time));
                    cache = out;
                    if (onUpdate != null) onUpdate.run();
                } catch (Throwable t) { Log.w("recents notifs list: " + t); }
            }
        }, new IntentFilter(SysNotifs.LIST), Context.RECEIVER_EXPORTED);
    }

    /** Ask the status bar for the current list (answer arrives in a few ms). */
    static void request(Context c) {
        try {
            BroadcastOptions o = BroadcastOptions.makeBasic();
            o.setShareIdentityEnabled(true);
            c.sendBroadcast(new Intent(SysNotifs.REQ).setPackage("com.android.systemui"), null, o.toBundle());
        } catch (Throwable t) { Log.w("recents notifs request: " + t); }
    }

    private static N unpack(Bundle b) {
        N n = new N();
        n.key = b.getString("key"); n.pkg = b.getString("pkg");
        n.title = b.getCharSequence("title"); n.text = b.getCharSequence("text");
        n.time = b.getLong("time"); n.flags = b.getInt("flags"); n.color = b.getInt("color");
        n.clear = b.getBoolean("clear"); n.media = b.getBoolean("media");
        n.tap = b.getParcelable("tap", PendingIntent.class);
        n.icon = b.getParcelable("icon", Icon.class);
        n.session = b.getParcelable("session", android.media.session.MediaSession.Token.class);
        ArrayList<Bundle> a = b.getParcelableArrayList("acts", Bundle.class);
        if (a != null) n.acts = a;
        return n;
    }

    /** Notifications of these packages (all when null), newest first. */
    static List<N> active(Collection<String> pkgs) {
        List<N> out = new ArrayList<>();
        for (N n : cache) if (pkgs == null || pkgs.contains(n.pkg)) out.add(n);
        return out;
    }

    static void open(Activity a, N s) {
        try {
            if (s.tap == null) { RecentsRail.openPkg(a, s.pkg); return; }
            send(a, s.tap);
            if ((s.flags & Notification.FLAG_AUTO_CANCEL) != 0) dismissKeys(a, s.key);
        } catch (Throwable t) { Log.w("recents notif open: " + t); RecentsRail.openPkg(a, s.pkg); }
    }

    private static void send(Activity a, PendingIntent pi) throws PendingIntent.CanceledException {
        ActivityOptions o = ActivityOptions.makeBasic();
        try { o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED); } catch (Throwable ignored) {}
        pi.send(a, 0, null, null, null, null, o.toBundle());
    }

    static void dismiss(Context c, N s) { if (s.clear) dismissKeys(c, s.key); }

    private static void dismissKeys(Context c, String... keys) {
        try {
            BroadcastOptions o = BroadcastOptions.makeBasic();
            o.setShareIdentityEnabled(true);
            c.sendBroadcast(new Intent(SysNotifs.DISMISS).setPackage("com.android.systemui").putExtra("keys", keys), null, o.toBundle());
            List<N> left = new ArrayList<>(cache);   // drop them here too, so the cards update at once
            for (String k : keys) left.removeIf(n -> n.key.equals(k));
            cache = left;
        } catch (Throwable t) { Log.w("recents notif dismiss: " + t); }
    }

    /** Clear every clearable notification of these apps; the apps themselves stay open. */
    static int clear(Context c, Collection<String> pkgs) {
        List<String> keys = new ArrayList<>();
        for (N n : active(pkgs)) if (n.clear) keys.add(n.key);
        if (!keys.isEmpty()) dismissKeys(c, keys.toArray(new String[0]));
        return keys.size();
    }

    /** Live notification (music, call, sports score, timer...): a media session, or ongoing with buttons of its own. */
    static boolean live(N s) { return s.media || ((s.flags & Notification.FLAG_ONGOING_EVENT) != 0 && !s.acts.isEmpty()); }

    // ------------------------------------------------------------------ views

    private static Drawable load(Context a, Icon ic, String pkg) {
        if (ic == null) return null;
        try { return ic.loadDrawable(a.createPackageContext(pkg, 0)); } catch (Throwable t) { return null; }
    }

    /** One notification row: app's small icon, title, text; tap opens it, a sideways swipe dismisses it. */
    static View row(Activity a, N s, float d, Runnable after) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int p = Math.round(6 * d);
        r.setPadding(p, Math.round(5 * d), p, Math.round(5 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE61C1C1E);
        bg.setCornerRadius(12 * d);
        r.setBackground(bg);
        // no app icon: the card (and the column popup's title) already say which app it is
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a);
        t.setText(s.title);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t);
        if (!TextUtils.isEmpty(s.text)) {
            TextView b = new TextView(a);
            b.setText(s.text);
            b.setTextColor(0xCCFFFFFF);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            b.setMaxLines(2);
            b.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(b);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        swipe(r, () -> { open(a, s); if (after != null) after.run(); }, s.clear ? () -> { dismiss(a, s); if (after != null) after.run(); } : null);
        return r;
    }

    /** Bigger block for a live notification: title, text and its own buttons (play / pause / next, answer / hang up...). */
    static View liveBlock(Activity a, N s, float d, Runnable after) {
        if (s.session != null) { try { return MediaBlock.make(a, s, d); } catch (Throwable t) { Log.w("media block: " + t); } }
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(8 * d);
        box.setPadding(p, p, p, Math.round(6 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(s.color != 0 && s.color != Notification.COLOR_DEFAULT ? (s.color & 0x00FFFFFF) | 0xF0000000 : 0xF02C2C2E);
        bg.setCornerRadius(16 * d);
        box.setBackground(bg);
        View head = row(a, s, d, after);
        head.setBackground(null);
        head.setPadding(0, 0, 0, 0);
        box.addView(head);
        if (!s.acts.isEmpty()) {
            LinearLayout btns = new LinearLayout(a);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setGravity(Gravity.CENTER);
            for (Bundle act : s.acts) {
                CharSequence title = act.getCharSequence("title");
                PendingIntent pi = act.getParcelable("intent", PendingIntent.class);
                Drawable ic = load(a, act.getParcelable("icon", Icon.class), s.pkg);
                View b;
                if (ic != null) {
                    ImageView iv = new ImageView(a);
                    ic = ic.mutate(); ic.setTint(0xFFFFFFFF);
                    iv.setImageDrawable(ic);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    int ip = Math.round(4 * d);
                    iv.setPadding(ip, ip, ip, ip);
                    iv.setContentDescription(title);
                    b = iv;
                } else {
                    TextView t = new TextView(a);
                    t.setText(title);
                    t.setTextColor(0xFFFFFFFF);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    t.setGravity(Gravity.CENTER);
                    t.setSingleLine(true);
                    t.setEllipsize(TextUtils.TruncateAt.END);
                    b = t;
                }
                GradientDrawable bb = new GradientDrawable();
                bb.setColor(0x33FFFFFF);
                bb.setCornerRadius(14 * d);
                b.setBackground(bb);
                b.setOnClickListener(v -> {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                    try { if (pi != null) send(a, pi); } catch (Throwable t) { Log.w("recents notif action: " + t); }
                    if (after != null) after.run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Math.round(32 * d), 1f);
                lp.leftMargin = lp.rightMargin = Math.round(2 * d);
                btns.addView(b, lp);
            }
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.topMargin = Math.round(6 * d);
            box.addView(btns, bl);
        }
        return box;
    }

    /** Tap = click; a sideways drag past a third of the width = swipe away (when clearable). */
    private static void swipe(View v, Runnable click, Runnable gone) {
        final float[] st = new float[2];
        final boolean[] drag = {false};
        v.setOnTouchListener((view, e) -> {
            int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    st[0] = e.getRawX(); st[1] = e.getRawY(); drag[0] = false;
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    view.setAlpha(0.85f);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - st[0], dy = e.getRawY() - st[1];
                    if (!drag[0] && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) drag[0] = true;
                    if (drag[0] && gone != null) { view.setTranslationX(dx); view.setAlpha(Math.max(0.2f, 1f - Math.abs(dx) / view.getWidth())); }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float dx = e.getRawX() - st[0], dy = e.getRawY() - st[1];
                    if (drag[0] && gone != null && Math.abs(dx) > view.getWidth() / 3f) {
                        view.animate().translationX(Math.signum(dx) * view.getWidth()).alpha(0f).setDuration(150).withEndAction(gone).start();
                    } else {
                        view.animate().translationX(0).alpha(1f).setDuration(120).start();
                        if (!drag[0] && Math.abs(dy) < slop) { view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); click.run(); }
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    view.animate().translationX(0).alpha(1f).setDuration(120).start();
                    return true;
                default:
                    return true;
            }
        });
    }

    /** The app's shortcuts (like a long-press on a home screen icon): dynamic + manifest, max 5. */
    static List<ShortcutInfo> shortcuts(Context c, String pkg) {
        try {
            LauncherApps la = c.getSystemService(LauncherApps.class);
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(pkg);
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            List<ShortcutInfo> l = la.getShortcuts(q, Process.myUserHandle());
            if (l == null) return new ArrayList<>();
            List<ShortcutInfo> out = new ArrayList<>();
            for (ShortcutInfo s : l) if (s.isEnabled() && (s.isDeclaredInManifest() || s.isDynamic())) out.add(s);
            Collections.sort(out, (x, y) -> Integer.compare(x.getRank(), y.getRank()));
            return out.size() > 5 ? out.subList(0, 5) : out;
        } catch (Throwable t) { Log.w("recents shortcuts: " + t); return new ArrayList<>(); }
    }

    static void startShortcut(Context c, ShortcutInfo s) {
        try { c.getSystemService(LauncherApps.class).startShortcut(s, null, null); } catch (Throwable t) { Log.w("recents shortcut start: " + t); }
    }

    static Drawable shortcutIcon(Context c, ShortcutInfo s) {
        try { return c.getSystemService(LauncherApps.class).getShortcutIconDrawable(s, c.getResources().getDisplayMetrics().densityDpi); } catch (Throwable t) { return null; }
    }
}
