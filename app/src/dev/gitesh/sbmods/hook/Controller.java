// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Date;
import java.util.Locale;

import dev.gitesh.sbmods.ClockFormat;
import dev.gitesh.sbmods.Config;
import dev.gitesh.sbmods.items.Item;
import dev.gitesh.sbmods.items.Items;

/**
 * Lives inside SystemUI. Adds one TextView next to the status bar clock (temperature and
 * future items) and can optionally reformat the clock text itself.
 *
 * Stability rules:
 *  - every entry point is wrapped; any exception is counted, never thrown into SystemUI;
 *  - too many runtime errors -> the mod removes itself (and restores the clock) until restart;
 *  - crash guard keeps the mod off in two cases: (a) SystemUI restarted 3x, each time within 90 s of loading the mod (fast loop), or
 *    (b) SystemUI started 3x within 10 minutes without the app asking for it (slow loop: a crash every few minutes never reaches 90 s).
 *    The app's own restart button and "Turn the mod back on" clear both counters, so one restart or one reboot never trips it.
 *    Both re-arm themselves: (a) after 10 min without a start, (b) as soon as the oldest of the 3 starts is 10 min old;
 *  - sensors are read on a background thread; polling stops while the screen is off;
 *  - Samsung's clock is never replaced or removed, only its text is re-formatted, and the
 *    original text comes back as soon as the custom clock is switched off.
 */
public final class Controller {
    private static final String TAG_VIEW = "sbmods_label";
    private static final int MAX_ERRORS = 25;
    private static final int GUARD_LIMIT = 3;
    private static final long GUARD_STABLE_MS = 90_000;
    private static final long GUARD_FORGET_MS = 10 * 60_000;   // a tripped guard re-arms itself after this long without a restart
    private static final long ERROR_WINDOW_MS = 60_000;         // MAX_ERRORS only count when they come in a burst
    private static final String VERSION = "3.18.0";

    private static Controller sInstance;

    static Controller get() {
        if (sInstance == null) sInstance = new Controller();
        return sInstance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Handler bg;
    private Context ctx;
    private SharedPreferences prefs;
    private android.widget.Toast lastToast;
    private volatile Config cfg = new Config();
    private final Sensors sensors = new Sensors();

    private boolean initDone, disabled, guardTripped;
    private int clockId, rightContainerId, middleContainerId, batteryId, systemIconsId;
    private long lastBatteryCheck;

    private final TapActions batteryTaps = new TapActions(this, "battery", 0);
    private final TapActions clockTaps = new TapActions(this, "clock", 1);
    private final TapActions labelTaps = new TapActions(this, "label", 2);
    /** Front-camera punch hole: no view of its own, hit-tested against the display cutout. */
    private final TapActions cutoutTaps = new TapActions(this, "cutout", 3);
    private final TapActions wifiTaps = new TapActions(this, "wifi", 4);
    private final TapActions mobileTaps = new TapActions(this, "mobile", 5);
    private final Mover mover = new Mover();
    private final BackChip backChip = new BackChip(this);
    private final TipTicker tips = new TipTicker(this);
    /** Root of the status bar window; Entry forwards its touches to onStatusBarTouch. */
    static volatile View statusRoot;
    /** True once the mod switched itself off (crash guard or error storm): every hook of ours must then leave Samsung's behaviour alone. */
    static volatile boolean off;

    /** Called when the mod switches itself off: hooks that read settings go quiet, and the keep-open limit is handed back to Samsung (0 = not ours). */
    private void markOff() {
        off = true;
        NotifExpand.off();
        try { android.provider.Settings.Global.putInt(ctx.getContentResolver(), Config.LOCK_SETTING, 0); } catch (Throwable ignored) {}
    }

    Config config() { return cfg; }

    private int errors, replaceCount, windowErrors;
    private long errWindowStart;
    private long replaceWindow;
    private String lastError = "";
    private volatile CharSequence lastText = "";
    private boolean screenOn = true, polling;

    private WeakReference<TextView> clockRef = new WeakReference<>(null);
    private TextView label, dateView;
    private ViewTreeObserver observer;
    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        try { syncWithClock(); } catch (Throwable t) { fail("predraw", t); }
        return true;
    };

    // custom clock state
    private boolean settingClock;          // re-entrancy guard
    private CharSequence stockText;        // Samsung's latest own text
    private String stockFeatures;          // clock font features before we touched them
    private boolean clockTouched;

    // ------------------------------------------------------------------ entry

    void onTextViewAttached(TextView tv) {
        if (disabled) return;
        if (!initDone) init(tv.getContext());
        if (disabled || guardTripped || clockId == 0) return;
        if (tv.getId() != clockId || TAG_VIEW.equals(tv.getTag())) return;
        if (!tv.getClass().getName().contains("Clock")) return;
        if (!(tv.getParent() instanceof LinearLayout)) return;
        // Samsung has one clock per side (left / middle / right container); only one is visible.
        for (int i = clockCandidates.size() - 1; i >= 0; i--)
            if (clockCandidates.get(i).get() == null) clockCandidates.remove(i);
        clockCandidates.add(new WeakReference<>(tv));
        TextView cur = clockRef.get();
        if (cur == null || !cur.isAttachedToWindow() || !cur.isShown()) attachTo(tv);
    }

    private final java.util.List<WeakReference<TextView>> clockCandidates = new java.util.ArrayList<>();
    private long lastClockCheck;

    /** If the clock we decorate is hidden but another status bar clock is showing, move to it. */
    private boolean followVisibleClock(TextView clock) {
        if (clock.isShown()) return false;
        long now = SystemClock.uptimeMillis();
        if (now - lastClockCheck < 500) return false;
        lastClockCheck = now;
        for (WeakReference<TextView> w : clockCandidates) {
            TextView c = w.get();
            if (c != null && c != clock && c.isShown() && c.getParent() instanceof LinearLayout) {
                Log.i("clock moved to " + idName(((View) c.getParent()).getId()) + ", following it");
                main.post(() -> { try { attachTo(c); } catch (Throwable t) { fail("follow", t); } });
                return true;
            }
        }
        return false;
    }

    private void init(Context any) {
        initDone = true;
        ctx = any.getApplicationContext() != null ? any.getApplicationContext() : any;
        prefs = ctx.createDeviceProtectedStorageContext()
                .getSharedPreferences("sbmods", Context.MODE_PRIVATE);

        registerControlReceiver();

        // old debugging notes from earlier test builds
        try { android.content.SharedPreferences.Editor de = prefs.edit(); for (String k : prefs.getAll().keySet()) if (k.startsWith("dbg_")) de.remove(k); de.apply(); } catch (Throwable ignored) {}
        // an app update (a new version, or the same one installed again while developing) is not a crash: clear both guard counters once. One read, at start only.
        try {
            String build = VERSION + ":" + ctx.getPackageManager().getPackageInfo(Config.PKG, 0).lastUpdateTime;
            if (!build.equals(prefs.getString("guard_build", ""))) prefs.edit().putInt("guard_starts", 0).putString("guard_slow", "").putString("guard_build", build).apply();
        } catch (Throwable ignored) {}
        // the stamps are time since boot: after a reboot the old ones mean nothing (and could count as recent starts), so a new boot starts the count fresh
        try {
            int boot = android.provider.Settings.Global.getInt(ctx.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
            if (boot != prefs.getInt("guard_boot", -2)) prefs.edit().putInt("guard_starts", 0).putString("guard_slow", "").putLong("guard_last", 0).putInt("guard_boot", boot).apply();
        } catch (Throwable ignored) {}

        int starts = prefs.getInt("guard_starts", 0);
        long lastStart = prefs.getLong("guard_last", 0);
        long nowMs = SystemClock.elapsedRealtime();   // not wall clock: a network time fix must not reset the count. Restarts at 0 on reboot, and a larger stored value (reboot, or an old wall-clock one) is forgotten below
        // Self-healing: the guard only protects against a crash LOOP. If the last start was a while ago, the loop is over: forget it.
        if (starts > 0 && (nowMs - lastStart > GUARD_FORGET_MS || nowMs < lastStart)) starts = 0;
        if (starts >= GUARD_LIMIT) {
            guardTripped = true; markOff();
            Log.w("crash guard tripped (" + starts + " unstable starts) - mod stays off (tries again after " + GUARD_FORGET_MS / 60000 + " min)");
            gput("sbmods_alive", 0L); gput("sbmods_alive_boot", -1L);
            Entry.unhook();
            return;
        }
        // slow loop: remember the start times (ms, last 10 min); 2 earlier ones plus this start = 3 unasked starts in 10 min. A tripped start is not recorded.
        StringBuilder slow = new StringBuilder(); int slowN = 0;
        for (String t : prefs.getString("guard_slow", "").split(",")) {
            try { long v = Long.parseLong(t); if (v <= nowMs && nowMs - v <= GUARD_FORGET_MS) { slow.append(v).append(','); slowN++; } } catch (NumberFormatException ignored) {}
        }
        prefs.edit().putString("guard_slow", slow.toString()).apply();
        if (slowN >= GUARD_LIMIT - 1) {
            guardTripped = true; markOff();
            Log.w("crash guard tripped (" + (slowN + 1) + " starts in " + GUARD_FORGET_MS / 60000 + " min) - mod stays off until the oldest is " + GUARD_FORGET_MS / 60000 + " min old");
            gput("sbmods_alive", 0L); gput("sbmods_alive_boot", -1L);
            Entry.unhook();
            return;
        }
        prefs.edit().putInt("guard_starts", starts + 1).putLong("guard_last", nowMs).putString("guard_slow", slow.toString() + nowMs).commit();
        main.postDelayed(() -> {
            try { prefs.edit().putInt("guard_starts", 0).apply(); } catch (Throwable ignored) {}
        }, GUARD_STABLE_MS);

        cfg = Config.load(prefs);
        NotifExpand.apply(cfg);
        publishLockMax(cfg);
        gput("sbmods_alive", System.currentTimeMillis());
        gput("sbmods_alive_boot", android.provider.Settings.Global.getInt(ctx.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1));   // which boot: immune to clock jumps
        clockId = ctx.getResources().getIdentifier("clock", "id", Config.SYSUI);
        rightContainerId = ctx.getResources().getIdentifier("right_clock_container", "id", Config.SYSUI);
        middleContainerId = ctx.getResources().getIdentifier("middle_clock_container", "id", Config.SYSUI);
        batteryId = ctx.getResources().getIdentifier("battery", "id", Config.SYSUI);
        systemIconsId = ctx.getResources().getIdentifier("system_icons", "id", Config.SYSUI);
        Log.missId(clockId, "clock", "every status bar change");
        Log.missId(rightContainerId, "right_clock_container", "putting the clock on the right");
        Log.missId(middleContainerId, "middle_clock_container", "putting the clock in the middle");
        Log.missId(batteryId, "battery", "battery taps");
        Log.missId(systemIconsId, "system_icons", "moving the status icons");
        if (clockId == 0) {
            Log.w("status bar clock id not found - doing nothing");
            Entry.unhook();
            return;
        }

        HandlerThread ht = new HandlerThread("SBMods");
        ht.start();
        bg = new Handler(ht.getLooper());

        try {
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            screenOn = pm == null || pm.isInteractive();
        } catch (Throwable ignored) {}
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_LOCALE_CHANGED);
        ctx.registerReceiver(systemReceiver, f, null, main, Context.RECEIVER_NOT_EXPORTED);
        Smart.apply(ctx, prefs, cfg.smartOn);
        Log.i("initialised v" + VERSION + ", clockId=" + Integer.toHexString(clockId));
    }

    // -------------------------------------------------------------- view work

    /** The launcher and system server read the limit from here (SystemUI may write secure settings, they cannot see our prefs). */
    private void publishLockMax(Config c) {
        try { android.provider.Settings.Global.putInt(ctx.getContentResolver(), Config.LOCK_SETTING, c.lockMax); }
        catch (Throwable t) { Log.w("lockMax publish failed: " + t); }
    }

    private void attachTo(TextView clock) {
        if (clockRef.get() == clock && label != null && label.getParent() == clock.getParent()) return;
        detach();
        clockRef = new WeakReference<>(clock);

        label = new TextView(clock.getContext());
        label.setTag(TAG_VIEW);
        label.setSingleLine(true);
        label.setIncludeFontPadding(clock.getIncludeFontPadding());
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        label.setVisibility(View.GONE);
        dateView = new TextView(clock.getContext());
        dateView.setTag("sbmods_date");
        dateView.setSingleLine(true);
        dateView.setIncludeFontPadding(clock.getIncludeFontPadding());
        dateView.setGravity(Gravity.CENTER_VERTICAL);
        dateView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        dateView.setVisibility(View.GONE);
        place();

        stockText = clock.getText() == null ? null : clock.getText().toString();
        clock.addTextChangedListener(clockWatcher);

        observer = clock.getViewTreeObserver();
        observer.addOnPreDrawListener(preDraw);
        clock.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) {
                v.removeOnAttachStateChangeListener(this);
                // Samsung sometimes detaches the clock only to put it straight back (moving sides):
                // give it a moment and only let go if it really left the screen.
                main.postDelayed(() -> {
                    try {
                        if (clockRef.get() != v) return;
                        detach();
                        if (v.isShown()) attachTo((TextView) v);
                    } catch (Throwable t) { fail("detach", t); }
                }, 400);
            }
        });
        Log.i("attached next to " + clock.getClass().getName());
        bindTaps(true);
        statusRoot = clock.getRootView();
        backChip.attach(clock);
        main.post(shiftTick);
        startPolling();
        restartClockTick();
    }

    private void detach() {
        TextView clock = clockRef.get();
        if (clock != null) {
            try { clock.removeTextChangedListener(clockWatcher); } catch (Throwable ignored) {}
        }
        main.removeCallbacks(clockTick);
        if (pendingBackTap != null) main.removeCallbacks(pendingBackTap);   // must not fire on a stale view
        pendingBackTap = null;
        cancelStack();
        batteryTaps.unbind();
        clockTaps.unbind();
        labelTaps.unbind();
        cutoutTaps.unbind();
        wifiTaps.unbind();
        mobileTaps.unbind();
        backChip.detach();
        tips.stop();
        mover.releaseAll();
        statusRoot = null;
        try {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(preDraw);
        } catch (Throwable ignored) {}
        observer = null;
        if (label != null) {
            ViewParent p = label.getParent();
            if (p instanceof ViewGroup) ((ViewGroup) p).removeView(label);
        }
        if (dateView != null) {
            ViewParent dp = dateView.getParent();
            if (dp instanceof ViewGroup) ((ViewGroup) dp).removeView(dateView);
        }
        label = null;
        dateView = null;
        clockTouched = false;
        clockRef = new WeakReference<>(null);
    }

    /** Clock on the right side (Good Lock / QuickStar) -> number goes on its left in Auto mode. */
    private boolean wantBefore(TextView clock) {
        if (cfg.labelPos == Config.POS_BEFORE) return true;
        if (cfg.labelPos == Config.POS_AFTER) return false;
        ViewParent p = clock.getParent();
        return rightContainerId != 0 && p instanceof View && ((View) p).getId() == rightContainerId;
    }

    /** Put the label right after (or before) the clock in the clock's own container. */
    private void place() {
        TextView clock = clockRef.get();
        if (clock == null || label == null) return;
        if (!(clock.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) clock.getParent();

        if (label.getParent() instanceof ViewGroup) ((ViewGroup) label.getParent()).removeView(label);
        int idx = parent.indexOfChild(clock);
        if (idx < 0) return;
        boolean before = wantBefore(clock);
        if (!before) idx++;

        int gap = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, cfg.gapDp,
                clock.getResources().getDisplayMetrics()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        if (before) lp.setMarginEnd(gap); else lp.setMarginStart(gap);
        parent.addView(label, idx, lp);
        if (dateView != null) {
            if (dateView.getParent() instanceof ViewGroup) ((ViewGroup) dateView.getParent()).removeView(dateView);
            int ci = parent.indexOfChild(clock);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            dlp.gravity = Gravity.CENTER_VERTICAL;
            if (cfg.dateAfterTime) dlp.setMarginStart(gap); else dlp.setMarginEnd(gap);
            parent.addView(dateView, cfg.dateAfterTime ? ci + 1 : ci, dlp);
        }
        applyStyle(clock, true);
    }

    private boolean isPlacedCorrectly(TextView clock) {
        ViewParent p = label.getParent();
        if (p != clock.getParent() || !(p instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) p;
        int ci = g.indexOfChild(clock), li = g.indexOfChild(label);
        if (dateView != null) {
            if (dateView.getParent() != g) return false;
            int di = g.indexOfChild(dateView);
            if (cfg.dateAfterTime ? di != ci + 1 : di != ci - 1) return false;
            // the temperature sits on the outer side of the date
            if (di < ci) { if (li > di && li < ci) return false; } else if (li > ci && li < di) return false;
            if (wantBefore(clock)) return li == (di < ci ? di - 1 : ci - 1);
            return li == (di > ci ? di + 1 : ci + 1);
        }
        return wantBefore(clock) ? li == ci - 1 : li == ci + 1;
    }

    /** Runs before every status bar frame: cheap comparisons, changes only on difference. */
    private void syncWithClock() {
        TextView clock = clockRef.get();
        if (clock == null || label == null) return;
        if (tickIdle && clock.isShown()) { tickIdle = false; restartClockTick(); }   // bar is drawing again: back to the 1 s tick (flag cleared first, so no loop)
        if (followVisibleClock(clock)) return;
        if (!(clock.getParent() instanceof LinearLayout)) {
            if (label.getVisibility() != View.GONE) label.setVisibility(View.GONE);
            return; // clock moved somewhere we don't support: stay hidden, do nothing
        }
        if (!isPlacedCorrectly(clock)) {
            long now = SystemClock.uptimeMillis();
            if (now - replaceWindow > 10_000) { replaceWindow = now; replaceCount = 0; }
            // >30 re-placements in 10 s = something keeps moving it; count as error, back off
            if (++replaceCount > 30) { fail("place-loop", new IllegalStateException("layout fights back")); return; }
            main.post(() -> { try { place(); } catch (Throwable t) { fail("place", t); } });
            return;
        }
        applyStyle(clock, false);
        backChip.style(clock);
        safeMove(cfg, statusRoot, clock);
        long now = SystemClock.uptimeMillis();
        if (now - lastBatteryCheck > 2000) { lastBatteryCheck = now; bindTaps(false); }
    }

    // ------------------------------------------------------------ touch router

    private TapActions touchItem;
    private boolean touchBack, touchStack, touchBg;
    private long lastBgTap;
    private float lastBgX;
    private Runnable stackPress, pendingBackTap;
    private float downX, downY;
    private final int[] loc = new int[2];

    /**
     * Sees every touch on the status bar window but never consumes it, so pulling the panel down
     * always works. A touch that starts on an item and doesn't move becomes a tap / long-press.
     */
    void onStatusBarTouch(android.view.MotionEvent e) {
        if (disabled) return;
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                downX = e.getRawX(); downY = e.getRawY();
                touchItem = null;
                touchBack = backChip.hitBack(downX, downY);
                if (!touchBack) {
                    Config c = cfg;
                    for (TapActions t : new TapActions[]{batteryTaps, clockTaps, labelTaps, wifiTaps, mobileTaps})
                        if (t.wants(c) && hit(t.view(), downX, downY)) { touchItem = t; break; }
                    if (touchItem == null && cutoutTaps.wants(c) && statusRoot != null
                            && hitCutout(statusRoot, downX, downY)) {
                        cutoutTaps.bind(statusRoot, c);
                        touchItem = cutoutTaps;
                    }
                }
                if (touchItem != null) touchItem.down();
                // "Stack": long-press on the live activity swaps in the back button
                touchStack = false;
                touchBg = false;
                if (touchItem == null && !touchBack && cfg.backLive == 3 && backChip.stackActive()) {
                    final View lb = liveBox();
                    if (lb != null && hit(lb, downX, downY)) {
                        touchStack = true;
                        final long downTime = e.getDownTime();
                        stackPress = () -> {
                            stackPress = null;
                            touchStack = false;
                            try { backChip.peek(lb); } catch (Throwable t) { fail("stack peek", t); }
                            // The live activity must not also "click" when the finger lifts: cancel its touch.
                            try {
                                View r0 = statusRoot;
                                if (r0 != null) {
                                    android.view.MotionEvent cancel = android.view.MotionEvent.obtain(downTime,
                                            android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_CANCEL, downX, downY, 0);
                                    r0.dispatchTouchEvent(cancel);
                                    cancel.recycle();
                                }
                            } catch (Throwable t) { fail("stack cancel", t); }
                        };
                        main.postDelayed(stackPress, android.view.ViewConfiguration.getLongPressTimeout());
                    }
                }
                touchBg = cfg.dtSleep && touchItem == null && !touchBack && !touchStack;
                break;
            case android.view.MotionEvent.ACTION_MOVE: {
                if (touchItem == null && !touchBack && !touchStack && !touchBg) break;
                View r = statusRoot;
                int slop = r == null ? 24 : android.view.ViewConfiguration.get(r.getContext()).getScaledTouchSlop();
                // "Stack": a sideways flick on the live activity (or on the swapped-in back button) swaps them, like One UI's stacked cards
                if (touchStack || (touchBack && backChip.isPeeking())) {
                    float fx = e.getRawX() - downX, fy = e.getRawY() - downY;
                    if (Math.abs(fx) > 2 * slop && Math.abs(fx) > Math.abs(fy)) {
                        final View lb2 = liveBox();
                        cancelStack();
                        touchBack = false;
                        if (backChip.isPeeking()) backChip.endPeek(); else if (lb2 != null) backChip.peek(lb2);
                        try {
                            View r0 = statusRoot;
                            if (r0 != null) {
                                android.view.MotionEvent cancel = android.view.MotionEvent.obtain(e.getDownTime(),
                                        android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_CANCEL, e.getRawX(), e.getRawY(), 0);
                                r0.dispatchTouchEvent(cancel);
                                cancel.recycle();
                            }
                        } catch (Throwable t) { fail("stack flick", t); }
                        break;
                    }
                }
                if (Math.abs(e.getRawX() - downX) > slop || Math.abs(e.getRawY() - downY) > slop) { touchBg = false; endTouch(false); }
                break;
            }
            case android.view.MotionEvent.ACTION_UP:
                if (touchBg && e.getEventTime() - e.getDownTime() < 350) {
                    long now = e.getEventTime();
                    View r1 = statusRoot;
                    float slop2 = r1 == null ? 60 : 3 * android.view.ViewConfiguration.get(r1.getContext()).getScaledTouchSlop();
                    if (now - lastBgTap < android.view.ViewConfiguration.getDoubleTapTimeout() && Math.abs(e.getRawX() - lastBgX) < slop2 * 3) {
                        lastBgTap = 0;
                        if (r1 != null) {
                            try {
                                if (cfg.tapHaptic) r1.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                                TapActions.perform(r1.getContext(), Config.ACT_LOCK, null);
                            } catch (Throwable t) { fail("double-tap sleep", t); }
                        }
                    } else { lastBgTap = now; lastBgX = e.getRawX(); }
                }
                touchBg = false;
                endTouch(true);
                break;
            case android.view.MotionEvent.ACTION_CANCEL:
                endTouch(false);
                break;
            default:
                break;
        }
    }

    private View liveBox() {
        View r = statusRoot;
        if (r == null) return null;
        int id = r.getResources().getIdentifier("samsung_ongoing_activity_chip_container", "id", Config.SYSUI);
        Log.missId(id, "samsung_ongoing_activity_chip_container", "the back pill beside live activities");
        return id == 0 ? null : r.findViewById(id);
    }

    private void cancelStack() {
        if (stackPress != null) main.removeCallbacks(stackPress);
        stackPress = null;
        touchStack = false;
    }

    private void endTouch(boolean tap) {
        cancelStack();
        TapActions t = touchItem;
        boolean back = touchBack;
        touchItem = null;
        touchBack = false;
        if (t != null) { if (tap) t.up(); else t.cancel(); }
        if (back && tap) {
            final View v = backChip.view();
            if (v != null) {
                if (backChip.isPeeking()) {
                    // while it is swapped in: one tap goes back, a double-tap returns to the live activity
                    if (pendingBackTap != null) { main.removeCallbacks(pendingBackTap); pendingBackTap = null; backChip.endPeek(); }
                    else {
                        pendingBackTap = () -> { pendingBackTap = null; try { backChip.switchBack(v); backChip.endPeek(); } catch (Throwable th) { fail("back tap", th); } };
                        main.postDelayed(pendingBackTap, android.view.ViewConfiguration.getDoubleTapTimeout());
                    }
                } else backChip.switchBack(v);
            }
        }
    }

    /** Is the screen point on (or just around) the front camera cutout? */
    private boolean hitCutout(View root, float x, float y) {
        try {
            android.view.WindowInsets wi = root.getRootWindowInsets();
            android.view.DisplayCutout dc = wi == null ? null : wi.getDisplayCutout();
            if (dc == null) return false;
            float slack = 14 * root.getResources().getDisplayMetrics().density;
            for (android.graphics.Rect r : dc.getBoundingRects()) {
                if (r.top > 0 || r.isEmpty()) continue; // top-edge cutouts only
                if (x >= r.left - slack && x <= r.right + slack && y <= r.bottom + slack) return true;
            }
        } catch (Throwable t) {
            fail("cutout", t);
        }
        return false;
    }

    /** Is the screen point on this (visible) view? A few dp of slack makes small items easier. */
    private boolean hit(View v, float x, float y) {
        if (v == null || !v.isShown() || v.getWidth() == 0) return false;
        v.getLocationOnScreen(loc);
        float slack = 4 * v.getResources().getDisplayMetrics().density;
        return x >= loc[0] - slack && x <= loc[0] + v.getWidth() + slack
                && y >= loc[1] - slack && y <= loc[1] + v.getHeight() + slack;
    }

    /** Finds Samsung's Wi-Fi and mobile signal icons (the drawn glyphs, so taps next to them don't count). */
    private void bindNet(View root) {
        int wid = root.getResources().getIdentifier("wifi_signal", "id", Config.SYSUI);
        int mid = root.getResources().getIdentifier("mobile_signal", "id", Config.SYSUI);
        Log.missId(wid, "wifi_signal", "taps on the Wi-Fi icon"); Log.missId(mid, "mobile_signal", "merging the Wi-Fi and mobile icons");
        View w = wid == 0 ? null : root.findViewById(wid), m = mid == 0 ? null : root.findViewById(mid);
        if (w != null) wifiTaps.bind(w, cfg); else wifiTaps.unbind();
        if (m != null && !m.isShown()) m = null;
        if (m != null && cfg.mergeNet) { View p = (View) m.getParent(); while (p != null && p.getParent() instanceof View && ((View) p.getParent()).getId() != root.getResources().getIdentifier("statusIcons", "id", Config.SYSUI)) p = (View) p.getParent(); if (p != null && p.getAlpha() == 0f) m = null; }
        if (m != null) mobileTaps.bind(m, cfg); else mobileTaps.unbind();
    }

    /** (Re)binds tap actions; finds the battery icon in the same status bar window as the clock. */
    private void bindTaps(boolean force) {
        TextView clock = clockRef.get();
        if (clock == null) return;
        clockTaps.bind(clock, cfg);
        if (label != null) labelTaps.bind(label, cfg);
        bindNet(clock.getRootView());
        View bat = batteryTaps.view();
        if (force || bat == null || !bat.isShown()) bat = findBattery(clock.getRootView());
        batteryTaps.bind(bat, cfg);
    }

    /**
     * One UI 9 draws the battery with Jetpack Compose: the old BatteryMeterView (id/battery) is a
     * hidden leftover. Use it only if it is really visible, else the visible ComposeView inside
     * id/system_icons (that holds the Compose battery).
     */
    private View findBattery(View root) {
        View b = batteryId == 0 ? null : root.findViewById(batteryId);
        if (b != null && b.isShown() && b.getWidth() > 0) return b;
        View si = systemIconsId == 0 ? null : root.findViewById(systemIconsId);
        if (si instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) si;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View c = g.getChildAt(i);
                if (c.isShown() && c.getWidth() > 0 && c.getClass().getName().contains("ComposeView")) return c;
            }
        }
        return null;
    }

    private void applyStyle(TextView clock, boolean force) {
        int color = clock.getCurrentTextColor();
        if (force || label.getCurrentTextColor() != color) label.setTextColor(color);

        float size = clock.getTextSize() * cfg.sizePct / 100f;
        if (force || Math.abs(label.getTextSize() - size) > 0.5f)
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);

        if (force || label.getTypeface() != clock.getTypeface()) label.setTypeface(clock.getTypeface());
        if (force || label.getLetterSpacing() != clock.getLetterSpacing())
            label.setLetterSpacing(clock.getLetterSpacing());

        boolean show = clock.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(lastText);
        int vis = show ? View.VISIBLE : View.GONE;
        if (label.getVisibility() != vis) label.setVisibility(vis);

        if (dateView != null) {
            if (force || dateView.getCurrentTextColor() != color) dateView.setTextColor(color);
            float dsize = clock.getTextSize();
            if (force || Math.abs(dateView.getTextSize() - dsize) > 0.5f) dateView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dsize);
            if (force || dateView.getTypeface() != clock.getTypeface()) dateView.setTypeface(clock.getTypeface());
            if (force || dateView.getLetterSpacing() != clock.getLetterSpacing()) dateView.setLetterSpacing(clock.getLetterSpacing());
            boolean dshow = clock.getVisibility() == View.VISIBLE && cfg.dateSeparate && !TextUtils.isEmpty(dateView.getText());
            int dv = dshow ? View.VISIBLE : View.GONE;
            if (dateView.getVisibility() != dv) dateView.setVisibility(dv);
        }
    }

    // ----------------------------------------------------------- custom clock

    private CharSequence customClockText() {
        Config c = cfg;
        if (c.clockMode == Config.CLOCK_STOCK) return null;
        return ClockFormat.format(c, DateFormat.is24HourFormat(ctx), new Date(), Locale.getDefault());
    }

    /** Samsung sets its text -> we remember it and, if custom clock is on, swap in ours. */
    private final TextWatcher clockWatcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable s) {
            if (settingClock || disabled) return;
            try {
                stockText = s.toString();
                CharSequence mine = customClockText();
                if (mine == null) return;
                settingClock = true;
                try { s.replace(0, s.length(), mine); } finally { settingClock = false; }
                clockTouched = true;
            } catch (Throwable t) { fail("clock-watch", t); }
        }
    };

    /** Writes our clock text now, or restores Samsung's when custom clock is off. */
    private void refreshClock() {
        TextView clock = clockRef.get();
        if (clock == null) return;
        CharSequence mine = disabled ? null : customClockText();
        if (dateView != null) {
            CharSequence dt = disabled ? null : ClockFormat.dateText(cfg, new Date(), Locale.getDefault());
            CharSequence want = dt == null ? "" : dt;
            if (!TextUtils.equals(dateView.getText(), want)) dateView.setText(want);
        }
        settingClock = true;
        try {
            if (mine != null) {
                if (stockFeatures == null)
                    stockFeatures = clock.getFontFeatureSettings() == null ? "" : clock.getFontFeatureSettings();
                String want = ClockFormat.needsSeconds(cfg) ? "tnum" : stockFeatures; // no jitter with seconds
                if (!TextUtils.equals(nz(clock.getFontFeatureSettings()), want))
                    clock.setFontFeatureSettings(want.isEmpty() ? null : want);
                if (!TextUtils.equals(clock.getText(), mine)) clock.setText(mine);
                clockTouched = true;
            } else if (clockTouched) {
                if (stockFeatures != null) clock.setFontFeatureSettings(stockFeatures.isEmpty() ? null : stockFeatures);
                if (stockText != null) clock.setText(stockText);
                clockTouched = false;
            }
        } finally {
            settingClock = false;
        }
    }

    /**
     * Called from hooks Samsung runs on the clock that is really on screen (minute tick, tint,
     * realign). If our clock is detached or hidden (status bar rebuilt, clock moved side), switch
     * to this one. Cheap: a few field checks, runs at most a few times a minute.
     */
    void considerClock(TextView v) {
        if (disabled || guardTripped || !initDone || v == null || v.getId() != clockId) return;
        TextView cur = clockRef.get();
        if (v == cur && label != null) return;
        // isShown() walks up to the window root, so it's true only for a clock really on screen.
        // (isAttachedToWindow() can be false here: Samsung moves the clock between side containers
        // without the normal attach callbacks.)
        if (!v.isShown() || !(v.getParent() instanceof LinearLayout)) return;
        if (cur != null && cur.isShown() && label != null) return;
        Log.i("switching to the visible clock in " + idName(((View) v.getParent()).getId()));
        clockCandidates.add(new WeakReference<>(v));
        attachTo(v);
    }

    // ------------------------------------------------------- burn-in protection

    /** Small cycle of offsets (px) visited one step every 2 minutes; never more than 3 px away. */
    private static final int[][] SHIFT = {{0, 0}, {2, 0}, {3, 1}, {1, 1}, {-1, 1}, {-3, 0}, {-2, -1}, {0, -1}, {2, -1}, {1, 0}, {-1, 0}, {-2, 1}};
    private int shiftStep;
    private WeakReference<View> shiftedRef = new WeakReference<>(null);

    private final Runnable shiftTick = new Runnable() {
        @Override public void run() {
            try {
                main.removeCallbacks(this);
                if (disabled) return;
                applyShift(cfg.pixelShift && screenOn);
                if (cfg.pixelShift && screenOn) main.postDelayed(this, 120_000);
            } catch (Throwable t) { fail("shift", t); }
        }
    };

    private int[] curShift = {0, 0};      // offset we added
    private float lastX, lastY;              // translation right after our last change

    /** Moves the whole status bar row (Samsung's icons too) by a few pixels to spread wear. */
    private void applyShift(boolean on) {
        TextView clock = clockRef.get();
        int id = ctx.getResources().getIdentifier("status_bar_contents", "id", Config.SYSUI);
        if (clock != null) Log.missId(id, "status_bar_contents", "the burn-in protection");
        View row = clock == null || id == 0 ? null : clock.getRootView().findViewById(id);
        View old = shiftedRef.get();
        if (old != null && old != row) { removeShift(old); shiftedRef = new WeakReference<>(null); }
        if (row == null) return;
        // Someone else set the translation since our last step: our offset is gone, start from theirs.
        if (row == old && (row.getTranslationX() != lastX || row.getTranslationY() != lastY)) curShift = new int[]{0, 0};
        float baseX = row.getTranslationX() - curShift[0], baseY = row.getTranslationY() - curShift[1];
        if (on) {
            shiftStep = (shiftStep + 1) % SHIFT.length;
            curShift = SHIFT[shiftStep].clone();
        } else {
            curShift = new int[]{0, 0};
        }
        row.setTranslationX(baseX + curShift[0]);
        row.setTranslationY(baseY + curShift[1]);
        lastX = row.getTranslationX();
        lastY = row.getTranslationY();
        shiftedRef = new WeakReference<>(row);
    }

    private void removeShift(View row) {
        if (row.getTranslationX() == lastX && row.getTranslationY() == lastY) {
            row.setTranslationX(lastX - curShift[0]);
            row.setTranslationY(lastY - curShift[1]);
        }
        curShift = new int[]{0, 0};
    }

    /** Samsung just re-tinted a clock (light / dark status bar): restyle our views right away. */
    void onClockTint(TextView v) {
        considerClock(v);
        if (disabled || v != clockRef.get() || label == null) return;
        applyStyle(v, true);
        backChip.style(v);
    }

    void fixClockAlign(TextView v) {
        considerClock(v);
        if (disabled || !clockTouched || v != clockRef.get()) return;
        CharSequence t = v.getText();
        if (t == null || t.length() == 0) return;
        String s = t.toString();
        android.graphics.Rect all = new android.graphics.Rect(), digits = new android.graphics.Rect();
        v.getPaint().getTextBounds(s, 0, s.length(), all);
        v.getPaint().getTextBounds("0:00", 0, 4, digits);
        float shift = all.exactCenterY() - digits.exactCenterY();
        if (Math.abs(shift) >= 0.5f) v.setTranslationY(v.getTranslationY() + shift);
    }

    private String idName(int id) {
        try { return id == View.NO_ID ? "-" : ctx.getResources().getResourceEntryName(id); } catch (Throwable t) { return "?"; }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private boolean tickIdle;   // seconds clock is waiting a full minute because the bar is hidden

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            try {
                TextView ck = clockRef.get();
                boolean shown = ck != null && ck.isShown();
                tickIdle = !shown && ClockFormat.needsSeconds(cfg);
                if (shown || !ClockFormat.needsSeconds(cfg)) refreshClock();   // hidden bar: no 1 s refresh
                if (disabled || cfg.clockMode == Config.CLOCK_STOCK || ck == null) return;
                if (!screenOn) return; // restarted on screen-on
                long now = System.currentTimeMillis();
                long delay = ClockFormat.needsSeconds(cfg) && shown ? 1000 - (now % 1000) : 60_000 - (now % 60_000);
                main.postDelayed(this, delay + 15);
            } catch (Throwable t) { fail("clock-tick", t); }
        }
    };

    private void restartClockTick() {
        main.removeCallbacks(clockTick);
        main.post(clockTick);
    }

    // ---------------------------------------------------------------- polling

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (disabled) { polling = false; return; }
            CharSequence text;
            try {
                text = renderAll();
            } catch (Throwable t) {
                fail("render", t);
                text = null;
            }
            final CharSequence out = text == null ? "" : text;
            main.post(() -> {
                try {
                    lastText = out;
                    if (label != null) {
                        if (!TextUtils.equals(label.getText(), out)) label.setText(out);
                        TextView c = clockRef.get();
                        if (c != null) applyStyle(c, false);
                    }
                } catch (Throwable t) { fail("update", t); }
            });
            if (shouldPoll()) bg.postDelayed(this, cfg.intervalSec * 1000L);
            else polling = false;
        }
    };

    private CharSequence renderAll() throws Exception {
        Config c = cfg;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (Item item : Items.ALL) {
            if (!item.enabled(c)) continue;
            CharSequence s;
            try {
                s = item.render(ctx, c, sensors);
            } catch (Throwable t) {
                fail("item " + item.key(), t);
                continue;
            }
            if (TextUtils.isEmpty(s)) continue;
            if (sb.length() > 0) sb.append(Items.SEPARATOR);
            sb.append(s);
        }
        return sb;
    }

    private boolean shouldPoll() {
        return !disabled && clockRef.get() != null && screenOn;   // never poll with the screen off
    }

    private void startPolling() {
        if (bg == null) return;
        bg.removeCallbacks(tick);
        if (shouldPoll()) { polling = true; bg.post(tick); }
    }

    private int wake, tipWake = -1;

    /** At most one tip per screen-on, whichever signal comes first (unlock, or screen on without a lock screen). */
    private void tryTip(int w) {
        if (tipWake == w) return;
        tipWake = w;
        tips.maybeShow(clockRef.get(), prefs);
    }

    private final BroadcastReceiver systemReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            try {
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    screenOn = true;
                    wake++;
                    // no lock screen ("None" / not locked right now): Android may never say "user present", so the screen
                    // coming on is the unlock
                    final int w = wake;
                    main.postDelayed(() -> {
                        try {
                            android.app.KeyguardManager km = ctx.getSystemService(android.app.KeyguardManager.class);
                            if (screenOn && w == wake && (km == null || !km.isKeyguardLocked())) tryTip(w);
                        } catch (Throwable t) { fail("tip", t); }
                    }, 1500);
                    Smart.resume();
                    main.post(shiftTick);
                    startPolling();
                    restartClockTick();
                } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                    Smart.resume();
                    final int w = wake;
                    main.postDelayed(() -> {
                        try { tryTip(w); } catch (Throwable t) { fail("tip", t); }
                    }, 1500);
                } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    screenOn = false;
                    Smart.screenOff();
                    tips.stop();
                    main.removeCallbacks(shiftTick); // nothing to protect while the screen is off
                } else {
                    restartClockTick(); // time / timezone / language changed
                }
            } catch (Throwable t) { fail("system", t); }
        }
    };

    // ----------------------------------------------------------- control API

    private void registerControlReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(Config.ACTION_APPLY);
        f.addAction(Config.ACTION_PING);
        f.addAction(Config.ACTION_RESET_GUARD);
        f.addAction(Config.ACTION_TEST);
        f.addAction(Config.ACTION_RESTART);
        f.addAction(Config.ACTION_TIP);
        f.addAction(Config.ACTION_TOAST);
        // Only apps holding our signature permission (= the settings app) can send these.
        ctx.registerReceiver(controlReceiver, f, Config.PERM, main, Context.RECEIVER_EXPORTED);
    }

    private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            try {
                String a = i.getAction();
                if (Config.ACTION_APPLY.equals(a)) {
                    Config n = Config.fromBundle(i.getExtras());
                    cfg = n;
                    if (guardTripped || disabled) { n.save(prefs); sendStatus(); return; }   // mod is off: only remember the settings, run no feature code
                    Smart.apply(ctx, prefs, n.smartOn);
                    NotifExpand.apply(n);
                    n.save(prefs);
                    publishLockMax(n);
                    if (label != null) place();
                    bindTaps(true);
                    TextView ck = clockRef.get();
                    safeMove(n, statusRoot, ck);
                    if (ck != null) backChip.attach(ck);
                    backChip.update();
                    main.post(shiftTick);
                    startPolling();
                    restartClockTick();
                } else if (Config.ACTION_TOAST.equals(a)) {   // "3, 2, 1" before the assistant drives the screen (status bar toasts are never blocked)
                    String t = i.getStringExtra("t");
                    int n = Math.max(0, Math.min(5, i.getIntExtra("count", 0)));
                    for (int k = n; k >= 0; k--) {
                        final int at = k;
                        main.postDelayed(() -> {
                            try {
                                String m = at > 0 ? (t != null ? t + " in " : "") + at + "\u2026" : t;
                                if (lastToast != null) lastToast.cancel();   // one at a time, so each number shows on its own second
                                lastToast = m == null ? null : android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_SHORT);
                                if (lastToast != null) lastToast.show();
                            } catch (Throwable th) { fail("toast", th); }
                        }, (n - k) * 1000L);
                    }
                    return;
                } else if (Config.ACTION_TIP.equals(a)) {
                    TextView ck = clockRef.get();
                    if (ck != null && !tips.show(ck)) Log.i("tip: nothing to show / no room");
                    return;
                } else if (Config.ACTION_RESTART.equals(a)) {
                    // Asked by the app: not a crash, so clear the guard, then let Android restart us
                    // (SystemUI is persistent, it comes straight back). No root needed.
                    prefs.edit().putInt("guard_starts", 0).putString("guard_slow", "").commit();
                    Log.i("restart requested by app");
                    main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
                    return;
                } else if (Config.ACTION_TEST.equals(a)) {
                    TextView clock = clockRef.get();
                    Context vc = clock != null ? clock.getContext() : ctx;
                    TapActions.perform(vc, i.getIntExtra("act", 0), i.getStringExtra("app"));
                    return;
                } else if (Config.ACTION_RESET_GUARD.equals(a)) {
                    prefs.edit().putInt("guard_starts", 0).putString("guard_slow", "").commit();
                }
                sendStatus();
            } catch (Throwable t) { fail("control", t); }
        }
    };

    private long moverPausedUntil;

    /**
     * The moving code is optional polish: if it throws, put everything back to Samsung's places and pause only that part for
     * 30 s (counted as ONE error), so a problem there can never take the whole mod down.
     */
    private void safeMove(Config c, View root, TextView clock) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now < moverPausedUntil) return;
        try {
            mover.update(c, root, clock, label, batteryTaps.view(), backChip.view(), dateView);
        } catch (Throwable t) {
            moverPausedUntil = now + 30_000;
            try { mover.releaseAll(); } catch (Throwable ignored) {}
            fail("mover", t);
        }
    }

    private void sendStatus() {
        Intent r = new Intent(Config.ACTION_PONG).setPackage(Config.PKG);
        TextView clock = clockRef.get();
        String side = "-";
        if (clock != null && clock.getParent() instanceof View) {
            int pid = ((View) clock.getParent()).getId();
            side = pid == rightContainerId ? "right" : pid == middleContainerId ? "middle" : "left";
        }
        r.putExtra("version", VERSION);
        r.putExtra("disabled", disabled);
        r.putExtra("guardTripped", guardTripped);
        r.putExtra("guardStarts", prefs != null ? prefs.getInt("guard_starts", 0) : -1);
        r.putExtra("attached", label != null && label.getParent() != null);
        r.putExtra("text", String.valueOf(lastText));
        r.putExtra("source", sensors.lastSource);
        r.putExtra("tempValue", sensors.lastValue);
        View bv = batteryTaps.view();
        r.putExtra("battery", bv != null);
        r.putExtra("batteryClass", bv == null ? "-" : bv.getClass().getSimpleName());
        r.putExtra("clockSide", side);
        r.putExtra("polling", polling);
        r.putExtra("errors", errors);
        r.putExtra("lastError", lastError);
        r.putExtra("misses", Log.misses());
        ctx.sendBroadcast(r);
    }

    // ---------------------------------------------------------------- errors

    /** Tells the app / boot helper that the mod is alive (a timestamp) or not (0). */
    private void gput(String key, long v) {
        try { android.provider.Settings.Global.putLong(ctx.getContentResolver(), key, v); } catch (Throwable ignored) {}
    }

    void fail(String where, Throwable t) {
        try {
            errors++;
            long nowE = android.os.SystemClock.elapsedRealtime();
            if (nowE - errWindowStart > ERROR_WINDOW_MS) { errWindowStart = nowE; windowErrors = 0; }
            windowErrors++;
            lastError = where + ": " + t;
            Log.w("error #" + errors + " in " + lastError);
            if (windowErrors >= MAX_ERRORS && !disabled) {
                disabled = true; markOff();
                Log.w("error storm (" + windowErrors + " in a minute) - removing mod until SystemUI restarts");
                gput("sbmods_alive", 0L); gput("sbmods_alive_boot", -1L);
                main.post(() -> {
                    try { refreshClock(); } catch (Throwable ignored) {} // restores stock clock
                    try { detach(); } catch (Throwable ignored) {}
                    Entry.unhook();
                });
            }
        } catch (Throwable ignored) {}
    }
}
