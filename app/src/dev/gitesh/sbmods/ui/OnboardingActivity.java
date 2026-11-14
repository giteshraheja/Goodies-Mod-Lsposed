// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.ui;

import android.app.Activity;
import android.graphics.Color;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import dev.gitesh.sbmods.Backup;
import dev.gitesh.sbmods.Helper;
import dev.gitesh.sbmods.Ui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Step-by-step setup: root, the module, and bringing back a backup. Shown on a fresh install or after a reinstall. */
public final class OnboardingActivity extends Activity {
    static final String STATE = "pulsebar_state";
    private static final int REQ_PICK = 41;
    private static final int WELCOME = 0, ROOT = 1, MODULE = 2, RESTORE = 3, DONE = 4;
    private static final String[] ROOT_APPS = {"me.weishu.kernelsu", "df.root", "com.topjohnwu.magisk"};
    private static final String[] MODULE_APPS = {"org.lsposed.manager", "org.matrix.vector.manager", "io.github.lsposed.manager"};

    private Ui.Pal pal;
    private List<Backup.Entry> backups = new ArrayList<>();
    private int[] order;
    private int pos, chosen;
    private Boolean rootOk;   // null = not checked yet

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().hide();
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        pal = Ui.appPal(this);
        backups = Backup.list(this);
        order = new int[]{WELCOME, ROOT, MODULE, RESTORE, DONE};   // (always: after a reinstall Android hides the old backups from the list, "Pick a file" still reaches them)
        show();
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private int id() { return order[pos]; }

    private void go(int target) {
        for (int i = 0; i < order.length; i++) if (order[i] == target) pos = i;
        show();
    }

    private void show() {
        int id = id();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(pal.bg);
        sv.setFillViewport(true);
        sv.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets bars = ins.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return ins;
        });
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, dp(24), pad, dp(28));
        col.addView(progress(), margin(-1, -2, 18));
        col.addView(card(id), margin(-1, -2, 16));
        col.addView(nav(id), margin(-1, -2, 0));
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        setContentView(sv);
        if (id == ROOT && rootOk == null) checkRoot();
    }

    private View progress() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < order.length; i++) {
            View d = new View(this);
            d.setBackground(Ui.round(i <= pos ? pal.acc : pal.line2, dp(4), 0, 0));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(i == pos ? 28 : 10), dp(8));
            dl.rightMargin = dp(6);
            dots.addView(d, dl);
        }
        row.addView(dots);
        TextView t = Ui.text(this, "Step " + (pos + 1) + " of " + order.length, Ui.T_CAPTION, pal.tx2, 500);
        t.setPadding(0, dp(10), 0, 0);
        row.addView(t);
        return row;
    }

    private View card(int id) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.card(this, pal));
        c.setPadding(dp(22), dp(24), dp(22), dp(24));
        c.addView(Ui.iconBadge(this, pal, iconFor(id)), new LinearLayout.LayoutParams(dp(48), dp(48)));
        c.addView(Ui.text(this, titleFor(id), Ui.T_SHEET, pal.tx, 700), margin(-2, -2, 8));
        c.addView(Ui.text(this, bodyFor(id), Ui.T_BODY, pal.tx2, 400), margin(-1, -2, 16));

        if (id == ROOT) {
            String s = rootOk == null ? "Checking…" : rootOk ? "Root is on" : "Root is not given yet";
            c.addView(pill(s, rootOk != null && rootOk ? pal.ok : pal.warn), margin(-2, -2, 16));
            Intent i = launcherOf(ROOT_APPS);
            if (i != null) c.addView(button("Open root manager", true, () -> startActivity(i)), margin(-1, -2, 0));
        } else if (id == MODULE) {
            boolean on = moduleRunning();
            c.addView(pill(on ? "Running in the status bar" : "Not running yet", on ? pal.ok : pal.warn), margin(-2, -2, 16));
            Intent i = launcherOf(MODULE_APPS);
            if (i != null) c.addView(button("Open module manager", true, () -> startActivity(i)), margin(-1, -2, 10));
            c.addView(button("Restart status bar", false, this::restartStatusBar), margin(-1, -2, 0));
        } else if (id == RESTORE) {
            for (int i = 0; i < backups.size(); i++) {
                final int idx = i;
                Backup.Entry e = backups.get(i);
                String when = new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(new Date(e.seconds * 1000));
                String label = (e.daily() ? "Daily backup" : "Saved by hand") + " · " + when;
                c.addView(Ui.pickRow(this, pal, label, i == chosen, v -> { chosen = idx; show(); }), margin(-1, -2, 6));
            }
            if (!backups.isEmpty()) c.addView(button("Restore selected", true, this::confirmRestore), margin(-1, -2, 10));
            c.addView(button("Pick a file…", backups.isEmpty(), this::pickFile), margin(-1, -2, 0));
        } else if (id == DONE) {
            c.addView(pill("Daily backups on · newest 7 kept", pal.ok), margin(-2, -2, 0));
        }
        return c;
    }

    private View nav(int id) {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.text(this, pos == 0 ? "Skip setup" : "Back", Ui.T_LABEL, pal.tx2, 600);
        back.setGravity(Gravity.CENTER);
        back.setMinHeight(dp(Ui.TAP));
        back.setPadding(dp(8), 0, dp(8), 0);
        back.setOnClickListener(v -> {
            if (pos == 0) finishSetup();
            else { pos--; show(); }
        });
        nav.addView(back, new LinearLayout.LayoutParams(-2, -2));
        View gap = new View(this);
        nav.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        String next = id == WELCOME ? "Let’s start" : id == DONE ? "Finish" : id == RESTORE ? "Skip" : "Next";
        TextView n = button(next, true, () -> {
            if (id == DONE) finishSetup();
            else { pos++; show(); }
        });
        nav.addView(n, new LinearLayout.LayoutParams(-2, -2));
        return nav;
    }

    private String iconFor(int id) {
        switch (id) {
            case ROOT: return "shield";
            case MODULE: return "bolt";
            case RESTORE: return "layers";
            case DONE: return "check";
            default: return "spark";
        }
    }

    private String titleFor(int id) {
        switch (id) {
            case ROOT: return "1 · Give PulseBar root";
            case MODULE: return "2 · Turn on the module";
            case RESTORE: return "3 · Bring back a backup";
            case DONE: return "You’re all set";
            default: return "Welcome to PulseBar";
        }
    }

    private String bodyFor(int id) {
        switch (id) {
            case ROOT: return "Root lets PulseBar change speed, sleep and the status bar. Open your root manager, find PulseBar and tap Allow.";
            case MODULE: return "In your module manager (Vector or LSPosed), open Modules, tick PulseBar, then restart the status bar.";
            case RESTORE: return backups.isEmpty()
                    ? "No backup shows up by itself (after a reinstall the phone hides the old ones from the app). If you have one, tap Pick a file and choose a sbmods-….json file in Downloads. Or skip to start fresh."
                    : "We found backups from before. Pick one to get your settings back, or skip to start fresh.";
            case DONE: return "PulseBar now saves your settings once a day and keeps the newest 7 backups in Downloads. After a reinstall, this guide can bring them back. You can reopen it from More.";
            default: return "Setup takes about two minutes. You’ll give PulseBar root, switch it on in your module manager, and optionally bring back old settings.";
        }
    }

    private TextView pill(String text, int color) {
        TextView t = Ui.text(this, text, Ui.T_CAPTION, color, 600);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setBackground(Ui.round(pal.card2, dp(20), 0, 0));
        return t;
    }

    private TextView button(String text, boolean primary, Runnable r) {
        TextView t = Ui.text(this, text, Ui.T_LABEL, primary ? pal.onAcc : pal.accInk, 600);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(Ui.TAP));
        t.setPadding(dp(18), dp(12), dp(18), dp(12));
        t.setBackground(Ui.round(primary ? pal.acc : pal.accSoft, dp(Ui.R_BUTTON), 0, 0));
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private LinearLayout.LayoutParams margin(int w, int h, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.bottomMargin = dp(bottom);
        return lp;
    }

    private Intent launcherOf(String[] pkgs) {
        for (String p : pkgs) {
            Intent i = getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) return i;
        }
        return null;
    }

    private boolean moduleRunning() {
        ContentResolver cr = getContentResolver();
        long boot = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1);
        return boot >= 0 && Settings.Global.getLong(cr, "sbmods_alive_boot", -1) == boot;
    }

    private void checkRoot() {
        new Thread(() -> {
            boolean ok = Helper.rootGranted();
            runOnUiThread(() -> {
                rootOk = ok;
                if (!isFinishing() && id() == ROOT) show();
            });
        }).start();
    }

    private void restartStatusBar() {
        new Thread(() -> {
            try { Runtime.getRuntime().exec(new String[]{"su", "-c", "killall com.android.systemui"}).waitFor(); } catch (Exception ignored) {}
        }).start();
        Toast.makeText(this, "Restarting the status bar…", Toast.LENGTH_SHORT).show();
    }

    private void confirmRestore() {
        if (backups.isEmpty()) return;
        new Ui.Dlg(this, pal)
                .title("Restore this backup?")
                .message("Your current options are replaced by the backup you picked.")
                .button(0, "Cancel", null)
                .button(2, "Restore", () -> restoreFrom(backups.get(chosen).uri))
                .show();
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json"});
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) restoreFrom(data.getData());
    }

    private void restoreFrom(Uri u) {
        new Thread(() -> {
            String msg;
            try { msg = Backup.restoreUri(this, getSharedPreferences("settings", MODE_PRIVATE), u); }
            catch (Throwable t) { msg = "Couldn’t restore: " + t.getClass().getSimpleName(); }
            String m = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                if (m.startsWith("Restored") || m.startsWith("Options restored")) { if (getIntent().getBooleanExtra("reopen", false)) SettingsActivity.restoredElsewhere = true; go(DONE); }
            });
        }).start();
    }

    private void finishSetup() {
        getSharedPreferences(STATE, MODE_PRIVATE).edit().putBoolean("onboarded", true).apply();
        Backup.schedule(this);
        if (!getIntent().getBooleanExtra("reopen", false)) startActivity(new Intent(this, SettingsActivity.class));
        finish();
    }
}
