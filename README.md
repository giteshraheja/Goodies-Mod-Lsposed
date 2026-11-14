# PulseBar

An LSPosed / Vector module for the Samsung status bar (and the Recents screen) on a rooted Galaxy S23 Ultra, plus a settings app.
Formerly "StatusBar Mods". Package `dev.gitesh.sbmods` (unchanged), version 3.26.2.

## What it does
- **Status bar items and positions:** a phone temperature next to the clock (skin, battery or CPU sensor, optional warning colour), a custom clock
  (builder or pattern: 12 / 24 hour, seconds, AM/PM style, weekday, date, year), and a live preview where you drag items to the left, middle or right
  (or back to Samsung's place). Spacing, edge padding, notification icon count and merging the Wi-Fi and mobile icons are options too.
- **Gestures:** tap, double-tap and long-press on the battery, clock, temperature, camera hole, Wi-Fi and mobile signal. Actions include power saving,
  flashlight, Wi-Fi, Bluetooth, do not disturb, lock the screen, close apps, Samsung Finder, camera, any app you pick, and more. "Test" tries one.
- **Back pill:** a small chip in the status bar that jumps back to the previous app. It can merge with a live activity or stack with it.
- **Performance panel** (on the Recents screen): RAM, swap, temperature, CPU and GPU chips. Tap for the panel: speed modes, close apps, deep clean,
  busiest apps with Stop, power saving, screen smoothness, battery report and battery health.
- **Sleep report** (Battery page): how much of the screen-off time the phone really slept (light and deep), which apps kept it awake longest, and what woke it most (Wi-Fi traffic, app timers, mobile network). Tap the app line to open that app's info.
- **Memory health** (Memory page): memory pressure (none / some / high), how much is compressed, and RAM Plus. A full swap is normal on Samsung and is no longer shown as an alarm.
- **Sleep sooner** (System tab): Samsung's own timing, Smart (learns per hour of the day how long the phone is left alone after the screen goes off), Sooner (deep sleep after about 10 minutes) or Soonest (about 3). It only changes Android's doze setting; nothing runs in the background. Also lists the apps allowed to ignore deep sleep, with a way to take them off.
- **Tips:** short reminders scroll across the status bar after unlocking; each one is shown once before any repeats.
- **Speed limits:** cap the CPU / GPU at a share of the hardware maximum, park cores, cool-down until the battery is cool. They end by themselves after a time you choose and never survive a restart.
- **Charge limit:** stop charging at 80, 85, 90, 95 or 100 %.
- **Vulkan switch:** use Vulkan graphics for apps that start afterwards, with an automatic way back if Wi-Fi calling drops.
- **Smart suggestions:** learns which apps you reopen right after a clean-up and spares them next time.
- **Self-check:** the settings app lists what is wrong in plain words (helper off, stuck limit, mod switched itself off, things Samsung changed that the mod can no longer find) with a fix button where there is one.
- **Safety:** errors never reach the status bar; too many errors remove the mod until restart; a crash loop keeps it off by itself. Without root or the module framework after a reboot, the status bar is stock.

Needs root for the helper (speed limits, clean-up, charge limit). Nothing polls while the screen is off.

## Build
`bash build.sh` makes `out/StatusBarMods.apk` (needs the Android SDK jar in `sdk-dl/android-35/`, javac, d8, aapt, zipalign, apksigner).
The signing key is kept outside the project: `${SBMODS_KEYS:-$HOME/.sbmods-keys}/sbmods.jks`, with the password in the environment variable
`SBMODS_KS_PASS` or in `pass.txt` in that folder. If the key is missing, the first build creates one. Updates must be signed with the same key, so back it up.

Install: `cat out/StatusBarMods.apk | su -c "pm install -r -S $(stat -c %s out/StatusBarMods.apk)"`, then restart the status bar (or use the button in the app). Logs: the LSPosed / Vector module log (as root, `/data/adb/lspd/log/modules_*.log`, lines tagged `[SBMods]`); logcat can be empty on this phone. The root helper (`sbmods_cpud.sh`, with the shared functions in `sbmods_lib.sh`) and its state live in `/data/adb/sbmods` (root only).

## Uninstall cleanly
Either press "Undo everything the mod changed" in the app (More tab), then uninstall it, or run `./uninstall.sh` from this folder: it first runs
`app/assets/sbmods_restore.sh all` as root (speed limits, charge limit, graphics setting, the mod's settings and files), then uninstalls the app and restarts the status bar.
If the app is removed any other way, the helper notices within a minute or so while idle and undoes the same things by itself.

## License
Copyright (C) 2025-2026 Gitesh Raheja. GPL-3.0-or-later (see `LICENSE`, `NOTICE`). No warranty; it modifies the status bar, use at your own risk.
Forks must use their own signing key and a different package name. See `SECURITY.md`.
