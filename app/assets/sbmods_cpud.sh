#!/system/bin/sh
# SBMods performance helper (root).
#
# How speed limits work on this phone: scaling_max_freq / devfreq max_freq hold OUR request; the kernel combines it
# with Samsung's own heat limit and shows the lower one. So:
#  - a limit is a share of the HARDWARE maximum (cpuinfo_max_freq / top GPU step), never of today's heat-limited value;
#  - "back to normal" writes the hardware maximum, which only removes our request (Samsung's heat limit keeps working).
#
# Battery: idle, it sleeps until the global settings folder reports a change (or checks the file once a second where that is not possible; no Java process).
# With the watcher it also sleeps right up to the next timed job instead of waking every second (see rest()). It reads the settings
# (one `settings list`) only when that file changed, or every 10 s while a limit is on. Live speeds are NOT published:
# the Overview chips read them straight from the kernel.
#
# Controls (global settings, written by the Overview panel / the app):
#   sbmods_cpu_pct, sbmods_gpu_pct   0 = no limit, else cap at about this % of the hardware maximum (never below 35)
#   sbmods_cpu_ps = 1                also turn on power saving while the CPU is limited
#   sbmods_cool = 1                  cool-down: CPU / GPU at your chosen levels (sbmods_*_pick, + power saving) until the battery is at sbmods_cool_at (tenths of °C)
#   sbmods_limit_min                 limits end by themselves after this many minutes (default 15, max 120)
#   sbmods_until                     epoch seconds when the running limit / core parking ends by itself (0 = none); follows sbmods_limit_min
#   sbmods_cmd = ramclean|deepclean  one-off jobs; the answer goes to sbmods_result
#   sbmods_cmd = battreport          answer in sbmods_batt = left_min|on_sec|off_sec|discharge_mah|off_discharge_mah|capacity_mah (-1 = unknown)
#   sbmods_cmd = top                 answer in sbmods_top = up to 3 "package:cpu%" comma-separated (empty = nothing above 1 %)
#   sbmods_cmd = stop:<package>      force-stops one app (strictly validated, protected apps refused); answer in sbmods_result
#   sbmods_cmd = gfx:vulkan|gfx:opengl   graphics renderer for apps that start AFTER this (debug.hwui.renderer skiavk / skiagl; a reboot returns to
#                                    default). Nothing is ever killed or restarted. Answers: sbmods_gfx = vulkan|opengl, sbmods_result.
#                                    After a switch to Vulkan, at ~60 s and ~5 min the helper checks Wi-Fi calling and switches back to OpenGL if it dropped
#   sbmods_gfx_keep = 1              the user chose "keep Vulkan": at helper start-up (one time) Vulkan is switched on again if the renderer is not skiavk
#                                    (the property is lost at every reboot), through the same guarded code as gfx:vulkan. Set by the status bar mod;
#                                    cleared (0) by gfx:opengl and when the Wi-Fi calling guard switches back to OpenGL
#   sbmods_cmd = appgfx:<package>    which renderer an app uses right now; answer in sbmods_appgfx = <package>:vulkan|opengl|unknown
#   sbmods_cmd = health              battery health; answer in sbmods_health = asoc%|cycles|charge_limit% (-1 = unknown)
#   sbmods_cmd = chargelimit:<n>     n = 80, 85, 90, 95 or 100: charging stops at n % (kept until you change it; not a speed limit, so not undone by
#                                    "restore"); answer in sbmods_result, and sbmods_health is refreshed
#   sbmods_watch = 1|2               1 = Overview open (total load -> sbmods_cpu_load, every 4 s, when it moved 3+ points);
#                                    2 = Performance panel open: that PLUS sbmods_core_load = 8 comma-separated % (cpu0..cpu7, -1 = offline),
#                                    every 2 s, only when some core moved 5+ points
#   sbmods_cores = S,M,P             core parking (Qualcomm core_ctl): cores to keep awake in the small / mid / prime cluster
#                                    (clamped to 2..3 / 1..4 / 0..1). Empty, 0 or malformed = no parking. Counts as "a limit is on"
#                                    (same timeout, never survives a reboot); wakes itself if the load stays >= 90 % for two 5 s samples
ST=/data/local/tmp/sbmods_state
CPU=/sys/devices/system/cpu
F=/data/system/users/0/settings_global.xml
G=/sys/class/kgsl/kgsl-3d0/devfreq
mkdir -p $ST
echo $$ > /data/local/tmp/sbmods_cpud.pid

# a clean-up request nobody answered (helper was off) must not run by itself later
settings put global sbmods_cmd none
# limits never survive a restart: if the last limit started before this boot, clear them
# (by boot count, not clock time: the clock may still be wrong this early after boot)
BOOT=$(settings get global boot_count)

# core parking: give every parked cluster its saved min / max back (max first, so min never ends above max); no saved file = nothing of ours to undo
cores_restore() { for f in $ST/cc*.max; do [ -f "$f" ] || continue; c=${f##*/cc}; c=${c%.max}; d=$CPU/cpu$c/core_ctl
  cat $ST/cc$c.max > $d/max_cpus; cat $ST/cc$c.min > $d/min_cpus; rm -f $ST/cc$c.max $ST/cc$c.min; done; }
# a kill (TERM/INT/HUP) must not leave cores parked
trap 'cores_restore; exit 0' TERM INT HUP

if [ ! -f $ST/since ] || [ "$(cat $ST/boot 2>/dev/null)" != "$BOOT" ]; then
  # (same boot but no stamp = helper died while parked: put the cores back before forgetting the saved values; after a reboot the kernel already reset them)
  [ "$(cat $ST/boot 2>/dev/null)" = "$BOOT" ] && cores_restore
  for k in sbmods_cpu_pct sbmods_gpu_pct sbmods_cool sbmods_cores sbmods_until; do settings put global $k 0; done
  [ -f $ST/ps.set ] && settings put global low_power 0   # power saving we turned on goes off with the limit
  rm -f $ST/since $ST/*.want $ST/ps.set $ST/cc*.min $ST/cc*.max $ST/gfx.*   # (a pending renderer check is dropped too; the renderer itself resets at reboot)
  UNT=0
fi

# older helper versions kept "original" values that were really Samsung's heat limit: drop those requests once
if [ -d /data/local/tmp/sbmods_orig ] || [ -d /data/local/tmp/sbmods_cpu_orig ]; then
  for p in /sys/devices/system/cpu/cpufreq/policy*; do m=$(stat -c %a $p/scaling_max_freq); chmod 644 $p/scaling_max_freq; cat $p/cpuinfo_max_freq > $p/scaling_max_freq; chmod $m $p/scaling_max_freq; done
  [ -d $G ] && { tr ' ' '\n' < $G/available_frequencies | sort -n | tail -1 > $G/max_freq; }
  rm -rf /data/local/tmp/sbmods_orig /data/local/tmp/sbmods_cpu_orig
  for k in sbmods_cpu_steps sbmods_gpu_steps sbmods_cpu_level sbmods_gpu_level sbmods_watch sbmods_cpu_mhz sbmods_gpu_mhz; do settings delete global $k >/dev/null 2>&1; done
fi

# show the renderer that is really active (the property is gone after a reboot), but only write when the stored setting differs
case "$(getprop debug.hwui.renderer)" in skiavk) g=vulkan;; *) g=opengl;; esac
[ "$(settings get global sbmods_gfx)" != "$g" ] && settings put global sbmods_gfx $g

# highest available step at or below pct % of hw ($1 list, $2 hw max, $3 pct); the lowest step if none is
pick() { echo "$1" | tr ' ' '\n' | awk -v hw="$2" -v pc="$3" 'NF{ if ($1*100 <= hw*pc && $1 > best) best=$1; if (low == "" || $1 < low) low=$1 } END{ print (best ? best : low) }'; }
num() { case "$1" in ''|*[!0-9]*) echo "$2";; *) echo "$1";; esac; }
# one setting from the last read ($S), using shell built-ins only (this runs a dozen times per pass)
val() { _v="
$S"; case "$_v" in *"
$1="*) _v=${_v##*"
$1="}; echo "${_v%%"
"*}";; esac; }
mem() { awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo; }
swp() { awk '/SwapTotal/{t=$2}/SwapFree/{f=$2}END{if(t>0)print int((t-f)*100/t); else print 0}' /proc/meminfo; }

cpu_apply() { # $1 pct (0 = no limit)
  for p in /sys/devices/system/cpu/cpufreq/policy*; do
    n=${p##*/}; hw=$(<$p/cpuinfo_max_freq)
    if [ "$1" = "0" ]; then
      [ -f $ST/$n.mode ] || continue
      chmod 644 $p/scaling_max_freq; echo $hw > $p/scaling_max_freq; chmod $(<$ST/$n.mode) $p/scaling_max_freq
      rm -f $ST/$n.mode $ST/$n.want
    else
      # same level as last pass: the target is already worked out (working it out starts three programs per group of cores)
      if [ "$1" = "$cpp" ] && [ -f $ST/$n.want ]; then t=$(<$ST/$n.want); else t=$(pick "$(<$p/scaling_available_frequencies)" $hw $1); fi; [ -n "$t" ] || continue
      [ -f $ST/$n.mode ] || stat -c %a $p/scaling_max_freq > $ST/$n.mode
      # write when the target changed, or when something raised the cap above ours (Samsung's heat limit only lowers it)
      if { [ ! -f $ST/$n.want ] || [ "$(<$ST/$n.want)" != "$t" ]; } || [ "$(<$p/scaling_max_freq)" -gt "$t" ]; then
        chmod 644 $p/scaling_max_freq   # Samsung keeps some clusters read-only; unlocked only for the write
        echo $t > $p/scaling_max_freq; chmod $(<$ST/$n.mode) $p/scaling_max_freq; echo $t > $ST/$n.want
      fi
    fi
  done
  cpp=$1
}

gpu_apply() { # $1 pct
  [ -d $G ] || return
  [ "$1" = "0" ] && [ ! -f $ST/gpu.min ] && return   # nothing of ours to undo
  av=$(<$G/available_frequencies); [ -n "$GHW" ] || GHW=$(echo "$av" | tr ' ' '\n' | sort -n | tail -1); hw=$GHW   # top GPU step: worked out once
  if [ "$1" = "0" ]; then
    [ -f $ST/gpu.min ] || return
    echo $hw > $G/max_freq; cat $ST/gpu.min > $G/min_freq; rm -f $ST/gpu.min $ST/gpu.want
  else
    if [ "$1" = "$gpp" ] && [ -f $ST/gpu.want ]; then t=$(<$ST/gpu.want); else t=$(pick "$av" $hw $1); fi; [ -n "$t" ] || return
    gpp=$1
    [ -f $ST/gpu.min ] || cat $G/min_freq > $ST/gpu.min
    [ "$(<$G/min_freq)" -gt "$t" ] && echo $t > $G/min_freq
    if { [ ! -f $ST/gpu.want ] || [ "$(<$ST/gpu.want)" != "$t" ]; } || [ "$(<$G/max_freq)" -gt "$t" ]; then echo $t > $G/max_freq; echo $t > $ST/gpu.want; fi
  fi
}

# CPU load in % since the previous call (all cores), from /proc/stat
cpuload() { L=""; read -r _l < /proc/stat; set -- $_l; shift; t=0; for v in "$@"; do t=$((t + v)); done; i=$(($4 + $5))
  [ -n "$pt" ] && [ $((t - pt)) -gt 0 ] && L=$(( 100 * ((t - pt) - (i - pi)) / (t - pt) )); pt=$t; pi=$i; }

# same, for the core-parking auto-wake (own state, so it never disturbs the 4 s sampler above)
kload() { K=""; read -r _l < /proc/stat; set -- $_l; shift; t=0; for v in "$@"; do t=$((t + v)); done; i=$(($4 + $5))
  [ -n "$kt" ] && [ $((t - kt)) -gt 0 ] && K=$(( 100 * ((t - kt) - (i - ki)) / (t - kt) )); kt=$t; ki=$i; }

# Per-core load in ONE awk pass over /proc/stat (no fork per core). $CS = previous "total:idle" per core ("-" = offline), $CP = last published list.
# Prints "changed loads state": changed = 1 when some core moved 5+ points (or went on/offline) since the published list; loads = 8 numbers, -1 = offline / no data.
coreload() { set -- $(awk -v prev="$CS" -v pub="$CP" 'BEGIN{ split(prev, P, ","); split(pub, B, ",") }
  /^cpu[0-9]/{ n = substr($1, 4) + 0; t = 0; for (i = 2; i <= NF; i++) t += $i; T[n] = t; I[n] = $5 + $6 }
  END{ ch = (pub == "" || prev == ""); for (c = 0; c < 8; c++) {
      if (c in T) { s = T[c] ":" I[c]; split(P[c + 1], q, ":"); d = T[c] - q[1]; v = (prev != "" && P[c + 1] != "-" && d > 0) ? int(100 * (d - (I[c] - q[2])) / d + 0.5) : -1 }
      else { s = "-"; v = -1 }
      if (pub != "" && prev != "") { b = B[c + 1] + 0; if ((b < 0) != (v < 0) || b - v >= 5 || v - b >= 5) ch = 1 }
      L = L (c ? "," : "") v; Q = Q (c ? "," : "") s }
    print (prev != "" ? ch : 0), L, Q }' /proc/stat)
  CS=$3; [ "$1" = "1" ] && { settings put global sbmods_core_load "$2"; CP=$2; }; }

# Core parking. Clusters come from cpufreq's related_cpus: CL = "first_cpu size" x3, sorted small / mid / prime; fails unless exactly 3 clusters each with a core_ctl dir.
cl() { CL=""; set -- $(for p in $CPU/cpufreq/policy*; do awk '{ m = $1; for (i = 2; i <= NF; i++) if ($i + 0 < m + 0) m = $i; print m, NF }' $p/related_cpus; done 2>/dev/null | sort -n)
  [ $# = 6 ] || return 1; for c in $1 $3 $5; do [ -d $CPU/cpu$c/core_ctl ] || return 1; done; CL="$*"; }
# keep $3 cores awake in the cluster starting at cpu $1 (size $2); originals saved once; max_cpus holds, Samsung keeps resetting min_cpus, so re-assert when either drifted
park1() { d=$CPU/cpu$1/core_ctl; [ $3 -gt $2 ] && set -- $1 $2 $2
  [ -f $ST/cc$1.max ] || { cat $d/min_cpus > $ST/cc$1.min; cat $d/max_cpus > $ST/cc$1.max; }
  om=$(<$ST/cc$1.min); [ "$om" -gt "$3" ] && om=$3   # min wanted: the original, but never above the target (so raising the target wakes cores again)
  if [ "$(<$d/max_cpus)" != "$3" ] || [ "$(<$d/min_cpus)" != "$om" ]; then echo $om > $d/min_cpus; echo $3 > $d/max_cpus; echo $om > $d/min_cpus; fi; }
# $1 = sbmods_cores value: "S,M,P" (one digit each) parks, anything else restores
cores_apply() {
  case "$1" in [0-9],[0-9],[0-9]) ;; *) cores_restore; pk=0; ccna=""; return;; esac
  [ -n "$CL" ] || cl || { [ -n "$ccna" ] || sp sbmods_result "Core parking isn't available on this phone"; ccna=1; pk=0; return; }
  IFS=,; set -- $1; unset IFS; a=$1; b=$2; c=$3
  [ $a -lt 2 ] && a=2; [ $a -gt 3 ] && a=3; [ $b -lt 1 ] && b=1; [ $b -gt 4 ] && b=4; [ $c -gt 1 ] && c=1
  set -- $CL; park1 $1 $2 $a; park1 $3 $4 $b; park1 $5 $6 $c; pk=1; }

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

# top apps: reads toybox `top -b -o %CPU,ARGS` on stdin (the LAST of two samples: one second apart, so it is "right now", not an average since the process started).
# Keeps only app package names (first word of ARGS: dot, only [A-Za-z0-9._:]; ":process" stripped), adds up a package's processes, prints up to 3 "pkg:pct" above 1 %.
topparse() { awk '/^Tasks:/ { split("", U); hdr = 0; next } /^%CPU\[/ { hdr = 1; next }
  hdr && $1 ~ /^[0-9.]+$/ && $2 ~ /^[A-Za-z0-9._:]+$/ { n = $2; sub(/:.*/, "", n); if (n !~ /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$/ || n ~ /sbmods_cpud/) next; U[n] += $1 }
  END { for (k = 0; k < 3; k++) { m = ""; mv = 1; for (n in U) if (!(n in D) && U[n] > mv) { m = n; mv = U[n] }
      if (m == "") break; D[m] = 1; o = o (k ? "," : "") m ":" int(U[m] + 0.5) } print o }'; }
topapps() { top -b -n 2 -d 1 -o %CPU,ARGS 2>/dev/null | topparse; }
# Wi-Fi calling (IWLAN) registered right now? Reads `dumpsys telephony.registry` on stdin. Prints 1 = yes, 0 = no, nothing = can't tell.
# Only the per-phone "mServiceState=" lines count (the dump also holds old timestamped log lines); each has several NetworkRegistrationInfo{...} parts,
# we want the WLAN one that is HOME / ROAMING. Quits at the first yes, or after 4 phones' lines (dumpsys then stops by itself).
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
# renderer check after a switch to Vulkan: calling services restarted, or Wi-Fi calling registered before and gone now -> back to OpenGL
# ~60 s after the switch and again at ~5 min, then stop. Pending checks live in $ST/gfx.* (t = switch time, stage = 1 once the first check passed)
gfxguard() {
  [ -f $ST/gfx.t ] || return
  gl=60; [ "$(cat $ST/gfx.stage 2>/dev/null)" = "1" ] && gl=300
  [ $(( ${EPOCHREALTIME%.*} - $(<$ST/gfx.t) )) -ge $gl ] || return
  gb=$(cat $ST/gfx.pids 2>/dev/null); gn=$(callpids); gx=0
  [ -n "${gb%%|*}" ] && [ "${gb%%|*}" != "${gn%%|*}" ] && gx=1
  [ -n "${gb#*|}" ] && [ "${gb#*|}" != "${gn#*|}" ] && gx=1
  [ "$gx" = "0" ] && [ "$(cat $ST/gfx.wfc 2>/dev/null)" = "1" ] && [ "$(wfc)" = "0" ] && gx=1
  if [ "$gx" = "1" ]; then
    setprop debug.hwui.renderer skiagl; sp sbmods_gfx opengl; sp sbmods_gfx_keep 0; rm -f $ST/gfx.*
    sp sbmods_result "Wi-Fi calling dropped after switching to Vulkan, so I switched back to OpenGL"
  elif [ "$gl" = "300" ]; then rm -f $ST/gfx.*
  else echo 1 > $ST/gfx.stage; fi
}
# switch to Vulkan (used by gfx:vulkan and by the start-up restore): the guard keeps watching Wi-Fi calling afterwards
gfx_vulkan() {
  # remember how calling looked before, so the guard can tell whether OUR switch broke it
  rm -f $ST/gfx.*; wfc > $ST/gfx.wfc; callpids > $ST/gfx.pids; echo 0 > $ST/gfx.stage; date +%s > $ST/gfx.t
  setprop debug.hwui.renderer skiavk; sp sbmods_gfx vulkan
  sp sbmods_result "Vulkan on: apps use it the next time they open"
}
# only plain package names may reach `am` and never the ones that would hurt the phone (or this tool)
stopok() { case "$1" in ''|*[!A-Za-z0-9._]*) return 1;; *.*) ;; *) return 1;; esac
  case "$1" in android|com.android.systemui|com.sec.android.app.launcher|dev.gitesh.sbmods|com.termux|com.android.providers.*|com.google.android.gms.*|com.google.android.gms) return 1;; esac; }

# publish when the limit ends (epoch s, 0 = none), only when it changed
pubuntil() { [ "$1" != "$UNT" ] && sp sbmods_until $1; UNT=$1; }

# Battery: waiting and "did the settings change?" use shell built-ins only. Starting `sleep` and `stat` every second
# kept a small core about 5 % busy all the time the phone was awake; this way an idle second costs next to nothing.
# A truly idle helper (no limit, nothing watching, no core parking, no pending check) does not even wake every second:
# a small toybox `inotifyd` watches the folder of the global settings file (that file is replaced by renaming, so the
# folder is watched) and writes a line into the fifo whenever something in it is created, moved in or saved.
# idle() sleeps until such a line arrives (60 s is only a safety net), nap() waits at most 1 s but also ends early on a line,
# and waitfor() sleeps for the time to the next timed job (also ending early on a line).
# Timed jobs (watch load, per-core load, core-parking check, the 10 s look-in while a limit is on) each remember the second
# ($SECONDS, a shell built-in) they last ran and run again once their own period has passed. So a wake-up that comes early
# (a settings change) neither makes a job run more often than its period nor makes it skip a turn.
# The lines are only a wake-up call: whether the settings really changed is still decided by the `-nt $ST/seen` test.
# Without a fifo or without a working watcher the helper falls back to the plain one-second nap.
rm -f $ST/wait; mkfifo $ST/wait 2>/dev/null
IDLE=""; WP=""
if [ -p $ST/wait ]; then
  exec 9<>$ST/wait   # read-write: writers never block on open, and the fifo never reports end of file
  nap() { read -t 1 _n <&9; }
  # sleep at most $1 seconds, ending early on a line from the watcher; queued lines are then thrown away (as in idle()).
  # A full timeout means nothing happened: check that the watcher still lives, else go back to the one-second nap for good.
  waitfor() {
    if read -t $1 _n <&9; then while read -t 0.05 _n <&9; do :; done
    else kill -0 $WP 2>/dev/null || IDLE=""; fi
  }
  # a watcher left over from an earlier helper run goes first (only if the PID really is an inotifyd)
  wp=$(cat $ST/inotify.pid 2>/dev/null); [ -n "$wp" ] && grep -q inotifyd /proc/$wp/cmdline 2>/dev/null && kill $wp 2>/dev/null
  rm -f $ST/inotify.pid
  if command -v inotifyd >/dev/null 2>&1; then
    inotifyd - ${F%/*}:nmw > $ST/wait 2>/dev/null <&- &
    WP=$!; echo $WP > $ST/inotify.pid
    sleep 0.3; kill -0 $WP 2>/dev/null && IDLE=1   # (start-up only) one that died at once keeps the one-second nap
  fi
  stopw() { [ -n "$WP" ] && kill $WP 2>/dev/null; rm -f $ST/inotify.pid; }
  trap 'cores_restore; stopw; exit 0' TERM INT HUP
  trap stopw EXIT
  # sleep until the settings folder reports something. After a wake-up the queued lines are thrown away (read -t 0.05: -t 0 is
  # unreliable in this shell, a fractional timeout works; with lines queued it returns at once, so this costs one 50 ms poll).
  # A timeout means nothing happened: check that the watcher still lives, else go back to the one-second nap for good.
  idle() {
    if read -t 60 _n <&9; then while read -t 0.05 _n <&9; do :; done
    else kill -0 $WP 2>/dev/null || IDLE=""; fi
  }
else nap() { sleep 1; }; fi
# the next wait. Without the watcher: the plain one-second nap. With it: if a fresh read is due (or we just wrote a setting, or the
# settings file is newer than our last read) a one-second nap; otherwise sleep until the NEXT timed job is due, i.e. the smallest
# time left over everything that is on (at least 1 s), and idle (60 s safety net) when nothing is on. All of these end early on a
# watcher line. Timed jobs: watch load 4 s (lw), per-core load 2 s (lc, watch 2 only), core parking check 5 s (lk), and the 10 s
# look-in (lr) while a limit / core parking / pending renderer check is on (on=1; the renderer check runs in that look-in).
rest() {
  if [ -z "$IDLE" ]; then nap; return; fi
  if [ "$force" != "0" ] || [ ! -f $ST/seen ] || [ $F -nt $ST/seen ]; then nap; return; fi
  if [ "$on" = "0" ] && [ "$w" != "1" ] && [ "$w" != "2" ] && [ "$pk" = "0" ]; then idle; return; fi
  wt=60; n=$SECONDS
  if [ "$w" = "1" ] || [ "$w" = "2" ]; then r=$((4 - (n - lw))); [ $r -lt $wt ] && wt=$r; fi
  if [ "$w" = "2" ]; then r=$((2 - (n - lc))); [ $r -lt $wt ] && wt=$r; fi
  if [ "$pk" = "1" ]; then r=$((5 - (n - lk))); [ $r -lt $wt ] && wt=$r; fi
  if [ "$on" = "1" ]; then r=$((10 - (n - lr))); [ $r -lt $wt ] && wt=$r; fi
  [ $wt -lt 1 ] && wt=1
  waitfor $wt
}
# a setting we write ourselves: the next pass must read fresh values (the settings file itself is saved a little later)
sp() { settings put global "$@"; force=1; }

# the user chose "keep Vulkan": the renderer property is gone after a reboot, so switch it on again (one time, here, not in the loop)
if [ "$(settings get global sbmods_gfx_keep)" = "1" ] && [ "$(getprop debug.hwui.renderer)" != "skiavk" ]; then
  gfx_vulkan; sp sbmods_result "Vulkan is back on after the restart"
fi

on=0; w=0; pk=0; hi=0; force=1; cmd=none; lw=0; lc=0; lk=0; lr=0
while true; do
  ch=0; { [ "$force" = "1" ] || [ ! -f $ST/seen ] || [ $F -nt $ST/seen ]; } && ch=1   # built-in test: no program started
  now=$SECONDS
  # Overview open: publish CPU load every 4 s, only when it moved by 3+ points (apps may not read /proc/stat; each write touches flash)
  # (watch 2 = Performance panel: includes everything watch 1 does)
  if { [ "$w" = "1" ] || [ "$w" = "2" ]; } && [ $((now - lw)) -ge 4 ]; then
    lw=$now; cpuload
    if [ -n "$L" ] && { [ -z "$pl" ] || [ $((L - pl)) -ge 3 ] || [ $((pl - L)) -ge 3 ]; }; then settings put global sbmods_cpu_load $L; pl=$L; fi
  elif [ "$w" != "1" ] && [ "$w" != "2" ]; then pt=""; pl=""; lw=$now; fi
  # Performance panel open: per-core load every 2 s, published only when some core moved 5+ points
  if [ "$w" = "2" ]; then [ $((now - lc)) -ge 2 ] && { lc=$now; coreload; }; else CS=""; CP=""; lc=$now; fi
  # cores parked: if the phone is working hard (>= 90 % twice in a row, 5 s apart) wake them up again
  if [ "$pk" = "1" ]; then
    if [ $((now - lk)) -ge 5 ]; then
      lk=$now; kload; if [ -n "$K" ] && [ "$K" -ge 90 ]; then hi=$((hi + 1)); else hi=0; fi
      if [ "$hi" -ge 2 ]; then
        sp sbmods_cores 0; cores_restore; pk=0; hi=0
        sp sbmods_result "Cores woken: the phone was working hard"
      fi
    fi
  else kt=""; hi=0; lk=$now; fi
  # nothing changed: sleep (while a limit / parking is on, still look again every 10 s for the timeout / cool-down)
  if [ "$ch" = "0" ] && { [ "$on" = "0" ] || [ $((now - lr)) -lt 10 ]; }; then rest; continue; fi
  lr=$now
  # read the settings only when they changed (or we just wrote one): `settings list` starts a whole Java program.
  # The 10 s look-in while a limit is on re-uses the last read; one-off jobs run only on a fresh read, never twice.
  cmd=none
  if [ "$ch" = "1" ]; then
    force=0; touch -r $F $ST/seen
    S=$(settings list global | grep -E '^(sbmods_|low_power=)')
    w=$(val sbmods_watch); cmd=$(val sbmods_cmd)
  fi

  case "$cmd" in
    ramclean|deepclean)
      sp sbmods_cmd none
      b=$(mem); sb=$(swp)
      am kill-all >/dev/null 2>&1
      if [ "$cmd" = "deepclean" ]; then sync; echo 3 > /proc/sys/vm/drop_caches; echo 1 > /proc/sys/vm/compact_memory; fi
      sleep 2
      a=$(mem)
      if [ "$cmd" = "deepclean" ]; then sp sbmods_result "Deep clean: +$((a-b)) MB RAM free, swap $sb% -> $(swp)%"
      else sp sbmods_result "Closed background apps: +$((a-b)) MB RAM free"; fi;;
    battreport) sp sbmods_cmd none; sp sbmods_batt "$(battreport)";;
    top) sp sbmods_cmd none; sp sbmods_top "$(topapps)";;
    stop:*)
      sp sbmods_cmd none; pkg=${cmd#stop:}
      if stopok "$pkg"; then am force-stop "$pkg" >/dev/null 2>&1; sp sbmods_result "Stopped $pkg"
      else sp sbmods_result "Can't stop that one"; fi;;
    gfx:vulkan|gfx:opengl)
      sp sbmods_cmd none
      # never kill or restart anything: apps pick the renderer up when they next start by themselves
      if [ "$cmd" = "gfx:vulkan" ]; then gfx_vulkan
      else
        rm -f $ST/gfx.*; setprop debug.hwui.renderer skiagl; sp sbmods_gfx opengl; sp sbmods_gfx_keep 0
        sp sbmods_result "Back to OpenGL: apps switch the next time they open"
      fi;;
    appgfx:*)   # (a sleeping app can take 10 s to answer: give up after 4 s -> unknown)
      sp sbmods_cmd none; pkg=${cmd#appgfx:}
      if plainok "$pkg"; then r=$(timeout 4 dumpsys gfxinfo "$pkg" 2>/dev/null | head -40 | pipeparse); else r=unknown; fi
      sp sbmods_appgfx "$pkg:$r";;
    health) sp sbmods_cmd none; sp sbmods_health "$(health)";;
    chargelimit:*)
      sp sbmods_cmd none; n=${cmd#chargelimit:}; cf=/sys/class/power_supply/battery/batt_full_capacity
      if chgok "$n" && [ -f $cf ]; then
        # the original stays outside $ST: the restore script wipes $ST, but a charge limit is meant to last
        [ -f /data/local/tmp/sbmods_chg.orig ] || cat $cf > /data/local/tmp/sbmods_chg.orig
        echo $n > $cf 2>/dev/null
        if [ "$(cat $cf 2>/dev/null)" = "$n" ]; then sp sbmods_result "Charging now stops at $n%"
        else sp sbmods_result "This phone didn't accept the charge limit"; fi
        sp sbmods_health "$(health)"
      else sp sbmods_result "Can't set that charge limit"; fi;;
  esac
  gfxguard

  cp=$(num "$(val sbmods_cpu_pct)" 0); gp=$(num "$(val sbmods_gpu_pct)" 0); ps=$(val sbmods_cpu_ps)
  # 100 = "Full" picked on purpose: stays at full speed even during cool-down
  cfull=0; gfull=0; [ "$cp" -ge 100 ] && { cp=0; cfull=1; }; [ "$gp" -ge 100 ] && { gp=0; gfull=1; }
  [ "$cp" != "0" ] && [ "$cp" -lt 35 ] && cp=35; [ "$gp" != "0" ] && [ "$gp" -lt 35 ] && gp=35
  if [ "$(val sbmods_cool)" = "1" ]; then
    at=$(num "$(val sbmods_cool_at)" 360); t=$(cat /sys/class/power_supply/battery/temp 2>/dev/null)
    if [ -n "$t" ] && [ "$t" -le "$at" ]; then
      sp sbmods_cool 0; sp sbmods_result "Cooled down to $((t/10))°C: back to normal speed"
      [ "$cfull" = "1" ] && sp sbmods_cpu_pct 0; [ "$gfull" = "1" ] && sp sbmods_gpu_pct 0
    else
      # cool-down uses the levels you picked (the panel's CPU / GPU rows), 55 % if none
      [ "$cp" = "0" ] && [ "$cfull" = "0" ] && cp=$(num "$(val sbmods_cpu_pick)" 55); [ "$gp" = "0" ] && [ "$gfull" = "0" ] && gp=$(num "$(val sbmods_gpu_pick)" 55); ps=1
    fi
  fi

  cv=$(val sbmods_cores); case "$cv" in [0-9],[0-9],[0-9]) pr=1;; *) pr=0;; esac
  # safety: a limit (or core parking) never lasts longer than the chosen time
  if [ "$cp" != "0" ] || [ "$gp" != "0" ] || [ "$pr" = "1" ]; then
    on=1
    [ -f $ST/since ] || { date +%s > $ST/since; echo "$BOOT" > $ST/boot; }
    lim=$(num "$(val sbmods_limit_min)" 15); [ "$lim" -gt 120 ] && lim=120; [ "$lim" -lt 1 ] && lim=15
    pubuntil $(( $(<$ST/since) + lim * 60 ))
    if [ $(( ${EPOCHREALTIME%.*} - $(<$ST/since) )) -gt $((lim * 60)) ]; then
      for k in sbmods_cpu_pct sbmods_gpu_pct sbmods_cool sbmods_cores; do sp $k 0; done
      sp sbmods_result "Speed limits ended after $lim min"
      cp=0; gp=0; cv=0; on=0; rm -f $ST/since; pubuntil 0
    fi
  else on=0; rm -f $ST/since; pubuntil 0; fi
  [ -f $ST/gfx.t ] && on=1   # a pending renderer check keeps the loop awake (no limit timeout for it)

  cpu_apply $cp
  gpu_apply $gp
  cores_apply "$cv"

  if [ "$cp" != "0" ] && [ "$ps" = "1" ]; then
    if [ "$(val low_power)" != "1" ]; then sp low_power 1; touch $ST/ps.set; fi
  elif [ -f $ST/ps.set ]; then sp low_power 0; rm -f $ST/ps.set; fi
  # (no re-stat here: a change you made while we were busy must still be seen; our own writes cost one extra read at most)
  rest
done
