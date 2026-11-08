# StatusBar Mods (Vector/LSPosed module for SystemUI)

v2.1: phone temperature next to the clock, custom clock (builder or pattern),
tap/long-press actions (battery, clock, temperature), Simple/Expert settings with presets. Package: dev.gitesh.sbmods

- Build:     ./build.sh            -> out/StatusBarMods.apk
- Install:   cat out/StatusBarMods.apk | su -c "pm install -r -S $(stat -c %s out/StatusBarMods.apk)"  (same key in keys/ is required for updates - don't delete it)
- Restart:   su -c 'killall com.android.systemui'
- Logs:      su -c 'logcat -s SBMods'
- Remove:    ./uninstall.sh

Safety: errors never reach SystemUI; >25 errors -> mod removes itself; 3 SystemUI restarts
within 90 s of load -> crash guard keeps it off (reset in app). No root/Vector after reboot ->
nothing loads, SystemUI is stock.

Add a new item: implement items/Item.java (see TempItem), register in items/Items.java,
add its option to Config.java and a control in ui/SettingsActivity.java, bump versionCode, build.

## Signing key
`build.sh` creates `keys/sbmods.jks` on first run. Provide its password via `SBMODS_KS_PASS` or a
git-ignored `keys/pass.txt`. Forks must use their own key and a different package name.

## License
Copyright (C) 2025-2026 Gitesh Raheja. Licensed under GPL-3.0-or-later (see `LICENSE`, `NOTICE`).
Provided without warranty; it modifies SystemUI, use at your own risk.
