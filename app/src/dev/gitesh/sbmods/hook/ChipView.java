// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

/**
 * One clean line of text: a small coloured dot (green / amber / red / cyan = state), a light label and a bold value.
 * No box; a soft highlight shows only while it is pressed. Drawn by hand so nothing stretches, wraps or clips.
 */
final class ChipView extends View {
    private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF r = new android.graphics.RectF();
    private String label = "", value = "", sub = "";
    private int subColor = 0x99FFFFFF;
    private int color = Color.WHITE;
    private final float d;
    static final float PAD = 4;
    static final float BLOCK = 14 * 0.95f + 18 * 0.95f + 13 * 0.95f + 2;   // dp of the three text lines + tiny gaps   // dp between the chip's edge and its text
    boolean stacked, compact, bare;   // compact: small label; bare: no label at all (dot + value)
    int maxW, hgt;       // hgt: same height as "Close all" (0 = not known yet)            // stacked chips: never wider than this (room beside Samsung's "Close all"); text shrinks to fit
    private float k = 1; // that shrink factor   // compact: just dot + value (used when Samsung's own pill shares the corner)

    ChipView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setClickable(true);
        setLongClickable(true);
    }

    void sub(String sub, int color) {
        if (!sub.equals(this.sub)) { this.sub = sub; requestLayout(); }
        if (subColor != color) { subColor = color; invalidate(); }
    }

    void set(String label, String value, int color, float frac) {
        boolean relayout = !label.equals(this.label) || !value.equals(this.value);
        this.label = label; this.value = value; this.color = color;
        if (relayout) requestLayout();
        invalidate();
    }

    private void fontLabel() { p.setTextSize((compact ? 11f : 12.5f) * d); p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); }
    private void fontValue() { p.setTextSize((bare ? 12f : 13f) * d); p.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD)); }

    @Override protected void onMeasure(int w, int h) {
        fontLabel(); float lw = bare ? 0 : p.measureText(label);
        fontValue(); float vw = p.measureText(value);
        if (compact) { setMeasuredDimension(Math.round(2 * d + 7 * d + 4 * d + (bare ? 0 : lw + 3 * d) + vw + 2 * d), Math.round(40 * d)); return; }   // short, but still says what it is
        if (stacked) {
            p.setTextSize(14f * d); lw = p.measureText(label);
            p.setTextSize(18f * d); vw = p.measureText(value);
            p.setTextSize(13f * d); float sw = p.measureText(sub);
            float content = Math.max(Math.max(13 * d + lw, vw), sw);
            k = maxW > 0 ? Math.max(0.6f, Math.min(1f, (maxW - 2 * PAD * d) / content)) : 1f;
            int hh = hgt > 0 ? hgt : Math.round(48 * d);
            k = Math.min(k, hh / (BLOCK * d));   // three lines must fit the button's height
            setMeasuredDimension(Math.round(2 * PAD * d + content * k), hh); return;
        }
        setMeasuredDimension(Math.round(4 * d + 7 * d + 5 * d + lw + 4 * d + vw + 2 * d), Math.round(40 * d));
    }

    @Override protected void onDraw(android.graphics.Canvas c) {
        float w = getWidth(), h = getHeight(), cy = h / 2;
        if (isPressed()) {
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(0x33FFFFFF);
            r.set(0, 4 * d, w, h - 4 * d);
            c.drawRoundRect(r, 14 * d, 14 * d, p);
        }
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setShadowLayer(3 * d, 0, 1 * d, 0x99000000);   // keeps it readable on any wallpaper
        if (stacked) {   // dot + label, value, then what holds it back. The right-hand chip is a mirror image: it reads from the right edge.
            boolean right = getLayoutParams() instanceof FrameLayout.LayoutParams && (((FrameLayout.LayoutParams) getLayoutParams()).gravity & Gravity.END) == Gravity.END;
            float x0 = right ? w - PAD * d : PAD * d, dir = right ? -1 : 1;
            p.setTextAlign(right ? android.graphics.Paint.Align.RIGHT : android.graphics.Paint.Align.LEFT);
            p.setColor(color);
            // same height as "Close all": label's top on the button's top edge, the speed centred on its text, the last line on its bottom edge
            float y1 = 0.74f * 14 * d * k, y2 = h / 2f + 0.36f * 18 * d * k, y3 = h - 0.22f * 13 * d * k;
            c.drawCircle(x0 + dir * 4f * d, y1 - 4.8f * d * k, 4f * d, p);
            p.setTextSize(14f * d * k);
            p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            p.setColor(0xCCFFFFFF);
            c.drawText(label, x0 + dir * 13 * d, y1, p);
            p.setTextSize(18f * d * k);
            p.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            p.setColor(Color.WHITE);
            c.drawText(value, x0, y2, p);
            p.setTextSize(13f * d * k);
            p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            p.setColor(subColor);
            c.drawText(sub, x0, y3, p);
            p.clearShadowLayer();
            return;
        }
        p.setColor(color);
        float in = compact ? 2 * d : 4 * d;
        c.drawCircle(in + 3.5f * d, cy, 3.5f * d, p);
        p.setTextAlign(android.graphics.Paint.Align.LEFT);
        float x = in + 7 * d + (compact ? 4 : 5) * d;
        if (!bare) {
            fontLabel();
            p.setColor(compact ? 0x99FFFFFF : 0xCCFFFFFF);
            c.drawText(label, x, cy + 4.6f * d, p);
            x += p.measureText(label) + (compact ? 3 : 4) * d;
        }
        fontValue();
        p.setColor(color == RecentsChip.GREEN || color == RecentsChip.CYAN || color == RecentsChip.AMBER || color == RecentsChip.RED ? Color.WHITE : color);
        c.drawText(value, x, cy + 4.6f * d, p);
        p.clearShadowLayer();
    }

    @Override public void setPressed(boolean pressed) { super.setPressed(pressed); invalidate(); }
}
