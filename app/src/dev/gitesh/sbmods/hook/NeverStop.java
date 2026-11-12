// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods.hook;

/** Apps the panel never offers to stop. Same list as stopok() in assets/sbmods_cpud.sh and sbmods_job.sh (keep the three in step). */
final class NeverStop {
    private NeverStop() {}

    static boolean listed(String p) {
        switch (p) {
            case "android": case "com.android.systemui": case "com.sec.android.app.launcher": case "dev.gitesh.sbmods": case "com.termux":
            case "com.android.phone": case "com.android.server.telecom": case "com.samsung.android.incallui": case "com.samsung.android.dialer":
            case "com.samsung.android.honeyboard": case "com.android.settings": case "com.topjohnwu.magisk": case "org.lsposed.manager":
            case "com.android.shell": case "com.google.android.gms": case "com.sec.imsservice": case "com.sec.epdg":
                return true;
            default:
                return p.startsWith("com.samsung.android.providers.") || p.startsWith("com.android.providers.") || p.startsWith("com.google.android.gms.");
        }
    }
}
