// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.items;

/** Registry of all status bar items, shown left-to-right in this order. */
public final class Items {
    public static final Item[] ALL = {
            new TempItem(),
            // new NetSpeedItem(), new CpuFreqItem(), ... add future items here
    };

    /** Text placed between two visible items. */
    public static final String SEPARATOR = "  ";

    private Items() {}
}
