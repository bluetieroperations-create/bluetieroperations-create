# Session handoff — Pixel Flipper Companion

A snapshot of everything built and decided in this working session, so a future
session (or person) can pick it up cold.

## TL;DR
- Built **Pixel Flipper Companion**, an Android phone + Wear OS app that does the
  Flipper-Zero-style things a Pixel can do with its own hardware (NFC, BLE), plus
  an OBD-II terminal, a device finder, and an unwanted-tracker sweep.
- Everything lives under **`pixel-flipper-companion/`** on branch
  **`ccr-f57e1c8e-dnp3wk`**. 13 commits, ~65 files, 60 passing unit tests.
- **GitHub Actions builds installable APKs** on every push and publishes them to
  the **`debug-latest`** prerelease — no local Android toolchain needed.
- The phone app is **installed and running on a Moto G Play (XT2413V)**; BLE scan
  confirmed working on-device. Full feature testing on real hardware is still
  mostly pending.

## Where things live
- **Repo / branch:** `bluetieroperations-create/bluetieroperations-create`, branch
  `ccr-f57e1c8e-dnp3wk`, subfolder `pixel-flipper-companion/` (its own Gradle root).
- **Docs:** `README.md` (features, architecture, limitations), `INSTALL.md`
  (phone + watch install, Wi-Fi or USB), this file.
- **CI:** `.github/workflows/android-build.yml` (at the repo root).
- **Release with APKs:** GitHub → Releases → **`debug-latest`** (Pre-release).
  Direct link to the phone APK:
  `https://github.com/bluetieroperations-create/bluetieroperations-create/releases/download/debug-latest/mobile-debug.apk`

## What the app does
**Phone (`:mobile`, min SDK 29, target 35):**
- **NFC read** (reader mode) — UID, tech list, decoded NDEF (text + URI).
- **NFC write** — writes text/URL NDEF to a tag (or formats a blank one) on tap.
- **NFC HCE** — emulates a card to a reader (sample applet).
- **BLE scan** — live list with name, signal, and a **device-identity label**
  ("Apple · heart rate", "Samsung · SmartTag", "Unknown device", …).
- **BLE GATT** — connect, service/characteristic discovery, read/write/notify.
- **Auto-reconnect** (exponential backoff, re-arms notifications) + **MTU
  negotiation** (up to 517) + **bonding/pairing**.
- **OBD-II terminal** — ELM327 over a BLE UART dongle: AT/OBD commands, PID
  decode (RPM/speed/temps/voltage), quick commands.
- **Hot/cold finder** — lock a device, walk it down via smoothed RSSI + a
  warmer/colder cue and proximity band.
- **Unwanted-tracker sweep** — catalogs advertisers and flags likely item
  trackers (AirTag/Find My, Samsung SmartTag, Tile) by signature + persistence.
- Receives **triggers from the watch** over the Wearable Data Layer.

**Watch (`:wear`, Pixel Watch 3 / Wear OS):**
- BLE scan on the wrist, and a remote that fires phone actions.

**`:shared`** — `WearContract`: the phone⇄watch message protocol.

## Hardware reality (why some things are phone-only)
A Pixel/phone has **no sub-GHz radio, no 125 kHz LF RFID, no IR blaster**, so it
can't be a full Flipper for those. NFC/BLE are what it can do natively. Those
gaps need an external USB-C dongle (SDR / IR / NFC reader) — noted as future work,
not built.

## Key files (by area)
- `shared/.../WearContract.kt` — phone⇄watch paths, capability, action enum.
- `mobile/.../nfc/NfcReader.kt` — reader-mode reads + armed NDEF writes.
- `mobile/.../nfc/NfcMessages.kt`, `NdefText.kt` — NDEF build/decode.
- `mobile/.../hce/CardEmulationService.kt` — HCE applet (swap the sample AID).
- `mobile/.../ble/BleScanner.kt` — aggregated scan (RSSI smoothing, mfr/service data).
- `mobile/.../ble/BleConnection.kt` — GATT client: serial op queue, API-33 dual
  paths, reconnect, MTU, bonding.
- `mobile/.../ble/DeviceIdentity.kt` — "<maker> · <type>" labels.
- `mobile/.../ble/TrackerClassifier.kt`, `SweepAnalysis.kt` — tracker sweep logic.
- `mobile/.../ble/Proximity.kt` — RSSI → band/metres + warmer/colder TrendTracker.
- `mobile/.../obd/` — `UartDetect`, `ObdFraming`, `ObdPids`, `ObdController`.
- `mobile/.../ui/` — Compose screens: Companion (scan + NFC), Gatt, Obd, Finder,
  Sweep; `MainViewModel`; `MainActivity`.
- `wear/.../` — Wear UI + `PhoneConnector` (Data Layer).

## Build & CI
- `.github/workflows/android-build.yml`: on push (or manual), sets up JDK 17 +
  Android SDK, builds both debug APKs, **publishes them to the `debug-latest`
  prerelease**, then runs unit tests. Tests run **after** publish so a test
  failure can't block the APK.
- CI fixes made this session: the `setup-android` action's obsolete `tools`
  package was skipped and exact SDK packages installed; a framework-dependent NFC
  unit test (`NfcMessagesTest`) was removed because it can't run under plain JVM
  unit tests (needs real `android.jar`, not the stub).

## Install / update over Wi-Fi (what actually worked)
No Android Studio needed. On Windows:
1. adb installed standalone at `C:\platform-tools\adb.exe` (platform-tools zip
   extracted to `C:\`).
2. Phone: Settings → Developer options → **Wireless debugging** on.
3. Pair once: `C:\platform-tools\adb.exe pair <ip>:<pairPort> <6-digit-code>`
   (from the phone's "Pair device with pairing code" popup).
4. Each session: read the **current** IP:port off the main Wireless-debugging
   screen (**the port changes** every time debugging restarts), then:
   ```
   C:\platform-tools\adb.exe connect <ip>:<port>
   C:\platform-tools\adb.exe -s <ip>:<port> install -r "$env:USERPROFILE\Downloads\mobile-debug.apk"
   ```
   `-s <ip>:<port>` is needed because the phone shows up twice in `adb devices`
   (explicit + mDNS). Download a **fresh** `mobile-debug.apk` first (delete the old
   one so the name has no `(1)`).

Gotchas hit: PowerShell treats `<` `>` as operators (don't paste placeholders
literally); "device offline"/"actively refused" means the port rotated — re-read
it and reconnect.

## Testing status
- **Verified offline (CI + a local harness):** all code type-checks against
  Robolectric `android-all` API 35; all 5 Compose screens compile against Compose
  desktop 1.7; **60 unit tests pass** (hex, NDEF text, reconnect backoff, OBD
  framing/PIDs, UART detect, tracker classify, proximity/trend, sweep ranking,
  device identity, watch contract).
- **On real hardware:** installed on the **Moto G Play (XT2413V)**; **BLE scan
  confirmed working**. Not yet exercised on device: GATT connect, OBD, finder,
  sweep behavior, NFC (the Moto G Play **has no NFC** — NFC must be tested on the
  Pixel 11).

## Devices in play
- **Pixel 11** (owner's main) — full NFC + BLE; the intended host for everything.
- **Pixel Watch 3** — Wear app target (NFC locked to Wallet; BLE + remote only).
- **Moto G Play 2024 (XT2413V)** — test phone, **Android 13**, **no NFC chip**.
  App installed + running here. Being considered for repurposing as a kid's phone.

## Open threads / next steps
- **VW Taos remote start** — explicitly deferred to a separate session. Summary:
  remote start is a **cloud** feature (phone → VW servers → car modem), gated by
  login + an active remote-access subscription; it is **not** Bluetooth and
  **not** something to clone from the app. The legit path is a thin client using
  the owner's own credentials (EU "We Connect" has maintained libraries; US
  "Car-Net" does not reliably). Separate cloud project. Also: the car's eCall/SOS
  warning light is a telematics-module fault (often a dead backup battery) —
  generic OBD-II can't fix it; needs a VW-specific scan / dealer.
- **Child-safe Moto G Play** (in progress when session ended) — recommended path:
  **factory reset → set up with a Google child account under Family Link**
  (app-install approval, screen-time, content filters, SafeSearch, location,
  remote lock); that also wipes bloat and the dev-mode/sideload holes we opened.
  Lighter path: disable unwanted apps in Settings → Apps, turn off Developer
  options/Wireless debugging + "install unknown apps", add Family Link. adb
  debloat (`pm uninstall --user 0 <pkg>`, never Play Services/Store/SystemUI) is
  optional and only for apps that won't disable in Settings.
- **App ideas raised, not built:** live OBD dashboard (gauges + logging + DTC
  read/clear), phone-as-BLE-peripheral (GATT server), NFC-triggered automation,
  signal number + warmer/colder cue directly on sweep cards, external USB-C
  dongles for sub-GHz/IR/LF-RFID.

## Known limitations (from README)
- HCE only answers readers while the app is **background** (reader mode disables
  emulation in foreground).
- Watch NFC buttons are display-only; watch triggers need the phone app alive.
- `BLUETOOTH_SCAN` is `neverForLocation`, so some beacons are filtered out.
- Device labels/tracker flags are **heuristics** — many devices stay "Unknown" by
  BLE privacy design (nameless ads, rotating MACs).

## Commits this session (newest first)
```
0e94611 Add device identity labels to scan, finder, and sweep rows
e14604f Fix CI: drop framework-dependent NFC test, publish APKs before tests
3832930 Fix Android SDK setup in build workflow
cb8717c Add GitHub Actions workflow to build debug APKs
9219c63 Add NFC tag writing (NDEF text / URL)
f69fc10 Add INSTALL.md with Pixel phone + watch install steps
1197ac8 Add hot/cold device finder and unwanted-tracker sweep
ca274e2 Add OBD-II (ELM327) terminal over BLE UART
7e3284f Fix audit findings: Compose crash, NDEF reads, bond tracking, insets
e143014 Add BLE bonding and pairing support
34fc432 Add BLE auto-reconnect and MTU negotiation
b2b11d2 Add BLE connect + GATT read/write/notify to phone app
1b1762a Add Pixel Flipper Companion scaffold (phone + Wear OS)
```
