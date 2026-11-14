// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Gitesh Raheja
package dev.gitesh.sbmods;

import java.util.HashMap;
import java.util.Map;

/**
 * Plain-English guidance for the system apps Android lets ignore deep sleep (the "system" tier of `dumpsys deviceidle whitelist`).
 * Built from public debloat lists, package databases, community reports and Samsung's own app names; NOT tested app by app. Everything shown is reversible.
 * ESSENTIAL apps are never offered (calls, messages, Wi-Fi calling, push, location, security, carrier set-up, emergency alerts); the shell side refuses the core of them too (wlok in sbmods_lib.sh).
 * Anything not listed here counts as CAUTION. No Android classes, so it can be tested on a plain JVM.
 */
public final class SysWhitelist {
    private SysWhitelist() {}

    public static final int ESSENTIAL = 0, CAUTION = 1, SAFE = 2;

    public static final class Info {
        public final int level;
        public final String name, why;
        Info(int level, String name, String why) { this.level = level; this.name = name; this.why = why; }
    }

    private static final Map<String, Info> M = new HashMap<>();

    private static void e(String pkg, String name) { M.put(pkg, new Info(ESSENTIAL, name, "Kept on for your safety.")); }
    private static void c(String pkg, String name, String why) { M.put(pkg, new Info(CAUTION, name, why)); }
    private static void s(String pkg, String name, String why) { M.put(pkg, new Info(SAFE, name, why)); }

    static {
        // never offered
        e("com.android.managedprovisioning", "Work profile set-up");
        e("com.android.providers.calendar", "Calendar storage");
        e("com.android.providers.downloads", "Download manager");
        e("com.android.shell", "Shell");
        e("com.google.android.cellbroadcastreceiver", "Emergency alerts");
        e("com.google.android.gms", "Google Play services");
        e("com.google.android.apps.carrier.carrierwifi", "Carrier Wi-Fi");
        e("com.osp.app.signin", "Samsung account");
        e("com.qualcomm.location", "Location engine");
        e("com.samsung.android.app.contacts", "Contacts");
        e("com.samsung.android.authfw", "Samsung authentication");
        e("com.samsung.android.callassistant", "Call assistant");
        e("com.samsung.android.cidmanager", "Carrier identification");
        e("com.samsung.android.dialer", "Phone");
        e("com.samsung.android.kgclient", "Knox Guard");
        e("com.samsung.android.kmxservice", "Knox Matrix");
        e("com.samsung.android.knox.analytics.uploader", "Knox analytics");
        e("com.samsung.android.knox.containercore", "Secure Folder core");
        e("com.samsung.android.mdm", "Device management");
        e("com.samsung.android.messaging", "Messages");
        e("com.samsung.android.privateaccesstokens", "Access tokens");
        e("com.samsung.android.sdm.config", "Carrier configuration");
        e("com.samsung.android.sm.devicesecurity", "Device security");
        e("com.samsung.klmsagent", "Knox license");
        e("com.samsung.sec.android.application.csc", "Carrier customisation");
        e("com.samsung.ssu", "SIM unlock");
        e("com.sec.android.app.safetyassurance", "Emergency SOS");
        e("com.sec.enterprise.knox.cloudmdm.smdms", "Knox Manage");
        e("com.sec.imsservice", "Calling (VoLTE / Wi-Fi calling)");
        e("com.sec.spp.push", "Samsung push service");
        e("com.sec.unifiedwfc", "Wi-Fi calling");
        e("com.sec.vsim.ericssonnsds.webapp", "Wi-Fi calling sign-in");

        // careful: a feature you use may react late
        c("com.google.android.syncadapters.calendar", "Google Calendar sync", "Calendar changes reach the phone late while it sleeps. Keep it on if you rely on calendar reminders.");
        c("com.samsung.android.app.parentalcare", "Family Care", "Screen-time rules and child location can lag. Fine to switch off if you don't use it.");
        c("com.samsung.android.app.routines", "Modes and Routines", "Time and place routines can start late. Keep it on if you use routines.");
        c("com.samsung.android.beaconmanager", "Nearby scanning (SmartTag)", "SmartTag and nearby-device finding update late. Switch off only if you have no SmartTags.");
        c("com.samsung.android.bixby.agent", "Bixby", "Bixby routines wait for the screen. Fine if you don't use Bixby.");
        c("com.samsung.android.bixby.wakeup", "Hi Bixby", "\"Hi Bixby\" will not answer while the phone sleeps. Fine if you never say it.");
        c("com.samsung.android.game.gos", "Game Optimizing Service", "Samsung's game speed and heat handling. Keep it on if you play games like PUBG.");
        c("com.samsung.android.game.gametools", "Game tools", "Game overlay and tools. Keep it on if you play games.");
        c("com.samsung.android.intellivoiceservice", "Voice intelligence", "Voice features of Galaxy AI can stutter. Fine if you don't use them.");
        c("com.samsung.android.mcfds", "Continuity (device sync)", "Galaxy Buds auto-switching and copying between Samsung devices may lag.");
        c("com.samsung.android.mcfserver", "Continuity (server)", "Hand-off between your Samsung devices may lag.");
        c("com.samsung.android.mobileservice", "Samsung services", "Samsung account sync (Notes, Gallery, Find My Mobile) can lag. Keep it on unless you never use those.");
        c("com.samsung.android.samsungpass", "Samsung Pass / Wallet", "Stored logins and digital keys may react late. Keep it on if you use Samsung Pass or a digital key.");
        c("com.samsung.android.scs", "Samsung search indexing", "Search results and text recognition update late. Mostly harmless.");
        c("com.samsung.android.server.wifi.mobilewips", "Wi-Fi protection", "Scanning for rogue Wi-Fi stops while the phone sleeps. Mostly harmless.");
        c("com.samsung.android.service.stplatform", "SmartThings", "Smart-home automations can lag. Keep it on if you use SmartThings.");

        // fine to switch off: optional features that only do something when you open them
        s("com.facebook.services", "Facebook services", "Background upkeep for Facebook apps. Nothing else uses it.");
        s("com.google.ambient.streaming", "App streaming", "Streaming phone apps to a Chromebook. Optional.");
        s("com.microsoft.appmanager", "Link to Windows", "Phone-to-PC syncing waits for the screen. Fine if you don't use it.");
        s("com.samsung.android.agent.storage", "Storage care", "Cloud storage upkeep waits for the screen.");
        s("com.samsung.android.allshare.service.mediashare", "Media sharing (Smart View)", "Casting discovery. Optional.");
        s("com.samsung.android.app.interpreter", "Interpreter", "Only works when you open it.");
        s("com.samsung.android.app.sharelive", "Live sharing", "Only used during a live share.");
        s("com.samsung.android.app.smartcapture", "Smart capture", "Screenshot tools. Only works when you use it.");
        s("com.samsung.android.app.updatecenter", "App update centre", "Samsung app updates wait for the screen.");
        s("com.samsung.android.audiomirroring", "Audio mirroring", "Casting sound to TVs and speakers. Optional.");
        s("com.samsung.android.aware.service", "Nearby device awareness", "Pop-ups for nearby Galaxy devices come late.");
        s("com.samsung.android.bluelightfilter", "Eye comfort shield", "Scheduled warm-screen changes may start when the screen turns on.");
        s("com.samsung.android.dbsc", "Service set-up", "Set-up and telemetry helper.");
        s("com.samsung.android.easysetup", "Nearby device set-up", "Pairing pop-ups for new Buds and watches. Manual pairing still works.");
        s("com.samsung.android.forest", "Digital Wellbeing", "Usage statistics are filled in when the screen is on.");
        s("com.samsung.android.hwresourceshare", "Camera sharing", "Using the phone camera on a Galaxy Book. Optional.");
        s("com.samsung.android.hwresourceshare.storage", "Storage sharing", "Sharing files with a Galaxy Book. Optional.");
        s("com.samsung.android.inputshare", "Multi Control", "Using a Galaxy Book keyboard and mouse on the phone. Optional.");
        s("com.samsung.android.mdx", "Link to Windows (support)", "Phone-to-PC linking. Optional.");
        s("com.samsung.android.mdx.kit", "Link to Windows (kit)", "Phone-to-PC linking. Optional.");
        s("com.samsung.android.rubin.app", "Customisation service", "Collects habits for suggestions. Safe to put to sleep.");
        s("com.samsung.android.sdk.handwriting", "Handwriting", "Only works while you write with the S Pen.");
        s("com.samsung.android.sdk.ocr", "Text recognition", "Only works when an app asks for it.");
        s("com.samsung.android.service.aircommand", "Air Command (S Pen)", "Only works when you use the S Pen menu.");
        s("com.samsung.android.service.airviewdictionary", "Air view dictionary", "Only works when you hover the S Pen.");
        s("com.samsung.android.service.peoplestripe", "Edge panel people", "Only works when you open the edge panel.");
        s("com.samsung.android.shortcutbackupservice", "Shortcut backup", "Home-screen shortcut backup waits for the screen.");
        s("com.samsung.android.smartsuggestions", "Smart suggestions", "App and action suggestions. Optional.");
        s("com.samsung.android.video", "Video player", "Only works when you open it.");
        s("com.sec.android.app.camera", "Camera", "Only works when you open it.");
        s("com.sec.android.app.samsungapps", "Galaxy Store", "Store updates wait for the screen.");
        s("com.sec.android.daemonapp", "Weather updates", "Weather widget refreshes when the screen turns on.");
        s("com.sec.android.easyMover", "Smart Switch", "Only used when you move data to a new phone.");
        s("com.sec.android.easyonehand", "One-handed mode", "Only works when you use it.");
        s("com.sec.android.widgetapp.easymodecontactswidget", "Easy mode contacts widget", "Widget for Easy mode. Optional.");
    }

    /** Guidance for one package; unknown packages count as CAUTION. */
    public static Info of(String pkg) {
        Info i = M.get(pkg);
        return i != null ? i : new Info(CAUTION, null, "Not checked. Leave it on unless you know what it does.");
    }

    /** True for the apps that are never offered. */
    public static boolean essential(String pkg) { return of(pkg).level == ESSENTIAL; }

    public static int count(int level) { int n = 0; for (Info i : M.values()) if (i.level == level) n++; return n; }
}
