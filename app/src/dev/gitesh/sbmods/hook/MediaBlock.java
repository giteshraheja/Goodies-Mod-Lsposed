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

    /**
     * Compact player sized for a Recent apps card (~130 dp wide):
     *   [cover] title / artist      [like]
     *   ~~~~~~~~~ seek bar ~~~~~~~~~~
     *   0:52                     2:58
     *   [extra] prev (play) next [extra]
     * On a card (strip mode) prev / play / next sit in the card's right strip and the row keeps only the extras.
     * Blurred cover behind, darkened so the text stays readable.
     */
    static View make(Activity a, RecentsNotifs.N n, float d) { return make(a, n, d, null); }

    /** strip != null (Recent apps card): prev / play / next go into strip[0] as a vertical pill for the card's right strip;
     *  the block keeps title, seek bar and the app's extra buttons over the sharp cover (no thumb: too narrow). */
    static View make(Activity a, RecentsNotifs.N n, float d, View[] strip) {
        boolean vertical = strip != null;
        MediaController mc = new MediaController(a, n.session);
        FrameLayout box = new FrameLayout(a);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 16 * d); }
        });
        box.setClipToOutline(true);
        GradientDrawable base = new GradientDrawable();
        base.setColor(0xFF2C2C2E);
        box.setBackground(base);

        ImageView art = new ImageView(a) {   // fills the block (sized by the content, not by the picture)
            @Override protected void onMeasure(int w, int h) {
                if (View.MeasureSpec.getMode(h) != View.MeasureSpec.EXACTLY) { setMeasuredDimension(View.MeasureSpec.getSize(w), 0); return; }
                super.onMeasure(w, h);
            }
        };
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (!vertical) art.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(18 * d, 18 * d, android.graphics.Shader.TileMode.CLAMP));   // card: sharp cover (no room for a thumb)
        box.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(a) {   // over the blur; 0 height when unconstrained, same trick as the cover
            @Override protected void onMeasure(int w, int h) {
                if (View.MeasureSpec.getMode(h) != View.MeasureSpec.EXACTLY) { setMeasuredDimension(View.MeasureSpec.getSize(w), 0); return; }
                super.onMeasure(w, h);
            }
        };
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, vertical ? new int[]{0x33000000, 0x66000000, 0xD9000000} : new int[]{0x8C000000, 0xB3000000}));
        box.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(8 * d);
        col.setPadding(p, p, p, Math.round(4 * d));

        LinearLayout head = new LinearLayout(a);   // cover thumb + title / artist + like
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView thumb = new ImageView(a);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 8 * d); }
        });
        thumb.setClipToOutline(true);
        int ts = Math.round(34 * d);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ts, ts);
        tl.rightMargin = Math.round(7 * d);
        if (!vertical) head.addView(thumb, tl);
        LinearLayout names = new LinearLayout(a);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(a, 12, true);
        TextView artist = text(a, 10, false);
        artist.setTextColor(0xCCFFFFFF);
        names.addView(title);
        names.addView(artist);
        head.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        FrameLayout likeSlot = new FrameLayout(a);   // the app's first extra button (usually like)
        head.addView(likeSlot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar bar = new SeekBar(a);
        bar.setPadding(0, Math.round(6 * d), 0, 0);
        Squiggly sq = new Squiggly(d);   // One UI's moving wave
        bar.setProgressDrawable(sq);
        GradientDrawable knob = new GradientDrawable();   // upright pill handle
        knob.setColor(0xFFFFFFFF);
        knob.setCornerRadius(2 * d);
        knob.setSize(Math.round(4 * d), Math.round(14 * d));
        bar.setThumb(knob);
        bar.setSplitTrack(false);
        bar.setMinHeight(Math.round(14 * d));
        bar.setMaxHeight(Math.round(14 * d));
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout times = new LinearLayout(a);   // 0:52 ............ 2:58
        TextView now = text(a, 9, false), total = text(a, 9, false);
        now.setTextColor(0xB3FFFFFF); total.setTextColor(0xB3FFFFFF);
        times.addView(now, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(total);
        col.addView(times, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout btns = new LinearLayout(a);   // equal slots so it fits any card width
        btns.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout leftSlot = new FrameLayout(a), rightSlot = new FrameLayout(a);
        ImageView prev = button(a, android.R.drawable.ic_media_previous, d, 28);
        ImageView play = button(a, android.R.drawable.ic_media_play, d, vertical ? 26 : 34);   // card: fits inside the 30 dp pill
        ImageView next = button(a, android.R.drawable.ic_media_next, d, 28);
        GradientDrawable pb = new GradientDrawable();   // play / pause: solid white circle
        pb.setShape(GradientDrawable.OVAL);
        pb.setColor(0xFFFFFFFF);
        play.setBackground(pb);
        play.setColorFilter(0xFF111111);
        FrameLayout midSlot = new FrameLayout(a);
        View[] slots = vertical ? new View[]{leftSlot, midSlot, rightSlot} : new View[]{leftSlot, prev, play, next, rightSlot};
        float[] wts = vertical ? new float[]{1f, 1f, 1f} : new float[]{1f, 1f, 1.3f, 1f, 1f};
        for (int i = 0; i < slots.length; i++) {
            FrameLayout cell = slots[i] instanceof FrameLayout ? (FrameLayout) slots[i] : new FrameLayout(a);
            if (cell != slots[i]) {
                ViewGroup.LayoutParams ol = slots[i].getLayoutParams();
                cell.addView(slots[i], new FrameLayout.LayoutParams(ol.width, ol.height, Gravity.CENTER));
            }
            btns.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, wts[i]));
        }
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Math.round(2 * d);
        col.addView(btns, bl);
        if (vertical) {   // one round dark pill in the card's free strip, like the clear button
            LinearLayout pill = new LinearLayout(a);
            pill.setOrientation(LinearLayout.VERTICAL);
            pill.setGravity(Gravity.CENTER);
            GradientDrawable pb2 = new GradientDrawable();
            pb2.setColor(0xE61C1C1E);
            pb2.setCornerRadius(15 * d);
            pill.setBackground(pb2);
            int vp = Math.round(2 * d);
            pill.setPadding(0, vp, 0, vp);
            View[] bs = {prev, play, next};
            for (int i = 0; i < bs.length; i++) {   // spread over the player's height (the card sets the pill's height)
                if (i > 0) pill.addView(new View(a), new LinearLayout.LayoutParams(1, 0, 1f));
                LinearLayout.LayoutParams l = (LinearLayout.LayoutParams) bs[i].getLayoutParams();
                l.leftMargin = l.rightMargin = 0; l.topMargin = l.bottomMargin = Math.round(1 * d);
                pill.addView(bs[i], l);
            }
            strip[0] = pill;
        }
        box.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout[] extraSlots = vertical ? new FrameLayout[]{leftSlot, midSlot, rightSlot} : new FrameLayout[]{likeSlot, leftSlot, rightSlot};
        final String[] extraKey = {""};

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
            thumb.setImageBitmap(bm);
            thumb.setVisibility(bm != null ? View.VISIBLE : View.GONE);
            long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            if (play.getTag() != Boolean.valueOf(playing)) {   // play <-> pause: a quick pop so the change is noticed
                if (play.getTag() != null) { play.setScaleX(0.6f); play.setScaleY(0.6f); play.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(new android.view.animation.OvershootInterpolator(2.5f)).start(); }
                play.setTag(playing);
                play.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
            }
            sq.setPlaying(playing);
            play.setContentDescription(playing ? "Pause" : "Play");
            long pos = st == null ? 0 : st.getPosition();
            if (playing && st.getLastPositionUpdateTime() > 0) pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            if (dur > 0) pos = Math.min(pos, dur);
            bar.setVisibility(dur > 0 ? View.VISIBLE : View.GONE);
            times.setVisibility(dur > 0 ? View.VISIBLE : View.GONE);
            bar.setEnabled(st != null && (st.getActions() & PlaybackState.ACTION_SEEK_TO) != 0);
            if (!dragging[0] && dur > 0) { bar.setMax((int) (dur / 1000)); bar.setProgress((int) (pos / 1000)); now.setText(time(pos)); }
            total.setText(time(dur));
            java.util.List<PlaybackState.CustomAction> cas = st == null ? java.util.Collections.emptyList() : st.getCustomActions();
            StringBuilder k = new StringBuilder();
            for (PlaybackState.CustomAction ca : cas) k.append(ca.getAction()).append(ca.getIcon()).append(',');
            if (!k.toString().equals(extraKey[0])) {   // rebuild only when the app changes its buttons (e.g. liked -> unlike)
                extraKey[0] = k.toString();
                for (FrameLayout sl : extraSlots) sl.removeAllViews();
                int used = 0;
                for (int i = 0; i < cas.size() && used < extraSlots.length; i++) {   // 1st: beside the title, 2nd / 3rd: either side of the controls
                    PlaybackState.CustomAction ca = cas.get(i);
                    ImageView iv = button(a, 0, d, 26);
                    try { iv.setImageDrawable(a.createPackageContext(mc.getPackageName(), 0).getDrawable(ca.getIcon())); } catch (Throwable e) { continue; }
                    iv.setContentDescription(ca.getName());
                    iv.setOnClickListener(v -> { tap(v); mc.getTransportControls().sendCustomAction(ca, null); });
                    iv.setOnLongClickListener(v -> { android.widget.Toast.makeText(a, ca.getName(), android.widget.Toast.LENGTH_SHORT).show(); return true; });
                    ViewGroup.LayoutParams il = iv.getLayoutParams();
                    extraSlots[used++].addView(iv, new FrameLayout.LayoutParams(il.width, il.height, Gravity.CENTER));
                }
                if (vertical) btns.setVisibility(used > 0 ? View.VISIBLE : View.GONE);   // only the extras row is left in the block
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
        t.setShadowLayer(6, 0, 2, 0xB3000000);   // readable over a bright cover
        return t;
    }

    private static ImageView button(Activity a, int res, float d, int dp) {
        ImageView b = new ImageView(a);
        if (res != 0) b.setImageResource(res);
        android.animation.StateListAnimator sla = new android.animation.StateListAnimator();   // springs down when pressed, pops back on release
        android.animation.AnimatorSet down = new android.animation.AnimatorSet();
        down.playTogether(android.animation.ObjectAnimator.ofFloat(b, "scaleX", 0.82f), android.animation.ObjectAnimator.ofFloat(b, "scaleY", 0.82f));
        down.setDuration(90);
        android.animation.AnimatorSet up = new android.animation.AnimatorSet();
        up.playTogether(android.animation.ObjectAnimator.ofFloat(b, "scaleX", 1f), android.animation.ObjectAnimator.ofFloat(b, "scaleY", 1f));
        up.setDuration(260);
        up.setInterpolator(new android.view.animation.OvershootInterpolator(3f));
        sla.addState(new int[]{android.R.attr.state_pressed}, down);
        sla.addState(new int[]{}, up);
        b.setStateListAnimator(sla);
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
