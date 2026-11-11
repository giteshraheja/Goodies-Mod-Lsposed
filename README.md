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

## Look (branch ui-liquid)
One shared style in `app/src/dev/gitesh/sbmods/Ui.java`: cool-grey surfaces, one teal accent (#0B7A85 day / #5CD0DB night), system font,
glass on the bottom bar, info sheet and the Performance panel, solid cards elsewhere, line icons drawn in code. No XML layouts, no realtime blur in the app
(frosted fills + a highlight border); the panel uses the window blur only when the system allows it. Nothing polls or animates continuously.
- Settings app: four tabs (Layout, Clock, Panel, System). Layout and Clock pin the live preview. Long captions open from the "i" in a bottom sheet.
  Each card has Reset; less-used options sit behind an "Advanced" row; System > Restore defaults resets everything (with a confirm).
- Performance panel follows the system day / night setting. Panel cards are reordered with the drag handle in the Panel tab.
  Switch cards are tiles (wide or small icon-only, chosen in the Panel tab). The All page lists every shown card in your order; "Fill it with the cards I use most" brings back the old behaviour.
  Pin up to 3 cards (the pin in the Panel tab, or press and hold a card in the panel) and they stay at the top of the All page, in pin order.
- New global settings (written by the app with root, read by the panel): `sbmods_tiles` (small tile ids), `sbmods_allfill` (1 = All page fills itself), `sbmods_pins` (pinned card ids, in pin order, up to 3).

## Signing key
`build.sh` creates `keys/sbmods.jks` on first run. Provide its password via `SBMODS_KS_PASS` or a
git-ignored `keys/pass.txt`. Forks must use their own key and a different package name.

## License
Copyright (C) 2025-2026 Gitesh Raheja. Licensed under GPL-3.0-or-later (see `LICENSE`, `NOTICE`).
Provided without warranty; it modifies SystemUI, use at your own risk.
