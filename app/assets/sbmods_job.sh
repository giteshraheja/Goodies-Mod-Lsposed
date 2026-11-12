#!/system/bin/sh
# SBMods one-off jobs (root). Started by the app (JobReceiver) for ONE request, does it, writes the answer and ends.
# Nothing keeps running afterwards: no loop, no watcher, no sleep. This replaces the one-off half of sbmods_cpud.sh;
# the speed limits / core parking / cool-down are still done by that helper.
#
#   sh sbmods_job.sh <job> [kept]
#     ramclean | deepclean        answer in sbmods_result; kept = " pkg pkg " apps to spare (" " = none, worked out by the app)
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
JS=/data/local/tmp/sbmods_job
ST=/data/local/tmp/sbmods_state
cmd=$1; KEEP=$2
[ -n "$KEEP" ] || KEEP=" "
mkdir -p $JS

sp() { settings put global "$@" >/dev/null 2>&1; }
num() { case "$1" in ''|*[!0-9]*) echo "$2";; *) echo "$1";; esac; }
mem() { awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo; }
swp() { awk '/SwapTotal/{t=$2}/SwapFree/{f=$2}END{if(t>0)print int((t-f)*100/t); else print 0}' /proc/meminfo; }

# battery report: reads `dumpsys batterystats` on stdin (slow and huge, so it quits as soon as the "Screen off discharge" line has been seen).
# Prints left_min|on_sec|off_sec|discharge_mah|off_discharge_mah|capacity_mah, -1 for anything not found.
battparse() { awk 'function dur(s,  i, ch, n, u, t) { sub(/\(.*/, "", s); n = ""; u = ""; t = 0
    for (i = 1; i <= length(s) + 1; i++) { ch = substr(s, i, 1)
      if (ch ~ /[0-9.]/) { if (u != "") { t += un(n, u); n = ""; u = "" } n = n ch }
      else if (ch ~ /[a-z]/) u = u ch
      else if (u != "") { t += un(n, u); n = ""; u = "" } }
    return t }
  function un(n, u) { return n * (u == "d" ? 86400 : u == "h" ? 3600 : u == "m" ? 60 : u == "s" ? 1 : u == "ms" ? 0.001 : 0) }
  function mah(s) { sub(/ *mAh.*/, "", s); return int(s + 0.5) }
  BEGIN { rem = on = off = dis = od = cap = -1 }
  /^ *Estimated discharge time remaining:/ && rem < 0 { s = $0; sub(/^[^:]*: */, "", s); rem = int(dur(s) / 60) }
  /^ *Estimated battery capacity:/ && cap < 0 { s = $0; sub(/^[^:]*: */, "", s); cap = mah(s) }
  /^ *Time on battery:/ && on < 0 { s = $0; sub(/^[^:]*: */, "", s); on = int(dur(s) + 0.5) }
  /^ *Time on battery screen off:/ && off < 0 { s = $0; sub(/^[^:]*: */, "", s); off = int(dur(s) + 0.5) }
  /^ *Discharge:/ && dis < 0 { s = $0; sub(/^[^:]*: */, "", s); dis = mah(s) }
  /^ *Screen off discharge:/ && od < 0 { s = $0; sub(/^[^:]*: */, "", s); od = mah(s); exit }
  END { print rem "|" (on >= 0 && off >= 0 ? on - off : -1) "|" off "|" dis "|" od "|" cap }'; }
battreport() { dumpsys batterystats 2>/dev/null | battparse; }

# top apps: reads toybox `top -b -o %CPU,ARGS` on stdin (the LAST of two samples: one second apart, so it is "right now").
# Keeps only app package names, adds up a package's processes, prints up to 3 "pkg:pct" above 1 %.
topparse() { awk '/^Tasks:/ { split("", U); hdr = 0; next } /^%CPU\[/ { hdr = 1; next }
  hdr && $1 ~ /^[0-9.]+$/ && $2 ~ /^[A-Za-z0-9._:]+$/ { n = $2; sub(/:.*/, "", n); if (n !~ /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$/ || n ~ /sbmods_(cpud|job)/) next; U[n] += $1 }
  END { for (k = 0; k < 3; k++) { m = ""; mv = 1; for (n in U) if (!(n in D) && U[n] > mv) { m = n; mv = U[n] }
      if (m == "") break; D[m] = 1; o = o (k ? "," : "") m ":" int(U[m] + 0.5) } print o }'; }
topapps() { top -b -n 2 -d 1 -o %CPU,ARGS 2>/dev/null < /dev/null | topparse; }

# Wi-Fi calling (IWLAN) registered right now? Reads `dumpsys telephony.registry` on stdin. Prints 1 = yes, 0 = no, nothing = can't tell.
wfcparse() { awk '/^ *mServiceState=/ { seen++; n = split($0, P, "NetworkRegistrationInfo[{]")
    for (i = 2; i <= n; i++) if (P[i] ~ /transportType=WLAN/ && P[i] ~ /registrationState=(HOME|ROAMING)/) { yes = 1; exit }
    if (seen >= 4) exit }
  END { if (yes) print 1; else if (seen) print 0 }'; }
wfc() { dumpsys telephony.registry 2>/dev/null | wfcparse; }
# renderer of an app: the first "Pipeline=" line (in the first 40 lines of `dumpsys gfxinfo`) on stdin -> vulkan | opengl | unknown
pipeparse() { awk '/Pipeline=/ { f = 1; print ($0 ~ /Vulkan/ ? "vulkan" : $0 ~ /OpenGL/ ? "opengl" : "unknown"); exit } END { if (!f) print "unknown" }'; }
# PIDs of Samsung's calling services as "ims|epdg" (a changed or missing PID means it was restarted)
callpids() { echo "$(pidof com.sec.imsservice 2>/dev/null | tr ' ' ,)|$(pidof com.sec.epdg 2>/dev/null | tr ' ' ,)"; }
# battery health: asoc % | cycles | charge limit %, -1 for anything missing / not a number
health() { b=/sys/class/power_supply/battery; echo "$(num "$(cat $b/fg_asoc 2>/dev/null)" -1)|$(num "$(cat $b/battery_cycle 2>/dev/null)" -1)|$(num "$(cat $b/batt_full_capacity 2>/dev/null)" -1)"; }
chgok() { case "$1" in 80|85|90|95|100) return 0;; esac; return 1; }
# a plain package name for READING (dot required, nothing else but [A-Za-z0-9._]); unlike stopok it has no protected list
plainok() { case "$1" in ''|*[!A-Za-z0-9._]*) return 1;; *.*) return 0;; esac; return 1; }
# only plain package names may reach `am` and never the ones that would hurt the phone (or this tool).
# The calling services (Wi-Fi calling) are refused here too, so no job can ever stop them.
stopok() { case "$1" in ''|*[!A-Za-z0-9._]*) return 1;; *.*) ;; *) return 1;; esac
  case "$1" in android|com.android.systemui|com.sec.android.app.launcher|dev.gitesh.sbmods|com.termux|com.android.phone|com.android.server.telecom|com.samsung.android.incallui|com.samsung.android.dialer|com.samsung.android.honeyboard|com.android.settings|com.topjohnwu.magisk|org.lsposed.manager|com.samsung.android.providers.*|com.android.shell|com.android.providers.*|com.google.android.gms.*|com.google.android.gms|com.sec.imsservice|com.sec.epdg) return 1;; esac; }

# kill the cached background apps one by one, except kept ones ($1 = " a b "): oom_score_adj >= 900, package from cmdline (":suffix" cut),
# each package once, nothing stopok refuses (and never the calling services). Sets KILLED and SPARED (kept apps that were running).
killbg() {
  _seen=" "; KILLED=0; SPARED=0
  for _d in /proc/[0-9]*; do
    _a=; read -r _a 2>/dev/null < $_d/oom_score_adj   # (a failed read is not an error here: the pid may be gone)
    case "$_a" in ''|*[!0-9]*) continue;; esac
    [ "$_a" -ge 900 ] || continue
    _n=; read -r _n 2>/dev/null < $_d/cmdline
    _n=${_n%%:*}
    case "$_seen" in *" $_n "*) continue;; esac
    _seen="$_seen$_n "
    case "$_n" in com.sec.imsservice|com.sec.epdg) continue;; esac
    stopok "$_n" || continue
    case "$1" in *" $_n "*) SPARED=$((SPARED+1)); continue;; esac
    am kill "$_n" >/dev/null 2>&1; KILLED=$((KILLED+1))
  done
}

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
      [ -f /data/local/tmp/sbmods_chg.orig ] || cat $cf > /data/local/tmp/sbmods_chg.orig
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
