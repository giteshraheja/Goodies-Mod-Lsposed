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
 * and it can be tested on a plain JVM. Three global settings:
 *   sbmods_cards      comma-separated ids, a leading "-" hides one (for example "modes,cpu,-gpu,cores")
 *   sbmods_recent     the cards used last, newest first, at most RECENT_MAX (what the "All" page shows)
 *   sbmods_panel_tab  the page that was open last (0..PAGE_NAMES.length-1)
 */
public final class PanelLayout {
    private PanelLayout() {}

    public static final String SETTING = "sbmods_cards";
    public static final String RECENT = "sbmods_recent";
    public static final String TAB = "sbmods_panel_tab";
    public static final int RECENT_MAX = 5;

    /** Default order. The page of each card is in PAGES, its plain-English name in TITLES (same positions). */
    public static final String[] DEFAULTS = {"modes", "cpu", "gpu", "cores", "graphics", "switches",
            "powersave", "smooth", "battery", "health", "memory", "topapps"};
    public static final String[] TITLES = {"Mode", "CPU", "GPU", "Cores", "Graphics", "Options",
            "Power saving", "Screen smoothness", "Battery report", "Battery health", "Memory", "Busiest apps"};
    /** 0 = All (the cards used last; no card lives there), 1 = Speed, 2 = Battery, 3 = Memory. */
    public static final int[] PAGES = {1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3};
    public static final String[] PAGE_NAMES = {"All", "Speed", "Battery", "Memory"};
    public static final int PAGE_ALL = 0, PAGE_SPEED = 1, PAGE_BATTERY = 2, PAGE_MEMORY = 3;

    /**
     * The visible card ids, in order. Ids named in the setting come first (a "-" hides it); unknown ids are ignored,
     * a repeated id counts once (the first mention wins); ids the setting does not mention follow in default order,
     * so cards added in a later version simply appear. A null or empty setting gives all defaults.
     */
    public static List<String> order(String setting, String[] defaults) {
        List<String> out = new ArrayList<>();
        Set<String> hidden = new HashSet<>();
        for (String id : everything(setting, defaults, hidden)) if (!hidden.contains(id)) out.add(id);
        return out;
    }

    /** Every known card id in the owner's order, hidden ones included; the hidden ids are added to hiddenOut (may be null). */
    public static List<String> everything(String setting, String[] defaults, Set<String> hiddenOut) {
        List<String> known = Arrays.asList(defaults);
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        if (setting != null) for (String raw : setting.split(",")) {
            String t = raw.trim();
            boolean hide = t.startsWith("-");
            String id = hide ? t.substring(1).trim() : t;
            if (id.isEmpty() || !known.contains(id) || !seen.add(id)) continue;
            out.add(id);
            if (hide && hiddenOut != null) hiddenOut.add(id);
        }
        for (String id : defaults) if (seen.add(id)) out.add(id);
        return out;
    }

    /** The setting text for an order (every id, as everything() gives it) and the ids to hide. */
    public static String write(List<String> order, Set<String> hidden) {
        StringBuilder sb = new StringBuilder();
        for (String id : order) {
            if (sb.length() > 0) sb.append(',');
            if (hidden != null && hidden.contains(id)) sb.append('-');
            sb.append(id);
        }
        return sb.toString();
    }

    /** The cards used last, newest first: known ids only, each once, at most RECENT_MAX. */
    public static List<String> recent(String setting) {
        List<String> out = new ArrayList<>();
        if (setting != null) for (String raw : setting.split(",")) {
            String id = raw.trim();
            if (indexOf(id) < 0 || out.contains(id)) continue;
            out.add(id);
            if (out.size() >= RECENT_MAX) break;
        }
        return out;
    }

    /** The recent setting after card id was used: it moves to the front, the oldest drops off. Unknown ids change nothing. */
    public static String touch(String setting, String id) {
        List<String> r = recent(setting);
        if (indexOf(id) < 0) return write(r, null);
        r.remove(id);
        r.add(0, id);
        while (r.size() > RECENT_MAX) r.remove(r.size() - 1);
        return write(r, null);
    }

    /** Index of an id in DEFAULTS, or -1. */
    public static int indexOf(String id) { return Arrays.asList(DEFAULTS).indexOf(id); }
}
