#!/data/data/com.termux/files/usr/bin/bash
# Removes StatusBar Mods completely and restarts the status bar (stock again).
# First undoes everything the mod changed (speed limits, charge limit, renderer, settings) with the restore script that sits next to this one;
# if that fails the app is still uninstalled.
D="$(cd "$(dirname "$0")" && pwd)"
R="$D/app/assets/sbmods_restore.sh"
if [ -f "$R" ]; then su -c "sh '$R' all" || echo "Undo failed; uninstalling anyway" >&2
else echo "sbmods_restore.sh not found next to this script; uninstalling anyway" >&2; fi
su -c 'pm uninstall dev.gitesh.sbmods; killall com.android.systemui'
