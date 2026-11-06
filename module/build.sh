#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
BT="${BUILD_TOOLS:-$SDK/build-tools/35.0.0}"
PLATFORM="${ANDROID_PLATFORM:-$SDK/platforms/android-35/android.jar}"
OUT="$ROOT/build"
DIST="$ROOT/dist"

rm -rf "$OUT" "$DIST"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/stage/assets" "$DIST"

echo "== javac =="
javac -source 8 -target 8 -Xlint:all \
  -cp "$PLATFORM:$ROOT/XposedBridgeAPI-82.jar" \
  -d "$OUT/classes" \
  $(find "$ROOT/src" -name '*.java')

echo "== d8 =="
"$BT/d8" --lib "$PLATFORM" --min-api 29 --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "== aapt2 link =="
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --min-sdk-version 29 --target-sdk-version 35

cp "$OUT/dex/classes.dex" "$OUT/stage/classes.dex"
cp "$ROOT/assets/xposed_init" "$OUT/stage/assets/xposed_init"
gzip -9 -c "$ROOT/../scripts/edit_today_screentime.sh" > "$OUT/stage/assets/edit_today_screentime.sh.gz"
(cd "$OUT/stage" && zip -q -X "$OUT/base.apk" classes.dex assets/xposed_init assets/edit_today_screentime.sh.gz)

echo "== zipalign =="
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== sign =="
# 签名密钥绝不提交到仓库。
# CI 可通过 ANDROID_KEYSTORE_BASE64 secret 提供固定 keystore；
# 否则在 build/ 下生成一次性调试密钥（每次构建签名不同）。
if [ -n "${KEYSTORE_BASE64:-}" ]; then
  KS="$OUT/keystore.jks"
  printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$KS"
elif [ -f "$ROOT/debug.keystore" ]; then
  KS="$ROOT/debug.keystore"
else
  KS="$OUT/debug.keystore"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$DIST/screentime-editor-module.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --verbose "$DIST/screentime-editor-module.apk"

echo "== inspect =="
"$BT/aapt2" dump badging "$DIST/screentime-editor-module.apk" | head -20
unzip -l "$DIST/screentime-editor-module.apk" | grep -E 'classes.dex|xposed_init|AndroidManifest'
sha256sum "$DIST/screentime-editor-module.apk"
