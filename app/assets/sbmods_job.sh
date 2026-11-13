#!/system/bin/sh
# SBMods one-off jobs (root). Started by the app (JobReceiver) for ONE request, does it, writes the answer and ends.
# Nothing keeps running afterwards: no loop, no watcher, no sleep. This replaces the one-off half of sbmods_cpud.sh;
# the speed limits / core parking / cool-down are still done by that helper.
#
#   sh sbmods_job.sh <job> [kept]
#     ramclean | deepclean        answer in sbmods_result; kept = " pkg pkg " apps to spare (" " = none, worked out by the app)
#     shot                        screenshot to Pictures/Screenshots; answer in sbmods_result
#     battreport                  answer in sbmods_batt = left_min|on_sec|off_sec|discharge_mah|off_discharge_mah|capacity_mah (-1 = unknown)
#     top                         answer in sbmods_top = up to 3 "package:cpu%" comma-separated (empty = nothing above 1 %)
#     stop:<package>              force-stops one app (strictly validated, protected apps refused); answer in sbmods_result
#     gfx:vulkan | gfx:opengl     renderer for apps that start AFTER this; nothing is ever killed or restarted. Answers: sbmods_gfx, sbmods_result
#     appgfx:<package>            answer in sbmods_appgfx = <package>:vulkan|opengl|unknown
#     health                      answer in sbmods_health = asoc%|cycles|charge_limit% (-1 = unknown)
#     chargelimit:<n>             n = 80, 85, 90, 95 or 100; answer in sbmods_result, sbmods_health refreshed
#     gfxcheck                    the Wi-Fi calling check after a switch to Vulkan (the app's alarm asks for it at ~60 s and ~5 min)
#     boot                        after a restart of the phone, when the helper could not be started: show the real renderer, bring "keep Vulkan" back
#
# The answers have exactly the same form as the helper's. This script never writes sbmods_cmd (that is the helper's inbox),
# so the two never answer the same request. It prints only "ALARM <seconds>" lines for the app (0 = no check pending).
#
# Its own notes live in $JS (not the helper's $ST): a renderer check started here is done here, never by the helper as well.
B=/data/adb/sbmods   # root-only folder (see sbmods_cpud.sh)
D=${0%/*}; [ "$D" = "$0" ] && D=.; . $D/sbmods_lib.sh   # shared functions (battery report, busiest apps, Wi-Fi calling check, never-stop list, clean-up...)
JS=$B/sbmods_job
ST=$B/sbmods_state
cmd=$1; KEEP=$2
[ -n "$KEEP" ] || KEEP=" "
mkdir -p $JS; chmod 700 $B

sp() { settings put global "$@" >/dev/null 2>&1; }





# Renderer check after a switch to Vulkan: calling services restarted, or Wi-Fi calling registered before and gone now -> back to OpenGL.
# ~60 s after the switch and again at ~5 min, then it is over. Pending checks live in $JS/gfx.* (t = switch time, stage = 1 once the
# first check passed). Called too early (or by another job that happened to run) it only says how long is left.
gfxcheck() {
  [ -f $JS/gfx.t ] || { echo "ALARM 0"; return; }
  # not Vulkan any more (the phone restarted, or the helper's restore put OpenGL back): nothing left to watch
  [ "$(getprop debug.hwui.renderer)" = "skiavk" ] || { rm -f $JS/gfx.*; echo "ALARM 0"; return; }
  gl=60; [ "$(cat $JS/gfx.stage 2>/dev/null)" = "1" ] && gl=300
  el=$(( $(date +%s) - $(num "$(cat $JS/gfx.t)" 0) )); [ $el -lt 0 ] && el=$gl   # (the clock went backwards: check now)
  [ $el -ge $gl ] || { echo "ALARM $((gl - el))"; return; }
  gb=$(cat $JS/gfx.pids 2>/dev/null); gn=$(callpids); gx=0
  [ -n "${gb%%|*}" ] && [ "${gb%%|*}" != "${gn%%|*}" ] && gx=1
  [ -n "${gb#*|}" ] && [ "${gb#*|}" != "${gn#*|}" ] && gx=1
  [ "$gx" = "0" ] && [ "$(cat $JS/gfx.wfc 2>/dev/null)" = "1" ] && [ "$(wfc)" = "0" ] && gx=1
  if [ "$gx" = "1" ]; then
    setprop debug.hwui.renderer skiagl; sp sbmods_gfx opengl; sp sbmods_gfx_keep 0; rm -f $JS/gfx.*
    sp sbmods_result "Wi-Fi calling dropped after switching to Vulkan, so I switched back to OpenGL"
    echo "ALARM 0"
  elif [ "$gl" = "300" ]; then rm -f $JS/gfx.*; echo "ALARM 0"
  else echo 1 > $JS/gfx.stage; r=$((300 - el)); [ $r -lt 5 ] && r=5; echo "ALARM $r"; fi
}
# switch to Vulkan (used by gfx:vulkan and by boot): the check above keeps an eye on Wi-Fi calling afterwards.
# A check the helper had pending ($ST/gfx.*) is dropped: this switch is watched from here.
gfx_vulkan() {
  rm -f $JS/gfx.* $ST/gfx.* 2>/dev/null
  # remember how calling looked before, so the check can tell whether OUR switch broke it
  wfc > $JS/gfx.wfc; callpids > $JS/gfx.pids; echo 0 > $JS/gfx.stage; date +%s > $JS/gfx.t
  setprop debug.hwui.renderer skiavk; sp sbmods_gfx vulkan
  sp sbmods_result "Vulkan on: apps use it the next time they open"
  echo "ALARM 60"
}

case "$cmd" in
  ramclean|deepclean)
    b=$(mem); sb=$(swp); kn=""
    killbg "$KEEP"; [ "$SPARED" -gt 0 ] && kn=" (kept $SPARED app$([ "$SPARED" -gt 1 ] && echo s) you reopen)"   # (always one by one, so the never-stop list applies)
    if [ "$cmd" = "deepclean" ]; then sync; echo 3 > /proc/sys/vm/drop_caches; echo 1 > /proc/sys/vm/compact_memory; fi
    sleep 2
    a=$(mem)
    if [ "$cmd" = "deepclean" ]; then sp sbmods_result "Deep clean: +$((a-b)) MB RAM free, swap $sb% -> $(swp)%$kn"
    else sp sbmods_result "Closed background apps: +$((a-b)) MB RAM free$kn"; fi;;
  shot)
    d=/sdcard/Pictures/Screenshots; mkdir -p $d
    if screencap -p $d/sbmods_$(date +%s).png; then sp sbmods_result "Screenshot saved to Pictures/Screenshots"
    else sp sbmods_result "Couldn't take a screenshot"; fi;;
  battreport) sp sbmods_batt "$(battreport)";;
  top) sp sbmods_top "$(topapps)";;
  stop:*)
    pkg=${cmd#stop:}
    if stopok "$pkg"; then am force-stop "$pkg" >/dev/null 2>&1; sp sbmods_result "Stopped $pkg"
    else sp sbmods_result "Can't stop that one"; fi;;
  gfx:vulkan) gfx_vulkan;;   # never kill or restart anything: apps pick the renderer up when they next start by themselves
  gfx:opengl)
    rm -f $JS/gfx.* $ST/gfx.* 2>/dev/null; setprop debug.hwui.renderer skiagl; sp sbmods_gfx opengl; sp sbmods_gfx_keep 0
    sp sbmods_result "Back to OpenGL: apps switch the next time they open"
    echo "ALARM 0";;
  appgfx:*)   # (a sleeping app can take 10 s to answer: give up after 4 s -> unknown)
    pkg=${cmd#appgfx:}
    if plainok "$pkg"; then r=$(timeout 4 dumpsys gfxinfo "$pkg" 2>/dev/null | head -40 | pipeparse); else r=unknown; fi
    sp sbmods_appgfx "$pkg:$r";;
  health) sp sbmods_health "$(health)";;
  chargelimit:*)
    n=${cmd#chargelimit:}; cf=/sys/class/power_supply/battery/batt_full_capacity
    if chgok "$n" && [ -f $cf ]; then
      # the original stays outside the state folders: a charge limit is meant to last
      [ -f $B/sbmods_chg.orig ] || cat $cf > $B/sbmods_chg.orig
      echo $n > $cf 2>/dev/null
      if [ "$(cat $cf 2>/dev/null)" = "$n" ]; then sp sbmods_result "Charging now stops at $n%"
      else sp sbmods_result "This phone didn't accept the charge limit"; fi
      sp sbmods_health "$(health)"
    else sp sbmods_result "Can't set that charge limit"; fi;;
  gfxcheck) gfxcheck;;
  boot)
    # show the renderer that is really active (the property is gone after a restart), writing only when the stored setting differs
    case "$(getprop debug.hwui.renderer)" in skiavk) g=vulkan;; *) g=opengl;; esac
    [ "$(settings get global sbmods_gfx)" != "$g" ] && sp sbmods_gfx $g
    # the user chose "keep Vulkan": switch it on again, through the same guarded code as gfx:vulkan
    if [ "$g" != "vulkan" ] && [ "$(settings get global sbmods_gfx_keep)" = "1" ]; then
      gfx_vulkan; sp sbmods_result "Vulkan is back on after the restart"
    fi;;
esac
# a renderer check is pending and some other job happened to run: look at it now too (costs one file test when nothing is pending),
# so a late alarm never leaves the check undone
case "$cmd" in gfx:*|gfxcheck|boot) ;; *) [ -f $JS/gfx.t ] && gfxcheck;; esac
exit 0
