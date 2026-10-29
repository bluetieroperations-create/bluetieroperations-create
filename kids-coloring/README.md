# Coloring Fun

A simple, **ad-free, offline** colouring app for young children. Pick a
difficulty, tap a region to fill it with the selected crayon, flip between
pictures, and save your art to the gallery. No ads, no network, no tracking, no
accounts.

- **Three difficulty levels** — a difficulty selector at the top switches
  between:
  - **Easy** — a handful of big, simple regions (Sun, Flower, Balloon, Star,
    Fish).
  - **Medium** — richer scenes with more pieces (Cozy House, Sailing, Butterfly,
    Little Car, Rocket, Cupcake).
  - **Hard** — detailed scenes *in the style of* famous public-domain painters
    (Starry Swirls, The Big Wave, Sunflowers, Lily Pond), with lots of small
    regions. Each shows a style note under its title.
- **Original art only.** Every picture is drawn from scratch out of basic shapes
  (circles, rectangles, triangles, stars) — no third-party or branded images.
  The Hard pages are **original compositions** inspired by the *style* of
  public-domain artists (Van Gogh, Hokusai, Monet); they are not reproductions
  of any specific artwork.
- **No permissions.** No `INTERNET`; saving uses scoped MediaStore (no storage
  permission on Android 10+).
- Jetpack Compose, single module (`:app`), min SDK 29.

## How it works
- `art/Geometry.kt` — point type, shape builders, point-list transforms
  (rotate/translate, used to compose the Hard scenes), and even-odd ray-cast
  point-in-polygon hit testing (pure Kotlin, unit-tested).
- `art/Picture.kt` / `art/Pictures.kt` — pictures as lists of polygon regions,
  each tagged with a `Level` (Easy/Medium/Hard) and an optional style subtitle;
  `regionAt()` returns the front-most region under a tap, `byLevel()` groups the
  built-in pages by difficulty.
- `ui/ColoringViewModel.kt` — current level and picture, per-region fill colours
  (kept per picture, keyed by its unique name so switching level/page never
  loses work), selected crayon.
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
