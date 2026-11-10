// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.gitesh.sbmods.PanelLayout;
import dev.gitesh.sbmods.Ui;

import static dev.gitesh.sbmods.hook.PanelCards.dp;
import static dev.gitesh.sbmods.hook.PanelCards.tv;

/**
 * The Performance popup: a frosted sheet at the bottom of the Overview screen with a header, a page switcher
 * (All / Speed / Battery / Memory) and the cards of the current page (see PanelCards). Shown inside the launcher's
 * process, so everything here is guarded: a failing card is skipped, never a crash.
 */
final class PerfPanel {
    private PerfPanel() {}

    static android.app.Dialog panel;
    static long pChangedAt;
    private static int page = PanelLayout.PAGE_SPEED;   // the open page: read from the setting when the panel opens, written when the owner switches
    private static List<String> allOrder;   // the cards the All page showed when it was built: kept while it is open, so nothing moves under the finger
    private static String helpOpen;   // id of the card whose help is open (one at a time)
    private static final List<TextView> helps = new ArrayList<>();
    private static Activity pAct;
    static LinearLayout pBox;
    private static LinearLayout pSwitch;
    private static ScrollView pScroll;
    private static TextView pHead, pWarn;
    private static final List<PanelCards.Card> shown = new ArrayList<>();
    private static final List<String> sigs = new ArrayList<>();
    private static boolean rebuilding;

    static boolean showing() { return panel != null && panel.isShowing(); }

    /** Tap on the CPU / GPU chip. */
    static void showPanel(Activity a) {
        try { show(a); } catch (Throwable t) { Log.w("performance panel: " + t); }
    }

    private static void show(Activity a) {
        PanelCards.theme(a);   // the system's day / night setting decides the colours
        final Ui.Pal pal = Ui.cur;
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 12));
        final Ui.Glass bg = Ui.panelGlass(a, pal, pal.sheet, pal.sheetBd, Ui.R_SHEET);
        col.setBackground(bg);

        // the usual bottom-sheet handle (only a cue, no gesture)
        View grab = new View(a);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor((pal.tx2 & 0x00FFFFFF) | 0x99000000);
        gb.setCornerRadius(dp(a, 3));
        grab.setBackground(gb);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(a, 40), dp(a, 5));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = dp(a, 8);
        col.addView(grab, gp);

        // header: title left; battery temperature, power draw and (when a limit is on) its end on the right
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(PanelCards.tvm(a, "Performance", Ui.T_PANEL, pal.tx), new LinearLayout.LayoutParams(-2, -2));   // the title never wraps: the facts on the right take what is left
        pHead = tv(a, "", 13, pal.tx2, false);
        pHead.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        pHead.setMaxLines(1);   // one line always: a long line (Cool-down, hours left) shrinks a little instead of wrapping
        pHead.setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        pHead.setFontFeatureSettings("tnum");
        pHead.setPadding(dp(a, 10), 0, 0, 0);
        head.addView(pHead, new LinearLayout.LayoutParams(0, -1, 1));   // as tall as the title, not its own text: a shrunk line can then grow back to 13sp
        col.addView(head);

        int saved = Global.gint(a, PanelLayout.TAB, PanelLayout.PAGE_SPEED);   // the page that was open last
        page = saved < 0 || saved >= PanelLayout.PAGE_NAMES.length ? PanelLayout.PAGE_SPEED : saved;
        allOrder = null; helpOpen = null;

        pSwitch = new LinearLayout(a);
        pSwitch.setPadding(0, dp(a, 10), 0, dp(a, 8));
        col.addView(pSwitch);
        buildSwitch(a);

        pWarn = tv(a, "The limit isn't being applied: the root helper seems to be off. Turn it on in StatusBar Mods › System.", 13, pal.err, false);
        pWarn.setPadding(0, dp(a, 6), 0, 0);
        pWarn.setVisibility(View.GONE);
        col.addView(pWarn);

        // the whole sheet may use the screen between the status bar and the navigation bar (less a small gap on top):
        // the card area gets what the header and tabs leave. A fixed share of the screen cut the last card of the Speed page.
        int room = Math.round(a.getResources().getDisplayMetrics().heightPixels * 0.88f);
        try {
            android.view.WindowMetrics m = a.getWindowManager().getCurrentWindowMetrics();
            android.graphics.Insets in = m.getWindowInsets().getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            room = m.getBounds().height() - in.top - in.bottom - dp(a, 8);
        } catch (Throwable t) { Log.w("panel room: " + t); }
        final int cap = room;
        pScroll = new ScrollView(a) {
            private int keep;   // tallest page seen: the sheet never shrinks, or the page buttons would jump away from under the finger
            @Override protected void onMeasure(int w, int h) {
                int used = col.getPaddingTop() + col.getPaddingBottom() + dp(a, 12) + dp(a, 8);   // the sheet's own padding, the gap under it and the handle's margin
                for (int i = 0; i < col.getChildCount(); i++) { View c = col.getChildAt(i); if (c != this && c.getVisibility() != View.GONE) used += c.getMeasuredHeight(); }   // siblings above are measured already
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(Math.max(dp(a, 120), cap - used), MeasureSpec.AT_MOST));
                keep = Math.max(keep, getMeasuredHeight());
                if (getMeasuredHeight() < keep) setMeasuredDimension(getMeasuredWidth(), keep);
            }
        };
        pScroll.setVerticalScrollBarEnabled(false);
        pBox = new LinearLayout(a);
        pBox.setOrientation(LinearLayout.VERTICAL);
        pScroll.addView(pBox, new ViewGroup.LayoutParams(-1, -2));
        col.addView(pScroll, new LinearLayout.LayoutParams(-1, -2));

        if (panel != null) try { panel.dismiss(); } catch (Throwable ignored) {}
        android.app.Dialog dl = new android.app.Dialog(a);
        dl.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout wrap = new FrameLayout(a);
        wrap.setPadding(dp(a, 12), 0, dp(a, 12), dp(a, 12));
        wrap.addView(col);
        final FrameLayout wrapView = wrap;
        dl.setContentView(wrap);

        // frosted glass: blur the scene behind when the system allows it (it does not while power saving), and fall back live
        WindowManager wm = a.getSystemService(WindowManager.class);
        Window w = dl.getWindow();
        final Consumer<Boolean> blurWatch = on -> { try { if (w != null) applyBlur(a, w, bg, on); } catch (Throwable t) { Log.w("blur: " + t); } };
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            boolean on = false;
            try { on = wm != null && wm.isCrossWindowBlurEnabled(); } catch (Throwable ignored) {}
            applyBlur(a, w, bg, on);
            try { if (wm != null) wm.addCrossWindowBlurEnabledListener(a.getMainExecutor(), blurWatch); } catch (Throwable t) { Log.w("blur listener: " + t); }
        }
        // day <-> night while the panel is open: build it again in the other colours (event-driven, nothing runs otherwise)
        final android.content.ComponentCallbacks themeWatch = new android.content.ComponentCallbacks() {
            @Override public void onConfigurationChanged(android.content.res.Configuration n) {
                boolean night = (n.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                if (panel != dl || night == Ui.cur.night) return;
                RecentsChip.main.post(() -> { try { if (panel == dl) show(a); } catch (Throwable t) { Log.w("retheme: " + t); } });
            }
            @Override public void onLowMemory() {}
        };
        try { a.registerComponentCallbacks(themeWatch); } catch (Throwable t) { Log.w("theme watch: " + t); }
        dl.setOnDismissListener(x -> {
            try { a.unregisterComponentCallbacks(themeWatch); } catch (Throwable ignored) {}
            try { if (wm != null) wm.removeCrossWindowBlurEnabledListener(blurWatch); } catch (Throwable ignored) {}
            if (panel != dl) return;   // an older panel replaced by a new one: the new one owns everything below
            panel = null;
            PanelCmd.clear();
            RecentsChip.main.removeCallbacks(ticker);
            Global.gset(a, "sbmods_watch", RecentsChip.overviewShown() ? "1" : "0");   // per-core load is only measured while the panel shows
        });
        panel = dl;
        pAct = a;
        Global.gset(a, "sbmods_watch", "2");
        dl.show();
        rebuildCards(a);
        RecentsChip.main.removeCallbacks(ticker);
        RecentsChip.main.postDelayed(ticker, 1000);
    }

    private static void applyBlur(Context a, Window w, Ui.Glass bg, boolean on) {
        bg.setFill(on ? Ui.cur.sheet : Ui.cur.sheetSolid);   // glass when blurred, a near-solid sheet otherwise
        WindowManager.LayoutParams lp = w.getAttributes();
        if (on) { w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND); lp.setBlurBehindRadius(dp(a, 60)); }
        else { w.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND); lp.setBlurBehindRadius(0); }
        w.setAttributes(lp);
        w.setDimAmount(Ui.cur.night ? (on ? 0.25f : 0.45f) : (on ? 0.10f : 0.30f));
    }

    /** One refresh a second while the panel shows (the Overview tick keeps its slower rhythm). */
    private static final Runnable ticker = new Runnable() {
        @Override public void run() {
            try {
                if (!showing() || pAct == null) return;
                panelLive(pAct, Perf.read());
            } catch (Throwable t) { Log.w("panel tick: " + t); }
            if (showing()) RecentsChip.main.postDelayed(this, 1000);
        }
    };

    /** Something was changed from the panel: show it, and let the chips catch up. */
    static void changed(Activity a) {
        pChangedAt = SystemClock.uptimeMillis();
        RecentsChip.main.postDelayed(() -> { try { if (showing()) rebuildCards(a); RecentsChip.refresh(a); } catch (Throwable t) { Log.w("panel refresh: " + t); } }, 350);
    }

    /** The page buttons: four 48 dp pills, the open one solid accent, the others glass. */
    private static void buildSwitch(Activity a) {
        pSwitch.removeAllViews();
        LinearLayout row = new LinearLayout(a);
        for (int i = 0; i < PanelLayout.PAGE_NAMES.length; i++) {
            final int at = i;
            boolean on = i == page;
            TextView b = PanelCards.tvm(a, PanelLayout.PAGE_NAMES[i], 14, on ? Ui.cur.onAcc : Ui.cur.tx);
            b.setGravity(Gravity.CENTER);
            b.setSingleLine(true);
            b.setBackground(on ? Ui.round(Ui.cur.acc, dp(a, 24), 1, Ui.cur.acc) : Ui.panelGlass(a, Ui.cur, Ui.cur.tile, Ui.cur.tileBd, 24));
            b.setSelected(on);
            b.setOnClickListener(PanelCards.safe(() -> {
                if (at == page) return;
                page = at;
                allOrder = null; helpOpen = null;   // the All page is rebuilt with the newest order
                Global.gset(a, PanelLayout.TAB, String.valueOf(at));
                buildSwitch(a);
                rebuildCards(a);   // only the card area changes
                try { pBox.setAlpha(0f); pBox.animate().alpha(1f).setDuration(120).start(); } catch (Throwable t) { Log.w("fade: " + t); }
            }));
            row.addView(b, PanelCards.lp(0, dp(a, 48), i == 0 ? 0 : dp(a, 4), 0, i == PanelLayout.PAGE_NAMES.length - 1 ? 0 : dp(a, 4), 0, 1));
        }
        pSwitch.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Rebuild the card area now (a card changed something that its own view cannot show). */
    static void rebuild(Activity a) { if (showing()) rebuildCards(a); }

    /** The cards of the current page. The All page keeps the order it was built with until the page is left or the panel reopens. */
    private static List<PanelCards.Card> pageCards(Activity a) {
        if (page != PanelLayout.PAGE_ALL) return PanelCards.visible(a, page);
        if (!PanelCards.allFill(a)) return PanelCards.allFixed(a);   // the usual All page: every shown card, in the owner's order
        if (allOrder == null) allOrder = PanelCards.recentVisible(a);   // opt-in: the cards used last, kept while the panel shows
        List<PanelCards.Card> out = new ArrayList<>();
        for (String id : allOrder) { PanelCards.Card k = PanelCards.byId(id); if (k != null) out.add(k); }
        return out;
    }

    /** One card as a glass tile: header line (title left, live value right), the card, and its help (hidden until the title is tapped). Null if it failed to build. */
    private static LinearLayout tile(Activity a, PanelCards.Card k) {
        k.val = null;
        k.toggle = () -> {
            helpOpen = k.id.equals(helpOpen) ? null : k.id;
            for (int i = 0; i < shown.size() && i < helps.size(); i++) if (helps.get(i) != null) helps.get(i).setVisibility(shown.get(i).id.equals(helpOpen) ? View.VISIBLE : View.GONE);
        };
        View content = PanelCards.safeBuild(k, a);
        if (content == null) return null;
        boolean on = false;
        if (k.tapTile()) try { on = k.on(a); } catch (Throwable t) { Log.w("card " + k.id + " on: " + t); }
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
        t.setBackground(on ? Ui.round(Ui.cur.acc, dp(a, Ui.R_TILE), 1, Ui.cur.acc)
                : Ui.panelGlass(a, Ui.cur, Ui.cur.tile, Ui.cur.tileBd, Ui.R_TILE));
        if (k.tapTile()) {
            t.setMinimumHeight(dp(a, 64));
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setOnClickListener(PanelCards.safe(() -> { k.tap(a); rebuild(a); }));
            t.setOnLongClickListener(v -> { try { k.toggle.run(); } catch (Throwable x) { Log.w("help: " + x); } return true; });
        }
        String h = null;
        try { h = k.help(a); } catch (Throwable x) { Log.w("card " + k.id + " help: " + x); }
        if (!k.ownHeader()) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 0, 0, dp(a, 6));
            TextView title = k.titleView(a);
            title.setPadding(0, 0, dp(a, 8), 0);
            row.addView(title, new LinearLayout.LayoutParams(-2, -2));
            if (k.val != null) row.addView(k.val, new LinearLayout.LayoutParams(0, -2, 1));
            if (h != null) row.setOnClickListener(PanelCards.safe(k.toggle));
            t.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        t.addView(content, new LinearLayout.LayoutParams(-1, -2));
        TextView hv = null;
        if (h != null) {
            hv = tv(a, h, 12, on ? PanelCards.onAccDim() : Ui.cur.tx2, false);
            hv.setPadding(0, dp(a, 8), 0, 0);
            hv.setVisibility(k.id.equals(helpOpen) ? View.VISIBLE : View.GONE);
            t.addView(hv, new LinearLayout.LayoutParams(-1, -2));
        }
        shown.add(k);
        helps.add(hv);
        String sg = "";
        try { sg = k.sig(a); } catch (Throwable x) { Log.w("card " + k.id + " sig: " + x); }
        sigs.add(sg);
        return t;
    }

    private static void bindToggle(PanelCards.Card k) {
        k.val = null;
        k.toggle = () -> {
            helpOpen = k.id.equals(helpOpen) ? null : k.id;
            for (int i = 0; i < shown.size() && i < helps.size(); i++) if (helps.get(i) != null) helps.get(i).setVisibility(shown.get(i).id.equals(helpOpen) ? View.VISIBLE : View.GONE);
        };
    }

    /**
     * A switch card as a tile. Wide: round icon, label and state, an info "i" for the help; small: the icon only.
     * Solid accent while on, glass while off. Returns null if it failed; the help text goes into `helpOut` (added under the row).
     */
    private static View switchTile(Activity a, PanelCards.Card k, boolean small, List<TextView> helpOut) {
        Ui.Pal p = Ui.cur;
        bindToggle(k);
        boolean on = false;
        String state = "";
        try { on = k.on(a); state = k.state(a); } catch (Throwable t) { Log.w("card " + k.id + " tile: " + t); return null; }
        String h = null;
        try { h = k.help(a); } catch (Throwable x) { Log.w("card " + k.id + " help: " + x); }
        FrameLayout t = new FrameLayout(a);
        t.setBackground(on ? Ui.round(p.acc, dp(a, Ui.R_TILE), 1, p.acc) : Ui.panelGlass(a, p, p.tile, p.tileBd, Ui.R_TILE));
        t.setClickable(true);
        t.setContentDescription(k.title + ", " + state);
        t.setOnClickListener(PanelCards.safe(() -> { k.tap(a); rebuild(a); }));
        t.setOnLongClickListener(v -> { try { k.toggle.run(); } catch (Throwable x) { Log.w("help: " + x); } return true; });
        int ink = on ? p.onAcc : p.accInk;
        if (small) {
            android.widget.ImageView ic = Ui.icon(a, k.icon(), on ? p.onAcc : p.tx, 26);
            FrameLayout.LayoutParams ip = (FrameLayout.LayoutParams) ic.getLayoutParams();
            ip.gravity = Gravity.CENTER;
            t.addView(ic);
        } else {
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
            FrameLayout badge = new FrameLayout(a);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(on ? (p.night ? 0x29031F23 : 0x38FFFFFF) : p.tileIco);
            badge.setBackground(bg);
            android.widget.ImageView ic = Ui.icon(a, k.icon(), ink, 20);
            ((FrameLayout.LayoutParams) ic.getLayoutParams()).gravity = Gravity.CENTER;
            badge.addView(ic);
            col.addView(badge, new LinearLayout.LayoutParams(dp(a, 36), dp(a, 36)));
            col.addView(new View(a), new LinearLayout.LayoutParams(1, 0, 1));
            TextView lab = PanelCards.tvm(a, k.title, Ui.T_LABEL, on ? p.onAcc : p.tx);
            lab.setSingleLine(true);
            lab.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(lab);
            TextView val = tv(a, state, 13, on ? p.onAcc : p.tx2, false);
            val.setSingleLine(true);
            val.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(val);
            t.addView(col, new FrameLayout.LayoutParams(-1, -1));
            if (h != null) {
                View info = Ui.infoButton(a, on ? p.onAcc : p.tx2, k.title, PanelCards.safe(k.toggle));
                FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(a, 48), dp(a, 48), Gravity.TOP | Gravity.END);
                fp.setMargins(0, dp(a, -2), dp(a, -2), 0);
                t.addView(info, fp);
            }
        }
        TextView hv = null;
        if (h != null) {
            hv = tv(a, h, 12, p.tx2, false);
            hv.setPadding(dp(a, 6), dp(a, 8), dp(a, 6), 0);
            hv.setVisibility(k.id.equals(helpOpen) ? View.VISIBLE : View.GONE);
            helpOut.add(hv);
        }
        shown.add(k);
        helps.add(hv);
        String sg = "";
        try { sg = k.sig(a); } catch (Throwable x) { Log.w("card " + k.id + " sig: " + x); }
        sigs.add(sg);
        return t;
    }

    /** Switch cards in a row: two wide tiles or four small ones; a short row keeps the tile size (empty slots). */
    private static void addTileRow(Activity a, List<PanelCards.Card> run, boolean small, int per) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(a);
        List<TextView> hv = new ArrayList<>();
        int gap = dp(a, small ? 5 : 5);
        for (int i = 0; i < per; i++) {
            View t = i < run.size() ? switchTile(a, run.get(i), small, hv) : null;
            if (t == null) t = new View(a);
            row.addView(t, PanelCards.lp(0, dp(a, small ? 72 : 106), i == 0 ? 0 : gap, 0, i == per - 1 ? 0 : gap, 0, 1));
        }
        col.addView(row, new LinearLayout.LayoutParams(-1, -2));
        for (TextView h : hv) col.addView(h, new LinearLayout.LayoutParams(-1, -2));
        int top = pBox.getChildCount() > 0 ? dp(a, 10) : 0;
        pBox.addView(col, PanelCards.lp(-1, -2, 0, top, 0, 0));
    }

    /** Fill the card area with the cards of the current page; switch cards become tiles, two wide or four small to a row. */
    private static void rebuildCards(Activity a) {
        if (pBox == null || rebuilding) return;
        rebuilding = true;
        try {
            int y = pScroll.getScrollY();
            pBox.removeAllViews();
            shown.clear(); sigs.clear(); helps.clear();
            Perf.read();   // make sure the core groups are known before cards use them
            List<PanelCards.Card> cards = pageCards(a);
            for (int i = 0; i < cards.size(); ) {
                PanelCards.Card k = cards.get(i);
                if (k.tapTile()) {
                    boolean small = PanelCards.small(a, k);
                    int per = small ? 4 : 2;
                    List<PanelCards.Card> run = new ArrayList<>();
                    while (i < cards.size() && cards.get(i).tapTile() && PanelCards.small(a, cards.get(i)) == small && run.size() < per) run.add(cards.get(i++));
                    addTileRow(a, run, small, per);
                    continue;
                }
                i++;
                LinearLayout t1 = tile(a, k);
                if (t1 != null) pBox.addView(t1, PanelCards.lp(-1, -2, 0, pBox.getChildCount() > 0 ? dp(a, 10) : 0, 0, 0));
            }
            if (shown.isEmpty()) {
                if (page == PanelLayout.PAGE_ALL) {
                    LinearLayout empty = new LinearLayout(a);
                    empty.setOrientation(LinearLayout.VERTICAL);
                    empty.setGravity(Gravity.CENTER_HORIZONTAL);
                    empty.setPadding(dp(a, 16), dp(a, 28), dp(a, 16), dp(a, 28));
                    TextView l1 = tv(a, "Nothing here yet", 15, Ui.cur.tx, false);
                    TextView l2 = tv(a, PanelCards.allFill(a) ? "Whatever you change on the other pages shows up here, newest first." : "No cards are shown. Turn some on in StatusBar Mods.", 13, Ui.cur.tx2, false);
                    l2.setGravity(Gravity.CENTER);
                    empty.addView(l1);
                    empty.addView(l2, PanelCards.lp(-2, -2, 0, dp(a, 6), 0, 0));
                    pBox.addView(empty, new LinearLayout.LayoutParams(-1, -2));
                } else pBox.addView(tv(a, "No cards on this page. Turn some on in StatusBar Mods.", 14, Ui.cur.tx2, false), new LinearLayout.LayoutParams(-1, -2));
            }
            pScroll.post(() -> pScroll.scrollTo(0, y));
        } finally { rebuilding = false; }
        liveAll(a, Perf.read());
    }

    /** Live numbers in the open panel (called about once a second). */
    static void panelLive(Context c, Perf.Snap s) {
        if (pBox == null || !(pAct instanceof Activity)) return;
        liveAll(pAct, s);
    }

    private static void liveAll(Activity a, Perf.Snap s) {
        PanelCards.Power.read(a);
        try { header(a); warn(a, s); } catch (Throwable t) { Log.w("panel header: " + t); }
        boolean stale = false;
        for (int i = 0; i < shown.size(); i++) {
            PanelCards.Card k = shown.get(i);
            PanelCards.safeLive(k, a, s);
            try { if (!k.sig(a).equals(sigs.get(i))) stale = true; } catch (Throwable t) { Log.w("card " + k.id + " sig: " + t); }
        }
        if (stale) rebuildCards(a);   // changed from elsewhere (a chip long-press, the app)
    }

    private static void header(Activity a) {
        SpannableStringBuilder t = new SpannableStringBuilder();
        int m = PanelCards.currentMode(a);   // the mode's colour and name lead the line (amber when it is your own mix or cool-down)
        int mc = m >= 0 ? PanelCards.MODE_COLORS[m] : Ui.cur.warn;
        t.append("\u25cf");
        t.setSpan(new ForegroundColorSpan(mc), 0, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.append(" ").append(m >= 0 ? PanelMath.MODE_NAMES[m] : Global.gint(a, "sbmods_cool", 0) == 1 ? "Cool-down" : "Custom").append(" · ").append(PanelCards.Power.tempC + "°C");
        String w = PanelCards.Power.text();
        if (w != null) t.append(" · ").append(w);
        long until = 0;
        try { String u = Global.gstr(a, "sbmods_until"); if (u != null && !u.isEmpty()) until = Long.parseLong(u.trim()); } catch (NumberFormatException ignored) {}
        long min = PanelMath.limitMinutes(until, System.currentTimeMillis() / 1000);
        if (min > 0) {
            t.append(" · ");
            int st = t.length();
            t.append(PanelMath.duration(min));   // just the time left, in the limit colour: the word "limit" pushed the line past 384dp
            t.setSpan(new ForegroundColorSpan(Ui.cur.acc), st, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        pHead.setText(t);
    }

    /** A clear warning when a limit was asked for but nothing happened (helper off). */
    private static void warn(Context c, Perf.Snap s) {
        int cp = Global.gint(c, "sbmods_cpu_pct", 0), gp = Global.gint(c, "sbmods_gpu_pct", 0);
        boolean cool = Global.gint(c, "sbmods_cool", 0) == 1;
        cp = RecentsChip.effPct(c, "cpu", cp, cool); gp = RecentsChip.effPct(c, "gpu", gp, cool);
        boolean missed = (cp > 0 && s.cpuCap > Perf.pick(Perf.cpuSteps, s.cpuHw, cp)) || (gp > 0 && s.gpuCap > Perf.pick(Perf.gpuSteps, s.gpuHw, gp));
        boolean late = SystemClock.uptimeMillis() - pChangedAt > 4000;
        pWarn.setVisibility(missed && late ? View.VISIBLE : View.GONE);
    }
}
