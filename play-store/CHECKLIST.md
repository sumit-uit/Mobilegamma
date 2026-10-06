# Launch checklist — Google Play + Google Drive sign-in

Do the parts in this order. Console menus and policy details were written from memory in
October 2026; if a screen looks different, trust the console.

Key facts about this app:

- Package name: `com.cakesync.app`
- Drive permission requested: `drive.file` only (files the app creates)
- Signing: CI signs every build with `app/cakesync.jks` (secrets `CAKESYNC_KEYSTORE_BASE64`,
  `CAKESYNC_KEYSTORE_PASSWORD`). On Play this key becomes your **upload key**; Play re-signs the
  app with its own **app signing key**, which has a different SHA-1.

## Part 0 — Before either console

- [ ] Uninstall any old CakeSync build from your phone (the package name changed from
      `com.mobilegamma.cakesync`, so the new build installs as a separate app).
- [ ] Commit and push `docs/privacy/index.html`, after replacing `[ADD YOUR CONTACT EMAIL]`.
- [ ] Switch on GitHub Pages: repo → Settings → Pages → Source "Deploy from a branch" →
      Branch `main`, folder `/docs` → Save.
- [ ] Check both pages load:
  - `https://sumit-uit.github.io/cakesync/privacy/`
  - `https://sumit-uit.github.io/cakesync/order/` — the app's order-form link points here
    and it currently returns 404, so this also fixes the order form for customers.
- [ ] Back up `cakesync.jks` and its password somewhere safe outside GitHub. If you lose the
      upload key you have to ask Google to reset it.

## Part 1 — Play Console account (personal)

- [ ] Go to `play.google.com/console/signup`, choose **Personal**, pay the $25 fee.
- [ ] Complete identity verification (government ID) and verify your phone and email.
- [ ] Wait for the account to be approved (can take a few days).

## Part 2 — Create the app and upload the first build

- [ ] Play Console → **Create app**. Name `CakeSync`, language English, type **App**, **Free**.
- [ ] Get the bundle: GitHub → Actions → latest "Android build" run on `main` → artifact
      **cakesync-release-aab**. (It only appears once the workflow change is pushed.)
- [ ] Testing → **Internal testing** → Create new release. Accept **Play App Signing** when asked.
- [ ] Upload the `.aab`, add release notes, save. Add yourself as an internal tester and install
      from the opt-in link to confirm it runs.

## Part 3 — Make Drive sign-in work for the Play build

Do this as soon as Part 2 is done, or Drive sign-in fails for everyone who installs from Play.

- [ ] Play Console → your app → Test and release → Setup → **App signing** (older consoles:
      Setup → App integrity). Copy the **App signing key certificate → SHA-1**.
- [ ] Google Cloud Console → your CakeSync project → APIs & Services → **Credentials** →
      Create credentials → **OAuth client ID** → Application type **Android**.
  - Package name: `com.cakesync.app`
  - SHA-1: the Play app signing SHA-1 you just copied
- [ ] Create a **second** Android OAuth client for the same package with the SHA-1 of
      `cakesync.jks` (printed by the CI step "Show APK signing certificate"). It is needed for
      builds installed directly from GitHub. The old client registered for
      `com.mobilegamma.cakesync` no longer matches anything and can be deleted.
- [ ] Install the internal-test build from Play and connect Google Drive to confirm it works.

## Part 4 — Take the OAuth consent screen out of Testing

While it says Testing, only listed test users can sign in and their sign-in expires after about
7 days.

- [ ] Google Cloud Console → APIs & Services → **OAuth consent screen** (now also shown as
      "Google Auth Platform").
- [ ] Branding: app name `CakeSync`, support email, app home page, **privacy policy URL**
      (the Pages URL from Part 0), developer contact email.
- [ ] Add `sumit-uit.github.io` under Authorized domains if the home page / policy live there.
      Google may ask you to prove you own the domain in Search Console.
- [ ] Data access / Scopes: confirm the only scope is `.../auth/drive.file`.
- [ ] Audience → **Publish app** → confirm. Status should change to **In production**.
- [ ] `drive.file` is a non-sensitive scope, so no security review is expected. If you upload a
      logo, Google runs a short brand verification; the app still works meanwhile, but may show an
      "unverified app" notice until it completes.

## Part 5 — Store listing and declarations

Everything to paste is in `listing.md`.

- [ ] Grow → Store presence → **Main store listing**: name, short and full description,
      screenshots from `screenshots/`, app icon (512×512) and feature graphic (1024×500).
- [ ] Policy → **App content**, complete every item:
  - [ ] Privacy policy URL
  - [ ] Ads: No
  - [ ] App access: all features available without login
  - [ ] Content rating questionnaire
  - [ ] Target audience: 18+
  - [ ] Data safety (answers in `listing.md`)
  - [ ] Photo and video permissions declaration
  - [ ] Foreground service declaration (data sync) — Play asks for a short video showing the
        feature; a screen recording of "Scan now" running is enough
- [ ] Store settings: category **Business**, contact email.

## Part 6 — Closed test (required for new personal accounts)

- [ ] Testing → **Closed testing** → create a track, promote the internal build to it.
- [ ] Add at least **12 testers** by email (Google accounts) and send them the opt-in link.
- [ ] They must stay opted in for **14 days in a row**. Ask them to open the app a few times.
- [ ] Keep a note of feedback you received and what you changed; the production form asks.

## Part 7 — Production

- [ ] Dashboard → **Apply for production access** (appears after the 14 days). Answer the
      questions about your closed test.
- [ ] After approval: Production → Create new release → use the tested bundle → choose countries
      (start with Canada) → **Send for review**.
- [ ] First review commonly takes a few days.

## Later releases

Every push to `main` builds a new bundle with a higher version number automatically
(`versionCode` = GitHub run number). Download the artifact and upload it to the track you want.
