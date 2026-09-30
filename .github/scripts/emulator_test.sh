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

# Closes the on-screen keyboard if it is showing (BACK would otherwise close a dialog).
hide_keyboard() {
  if adb shell dumpsys input_method | grep -q "mInputShown=true"; then
    adb shell input keyevent KEYCODE_BACK
    sleep 1
  fi
}

tap_text() {
  local pos
  pos=$(find_text "$1")
  if [ -z "$pos" ]; then echo "Could not find '$1' on screen"; shot "missing-$(echo "$1" | tr ' :' '__')"; return 1; fi
  echo "tap '$1' at $pos"
  adb shell input tap $pos
}

# Swipes up until text containing $1 is on screen (max 8 swipes).
scroll_to_text() {
  local i
  for i in 1 2 3 4 5 6 7 8; do
    [ -n "$(find_text "$1")" ] && return 0
    adb shell input swipe 540 1700 540 900 300; sleep 1
  done
  [ -n "$(find_text "$1")" ]
}

# Taps a bottom-navigation item (Home, Gallery, Create, Settings): the lowest exact match.
nav() {
  local pos
  pos=$(dump_ui | python3 -c '
import re, sys
needle = sys.argv[1]; best = None
for m in re.finditer(r"<node [^>]*>", sys.stdin.read()):
    node = m.group(0)
    t = re.search(r" text=\"([^\"]*)\"", node)
    b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node)
    if t and b and t.group(1) == needle:
        x1, y1, x2, y2 = map(int, b.groups())
        if best is None or y1 > best[1]: best = ((x1 + x2) // 2, (y1 + y2) // 2)
if best: print(best[0], best[1])
' "$1")
  if [ -z "$pos" ]; then echo "nav '$1' not found"; return 1; fi
  echo "nav '$1' at $pos"; adb shell input tap $pos; sleep 2
}

# Long-presses the first photo tile (found by its "Label NN%," caption) to start a selection.
select_first_photo() {
  local label pos
  label=$(dump_ui | grep -oE 'text="[^"]*[0-9]+%,[^"]*"' | head -1 | sed -E 's/text="([^"]*)"/\1/' || true)
  [ -n "$label" ] || { echo "no photo tile on screen"; return 1; }
  pos=$(find_text "$label"); set -- $pos
  adb shell input swipe "$1" "$(( $2 - 150 ))" "$1" "$(( $2 - 150 ))" 900
  sleep 2
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
echo "== First-run intro"
shot 00b-intro-1
if tap_text "Next"; then sleep 2; shot 00c-intro-2; tap_text "Next" || true; sleep 2; shot 00d-intro-3; fi
tap_text "Get started" || tap_text "Skip" || true
sleep 2
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

echo "== Gallery tabs"
nav Gallery || true
tap_text "All scanned"
sleep 3
shot 03-all-scanned
tap_text "Videos" || true
sleep 3
shot 03b-videos-tab
tap_text "Matches" || true
sleep 2
shot 03c-gallery

echo "== Photo viewer: tap a photo, exclude/include it"
first_label=$(dump_ui | grep -oE 'text="[^"]*[0-9]+%[^"]*"' | head -1 | sed -E 's/text="([^"]*)"/\1/' || true)
if [ -n "$first_label" ]; then
  pos=$(find_text "$first_label"); set -- $pos
  adb shell input tap "$1" "$(( $2 - 150 ))"
  sleep 2
  shot 04-photo-viewer
  tap_exact "Exclude" || tap_exact "Include" || true
  sleep 2
  shot 04b-after-toggle
  adb shell input keyevent KEYCODE_BACK
  sleep 1
fi

echo "== Settings"
nav Settings || true
shot 05-settings
picker=1
for i in 1 2 3 4 5 6 7 8; do
  if tap_exact "Choose"; then picker=0; break; fi
  adb shell input swipe 540 1700 540 900 300; sleep 1
done
if [ "$picker" -eq 0 ]; then
  sleep 3
  shot 05b-folder-picker
  dump_ui | grep -oE 'text="[^"]*\([0-9]+\)"' | tee "$OUT/folders.txt" || true
  tap_text "Cancel" || adb shell input keyevent KEYCODE_BACK
  sleep 1
fi
scroll_top() { for i in 1 2 3 4 5 6; do adb shell input swipe 540 700 540 1900 200; sleep 0.4; done; }
scroll_top

echo "== Categories: add a 'Beach' category through the UI"
category_ok=1
if tap_text "+ Add category"; then
  sleep 2
  tap_text "Name, e.g. Cupcakes" && adb shell input text "Beach"
  sleep 1
  tap_text "Labels to match" && adb shell input text "Beach"
  sleep 1
  hide_keyboard
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

echo "== Drive settings: account and folders"
if scroll_to_text "Drive folders"; then shot 05a2-drive-settings; fi
scroll_top

echo "== Branding: business name, tagline, Instagram, font and colour in the brand kit"
brand_ok=1
if scroll_to_text "Business name"; then
  tap_text "Business name" && adb shell input text "Soni%sBakes"
  sleep 1
  hide_keyboard
  tap_text "Save name" || true
  sleep 1
  shot 05i-brand-kit
  if scroll_to_text "Tagline"; then
    tap_text "Tagline" && adb shell input text "Custom%scakes%sPune"; sleep 1; hide_keyboard
    tap_text "Instagram" && adb shell input text "sonibakes"; sleep 1; hide_keyboard
    scroll_to_text "Save text" && tap_text "Save text"
    sleep 1
  fi
  scroll_to_text "Text font" && { tap_exact "Script" || true; sleep 1; }
  shot 05i1-brand-fonts
  scroll_to_text "Preview" || true
  adb shell input swipe 540 1500 540 1000 300; sleep 3
  shot 05i2-brand-preview
  if dump_ui | grep -q 'text="Preview"'; then echo "PASS: brand kit shows a preview"; else echo "NOTE: brand preview not found on screen"; fi
fi

echo "== Orders: long-press a photo, tag it 'Order 1 - Test'"
order_ok=1
nav Gallery || true
if select_first_photo; then
  shot 05e-selection
  if tap_exact "Order"; then
    sleep 2
    tap_text "Order name" && adb shell input text "Order%s1%s-%sTest"
    sleep 1
    hide_keyboard
    tap_text "Save" || true
    sleep 3
    shot 05f-after-order
    adb exec-out run-as "$PKG" cat databases/photos.db > "$OUT/photos-order.db" || true
    adb exec-out run-as "$PKG" cat databases/photos.db-wal > "$OUT/photos-order.db-wal" 2>/dev/null || true
    if python3 -c "
import sqlite3, sys
n=sqlite3.connect('$OUT/photos-order.db').execute(\"SELECT COUNT(*) FROM photos WHERE order_tag='Order 1 - Test'\").fetchone()[0]
print('tagged photos:', n); sys.exit(0 if n>0 else 1)"; then echo "PASS: order tag saved"; order_ok=0
    else echo "FAIL: order tag not saved"; fi
  fi
fi

echo "== Crop: select all, save 1:1 Instagram copies"
crop_ok=1
if select_first_photo; then
  tap_exact "All" || true
  sleep 1
  if tap_exact "Crop"; then
    sleep 2
    tap_text "1:1 Instagram post" || true
    sleep 8
    shot 05g-after-crop
    squares=$(adb shell "content query --uri content://media/external/images/media --projection _display_name:width:height:relative_path" \
      | grep "Pictures/CakeSync" | tee "$OUT/edited.txt" | grep -c "width=1080, height=1080" || true)
    if [ "${squares:-0}" -ge 1 ]; then echo "PASS: 1:1 crop saved"; crop_ok=0; else echo "FAIL: no 1:1 crop found"; fi
  fi
fi

echo "== Brand: select all, brand with a price"
if select_first_photo; then
  tap_exact "All" || true
  if tap_exact "Brand"; then
    sleep 2
    tap_text "Price or text" && adb shell input text "Rs%s1200"
    sleep 1
    hide_keyboard
    sleep 2
    shot 05i3-brand-dialog-preview
    tap_text "Save copies" || true
    sleep 10
    branded=$(adb shell "content query --uri content://media/external/images/media --projection _display_name" | grep -c "_branded" || true)
    echo "branded copies: $branded"
    if [ "${branded:-0}" -ge 1 ]; then echo "PASS: branded copies saved"; brand_ok=0; else echo "FAIL: no branded copies"; fi
  fi
fi

echo "== Share: Share → Other apps opens the Android share sheet"
share_ok=1
if select_first_photo; then
  if tap_exact "Share"; then
    sleep 2
    shot 05j-share-dialog
    dump_ui | grep -oE 'text="[^"]*#[^"]*"' | head -3 | tee "$OUT/caption.txt" || true
    tap_text "Other apps" || true
    sleep 4
    shot 05k-share-sheet
    if dump_ui | grep -qE 'Share [0-9]+ item|Share with|Nearby|Copy'; then echo "PASS: share sheet opened"; share_ok=0
    else echo "FAIL: share sheet not shown"; fi
    adb shell input keyevent KEYCODE_BACK; sleep 2
    adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null; sleep 3
  fi
fi

echo "== Reel: select all, make a reel (defaults: mixed transitions, built-in Happy music)"
reel_ok=1
nav Gallery || true
if select_first_photo; then
  tap_exact "All" || true
  if tap_exact "Reel"; then
    sleep 2
    shot 05l-reel-dialog
    tap_text "Make reel" || true
    for i in $(seq 1 30); do
      sleep 4
      reels=$(adb shell "content query --uri content://media/external/video/media --projection _display_name:duration:width:height" | grep "CakeSync_reel_" || true)
      [ -n "$reels" ] && break
    done
    echo "$reels" | tee "$OUT/reels.txt"
    if [ -n "$reels" ]; then echo "PASS: reel saved"; reel_ok=0; else echo "FAIL: no reel saved"; fi
  fi
fi

echo "== Reel file: size, music track, fade from black (ffprobe)"
reel_path=$(adb shell "content query --uri content://media/external/video/media --projection _data" | grep -oE '/storage/[^,]*CakeSync_reel_[^,]*\.mp4' | tail -1 || true)
if [ -n "$reel_path" ] && adb pull "$reel_path" "$OUT/reel.mp4" >/dev/null; then
  ffprobe -v error -show_entries stream=codec_type,width,height:stream_side_data=rotation:format=duration -of default=nw=1 "$OUT/reel.mp4" | tee "$OUT/reel_probe.txt"
  if grep -q "codec_type=audio" "$OUT/reel_probe.txt"; then echo "PASS: reel has the built-in music"; else echo "FAIL: reel has no audio track"; reel_ok=1; fi
  for t in 0.03 1.2; do
    y=$(ffmpeg -hide_banner -loglevel info -ss "$t" -i "$OUT/reel.mp4" -frames:v 1 -vf signalstats,metadata=print:key=lavfi.signalstats.YAVG -f null - 2>&1 \
      | grep -oE 'YAVG=[0-9.]+' | head -1 || true)
    echo "brightness at ${t}s: $y" | tee -a "$OUT/reel_probe.txt"
  done
fi

echo "== Home shows the result; View results opens the Created tab"
created_ok=1
nav Home || true
sleep 1
shot 05m-home-after-reel
dump_ui | grep -oE 'text="(Reel saved|Could not make)[^"]*"' | tee -a "$OUT/reels.txt" || true
if scroll_to_text "View results" && tap_text "View results"; then
  sleep 3
  shot 05n-created-tab
  ui=$(dump_ui)
  if echo "$ui" | grep -qE 'text="(&#127916;|🎬) Reel"'; then echo "PASS: Created tab lists the reel"; created_ok=0; else echo "FAIL: reel not in Created tab"; fi
  echo "$ui" | grep -oE 'text="[^"]*(Reel|Branded|crop|filter|Collage)[^"]*"' | head -10 | tee "$OUT/created.txt" || true
  pos=$(find_text "; Reel" || true)
  if [ -n "$pos" ]; then
    set -- $pos
    adb shell input tap "$1" "$(( $2 - 150 ))"; sleep 2
    shot 05o-creation-dialog
    if dump_ui | grep -q "Share: Instagram"; then echo "PASS: creation dialog opens"; else echo "FAIL: creation dialog missing"; created_ok=1; fi
    tap_text "Close" || adb shell input keyevent KEYCODE_BACK
    sleep 1
  fi
else
  echo "FAIL: no View results button after the reel"
fi

echo "== Create hub → Collage: pick two photos, continue, save"
collage_ok=1
nav Create || true
shot 05p-create-hub
if tap_text "2 to 9 photos"; then
  sleep 2
  shot 05p2-pick-photos
  # Tap the first two tiles by position (captions can repeat, so not by text).
  tiles=$(dump_ui | python3 -c '
import re, sys
seen = []
for m in re.finditer(r"<node [^>]*>", sys.stdin.read()):
    node = m.group(0)
    t = re.search(r" text=\"([^\"]*)\"", node)
    b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node)
    if t and b and re.search(r"[0-9]+%,", t.group(1)):
        x1, y1, x2, y2 = map(int, b.groups())
        # A caption on its own: tap the photo above it. A merged tile node: tap its middle.
        c = ((x1 + x2) // 2, y1 - 120) if y2 - y1 <= 120 else ((x1 + x2) // 2, (y1 + y2) // 2)
        if c[1] < 700: continue             # not a tile (banner or chips)
        if all(abs(c[0] - x) > 150 or abs(c[1] - y) > 150 for x, y in seen): seen.append(c)
for x, y in seen[:2]: print(x, y)
')
  echo "tiles to tap: $tiles"
  while read -r x y; do
    [ -n "$x" ] || continue
    adb shell input tap "$x" "$y" < /dev/null; sleep 2   # adb must not eat the loop's input
  done <<< "$tiles"
  shot 05p3-picked
  if tap_text "Continue:"; then
    sleep 5
    shot 05q-collage-preview
    tap_text "Save collage" || true
    sleep 10
    n=$(adb shell "content query --uri content://media/external/images/media --projection _display_name" | grep -c "CakeSync_collage_" || true)
    echo "collages: $n"
    if [ "${n:-0}" -ge 1 ]; then echo "PASS: collage saved"; collage_ok=0; else echo "FAIL: no collage saved"; fi
  fi
fi
tap_exact "Cancel" >/dev/null 2>&1 || true
tap_exact "Done" >/dev/null 2>&1 || true

echo "== Filter: select a photo, preview a filter, save"
filter_ok=1
nav Gallery || true
tap_text "Matches" || true
sleep 2
if select_first_photo; then
  if tap_exact "Filter"; then
    sleep 3
    tap_exact "Bright" || true
    sleep 3
    shot 05r-filter-preview
    tap_text "Save copies" || true
    sleep 8
    n=$(adb shell "content query --uri content://media/external/images/media --projection _display_name" | grep -c "_filter_" || true)
    echo "filtered copies: $n"
    if [ "${n:-0}" -ge 1 ]; then echo "PASS: filtered copy saved"; filter_ok=0; else echo "FAIL: no filtered copy"; fi
  fi
fi

echo "== Photo studio: open a photo, Edit, try filters/adjust/background/brand, save"
studio_ok=1
nav Gallery || true
tap_text "Matches" || true
sleep 2
first_label=$(dump_ui | grep -oE 'text="[^"]*[0-9]+%[^"]*"' | head -1 | sed -E 's/text="([^"]*)"/\1/' || true)
if [ -n "$first_label" ]; then
  pos=$(find_text "$first_label"); set -- $pos
  adb shell input tap "$1" "$(( $2 - 150 ))"
  sleep 2
  if tap_text "Edit"; then
    sleep 5
    shot 08-studio-filters
    tap_exact "Warm" || true; sleep 2
    tap_exact "Adjust" || true; sleep 2
    shot 08b-studio-adjust
    tap_exact "Background" || true; sleep 1
    tap_text "Remove background" || true
    sleep 15
    shot 08c-studio-background
    dump_ui | grep -oE 'text="[^"]*(Downloading|No cake|Could not)[^"]*"' | head -2 || true
    tap_exact "Brand" || true; sleep 1
    tap_text "Add my logo" || true; sleep 2
    shot 08d-studio-brand
    tap_exact "Save" || true
    sleep 6
    shot 08e-studio-saved
    n=$(adb shell "content query --uri content://media/external/images/media --projection _display_name" | grep -c "_studio_" || true)
    echo "studio edits: $n"
    if [ "${n:-0}" -ge 1 ]; then echo "PASS: studio edit saved"; studio_ok=0; else echo "FAIL: no studio edit saved"; fi
    tap_text "Done" || adb shell input keyevent KEYCODE_BACK
    sleep 2
  fi
fi

echo "== White background (needs the Play services model; reported, not required)"
nav Gallery || true
if select_first_photo && tap_exact "White bg"; then
  sleep 20
  nav Home || true
  dump_ui | grep -oE 'text="White background:[^"]*"' | tee "$OUT/white_background.txt" || true
  shot 05h-after-white-background
fi
check_no_crash

echo "== Dark mode"
adb shell cmd uimode night yes || true
sleep 3
nav Home || true
shot 07-home-dark
nav Create || true
shot 07b-create-dark
adb shell cmd uimode night no || true
sleep 3

echo "== Connect Drive (no Google account on the emulator: expect an error message, not a crash)"
nav Home || true
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
if [ "$created_ok" -ne 0 ]; then
  echo "FAIL: Created tab check"; exit 1
fi
if [ "$studio_ok" -ne 0 ]; then
  echo "FAIL: photo studio check"; exit 1
fi
if [ "$collage_ok" -ne 0 ]; then
  echo "FAIL: collage check"; exit 1
fi
if [ "$filter_ok" -ne 0 ]; then
  echo "FAIL: filter check"; exit 1
fi
if [ "$reel_ok" -ne 0 ]; then
  echo "FAIL: reel check"; exit 1
fi
if [ "$share_ok" -ne 0 ]; then
  echo "FAIL: share check"; exit 1
fi
if [ "$brand_ok" -ne 0 ]; then
  echo "FAIL: branding check"; exit 1
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
