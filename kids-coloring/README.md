# Coloring Fun

A simple, **ad-free, offline** colouring app for young children. Pick a
difficulty, tap a region to fill it with the selected crayon, flip between
pictures, and save your art to the gallery. No ads, no network, no tracking, no
accounts.

- **Three difficulty levels** — a difficulty selector at the top switches
  between pages aimed at older kids, teens and adults (not toddlers):
  - **Easy** — clean, simple designs (Crystal Gem, Crescent & Stars, Maple Leaf,
    Lotus, Simple Mandala).
  - **Medium** — detailed figures (Rose, Butterfly, Koi, Owl, Seashell, Sailing).
  - **Hard** — intricate, adult-colouring-book pages: **mandalas** and a **rose
    window** (dozens of fillable pieces, generated procedurally), a **peacock**,
    a layered **mountain landscape**, and original scenes *in the style of*
    public-domain painters (Starry Swirls, The Big Wave, Lily Pond). Each shows
    a style note under its title.
- **Original art only.** Every picture is drawn from scratch in code out of
  circles, polygons, stars and radial ring generators — no third-party, branded,
  or copyrighted images (not from Crayola, stock sites, or anywhere else). The
  Hard painterly pages are **original compositions** inspired by the *style* of
  public-domain artists (Van Gogh, Hokusai, Monet), not reproductions of any
  specific artwork.
- **No permissions.** No `INTERNET`; saving uses scoped MediaStore (no storage
  permission on Android 10+).
- Jetpack Compose, single module (`:app`), min SDK 29.

## How it works
- `art/Geometry.kt` — point type, shape builders, point-list transforms
  (rotate/translate), the `Mandala` ring generators (petal/triangle/diamond/
  sector/bead rings that build the intricate radial pages), and even-odd ray-cast
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
