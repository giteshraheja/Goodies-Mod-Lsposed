// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

/**
 * iOS-style back chevron drawn with a rounded stroke in the text color, sized and centred
 * to the digits/capitals of the current font (a glyph like ◀ looks heavy and sits too low).
 */
public final class ChevronSpan extends android.text.style.ReplacementSpan {
    private final android.graphics.Rect cap = new android.graphics.Rect();
    private final android.graphics.Path path = new android.graphics.Path();

    @Override public int getSize(android.graphics.Paint p, CharSequence t, int s, int e, android.graphics.Paint.FontMetricsInt fm) {
        return Math.round(p.getTextSize() * 0.62f); // chevron + a small gap before the name
    }

    @Override public void draw(android.graphics.Canvas c, CharSequence t, int s, int e, float x, int top, int y, int bottom, android.graphics.Paint p) {
        p.getTextBounds("H", 0, 1, cap);
        float h = cap.height() * 0.78f;           // chevron height ~ cap height
        float w = h * 0.5f;                       // half as wide as tall
        float cy = y + cap.exactCenterY();        // centred on capitals
        float stroke = Math.max(1.5f, p.getTextSize() * 0.11f);
        float left = x + stroke;
        android.graphics.Paint.Style st = p.getStyle();
        float sw = p.getStrokeWidth();
        android.graphics.Paint.Cap capStyle = p.getStrokeCap();
        android.graphics.Paint.Join join = p.getStrokeJoin();
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        path.reset();
        path.moveTo(left + w, cy - h / 2);
        path.lineTo(left, cy);
        path.lineTo(left + w, cy + h / 2);
        c.drawPath(path, p);
        p.setStyle(st);
        p.setStrokeWidth(sw);
        p.setStrokeCap(capStyle);
        p.setStrokeJoin(join);
    }

}
