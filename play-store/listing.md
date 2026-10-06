# Google Play store listing — CakeSync

Draft copy to paste into Play Console → Grow → Store presence → Main store listing.
Character limits are Play's (title 30, short description 80, full description 4000).

## App name (30 max)

    CakeSync: Cake Photos & Orders

## Short description (80 max)

    Find your cake photos, sort them, take orders and post — all from your phone.

## Full description

    Two hundred cake photos sitting in your gallery. Zero posts. CakeSync fixes that.

    CakeSync is built for home bakers, cake decorators and small bakeries. It finds
    the cakes in your camera roll, sorts them, and turns them into posts, a menu and
    tracked orders — without a laptop and without a cloud account.

    FIND AND SORT
    • One scan finds every cake photo and video on your phone
    • The scan runs privately on your device — nothing is uploaded just to check
    • Skips photos with people in them and near-duplicates, so only your best shots get through
    • Saves cakes into dated folders in your own gallery
    • Optional daily sync at the time you pick

    CREATE AND POST
    • Photo studio: clean backgrounds, filters, light and crop
    • Collages and reels with music and transitions
    • Your logo, business name and price on every post
    • Share to Instagram and WhatsApp with the caption ready

    ORDERS AND MENU
    • Send customers an order form by WhatsApp, text or email — their reply becomes an order
    • Orders are scheduled in your calendar with reminders
    • Bookings from your booking page turn into orders with one tap
    • Paste your price list once; every menu design shows its starting price
    • Share a menu card as pictures or a PDF

    YOUR DATA STAYS YOURS
    • No account needed. Photos, orders and prices stay on your phone
    • Optional backup to your own Google Drive — CakeSync can only see the files it creates
    • No ads, no tracking

    Light and dark themes included.

## Listing details

| Field | Value |
|---|---|
| App category | Business (alternative: Photography) |
| Tags | Business, Photography, Productivity |
| Contact email | **your support email** |
| Website | **your site, or the GitHub Pages URL** |
| Privacy policy URL | `https://techaipro.github.io/cakesync/privacy/` (after GitHub Pages is switched on) |
| Ads | No |
| Target audience | 18+ (business tool) |

## Graphics

| Asset | Requirement | Status |
|---|---|---|
| Phone screenshots | 2–8, each side 320–3840 px, long side at most 2× the short side | 8 ready in `screenshots/` (1080×2160) |
| App icon | 512×512 PNG | ready: `icon-512.png` (rendered from the launcher icon) |
| Feature graphic | 1024×500 PNG/JPG | **to do** |
| Promo video | YouTube URL (optional) | Upload `videos/cakesync-demo/renders/video.mp4` to YouTube |

The screenshots deliberately leave out the Gallery screen, because the test photos in the
emulator include a portrait. Retake that one with real cake photos before launch — it is the
app's core screen and should be screenshot 2.

## Permission declarations (Play Console → App content)

Paste-ready justifications. Play asks for these when the bundle is uploaded.

**Photo and video permissions (READ_MEDIA_IMAGES / READ_MEDIA_VIDEO)**

    CakeSync's core purpose is to scan the user's whole photo and video library to find
    the cake photos among them, then organise, edit and share them. The scan runs
    repeatedly (including an optional daily background sync) and needs broad, persistent
    access to the gallery; a one-time photo picker cannot provide this. All analysis is
    on-device.

**Foreground service — data sync (FOREGROUND_SERVICE_DATA_SYNC)**

    Used while scanning the gallery, copying matched cake photos into the user's gallery
    folders and, if the user connected Google Drive, uploading them to their own Drive.
    The task is started by the user ("Scan now" / "Save to gallery") or by the daily sync
    they enabled, shows a progress notification, and stops when the transfer finishes.

**Calendar (READ_CALENDAR / WRITE_CALENDAR)**

    Orders are stored as events in the calendar the user picks, with reminders, and
    existing bookings in that calendar can be turned into orders.

## Data safety form — suggested answers

These follow from how the app works (no developer server, no analytics SDK). Verify the ML Kit
line against Google's current "ML Kit Android data disclosure" page before submitting.

| Question | Answer |
|---|---|
| Does the app collect or share user data? | Yes — only the diagnostics Google's ML Kit / Play services libraries collect |
| Data types | App info and performance (diagnostics); Device or other IDs |
| Purpose | Analytics / app functionality (by the Google libraries) |
| Is data encrypted in transit? | Yes |
| Can users request deletion? | No account exists; nothing is held by the developer |
| Photos and videos | Not collected. Processed on device; Drive upload is a user-initiated transfer to the user's own account |
| Calendar events, contacts typed into orders | Not collected. Stored on device only |
