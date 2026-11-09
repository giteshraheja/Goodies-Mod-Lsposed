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
import dev.gitesh.sbmods.ZoneLayout.Plan;

/**
 * A live mock of the top of the screen: the status bar with the camera hole. Drag any item anywhere along
 * the bar: it follows your finger, then settles against the nearest screen edge, the camera, or the side of
 * another item (and then stays stuck to it). Drop it on the grey tray to put it back in Samsung's place.
 * The same ZoneLayout engine runs inside SystemUI, so the preview is what you get. A tap (no drag) asks the
 * host for a list instead. Everything is drawn in "screen dp" units and scaled to the view width.
 */
final class PositionView extends View {
    interface Host {
        /** A position changed (cfg already updated): save + send. */
        void changed();
        /** Element tapped without dragging: let the user pick a spot from a list. */
        void tapped(int element);
    }

    // layout, in units
    private static final float BAR_Y = 8, BAR_H = 36, TRAY_Y = 54, TRAY_H = 28, TOTAL_H = 92;
    private static final float EDGE = 16, GAP = 6, HOLE_GAP = 8, TEXT = 15, SNAP = 10;

    private final Config cfg;
    private final Host host;
    private final int cAccent, cText, cSub, cField;
    private final float screenW, holeL, holeR;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private CharSequence clock = "10:04", temp = "34°", date = "";
    private int battery = 27;
    private boolean clockRight;
    private float scale = 1f;

    // element state (animated positions)
    private final float[] curX = new float[ZoneLayout.ELEMENTS];
    private final float[] curY = new float[ZoneLayout.ELEMENTS];
    private final boolean[] placed = new boolean[ZoneLayout.ELEMENTS];

    // drag state
    private int drag = -1;
    private Plan cand;            // the plan as it would be if dropped now
    private float snapGuide = Float.NaN;   // x of the guide line while an edge / neighbour snap is active
    private float fx, fy, downX, downY, grabDx;
    private boolean moved, overTray;
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

    void setDate(CharSequence d) { date = d == null ? "" : d; }

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
        if (i == ZoneLayout.DATE) return cfg.dateSeparate ? textW(date, 1f) : 0;
        if (i == ZoneLayout.NETSPEED) return 30;
        return 30; // battery icon
    }

    private float textW(CharSequence s, float k) {
        if (s == null || s.length() == 0) return 0;
        tp.setTextSize(TEXT * k);
        return tp.measureText(s, 0, s.length());
    }

    private float edge() { return cfg.edgeEff() >= 0 ? cfg.edgeEff() : EDGE; }
    private float gap() { return cfg.itemGap >= 0 ? cfg.itemGap : GAP; }

    /** The plan that is drawn: the real one, or the would-be one while dragging. Hidden items are in Samsung's place. */
    private Plan live() {
        Plan pl = (cand != null ? cand : cfg.plan).copy();
        if (!cfg.dateSeparate) pl.mode[ZoneLayout.DATE] = ZoneLayout.STOCK;
        // same as the status bar: stuck to an item that isn't showing -> back in Samsung's place (the back button's spot is kept)
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++) {
            int t = pl.to[i];
            if (pl.mode[i] == ZoneLayout.LINK && t != ZoneLayout.BACK && t >= 0 && t < ZoneLayout.ELEMENTS && width(t) <= 0) pl.mode[i] = ZoneLayout.STOCK;
        }
        return pl;
    }

    private float[] widths() {
        float[] w = new float[ZoneLayout.ELEMENTS];
        for (int i = 0; i < w.length; i++) w[i] = width(i);
        return w;
    }

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

    private float decoyRight, notifX = -1, notifClip, decoyClip;

    /**
     * Where each element wants to be (left x), in units. Same rules as the status bar hook:
     * placed items sit where the plan says, Samsung's icons make room, and what stays in
     * Samsung's place closes up around them.
     */
    private float[] targets() {
        Plan pl = live();
        float[] w = widths();
        float e = edge(), g = gap();
        float[] nat = natural(pl, w, e);
        float[] x = ZoneLayout.solve(pl, w, screenW, holeL, holeR, HOLE_GAP, e, e, g, nat);
        float leftBase = e;
        if (!Float.isNaN(nat[ZoneLayout.BACK]) && w[ZoneLayout.BACK] > 0) leftBase = e + w[ZoneLayout.BACK] + g;
        float[] ln = ZoneLayout.lanes(x, w, screenW, holeL, holeR, e, e, g, HOLE_GAP, 14, leftBase);
        notifClip = ln[1]; decoyClip = ln[3];

        float rx = ln[2];
        int[] right = clockRight ? (cfg.dateAfterTime ? new int[]{ZoneLayout.DATE, ZoneLayout.CLOCK, ZoneLayout.TEMP, ZoneLayout.BATTERY}
                        : new int[]{ZoneLayout.CLOCK, ZoneLayout.DATE, ZoneLayout.TEMP, ZoneLayout.BATTERY})
                : new int[]{ZoneLayout.BATTERY};
        for (int id : right) {
            if (w[id] <= 0 || pl.mode[id] != ZoneLayout.STOCK) continue;
            rx -= w[id];
            x[id] = rx;
            boolean follows = (id == ZoneLayout.CLOCK || id == ZoneLayout.DATE) && w[ZoneLayout.TEMP] > 0 && pl.mode[ZoneLayout.TEMP] == ZoneLayout.STOCK;
            rx -= follows ? cfg.gapDp : (cfg.itemGap >= 0 ? cfg.itemGap : 8);
        }
        decoyRight = rx;

        float lx = ln[0];
        if (!clockRight) {
            boolean before = cfg.labelPos == Config.POS_BEFORE;
            int[] ct = cfg.dateAfterTime ? new int[]{ZoneLayout.CLOCK, ZoneLayout.DATE} : new int[]{ZoneLayout.DATE, ZoneLayout.CLOCK};
            int[] left = before ? new int[]{ZoneLayout.TEMP, ct[0], ct[1]} : new int[]{ct[0], ct[1], ZoneLayout.TEMP};
            for (int id : left) {
                if (w[id] <= 0 || pl.mode[id] != ZoneLayout.STOCK) continue;
                x[id] = lx;
                lx += w[id] + (id == ZoneLayout.TEMP || id == left[left.length - 1] ? (cfg.itemGap >= 0 ? cfg.itemGap : 8) : cfg.gapDp);
            }
        }
        if (w[ZoneLayout.BACK] > 0 && pl.mode[ZoneLayout.BACK] == ZoneLayout.STOCK) { x[ZoneLayout.BACK] = e; lx = Math.max(lx, leftBase); }
        notifX = lx;
        if (w[ZoneLayout.NETSPEED] > 0 && pl.mode[ZoneLayout.NETSPEED] == ZoneLayout.STOCK) x[ZoneLayout.NETSPEED] = decoyRight - 34 - w[ZoneLayout.NETSPEED];
        for (int i = 0; i < x.length; i++) if (Float.isNaN(x[i])) x[i] = 0;
        return x;
    }

    private float barCy() { return BAR_Y + BAR_H / 2; }

    /** Where items that stay in Samsung's place are: only the back button matters (others can be stuck to it). */
    private float[] natural(Plan pl, float[] w, float e) {
        float[] nat = new float[ZoneLayout.ELEMENTS];
        java.util.Arrays.fill(nat, Float.NaN);
        if (pl.mode[ZoneLayout.BACK] == ZoneLayout.STOCK) nat[ZoneLayout.BACK] = e;   // also without a back button: things stuck to it sit at the far left
        return nat;
    }

    // ------------------------------------------------------------ placing

    private boolean dependsOn(Plan pl, int a, int b) {   // does a (through links) hang on b?
        int guard = 0;
        while (a >= 0 && pl.mode[a] == ZoneLayout.LINK && guard++ < 8) {
            if (pl.to[a] == b) return true;
            a = pl.to[a];
        }
        return false;
    }

    /** Item i leaves its place: whatever was stuck to it closes the gap (takes over i's spot). */
    private void release(Plan pl, int i, float[] x, float[] w) {
        java.util.ArrayList<Integer> deps = new java.util.ArrayList<>();
        for (int k = 0; k < ZoneLayout.ELEMENTS; k++)
            if (k != i && pl.mode[k] == ZoneLayout.LINK && pl.to[k] == i) deps.add(k);
        if (deps.isEmpty()) return;
        if (pl.mode[i] == ZoneLayout.LINK) {
            int t = pl.to[i], s = pl.side[i];
            for (int k : deps) { pl.to[k] = t; pl.side[k] = s; }
        } else if (pl.mode[i] == ZoneLayout.FREE) {
            int a = pl.right[i], d = (a == 0 || a == 3) ? 1 : 0;      // the way the row grows from this anchor
            int head = -1;
            for (int k : deps) if (pl.side[k] == d) { head = k; break; }
            if (head < 0) head = deps.get(0);
            for (int k : deps) if (k != head) { pl.to[k] = head; pl.side[k] = d; }   // the rest line up behind the new head, growing the same way
            pl.mode[head] = ZoneLayout.FREE; pl.right[head] = a; pl.off[head] = pl.off[i];   // keeps the distance the moved item had
        } else if (i == ZoneLayout.BACK) {
            // the back button in Samsung's place: whatever was stuck to it takes its spot at the left edge, keeping its order
            int head = deps.get(0);
            for (int k : deps) if (k != head) { pl.to[k] = head; pl.side[k] = 1; }
            pl.mode[head] = ZoneLayout.FREE; pl.right[head] = 0; pl.off[head] = 0;
        } else {
            for (int k : deps) pl.mode[k] = ZoneLayout.STOCK;   // nothing else can be stuck to an item in Samsung's place
        }
    }

    /** Where item i, with its left side at "left", settles: the nearest slot (an edge, the camera, or the side of another item). */
    private Plan snap(int i, float left) {
        Plan pl = cfg.plan.copy();
        if (!cfg.dateSeparate) pl.mode[ZoneLayout.DATE] = ZoneLayout.STOCK;
        float[] w = widths();
        float e = edge(), g = gap();
        float[] nat = natural(pl, w, e);
        float[] x = ZoneLayout.solve(pl, w, screenW, holeL, holeR, HOLE_GAP, e, e, g, nat);
        if (!Float.isNaN(nat[ZoneLayout.BACK])) x[ZoneLayout.BACK] = nat[ZoneLayout.BACK];
        float wi = w[i];
        Plan after = pl.copy();
        release(after, i, x, w);
        float best = Float.MAX_VALUE; int kind = -1, arg = 0;   // kinds: 0 left edge, 1 right edge, 2 camera-left, 3 camera-right, 4 link
        float guide = Float.NaN, d;
        float[] land = {e, screenW - e - wi, holeL - HOLE_GAP - wi, holeR + HOLE_GAP};
        float[] gd = {e, screenW - e, holeL - HOLE_GAP, holeR + HOLE_GAP};
        for (int k = 0; k < 4; k++) {
            if (land[k] < 0 || land[k] + wi > screenW) continue;
            if ((d = Math.abs(left - land[k])) < best) { best = d; kind = k; guide = gd[k]; }
        }
        for (int j = 0; j < ZoneLayout.ELEMENTS; j++) {
            if (j == i || w[j] <= 0 || Float.isNaN(x[j]) || dependsOn(after, j, i)) continue;
            float ll = x[j] - g - wi, lr = x[j] + w[j] + g;
            if (ll >= 0 && ll + wi <= screenW && (d = Math.abs(left - ll)) < best) { best = d; kind = 4; arg = j * 2; guide = x[j] - g / 2; }
            if (lr >= 0 && lr + wi <= screenW && (d = Math.abs(left - lr)) < best) { best = d; kind = 4; arg = j * 2 + 1; guide = x[j] + w[j] + g / 2; }
        }
        snapGuide = guide;
        if (kind == 4) link(after, i, arg / 2, arg % 2);
        else if (kind >= 0) edgePlace(after, i, kind);
        return after;
    }

    /** Put i at a screen edge / beside the camera; anything already sitting there is nudged inward next to it. */
    private void edgePlace(Plan pl, int i, int kind) {
        int a = kind == 0 ? 0 : kind == 1 ? 1 : kind == 2 ? 2 : 3;
        int inward = (a == 0 || a == 3) ? 1 : 0;       // side of i where the rest of the row goes
        for (int k = 0; k < ZoneLayout.ELEMENTS; k++)
            if (k != i && pl.mode[k] == ZoneLayout.FREE && pl.right[k] == a && pl.off[k] == 0) {
                pl.mode[k] = ZoneLayout.LINK; pl.to[k] = i; pl.side[k] = inward;
            }
        pl.mode[i] = ZoneLayout.FREE; pl.right[i] = a; pl.off[i] = 0;
    }

    /** Stick i to the side of j (0 = left of it, 1 = right of it); what was stuck on that side moves on to i. */
    private void link(Plan pl, int i, int j, int side) {
        for (int k = 0; k < ZoneLayout.ELEMENTS; k++)
            if (k != i && pl.mode[k] == ZoneLayout.LINK && pl.to[k] == j && pl.side[k] == side && !dependsOn(pl, j, k)) pl.to[k] = i;
        pl.mode[i] = ZoneLayout.LINK; pl.to[i] = j; pl.side[i] = side;
    }

    /** Called by the list picker: 0 Samsung's place, 1 far left, 2 left of camera, 3 right of camera, 4 far right, 10+2j / 11+2j left / right of item j. */
    void place(int i, int choice) {
        Plan pl = cfg.plan.copy();
        float[] w = widths();
        float e = edge(), g = gap();
        float[] x = ZoneLayout.solve(live(), w, screenW, holeL, holeR, HOLE_GAP, e, e, g);
        release(pl, i, x, w);
        if (choice == 0) pl.mode[i] = ZoneLayout.STOCK;
        else if (choice >= 1 && choice <= 4) edgePlace(pl, i, choice == 1 ? 0 : choice == 2 ? 2 : choice == 3 ? 3 : 1);
        else if (choice >= 10) {
            int j = (choice - 10) / 2, side = (choice - 10) % 2;
            if (j != i && !dependsOn(pl, j, i)) link(pl, i, j, side);
        }
        cfg.plan = pl;
        invalidate();
        host.changed();
    }

    /** Which items could item i be stuck to (placed, showing, not already hanging on i). */
    boolean canLinkTo(int i, int j) {
        return j != i && width(j) > 0 && (cfg.plan.mode[j] != ZoneLayout.STOCK || j == ZoneLayout.BACK) && !dependsOn(cfg.plan, j, i)
                && !(j == ZoneLayout.DATE && !cfg.dateSeparate);
    }

    // ------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        c.save();
        c.scale(scale, scale);
        boolean dragging = drag >= 0 && moved;
        float cy = barCy();

        // bar
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0B0B0C);
        r.set(0, BAR_Y, screenW, BAR_Y + BAR_H);
        c.drawRoundRect(r, 12, 12, p);

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

        // snap guide
        if (dragging && !Float.isNaN(snapGuide) && !overTray) {
            p.setColor(withAlpha(cAccent, 0xCC));
            c.drawRect(snapGuide - 0.8f, BAR_Y + 3, snapGuide + 0.8f, BAR_Y + BAR_H - 3, p);
        }

        // tray
        r.set(0, TRAY_Y, screenW, TRAY_Y + TRAY_H);
        p.setColor(overTray ? withAlpha(cAccent, 0x33) : withAlpha(cField, 0xFF));
        c.drawRoundRect(r, 10, 10, p);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tp.setTextSize(10f);
        tp.setColor(overTray ? cAccent : cSub);
        String hint = dragging ? "Drop here to put it back in Samsung's place"
                : "Drag an item and drop it at an edge, by the camera, or between items";
        c.drawText(hint, screenW / 2, TRAY_Y + TRAY_H / 2 + 3.5f, tp);

        // elements (dragged one last, on top)
        boolean animating = false;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < ZoneLayout.ELEMENTS; i++) {
                if ((i == drag) != (pass == 1)) continue;
                float w = width(i);
                if (w <= 0) continue;
                float gx, gy;
                if (i == drag && moved) { gx = fx - grabDx; gy = fy; curX[i] = gx; curY[i] = gy; placed[i] = true; }
                else {
                    float ty = cy;
                    if (!placed[i]) { curX[i] = tx[i]; curY[i] = ty; placed[i] = true; }
                    curX[i] += (tx[i] - curX[i]) * 0.4f;
                    curY[i] += (ty - curY[i]) * 0.4f;
                    if (Math.abs(tx[i] - curX[i]) > 0.3f || Math.abs(ty - curY[i]) > 0.3f) animating = true;
                    else { curX[i] = tx[i]; curY[i] = ty; }
                    gx = curX[i]; gy = curY[i];
                }
                drawChip(c, i, gx, gy, w, i == drag && moved);
            }
        }
        c.restore();
        if (animating) postInvalidateOnAnimation();
    }

    private void drawChip(Canvas c, int i, float x, float cy, float w, boolean lifted) {
        // handle behind the item: faint, so it reads as something you can grab
        r.set(x - 1.5f, cy - 11, x + w + 1.5f, cy + 11);
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
        if (i == ZoneLayout.NETSPEED) {
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tp.setColor(0xFFFFFFFF);
            tp.setTextSize(9f);
            c.drawText("12.3", x + w / 2, cy - 1f, tp);
            c.drawText("K/s", x + w / 2, cy + 8f, tp);
            return;
        }
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
        CharSequence s = i == ZoneLayout.CLOCK ? clock : i == ZoneLayout.DATE ? date : temp;
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
        if (x + 24 > Math.min(holeL - 4, notifClip + 2)) return;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55FFFFFF);
        c.drawCircle(x + 6, cy, 4.5f, p);
        c.drawCircle(x + 19, cy, 4.5f, p);
    }

    private void drawDecoys(Canvas c, float right, float cy) {
        if (right - 30 < Math.max(holeR + 4, decoyClip - 2) && right > holeL) return; // no room next to the camera hole / placed items
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
                downX = e.getX(); downY = e.getY();
                grabDx = x - curX[i];
                fx = x; fy = y;
                moved = false; overTray = false; cand = null; snapGuide = Float.NaN;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (drag < 0) return false;
                if (!moved && Math.abs(e.getX() - downX) < slop && Math.abs(e.getY() - downY) < slop) return true;
                moved = true;
                fx = x; fy = Math.max(BAR_Y + 4, Math.min(TOTAL_H - 8, y));
                boolean tray = y > TRAY_Y - 6;
                if (tray != overTray) { overTray = tray; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
                Plan before = cand;
                if (tray) {
                    cand = cfg.plan.copy();
                    float[] w = widths();
                    float[] xs = ZoneLayout.solve(live(), w, screenW, holeL, holeR, HOLE_GAP, edge(), edge(), gap());
                    release(cand, drag, xs, w);
                    cand.mode[drag] = ZoneLayout.STOCK;
                    snapGuide = Float.NaN;
                } else {
                    cand = snap(drag, x - grabDx);
                }
                if (before != null && !samePlan(before, cand)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int i = drag;
                if (i < 0) return false;
                drag = -1;
                if (moved && cand != null) {
                    boolean changed = !samePlan(cfg.plan, cand);
                    cfg.plan = cand;
                    cand = null;
                    if (changed) { performHapticFeedback(HapticFeedbackConstants.CONFIRM); host.changed(); }
                } else if (!moved) host.tapped(i);
                cand = null; overTray = false; moved = false;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                drag = -1; cand = null; overTray = false; moved = false;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private static boolean samePlan(Plan a, Plan b) {
        for (int i = 0; i < ZoneLayout.ELEMENTS; i++)
            if (a.mode[i] != b.mode[i] || a.right[i] != b.right[i] || a.off[i] != b.off[i] || a.to[i] != b.to[i] || a.side[i] != b.side[i]) return false;
        return true;
    }
}
