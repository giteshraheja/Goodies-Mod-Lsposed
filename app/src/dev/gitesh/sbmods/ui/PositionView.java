// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import dev.gitesh.sbmods.Config;
import dev.gitesh.sbmods.ZoneLayout;

/**
 * A live mock of the top of the screen: the status bar with the camera hole. Drag the clock, the
 * temperature or the battery to a zone (left edge / left of the camera / right of the camera /
 * right edge) or onto the "Default" tray to put it back. The same ZoneLayout engine runs inside
 * SystemUI, so the preview is what you get. A tap (no drag) asks the host for a list instead.
 * Everything is drawn in "screen dp" units and scaled to the view width.
 */
final class PositionView extends View {
    interface Host {
        /** A zone changed (cfg already updated): save + send. */
        void changed();
        /** Element tapped without dragging: let the user pick a zone from a list. */
        void tapped(int element);
    }

    // layout, in units
    private static final float BAR_Y = 8, BAR_H = 36, LABEL_Y = 48, TRAY_Y = 68, TRAY_H = 30, TOTAL_H = 106;
    private static final float EDGE = 16, GAP = 6, HOLE_GAP = 8, TEXT = 15;

    private final Config cfg;
    private final Host host;
    private final int cAccent, cText, cSub, cField;
    private final float screenW, holeL, holeR;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private CharSequence clock = "10:04", temp = "34°";
    private int battery = 27;
    private boolean clockRight;
    private float scale = 1f;

    // element state
    private final float[] curX = new float[ZoneLayout.ELEMENTS];
    private final float[] curY = new float[ZoneLayout.ELEMENTS];
    private final boolean[] placed = new boolean[ZoneLayout.ELEMENTS];

    // drag state
    private int drag = -1;
    private int candidate = ZoneLayout.STOCK;
    private float fx, fy, downX, downY, grabDx;
    private boolean moved;
    private final int slop;

    PositionView(Context c, Config cfg, Host host, float screenW, float holeL, float holeR,
                 int accent, int text, int sub, int field) {
        super(c);
        this.cfg = cfg;
        this.host = host;
        this.screenW = screenW;
        this.holeL = holeL;
        this.holeR = holeR;
        cAccent = accent; cText = text; cSub = sub; cField = field;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tp.setColor(0xFFFFFFFF);
    }

    /** Which side Samsung's clock is on in the real status bar ("right" for Good Lock layouts). */
    void setClockRight(boolean right) { clockRight = right; }

    void setTexts(CharSequence clock, CharSequence temp, int batteryPct) {
        this.clock = clock == null ? "" : clock;
        this.temp = temp == null ? "" : temp;
        this.battery = batteryPct;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        scale = w / screenW;
        setMeasuredDimension(w, Math.round(TOTAL_H * scale));
    }

    // ------------------------------------------------------------ geometry

    private float width(int i) {
        if (i == ZoneLayout.CLOCK) return textW(clock, 1f);
        if (i == ZoneLayout.TEMP) return cfg.tempEnabled ? textW(temp, cfg.sizePct / 100f) : 0;
        if (i == ZoneLayout.BACK) return cfg.backEnabled ? backWidth() : 0;
        return 30; // battery icon
    }

    private float textW(CharSequence s, float k) {
        if (s == null || s.length() == 0) return 0;
        tp.setTextSize(TEXT * k);
        return tp.measureText(s, 0, s.length());
    }

    private int zoneOf(int i) {
        return i == drag ? candidate : getZone(i);
    }

    private int getZone(int i) {
        return i == ZoneLayout.CLOCK ? cfg.clockZone : i == ZoneLayout.TEMP ? cfg.tempZone
                : i == ZoneLayout.BATTERY ? cfg.batteryZone : cfg.backZone;
    }

    private void setZone(int i, int z) {
        if (i == ZoneLayout.CLOCK) cfg.clockZone = z; else if (i == ZoneLayout.TEMP) cfg.tempZone = z;
        else if (i == ZoneLayout.BATTERY) cfg.batteryZone = z; else cfg.backZone = z;
    }

    private float decoyRight, notifX = -1;
    private static final String BACK_APP = "WhatsApp";

    private String backText() {
        return "\u2039" + (BACK_APP.length() > cfg.backChars ? BACK_APP.substring(0, cfg.backChars) : BACK_APP);
    }

    private float backWidth() {
        tp.setTextSize(TEXT * 0.9f * cfg.backSizePct / 100f);
        if (cfg.backLive == 4) {   // Merge: a mock music capsule with the small back tab joined to its end
            float k = cfg.backSizePct / 100f;
            return CAP_W + (4 + 7 + 14 + 7) * k;
        }
        if (cfg.backStyle == 1) {
            String n = BACK_APP.length() > cfg.backChars ? BACK_APP.substring(0, cfg.backChars) : BACK_APP;
            float k = cfg.backSizePct / 100f;
            return (4 + 7 + 14 + 3 + 10) * k + tp.measureText(n);
        }
        return tp.measureText(backText()) + 16;
    }

    /**
     * Where each element wants to be (left x), in units. Same rules as the status bar hook:
     * what stays on Default closes up around what left, and items parked at an edge push that
     * side's content over.
     */
    private float[] targets() {
        int n = ZoneLayout.ELEMENTS;
        int[] zone = new int[n];
        float[] w = new float[n];
        for (int i = 0; i < n; i++) { zone[i] = zoneOf(i); w[i] = width(i); }
        float ze = cfg.edgeEff() >= 0 ? cfg.edgeEff() : EDGE;   // edge zones can hug the curved screen edge
        float ig = cfg.itemGap >= 0 ? cfg.itemGap : GAP;        // space between items
        float[] x = ZoneLayout.solve(zone, w, screenW, holeL, holeR, ze, ze, ig, HOLE_GAP);

        float pushL = 0, pushR = 0;
        int nl = 0, nr = 0;
        for (int i = 0; i < n; i++) {
            if (w[i] <= 0) continue;
            if (zone[i] == ZoneLayout.LEFT) { pushL += w[i]; nl++; }
            if (zone[i] == ZoneLayout.RIGHT) { pushR += w[i]; nr++; }
        }
        if (nl > 0) pushL += GAP * (nl - 1) + GAP;
        if (nr > 0) pushR += GAP * (nr - 1) + GAP;

        // right side, hugging the edge: battery (and the clock when Samsung puts it there)
        float se = cfg.edgeEff() >= 0 ? cfg.edgeEff() : EDGE;   // stock edge padding follows the setting too
        float rx = screenW - se - pushR;
        int[] right = clockRight ? new int[]{ZoneLayout.CLOCK, ZoneLayout.TEMP, ZoneLayout.BATTERY}
                : new int[]{ZoneLayout.BATTERY};
        for (int id : right) {
            if (w[id] <= 0 || zone[id] != ZoneLayout.STOCK) continue;
            rx -= w[id];
            x[id] = rx;
            boolean tempFollows = id == ZoneLayout.CLOCK && zone[ZoneLayout.TEMP] == ZoneLayout.STOCK && w[ZoneLayout.TEMP] > 0;
            rx -= tempFollows ? cfg.gapDp : (cfg.itemGap >= 0 ? cfg.itemGap : 8);
        }
        decoyRight = rx;

        // left side: clock + temperature when the clock is on the left, then notification icons
        float lx = se + pushL;
        if (!clockRight) {
            boolean before = cfg.labelPos == Config.POS_BEFORE;
            int[] left = before ? new int[]{ZoneLayout.TEMP, ZoneLayout.CLOCK} : new int[]{ZoneLayout.CLOCK, ZoneLayout.TEMP};
            for (int id : left) {
                if (w[id] <= 0 || zone[id] != ZoneLayout.STOCK) continue;
                x[id] = lx;
                lx += w[id] + (id == left[0] && w[left[1]] > 0 && zone[left[1]] == ZoneLayout.STOCK ? cfg.gapDp : (cfg.itemGap >= 0 ? cfg.itemGap : 8));
            }
        }
        if (w[ZoneLayout.BACK] > 0 && zone[ZoneLayout.BACK] == ZoneLayout.STOCK) { x[ZoneLayout.BACK] = lx; lx += w[ZoneLayout.BACK] + (cfg.itemGap >= 0 ? cfg.itemGap : 6); }
        notifX = lx;
        return x;
    }

    private float barCy() { return BAR_Y + BAR_H / 2; }

    /** Which zone a finger position means. */
    private int zoneAt(float x, float y) {
        if (y > TRAY_Y - 8) return ZoneLayout.STOCK;
        float mid = (holeL + holeR) / 2;
        if (x < holeL * 0.5f) return ZoneLayout.LEFT;
        if (x < mid) return ZoneLayout.HOLE_LEFT;
        if (x < mid + (screenW - holeR) * 0.5f) return ZoneLayout.HOLE_RIGHT;
        return ZoneLayout.RIGHT;
    }

    // ------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        c.save();
        c.scale(scale, scale);
        boolean dragging = drag >= 0;
        float cy = barCy();

        // bar
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0B0B0C);
        r.set(0, BAR_Y, screenW, BAR_Y + BAR_H);
        c.drawRoundRect(r, 12, 12, p);

        // zone highlights while dragging
        float mid = (holeL + holeR) / 2;
        float[][] zr = {
                {0, holeL * 0.5f}, {holeL * 0.5f, mid}, {mid, mid + (screenW - holeR) * 0.5f},
                {mid + (screenW - holeR) * 0.5f, screenW}};
        for (int z = 1; z <= 4; z++) {
            boolean hot = dragging && candidate == z;
            if (hot || dragging) {
                p.setColor(hot ? withAlpha(cAccent, 0x55) : withAlpha(0xFFFFFFFF, 0x10));
                r.set(zr[z - 1][0] + 1, BAR_Y + 2, zr[z - 1][1] - 1, BAR_Y + BAR_H - 2);
                c.drawRoundRect(r, 9, 9, p);
            }
        }

        // decoy system icons (Samsung's, not movable): signal + wifi, left of the stock battery slot
        float[] tx = targets();
        drawDecoys(c, decoyRight, cy);
        drawNotifs(c, notifX, cy);

        // camera hole
        p.setColor(0xFF000000);
        c.drawCircle((holeL + holeR) / 2, cy, 6f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f);
        p.setColor(0xFF2A2B2E);
        c.drawCircle((holeL + holeR) / 2, cy, 6f, p);
        p.setStyle(Paint.Style.FILL);

        // zone captions under the bar
        tp.setTextSize(9f);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tp.setTextAlign(Paint.Align.CENTER);
        String[] names = {"Far left", "Left of camera", "Right of camera", "Far right"};
        for (int z = 1; z <= 4; z++) {
            boolean hot = dragging && candidate == z;
            tp.setColor(hot ? cAccent : withAlpha(cSub, dragging ? 0xFF : 0x99));
            c.drawText(names[z - 1], (zr[z - 1][0] + zr[z - 1][1]) / 2, LABEL_Y + 10, tp);
        }

        // default tray
        boolean trayHot = dragging && candidate == ZoneLayout.STOCK && fy > TRAY_Y - 8;
        r.set(0, TRAY_Y, screenW, TRAY_Y + TRAY_H);
        p.setColor(trayHot ? withAlpha(cAccent, 0x33) : withAlpha(cField, 0xFF));
        c.drawRoundRect(r, 10, 10, p);
        tp.setTextSize(10f);
        tp.setColor(trayHot ? cAccent : cSub);
        String hint = dragging ? "Drop here to put it back in Samsung's place" : "Drag an item to move it · tap it to pick from a list";
        c.drawText(hint, screenW / 2, TRAY_Y + TRAY_H / 2 + 3.5f, tp);

        // elements (dragged one last, on top)
        boolean animating = false;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < ZoneLayout.ELEMENTS; i++) {
                if ((i == drag) != (pass == 1)) continue;
                float w = width(i);
                if (w <= 0) continue;
                float gx, gy;
                if (i == drag) { gx = fx - grabDx; gy = fy; curX[i] = gx; curY[i] = gy; placed[i] = true; }
                else {
                    float ty = cy;
                    if (!placed[i]) { curX[i] = tx[i]; curY[i] = ty; placed[i] = true; }
                    curX[i] += (tx[i] - curX[i]) * 0.35f;
                    curY[i] += (ty - curY[i]) * 0.35f;
                    if (Math.abs(tx[i] - curX[i]) > 0.3f || Math.abs(ty - curY[i]) > 0.3f) animating = true;
                    else { curX[i] = tx[i]; curY[i] = ty; }
                    gx = curX[i]; gy = curY[i];
                }
                drawChip(c, i, gx, gy, w, i == drag);
            }
        }
        c.restore();
        if (animating) postInvalidateOnAnimation();
    }

    private void drawChip(Canvas c, int i, float x, float cy, float w, boolean lifted) {
        // handle behind the item: faint, so it reads as something you can grab
        r.set(x - 4, cy - 11, x + w + 4, cy + 11);
        p.setStyle(Paint.Style.FILL);
        p.setColor(lifted ? withAlpha(cAccent, 0x66) : 0x22FFFFFF);
        if (i != ZoneLayout.BACK || lifted) c.drawRoundRect(r, 11, 11, p);
        if (lifted) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.4f);
            p.setColor(cAccent);
            c.drawRoundRect(r, 11, 11, p);
            p.setStyle(Paint.Style.FILL);
        }
        if (i == ZoneLayout.BATTERY) { drawBattery(c, x, cy); return; }
        if (i == ZoneLayout.BACK) { drawBackChip(c, x, cy, w); return; }
        float k = i == ZoneLayout.TEMP ? cfg.sizePct / 100f : 1f;
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tp.setTextSize(TEXT * k);
        int col = 0xFFFFFFFF;
        if (i == ZoneLayout.TEMP && cfg.warnAt > 0) {
            try {
                String digits = temp.toString().replaceAll("[^0-9]", "");
                if (!digits.isEmpty() && Integer.parseInt(digits.length() > 2 ? digits.substring(0, 2) : digits) >= cfg.warnAt)
                    col = cfg.warnColor;
            } catch (Exception ignored) {}
        }
        tp.setColor(col);
        CharSequence s = i == ZoneLayout.CLOCK ? clock : temp;
        c.drawText(s, 0, s.length(), x, cy + TEXT * k * 0.36f, tp);
    }

    private void drawBattery(Canvas c, float x, float cy) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.3f);
        p.setColor(0xFFFFFFFF);
        r.set(x, cy - 6, x + 26, cy + 6);
        c.drawRoundRect(r, 3.5f, 3.5f, p);
        p.setStyle(Paint.Style.FILL);
        r.set(x + 27.2f, cy - 2.5f, x + 29f, cy + 2.5f);
        c.drawRoundRect(r, 1f, 1f, p);
        p.setColor(battery <= 15 ? 0xFFFF5252 : 0xFFFFFFFF);
        float fill = Math.max(2f, 23f * Math.min(100, Math.max(0, battery)) / 100f);
        r.set(x + 1.5f, cy - 4.5f, x + 1.5f + fill, cy + 4.5f);
        c.drawRoundRect(r, 2.2f, 2.2f, p);
    }

    /** The back button, in the chosen look (capsule like One UI live activities, or classic). */
    private static final float CAP_W = 62;

    private void drawBackChip(Canvas c, float x, float cy, float w) {
        p.setStyle(Paint.Style.FILL);
        if (cfg.backLive == 4) { drawMerge(c, x, cy, w); return; }
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        float bs = TEXT * 0.9f * cfg.backSizePct / 100f;
        tp.setTextSize(bs);
        tp.setColor(0xFFFFFFFF);
        if (cfg.backStyle == 1) {
            float k = cfg.backSizePct / 100f;
            float hh = Math.max(14f * k + 3f * k, bs + 3f * k) / 2f;
            p.setColor(0xFF1F5A34);                       // app-coloured pill (WhatsApp green in the demo)
            r.set(x, cy - hh, x + w, cy + hh);
            c.drawRoundRect(r, hh, hh, p);
            p.setStyle(Paint.Style.STROKE);                // chevron
            p.setStrokeWidth(Math.max(1f, 1.7f * k));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(0xFFFFFFFF);
            android.graphics.Path ch = new android.graphics.Path();
            ch.moveTo(x + 4 * k + 4.2f * k, cy - 3.4f * k); ch.lineTo(x + 4 * k + 1.8f * k, cy); ch.lineTo(x + 4 * k + 4.2f * k, cy + 3.4f * k);
            c.drawPath(ch, p);
            p.setStyle(Paint.Style.FILL);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setColor(0xFF25D366);                       // the app's icon, in a circle
            c.drawCircle(x + (4 + 7 + 7) * k, cy, 7f * k, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(x + (4 + 7 + 7) * k, cy, 2.6f * k, p);
            String n = BACK_APP.length() > cfg.backChars ? BACK_APP.substring(0, cfg.backChars) : BACK_APP;
            c.drawText(n, x + (4 + 7 + 14 + 3) * k, cy + bs * 0.36f, tp);
        } else {
            p.setColor(0x33FFFFFF);
            r.set(x, cy - 10.5f, x + w, cy + 10.5f);
            c.drawRoundRect(r, 10.5f, 10.5f, p);
            c.drawText(backText(), x + 8, cy + bs * 0.36f, tp);
        }
    }

    /** Merge look: the live activity capsule with a small back tab (chevron + app icon, no name) joined to its right end. */
    private void drawMerge(Canvas c, float x, float cy, float w) {
        float k = cfg.backSizePct / 100f, h = 9.5f;
        float tabX = x + CAP_W - 11;
        p.setColor(0xFF1F5A34);                              // the tab, in the previous app's colour
        r.set(tabX, cy - h * k, x + w, cy + h * k);
        c.drawRoundRect(r, h * k, h * k, p);
        p.setColor(0xFFA00000);                              // the music capsule, on top
        r.set(x, cy - 10, x + CAP_W, cy + 10);
        c.drawRoundRect(r, 10, 10, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(x + 10, cy, 4.5f, p);
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tp.setColor(0xFFFFFFFF);
        tp.setTextSize(11f);
        c.drawText("Do Pall", x + 18, cy + 4f, tp);
        p.setStyle(Paint.Style.STROKE);                      // chevron
        p.setStrokeWidth(Math.max(1f, 1.7f * k));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float cx0 = x + CAP_W + 1;
        android.graphics.Path ch = new android.graphics.Path();
        ch.moveTo(cx0 + 4.2f * k, cy - 3.4f * k); ch.lineTo(cx0 + 1.8f * k, cy); ch.lineTo(cx0 + 4.2f * k, cy + 3.4f * k);
        c.drawPath(ch, p);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setColor(0xFF25D366);
        c.drawCircle(cx0 + (4 + 7) * k + 7 * k, cy, 7f * k, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(cx0 + (4 + 7) * k + 7 * k, cy, 2.6f * k, p);
    }

    /** Two notification dots, so the left side feels like a real status bar and shows being pushed. */
    private void drawNotifs(Canvas c, float x, float cy) {
        if (x + 24 > holeL - 4) return;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55FFFFFF);
        c.drawCircle(x + 6, cy, 4.5f, p);
        c.drawCircle(x + 19, cy, 4.5f, p);
    }

    private void drawDecoys(Canvas c, float right, float cy) {
        if (right - 30 < holeR + 4 && right > holeL) return; // no room next to the camera hole
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x66FFFFFF);
        float x = right - 18;
        for (int b = 0; b < 4; b++) {            // signal bars
            float h = 3 + b * 2.3f;
            r.set(x + b * 4.2f, cy + 5 - h, x + b * 4.2f + 2.6f, cy + 5);
            c.drawRoundRect(r, 0.8f, 0.8f, p);
        }
        x -= 12;
        p.setStyle(Paint.Style.STROKE);          // wifi
        p.setStrokeWidth(1.4f);
        for (int a = 1; a <= 3; a++) {
            r.set(x + 6 - a * 3.4f, cy + 4 - a * 3.4f, x + 6 + a * 3.4f, cy + 4 + a * 3.4f);
            c.drawArc(r, 225, 90, false, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static int withAlpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    // ------------------------------------------------------------ touch

    /** The item under the finger: one you touch directly wins over a near miss; ties go to the closest centre. */
    private int hit(float x, float y) {
        float cy = barCy();
        float[] tx = targets();
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) {
            float w = width(i);
            if (w <= 0) continue;
            float ex = placed[i] ? curX[i] : tx[i];
            float ey = placed[i] ? curY[i] : cy;
            if (Math.abs(y - ey) > 14) continue;
            float dx = x < ex ? ex - x : x > ex + w ? x - (ex + w) : 0;
            if (dx > 6) continue;                              // 6 units of slack for small items
            float score = dx * 1000 + Math.abs(x - (ex + w / 2));
            if (score < bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() / scale, y = e.getY() / scale;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int i = hit(x, y);
                if (i < 0) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                drag = i;
                candidate = getZone(i);
                downX = e.getX(); downY = e.getY();
                grabDx = x - curX[i];
                fx = x; fy = y;
                moved = false;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (drag < 0) return false;
                if (!moved && Math.abs(e.getX() - downX) < slop && Math.abs(e.getY() - downY) < slop) return true;
                moved = true;
                fx = x; fy = Math.max(BAR_Y + 4, Math.min(TOTAL_H - 8, y));
                int z = zoneAt(x, y);
                if (z != candidate) { candidate = z; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int i = drag;
                if (i < 0) return false;
                drag = -1;
                if (moved) {
                    int before = getZone(i);
                    setZone(i, candidate);
                    if (before != candidate) { performHapticFeedback(HapticFeedbackConstants.CONFIRM); host.changed(); }
                } else host.tapped(i);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                drag = -1;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}
