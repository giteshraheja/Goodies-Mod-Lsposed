# SBMods shared shell functions (root). Sourced by sbmods_cpud.sh (from its own folder) and sbmods_job.sh (from the app cache folder next to it);
# nothing runs here, it only defines functions. One copy, so the helper and the one-off jobs can never drift apart.

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

num() { case "$1" in ''|*[!0-9]*) echo "$2";; *) echo "$1";; esac; }
mem() { awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo; }
swp() { awk '/SwapTotal/{t=$2}/SwapFree/{f=$2}END{if(t>0)print int((t-f)*100/t); else print 0}' /proc/meminfo; }
