# Mobilegamma: CakeSync

An Android app that finds cake photos **and videos** in your gallery **on the device** and
uploads them to Google Drive every day. A separate workflow (Zapier, Make, n8n and so on) can
then pick them up from Drive and post them to Meta apps.

```
Phone gallery ──► on-device ML Kit labelling ──► review grid ──► daily upload
                   (offline, private)                            Drive/CakeSync/2026-09-26/
```

## Features
- **On-device detection:** ML Kit Image Labeling with a bundled model. Photos never
  leave the phone to be analysed.
- **Configurable labels:** match "Cake", or add more (e.g. `Cake, Dessert, Food`).
  Changing labels or the confidence threshold re-checks photos already scanned.
- **Videos too:** frames at 10%, 50% and 90% of each video are checked the same way as
  photos. Uploads stream from disk using Drive's resumable upload, and run as a foreground
  job with a progress notification, so large videos don't run out of memory or get cut off.
  Turn off with "Include videos".
- **Skip photos with people:** on-device face detection (ML Kit) marks cake photos that
  contain a person with 👤 and leaves them out of uploads. Known limitation: cakes with a
  printed photo or character face on them are usually skipped too. Tap them in the grid to
  include them, and the choice is remembered. You can switch this off, or tap
  a photo to include it anyway.
- **Review grid:** tap a photo to include or exclude it. "All scanned" lets you
  include photos the detector missed. You can also require approval before upload.
- **Design:** a bakery theme (raspberry, caramel and cream, serif headings, light and dark),
  bottom navigation (Home · Gallery · Create · Settings), a first-run intro, a Home page with
  your numbers and shortcuts, a full-screen photo viewer, and a Create hub where you pick a tool
  and then the photos.
- **Created tab:** reels, collages and edited copies (filter, brand, crop, white background)
  all appear under ✨ Created, where you can open, share or delete them. They are also in the
  Gallery under Pictures/CakeSync and Movies/CakeSync. A "View results" button appears after
  each edit.
- **Reels:** transitions (simple cut, fade, slow zoom, slide in, zoom + fade, or a mix), photo
  speed, a colour filter and fill-the-screen. Built-in music (Happy, Calm, Upbeat, Sweet) is
  generated on the phone, so it's free to use with no copyright claims. You can also pick your
  own file, for example one downloaded from Pixabay Music.
- **Collages:** 9 layouts (2 to 9 photos) in 1:1, 4:5 or 9:16, with a background colour and
  optional branding. The dialog shows a live preview.
- **Filters and brand preview:** Warm, Bright, Cool, Vivid, Pastel, Vintage, Drama and B&W for
  photo copies and reels. The brand kit and Brand… dialog show how the logo, name and price
  will look on your own photo.
- **Daily automatic upload:** WorkManager runs at the hour you choose, optionally only
  on Wi-Fi, and uploads into `<Drive folder>/<date taken>/`. Each photo is uploaded once.
- **Narrow Drive permission:** uses the `drive.file` scope, so the app can only see
  the files and folders it created, not the rest of your Drive.

## Google Drive setup (one time)
Google sign-in only works after you register the app in Google Cloud:

1. Go to <https://console.cloud.google.com/> and create (or pick) a project.
2. **APIs & Services → Library →** enable **Google Drive API**.
3. **APIs & Services → OAuth consent screen:** set it up (External), and add your own
   Google account as a **test user**.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID → Android**
   - Package name: `com.mobilegamma.cakesync`
   - SHA-1: `ED:E8:06:C4:11:EB:CE:F9:C4:55:9D:64:93:C0:A5:3F:1A:6F:71:AD` (the shared
     CakeSync signing key used by CI builds)

### Signing key (GitHub secrets)
CI signs every APK with one shared key, so the SHA-1 above stays the same. The key is
not in the repo: it lives in two repository secrets (**Settings → Secrets and variables →
Actions**):

| Secret | Contents |
|---|---|
| `CAKESYNC_KEYSTORE_BASE64` | The keystore file (`cakesync.jks`, alias `cakesync`), base64-encoded |
| `CAKESYNC_KEYSTORE_PASSWORD` | Its store/key password |

Without these secrets, builds still work but use a temporary debug key, so Drive
sign-in fails. Keep a backup of the keystore: losing it means registering a new SHA-1.

No client ID is needed in the code: Google matches the package name and SHA-1.

## Build and run
- **Android Studio:** open the folder and press Run (minSdk 29 / Android 10+).
- **Command line:** `./gradlew assembleDebug`, then install
  `app/build/outputs/apk/debug/app-debug.apk`.
- **Phone download:** every push to `main` publishes
  <https://github.com/sumit-uit/Mobilegamma/releases/latest/download/CakeSync.apk>.

## Using it
1. **Allow** photo access (and notifications).
2. **Connect** Google Drive.
3. **Scan now.** Choose which folders to scan (Camera, WhatsApp Images and so on; nothing
   ticked means all of them) and how far back to look (7 days, 30 days, 1 year or all photos)
   in Settings. Photos already checked are never analysed again.
4. Check the grid, then **Upload now**, or turn on **Upload automatically every day**.

## Project layout
| Path | What it does |
|---|---|
| `scan/PhotoScanner.kt` | Reads new photos from MediaStore and labels thumbnails with ML Kit |
| `data/PhotoStore.kt` | SQLite record of scanned photos, match state, user choices and upload state |
| `data/Settings.kt` | User preferences |
| `drive/DriveAuth.kt` | Google authorization (`drive.file` scope) |
| `drive/DriveClient.kt` | Minimal Drive REST client: folders and multipart upload |
| `work/SyncWorker.kt` | Daily scan + upload job with notifications |
| `work/SyncScheduler.kt` | Schedules the daily job and "upload now" |
| `ui/` | Jetpack Compose screen + ViewModel |

## Roadmap
- LiteRT-LM chat command ("upload this week's cake photos again") using tool calling
- Image-embedding search (MobileCLIP/SigLIP) for free-text matches like "birthday cake with candles"
- Optional local move into a `Pictures/Cakes` folder via MediaStore
