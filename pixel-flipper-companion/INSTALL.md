# Installing on a Pixel phone and Pixel Watch

These are **debug builds** for personal use — no Play Store needed. The build runs
on your own computer; it needs [Android Studio](https://developer.android.com/studio)
(latest stable), which bundles the right JDK and downloads **Android SDK 35** on
first run. `adb` ships with the SDK (in `platform-tools/`).

Everything here can be done **over Wi-Fi** — no USB cable required for either
device. The only requirement is that your computer, phone, and watch are on the
**same Wi-Fi network**.

---

## 1. Get the code

```bash
git clone https://github.com/bluetieroperations-create/bluetieroperations-create.git
cd bluetieroperations-create
git checkout ccr-f57e1c8e-dnp3wk
```

In Android Studio: **Open** → select the **`pixel-flipper-companion`** folder
(this subfolder is the Gradle project root, not the repo root). Let it sync —
it downloads dependencies and may suggest minor version bumps; accept them. This
first sync is also the real compile check.

Modules: `:mobile` (phone), `:wear` (watch), `:shared` (common code). Both apps
share the application id `com.bluetier.flippercompanion` and install independently.

---

## 2. Phone (Pixel 11)

### Enable developer access
1. **Settings → About phone → tap "Build number" 7 times.**
2. **Settings → System → Developer options** → turn on **Wireless debugging**
   (or **USB debugging** if you prefer a cable).

### Option A — Wi-Fi (no cable)
1. Open **Wireless debugging → Pair device with pairing code**. The phone shows
   an **IP address : port** and a **6-digit code**.
2. On the computer:
   ```bash
   adb pair <phone-ip>:<pair-port>      # enter the 6-digit code when prompted
   adb connect <phone-ip>:<connect-port> # the IP:port shown on the main Wireless debugging screen
   ```
   (The *pair* port and the *connect* port are different — the pairing dialog
   shows the first, the main screen shows the second.)
3. In Android Studio pick the **`mobile`** run config, select the phone, **Run (▶)**.

### Option B — USB cable
1. Plug in the phone, tap **Allow** on the "authorize this computer?" prompt.
2. Pick the **`mobile`** run config, select the phone, **Run (▶)**.

### First launch
Grant **Nearby devices / Bluetooth** when asked (needed for scanning and GATT).
NFC works automatically if it's enabled in system settings.

---

## 3. Watch (Pixel Watch 3) — Wi-Fi only

The watch has no USB port, so it's always wireless.

1. On the watch: **Settings → System → About → tap "Build number" 7 times.**
2. **Settings → Developer options** → enable **ADB debugging** and
   **Wireless debugging / Debug over Wi-Fi**.
3. Open **Wireless debugging → Pair new device** — the watch shows an
   **IP : port** and a **6-digit pairing code**.
4. On the computer:
   ```bash
   adb pair <watch-ip>:<pair-port>       # enter the code
   adb connect <watch-ip>:<connect-port>
   ```
5. In Android Studio pick the **`wear`** run config, select the watch, **Run (▶)**.

> Tip: with both the phone and watch connected at once, `adb devices` lists both.
> Android Studio's target dropdown lets you pick which one each run goes to.

---

## Command-line alternative

From the `pixel-flipper-companion` folder, with the target device connected
(over Wi-Fi or USB):

```bash
./gradlew :mobile:installDebug     # installs on the phone
./gradlew :wear:installDebug       # installs on the watch
```

If several devices are connected, set `ANDROID_SERIAL` (from `adb devices`) so
Gradle installs to the right one:

```bash
ANDROID_SERIAL=<watch-ip>:<port> ./gradlew :wear:installDebug
```

### Build the APKs to sideload yourself

```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

- `mobile/build/outputs/apk/debug/mobile-debug.apk`
- `wear/build/outputs/apk/debug/wear-debug.apk`

Push one directly with `adb -s <serial> install <path>`. (Opening an `.apk`
through a file manager instead will prompt you to allow "install unknown apps".)

---

## Troubleshooting

- **`adb connect` fails / device not found** — confirm all three are on the same
  Wi-Fi (not a guest network that isolates clients), and that Wireless debugging
  is still toggled on (it resets after reboots and long idle periods).
- **Pairing code rejected** — you likely used the *connect* port for `adb pair`;
  use the port shown in the pairing dialog for `pair`, the main-screen port for
  `connect`.
- **Gradle sync nudges a version** — accept Android Studio's suggestion and
  re-sync; the pins in `gradle/libs.versions.toml` are a starting point.
- **Scanning finds nothing** — make sure Bluetooth is on and the Nearby-devices
  permission was granted; on first run the app requests it.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / "signatures do not match"** — the
  installed copy was signed with a different key than the one you are
  installing. This should no longer happen (see *App signing* below), but any
  build made before the keystore was committed carries a throwaway key, so going
  from one of those to a current build needs one uninstall:

  ```
  C:\platform-tools\adb.exe -s <ip>:<port> uninstall com.bluetier.flippercompanion
  ```

  That clears app data too, including any captured snap traces — pull the CSV
  first if you want to keep it.

## App signing

Debug builds are signed with `debug.keystore`, committed at the root of
`pixel-flipper-companion/`. Every build — CI or local, any machine — therefore
produces the same signature, and updates install over each other with plain
`adb install -r`.

Without it, AGP generates a fresh debug key wherever none exists, so each CI
runner signed with a different key and no two builds could replace one another.

The keystore holds the standard Android debug credentials (store and key
password `android`, alias `androiddebugkey`). It is deliberately not a secret:
a debug key cannot publish to Play, and the whole point of checking it in is
that everyone and every runner shares it. **Release signing must never work this
way** — a release key belongs in a secret store, never in git.

Each CI run prints the resulting certificate digests (step *Report APK signing
certificates*), so if signatures ever drift again it is visible in the build log
instead of only on a phone. The committed keystore's certificate is:

```
SHA-256: BF:74:C8:52:01:88:88:62:BD:35:49:4F:FD:A4:E6:C6:C1:C3:85:92:EB:76:1D:4D:52:0F:5C:93:FD:45:F8:68
```

To confirm what a given APK is signed with:

```
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs mobile-debug.apk
```
