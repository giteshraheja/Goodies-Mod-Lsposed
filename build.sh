#!/data/data/com.termux/files/usr/bin/bash
# Builds out/StatusBarMods.apk. The signing key lives outside the project, in ${SBMODS_KEYS:-$HOME/.sbmods-keys} (sbmods.jks, password in env SBMODS_KS_PASS or in pass.txt there).
# Keep that folder safe: updates must be signed with the same key.
set -euo pipefail
cd "$(dirname "$0")"
SDK=sdk-dl/android-35/android.jar
B=build; rm -rf "$B"; mkdir -p "$B"/{stubs,classes,dex} out

# 1. Xposed API stubs (compile-only, NOT packaged - the framework provides the real ones)
javac -nowarn --release 11 -cp "$SDK" -d "$B/stubs" $(find stubs -name '*.java') 2>&1 | grep -v 'warning: \[options\]' || true
# 2. App + hook
javac -Xlint:-options --release 11 -encoding UTF-8 -cp "$SDK:$B/stubs" \
      -d "$B/classes" $(find app/src -name '*.java')
# 3. Dex
d8 --release --min-api 34 --lib "$SDK" --classpath "$B/stubs" --output "$B/dex" $(find "$B/classes" -name '*.class')
# 4. Resources + manifest + assets
aapt package -f -M app/AndroidManifest.xml -S app/res -A app/assets -I "$SDK" -F "$B/unsigned.apk"
(cd "$B/dex" && zip -q -j ../unsigned.apk classes.dex)
# 5. Align + sign
zipalign -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"
# Keystore folder and password: ${SBMODS_KEYS:-$HOME/.sbmods-keys}/sbmods.jks; password from env SBMODS_KS_PASS or pass.txt in that folder. Never hardcode or commit it.
KEYS="${SBMODS_KEYS:-$HOME/.sbmods-keys}"
KS_PASS="${SBMODS_KS_PASS:-}"
[ -z "$KS_PASS" ] && [ -f "$KEYS/pass.txt" ] && KS_PASS="$(head -n1 "$KEYS/pass.txt")"
[ -n "$KS_PASS" ] || { echo "Set SBMODS_KS_PASS or put the keystore password in $KEYS/pass.txt" >&2; exit 1; }
if [ ! -f "$KEYS/sbmods.jks" ]; then
  mkdir -p "$KEYS"
  keytool -genkeypair -keystore "$KEYS/sbmods.jks" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias sbmods -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=StatusBar Mods" >/dev/null 2>&1
fi
export KS_PASS
apksigner sign --ks "$KEYS/sbmods.jks" --ks-pass env:KS_PASS --key-pass env:KS_PASS \
  --out out/StatusBarMods.apk "$B/aligned.apk"
apksigner verify out/StatusBarMods.apk && ls -la out/StatusBarMods.apk
