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
    private static final String[] WORST_V = {"8.8 GHz", "888 MHz"};
    private static final String[] WORST_S = {"100% Saver", "100% Limit", "100% Heat", "100% Full", "Not applied", "Applying\u2026"};
    private static final float V2 = 15, S2 = 12;   // sp of the speed / the line below: just under "Close all" (16), next to the top chips (13)
    boolean stacked, compact, bare, tiny;   // compact: small label; tiny: small label above the value; bare: no label at all (dot + value)
    String name = "";   // stacked chips without a label: drawn small before the value ("CPU 1.7 GHz")
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
        if (compact && tiny && !bare) {   // two lines: small label over the value, same 40 dp height
            p.setTextSize(9f * d); p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); float tw = p.measureText(label);
            setMeasuredDimension(Math.round(2 * d + 7 * d + 4 * d + Math.max(tw, vw) + 2 * d), Math.round(40 * d)); return;
        }
        if (compact) { setMeasuredDimension(Math.round(2 * d + 7 * d + 4 * d + (bare ? 0 : lw + 3 * d) + vw + 2 * d), Math.round(40 * d)); return; }   // short, but still says what it is
        if (stacked && label.isEmpty()) {   // two lines, no dot: speed on the top edge, load + state on the bottom edge
            Typeface bold = Typeface.create("sans-serif-medium", Typeface.NORMAL), norm = Typeface.create("sans-serif", Typeface.NORMAL);
            p.setTypeface(bold); p.setTextSize(V2 * d);
            float nw = name.isEmpty() ? 0 : nameW(V2 * d);
            float cv = nw + p.measureText(value), wv = nw + Math.max(p.measureText(WORST_V[0]), p.measureText(WORST_V[1]));
            p.setTypeface(norm); p.setTextSize(S2 * d);
            float cs = p.measureText(sub), ws = 0;
            for (String t : WORST_S) ws = Math.max(ws, p.measureText(t));
            float content = Math.max(cv, cs), worst = Math.max(content, Math.max(wv, ws));   // one fixed size for the longest text ever shown
            int hh = hgt > 0 ? hgt : Math.round(48 * d);
            k = maxW > 0 ? Math.max(0.45f, Math.min(1f, (maxW - 2 * PAD * d) / worst)) : 1f;   // never wider than the room beside "Close all"
            k = Math.min(k, hh * 0.86f / ((1.06f * V2 + 1.24f * S2) * d));   // the block keeps a little air inside the button's height
            setMeasuredDimension(Math.round(2 * PAD * d + content * k), hh); return;
        }
        if (stacked) {
            p.setTextSize(14f * d); lw = p.measureText(label);
            p.setTextSize(18f * d); vw = p.measureText(value);
            p.setTextSize(13f * d); float sw = p.measureText(sub);
            float content = label.isEmpty() ? Math.max(13 * d + vw, sw) : Math.max(Math.max(13 * d + lw, vw), sw);
            // one fixed size for the longest text these chips ever show, so the font never jumps when the text changes
            p.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            p.setTextSize(18f * d); float wv = 13 * d + Math.max(p.measureText(WORST_V[0]), p.measureText(WORST_V[1]));
            if (label.isEmpty()) content = Math.max(content, 13 * d + p.measureText(value));
            p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));   // the line below is drawn in the normal face
            p.setTextSize(13f * d); float ws = 0; for (String t : WORST_S) ws = Math.max(ws, p.measureText(t));
            float worst = Math.max(content, Math.max(wv, ws));
            k = maxW > 0 ? Math.max(0.45f, Math.min(1f, (maxW - 2 * PAD * d) / worst)) : 1f;   // never wider than the room: no floor that lets it spill onto "Close all"
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
            if (label.isEmpty()) {   // no name, no dot: one tight two-line block, centred on "Close all", both lines on the same edge
                float vs = V2 * d * k, ss = S2 * d * k;
                float b1 = 0.76f * vs, b2 = b1 + 0.30f * vs + 1.0f * ss, block = b2 + 0.24f * ss;   // normal leading, not spread apart
                float top = (h - block) / 2f;
                p.setTextSize(vs);
                p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));   // Samsung's own weight, like "Close all"
                p.setColor(Color.WHITE);
                float vw2 = p.measureText(value);
                c.drawText(value, right ? x0 : x0 + (name.isEmpty() ? 0 : nameW(vs)), top + b1, p);
                if (!name.isEmpty()) {   // "CPU" / "GPU" small and grey, on the value's baseline
                    float nx = right ? x0 - vw2 - 4 * d * k : x0;
                    p.setTextSize(vs * 0.72f);
                    p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                    p.setColor(0xB3FFFFFF);
                    c.drawText(name, nx, top + b1, p);
                }
                p.setTextSize(ss);
                p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                p.setColor((subColor & 0x00FFFFFF) | 0xD9000000);   // the state colour, a little softer
                c.drawText(sub, x0, top + b2, p);
                p.clearShadowLayer();
                return;
            }
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
        c.drawCircle(in + 3.5f * d, tiny && !bare ? cy + 6f * d : cy, 3.5f * d, p);   // tiny: beside the value line
        p.setTextAlign(android.graphics.Paint.Align.LEFT);
        float x = in + 7 * d + (compact ? 4 : 5) * d;
        if (tiny && !bare) {   // small label on top, value below
            p.setTextSize(9f * d); p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            p.setColor(0x99FFFFFF);
            c.drawText(label, x, cy - 2.5f * d, p);
            fontValue();
            p.setColor(color == RecentsChip.GREEN || color == RecentsChip.CYAN || color == RecentsChip.AMBER || color == RecentsChip.RED ? Color.WHITE : color);
            c.drawText(value, x, cy + 10f * d, p);
            p.clearShadowLayer();
            return;
        }
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

    /** Width of the small name plus its gap, for a value drawn at text size vs. */
    private float nameW(float vs) {
        float ts = p.getTextSize(); Typeface tf = p.getTypeface();
        p.setTextSize(vs * 0.72f); p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        float w = p.measureText(name) + 4 * d;
        p.setTextSize(ts); p.setTypeface(tf);
        return w;
    }

    @Override public void setPressed(boolean pressed) { super.setPressed(pressed); invalidate(); }

    @Override public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) RecentsChip.kick();
    }
}
