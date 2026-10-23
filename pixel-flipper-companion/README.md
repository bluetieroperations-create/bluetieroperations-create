# Pixel Flipper Companion

A Flipper-Zero-*inspired* toolkit for **Pixel 11** (phone) and **Pixel Watch 3**
(Wear OS) that uses **only the hardware those devices actually have**. It is a
starter scaffold — buildable in Android Studio — not a finished product.

> **What this is not:** you cannot turn a phone or watch into a Flipper Zero.
> The Flipper is mostly *radios* (sub-GHz CC1101, 125 kHz LF RFID coil, IR
> transceiver) that neither device contains. This project implements the subset
> a Pixel can do natively, and leaves hooks for external USB-C hardware.

## What each device can do

| Capability | Pixel 11 | Pixel Watch 3 | Notes |
|---|---|---|---|
| NFC read (13.56 MHz) | ✅ `NfcReader` (reader mode) | ❌ | Wear OS reserves NFC for Google Wallet |
| NFC write (NDEF) | ✅ `NfcReader.armWrite` + `NfcMessages` (text / URL) | ❌ | writes/formats tags you own |
| NFC host-card emulation | ✅ `CardEmulationService` (HCE) | ❌ | emulate to readers you control |
| BLE scan | ✅ `BleScanner` | ✅ `WearBleScanner` | both platforms expose BLE to apps |
| Hot/cold device finder | ✅ smoothed-RSSI proximity + warmer/colder | ❌ | `Proximity`, `FinderScreen` |
| Unwanted-tracker sweep | ✅ flags AirTag/SmartTag/Tile + persistent beacons | ❌ | `TrackerClassifier`, `SweepAnalysis` |
| BLE connect + GATT read/write/notify | ✅ `BleConnection` + GATT browser UI | ❌ (scaffold: phone only) | serial op queue; API 33 dual-path |
| Auto-reconnect + MTU negotiation | ✅ backoff retries, re-armed notifications, MTU up to 517 | ❌ | see `BleConnection` |
| Bonding / pairing | ✅ `createBond()` + bond-state tracking, system pairing dialog | ❌ | needed for encrypted characteristics |
| OBD-II terminal | ✅ ELM327 terminal over BLE UART (`obd/`) | ❌ | your own adapter, your own car |
| Trigger phone from wrist | — | ✅ `PhoneConnector` | Wearable Data Layer messaging |
| Snap detection (wrist IMU) | — | ✅ `SnapLab` + `TransientDetector` | capture/tuning spike; see [SPIKES.md](SPIKES.md) |
| Pointing capability probe | ✅ `CapabilityProbe` | ❌ | UWB + orientation sensors; see [SPIKES.md](SPIKES.md) |
| Sub-GHz / 125 kHz / IR | ⚠️ USB-C OTG dongle only | ❌ no USB port | out of scope for this scaffold |

The watch's real role here is a **remote**: its chips send actions
(`BLE_SCAN`, `NFC_READ`, `NFC_EMULATE`) to the phone, which has the hardware to
carry them out.

It is also the better *sensor* for a gesture remote, which is what the snap lab
explores: worn on the arm you point with, its IMU sits ~15 cm from a finger snap
and its orientation is the forearm's aim vector. See **[SPIKES.md](SPIKES.md)**
for the two measurements that decide whether a snap-and-point remote is
buildable on this hardware, and how to run them.

## Modules

```
pixel-flipper-companion/
├── shared/   # WearContract: message paths, capability name, action enum
├── mobile/   # Pixel 11 app  (NFC + BLE + receives watch triggers)
└── wear/     # Pixel Watch 3 app (BLE + sends triggers to the phone)
```

### Key files
- `shared/.../WearContract.kt` — single source of truth for the phone⇄watch protocol.
- `mobile/.../nfc/NfcReader.kt` — NFC reader-mode tag reads (UID, tech, NDEF) and
  armed NDEF writes (writes a writable tag, or formats a blank one, on the next tap).
- `mobile/.../nfc/NfcMessages.kt` — builds text / URL NDEF messages for writing.
- `mobile/.../hce/CardEmulationService.kt` — HCE applet (replace the sample AID/logic).
- `mobile/.../ble/BleScanner.kt` — BLE scan as a `StateFlow`.
- `mobile/.../ble/BleConnection.kt` — GATT client: connect, discover, read, write,
  notifications. One serial operation queue (GATT allows a single in-flight op),
  with dual-path handling for the API 33 callback/method changes.
  - **MTU:** requests 517 right after connecting (falls back to discovery after
    3 s if the peer never answers); no-response writes are capped at `MTU - 3`,
    longer with-response writes go out as long (prepared) writes.
  - **Auto-reconnect:** any drop while you still want the link — including a
    failed first attempt (the classic status 133) — closes the dead client and
    retries with exponential backoff (1 s → 16 s, 5 attempts by default), then
    re-arms the notification subscriptions that were active. Tunable via the
    `autoReconnect` / `maxReconnectAttempts` / `desiredMtu` constructor params.
  - **Bonding / pairing:** `createBond()` starts pairing (Android shows the
    system passkey/confirm dialog); bond state is tracked via the
    `ACTION_BOND_STATE_CHANGED` broadcast and exposed as a `StateFlow`. Set the
    `autoBond` constructor param to pair automatically on connect. A bond is a
    system-level pairing the user manages in Settings and persists across
    connections, so `disconnect()` deliberately does not remove it — and nor
    does the app (there is no public remove-bond API; removal is a Settings
    action). The GATT stack also triggers pairing on its own when you access an
    encrypted characteristic.
- `mobile/.../ui/GattScreen.kt` — GATT browser: services/characteristics with
  Read / Write / Notify controls and a live event log.
- `mobile/.../ble/BleScanner.kt` — aggregates each advertiser across the scan
  (smoothed RSSI, sighting count, first/last-seen, manufacturer/service data).
- `mobile/.../ble/Proximity.kt` + `ui/FinderScreen.kt` — the hot/cold finder:
  lock onto a device, then follow a smoothed signal and a warmer/colder trend as
  you move. Distance is a rough RSSI estimate, used only relatively.
- `mobile/.../ble/DeviceIdentity.kt` — labels each advertiser "<maker> · <type>" from
  its company ID (Apple/Samsung/Google/Microsoft/Nordic) and advertised service
  UUIDs (heart rate, HID, Fast Pair, SmartTag, Tile…); "Unknown device" otherwise.
- `mobile/.../ble/TrackerClassifier.kt` + `SweepAnalysis.kt` + `ui/SweepScreen.kt`
  — the unwanted-tracker sweep: classifies advertisers against known item-tracker
  signatures (Apple Find My type `0x12`, Samsung SmartTag `FD5A`/`FD59`, Tile
  `FEED`) and ranks by signature + persistence + proximity. Heuristic, and a
  supplement to the OS's own unknown-tracker alerts — not a replacement.
- `mobile/.../obd/` — ELM327 OBD-II terminal over the BLE UART. `UartDetect`
  finds the write/notify characteristics (HM-10 `0xFFE1`, Nordic UART, Vgate
  `0xFFF0`, or a generic fallback); `ObdFraming` reassembles replies on the `>`
  prompt; `ObdPids` decodes common Mode 01 PIDs; `ObdController` serializes
  commands (one request/response at a time, with a timeout). UI in
  `ui/ObdScreen.kt`, reached from the GATT browser's "Open as OBD terminal".
- `mobile/.../wear/PhoneMessageListenerService.kt` — receives watch triggers.
- `wear/.../phone/PhoneConnector.kt` — discovers the phone by capability and sends triggers.
- `wear/.../sensors/TransientDetector.kt` — snap detection from the wrist IMU:
  short, sharp, low-rotation. Every threshold is a `TransientConfig` parameter,
  and rejected transients are reported too, since tuning is mostly a matter of
  studying the near-misses.
- `wear/.../sensors/SampleRateEstimator.kt` — measures the rate a sensor stream
  *actually* delivers (`SENSOR_DELAY_FASTEST` is a hint, not a contract).
- `wear/.../sensors/SnapLab.kt` — the capture session: runs the IMU flat out,
  reports the achieved rate, ships every transient's waveform to the phone.
- `shared/.../SnapTracePayload.kt` — the trace wire format and CSV row. Hand-rolled
  delimited text, to keep the phone⇄watch contract free of a serialization dependency.
- `mobile/.../probe/PointingCapabilities.kt` — the pointing-strategy decision,
  encoded and unit-tested rather than left to a spec sheet.
- `mobile/.../snap/SnapTraceStore.kt` — accumulates watch traces into a CSV for `adb pull`.

## Build

See **[INSTALL.md](INSTALL.md)** for step-by-step install on a Pixel phone and
Pixel Watch (Wi-Fi or USB).

Requires Android Studio (Ladybug or newer) with the Android SDK; internet access
to resolve the Android Gradle Plugin.

```bash
# from this directory, after `sdkmanager` / Android Studio has installed the SDK:
./gradlew :mobile:assembleDebug   # phone APK
./gradlew :wear:assembleDebug     # watch APK
```

Create a `local.properties` with `sdk.dir=/path/to/Android/sdk` (Android Studio
writes this automatically on first open). Both modules share the
`com.bluetier.flippercompanion` application id so the watch app installs as the
phone app's companion.

> This scaffold was generated without a full Gradle sync (the build container
> blocks the Google Maven repo). The first `File ▸ Sync` in Android Studio will
> download dependencies and may nudge plugin/library versions in
> `gradle/libs.versions.toml` — accept the IDE's suggested updates if prompted.

## Tests

```bash
./gradlew :mobile:testDebugUnitTest :shared:testDebugUnitTest :wear:testDebugUnitTest
```

JVM unit tests cover the pure logic — hex parsing, NFC Text-record decoding,
reconnect backoff, the watch action contract, the snap transient state machine,
the sample-rate estimator, the trace wire format and the pointing-strategy
decision — plus a contract test that pins the listener's `pathPrefix` and
advertised capability in XML to `WearContract` (if those strings drift, watch
messages are silently dropped). The BLE/NFC/sensor classes themselves need a
device (or Robolectric) to exercise.

Each module keeps its Android-dependent glue separate from pure logic so the
logic stays JVM-testable: `TransientDetector` and `SampleRateEstimator` take
timestamps from the caller, and `CapabilityProbe` (framework) hands back a pure
`PointingCapabilities` (tested).

## Known limitations

- **HCE vs. reader mode:** while the phone app is in the foreground, NFC reader
  mode is on, and Android disables card emulation in reader mode. HCE answers
  readers only when the app is in the background.
- **Watch NFC actions are display-only:** "Read NFC" / "Emulate card" from the
  watch are shown on the phone but don't change its NFC mode, and
  `WearContract.PATH_RESULT` is not used yet, so the watch can't confirm the
  phone acted.
- **Watch triggers need the phone UI alive:** triggers go to the phone's
  ViewModel, so they're dropped if the phone app isn't running. A foreground
  service would fix this.
- **Beacon filtering:** `BLUETOOTH_SCAN` is declared `neverForLocation`, so
  Android filters some beacon advertisements out of scan results.
- **HCE while locked:** `requireDeviceUnlock="false"` lets the sample applet
  answer while the phone is locked; set it to `true` for anything sensitive.

## Scope & legitimate use

Build and test against **tags, cards, and devices you own or are authorized to
test**. HCE here emulates a card to *your* reader; it does not and cannot clone
the secure credential on someone else's access badge, transit card, or car key —
that crypto is not exposed to the host OS. Keep it that way.

The OBD terminal talks to a diagnostic adapter **you** plug into **your own**
vehicle. It sends standard ELM327/OBD-II commands and displays the replies; it
does not unlock, bypass, or modify any vehicle security function.

### Not in scope: cloning the manufacturer app for remote start

Remote engine start in VW's app is **not** a Bluetooth feature and is out of
this project's scope. It runs over the manufacturer's cloud (phone → VW servers
→ the car's built-in cellular modem), gated by your account login and an active
remote-access subscription. Decompiling/repackaging the proprietary app would
infringe copyright and still wouldn't work — the server authorizes the command,
not the app. The legitimate route is a thin client that signs in with **your own
credentials** and calls the documented remote-start endpoint (what community
Home-Assistant integrations do), which only works if your plan includes it.
That's a separate cloud project, not part of this BLE app.
