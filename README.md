# Mobilegamma: CakeSync

An Android app that finds cake photos in your gallery **on the device** and uploads
them to Google Drive every day. A separate workflow (Zapier, Make, n8n and so on) can
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
- **Review grid:** tap a photo to include or exclude it. "All scanned" lets you
  include photos the detector missed. You can also require approval before upload.
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
   - SHA-1: the fingerprint of the key that signs your APK. For debug builds run
     `./gradlew signingReport` (or `keytool -list -v -keystore ~/.android/debug.keystore -storepass android`).
     APKs built by GitHub Actions use that runner's own debug key, so register your
     local build's SHA-1, or add a signing key to CI.

No client ID is needed in the code: Google matches the package name and SHA-1.

## Build and run
- **Android Studio:** open the folder and press Run (minSdk 29 / Android 10+).
- **Command line:** `./gradlew assembleDebug`, then install
  `app/build/outputs/apk/debug/app-debug.apk`.
- **CI:** every push builds a debug APK in GitHub Actions and attaches it to the run
  as the artifact `cakesync-debug-apk`.

## Using it
1. **Allow** photo access (and notifications).
2. **Connect** Google Drive.
3. **Scan now.** The first scan looks back 7 days, and later scans only look at new photos.
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
