# Wireless debugging: Pixel phone and Pixel Watch

How to get a phone and a watch onto `adb` over Wi-Fi, install builds onto them,
and read their logs — without a cable, and without the parts that waste hours.

Written from a session that lost most of a night to the failure modes at the
bottom. Read those before debugging anything; most "the app is broken" symptoms
in this setup were not the app.

## The devices in this setup

| Device | Address | Notes |
|---|---|---|
| Pixel phone | `192.168.0.22:5555` | pinned, see "Pinning the port" |
| Pixel Watch | `192.168.0.10:5555` | pinned |
| Android TV | `192.168.0.12:5555` | developer-options ADB, deauthorises on restart |
| PC (relay host) | `192.168.0.18` | runs the HTTP→ADB relay on port 8080 |

`adb` lives at `C:\platform-tools\adb.exe`. Every PowerShell window needs this
before anything else, because the variable does not survive a new window:

```powershell
$adb = "C:\platform-tools\adb.exe"
```

Adding `C:\platform-tools` to the user PATH makes `adb` work directly in new
windows:

```powershell
[Environment]::SetEnvironmentVariable("Path", $env:Path + ";C:\platform-tools", "User")
```

## Pairing a device, once

Both the phone and the watch use the same flow. The watch's screens are smaller
but identically named.

1. **Settings → System → Developer options → Wireless debugging** — turn it on.
2. Tap **Pair device with pairing code**.
3. The dialog shows an address and a six-digit code. Both are single-use.

```powershell
$adb pair 192.168.0.22:41234 123456
```

Expect `Successfully paired to ...`.

**The trap that catches everyone:** the pairing dialog's port is *not* the port
you connect to. Go back to the main Wireless debugging screen and read the
`IP address & Port` line — that is a different number. Pairing uses one, the
connection uses the other, and the pairing one dies immediately after use.

After pairing, the device usually appears on its own via mDNS:

```powershell
& $adb devices
```

A line like `adb-66060DLKY000Y3-62SATd._adb-tls-connect._tcp  device` is the
phone; `adb-48171JEAYL03BM-YaCY7j._adb-tls-connect._tcp` is the watch. If it is
listed, you are connected and do not need the port at all.

## Pinning the port

Wireless debugging rotates its port — on reboot, on idle disconnect, sometimes
within minutes. Chasing it is the single most repetitive part of this setup, and
it is avoidable. Once connected by any route:

```powershell
& $adb -s adb-66060DLKY000Y3-62SATd._adb-tls-connect._tcp tcpip 5555
& $adb connect 192.168.0.22:5555
```

The device is then reachable at `:5555` until it reboots. Do this immediately
after pairing, every time, for both devices.

Pairing itself survives reboots. Only the port moves, so after a restart it is
usually enough to read the current `IP address & Port` off the device, connect
to it once, and re-pin with `tcpip 5555`.

## Installing a build

```powershell
& $adb -s 192.168.0.22:5555 install -r "$HOME\Downloads\mobile-debug.apk"
& $adb -s 192.168.0.10:5555 install -r "$HOME\Downloads\wear-debug.apk"
```

`-r` replaces the existing install and keeps its data, which matters here
because the target registry and the taught aims live in app storage.

Launch an app without touching the device:

```powershell
& $adb -s 192.168.0.10:5555 shell am start -n com.bluetier.flippercompanion/com.bluetier.flippercompanion.wear.MainActivity
```

### Confirm what actually got installed

```powershell
& $adb -s 192.168.0.22:5555 shell dumpsys package com.bluetier.flippercompanion | Select-String versionName
```

This prints `versionName=0.1.0-<commit>`. Both apps also show `build 0.1.0-<commit>`
on screen. That stamp exists because of the failure mode below, and it answers
"did my change actually get onto the device?" in two seconds.

Verify a download before installing it, when the URL is a rolling release tag
and the filename never changes:

```powershell
(Get-FileHash "$HOME\Downloads\mobile-debug.apk" -Algorithm SHA256).Hash
```

Compare it against the hash published with the release.

## Reading logs

```powershell
& $adb -s 192.168.0.10:5555 logcat -c
& $adb -s 192.168.0.10:5555 logcat -s FlipperSnap:* -v time
```

`-c` clears the buffer first, so what you see is only what just happened.
`-s TAG:*` filters to one tag.

Tags in this project:

- `FlipperSnap` — the watch: reader state, every transient with its gates, every
  accepted snap with aim/wobble/peak/gyro, the send result, the phone's reply.
- `FlipperNfc` — the phone: reader mode on/off, every tag seen, write results.

For system-level NFC, which sits below the app and tells you whether the
controller saw anything at all:

```powershell
& $adb -s 192.168.0.22:5555 logcat -v time -s NfcService:* NativeNfcTag:*
```

For anything without a known tag, filter on text instead:

```powershell
& $adb -s 192.168.0.22:5555 logcat -v time | Select-String -Pattern "Nfc|NFC"
```

**Log before you theorise.** Every long detour in this project came from
reasoning about behaviour that one log line would have settled. The wear module
had exactly one log statement in it for most of its life, and the cost of that
was measured in hours and in photographs of a watch face taken in the dark.

## Failure modes, and how to recognise them

**The port rotated.** `cannot connect ... target machine actively refused it`.
Read the current `IP address & Port` off the device and reconnect. Then pin it.

**The device dropped off.** `device 'x.x.x.x:5555' not found`. Just
`& $adb connect` it again; the pairing is still good.

**A stale APK.** The release tag rolls, so every build downloads from one URL
under one filename, and `Downloads` fills with `mobile-debug.apk`,
`mobile-debug (1).apk`, `mobile-debug (2).apk`. Installing yesterday's build and
then debugging today's symptom costs an hour every time. Install over `adb` from
a filename containing the commit, and check `versionName` afterwards. On a
phone, Chrome's **⋮ → Downloads** list is ordered by time; the Files app is
ordered by name, which puts the oldest first.

**NFC only works while the app is in the foreground.** An app holding
`enableReaderMode` receives tags; the system does not dispatch NFC intents to
anything while that is true. Switch to Settings or another app and tags silently
go nowhere. `FlipperNfc: reader mode off` in the log is the tell.

**NFC stops entirely when the screen sleeps.** Android disables polling on
screen-off. Holding a phone against a tag while the screen times out produces
nothing at all, with no error.

**A tag on metal will not read.** Plain NFC stickers are detuned by a metal
surface, and most TV chassis are metal. Use an on-metal tag with a ferrite
backing, or mount it on plastic or the wall.

**A peeled tag is often a dead tag.** The antenna is a hair-thin coil in the
adhesive. A tag that read perfectly before being moved and reads nothing after
is torn, not misconfigured. It looks undamaged.

**Killing a listener by port does not work.** `HttpListener` runs on the
kernel's HTTP.sys driver, so `Get-NetTCPConnection -LocalPort 8080` reports the
owning process as `System (4)` and `Stop-Process` is denied. Kill the script
instead:

```powershell
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -like '*adb-relay*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

If that finds nothing, the relay was started by hand in a window — find it on
the taskbar and press Ctrl+C.

**The TV deauthorises itself.** Restarting an Android TV clears its ADB key
grant. `adb devices` shows `unauthorized`. Wake the TV to its home screen,
reconnect, and accept the prompt **on the TV**, ticking "always allow". If no
prompt appears, check Developer options still has USB/network debugging on —
a restart can switch it back off.

## Starting a new app against these devices

Nothing above is specific to this project except the package names and log tags.
For a new app:

1. Pair and pin both devices once, as above.
2. Give the app a `versionName` carrying the commit, and show it on screen.
   In the root `build.gradle.kts`, read the commit with `providers.exec` — the
   configuration cache refuses a plain `ProcessBuilder` — and wrap it so a
   checkout without git still builds.
3. Pick one log tag for each subsystem and log the whole path under it from the
   first commit: inputs, decisions, outcomes, and the reason for every
   rejection. Include the number that caused the decision, never just the
   verdict. A gate that says "too much" without saying how much cannot be tuned
   by anyone.
4. Build and test locally before pushing. The Android SDK is not needed on the
   device — `./gradlew :app:assembleDebug :app:testDebugUnitTest` on the
   development machine catches the compile errors that otherwise cost a full CI
   round trip each.
