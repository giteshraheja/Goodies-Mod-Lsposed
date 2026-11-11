// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Proxy;
import java.util.List;

import dev.gitesh.sbmods.Config;
import dev.gitesh.sbmods.ZoneLayout;
import dev.gitesh.sbmods.Ui;

/**
 * "◀ App" on the left of the status bar: shows the previously used app and switches back to it
 * on tap. Updates only when the task stack changes (SystemUI's own TaskStackChangeListeners),
 * so it costs nothing while you stay in one app. Hidden on home, lock screen, or when there is
 * no previous app.
 */
final class BackChip {
    private final Controller owner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView chip;
    private android.graphics.drawable.GradientDrawable pill;
    private int pillFor;
    private Object listener;            // proxy registered with TaskStackChangeListeners
    private boolean receiverOn;
    private int targetTask = -1;
    private String homePkg;
    private String lastPkg, iconPkg;
    private android.graphics.drawable.Drawable iconDraw, iconDrawDark;
    private android.graphics.drawable.Drawable shownIcon;
    private int tint = Ui.CHIP_TINT;
    private boolean faded;                   // dulled to plain text after the chosen time
    private String shownKey;                 // what the chip currently points at (restarts the fade timer on change)
    private android.animation.ValueAnimator fadeAnim;
    private final Runnable fadeTask = this::fadeOut;
    private boolean lastLive, forcedFade, peeking, shareLive, lastCap;
    private final Runnable endPeekTask = this::endPeek;

    /** True while Stack mode has a live activity: the back button then ignores its own zone (it shares the live activity's spot). */
    static volatile boolean stackLive;

    boolean isPeeking() { return peeking; }

    private boolean sliverApplied, sliverWanted, restingBlank, mergeWanted, mergeApplied, compactWanted;
    private java.lang.ref.WeakReference<View> notifBox = new java.lang.ref.WeakReference<>(null);
    private final int[] hl = new int[2], hl2 = new int[2];

    /** Is this screen point on the back button? In Merge mode only the part that sticks out of the live activity counts. */
    boolean hitBack(float x, float y) {
        TextView c = chip;
        if (c == null || !c.isShown() || c.getWidth() == 0 || sliverWanted) return false;
        float slack = 4 * c.getResources().getDisplayMetrics().density;
        c.getLocationOnScreen(hl);
        float minX = hl[0] - slack;
        if (mergeWanted) {
            View lb = liveBox();
            if (lb != null) { lb.getLocationOnScreen(hl2); minX = hl2[0] + lb.getWidth(); }
        }
        return x >= minX && x <= hl[0] + c.getWidth() + slack && y >= hl[1] - slack && y <= hl[1] + c.getHeight() + slack;
    }

    /**
     * "Merge": the back button becomes a small tab (chevron + previous app's icon, no name) that is joined to the
     * right end of the live activity, as if the capsule had one more segment. It sits behind the capsule's rounded
     * end, and the notification icons slide over to make room.
     */
    private void syncMerge() {
        TextView c = chip;
        if (c == null) return;
        View lb = mergeWanted && c.getVisibility() == View.VISIBLE ? liveBox() : null;
        View nb = notifBox.get();
        if (nb == null || !nb.isAttachedToWindow()) {
            int id = c.getResources().getIdentifier("start_side_notif_and_chip_container", "id", Config.SYSUI);
            nb = id == 0 ? null : c.getRootView().findViewById(id);
            notifBox = new java.lang.ref.WeakReference<>(nb);
        }
        if (lb != null && capsuleShown(lb) && c.getWidth() > 0) {
            float d = c.getResources().getDisplayMetrics().density;
            int overlap = Math.round(11 * d);
            int[] a = new int[2], b = new int[2];
            c.getLocationOnScreen(a);
            lb.getLocationOnScreen(b);
            float natX = a[0] - c.getTranslationX(), natY = a[1] - c.getTranslationY();
            float tx = (b[0] + lb.getWidth() - overlap) - natX;
            float ty = (b[1] + lb.getHeight() / 2f) - (natY + c.getHeight() / 2f);
            c.setTranslationZ(-2f);
            c.setAlpha(1f);
            if (Math.abs(c.getTranslationX() - tx) > 0.5f) c.setTranslationX(tx);
            if (Math.abs(c.getTranslationY() - ty) > 0.5f) c.setTranslationY(ty);
            // + room for things placed right after the button (network speed). extraPad ends at that item's right edge + one gap
            // measured from "button + gap", but the icons here start at the button itself: add the missing gap or they touch the speed text
            Config cfg = owner.config();
            float gap = (cfg.itemGap >= 0 ? cfg.itemGap : 6) * d;
            float push = Math.max(0, c.getWidth() - overlap) + (extraPad > 0.5f ? extraPad + gap : 0);
            if (nb != null && Math.abs(nb.getTranslationX() - push) > 0.5f) nb.setTranslationX(push);
            mergeApplied = true;
        } else if (mergeApplied) {
            mergeApplied = false;
            c.setTranslationZ(0f);
            c.setTranslationX(0f);
            c.setTranslationY(0f);
            if (nb != null) nb.setTranslationX(0f);
        }
    }

    /** Stack mode with a live activity, back button resting behind it (only its lower edge shows). */
    boolean sliverMode() { return sliverWanted && chip != null; }

    /** Is a live-activity capsule really on screen? Samsung can keep the empty box (width > 0) after its content is gone. */
    static boolean capsuleShown(View lb) {
        if (lb == null || lb.getWidth() <= 12 || !lb.isShown() || lb.getAlpha() < 0.1f) return false;
        View v = lb; int guard = 0;
        while (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0 && guard++ < 8) {
            View first = null;
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { View c = g.getChildAt(i); if (c.getVisibility() == View.VISIBLE) { first = c; break; } }
            if (first == null) return false;      // every child gone: the box is empty
            if (first.getAlpha() < 0.1f) return false;
            v = first;
        }
        return v.getWidth() > 0;
    }

    private View liveBox() {
        TextView c = chip;
        if (c == null) return null;
        int id = c.getResources().getIdentifier("samsung_ongoing_activity_chip_container", "id", Config.SYSUI);
        return id == 0 ? null : c.getRootView().findViewById(id);
    }

    /**
     * Like One UI's stacked cards: the back button rests slightly narrower behind the live activity and a
     * few dp lower, so its bottom edge peeks out and you can see something is underneath.
     */
    private void syncSliver() {
        TextView c = chip;
        if (c == null) return;
        View lb = sliverWanted && !peeking && c.getVisibility() == View.VISIBLE ? liveBox() : null;
        if (lb != null && capsuleShown(lb) && c.getWidth() > 0) {
            float d = c.getResources().getDisplayMetrics().density;
            int inset = Math.round(9 * d), show = Math.round(4 * d);
            int w = Math.max(Math.round(30 * d), lb.getWidth() - 2 * inset);
            int[] a = new int[2], b = new int[2];
            c.getLocationOnScreen(a);
            lb.getLocationOnScreen(b);
            float natX = a[0] - c.getTranslationX(), natY = a[1] - c.getTranslationY();
            float tx = b[0] + inset - natX;
            float ty = (b[1] + lb.getHeight() + show) - (natY + c.getHeight());
            if (c.getMinWidth() != w) { c.setMinWidth(w); c.setGravity(Gravity.CENTER); }
            c.setTranslationZ(-2f);
            c.setAlpha(0.8f);
            restingBlank = true;   // resting behind the live activity: only the coloured edge shows, no text or icon
            if (c.getCurrentTextColor() != 0) c.setTextColor(0);
            if (c.getCompoundDrawables()[0] != null) c.setCompoundDrawables(null, null, null, null);
            if (Math.abs(c.getTranslationX() - tx) > 0.5f) c.setTranslationX(tx);
            if (Math.abs(c.getTranslationY() - ty) > 0.5f) c.setTranslationY(ty);
            sliverApplied = true;
        } else if (sliverApplied) {
            sliverApplied = false;
            if (restingBlank) { restingBlank = false; pillFor = 0; update(); }
            c.setTranslationZ(0f);
            c.setAlpha(1f);
            c.setTranslationX(0f);
            c.setTranslationY(0f);
            if (!peeking) { c.setMinWidth(0); c.setGravity(Gravity.CENTER_VERTICAL); }
        }
    }

    /** "Stack" mode is on, the back button sits at the left start, and a live activity owns that spot. */
    boolean stackActive() {
        Config cfg = owner.config();
        return chip != null && cfg.backEnabled && cfg.backLive == 3 && shareLive;
    }

    /** Long-press on the live activity: the back button slides over it for a few seconds. */
    void peek(View liveBox) {
        TextView c = chip;
        if (c == null || !stackActive() || liveBox == null) return;
        peeking = true;
        update();
        if (c.getVisibility() != View.VISIBLE) { peeking = false; return; }
        sliverWanted = false;
        sliverApplied = false;
        c.setMinWidth(liveBox.getWidth());
        c.setGravity(Gravity.CENTER);
        c.setTranslationX(0);
        c.setTranslationY(0);
        c.setTranslationZ(6f);
        c.setAlpha(1f);
        c.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        main.removeCallbacks(endPeekTask);
        main.postDelayed(endPeekTask, 5000);
    }

    /** Back to the live activity. */
    void endPeek() {
        main.removeCallbacks(endPeekTask);
        if (!peeking) return;
        peeking = false;
        TextView c = chip;
        if (c != null) {
            c.setMinWidth(0);
            c.setGravity(Gravity.CENTER_VERTICAL);
            c.setTranslationX(0);
            c.setTranslationZ(0f);
        }
        update();
    }
    // Live-activity tracking is event driven: a listener for media sessions appearing / going away, plus a callback on
    // each current session for playback-state changes. No timer runs in the normal path and nothing runs with the screen
    // off. If the listener cannot be registered, the old 3 s poll is used instead, paused while the screen is off.
    private android.media.session.MediaSessionManager liveMsm;
    private android.media.session.MediaSessionManager.OnActiveSessionsChangedListener sessListener;
    private final java.util.ArrayList<android.media.session.MediaController> watched = new java.util.ArrayList<>();
    private boolean pollFallback, screenOff;
    private final android.media.session.MediaController.Callback ctlCb = new android.media.session.MediaController.Callback() {
        @Override public void onPlaybackStateChanged(android.media.session.PlaybackState st) { recheckLive(); }
        @Override public void onSessionDestroyed() { recheckLive(); }
    };

    /** Same check the old poll made: refresh only when the live state or the capsule state changed. */
    private void recheckLive() {
        if (chip == null) return;
        Config cfg = owner.config();
        if (!cfg.backEnabled || cfg.backLive == 0) { lastLive = false; return; }
        boolean cap = capsuleShown(liveBox());
        boolean now = cap || liveActive(chip.getContext());
        if (now != lastLive || cap != lastCap) { lastLive = now; update(); }
    }

    private void watchControllers(List<android.media.session.MediaController> l) {
        for (android.media.session.MediaController mc : watched) { try { mc.unregisterCallback(ctlCb); } catch (Throwable ignored) { } }
        watched.clear();
        if (l == null) return;
        for (android.media.session.MediaController mc : l) {
            try { mc.registerCallback(ctlCb, main); watched.add(mc); } catch (Throwable ignored) { }
        }
    }

    /** Start / stop live tracking to match the options. Safe to call repeatedly. */
    private void syncLiveTracking() {
        Config cfg = owner.config();
        boolean want = chip != null && cfg.backEnabled && cfg.backLive != 0;
        if (!want) { stopLiveTracking(); return; }
        if (sessListener != null) return;
        if (!pollFallback) {
            try {
                Context ctx = chip.getContext();
                liveMsm = ctx.getSystemService(android.media.session.MediaSessionManager.class);
                sessListener = l -> { watchControllers(l); recheckLive(); };
                liveMsm.addOnActiveSessionsChangedListener(sessListener, null, main);
                watchControllers(liveMsm.getActiveSessions(null));
                return;
            } catch (Throwable t) {
                Log.w("back chip: session listener failed, polling instead: " + t);
                sessListener = null;
                liveMsm = null;
                pollFallback = true;
            }
        }
        main.removeCallbacks(livePoll);
        if (!screenOff) main.postDelayed(livePoll, 3000);
    }

    private void stopLiveTracking() {
        main.removeCallbacks(livePoll);
        if (sessListener != null && liveMsm != null) {
            try { liveMsm.removeOnActiveSessionsChangedListener(sessListener); } catch (Throwable ignored) { }
        }
        sessListener = null;
        liveMsm = null;
        watchControllers(null);
    }

    /** Fallback only (listener unavailable). */
    private final Runnable livePoll = new Runnable() {
        @Override public void run() {
            main.removeCallbacks(this);
            if (chip == null || screenOff) return;
            Config cfg = owner.config();
            if (cfg.backEnabled && cfg.backLive != 0) {
                recheckLive();
                main.postDelayed(this, 3000);
            } else lastLive = false;
        }
    };

    /** Is something playing / paused in a media session (which Samsung shows as a live activity capsule)? */
    private boolean liveActive(Context ctx) {
        try {
            android.media.session.MediaSessionManager msm = ctx.getSystemService(android.media.session.MediaSessionManager.class);
            List<android.media.session.MediaController> l = msm.getActiveSessions(null);
            if (l == null) return false;
            for (android.media.session.MediaController mc : l) {
                android.media.session.PlaybackState st = mc.getPlaybackState();
                if (st == null) continue;
                int s = st.getState();
                if (s == android.media.session.PlaybackState.STATE_PLAYING || s == android.media.session.PlaybackState.STATE_PAUSED
                        || s == android.media.session.PlaybackState.STATE_BUFFERING) return true;
            }
        } catch (Throwable t) {
            Log.w("back chip: media check failed: " + t);
        }
        return false;
    }

    /** Fill + a thin outline (boundary) in the text colour, so the button stays readable on any wallpaper. */
    private void setPill(int fill) {
        if (pill == null) return;
        pill.setColor(fill);
        float d = chip != null ? chip.getResources().getDisplayMetrics().density : 2.5f;
        int line = (fill >>> 24) == 0 ? 0 : ((chip != null ? chip.getCurrentTextColor() : Ui.CHIP_FG) & 0x00FFFFFF) | 0xB3000000;
        pill.setStroke(Math.max(2, Math.round(1.2f * d)), line);
    }

    private LinearLayout leftRow;
    private int rowPadStart;

    BackChip(Controller owner) { this.owner = owner; }

    /** Put the chip at the start of the left side of the status bar (next to notification icons). */
    void attach(TextView clock) {
        Context ctx = clock.getContext();
        int rowId = ctx.getResources().getIdentifier("status_bar_start_side_except_heads_up", "id", Config.SYSUI);
        View row = rowId == 0 ? null : clock.getRootView().findViewById(rowId);
        if (!(row instanceof LinearLayout)) { Log.w("back chip: left row not found"); return; }
        if (!owner.config().backEnabled) { detach(); return; } // never touch the layout unless wanted
        if (chip != null && chip.getParent() == row) return;
        detach();
        chip = new TextView(ctx);
        chip.setTag("sbmods-back");
        chip.setSingleLine(true);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setIncludeFontPadding(clock.getIncludeFontPadding());
        chip.setVisibility(View.GONE);
        float d = ctx.getResources().getDisplayMetrics().density;
        chip.setPadding(Math.round(7 * d), Math.round(1 * d), Math.round(9 * d), Math.round(1 * d));
        pill = new android.graphics.drawable.GradientDrawable();
        pill.setCornerRadius(100 * d);
        setPill(Ui.CHIP_PILL);
        chip.setBackground(pill);
        // The chip is an overlay next to Samsung's left row, not a child of it: Samsung's notification /
        // live-activity logic looks at its own row's children, and an extra child there made the live
        // activity disappear. The row's content is moved over with padding instead (see syncRow).
        ViewParent rp = row.getParent();
        if (!(rp instanceof android.widget.FrameLayout)) { Log.w("back chip: left row parent is not a FrameLayout"); chip = null; return; }
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        ((ViewGroup) rp).addView(chip, 0, lp);   // index 0 + Z order: Samsung's row draws over it when stacked
        leftRow = (LinearLayout) row;
        rowPadStart = leftRow.getPaddingStart();
        style(clock);
        register(ctx, clock.getClass().getClassLoader());
        update();
        Log.i("back chip attached");
    }

    void detach() {
        mergeWanted = false;
        mergeApplied = false;
        View nb0 = notifBox.get();
        if (nb0 != null) nb0.setTranslationX(0f);
        sliverWanted = false;
        sliverApplied = false;
        if (leftRow != null) { leftRow.setPaddingRelative(rowPadStart, leftRow.getPaddingTop(), leftRow.getPaddingEnd(), leftRow.getPaddingBottom()); leftRow = null; }
        main.removeCallbacks(fadeTask);
        shownKey = null;
        faded = false;
        if (chip != null) {
            ViewParent p = chip.getParent();
            if (p instanceof ViewGroup) ((ViewGroup) p).removeView(chip);
        }
        chip = null;
        stopLiveTracking();
    }

    /** Keep Samsung's left row clear of the overlay chip (only while the chip sits at the left start). */
    /** Room (px) the layout needs after the back button, e.g. for network speed placed right of it. Set by Mover. */
    static volatile float extraPad;

    private void syncRow() {
        LinearLayout row = leftRow;
        if (row == null || chip == null) return;
        Config cfg = owner.config();
        int want = rowPadStart;
        float wantTx = 0;
        // (in Merge / Stack with a live activity the parked spot is ignored, see Mover, so the chip is at the left start then)
        if (chip.getVisibility() == View.VISIBLE && (cfg.plan.mode[ZoneLayout.BACK] == ZoneLayout.STOCK || stackLive) && chip.getWidth() > 0 && !peeking) {
            float d = chip.getResources().getDisplayMetrics().density;
            int w = chip.getWidth() + Math.round((cfg.itemGap >= 0 ? cfg.itemGap : 6) * d);
            // attached to a visible capsule (Merge / Stack): the capsule keeps its own spot, nothing to make room for.
            // Otherwise (also the tiny tab while a live activity is opening): make room with real padding, which Samsung's
            // layout respects - sliding the row looked fine but Samsung resets it during its open-card animation.
            if (!(stackLive && shareLive)) want += w + Math.round(extraPad);
        }
        if (row.getPaddingStart() != want)
            row.setPaddingRelative(want, row.getPaddingTop(), row.getPaddingEnd(), row.getPaddingBottom());
        if (Math.abs(row.getTranslationX() - wantTx) > 0.5f) row.setTranslationX(wantTx);
    }

    /** Match the clock's color / font; called from the status bar pre-draw pass (cheap). */
    private long lastCapCheck;

    void style(TextView clock) {
        if (chip == null) return;
        Config cfg0 = owner.config();
        if (cfg0.backLive != 0 && cfg0.backEnabled) {
            long now = SystemClock.uptimeMillis();
            if (now - lastCapCheck > 250) {
                lastCapCheck = now;
                View lbc = liveBox();
                boolean cap = capsuleShown(lbc);
                if (cap != lastCap) { lastCap = cap; main.post(this::update); }
            }
        }
        syncRow();
        syncSliver();
        syncMerge();
        boolean capsule = (owner.config().backStyle == 1 || mergeWanted || compactWanted) && !faded;
        int color = capsule ? Ui.CHIP_FG : clock.getCurrentTextColor();
        if (faded) color = (color & 0x00FFFFFF) | 0xB0000000;     // dimmed text, no pill
        if (!sliverApplied && chip.getCurrentTextColor() != color) chip.setTextColor(color);
        if (pill != null && !capsule && !faded && pillFor != color) {   // translucent pill in the text colour: reads as a button
            pillFor = color;
            setPill((color & 0x00FFFFFF) | 0x33000000);
            chip.invalidate();
        }
        if (iconDrawDark != null && chip.getCompoundDrawables()[0] != null) {   // keep the chevron readable when the bar turns light / dark or the pill fades
            android.graphics.drawable.Drawable want = chevronFor(clock);
            if (want != shownIcon) { shownIcon = want; chip.setCompoundDrawables(want, null, null, null); }
        }
        float size = clock.getTextSize() * 0.9f * owner.config().backSizePct / 100f;
        if (Math.abs(chip.getTextSize() - size) > 0.5f) chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        if (chip.getTypeface() != clock.getTypeface()) chip.setTypeface(clock.getTypeface());
    }

    private void register(Context ctx, ClassLoader cl) {
        if (listener == null) {
            try {
                Class<?> itf = cl.loadClass("com.android.systemui.shared.system.TaskStackChangeListener");
                Class<?> hub = cl.loadClass("com.android.systemui.shared.system.TaskStackChangeListeners");
                listener = Proxy.newProxyInstance(cl, new Class<?>[]{itf}, (proxy, m, args) -> {
                    String n = m.getName();
                    if (n.equals("onTaskStackChanged") || n.equals("onTaskMovedToFront") || n.equals("onTaskRemoved"))
                        main.post(this::update);
                    if (n.equals("equals")) return proxy == args[0];
                    if (n.equals("hashCode")) return System.identityHashCode(proxy);
                    if (n.equals("toString")) return "SBModsBackChip";
                    return m.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
                Object inst = hub.getField("INSTANCE").get(null);
                hub.getMethod("registerTaskStackListener", itf).invoke(inst, listener);
            } catch (Throwable t) {
                Log.w("back chip: task listener failed: " + t);
            }
        }
        if (!receiverOn) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    screenOff = Intent.ACTION_SCREEN_OFF.equals(i.getAction());
                    if (screenOff) main.removeCallbacks(livePoll);   // fallback poll sleeps with the screen
                    shownKey = null; update();
                }
            }, f, null, main, Context.RECEIVER_NOT_EXPORTED);
            receiverOn = true;
        }
    }

    /** Re-evaluate what to show. Only runs on task changes, unlock, screen off and config changes. */
    @SuppressWarnings("deprecation")
    void update() {
        TextView c = chip;
        if (c == null) return;
        try {
            Config cfg = owner.config();
            String label = null;
            if (cfg.backEnabled) {
                Context ctx = c.getContext();
                KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
                if (km == null || !km.isKeyguardLocked()) label = previousApp(ctx, cfg.backChars);
                // Any live activity counts: music (media session) or whatever Samsung is showing as a capsule right now
                // (timer, stopwatch, call, navigation, delivery ...).
                View lbx = liveBox();
                lastCap = capsuleShown(lbx);
                lastLive = cfg.backLive != 0 && (lastCap || liveActive(ctx));
                if (label != null && cfg.backLive == 2 && lastLive) label = null;   // "Hide": the live activity gets the room
                // Stack / Merge only engage when Samsung really shows a live-activity capsule right now
                shareLive = (cfg.backLive == 3 || cfg.backLive == 4) && lastLive && lastCap;
                stackLive = (cfg.backLive == 3 || cfg.backLive == 4) && lastLive;
                mergeWanted = label != null && cfg.backLive == 4 && shareLive;
                // live activity exists but its capsule isn't up yet: stay tiny (chevron + icon) so the capsule has room to appear
                // A live activity exists but Samsung is not showing its capsule. Any room I take on the left can keep the capsule
                // from coming back (Samsung drops it when space is short and does not re-add it), so the button steps aside
                // completely until the capsule is up again.
                if (label != null && (cfg.backLive == 3 || cfg.backLive == 4) && lastLive && !lastCap && !peeking) label = null;
                compactWanted = false;
                sliverWanted = label != null && cfg.backLive == 3 && shareLive && !peeking;   // "Stack": chip rests behind the live activity
                if (peeking && (!shareLive || cfg.backLive != 3)) { peeking = false; c.setMinWidth(0); c.setGravity(Gravity.CENTER_VERTICAL); }
            }
            syncLiveTracking();
            if (label == null) {
                sliverWanted = false;
                mergeWanted = false;
                compactWanted = false;
                if (c.getVisibility() != View.GONE) c.setVisibility(View.GONE);
                shownKey = null;
                main.removeCallbacks(fadeTask);
                return;
            }
            String key = lastPkg + "/" + targetTask;
            if (!key.equals(shownKey) || c.getVisibility() != View.VISIBLE) { shownKey = key; setFaded(c, false); }
            boolean forced = cfg.backLive == 1 && lastLive;      // "Text only" while a live activity shows
            if (forced) { setFaded(c, true); main.removeCallbacks(fadeTask); forcedFade = true; }
            else if (forcedFade) { forcedFade = false; setFaded(c, false); }
            boolean tiny = mergeWanted || compactWanted;
            boolean capsule = tiny || (cfg.backStyle == 1 && !faded);   // the tab keeps its chevron + icon even after the pill fades
            applyLook(c, capsule);
            if (!forced) scheduleFade(cfg);
            if (tiny) {
                c.setText("");
                float dk = c.getResources().getDisplayMetrics().density * cfg.backSizePct / 100f;
                c.setCompoundDrawablePadding(0);
                // attached to a capsule: the first 11 dp hide under its rounded end; on its own: normal small padding
                c.setPadding(Math.round((mergeWanted ? 11 + 1 : 4) * c.getResources().getDisplayMetrics().density), Math.round(1.5f * dk), Math.round(7 * dk), Math.round(1.5f * dk));
            } else if (capsule) {
                SpannableStringBuilder sb = new SpannableStringBuilder(label);
                c.setText(sb);
            } else {
                SpannableStringBuilder sb = new SpannableStringBuilder("\u2039"); // drawn as a chevron
                sb.setSpan(new dev.gitesh.sbmods.ChevronSpan(), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(label);
                c.setText(sb);
            }
            c.setContentDescription("Back to " + label);
            if (c.getVisibility() != View.VISIBLE) c.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            owner.fail("back chip", t);
        }
    }

    private void scheduleFade(Config cfg) {
        main.removeCallbacks(fadeTask);
        if (cfg.backFadeSec <= 0) { if (faded && chip != null) setFaded(chip, false); return; }
        if (!faded) main.postDelayed(fadeTask, cfg.backFadeSec * 1000L);
    }

    /** After the chosen time: the pill melts away and only dimmed "‹ Name" text stays. */
    private void fadeOut() {
        final TextView c = chip;
        if (c == null || faded || c.getVisibility() != View.VISIBLE) return;
        try {
            if (fadeAnim != null) fadeAnim.cancel();
            final int from = pill == null ? 0 : tintForFade();
            fadeAnim = android.animation.ValueAnimator.ofFloat(1f, 0f);
            fadeAnim.setDuration(900);
            fadeAnim.addUpdateListener(a -> {
                if (pill != null) setPill((from & 0x00FFFFFF) | (Math.round(((from >>> 24)) * (float) a.getAnimatedValue()) << 24));
                c.invalidate();
            });
            fadeAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator an) {
                    if (chip == c) { setFaded(c, true); update(); }
                }
            });
            fadeAnim.start();
        } catch (Throwable t) { owner.fail("back fade", t); }
    }

    private int tintForFade() { return (owner.config().backStyle == 1 || mergeWanted || compactWanted) ? tint : (pillFor & 0x00FFFFFF) | 0x33000000; }

    private void setFaded(TextView c, boolean f) {
        if (fadeAnim != null) { fadeAnim.cancel(); fadeAnim = null; }
        if (faded == f) return;
        faded = f;
        pillFor = 0;
    }

    /**
     * Capsule look, like One UI's live activity chips: the app's icon in a circle, white name,
     * pill tinted with the icon's own colour. Classic look: translucent pill with a chevron.
     */
    private void applyLook(TextView c, boolean capsule) {
        Context ctx = c.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        float k = owner.config().backSizePct / 100f;   // the whole pill scales with the text size
        float dk = d * k;
        if (!capsule || lastPkg == null) {
            c.setCompoundDrawables(null, null, null, null);
            if (faded) {
                c.setPadding(Math.round(4 * d), Math.round(1 * d), Math.round(4 * d), Math.round(1 * d));
                if (pill != null) { setPill(0); c.invalidate(); }
            } else {
                c.setPadding(Math.round(7 * d), Math.round(1 * d), Math.round(9 * d), Math.round(1 * d));
                pillFor = 0; // style() repaints the translucent pill
            }
            return;
        }
        String iconKey = lastPkg + "@" + owner.config().backSizePct;
        if (!iconKey.equals(iconPkg)) {
            try {
                int px = Math.max(8, Math.round(14 * dk));   // the app icon, in a circle
                int chev = Math.max(4, Math.round(7 * dk));                // room for the back chevron in front of it
                android.graphics.drawable.Drawable ic = ctx.getPackageManager().getApplicationIcon(lastPkg);
                android.graphics.Bitmap icon = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas ic2 = new android.graphics.Canvas(icon);
                android.graphics.Path clip = new android.graphics.Path();
                clip.addCircle(px / 2f, px / 2f, px / 2f, android.graphics.Path.Direction.CW);
                ic2.clipPath(clip);
                ic.setBounds(0, 0, px, px);
                ic.draw(ic2);
                iconDraw = chevIcon(ctx, icon, chev, px, dk, Ui.CHIP_FG);       // white chevron (on a coloured / dark background)
                iconDrawDark = chevIcon(ctx, icon, chev, px, dk, Ui.CHIP_INK);   // dark chevron (on a light background)
                tint = tintOf(icon);
                iconPkg = iconKey;
            } catch (Throwable t) {
                iconDraw = null;
                tint = Ui.CHIP_TINT;
                iconPkg = iconKey;
            }
        }
        shownIcon = chevronFor(c);
        c.setCompoundDrawables(shownIcon, null, null, null);
        c.setCompoundDrawablePadding(Math.round(3 * dk));
        // once the pill has faded there is nothing to pad against: close the empty space on the right (and a little on the left)
        c.setPadding(Math.round((faded ? 1.5f : 4) * dk), Math.round(1.5f * dk), Math.round((faded ? 1.5f : 10) * dk), Math.round(1.5f * dk));
        if (pill != null) { setPill(faded ? 0 : tint); c.invalidate(); }
    }

    private static android.graphics.drawable.Drawable chevIcon(Context ctx, android.graphics.Bitmap icon, int chev, int px, float dk, int color) {
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(chev + px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bm);
        cv.drawBitmap(icon, chev, 0, null);
        android.graphics.Paint sp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        sp.setStyle(android.graphics.Paint.Style.STROKE);
        sp.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        sp.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        sp.setStrokeWidth(Math.max(1f, 1.7f * dk));
        sp.setColor(color);
        android.graphics.Path ch = new android.graphics.Path();
        float cx = 4.2f * dk, hh = 3.4f * dk;          // chevron "\u2039"
        ch.moveTo(cx, px / 2f - hh); ch.lineTo(cx - 2.4f * dk, px / 2f); ch.lineTo(cx, px / 2f + hh);
        cv.drawPath(ch, sp);
        android.graphics.drawable.BitmapDrawable bd = new android.graphics.drawable.BitmapDrawable(ctx.getResources(), bm);
        bd.setBounds(0, 0, chev + px, px);
        return bd;
    }

    private static float luma(int c) {
        return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) / 255f;
    }

    /** White or dark chevron, whichever stands out: against the pill while it is showing, against the status bar once the pill has faded. */
    private android.graphics.drawable.Drawable chevronFor(TextView clock) {
        if (iconDrawDark == null) return iconDraw;
        boolean pillShown = !faded && pill != null && tint != 0;
        boolean lightBehind = pillShown ? luma(tint) > 0.62f : luma(clock.getCurrentTextColor()) < 0.5f;   // dark status bar text = light bar
        return lightBehind ? iconDrawDark : iconDraw;
    }

    /** The icon's main colour, darkened a little so white text stays readable on it. */
    private static int tintOf(android.graphics.Bitmap bm) {
        float r = 0, g = 0, b = 0, wsum = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < bm.getHeight(); y += 2) {
            for (int x = 0; x < bm.getWidth(); x += 2) {
                int px = bm.getPixel(x, y);
                if ((px >>> 24) < 200) continue;
                android.graphics.Color.colorToHSV(px, hsv);
                float w = 0.05f + hsv[1] * hsv[2];       // colourful pixels count more than white / grey ones
                r += android.graphics.Color.red(px) * w; g += android.graphics.Color.green(px) * w; b += android.graphics.Color.blue(px) * w;
                wsum += w;
            }
        }
        if (wsum <= 0) return Ui.CHIP_TINT;
        android.graphics.Color.colorToHSV(android.graphics.Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (b / wsum)), hsv);
        hsv[1] = Math.max(hsv[1], hsv[1] < 0.12f ? hsv[1] : 0.55f);
        hsv[2] = Math.min(Math.max(hsv[2], 0.30f), 0.55f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** Label of the app used before the current one, or null if it shouldn't show. */
    @SuppressWarnings("deprecation")
    private String previousApp(Context ctx, int maxChars) {
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningTaskInfo> running = am.getRunningTasks(1);
        if (running == null || running.isEmpty()) return null;
        ActivityManager.RunningTaskInfo top = running.get(0);
        String topPkg = pkgOf(top.topActivity, top.baseIntent);
        if (topPkg == null || topPkg.equals(home(ctx)) || topPkg.equals(Config.SYSUI)) return null;

        List<ActivityManager.RecentTaskInfo> recents = am.getRecentTasks(6, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
        if (recents == null) return null;
        for (ActivityManager.RecentTaskInfo r : recents) {
            if (r.taskId == top.taskId) continue;
            String pkg = pkgOf(r.topActivity, r.baseIntent);
            if (pkg == null || pkg.equals(topPkg) || pkg.equals(home(ctx)) || pkg.equals(Config.SYSUI)) continue;
            targetTask = r.taskId;
            lastPkg = pkg;
            String name = appLabel(ctx, pkg);
            if (name == null) return null;
            name = name.trim();
            return name.length() > maxChars ? name.substring(0, maxChars).trim() : name;
        }
        return null;
    }

    private static String pkgOf(ComponentName top, Intent base) {
        if (top != null) return top.getPackageName();
        if (base != null && base.getComponent() != null) return base.getComponent().getPackageName();
        return null;
    }

    private String home(Context ctx) {
        if (homePkg == null) {
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            homePkg = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "";
        }
        return homePkg;
    }

    private static String appLabel(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    View view() { return chip; }

    /** Bring the previous task back, the same way Recents does. */
    void switchBack(View v) {
        int task = targetTask;
        if (task < 0) return;
        try {
            if (owner.config().tapHaptic) v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            try {
                Class<?> atm = Class.forName("android.app.ActivityTaskManager");
                Object service = atm.getMethod("getService").invoke(null);
                service.getClass().getMethod("startActivityFromRecents", int.class, Bundle.class)
                        .invoke(service, task, null);
            } catch (Throwable t) {
                v.getContext().getSystemService(ActivityManager.class).moveTaskToFront(task, 0);
            }
        } catch (Throwable t) {
            owner.fail("back switch", t);
        }
    }
}
