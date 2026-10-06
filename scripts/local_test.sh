#!/usr/bin/env bash
# Local replacement for the deleted GitHub Actions emulator job.
# Builds the debug APK, prepares test photos/videos, boots (or reuses) an
# API 34 emulator, and runs .github/scripts/emulator_test.sh against it.
#
# One-time setup (macOS):
#   1. Install Android Studio (or the SDK cmdline-tools) and JDK 17.
#   2. Export ANDROID_HOME=$HOME/Library/Android/sdk (or create local.properties
#      with "sdk.dir=<path>"). Add $ANDROID_HOME/emulator + platform-tools to PATH.
#   3. Create an AVD named CakeSync_Test: Pixel 6, API 34, "Google APIs" x86_64
#      image (Android Studio Device Manager, or avdmanager).
#   4. brew install ffmpeg  (pip install pillow for make_composites.py)
#
# Usage: bash scripts/local_test.sh   (run from anywhere; repo root is derived)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

AVD="${AVD:-CakeSync_Test}"
PKG=com.cakesync.app

fail() { echo "ERROR: $*" >&2; exit 1; }

echo "== Prerequisites"
command -v java >/dev/null || fail "java not found (need JDK 17)"
java -version 2>&1 | grep -q '17\.' || echo "WARN: java is not 17; the build may fail"
command -v adb >/dev/null || fail "adb not found (platform-tools)"
command -v ffmpeg >/dev/null || fail "ffmpeg not found (brew install ffmpeg)"
command -v python3 >/dev/null || fail "python3 not found"
python3 -c "import PIL" 2>/dev/null || fail "pillow missing (pip install pillow)"
command -v emulator >/dev/null || fail "emulator not found; install the Android SDK emulator package and put \$ANDROID_HOME/emulator on PATH"

echo "== Build debug APK"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
[ -x "$JAVA_HOME/bin/java" ] || fail "JAVA_HOME looks wrong: $JAVA_HOME"
./gradlew assembleDebug
APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || fail "APK not produced at $APK"

echo "== Test media"
[ -f test-images/cupcake_1.jpg ] || { python3 .github/scripts/fetch_test_images.py test-images; }
python3 .github/scripts/make_composites.py test-images
mkdir -p test-videos
# NOTE: CI used video-src/cake.jpg, which is not in the repo; use a fetched cake photo.
cake=$(ls test-images/cake_*.jpg 2>/dev/null | head -1)
other=$(ls test-images/other_*.jpg 2>/dev/null | head -1)
[ -n "${cake:-}" ] && [ -n "${other:-}" ] || fail "fetch_test_images.py produced no cake/other photos"
ffmpeg -y -loglevel error -loop 1 -i "$cake" -t 4 -r 10 \
  -vf "scale=640:-2,format=yuv420p" -c:v libx264 test-videos/zz_cake_video.mp4
ffmpeg -y -loglevel error -loop 1 -i "$other" -t 4 -r 10 \
  -vf "scale=640:-2,format=yuv420p" -c:v libx264 test-videos/zz_other_video.mp4
ls -l test-videos

echo "== Emulator"
# Never trust "any attached device": pick the one running our AVD by name and
# pin it via ANDROID_SERIAL, so a stray emulator (or a real phone) can't hijack
# the run. Booting is only attempted when nothing is attached at all.
pick_device() {
  adb devices | awk 'NR>1 && $2=="device" {print $1}' | while read -r d; do
    name=$(ANDROID_SERIAL="$d" adb emu avd name 2>/dev/null | tr -d '\r' || true)
    if [[ "$name" == *"$AVD"* ]]; then echo "$d"; return 0; fi
  done
  return 1
}
chosen=$(pick_device || true)
if [ -z "$chosen" ]; then
  count=$(adb devices | awk 'NR>1 && $2=="device"' | grep -c . || true)
  if [ "$count" -eq 0 ]; then
    emulator -list-avds | grep -qx "$AVD" || fail "no device attached and no AVD named '$AVD' (create it, or set AVD=<name>)"
    echo "Starting emulator '$AVD' in the background (left running for next time)…"
    nohup emulator -avd "$AVD" -no-snapshot -no-audio -no-boot-anim -camera-back none \
      >/tmp/cakesync-emulator.log 2>&1 &
    adb wait-for-device
    echo "Waiting for boot…"
    for _ in $(seq 1 60); do
      [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
      sleep 5
    done
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] \
      || fail "emulator did not boot (see /tmp/cakesync-emulator.log)"
    chosen=$(pick_device || true)
  else
    fail "device(s) attached but none runs AVD '$AVD' — disconnect them or set AVD=<name> to use another emulator"
  fi
fi
[ -n "$chosen" ] || fail "could not find our emulator"
export ANDROID_SERIAL="$chosen"
echo "Using device $ANDROID_SERIAL (AVD $AVD)"

echo "== Fresh state (re-runs start clean: reinstall wipes photos.db)"
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb shell rm -rf /sdcard/Pictures/CakeSyncTest /sdcard/Movies/CakeSyncTest \
  /sdcard/Pictures/CakeSync /sdcard/Movies/CakeSync 2>/dev/null || true

echo "== Run the app test (same assertions CI used to run)"
bash .github/scripts/emulator_test.sh
