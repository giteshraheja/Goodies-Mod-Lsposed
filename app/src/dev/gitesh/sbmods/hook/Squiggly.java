// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * Seek bar track like One UI's media player: the played part is a wave that moves while music plays,
 * the rest a flat faint line. Animates only while it is drawn (Recent apps open) and playing.
 */
final class Squiggly extends Drawable {
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG), rest = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float amp, len, step;
    private boolean playing;
    private float phase, ampNow;
    private long last;

    Squiggly(float d) {
        amp = 1.6f * d; len = 20 * d; step = 2 * d;
        for (Paint p : new Paint[]{wave, rest}) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.2f * d); p.setStrokeCap(Paint.Cap.ROUND); }
        wave.setColor(0xFFFFFFFF);
        rest.setColor(0x66FFFFFF);
    }

    void setPlaying(boolean p) { if (playing != p) { playing = p; last = 0; invalidateSelf(); } }

    @Override protected boolean onLevelChange(int level) { invalidateSelf(); return true; }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.width() <= 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0 : Math.min(100, now - last) / 1000f;
        last = now;
        float target = playing ? amp : 0;   // the wave flattens when paused, like One UI
        ampNow += (target - ampNow) * Math.min(1f, dt * 6);
        if (playing) phase = (phase + dt * len * 1.2f) % len;
        float cy = b.exactCenterY(), x0 = b.left + wave.getStrokeWidth(), x1 = b.right - wave.getStrokeWidth();
        float px = x0 + (x1 - x0) * getLevel() / 10000f;
        path.reset();
        path.moveTo(x0, cy + (float) Math.sin((x0 + phase) / len * 2 * Math.PI) * ampNow);
        for (float x = x0 + step; x < px; x += step) path.lineTo(x, cy + (float) Math.sin((x + phase) / len * 2 * Math.PI) * ampNow);
        path.lineTo(px, cy + (float) Math.sin((px + phase) / len * 2 * Math.PI) * ampNow);
        c.drawPath(path, wave);
        if (px < x1) c.drawLine(px + step, cy, x1, cy, rest);
        if (playing || Math.abs(ampNow - target) > 0.05f) invalidateSelf();   // next frame only while it moves
    }

    @Override public void setAlpha(int a) { wave.setAlpha(a); rest.setAlpha(Math.min(a, 0x66)); }
    @Override public void setColorFilter(ColorFilter cf) { wave.setColorFilter(cf); rest.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
