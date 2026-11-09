#!/system/bin/sh
# SBMods performance helper (root).
#
# How speed limits work on this phone: scaling_max_freq / devfreq max_freq hold OUR request; the kernel combines it
# with Samsung's own heat limit and shows the lower one. So:
#  - a limit is a share of the HARDWARE maximum (cpuinfo_max_freq / top GPU step), never of today's heat-limited value;
#  - "back to normal" writes the hardware maximum, which only removes our request (Samsung's heat limit keeps working).
#
# Battery: idle, it only stats the global settings file once a second (no Java process). It reads the settings
# (one `settings list`) only when that file changed, or every 10 s while a limit is on. Live speeds are NOT published:
# the Overview chips read them straight from the kernel.
#
# Controls (global settings, written by the Overview panel / the app):
#   sbmods_cpu_pct, sbmods_gpu_pct   0 = no limit, else cap at about this % of the hardware maximum (never below 35)
#   sbmods_cpu_ps = 1                also turn on power saving while the CPU is limited
#   sbmods_cool = 1                  cool-down: CPU / GPU at your chosen levels (sbmods_*_pick, + power saving) until the battery is at sbmods_cool_at (tenths of °C)
#   sbmods_limit_min                 limits end by themselves after this many minutes (default 15, max 120)
#   sbmods_cmd = ramclean|deepclean  one-off jobs; the answer goes to sbmods_result
#   sbmods_cmd = battreport          answer in sbmods_batt = left_min|on_sec|off_sec|discharge_mah|off_discharge_mah|capacity_mah (-1 = unknown)
#   sbmods_cmd = top                 answer in sbmods_top = up to 3 "package:cpu%" comma-separated (empty = nothing above 1 %)
#   sbmods_cmd = stop:<package>      force-stops one app (strictly validated, protected apps refused); answer in sbmods_result
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
  for k in sbmods_cpu_pct sbmods_gpu_pct sbmods_cool sbmods_cores; do settings put global $k 0; done
  [ -f $ST/ps.set ] && settings put global low_power 0   # power saving we turned on goes off with the limit
  rm -f $ST/since $ST/*.want $ST/ps.set $ST/cc*.min $ST/cc*.max
fi

# older helper versions kept "original" values that were really Samsung's heat limit: drop those requests once
if [ -d /data/local/tmp/sbmods_orig ] || [ -d /data/local/tmp/sbmods_cpu_orig ]; then
  for p in /sys/devices/system/cpu/cpufreq/policy*; do m=$(stat -c %a $p/scaling_max_freq); chmod 644 $p/scaling_max_freq; cat $p/cpuinfo_max_freq > $p/scaling_max_freq; chmod $m $p/scaling_max_freq; done
  [ -d $G ] && { tr ' ' '\n' < $G/available_frequencies | sort -n | tail -1 > $G/max_freq; }
  rm -rf /data/local/tmp/sbmods_orig /data/local/tmp/sbmods_cpu_orig
  for k in sbmods_cpu_steps sbmods_gpu_steps sbmods_cpu_level sbmods_gpu_level sbmods_watch sbmods_cpu_mhz sbmods_gpu_mhz; do settings delete global $k >/dev/null 2>&1; done
fi

# highest available step at or below pct % of hw ($1 list, $2 hw max, $3 pct); the lowest step if none is
pick() { echo "$1" | tr ' ' '\n' | awk -v hw="$2" -v pc="$3" 'NF{ if ($1*100 <= hw*pc && $1 > best) best=$1; if (low == "" || $1 < low) low=$1 } END{ print (best ? best : low) }'; }
num() { case "$1" in ''|*[!0-9]*) echo "$2";; *) echo "$1";; esac; }
val() { echo "$S" | sed -n "s/^$1=//p" | head -1; }
mem() { awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo; }
swp() { awk '/SwapTotal/{t=$2}/SwapFree/{f=$2}END{if(t>0)print int((t-f)*100/t); else print 0}' /proc/meminfo; }

cpu_apply() { # $1 pct (0 = no limit)
  for p in /sys/devices/system/cpu/cpufreq/policy*; do
    n=$(basename $p); hw=$(cat $p/cpuinfo_max_freq)
    if [ "$1" = "0" ]; then
      [ -f $ST/$n.mode ] || continue
      chmod 644 $p/scaling_max_freq; echo $hw > $p/scaling_max_freq; chmod $(cat $ST/$n.mode) $p/scaling_max_freq
      rm -f $ST/$n.mode $ST/$n.want
    else
      t=$(pick "$(cat $p/scaling_available_frequencies)" $hw $1); [ -n "$t" ] || continue
      [ -f $ST/$n.mode ] || stat -c %a $p/scaling_max_freq > $ST/$n.mode
      # write when the target changed, or when something raised the cap above ours (Samsung's heat limit only lowers it)
      if [ "$(cat $ST/$n.want 2>/dev/null)" != "$t" ] || [ "$(cat $p/scaling_max_freq)" -gt "$t" ]; then
        chmod 644 $p/scaling_max_freq   # Samsung keeps some clusters read-only; unlocked only for the write
        echo $t > $p/scaling_max_freq; chmod $(cat $ST/$n.mode) $p/scaling_max_freq; echo $t > $ST/$n.want
      fi
    fi
  done
}

gpu_apply() { # $1 pct
  [ -d $G ] || return
  av=$(cat $G/available_frequencies); hw=$(echo "$av" | tr ' ' '\n' | sort -n | tail -1)
  if [ "$1" = "0" ]; then
    [ -f $ST/gpu.min ] || return
    echo $hw > $G/max_freq; cat $ST/gpu.min > $G/min_freq; rm -f $ST/gpu.min $ST/gpu.want
  else
    t=$(pick "$av" $hw $1); [ -n "$t" ] || return
    [ -f $ST/gpu.min ] || cat $G/min_freq > $ST/gpu.min
    [ "$(cat $G/min_freq)" -gt "$t" ] && echo $t > $G/min_freq
    if [ "$(cat $ST/gpu.want 2>/dev/null)" != "$t" ] || [ "$(cat $G/max_freq)" -gt "$t" ]; then echo $t > $G/max_freq; echo $t > $ST/gpu.want; fi
  fi
}

# CPU load in % since the previous call (all cores), from /proc/stat
cpuload() { L=""; set -- $(head -1 /proc/stat); shift; t=0; for v in "$@"; do t=$((t + v)); done; i=$(($4 + $5))
  [ -n "$pt" ] && [ $((t - pt)) -gt 0 ] && L=$(( 100 * ((t - pt) - (i - pi)) / (t - pt) )); pt=$t; pi=$i; }

# same, for the core-parking auto-wake (own state, so it never disturbs the 4 s sampler above)
kload() { K=""; set -- $(head -1 /proc/stat); shift; t=0; for v in "$@"; do t=$((t + v)); done; i=$(($4 + $5))
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
  if [ "$(cat $d/max_cpus)" != "$3" ] || [ "$(cat $d/min_cpus)" -gt "$3" ]; then [ "$(cat $d/min_cpus)" -gt "$3" ] && echo $3 > $d/min_cpus; echo $3 > $d/max_cpus; fi; }
# $1 = sbmods_cores value: "S,M,P" (one digit each) parks, anything else restores
cores_apply() {
  case "$1" in [0-9],[0-9],[0-9]) ;; *) cores_restore; pk=0; ccna=""; return;; esac
  [ -n "$CL" ] || cl || { [ -n "$ccna" ] || settings put global sbmods_result "Core parking isn't available on this phone"; ccna=1; pk=0; return; }
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
  hdr && $1 ~ /^[0-9.]+$/ && $2 ~ /^[A-Za-z0-9._:]+$/ { n = $2; sub(/:.*/, "", n); if (n !~ /\./ || n ~ /^\./ || n ~ /sbmods_cpud/) next; U[n] += $1 }
  END { for (k = 0; k < 3; k++) { m = ""; mv = 1; for (n in U) if (!(n in D) && U[n] > mv) { m = n; mv = U[n] }
      if (m == "") break; D[m] = 1; o = o (k ? "," : "") m ":" int(U[m] + 0.5) } print o }'; }
topapps() { top -b -n 2 -d 1 -o %CPU,ARGS 2>/dev/null | topparse; }
# only plain package names may reach `am` and never the ones that would hurt the phone (or this tool)
stopok() { case "$1" in ''|*[!A-Za-z0-9._]*) return 1;; *.*) ;; *) return 1;; esac
  case "$1" in android|com.android.systemui|com.sec.android.app.launcher|dev.gitesh.sbmods|com.termux|com.android.providers.*|com.google.android.gms.*|com.google.android.gms) return 1;; esac; }

last=""; tick=0; on=0; w=0; pk=0; hi=0
while true; do
  m=$(stat -c %y $F 2>/dev/null)
  tick=$((tick + 1))
  # Overview open: publish CPU load every 4 s, only when it moved by 3+ points (apps may not read /proc/stat; each write touches flash)
  # (watch 2 = Performance panel: includes everything watch 1 does)
  if { [ "$w" = "1" ] || [ "$w" = "2" ]; } && [ $((tick % 4)) = 0 ]; then
    cpuload
    if [ -n "$L" ] && { [ -z "$pl" ] || [ $((L - pl)) -ge 3 ] || [ $((pl - L)) -ge 3 ]; }; then settings put global sbmods_cpu_load $L; pl=$L; fi
  elif [ "$w" != "1" ] && [ "$w" != "2" ]; then pt=""; pl=""; fi
  # Performance panel open: per-core load every 2 s, published only when some core moved 5+ points
  if [ "$w" = "2" ]; then [ $((tick % 2)) = 0 ] && coreload; else CS=""; CP=""; fi
  # cores parked: if the phone is working hard (>= 90 % twice in a row, 5 s apart) wake them up again
  if [ "$pk" = "1" ]; then
    if [ $((tick % 5)) = 0 ]; then
      kload; if [ -n "$K" ] && [ "$K" -ge 90 ]; then hi=$((hi + 1)); else hi=0; fi
      if [ "$hi" -ge 2 ]; then
        settings put global sbmods_cores 0; cores_restore; pk=0; hi=0
        settings put global sbmods_result "Cores woken: the phone was working hard"
      fi
    fi
  else kt=""; hi=0; fi
  # nothing changed: sleep (while a limit / parking is on, still look again every 10 s for the timeout / cool-down)
  if [ "$m" = "$last" ] && { [ "$on" = "0" ] || [ $((tick % 10)) != 0 ]; }; then sleep 1; continue; fi
  last=$m
  S=$(settings list global | grep -E '^(sbmods_|low_power=)')
  w=$(val sbmods_watch)

  cmd=$(val sbmods_cmd)
  case "$cmd" in
    ramclean|deepclean)
      settings put global sbmods_cmd none
      b=$(mem); sb=$(swp)
      am kill-all >/dev/null 2>&1
      if [ "$cmd" = "deepclean" ]; then sync; echo 3 > /proc/sys/vm/drop_caches; echo 1 > /proc/sys/vm/compact_memory; fi
      sleep 2
      a=$(mem)
      if [ "$cmd" = "deepclean" ]; then settings put global sbmods_result "Deep clean: +$((a-b)) MB RAM free, swap $sb% -> $(swp)%"
      else settings put global sbmods_result "Closed background apps: +$((a-b)) MB RAM free"; fi;;
    battreport) settings put global sbmods_cmd none; settings put global sbmods_batt "$(battreport)";;
    top) settings put global sbmods_cmd none; settings put global sbmods_top "$(topapps)";;
    stop:*)
      settings put global sbmods_cmd none; pkg=${cmd#stop:}
      if stopok "$pkg"; then am force-stop "$pkg" >/dev/null 2>&1; settings put global sbmods_result "Stopped $pkg"
      else settings put global sbmods_result "Can't stop that one"; fi;;
  esac

  cp=$(num "$(val sbmods_cpu_pct)" 0); gp=$(num "$(val sbmods_gpu_pct)" 0); ps=$(val sbmods_cpu_ps)
  # 100 = "Full" picked on purpose: stays at full speed even during cool-down
  cfull=0; gfull=0; [ "$cp" -ge 100 ] && { cp=0; cfull=1; }; [ "$gp" -ge 100 ] && { gp=0; gfull=1; }
  [ "$cp" != "0" ] && [ "$cp" -lt 35 ] && cp=35; [ "$gp" != "0" ] && [ "$gp" -lt 35 ] && gp=35
  if [ "$(val sbmods_cool)" = "1" ]; then
    at=$(num "$(val sbmods_cool_at)" 360); t=$(cat /sys/class/power_supply/battery/temp 2>/dev/null)
    if [ -n "$t" ] && [ "$t" -le "$at" ]; then
      settings put global sbmods_cool 0; settings put global sbmods_result "Cooled down to $((t/10))°C: back to normal speed"
      [ "$cfull" = "1" ] && settings put global sbmods_cpu_pct 0; [ "$gfull" = "1" ] && settings put global sbmods_gpu_pct 0
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
    if [ $(( $(date +%s) - $(cat $ST/since) )) -gt $((lim * 60)) ]; then
      for k in sbmods_cpu_pct sbmods_gpu_pct sbmods_cool sbmods_cores; do settings put global $k 0; done
      settings put global sbmods_result "Speed limits ended after $lim min"
      cp=0; gp=0; cv=0; on=0; rm -f $ST/since
    fi
  else on=0; rm -f $ST/since; fi

  cpu_apply $cp
  gpu_apply $gp
  cores_apply "$cv"

  if [ "$cp" != "0" ] && [ "$ps" = "1" ]; then
    if [ "$(val low_power)" != "1" ]; then settings put global low_power 1; touch $ST/ps.set; fi
  elif [ -f $ST/ps.set ]; then settings put global low_power 0; rm -f $ST/ps.set; fi
  # (no re-stat here: a change you made while we were busy must still be seen; our own writes cost one extra read at most)
  sleep 1
done
