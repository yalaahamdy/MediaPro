#!/bin/bash
set -e

echo "=== MediaPro Android APK Build (Linux / CI) ==="

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
if [ ! -d "$SDK" ] && [ -d "/usr/local/lib/android/sdk" ]; then
    SDK="/usr/local/lib/android/sdk"
fi

BT="$SDK/build-tools/33.0.1"
if [ ! -f "$BT/aapt2" ]; then
    BT=$(find "$SDK/build-tools" -name "aapt2" | head -n 1 | xargs dirname)
fi

PLAT="$SDK/platforms/android-34/android.jar"
if [ ! -f "$PLAT" ]; then
    PLAT=$(find "$SDK/platforms" -name "android.jar" | sort -V | tail -n 1)
fi

echo "SDK: $SDK"
echo "Build Tools: $BT"
echo "Platform: $PLAT"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$SCRIPT_DIR/build"
GEN="$WORK/gen"
CLASSES="$WORK/classes"
DEX="$WORK/dex"
WWW="$SCRIPT_DIR/assets/www"

rm -rf "$WORK"
mkdir -p "$GEN" "$CLASSES" "$DEX" "$WWW"

echo "[1/7] Copying Web App..."
cp "$SCRIPT_DIR/../MediaPro.html" "$WWW/MediaPro.html"

echo "[2/7] Compiling Resources..."
"$BT/aapt2" compile --dir "$SCRIPT_DIR/res" -o "$WORK/res.zip"

echo "[3/7] Linking Resources..."
"$BT/aapt2" link -o "$WORK/app.unaligned.apk" \
    -I "$PLAT" \
    --manifest "$SCRIPT_DIR/AndroidManifest.xml" \
    -R "$WORK/res.zip" \
    -A "$SCRIPT_DIR/assets" \
    --java "$GEN" \
    --auto-add-overlay

echo "[4/7] Compiling Java..."
find "$SCRIPT_DIR/java" "$GEN" -name "*.java" > "$WORK/sources.txt"
javac -encoding UTF-8 -cp "$PLAT" -d "$CLASSES" @"$WORK/sources.txt"

echo "[5/7] Converting to DEX..."
if [ ! -f "$SCRIPT_DIR/r8.jar" ]; then
    echo "Downloading official Google R8 compiler..."
    curl -s -L -o "$SCRIPT_DIR/r8.jar" https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.33/r8-8.2.33.jar
fi
find "$CLASSES" -name "*.class" > "$WORK/classes.txt"
R8="$SCRIPT_DIR/r8.jar"
java -cp "$R8" com.android.tools.r8.D8 --release --min-api 24 --lib "$PLAT" --output "$DEX" @"$WORK/classes.txt"

echo "[6/7] Packaging & Aligning..."
cp "$WORK/app.unaligned.apk" "$WORK/app.withdex.apk"
cd "$DEX"
jar uf "$WORK/app.withdex.apk" classes.dex
cd "$SCRIPT_DIR"

"$BT/zipalign" -f -p 4 "$WORK/app.withdex.apk" "$WORK/app.aligned.apk"

echo "[7/7] Signing..."
KEYSTORE="$SCRIPT_DIR/mediapro.keystore"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -v -keystore "$KEYSTORE" -alias mediapro \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass mediapro2026 -keypass mediapro2026 \
        -dname "CN=MediaPro, O=MediaPro, C=US" 2>/dev/null
fi

"$BT/apksigner" sign --ks "$KEYSTORE" \
    --ks-key-alias mediapro --ks-pass pass:mediapro2026 --key-pass pass:mediapro2026 \
    --out "$SCRIPT_DIR/../MediaPro.apk" "$WORK/app.aligned.apk"

echo "=== SUCCESS: $SCRIPT_DIR/../MediaPro.apk ==="
