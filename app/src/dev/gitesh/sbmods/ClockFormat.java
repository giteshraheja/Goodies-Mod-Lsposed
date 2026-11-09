// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;

import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Builds the custom clock text. Used by SystemUI (real clock) and the app (preview). */
public final class ClockFormat {
    /** Date styles for the builder. Index 0 = no date. */
    public static final String[] DATE_PATTERNS = {"", "d", "d MMM", "MMM d", "dd/MM", "MM/dd", "d MMMM"};
    public static final String[] DATE_LABELS = {"None", "1", "1 Nov", "Nov 1", "01/11", "11/01", "1 November"};

    private static final float AMPM_SMALL = 0.7f;

    private ClockFormat() {}

    public static boolean is24h(Config c, boolean system24) {
        return c.hourMode == Config.HOUR_24 || (c.hourMode == Config.HOUR_SYSTEM && system24);
    }

    /** True if the clock must refresh every second. */
    public static boolean needsSeconds(Config c) {
        if (c.clockMode == Config.CLOCK_BUILDER) return c.seconds;
        if (c.clockMode == Config.CLOCK_PATTERN) return unquoted(c.pattern).indexOf('s') >= 0;
        return false;
    }

    /** Returns the clock text, or null for "leave the stock clock alone" (stock mode or bad pattern). */
    public static CharSequence format(Config c, boolean system24, Date now, Locale loc) {
        try {
            if (c.clockMode == Config.CLOCK_BUILDER) return builder(c, system24, now, loc);
            if (c.clockMode == Config.CLOCK_PATTERN) return pattern(c, now, loc);
        } catch (Throwable ignored) {}
        return null;
    }

    /** Validates a custom pattern; returns an error message or null if OK. */
    public static String validate(String p) {
        if (p == null || p.trim().isEmpty()) return "Pattern is empty";
        try {
            new SimpleDateFormat(p, Locale.getDefault()).format(new Date());
            return null;
        } catch (Throwable t) {
            return "Invalid pattern: " + t.getMessage();
        }
    }

    private static CharSequence builder(Config c, boolean system24, Date now, Locale loc) {
        boolean h24 = is24h(c, system24);
        String tp = (h24 ? (c.leadingZero ? "HH" : "H") : (c.leadingZero ? "hh" : "h"))
                + ":mm" + (c.seconds ? ":ss" : "");
        SpannableStringBuilder time = new SpannableStringBuilder(new SimpleDateFormat(tp, loc).format(now));

        if (!h24 && c.ampm != Config.AMPM_HIDDEN) {
            Calendar cal = Calendar.getInstance(loc);
            cal.setTime(now);
            String ap = new DateFormatSymbols(loc).getAmPmStrings()[cal.get(Calendar.AM_PM)];
            if (c.ampmLower) ap = ap.toLowerCase(loc);
            int start = time.length();
            time.append(c.ampm == Config.AMPM_SMALL ? "" : " ").append(ap);
            if (c.ampm == Config.AMPM_SMALL)
                time.setSpan(new RelativeSizeSpan(AMPM_SMALL), start, time.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        SpannableStringBuilder block = dateBlock(c, now, loc);
        if (block == null || c.dateSeparate) return time;

        SpannableStringBuilder out = new SpannableStringBuilder();
        if (c.dateAfterTime) { out.append(time); gap(out, c.gapDateTime); out.append(block); }
        else { out.append(block); gap(out, c.gapDateTime); out.append(time); }
        return out;
    }

    /** The day / date on its own (Custom clock with "Date as its own item"), or null. */
    public static CharSequence dateText(Config c, Date now, Locale loc) {
        if (!c.dateSeparate || c.clockMode != Config.CLOCK_BUILDER) return null;
        try { return dateBlock(c, now, loc); } catch (Throwable t) { return null; }
    }

    private static SpannableStringBuilder dateBlock(Config c, Date now, Locale loc) {
        String day = null;
        if (c.weekday == Config.WEEKDAY_TWO) day = new android.icu.text.SimpleDateFormat("EEEEEE", loc).format(now);
        else if (c.weekday != Config.WEEKDAY_NONE)
            day = new SimpleDateFormat(c.weekday == Config.WEEKDAY_FULL ? "EEEE" : "EEE", loc).format(now);
        String date = null;
        if (c.date > 0) {
            String d = DATE_PATTERNS[c.date];
            if (c.year > 0) {
                String y = c.year == 2 ? "yyyy" : "yy";
                d += d.contains("/") ? "/" + y : " " + y;
            }
            date = new SimpleDateFormat(d, loc).format(now);
        }
        if (day == null && date == null) return null;

        SpannableStringBuilder block = new SpannableStringBuilder();
        String first = c.dateFirst ? date : day, second = c.dateFirst ? day : date;
        if (first != null) block.append(first);
        if (first != null && second != null) gap(block, c.gapDayDate);
        if (second != null) block.append(second);
        if (c.dateSizePct != 100)
            block.setSpan(new RelativeSizeSpan(c.dateSizePct / 100f), 0, block.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        return block;
    }

    /** Appends a space whose width is pct % of a normal space (0 = none). */
    private static void gap(SpannableStringBuilder sb, int pct) {
        if (pct <= 0) return;
        int at = sb.length();
        sb.append(' ');
        if (pct != 100) sb.setSpan(new GapSpan(pct / 100f), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** A space of adjustable width. Leaves line metrics alone so the clock doesn't move vertically. */
    private static final class GapSpan extends android.text.style.ReplacementSpan {
        private final float factor;
        GapSpan(float factor) { this.factor = factor; }
        @Override public int getSize(android.graphics.Paint p, CharSequence t, int s, int e, android.graphics.Paint.FontMetricsInt fm) {
            return Math.round(p.measureText(" ") * factor);
        }
        @Override public void draw(android.graphics.Canvas c, CharSequence t, int s, int e, float x, int top, int y, int bottom, android.graphics.Paint p) {}
    }

    private static CharSequence pattern(Config c, Date now, Locale loc) {
        SpannableStringBuilder out = new SpannableStringBuilder(new SimpleDateFormat(c.pattern, loc).format(now));
        if (c.ampm == Config.AMPM_HIDDEN && unquoted(c.pattern).indexOf('a') < 0) return out;
        // AM/PM styling also applies to custom patterns
        for (String ap : new DateFormatSymbols(loc).getAmPmStrings()) {
            int i = out.toString().indexOf(ap);
            if (i < 0) continue;
            if (c.ampmLower) out.replace(i, i + ap.length(), ap.toLowerCase(loc));
            if (c.ampm == Config.AMPM_SMALL)
                out.setSpan(new RelativeSizeSpan(AMPM_SMALL), i, i + ap.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /** Pattern with quoted literals removed (so 'sec' text doesn't count as seconds). */
    private static String unquoted(String p) {
        if (p == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        for (char ch : p.toCharArray()) {
            if (ch == '\'') { q = !q; continue; }
            if (!q) sb.append(ch);
        }
        return sb.toString();
    }
}
