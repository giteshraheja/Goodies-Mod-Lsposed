// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.app.BroadcastOptions;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Status bar side of "notifications in Recent apps". Samsung only hands its launcher the ongoing notifications, but the
 * status bar sees them all, so it answers the launcher's request with a compact list (text, icons, tap / button intents).
 * Only answers while asked (Recent apps open) and only to the launcher; clearing is accepted from the launcher only.
 */
final class SysNotifs {
    private SysNotifs() {}

    static final String REQ = "dev.gitesh.sbmods.NOTIFS_REQ", LIST = "dev.gitesh.sbmods.NOTIFS_LIST", DISMISS = "dev.gitesh.sbmods.NOTIFS_DISMISS";
    private static WeakReference<NotificationListenerService> listener = new WeakReference<>(null);
    private static boolean registered;

    static void install(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.NotificationListener", cl, "onListenerConnected", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    NotificationListenerService l = (NotificationListenerService) p.thisObject;
                    listener = new WeakReference<>(l);
                    try {   // SystemUI registers this listener without a context of its own: use the app's
                        register((Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null));
                    } catch (Throwable t) { Log.w("sys notifs register: " + t); }
                }
            });
        } catch (Throwable t) { Log.w("sys notifs hook: " + t); Log.miss("SystemUI NotificationListener", "notifications in Recent apps"); }
    }

    private static void register(Context c) {
        if (registered || c == null) return;
        registered = true;
        IntentFilter f = new IntentFilter();
        f.addAction(REQ);
        f.addAction(DISMISS);
        c.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                try {
                    if (!Entry.LAUNCHER.equals(getSentFromPackage())) return;   // only the launcher may ask or clear
                    if (DISMISS.equals(i.getAction())) { dismiss(i.getStringArrayExtra("keys")); return; }
                    reply(ctx);
                } catch (Throwable t) { Log.w("sys notifs: " + t); }
            }
        }, f, Context.RECEIVER_EXPORTED);
    }

    private static void dismiss(String[] keys) {
        NotificationListenerService l = listener.get();
        if (l == null || keys == null) return;
        try { l.cancelNotifications(keys); } catch (Throwable t) { Log.w("sys notifs dismiss: " + t); }
    }

    private static void reply(Context ctx) {
        NotificationListenerService l = listener.get();
        ArrayList<Bundle> out = new ArrayList<>();
        if (l != null) {
            StatusBarNotification[] all = l.getActiveNotifications();
            if (all != null) {
                HashSet<String> withChildren = new HashSet<>();
                for (StatusBarNotification s : all)
                    if (s.getNotification().getGroup() != null && (s.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) == 0) withChildren.add(s.getPackageName() + "|" + s.getNotification().getGroup());
                java.util.Arrays.sort(all, (x, y) -> Long.compare(y.getPostTime(), x.getPostTime()));   // newest first, so a per-app cap keeps the newest
                java.util.HashMap<String, Integer> per = new java.util.HashMap<>();
                for (StatusBarNotification s : all) {
                    Notification n = s.getNotification();
                    if (s.getUserId() != 0 && s.getUserId() != -1) continue;
                    if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 && n.getGroup() != null && withChildren.contains(s.getPackageName() + "|" + n.getGroup())) continue;   // groups merge into one list
                    int c = per.getOrDefault(s.getPackageName(), 0);
                    if (c >= 25) continue;   // cap per app: one busy app (49 Outlook mails) must not push other apps' notifications out
                    Bundle b = pack(s);
                    if (b != null) { out.add(b); per.put(s.getPackageName(), c + 1); }
                    if (out.size() >= 200) break;
                }
            }
        }
        Intent r = new Intent(LIST).setPackage(Entry.LAUNCHER);
        r.putParcelableArrayListExtra("n", out);
        BroadcastOptions o = BroadcastOptions.makeBasic();
        o.setShareIdentityEnabled(true);
        ctx.sendBroadcast(r, null, o.toBundle());
    }

    private static CharSequence str(CharSequence c) { return c == null ? null : c.toString(); }   // plain text: spans can hold app classes

    private static Bundle pack(StatusBarNotification s) {
        Notification n = s.getNotification();
        Bundle e = n.extras;
        CharSequence title = e.getCharSequence(Notification.EXTRA_TITLE);
        if (title == null) title = e.getCharSequence(Notification.EXTRA_TITLE_BIG);
        CharSequence text = e.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence big = e.getCharSequence(Notification.EXTRA_BIG_TEXT);   // expanded text when it says more
        if (big != null && (text == null || big.length() > text.length())) text = big;
        if (text == null) { CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES); if (lines != null && lines.length > 0) text = lines[lines.length - 1]; }
        if ((title == null || title.length() == 0) && (text == null || text.length() == 0)) return null;
        Bundle b = new Bundle();
        b.putString("key", s.getKey());
        b.putString("pkg", s.getPackageName());
        b.putCharSequence("title", str(title));
        b.putCharSequence("text", str(text));
        b.putLong("time", s.getPostTime());
        b.putInt("flags", n.flags);
        b.putInt("color", n.color);
        b.putBoolean("clear", s.isClearable());
        b.putBoolean("media", e.containsKey(Notification.EXTRA_MEDIA_SESSION));
        try {   // music: the launcher drives the player itself (cover, seek bar, buttons) through the media session
            android.media.session.MediaSession.Token tk = e.getParcelable(Notification.EXTRA_MEDIA_SESSION, android.media.session.MediaSession.Token.class);
            if (tk != null) b.putParcelable("session", tk);
        } catch (Throwable ignored) {}
        if (n.contentIntent != null) b.putParcelable("tap", n.contentIntent);
        Icon si = n.getSmallIcon();
        if (si != null && si.getType() == Icon.TYPE_RESOURCE) b.putParcelable("icon", si);
        if (n.actions != null) {
            ArrayList<Bundle> acts = new ArrayList<>();
            for (Notification.Action a : n.actions) {
                if (a == null || a.actionIntent == null || acts.size() >= 5) continue;
                if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue;   // "Reply" needs a text box: open the app for that
                Bundle ab = new Bundle();
                ab.putCharSequence("title", str(a.title));
                ab.putParcelable("intent", a.actionIntent);
                Icon ic = a.getIcon();
                if (ic != null && ic.getType() == Icon.TYPE_RESOURCE) ab.putParcelable("icon", ic);
                acts.add(ab);
            }
            b.putParcelableArrayList("acts", acts);
        }
        return b;
    }
}
