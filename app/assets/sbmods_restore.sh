#!/system/bin/sh
# Stops the helper and removes every speed limit it set. Safe to run any time.
# "Normal" = the hardware maximum: writing it only drops our request; Samsung's own heat limit keeps working.
ST=/data/local/tmp/sbmods_state
G=/sys/class/kgsl/kgsl-3d0/devfreq
p=$(cat /data/local/tmp/sbmods_cpud.pid 2>/dev/null); [ -n "$p" ] && grep -q sbmods_cpud /proc/$p/cmdline 2>/dev/null && kill $p   # never a reused PID
rm -f /data/local/tmp/sbmods_cpud.pid
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
for k in sbmods_cpu_pct sbmods_gpu_pct sbmods_cool sbmods_cores sbmods_cmd; do settings put global $k 0; done
rm -rf $ST /data/local/tmp/sbmods_orig /data/local/tmp/sbmods_cpu_orig
echo "SBMods: speeds restored"
