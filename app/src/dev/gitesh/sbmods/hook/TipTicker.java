// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import dev.gitesh.sbmods.Config;

/**
 * Now and then, right after unlocking (or the screen coming on without a lock screen), the status bar turns
 * into a black strip and one line scrolls across it in white: what's new after an update, or a reminder of a
 * gesture you set up ("Double-tap the clock to …"). Then the normal status bar is back. Costs nothing between runs.
 */
final class TipTicker {
    private static final String[] ITEM = {"the battery icon", "the clock", "the temperature", "the camera hole", "the Wi-Fi icon", "the mobile signal icon"};
    /** What each action does, as the end of "Tap the clock to …" (index = Config.ACT_*). */
    private static final String[] DOES = {"", "turn power saving on or off", "see battery usage", "open your alarms",
            "open your calendar", "open Settings", "turn the flashlight on or off", "turn Wi-Fi on or off",
            "turn Bluetooth on or off", "turn Do not disturb on or off", "turn auto rotate on or off",
            "switch dark mode", "switch between sound, vibrate and mute", "play or pause music", "lock the screen",
            "open quick settings", "open your notifications", "open the camera", "open the timer",
            "open the calculator", "open the internet panel", "open %s", "open Samsung Finder", "close background apps",
            "take a screenshot", "make the screen brighter", "make the screen darker",
            "open Modes and Routines", "open Samsung Notes", "open Gallery"};
    private String lastTip;

    /**
     * What's new, newest last. After an update each is shown once (one per unlock), before the usual reminders.
     * Bump nothing by hand: an entry is "new" until its index is below the saved news_seen count.
     */
    private static final String[] NEWS = {
            "New: open recent apps (Overview) to see free RAM, swap and temperature at the top",
            "New: Overview shows CPU and GPU speed at the bottom \u2013 tap one for the Performance panel",
            "New: Performance panel \u2013 limit CPU / GPU speed, or cool-down until the phone is cool",
            "New: long-press CPU or GPU on Overview to turn your quick limit on or off",
            "New: tap RAM on Overview to close background apps, Swap for a deep clean",
            "New: drag network speed and the date anywhere in StatusBar Mods \u203a Layout",
            "New: the Performance panel has four pages \u2013 All, Speed, Battery and Memory",
            "New: one-tap modes in the Performance panel \u2013 Full, Balanced, Cool or Saver",
            "New: the Battery page shows time left, screen-on time and standby drain",
            "New: the Memory page shows which apps work hardest, with a Stop button",
            "New: rest some cores to save power \u2013 Performance panel \u203a Cores",
            "New: try Vulkan graphics \u2013 Performance panel \u203a Graphics (Wi-Fi calling is guarded)",
            "New: hide status bar icons you never need \u2013 StatusBar Mods \u203a Layout \u203a Status icons",
    };
    private static final long NEWS_GAP_MS = 10 * 60_000L;
    private static final String[] GESTURE = {"Tap", "Double-tap", "Long-press"};
    private static final long[] MIN_GAP_MS = {0, 3 * 3600_000L, 30 * 60_000L}; // off, sometimes, often

    private final Controller owner;
    private final Random rnd = new Random();
    private ValueAnimator anim;
    private TextView view;
    private View windowView;

    TipTicker(Controller owner) { this.owner = owner; }

    /** Called on unlock: maybe show a tip, depending on the chosen frequency. */
    void maybeShow(TextView clock, SharedPreferences prefs) {
        Config c = owner.config();
        long now = System.currentTimeMillis();
        String why;
        if (c.tipsMode <= 0 || c.tipsMode >= MIN_GAP_MS.length) why = "off";
        else if (clock == null) why = "no clock";
        else {
            int seen = prefs.getInt("news_seen", 0);
            long gap = now - prefs.getLong("last_tip", 0);
            if (seen < NEWS.length && gap >= NEWS_GAP_MS) {   // something new to tell: next unlock, no dice roll
                if (show(clock, NEWS[seen])) { prefs.edit().putInt("news_seen", seen + 1).putLong("last_tip", now).apply(); why = "news " + seen; }
                else why = "news: no room";
            } else if (gap < MIN_GAP_MS[c.tipsMode]) why = "too soon";
            else if (c.tipsMode == 1 && rnd.nextInt(3) != 0) why = "dice";   // "sometimes": 1 in 3 eligible unlocks
            else if (show(clock)) { prefs.edit().putLong("last_tip", now).apply(); why = "shown"; }
            else why = "nothing to show / no room";
        }
        prefs.edit().putString("tip_last_try", now + " " + why).apply();   // so "why didn't I see a tip?" has an answer
    }

    /** Shows one random tip now. Returns false if there is nothing to show or no room. */
    boolean show(TextView clock) {
        return show(clock, null);
    }

    /** text: a given line (what's new), or null for a random reminder. */
    boolean show(TextView clock, String text) {
        String tip = text != null ? text : pickTip(clock.getContext());
        if (tip == null) return false;
        Context ctx = clock.getContext();
        // The whole status bar becomes a black strip with the tip scrolling across in white: always readable,
        // nothing behind it to fight with (live chip, back pill, moved items). Gone again when the tip ends.
        int barId = ctx.getResources().getIdentifier("status_bar_contents", "id", Config.SYSUI);
        View bar = barId == 0 ? null : clock.getRootView().findViewById(barId);
        View root = clock.getRootView();
        ViewGroup host = bar != null && bar.getParent() instanceof FrameLayout ? (ViewGroup) bar.getParent()
                : root instanceof FrameLayout ? (ViewGroup) root : null;
        if (host == null || !host.isShown() || host.getWidth() < 100 || host.getHeight() <= 0) return false;
        stop();

        float d = ctx.getResources().getDisplayMetrics().density;
        TextView t = new TextView(ctx);
        t.setTag("sbmods-tip");
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setText(tip);
        t.setTextColor(0xFFFFFFFF);
        t.setTypeface(clock.getTypeface());
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, clock.getTextSize() * 0.9f);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        FrameLayout window = new FrameLayout(ctx);
        window.setTag("sbmods-tip");
        window.setBackgroundColor(0xFF000000);
        window.setClipChildren(true);
        window.setTranslationZ(100 * d);   // above everything else in the bar
        window.setClickable(true);         // taps on the strip don't reach the icons under it
        int fullW = (int) Math.ceil(t.getPaint().measureText(tip)) + 4;
        window.addView(t, new FrameLayout.LayoutParams(fullW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START | Gravity.CENTER_VERTICAL));
        host.addView(window, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view = t;
        windowView = window;
        window.setAlpha(0f);
        window.animate().alpha(1f).setDuration(200).start();

        float width = host.getWidth();
        float textW = t.getPaint().measureText(tip);
        float speed = 48 * d; // px per second: comfortable to read across the full width
        long dur = (long) ((width + textW) / speed * 1000);
        float scale = ValueAnimator.getDurationScale();
        if (scale > 0) dur = (long) (dur / scale);   // same reading speed even with animations sped up in developer options
        t.setTranslationX(width);
        anim = ValueAnimator.ofFloat(width, -textW);
        anim.setInterpolator(new LinearInterpolator());
        anim.setDuration(dur);
        anim.setStartDelay(200);
        anim.addUpdateListener(a -> t.setTranslationX((float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { cleanup(); }
            @Override public void onAnimationCancel(Animator a) { cleanup(); }
        });
        anim.start();
        Log.i("tip: " + tip);
        return true;
    }

    void stop() {
        if (anim != null) anim.cancel();
        cleanup();
    }

    private void cleanup() {
        anim = null;
        view = null;
        View w = windowView;
        windowView = null;
        if (w != null && w.getParent() instanceof ViewGroup)
            w.animate().alpha(0f).setDuration(200).withEndAction(() -> { if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w); }).start();
    }

    /** Builds one reminder from what's actually configured, picked at random. */
    private String pickTip(Context ctx) {
        Config c = owner.config();
        List<String> tips = new ArrayList<>();
        for (int slot = 0; slot < Config.SLOTS; slot++) {
            int a = c.action(slot);
            if (a <= Config.ACT_NONE || a >= DOES.length) continue;
            String what = a == Config.ACT_APP ? String.format(DOES[a], appLabel(ctx, c.app(slot))) : DOES[a];
            tips.add(GESTURE[slot % 3] + " " + ITEM[slot / 3] + " to " + what);
        }
        if (c.backEnabled) tips.add("Tap the ‹ app pill on the left to jump back to that app");
        if (tips.isEmpty()) return null;
        if (tips.size() > 1) tips.remove(lastTip);     // never the same tip twice in a row
        lastTip = tips.get(rnd.nextInt(tips.size()));
        return "Tip:  " + lastTip;
    }

    private static String appLabel(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return "an app";
        }
    }
}
