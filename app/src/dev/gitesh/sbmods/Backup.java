// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Save and restore of the options: the app's own preferences plus the panel's global settings. Files go to Downloads
 * through MediaStore, so no storage permission is needed; a restore reads the newest backup this app made.
 */
public final class Backup {
    private Backup() {}

    private static final String PREFIX = "sbmods-backup-";
    private static final String GLOBALS_SETTINGS = "sbmods_cards,sbmods_tiles,sbmods_allfill,sbmods_pins,sbmods_smart";

    /** Writes the backup. Returns the file name. Throws if Downloads can't be written. */
    public static String save(Context ctx, SharedPreferences prefs) throws Exception {
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

        String name = PREFIX + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".json";
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

    /** Reads the newest backup. Returns the file's JSON, or null if this app has none. */
    static JSONObject readLatest(Context ctx) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        Uri base = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        try (Cursor c = cr.query(base, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?", new String[]{PREFIX + "%"},
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c == null || !c.moveToFirst()) return null;
            Uri u = android.content.ContentUris.withAppendedId(base, c.getLong(0));
            StringBuilder sb = new StringBuilder();
            try (java.io.InputStream in = cr.openInputStream(u)) {
                if (in == null) return null;
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return new JSONObject(sb.toString());
        }
    }

    /**
     * Puts the newest backup back: the app's preferences are replaced, then the panel settings are written as root.
     * Returns null when restored, or a message. Call off the main thread (the root part blocks).
     */
    public static String restore(Context ctx, SharedPreferences prefs) throws Exception {
        JSONObject root = readLatest(ctx);
        if (root == null) return "No backup found. Save one first.";
        if (root.optInt("format", 0) != 1) return "That backup is from a version this app can't read.";
        JSONObject p = root.getJSONObject("prefs");
        SharedPreferences.Editor ed = prefs.edit().clear();
        java.util.Iterator<String> keys = p.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = p.get(k);
            if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) ed.putInt(k, (Integer) v);
            else if (v instanceof Long) ed.putLong(k, (Long) v);
            else if (v instanceof Double) ed.putFloat(k, ((Double) v).floatValue());
            else if (v instanceof String) ed.putString(k, (String) v);
        }
        ed.commit();
        JSONObject g = root.optJSONObject("globals");
        if (g != null && g.length() > 0) {
            java.util.List<String> kv = new java.util.ArrayList<>();
            java.util.Iterator<String> gk = g.keys();
            while (gk.hasNext()) {
                String k = gk.next();
                if (java.util.Arrays.asList(GLOBALS_SETTINGS.split(",")).contains(k)) { kv.add(k); kv.add(g.getString(k)); }
            }
            if (!kv.isEmpty() && !Helper.putGlobal(kv.toArray(new String[0])))
                return "Options restored, but the panel settings need root";
        }
        return null;
    }
}
