#!/system/bin/sh
# Stops the helper and removes every speed limit it set. Safe to run any time.
# "Normal" = the hardware maximum: writing it only drops our request; Samsung's own heat limit keeps working.
#   sh sbmods_restore.sh        speeds only (the battery charge limit is a battery-care choice and stays)
#   sh sbmods_restore.sh all    "everything": the same, plus the charge limit back to what it was before the mod, and every file and setting
#                               the mod made (used by uninstall, by the app's "Undo everything" button and by the helper when the app is gone)
ST=/data/local/tmp/sbmods_state
G=/sys/class/kgsl/kgsl-3d0/devfreq
p=$(cat /data/local/tmp/sbmods_cpud.pid 2>/dev/null); [ -n "$p" ] && grep -q sbmods_cpud /proc/$p/cmdline 2>/dev/null && kill $p   # never a reused PID
rm -f /data/local/tmp/sbmods_cpud.pid
w=$(cat $ST/inotify.pid 2>/dev/null); [ -n "$w" ] && grep -q inotifyd /proc/$w/cmdline 2>/dev/null && kill $w   # the helper's settings watcher (never a reused PID)
rm -f $ST/inotify.pid
for p in /sys/devices/system/cpu/cpufreq/policy*; do
  m=$(stat -c %a $p/scaling_max_freq); [ -f $ST/$(basename $p).mode ] && m=$(cat $ST/$(basename $p).mode)
  chmod 644 $p/scaling_max_freq; cat $p/cpuinfo_max_freq > $p/scaling_max_freq; chmod $m $p/scaling_max_freq
done
if [ -d $G ]; then
  tr ' ' '\n' < $G/available_frequencies | sort -n | tail -1 > $G/max_freq
  [ -f $ST/gpu.min ] && cat $ST/gpu.min > $G/min_freq
fi
# parked cores: put core_ctl's saved min / max back (max first); nothing saved = nothing of ours, leave core_ctl alone
for f in $ST/cc*.max; do [ -f "$f" ] || continue; c=${f##*/cc}; c=${c%.max}; d=/sys/devices/system/cpu/cpu$c/core_ctl
  cat $ST/cc$c.max > $d/max_cpus; cat $ST/cc$c.min > $d/min_cpus; done
[ -f $ST/ps.set ] && settings put global low_power 0
# renderer: skiavk back to OpenGL (nothing is restarted; apps follow when they next open)
[ "$(getprop debug.hwui.renderer)" = "skiavk" ] && { setprop debug.hwui.renderer skiagl; settings put global sbmods_gfx opengl; }
# the battery charge limit stays unless asked ("all"): then the saved original goes back, but only if it is one of the allowed numbers
if [ "$1" = "all" ]; then
  o=$(cat /data/local/tmp/sbmods_chg.orig 2>/dev/null); cf=/sys/class/power_supply/battery/batt_full_capacity
  case "$o" in 80|85|90|95|100) [ -f $cf ] && echo $o > $cf 2>/dev/null;; esac
  rm -f /data/local/tmp/sbmods_chg.orig
fi
for k in sbmods_cpu_pct sbmods_gpu_pct sbmods_cool sbmods_cores sbmods_cmd sbmods_until; do settings put global $k 0; done
rm -rf $ST /data/local/tmp/sbmods_orig /data/local/tmp/sbmods_cpu_orig
if [ "$1" = "all" ]; then
  # every other setting the mod keeps, the one-off job notes and our own scripts (this one is deleted last; the shell has it open)
  for k in $(settings list global | grep '^sbmods_' | cut -d= -f1); do settings delete global $k >/dev/null 2>&1; done
  rm -rf /data/local/tmp/sbmods_job /data/local/tmp/sbmods_cpud.sh
  case "$0" in /data/local/tmp/sbmods_restore.sh) rm -f "$0";; esac
  echo "SBMods: everything undone"
else echo "SBMods: speeds restored"; fi
