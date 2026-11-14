// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Save and restore of the options: the app's own preferences plus the panel's global settings. Files go to Downloads
 * through MediaStore, so no storage permission is needed. A daily backup is kept for the newest KEEP days; a backup
 * saved by hand is never deleted.
 */
public final class Backup {
    private Backup() {}

    public static final String MANUAL = "sbmods-backup-", AUTO = "sbmods-auto-";
    public static final int KEEP = 7;
    private static final String LIKE = "sbmods-%";
    private static final String STATE = "pulsebar_state";
    private static final String GLOBALS_SETTINGS = "sbmods_cards,sbmods_tiles,sbmods_allfill,sbmods_pins,sbmods_smart";

    /** One backup file in Downloads. */
    public static final class Entry {
        public final Uri uri;
        public final String name;
        public final long seconds;
        Entry(Uri uri, String name, long seconds) { this.uri = uri; this.name = name; this.seconds = seconds; }
        public boolean daily() { return name.startsWith(AUTO); }
    }

    /** Every backup this app can see in Downloads, newest first. */
    public static List<Entry> list(Context ctx) {
        List<Entry> out = new ArrayList<>();
        ContentResolver cr = ctx.getContentResolver();
        Uri base = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        try (Cursor c = cr.query(base, new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.DATE_ADDED},
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?", new String[]{LIKE},
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c == null) return out;
            while (c.moveToNext()) out.add(new Entry(ContentUris.withAppendedId(base, c.getLong(0)), c.getString(1), c.getLong(2)));
        }
        return out;
    }

    /** Writes a backup by hand. Returns the file name. Throws if Downloads can't be written. */
    public static String save(Context ctx, SharedPreferences prefs) throws Exception {
        return write(ctx, prefs, MANUAL);
    }

    private static String write(Context ctx, SharedPreferences prefs, String prefix) throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", 1);
        JSONObject p = new JSONObject();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Float || v instanceof String)
                p.put(e.getKey(), v);
        }
        root.put("prefs", p);
        JSONObject g = new JSONObject();
        ContentResolver cr = ctx.getContentResolver();
        for (String k : GLOBALS_SETTINGS.split(",")) {
            String v = Settings.Global.getString(cr, k);
            if (v != null) g.put(k, v);
        }
        root.put("globals", g);

        String name = prefix + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".json";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (u == null) throw new IllegalStateException("no Downloads entry");
        try (OutputStream os = cr.openOutputStream(u)) {
            if (os == null) throw new IllegalStateException("no output");
            os.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        return name;
    }

    /** Saves today's daily backup if there is none yet, then deletes the daily ones beyond the newest KEEP. Off the main thread. */
    public static void runDaily(Context ctx, SharedPreferences prefs) throws Exception {
        SharedPreferences st = ctx.getSharedPreferences(STATE, Context.MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        if (today.equals(st.getString("lastDaily", ""))) return;
        boolean have = false;
        for (Entry e : list(ctx)) if (e.name.startsWith(AUTO + today)) have = true;
        if (!have) write(ctx, prefs, AUTO);
        st.edit().putString("lastDaily", today).apply();
        int seen = 0;
        for (Entry e : list(ctx)) {
            if (!e.daily()) continue;
            if (++seen > KEEP) {
                try { ctx.getContentResolver().delete(e.uri, null, null); } catch (Throwable ignored) {}
            }
        }
    }

    /** One inexact daily alarm, batched by the system: it doesn't wake the phone by itself, so it costs about no battery. */
    public static void schedule(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 7, new Intent(ctx, BackupReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + 3_600_000L, AlarmManager.INTERVAL_DAY, pi);
    }

    private static JSONObject read(Context ctx, Uri u) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
            if (in == null) throw new IllegalStateException("no input");
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return new JSONObject(sb.toString());
    }

    /** Restores the newest backup. Returns the result message. Off the main thread (the root part blocks). */
    public static String restore(Context ctx, SharedPreferences prefs) throws Exception {
        List<Entry> all = list(ctx);
        if (all.isEmpty()) return "No backup found. Save one first.";
        return restoreUri(ctx, prefs, all.get(0).uri);
    }

    /**
     * Puts the chosen backup back: the app's preferences are replaced, then the panel settings are written as root.
     * Returns the result message. Off the main thread.
     */
    public static String restoreUri(Context ctx, SharedPreferences prefs, Uri u) throws Exception {
        JSONObject root = read(ctx, u);
        if (root.optInt("format", 0) != 1) return "That backup is from a version this app can't read.";
        JSONObject p = root.getJSONObject("prefs");
        SharedPreferences.Editor ed = prefs.edit().clear();
        Iterator<String> keys = p.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = p.get(k);
            if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) ed.putInt(k, (Integer) v);
            else if (v instanceof Long) ed.putLong(k, (Long) v);
            else if (v instanceof Double) ed.putFloat(k, ((Double) v).floatValue());
            else if (v instanceof String) ed.putString(k, (String) v);
        }
        boolean wrote = ed.commit();
        int now = prefs.getAll().size();
        if (!wrote) return "Restore: the settings file could not be written (" + p.length() + " keys in the backup)";
        JSONObject g = root.optJSONObject("globals");
        if (g != null && g.length() > 0) {
            List<String> kv = new ArrayList<>();
            Iterator<String> gk = g.keys();
            while (gk.hasNext()) {
                String k = gk.next();
                if (Arrays.asList(GLOBALS_SETTINGS.split(",")).contains(k)) { kv.add(k); kv.add(g.getString(k)); }
            }
            if (!kv.isEmpty() && !Helper.putGlobal(kv.toArray(new String[0])))
                return "Options restored, but the panel settings need root";
        }
        return "Restored: " + now + " settings written (backup had " + p.length() + ")";
    }
}
