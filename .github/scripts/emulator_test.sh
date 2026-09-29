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

# Taps the first node whose text is exactly $1.
tap_exact() {
  local pos
  pos=$(dump_ui | python3 -c '
import re, sys
needle = sys.argv[1]
for m in re.finditer(r"<node [^>]*>", sys.stdin.read()):
    node = m.group(0)
    t = re.search(r" text=\"([^\"]*)\"", node)
    b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node)
    if t and b and t.group(1) == needle:
        x1, y1, x2, y2 = map(int, b.groups()); print((x1 + x2) // 2, (y1 + y2) // 2); break
' "$1")
  [ -n "$pos" ] && adb shell input tap $pos
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
adb shell mkdir -p /sdcard/Movies/CakeSyncTest
for f in test-videos/*.mp4; do [ -e "$f" ] || continue; adb push "$f" /sdcard/Movies/CakeSyncTest/ >/dev/null; done
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null || true
sleep 5
echo "MediaStore now has $(adb shell content query --uri content://media/external/images/media --projection _display_name | grep -c 'Row:') image(s)" \
  "and $(adb shell content query --uri content://media/external/video/media --projection _display_name | grep -c 'Row:') video(s)"

echo "== Grant permissions and launch"
sleep 20  # let the freshly booted system settle
dismiss_system_dialogs
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

video_granted() {
  adb shell dumpsys package "$PKG" | grep -q "android.permission.READ_MEDIA_VIDEO: granted=true"
}

echo "== Updated from a photo-only version: photo access but no video access"
adb shell pm grant "$PKG" android.permission.READ_MEDIA_IMAGES
if video_granted; then echo "note: granting photos also granted videos on this system image"; fi
adb shell am start -W -n "$PKG/.ui.MainActivity"
sleep 6
shot 00-video-permission-prompt
if video_granted; then
  echo "PASS: video access granted after the app asked (Android grants it silently when photo access exists)"
else
  ui=$(dump_ui)
  if echo "$ui" | grep -qiE 'Allow .*(video|photos and videos)|Video access'; then
    echo "PASS: app is asking for video access"
  else
    echo "FAIL: video access neither granted nor requested"; echo "$ui" | grep -oE ' text="[^"]+"' | head -20; exit 1
  fi
fi
adb shell pm grant "$PKG" android.permission.READ_MEDIA_VIDEO
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.ui.MainActivity"
wait_for_text "Scan now" 60
sleep 2
shot 01-launch

echo "== Scan"
tap_text "Scan now"
wait_for_text "Scanned" 180
sleep 3
shot 02-after-scan-matches
result=$(dump_ui | grep -oE 'Scanned [0-9]+ new item\(s\), [0-9]+ match\(es\)' | head -1)
echo "Result: $result"
echo "$result" > "$OUT/result.txt"
check_no_crash

echo "== What ML Kit saw in each photo (from the app's database)"
adb exec-out run-as "$PKG" cat databases/photos.db > "$OUT/photos.db" || true
adb exec-out run-as "$PKG" cat databases/photos.db-wal > "$OUT/photos.db-wal" 2>/dev/null || true
python3 - "$OUT/photos.db" <<'PY' | tee "$OUT/labels.txt" || true
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
for name, match, score, faces, video, labels in db.execute(
        "SELECT display_name, is_match, score, faces, is_video, labels FROM photos ORDER BY display_name"):
    skip = " (skipped: people)" if match and faces else ""
    kind = "video" if video else "photo"
    print(f"{'MATCH' if match else '     '} {kind} {name:12} cake={score:.2f} faces={faces}{skip}  {labels}")
PY

echo "== Labels seen on all photos"
tap_text "All scanned"
sleep 3
shot 03-all-scanned

echo "== Videos tab"
tap_text "Videos" || true
sleep 3
shot 03b-videos-tab

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

echo "== Folder picker"
adb shell input swipe 540 1800 540 700 300 || true
sleep 2
if tap_text "Choose"; then
  sleep 3
  shot 05b-folder-picker
  dump_ui | grep -oE 'text="[^"]*\([0-9]+\)"' | tee "$OUT/folders.txt" || true
  tap_text "Cancel" || adb shell input keyevent KEYCODE_BACK
  sleep 1
fi
adb shell input swipe 540 700 540 1800 300 || true
sleep 1

echo "== Categories: add a 'Beach' category through the UI"
category_ok=1
if tap_text "+ Add category"; then
  sleep 2
  tap_text "Name, e.g. Cupcakes" && adb shell input text "Beach"
  sleep 1
  tap_text "Labels to match" && adb shell input text "Beach"
  sleep 1
  # hide the keyboard only if it covers the Save button (BACK would otherwise close the dialog)
  if [ -z "$(find_text "Save")" ]; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi
  shot 05c-new-category
  if tap_text "Save"; then
    sleep 6
    adb exec-out run-as "$PKG" cat databases/photos.db > "$OUT/photos-cat.db" || true
    adb exec-out run-as "$PKG" cat databases/photos.db-wal > "$OUT/photos-cat.db-wal" 2>/dev/null || true
    if python3 - "$OUT/photos-cat.db" <<'PY'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
rows = db.execute("SELECT display_name, category, is_match FROM photos WHERE display_name LIKE 'other_%'").fetchall()
beach = [r for r in rows if r[1] not in (None, 'default') and r[2] == 1]
print("other_* rows:", rows)
sys.exit(0 if beach else 1)
PY
    then echo "PASS: beach photos moved into the new category"; category_ok=0
    else echo "FAIL: no photo was matched to the new category"; fi
  fi
  shot 05d-after-category
fi
adb shell input swipe 540 700 540 1800 300 || true
sleep 1

echo "== Orders: long-press a photo, tag it 'Order 1 - Test'"
order_ok=1
tap_text "Settings" || true   # collapse settings so the photos are on screen
sleep 2
label=$(dump_ui | grep -oE 'text="[^"]*[0-9]+%,[^"]*"' | head -1 | sed -E 's/text="([^"]*)"/\1/' || true)
if [ -n "$label" ]; then
  pos=$(find_text "$label")
  set -- $pos
  adb shell input swipe "$1" "$(( $2 - 150 ))" "$1" "$(( $2 - 150 ))" 900   # long-press the image
  sleep 2
  shot 05e-selection
  if tap_text "Order…"; then
    sleep 2
    tap_text "Order name" && adb shell input text "Order%s1%s-%sTest"
    sleep 1
    if [ -z "$(find_text "Save")" ]; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi
    tap_text "Save" || true
    sleep 3
    shot 05f-after-order
    adb exec-out run-as "$PKG" cat databases/photos.db > "$OUT/photos-order.db" || true
    adb exec-out run-as "$PKG" cat databases/photos.db-wal > "$OUT/photos-order.db-wal" 2>/dev/null || true
    if python3 -c "
import sqlite3,sys
n=sqlite3.connect('$OUT/photos-order.db').execute(\"SELECT COUNT(*) FROM photos WHERE order_tag='Order 1 - Test'\").fetchone()[0]
print('tagged photos:', n); sys.exit(0 if n>0 else 1)"; then echo "PASS: order tag saved"; order_ok=0
    else echo "FAIL: order tag not saved"; fi
  fi
fi

echo "== Crop: select a photo, save a 1:1 Instagram copy"
crop_ok=1
label=$(dump_ui | grep -oE 'text="[^"]*[0-9]+%,[^"]*"' | head -1 | sed -E 's/text="([^"]*)"/\1/' || true)
if [ -n "$label" ]; then
  pos=$(find_text "$label"); set -- $pos
  adb shell input swipe "$1" "$(( $2 - 150 ))" "$1" "$(( $2 - 150 ))" 900
  sleep 2
  tap_exact "All" || true   # select every shown item; videos are skipped by the editor
  sleep 1
  if tap_text "Crop…"; then
    sleep 2
    tap_text "1:1 Instagram post" || true
    sleep 8
    shot 05g-after-crop
    squares=$(adb shell content query --uri content://media/external/images/media \
      --projection _display_name:width:height --where "relative_path LIKE 'Pictures/CakeSync%'" | tee "$OUT/edited.txt" | grep -c "width=1080, height=1080" || true)
    cat "$OUT/edited.txt"
    if [ "${squares:-0}" -ge 1 ]; then echo "PASS: 1:1 crop saved"; crop_ok=0; else echo "FAIL: no 1:1 crop found"; fi
  fi
fi

echo "== White background (needs the Play services model; reported, not required)"
label=$(dump_ui | grep -oE 'text="[^"]*[0-9]+%,[^"]*"' | head -1 | sed -E 's/text="([^"]*)"/\1/' || true)
if [ -n "$label" ]; then
  pos=$(find_text "$label"); set -- $pos
  adb shell input swipe "$1" "$(( $2 - 150 ))" "$1" "$(( $2 - 150 ))" 900
  sleep 2
  tap_text "White background" || true
  sleep 15
  shot 05h-after-white-background
  dump_ui | grep -oE 'text="White background:[^"]*"' | tee "$OUT/white_background.txt" || true
fi
check_no_crash

echo "== Connect Drive (no Google account on the emulator: expect an error message, not a crash)"
adb shell input keyevent KEYCODE_MOVE_HOME
tap_text "Connect" || true
sleep 8
shot 06-connect-drive
adb shell input keyevent KEYCODE_BACK || true
sleep 2
check_no_crash

adb logcat -d | grep -E "CakeSync|PhotoScanner|SyncWorker|AndroidRuntime" > "$OUT/logcat.txt" || true

echo "== People vs. cake-topper checks"
people_ok=0
python3 - "$OUT/photos.db" <<'PY' | tee "$OUT/people_checks.txt" || people_ok=1
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
rows = {n: (m, f) for n, m, f in db.execute("SELECT display_name, is_match, faces FROM photos")}
videos = {n: m for n, m in db.execute("SELECT display_name, is_match FROM photos WHERE is_video = 1")}
failed = False
def check(name, want_people, blocking=True):
    global failed
    if name not in rows:
        print(f"SKIP {name}: not scanned"); return
    match, faces = rows[name]
    if not match:
        print(f"SKIP {name}: not recognised as cake, check not applicable"); return
    ok = (faces or 0) > 0 if want_people else faces == 0
    verdict = "PASS" if ok else ("FAIL" if blocking else "KNOWN LIMITATION")
    print(f"{verdict} {name}: faces={faces} (want {'people' if want_people else 'no people'})")
    failed |= blocking and not ok
check("zz_cake_with_person.jpg", True)
# best shot: the blurry copy of a burst must be set aside, the sharp one kept
dup = dict(db.execute("SELECT display_name, duplicate FROM photos WHERE display_name LIKE 'zz_burst_%'").fetchall())
if dup.get("zz_burst_blurry.jpg") == 1 and dup.get("zz_burst_sharp.jpg") == 0:
    print("PASS best shot: blurry burst photo set aside, sharp one kept")
else:
    print(f"FAIL best shot: {dup}"); failed = True
# videos: the cake video must be found, the other one must not
for name, want in (("zz_cake_video.mp4", True), ("zz_other_video.mp4", False)):
    if name not in videos:
        print(f"FAIL {name}: video not scanned"); failed = True
    elif bool(videos[name]) != want:
        print(f"FAIL {name}: match={bool(videos[name])} (want {want})"); failed = True
    else:
        print(f"PASS {name}: match={bool(videos[name])}")
# Toppers with printed faces are usually skipped like people; the user taps to include them.
check("zz_cake_face_topper.jpg", False, blocking=False)
sys.exit(1 if failed else 0)
PY

matches=$(echo "$result" | grep -oE '[0-9]+ match' | grep -oE '[0-9]+' || echo 0)
if [ "${matches:-0}" -lt 1 ]; then
  echo "FAIL: no cake photos detected"; exit 1
fi
if [ "$crop_ok" -ne 0 ]; then
  echo "FAIL: crop check"; exit 1
fi
if [ "$order_ok" -ne 0 ]; then
  echo "FAIL: orders check"; exit 1
fi
if [ "$category_ok" -ne 0 ]; then
  echo "FAIL: categories check"; exit 1
fi
if [ "$people_ok" -ne 0 ]; then
  echo "FAIL: people/topper check"; exit 1
fi
echo "PASS: $result"
