#!/data/data/com.termux/files/usr/bin/bash
# Removes StatusBar Mods completely and restarts SystemUI (stock again).
su -c 'pm uninstall dev.gitesh.sbmods; killall com.android.systemui'
