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
ST=/data/local/tmp/sbmods_state
F=/data/system/users/0/settings_global.xml
G=/sys/class/kgsl/kgsl-3d0/devfreq
mkdir -p $ST
echo $$ > /data/local/tmp/sbmods_cpud.pid

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

last=""; tick=0; on=0; w=0
while true; do
  m=$(stat -c %y $F 2>/dev/null)
  tick=$((tick + 1))
  # Overview open: publish CPU load every 2 s (apps may not read /proc/stat themselves)
  if [ "$w" = "1" ] && [ $((tick % 2)) = 0 ]; then
    cpuload; [ -n "$L" ] && settings put global sbmods_cpu_load $L
    [ "$m" = "$last" ] && { sleep 0.3; last=$(stat -c %y $F 2>/dev/null); m=$last; }
  elif [ "$w" != "1" ]; then pt=""; fi
  # nothing changed: sleep (while a limit is on, still look again every 10 s for the timeout / cool-down)
  if [ "$m" = "$last" ] && { [ "$on" = "0" ] || [ $((tick % 10)) != 0 ]; }; then sleep 1; continue; fi
  last=$m
  S=$(settings list global | grep -E '^(sbmods_|low_power=)')
  w=$(val sbmods_watch)

  cmd=$(val sbmods_cmd)
  if [ "$cmd" = "ramclean" ] || [ "$cmd" = "deepclean" ]; then
    settings put global sbmods_cmd none
    b=$(mem); sb=$(swp)
    am kill-all >/dev/null 2>&1
    if [ "$cmd" = "deepclean" ]; then sync; echo 3 > /proc/sys/vm/drop_caches; echo 1 > /proc/sys/vm/compact_memory; fi
    sleep 2
    a=$(mem)
    if [ "$cmd" = "deepclean" ]; then settings put global sbmods_result "Deep clean: +$((a-b)) MB RAM free, swap $sb% -> $(swp)%"
    else settings put global sbmods_result "Closed background apps: +$((a-b)) MB RAM free"; fi
  fi

  cp=$(num "$(val sbmods_cpu_pct)" 0); gp=$(num "$(val sbmods_gpu_pct)" 0); ps=$(val sbmods_cpu_ps)
  [ "$cp" -ge 100 ] && cp=0; [ "$gp" -ge 100 ] && gp=0
  [ "$cp" != "0" ] && [ "$cp" -lt 35 ] && cp=35; [ "$gp" != "0" ] && [ "$gp" -lt 35 ] && gp=35
  if [ "$(val sbmods_cool)" = "1" ]; then
    at=$(num "$(val sbmods_cool_at)" 360); t=$(cat /sys/class/power_supply/battery/temp 2>/dev/null)
    if [ -n "$t" ] && [ "$t" -le "$at" ]; then
      settings put global sbmods_cool 0; settings put global sbmods_result "Cooled down to $((t/10))°C: back to normal speed"
    else
      # cool-down uses the levels you picked (the panel's CPU / GPU rows), 55 % if none
      [ "$cp" = "0" ] && cp=$(num "$(val sbmods_cpu_pick)" 55); [ "$gp" = "0" ] && gp=$(num "$(val sbmods_gpu_pick)" 55); ps=1
    fi
  fi

  # safety: a limit never lasts longer than the chosen time
  if [ "$cp" != "0" ] || [ "$gp" != "0" ]; then
    on=1
    [ -f $ST/since ] || date +%s > $ST/since
    lim=$(num "$(val sbmods_limit_min)" 15); [ "$lim" -gt 120 ] && lim=120; [ "$lim" -lt 1 ] && lim=15
    if [ $(( $(date +%s) - $(cat $ST/since) )) -gt $((lim * 60)) ]; then
      for k in sbmods_cpu_pct sbmods_gpu_pct sbmods_cool; do settings put global $k 0; done
      settings put global sbmods_result "Speed limits ended after $lim min"
      cp=0; gp=0; on=0; rm -f $ST/since
    fi
  else on=0; rm -f $ST/since; fi

  cpu_apply $cp
  gpu_apply $gp

  if [ "$cp" != "0" ] && [ "$ps" = "1" ]; then
    if [ "$(val low_power)" != "1" ]; then settings put global low_power 1; touch $ST/ps.set; fi
  elif [ -f $ST/ps.set ]; then settings put global low_power 0; rm -f $ST/ps.set; fi
  last=$(stat -c %y $F 2>/dev/null)   # our own writes above must not count as a change
  sleep 1
done
