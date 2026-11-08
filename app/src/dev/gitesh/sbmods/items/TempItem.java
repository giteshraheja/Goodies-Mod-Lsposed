// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.items;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.util.Locale;

import dev.gitesh.sbmods.Config;
import dev.gitesh.sbmods.hook.Sensors;

/** Phone temperature, e.g. "34". */
public final class TempItem implements Item {
    @Override public String key() { return "temp"; }

    @Override public boolean enabled(Config c) { return c.tempEnabled; }

    @Override
    public CharSequence render(Context ctx, Config c, Sensors s) {
        return format(c, s.temperature(ctx, c.sensor));
    }

    /** Shared with the settings preview. Returns null when hidden. */
    public static CharSequence format(Config c, float t) {
        if (Float.isNaN(t)) return null;
        if (c.showAbove > 0 && t < c.showAbove) return null;

        String num = c.decimal
                ? String.format(Locale.US, "%.1f", t)
                : String.valueOf(Math.round(t));
        String suffix = c.suffix == Config.SUFFIX_DEG ? "°"
                : c.suffix == Config.SUFFIX_DEG_C ? "°C" : "";
        String text = num + suffix;

        if (c.warnAt > 0 && t >= c.warnAt) {
            SpannableString sp = new SpannableString(text);
            sp.setSpan(new ForegroundColorSpan(c.warnColor), 0, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return sp;
        }
        return text;
    }
}
