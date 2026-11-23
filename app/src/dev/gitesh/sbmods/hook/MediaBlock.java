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

    static View make(Activity a, RecentsNotifs.N n, float d) { return make(a, n, d, null); }

    /** strip != null: the buttons go into strip[0] as a vertical column (for the card's right strip) instead of a row. */
    static View make(Activity a, RecentsNotifs.N n, float d, View[] strip) {
        MediaController mc = new MediaController(a, n.session);
        FrameLayout box = new FrameLayout(a);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 16 * d); }
        });
        box.setClipToOutline(true);
        GradientDrawable base = new GradientDrawable();
        base.setColor(0xFF2C2C2E);
        box.setBackground(base);

        ImageView art = new ImageView(a) {   // cover fills the block (sized by the text, not by the picture), darkened so the text stays readable
            @Override protected void onMeasure(int w, int h) {
                if (View.MeasureSpec.getMode(h) != View.MeasureSpec.EXACTLY) { setMeasuredDimension(View.MeasureSpec.getSize(w), 0); return; }
                super.onMeasure(w, h);
            }
        };
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x66000000, 0xCC000000});
        art.setForeground(g);   // shade drawn over the cover (a separate view would grow to the whole card)

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(8 * d);
        col.setPadding(p, Math.round(6 * d), p, Math.round(6 * d));
        TextView title = text(a, 12, true);
        title.setSingleLine(false);   // full song name: up to two lines
        title.setMaxLines(2);
        TextView artist = text(a, 10, false);
        artist.setTextColor(0xCCFFFFFF);
        col.addView(title);
        LinearLayout sub = new LinearLayout(a);   // artist + "0:13 / 3:50" on one line
        sub.addView(artist, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(sub);

        SeekBar bar = new SeekBar(a);
        bar.setPadding(0, Math.round(6 * d), 0, Math.round(2 * d));
        Squiggly sq = new Squiggly(d);   // One UI's moving wave
        bar.setProgressDrawable(sq);
        GradientDrawable thumb = new GradientDrawable();   // the upright pill handle of One UI's player
        thumb.setColor(0xFFFFFFFF);
        thumb.setCornerRadius(2 * d);
        thumb.setSize(Math.round(4 * d), Math.round(16 * d));
        bar.setThumb(thumb);
        bar.setSplitTrack(false);
        bar.setMinHeight(Math.round(16 * d));
        bar.setMaxHeight(Math.round(16 * d));
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView now = text(a, 9, false), total = text(a, 9, false);
        now.setTextColor(0xB3FFFFFF); total.setTextColor(0xB3FFFFFF);
        now.setPadding(Math.round(4 * d), 0, 0, 0);
        sub.addView(now);
        sub.addView(total);

        LinearLayout extra = new LinearLayout(a);   // the app's own extra buttons (thumbs up, dislike, shuffle...) from its media session
        extra.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(extra, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final String[] extraKey = {""};
        LinearLayout btns = new LinearLayout(a);
        btns.setGravity(Gravity.CENTER);
        boolean vertical = strip != null;
        ImageView prev = button(a, android.R.drawable.ic_media_previous, d, 28);
        ImageView play = button(a, android.R.drawable.ic_media_play, d, vertical ? 30 : 38);
        ImageView next = button(a, android.R.drawable.ic_media_next, d, 28);
        if (!vertical) btns.addView(prev);   // the card's strip keeps two: play / pause and next
        btns.addView(play); btns.addView(next);
        if (vertical) {   // one round dark pill in the card's free strip, like the clear button
            btns.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(0xE61C1C1E);
            pill.setCornerRadius(15 * d);
            btns.setBackground(pill);
            int vp = Math.round(2 * d);
            btns.setPadding(0, vp, 0, vp);
            for (View b : new View[]{play, next}) { LinearLayout.LayoutParams l = (LinearLayout.LayoutParams) b.getLayoutParams(); l.leftMargin = l.rightMargin = 0; l.topMargin = l.bottomMargin = Math.round(1 * d); }
            strip[0] = btns;
        } else {
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.topMargin = Math.round(2 * d);
            col.addView(btns, bl);
        }
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
            sq.setPlaying(playing);
            play.setContentDescription(playing ? "Pause" : "Play");
            long pos = st == null ? 0 : st.getPosition();
            if (playing && st.getLastPositionUpdateTime() > 0) pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            if (dur > 0) pos = Math.min(pos, dur);
            bar.setVisibility(dur > 0 ? View.VISIBLE : View.GONE);
            now.setVisibility(dur > 0 ? View.VISIBLE : View.GONE); total.setVisibility(dur > 0 ? View.VISIBLE : View.GONE);
            bar.setEnabled(st != null && (st.getActions() & PlaybackState.ACTION_SEEK_TO) != 0);
            if (!dragging[0] && dur > 0) { bar.setMax((int) (dur / 1000)); bar.setProgress((int) (pos / 1000)); now.setText(time(pos)); }
            total.setText("/" + time(dur));
            java.util.List<PlaybackState.CustomAction> cas = st == null ? java.util.Collections.emptyList() : st.getCustomActions();
            StringBuilder k = new StringBuilder();
            for (PlaybackState.CustomAction ca : cas) k.append(ca.getAction()).append(ca.getIcon()).append(',');
            if (!k.toString().equals(extraKey[0])) {   // rebuild only when the app changes its buttons (e.g. liked -> unlike)
                extraKey[0] = k.toString();
                extra.removeAllViews();
                int max = Math.min(4, cas.size());
                for (int i = 0; i < max; i++) {
                    PlaybackState.CustomAction ca = cas.get(i);
                    ImageView iv = button(a, 0, d, 26);
                    try { iv.setImageDrawable(a.createPackageContext(mc.getPackageName(), 0).getDrawable(ca.getIcon())); } catch (Throwable e) { continue; }
                    iv.setContentDescription(ca.getName());
                    iv.setOnClickListener(v -> { tap(v); mc.getTransportControls().sendCustomAction(ca, null); });
                    iv.setOnLongClickListener(v -> { android.widget.Toast.makeText(a, ca.getName(), android.widget.Toast.LENGTH_SHORT).show(); return true; });
                    extra.addView(iv);
                }
                extra.setVisibility(extra.getChildCount() > 0 ? View.VISIBLE : View.GONE);
            }
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
        if (res != 0) b.setImageResource(res);
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
