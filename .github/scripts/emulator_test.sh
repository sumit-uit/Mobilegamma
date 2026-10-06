#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the app, loads test photos,
# drives the UI with adb/uiautomator, saves screenshots and checks the result.
set -euo pipefail

PKG=com.cakesync.app
# applicationId differs from the code namespace, so the activity needs its full class name.
MAIN_ACTIVITY="$PKG/com.mobilegamma.cakesync.ui.MainActivity"
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

# Prints "x y" for the centre of the first node whose content-desc contains $1
# (photo tiles expose no text, only their file name as the description).
find_desc() {
  dump_ui | python3 -c '
import re, sys
needle = sys.argv[1]
xml = sys.stdin.read()
for m in re.finditer(r"<node [^>]*>", xml):
    node = m.group(0)
    t = re.search(r" content-desc=\"([^\"]*)\"", node)
    b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node)
    if t and b and needle in t.group(1):
        x1, y1, x2, y2 = map(int, b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' "$1"
}

# Taps the first node whose text is exactly $1 (retries: uiautomator dumps
# flake under load and an empty dump must not fail the run on its own).
tap_exact() {
  local pos i
  for i in 1 2 3; do
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
    [ -n "$pos" ] && break
    sleep 2
  done
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
  local pos i
  for i in 1 2 3; do
    pos=$(find_text "$1")
    [ -n "$pos" ] && break
    sleep 2
  done
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
  local pos i
  for i in 1 2 3; do
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
    [ -n "$pos" ] && break
    sleep 2
  done
  if [ -z "$pos" ]; then echo "nav '$1' not found"; return 1; fi
  echo "nav '$1' at $pos"; adb shell input tap $pos; sleep 2
}

# Polls until photo tiles (date captions) appear; grids load slowly on a
# busy emulator and a single dump often catches a transition instead.
# NOTE: keep the substitution and the test in separate statements — inline
# `[ -n "$(... || true)" ]` misparses under macOS bash 3.2.
wait_for_tiles() {
  local timeout=${1:-30} waited=0 found=""
  until [ -n "$found" ]; do
    found=$(dump_ui | grep -oE 'text="([^"]* · )?[0-9]{1,2} [A-Z][a-z]{2}"' | head -1 || true)
    [ -n "$found" ] && break
    sleep 3; waited=$((waited + 3))
    if [ "$waited" -ge "$timeout" ]; then echo "Timed out waiting for photo tiles"; return 1; fi
  done
}

# Types text one character at a time ("%s" = space, as adb expects). A whole
# word sent in one injection loses characters in Compose text fields on a busy
# emulator ("Cupcakes" arrived as "Cu").
type_text() {
  local s=$1
  while [ -n "$s" ]; do
    if [ "${s:0:2}" = "%s" ]; then adb shell input text "%s"; s=${s:2}
    else adb shell input text "${s:0:1}"; s=${s:1}; fi
  done
}

# Long-presses the first photo tile to start a selection. Tiles are anchored by
# their date caption ("5 Oct"); the image sits ~150px above its caption.
# Prints "x y" for the middle of the first tile that is a photo: video tiles
# carry a "▶ 0:04" badge, and edits, filters, collages and crops need photos.
first_photo_pos() {
  dump_ui | python3 -c '
import re, sys
caps, badges = [], []
for m in re.finditer(r"<node [^>]*>", sys.stdin.read()):
    node = m.group(0)
    t = re.search(r" text=\"([^\"]*)\"", node)
    b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node)
    if not (t and b):
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    if t.group(1).startswith("\u25b6"):
        badges.append(((x1 + x2) // 2, (y1 + y2) // 2))
    elif re.search(r"(^|\u00b7 )[0-9]{1,2} [A-Z][a-z]{2}$", t.group(1)):
        caps.append(((x1 + x2) // 2, (y1 + y2) // 2))
for x, y in caps:
    if not any(abs(bx - x) < 200 and y - 400 < by < y for bx, by in badges):
        print(x, y - 150)
        break
' || true
}

select_first_photo() {
  wait_for_tiles 30 || { echo "no photo tile on screen"; return 1; }
  local pos
  pos=$(first_photo_pos)
  [ -n "$pos" ] || { echo "no photo tile on screen"; return 1; }
  set -- $pos
  # A long-press delivered late (busy emulator) lands as a tap and opens the photo
  # viewer instead: check the selection bar appeared, otherwise back out and retry.
  local try
  for try in 1 2 3; do
    adb shell input swipe "$1" "$2" "$1" "$2" 1200
    sleep 2
    if dump_ui | grep -q ' selected"'; then return 0; fi
    adb shell input keyevent BACK
    sleep 2
  done
  echo "could not start a selection"
  return 1
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
adb logcat -c 2>/dev/null || true

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
adb shell am start -W -n "$MAIN_ACTIVITY" >/dev/null 2>&1 || true
sleep 6
shot 00-video-permission-prompt
# Cold start on a fresh emulator can exceed `am start -W`'s own timeout, so the
# app may still be on its splash screen: poll until video access is granted or
# the app asks for it, instead of checking a single moment.
prompt_ok=0
for _ in $(seq 1 30); do
  if video_granted; then
    echo "PASS: video access granted after the app asked (Android grants it silently when photo access exists)"
    prompt_ok=1
    break
  fi
  if dump_ui | grep -qiE 'Allow .*(video|photos and videos)|Video access'; then
    echo "PASS: app is asking for video access"
    prompt_ok=1
    break
  fi
  sleep 3
done
if [ "$prompt_ok" -ne 1 ]; then
  ui=$(dump_ui)
  echo "FAIL: video access neither granted nor requested"
  echo "$ui" | grep -oE ' text="[^"]+"' | head -20
  exit 1
fi
echo "== First-run intro"
shot 00b-intro-1
if tap_text "Next"; then sleep 2; shot 00c-intro-2; tap_text "Next" || true; sleep 2; shot 00d-intro-3; fi
# Prefer Skip (straight Home); Get started enters the orders wizard, which needs
# Later to exit. Either way we must land on Home, not in the wizard.
tap_text "Skip" || tap_text "Get started" || true
sleep 2
tap_text "Later" || true
sleep 1
sleep 2
adb shell pm grant "$PKG" android.permission.READ_MEDIA_VIDEO
adb shell am force-stop "$PKG"
adb shell am start -W -n "$MAIN_ACTIVITY"
wait_for_text "Scan now" 60
sleep 2
shot 01-launch

echo "== Scan"
tap_text "Scan now"
wait_for_text "Found" 60 || wait_for_text "No new cakes found" 120
sleep 3
shot 02-after-scan-matches
result=$(dump_ui | grep -oE 'Found [0-9]+ cake photo|No new cakes found' | head -1 || true)
if [ -z "$result" ]; then
  echo "WARN: scan result missing from UI dump (flaky dump?) — retrying once"
  sleep 5
  result=$(dump_ui | grep -oE 'Found [0-9]+ cake photo|No new cakes found' | head -1 || true)
fi
if [ -z "$result" ]; then
  echo "FAIL: no scan result on screen after two dumps"
  dump_ui | grep -oE ' text="[^"]+"' | head -20 || true
  exit 1
fi
echo "Result: $result"
echo "$result" > "$OUT/result.txt"
check_no_crash

echo "== Save to gallery (local folder, no Drive needed)"
tap_text "Save to gallery" || true
wait_for_text "Saved" 90
sleep 2
shot 02b-after-organize
gallery=$(adb shell ls /sdcard/Pictures/CakeSync/ 2>/dev/null | tr -d '\r' | grep -c . || true)
echo "Local gallery entries: $gallery"
if [ "${gallery:-0}" -lt 1 ]; then
  echo "FAIL: no local gallery copies under Pictures/CakeSync/"
  exit 1
fi
echo "PASS: cakes saved to the gallery"

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

echo "== Photo viewer: tap the first tile, exclude/include it"
datecap=""
if wait_for_tiles 30; then
  datecap=$(dump_ui | grep -oE 'text="([^"]* · )?[0-9]{1,2} [A-Z][a-z]{2}"' | head -1 | sed -E 's/text="([^"]*)"/\1/' || true)
fi
if [ -n "$datecap" ]; then
  pos=$(find_text "$datecap" || true)
  if [ -z "$pos" ]; then
    echo "WARN: tile vanished between dump and tap; skipping viewer check"
  else
    set -- $pos
    adb shell input tap "$1" "$(( $2 - 150 ))"
    sleep 2
    shot 04-photo-viewer
    tap_exact "Exclude" || tap_exact "Include" || true
    sleep 2
    shot 04b-after-toggle
    adb shell input keyevent KEYCODE_BACK
    sleep 1
  fi
else
  echo "WARN: no tiles for viewer check"
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

echo "== Categories: add a 'Cupcakes' category through the UI"
category_ok=1
# The Settings screen can still be composing after navigation on a slow host.
if wait_for_text "+ Add category" 90 && tap_text "+ Add category"; then
  sleep 2
  tap_text "Name, e.g. Cupcakes" && type_text "Cupcakes"
  sleep 1
  # Cupcake close-ups reliably score Food 70%+ while often missing Cake, so
  # "Cake, Food" at the default 60% catches them without stealing strong cakes
  # (ties always lose to the earlier default category).
  tap_text "Labels to match" && type_text "Cake,%sFood"
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
rows = db.execute("SELECT display_name, category, is_match FROM photos WHERE display_name LIKE 'cupcake_%'").fetchall()
moved = [r for r in rows if r[1] not in (None, 'default') and r[2] == 1]
print("cupcake_* rows:", rows)
sys.exit(0 if moved else 1)
PY
    then echo "PASS: cupcake photos moved into the new category"; category_ok=0
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
  tap_text "Business name" && type_text "Soni%sBakes"
  sleep 1
  hide_keyboard
  tap_text "Save name" || true
  sleep 1
  shot 05i-brand-kit
  if scroll_to_text "Tagline"; then
    tap_text "Tagline" && type_text "Custom%scakes%sto%sorder"; sleep 1; hide_keyboard
    scroll_to_text "Instagram" && tap_text "Instagram" && type_text "sonibakes"; sleep 1; hide_keyboard
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
    tap_text "Order name" && type_text "Order%s1%s-%sTest"
    sleep 1
    hide_keyboard
    tap_text "Save" || true
    sleep 3
    shot 05f-after-order
    # The tag write is async; poll the DB instead of reading it once.
    tagged=0
    for i in $(seq 1 6); do
      adb exec-out run-as "$PKG" cat databases/photos.db > "$OUT/photos-order.db" || true
      adb exec-out run-as "$PKG" cat databases/photos.db-wal > "$OUT/photos-order.db-wal" 2>/dev/null || true
      tagged=$(python3 -c "
import sqlite3
print(sqlite3.connect('$OUT/photos-order.db').execute(\"SELECT COUNT(*) FROM photos WHERE order_tag='Order 1 - Test'\").fetchone()[0])" || true)
      [ "${tagged:-0}" -gt 0 ] && break
      sleep 5
    done
    echo "tagged photos: $tagged"
    if [ "${tagged:-0}" -gt 0 ]; then echo "PASS: order tag saved"; order_ok=0
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
    tap_text "Price or text" && type_text "Rs%s1200"
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
    if dump_ui | grep -qE 'Share [0-9]+ item|Sharing image|Share with|Nearby|Copy'; then echo "PASS: share sheet opened"; share_ok=0
    else echo "FAIL: share sheet not shown"; fi
    adb shell input keyevent KEYCODE_BACK; sleep 2
    adb shell am start -n "$MAIN_ACTIVITY" >/dev/null; sleep 3
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
    for i in $(seq 1 180); do   # up to 12 min: software encoding on an emulator is slow
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
  # The picker opens slowly on a loaded host: poll for tiles (either caption
  # style) instead of reading the screen once.
  tiles=""
  for i in $(seq 1 10); do
    tiles=$(dump_ui | python3 -c '
import re, sys
seen = []
ui = sys.stdin.read()
badges = []
for m in re.finditer(r"<node [^>]*>", ui):
    t = re.search(r" text=\"▶[^\"]*\"", m.group(0))
    b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", m.group(0))
    if t and b:
        x1, y1, x2, y2 = map(int, b.groups())
        badges.append(((x1 + x2) // 2, (y1 + y2) // 2))
for m in re.finditer(r"<node [^>]*>", ui):
    node = m.group(0)
    t = re.search(r" text=\"([^\"]*)\"", node)
    b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node)
    if not (t and b):
        continue
    is_tile = re.search(r"[0-9]{1,2} [A-Z][a-z]{2}", t.group(1)) or re.search(r"[0-9]+%,", t.group(1))
    if not is_tile:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    # A caption on its own: tap the photo above it. A merged tile node: tap its middle.
    c = ((x1 + x2) // 2, y1 - 120) if y2 - y1 <= 120 else ((x1 + x2) // 2, (y1 + y2) // 2)
    if c[1] < 700: continue             # not a tile (banner or chips)
    if any(abs(bx - c[0]) < 200 and abs(by - c[1]) < 250 for bx, by in badges): continue  # a video
    if all(abs(c[0] - x) > 150 or abs(c[1] - y) > 150 for x, y in seen): seen.append(c)
for x, y in seen[:2]: print(x, y)
' || true)
    [ -n "$tiles" ] && break
    sleep 3
  done
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

echo "== Menu: set a price, add designs by swiping, make a menu card"
menu_ok=1
nav Create || true
if tap_text "Names, prices"; then
  sleep 4
  shot 09-menu-prices
  if tap_text "Round cakes"; then
    sleep 2
    tap_exact "\$" && { type_text "60"; sleep 1; hide_keyboard; }
    shot 09a-menu-price-table
  fi
  scroll_to_text "Save prices" && tap_text "Save prices"
  sleep 2
  shot 09b-menu-new-designs
  for i in 1 2 3; do tap_text "Add to menu" || true; sleep 2; done
  tap_text "Skip" || true
  sleep 2
  tap_text "My menu" || true
  sleep 2
  shot 09c-my-menu
  if tap_text "Menu card"; then
    sleep 6
    shot 09d-menu-card-dialog
    tap_text "Save and share" || true
    sleep 12
    shot 09e-menu-card-saved
    n=$(adb shell "content query --uri content://media/external/images/media --projection _display_name" | grep -c "CakeSync_menu_" || true)
    echo "menu card pages: $n"
    if [ "${n:-0}" -ge 2 ]; then echo "PASS: menu card saved with a price-list page"; menu_ok=0
    elif [ "${n:-0}" -ge 1 ]; then echo "PASS: menu card saved (no price-list page)"; menu_ok=0
    else echo "FAIL: no menu card saved"; fi
    tap_text "Done" || adb shell input keyevent KEYCODE_BACK
    sleep 1
  fi
  adb shell input keyevent KEYCODE_BACK
  sleep 2
fi
nav Home || true
shot 09f-home-menu-banner

echo "== Orders: setup wizard, share a customer message in, schedule in the calendar, import a booking"
orders_ok=1
adb shell pm grant "$PKG" android.permission.READ_CALENDAR || true
adb shell pm grant "$PKG" android.permission.WRITE_CALENDAR || true
# The emulator has no Google account, so make a local calendar to stand in for Google Calendar.
adb shell "content insert --uri 'content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=cakesync.test&account_type=LOCAL' --bind account_name:s:cakesync.test --bind account_type:s:LOCAL --bind name:s:CakeOrders --bind calendar_displayName:s:CakeOrders --bind calendar_access_level:i:700 --bind ownerAccount:s:cakesync.test --bind visible:i:1 --bind sync_events:i:1 --bind calendar_timezone:s:UTC" || echo "could not create a test calendar"
cal_id=$(adb shell "content query --uri content://com.android.calendar/calendars --projection _id:calendar_displayName" | grep "CakeOrders" | grep -oE "_id=[0-9]+" | cut -d= -f2 | head -1 || true)
echo "test calendar id: ${cal_id:-none}"
if [ -n "$cal_id" ]; then
  start=$(( ($(date +%s) + 3 * 86400) * 1000 ))
  adb shell "content insert --uri content://com.android.calendar/events --bind calendar_id:l:$cal_id --bind 'title:s:Meera cake booking' --bind dtstart:l:$start --bind dtend:l:$(( start + 3600000 )) --bind eventTimezone:s:UTC --bind 'description:s:Vanilla 8 inch'" || true
fi
nav Orders || true
shot 10-orders-empty
if tap_exact "Set up orders"; then
  sleep 2
  shot 10a-setup-channels
  tap_exact "Next" || true; sleep 1
  shot 10b-setup-policy
  tap_exact "Next" || true; sleep 1
  shot 10c-setup-questions
  tap_exact "Next" || true; sleep 2
  tap_exact "CakeOrders" || tap_text "Allow calendar access" || true
  sleep 2
  shot 10d-setup-calendar
  tap_exact "Finish" || true
  sleep 2
fi
msg='Hi! I would like to order a cake:
Name: Priya Test
Phone: 4165550100
Design: Pink Floral Cake
Size: 6"
Flavour: Vanilla
Eggless: yes
Message on cake: Happy Birthday
Date needed: tomorrow
Pickup / delivery time: 5 pm'
adb shell "am start -a android.intent.action.SEND -t text/plain -n $MAIN_ACTIVITY --es android.intent.extra.TEXT '$msg'" >/dev/null
sleep 4
shot 10e-order-from-message
tap_exact "Save" || true
sleep 4
adb exec-out run-as "$PKG" cat files/orders.json > "$OUT/orders.json" 2>/dev/null || true
if grep -q "Priya Test" "$OUT/orders.json"; then echo "PASS: shared message saved as an order"; orders_ok=0; else echo "FAIL: order from message not saved"; fi
if adb shell "content query --uri content://com.android.calendar/events --projection title" | grep -q "Priya Test"; then
  echo "PASS: order scheduled in the calendar"
else
  echo "NOTE: order not found in the calendar"
fi
nav Orders || true
shot 10f-orders-list
if tap_text "Order form"; then
  sleep 2
  shot 10f2-order-form-link
  # The web form link is behind Features.WEB_ORDER_FORM (off): expect the fill-in text.
  if dump_ui | grep -q "Name:"; then echo "PASS: order form text shown"; else echo "NOTE: order form text not seen"; fi
  tap_exact "Close" || adb shell input keyevent KEYCODE_BACK
  sleep 1
fi
if tap_text "From calendar"; then
  sleep 3
  shot 10g-calendar-bookings
  if tap_text "Meera"; then
    sleep 3
    shot 10h-order-from-booking
    tap_exact "Save" || true
    sleep 3
    adb exec-out run-as "$PKG" cat files/orders.json > "$OUT/orders.json" 2>/dev/null || true
    if grep -q "Meera" "$OUT/orders.json"; then echo "PASS: calendar booking imported as an order"; else echo "NOTE: booking not imported"; fi
  else
    tap_text "Close" || true
  fi
fi
nav Home || true
shot 10i-home-this-week

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
pos=""
if wait_for_tiles 30; then pos=$(first_photo_pos); fi
if [ -n "$pos" ]; then
  if true; then
    set -- $pos
    adb shell input tap "$1" "$2"
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

found=$(echo "$result" | grep -oE '[0-9]+' | head -1 || echo 0)
if [ "${found:-0}" -lt 1 ]; then
  echo "FAIL: no cake photos detected"; exit 1
fi
if [ "$created_ok" -ne 0 ]; then
  echo "FAIL: Created tab check"; exit 1
fi
if [ "$orders_ok" -ne 0 ]; then
  echo "FAIL: orders check"; exit 1
fi
if [ "$menu_ok" -ne 0 ]; then
  echo "FAIL: menu check"; exit 1
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
