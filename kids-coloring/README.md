# Coloring Fun

A simple, **ad-free, offline** colouring app for young children. Tap a region to
fill it with the selected crayon; flip between pictures; save your art to the
gallery. No ads, no network, no tracking, no accounts.

- **Original art only.** Every picture is drawn from scratch out of basic shapes
  (circles, rectangles, triangles, stars) — no third-party or branded images.
- **No permissions.** No `INTERNET`; saving uses scoped MediaStore (no storage
  permission on Android 10+).
- Jetpack Compose, single module (`:app`), min SDK 29.

## How it works
- `art/Geometry.kt` — point type, shape builders, and even-odd ray-cast
  point-in-polygon hit testing (pure Kotlin, unit-tested).
- `art/Picture.kt` / `art/Pictures.kt` — pictures as lists of polygon regions;
  `regionAt()` returns the front-most region under a tap.
- `ui/ColoringViewModel.kt` — current picture, per-region fill colours (kept per
  picture), selected crayon.
- `ui/ColoringScreen.kt` — Compose canvas (tap-to-fill), crayon palette, controls.
- `ui/SaveArt.kt` — renders the filled picture to a bitmap and saves it to
  Pictures/Coloring Fun via MediaStore.

## Build / install
CI builds an APK on every push and publishes it to the **`coloring-latest`**
release. Install `coloring-debug.apk` the same way as the companion app (see
`../pixel-flipper-companion/INSTALL.md` for the Wi-Fi/adb flow), or:

```bash
cd kids-coloring
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```
