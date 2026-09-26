#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the app, loads test photos,
# drives the UI with adb/uiautomator, saves screenshots and checks the result.
set -euo pipefail

PKG=com.mobilegamma.cakesync
OUT=emulator-output
mkdir -p "$OUT"

shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "screenshot: $1"; }

dump_ui() {
  adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1 || true
  adb shell uiautomator dump /sdcard/ui.xml > "$OUT/uiautomator.log" 2>&1 || true
  adb shell cat /sdcard/ui.xml 2>/dev/null || true
}

dismiss_system_dialogs() {
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  # "System UI isn't responding" / "App isn't responding" -> press "Wait"
  local pos
  pos=$(find_text "Wait" || true)
  [ -n "$pos" ] && adb shell input tap $pos || true
}

# Prints "x y" for the centre of the first node whose text contains $1.
find_text() {
  dump_ui | python3 -c '
import re, sys
needle = sys.argv[1]
xml = sys.stdin.read()
for m in re.finditer(r"<node [^>]*>", xml):
    node = m.group(0)
    t = re.search(r" text=\"([^\"]*)\"", node)
    b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node)
    if t and b and needle in t.group(1):
        x1, y1, x2, y2 = map(int, b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' "$1"
}

tap_text() {
  local pos
  pos=$(find_text "$1")
  if [ -z "$pos" ]; then echo "Could not find '$1' on screen"; shot "missing-$(echo "$1" | tr ' ' '_')"; return 1; fi
  echo "tap '$1' at $pos"
  adb shell input tap $pos
}

wait_for_text() {
  local text=$1 timeout=${2:-60} waited=0
  until [ -n "$(find_text "$text")" ]; do
    sleep 3; waited=$((waited + 3))
    if [ $((waited % 15)) -eq 0 ]; then dismiss_system_dialogs; fi
    if [ "$waited" -ge "$timeout" ]; then
      echo "Timed out waiting for '$text'"
      shot "timeout-$(echo "$text" | tr ' ' '_')"
      dump_ui > "$OUT/timeout-ui.xml"
      echo "uiautomator said: $(cat "$OUT/uiautomator.log")"
      echo "Visible texts:"; grep -oE ' text="[^"]+"' "$OUT/timeout-ui.xml" | head -30 || true
      return 1
    fi
  done
}

check_no_crash() {
  if adb logcat -d | grep -A 30 "FATAL EXCEPTION" | tee "$OUT/crash.txt" | grep -q "$PKG"; then
    echo "App crashed:"; cat "$OUT/crash.txt"; exit 1
  fi
}

echo "== Install"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c

echo "== Load test photos"
adb shell mkdir -p /sdcard/Pictures/CakeSyncTest
for f in test-images/*.jpg; do adb push "$f" /sdcard/Pictures/CakeSyncTest/ >/dev/null; done
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null || true
sleep 5
echo "MediaStore now has $(adb shell content query --uri content://media/external/images/media --projection _display_name | grep -c 'Row:') image(s)"

echo "== Grant permissions and launch"
sleep 20  # let the freshly booted system settle
dismiss_system_dialogs
adb shell pm grant "$PKG" android.permission.READ_MEDIA_IMAGES
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell am start -W -n "$PKG/.ui.MainActivity"
wait_for_text "Scan now" 60
sleep 2
shot 01-launch

echo "== Scan"
tap_text "Scan now"
wait_for_text "Scanned" 180
sleep 3
shot 02-after-scan-matches
result=$(dump_ui | grep -oE 'Scanned [0-9]+ new photo\(s\), [0-9]+ match\(es\)' | head -1)
echo "Result: $result"
echo "$result" > "$OUT/result.txt"
check_no_crash

echo "== What ML Kit saw in each photo (from the app's database)"
adb exec-out run-as "$PKG" cat databases/photos.db > "$OUT/photos.db" || true
adb exec-out run-as "$PKG" cat databases/photos.db-wal > "$OUT/photos.db-wal" 2>/dev/null || true
python3 - "$OUT/photos.db" <<'PY' | tee "$OUT/labels.txt" || true
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
for name, match, score, labels in db.execute(
        "SELECT display_name, is_match, score, labels FROM photos ORDER BY display_name"):
    print(f"{'MATCH' if match else '     '} {name:12} cake={score:.2f}  {labels}")
PY

echo "== Labels seen on all photos"
tap_text "All scanned"
sleep 3
shot 03-all-scanned

echo "== Toggle a photo (exclude/include)"
tap_text "Matches" || true
sleep 2
first_label=$(dump_ui | grep -oE 'text="[^"]*[0-9]+%[^"]*"' | head -1 | sed -E 's/text="([^"]*)"/\1/' || true)
if [ -n "$first_label" ]; then
  pos=$(find_text "$first_label")
  # tap the image just above its label
  set -- $pos
  adb shell input tap "$1" "$(( $2 - 150 ))"
  sleep 2
  shot 04-after-toggle
fi

echo "== Open settings"
tap_text "Settings" || true
sleep 2
shot 05-settings

echo "== Connect Drive (no Google account on the emulator: expect an error message, not a crash)"
adb shell input keyevent KEYCODE_MOVE_HOME
tap_text "Connect" || true
sleep 8
shot 06-connect-drive
adb shell input keyevent KEYCODE_BACK || true
sleep 2
check_no_crash

adb logcat -d | grep -E "CakeSync|PhotoScanner|SyncWorker|AndroidRuntime" > "$OUT/logcat.txt" || true

matches=$(echo "$result" | grep -oE '[0-9]+ match' | grep -oE '[0-9]+' || echo 0)
if [ "${matches:-0}" -lt 1 ]; then
  echo "FAIL: no cake photos detected"; exit 1
fi
echo "PASS: $result"
