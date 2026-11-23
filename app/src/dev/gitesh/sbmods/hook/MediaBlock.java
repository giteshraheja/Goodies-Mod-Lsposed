// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Music on its Recent apps card, laid out like One UI's media player: album cover behind, title / artist,
 * seek bar with times, previous / play-pause / next. Talks to the app's media session directly; the position
 * ticks once a second only while the block is on screen.
 */
final class MediaBlock {
    private MediaBlock() {}

    static View make(Activity a, RecentsNotifs.N n, float d) {
        MediaController mc = new MediaController(a, n.session);
        FrameLayout box = new FrameLayout(a);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 16 * d); }
        });
        box.setClipToOutline(true);
        GradientDrawable base = new GradientDrawable();
        base.setColor(0xFF2C2C2E);
        box.setBackground(base);

        ImageView art = new ImageView(a);   // cover fills the block, darkened so the text stays readable
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View dim = new View(a);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x66000000, 0xCC000000});
        dim.setBackground(g);
        box.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(10 * d);
        col.setPadding(p, p, p, Math.round(6 * d));
        TextView title = text(a, 13, true);
        TextView artist = text(a, 11, false);
        artist.setTextColor(0xCCFFFFFF);
        col.addView(title);
        col.addView(artist);

        SeekBar bar = new SeekBar(a);
        bar.setPadding(0, Math.round(10 * d), 0, Math.round(4 * d));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x66FFFFFF));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout times = new LinearLayout(a);
        TextView now = text(a, 9, false), total = text(a, 9, false);
        now.setTextColor(0xB3FFFFFF); total.setTextColor(0xB3FFFFFF);
        total.setGravity(Gravity.END);
        times.addView(now, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(total, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(times);

        LinearLayout btns = new LinearLayout(a);
        btns.setGravity(Gravity.CENTER);
        ImageView prev = button(a, android.R.drawable.ic_media_previous, d, 28);
        ImageView play = button(a, android.R.drawable.ic_media_play, d, 38);
        ImageView next = button(a, android.R.drawable.ic_media_next, d, 28);
        btns.addView(prev); btns.addView(play); btns.addView(next);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Math.round(2 * d);
        col.addView(btns, bl);
        box.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final boolean[] dragging = {false};
        Runnable show = () -> {
            MediaMetadata md = mc.getMetadata();
            PlaybackState st = mc.getPlaybackState();
            CharSequence t = md == null ? null : md.getText(MediaMetadata.METADATA_KEY_TITLE);
            CharSequence ar = md == null ? null : md.getText(MediaMetadata.METADATA_KEY_ARTIST);
            title.setText(t != null ? t : n.title);
            artist.setText(ar != null ? ar : n.text);
            Bitmap bm = md == null ? null : md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bm == null && md != null) bm = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (bm == null && md != null) bm = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            art.setImageBitmap(bm);
            long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            play.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
            play.setContentDescription(playing ? "Pause" : "Play");
            long pos = st == null ? 0 : st.getPosition();
            if (playing && st.getLastPositionUpdateTime() > 0) pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            if (dur > 0) pos = Math.min(pos, dur);
            bar.setVisibility(dur > 0 ? View.VISIBLE : View.GONE);
            times.setVisibility(dur > 0 ? View.VISIBLE : View.GONE);
            bar.setEnabled(st != null && (st.getActions() & PlaybackState.ACTION_SEEK_TO) != 0);
            if (!dragging[0] && dur > 0) { bar.setMax((int) (dur / 1000)); bar.setProgress((int) (pos / 1000)); now.setText(time(pos)); }
            total.setText(time(dur));
        };
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) { if (user) now.setText(time(v * 1000L)); }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging[0] = true; s.getParent().requestDisallowInterceptTouchEvent(true); }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging[0] = false; mc.getTransportControls().seekTo(s.getProgress() * 1000L); }
        });
        prev.setOnClickListener(v -> { tap(v); mc.getTransportControls().skipToPrevious(); });
        next.setOnClickListener(v -> { tap(v); mc.getTransportControls().skipToNext(); });
        play.setOnClickListener(v -> {
            tap(v);
            PlaybackState st = mc.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) mc.getTransportControls().pause(); else mc.getTransportControls().play();
        });
        box.setOnClickListener(v -> RecentsNotifs.open(a, n));   // tap the cover = open what the notification opens

        MediaController.Callback cb = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) { show.run(); }
            @Override public void onMetadataChanged(MediaMetadata metadata) { show.run(); }
        };
        Runnable tick = new Runnable() {
            @Override public void run() { if (box.isAttachedToWindow() && box.isShown()) { show.run(); box.postDelayed(this, 1000); } }
        };
        box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { mc.registerCallback(cb, RecentsChip.main); show.run(); v.postDelayed(tick, 1000); }
            @Override public void onViewDetachedFromWindow(View v) { mc.unregisterCallback(cb); v.removeCallbacks(tick); }
        });
        show.run();
        return box;
    }

    private static void tap(View v) { v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); }

    private static TextView text(Activity a, int sp, boolean bold) {
        TextView t = new TextView(a);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static ImageView button(Activity a, int res, float d, int dp) {
        ImageView b = new ImageView(a);
        b.setImageResource(res);
        b.setColorFilter(0xFFFFFFFF);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Math.round(dp / 5f * d);
        b.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(dp * d), Math.round(dp * d));
        lp.leftMargin = lp.rightMargin = Math.round(1 * d);
        b.setLayoutParams(lp);
        return b;
    }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
