// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which cards the Performance panel shows, and in what order. No Android classes, so the settings app can share it
 * and it can be tested on a plain JVM. The choice lives in the global setting "sbmods_cards": comma-separated ids,
 * a leading "-" hides one (for example "modes,cpu,-gpu,cores").
 */
public final class PanelLayout {
    private PanelLayout() {}

    public static final String SETTING = "sbmods_cards";

    /** Default order. The page of each card is in PAGES, its plain-English name in TITLES (same positions). */
    public static final String[] DEFAULTS = {"modes", "cpu", "cores", "gpu", "graphics", "switches", "battery", "health", "memory", "topapps"};
    public static final String[] TITLES = {"Mode", "CPU", "Cores", "GPU", "Graphics", "Options", "Battery report", "Battery health", "Memory", "Using the most power right now"};
    /** 0 = Performance, 1 = Battery, 2 = Memory. */
    public static final int[] PAGES = {0, 0, 0, 0, 0, 0, 1, 1, 2, 2};
    public static final String[] PAGE_NAMES = {"Performance", "Battery", "Memory"};

    /**
     * The visible card ids, in order. Ids named in the setting come first (a "-" hides it); unknown ids are ignored,
     * a repeated id counts once (the first mention wins); ids the setting does not mention follow in default order,
     * so cards added in a later version simply appear. A null or empty setting gives all defaults.
     */
    public static List<String> order(String setting, String[] defaults) {
        List<String> known = Arrays.asList(defaults);
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        if (setting != null) for (String raw : setting.split(",")) {
            String t = raw.trim();
            boolean hide = t.startsWith("-");
            String id = hide ? t.substring(1).trim() : t;
            if (id.isEmpty() || !known.contains(id) || !seen.add(id)) continue;
            if (!hide) out.add(id);
        }
        for (String id : defaults) if (seen.add(id)) out.add(id);
        return out;
    }

    /** Index of an id in DEFAULTS, or -1. */
    public static int indexOf(String id) { return Arrays.asList(DEFAULTS).indexOf(id); }
}
