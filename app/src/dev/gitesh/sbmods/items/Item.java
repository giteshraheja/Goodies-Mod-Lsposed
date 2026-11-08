// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.items;

import android.content.Context;

import dev.gitesh.sbmods.Config;
import dev.gitesh.sbmods.hook.Sensors;

/**
 * One piece of text shown next to the clock. To add a new mod item:
 *  1. implement this interface (see TempItem),
 *  2. register it in Items.ALL,
 *  3. add its on/off option to Config + the settings screen.
 * render() runs on a background thread; it must be fast and must not touch views.
 */
public interface Item {
    String key();

    boolean enabled(Config c);

    /** Text to show, or null/empty to hide this item. */
    CharSequence render(Context ctx, Config c, Sensors s) throws Exception;
}
