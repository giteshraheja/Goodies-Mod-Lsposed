// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A rounded bar showing 0..100 %, horizontal (rows) or vertical (core tiles); value changes glide over about a quarter second. */
final class BarView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final boolean vertical;
    private float shown, target;
    private ValueAnimator anim;

    BarView(Context c, boolean vertical) {
        super(c);
        this.vertical = vertical;
        track.setColor(0x22FFFFFF);
        fill.setColor(RecentsChip.GREEN);
    }

    /** pct is clamped to 0..100. The first value appears at once, later ones glide. */
    void set(int pct, int color) {
        float t = Math.max(0, Math.min(100, pct));
        fill.setColor(color);
        if (t != target) {
            target = t;
            if (anim != null) anim.cancel();
            if (!isLaidOut() || !isShown()) shown = t;   // nothing to see yet: no glide
            else {
                anim = ValueAnimator.ofFloat(shown, t);
                anim.setDuration(250);
                anim.addUpdateListener(v -> { shown = (Float) v.getAnimatedValue(); invalidate(); });
                anim.start();
            }
        }
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float rad = (vertical ? w : h) / 2f;
        r.set(0, 0, w, h);
        c.drawRoundRect(r, rad, rad, track);
        float f = shown / 100f;
        if (f <= 0f) return;
        if (vertical) r.set(0, h - Math.max(h * f, w), w, h);   // never thinner than a dot
        else r.set(0, 0, Math.max(w * f, h), h);
        c.drawRoundRect(r, rad, rad, fill);
    }
}
