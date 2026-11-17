#!/bin/bash
# 构建 JsGO（com.jocoks.jsgo）—— 单 WebView 的 jsonl 编辑器，未签名 APK。
# 用法: ./build.sh
set -e
ROOT=/root/duihua/jsgo
APP=$ROOT/app
OUT=$ROOT/build
DIST=$ROOT/dist
BT=/usr/lib/android-sdk/build-tools/29.0.3
D8JAR=/root/build/sdk/build-tools/36.0.0/lib/d8.jar
PLATFORM=/root/build/sdk/platforms/android-30/android.jar

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$DIST"

echo "== 1/4 aapt2 编译资源并链接"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2" link -I "$PLATFORM" --manifest "$APP/AndroidManifest.xml" \
  -R "$OUT/res.zip" --java "$OUT/gen" --auto-add-overlay \
  --min-sdk-version 28 --target-sdk-version 36 \
  -A "$APP/assets" -o "$OUT/base.apk"

echo "== 2/4 javac"
javac --release 8 -nowarn -classpath "$PLATFORM" -d "$OUT/classes" \
  $(find "$APP/java" "$OUT/gen" -name '*.java')

echo "== 3/4 d8"
java -Xmx2g -cp "$D8JAR" com.android.tools.r8.D8 --release --min-api 28 \
  --lib "$PLATFORM" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "== 4/4 打包并页对齐"
cd "$OUT"
cp base.apk unsigned.apk
zip -q -0 -j unsigned.apk dex/classes.dex
"$BT/zipalign" -p -f 4 unsigned.apk "$DIST/JsGO-unsigned.apk"
rm -f unsigned.apk

ls -l "$DIST/JsGO-unsigned.apk"
sha256sum "$DIST/JsGO-unsigned.apk"
