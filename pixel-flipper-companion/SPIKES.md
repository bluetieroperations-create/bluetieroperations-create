# Spikes: can this hardware do point-and-snap?

Two measurements that decide the design of a snap-and-point remote, before any
of it gets built. Both ship in the app; neither needs a local Android toolchain.

The target design, for context: wear the watch on the arm you point with and
snap with **that same hand**. One limb makes both gestures, the watch IMU sits
~15 cm from the snap, and the watch's orientation *is* the forearm's aim vector.
The phone stays in a pocket and does the resolving and dispatching.

Both spikes exist because the two load-bearing numbers in that design are
unknown, and a spec sheet will not answer either.

---

## Spike 1 — what sample rate does the wrist actually deliver?

**The question.** A finger snap at the wrist is a ~10–20 ms acceleration
transient. Catching its *shape* takes roughly 200 Hz. `SENSOR_DELAY_FASTEST` is
a hint, not a contract, and Wear OS routinely delivers less. If the Pixel Watch 3
caps out near 100 Hz, a snap spans one or two samples, the IMU cannot
characterise it, and the microphone has to carry the decision — which is a
different app.

**Where it lives.** Watch app → *Snap lab* → **Start capture**.

**What to read off the screen.**

| Reading | Means |
|---|---|
| `accel NNN Hz` | the rate actually delivered — the headline number |
| `gyro NNN Hz` | same for the rotation gate |
| `snaps` / `rejected` | accepted vs. near-miss counts this session |
| `N samples, X ms, peak Y` | the last transient's shape |

**The number that decides it** is `N samples` on a real snap:

- **1–2 samples** → the IMU can detect *that* something sharp happened but not
  *what*. Fuse with the watch microphone and treat the IMU as a gate.
- **3–6 samples** → enough shape to separate a snap from a knock. The design
  works as drawn.
- **more** → comfortable; tighten `maxDurationMs` to cut false positives.

**Method.** Start capture, then snap 20 times with the watch hand, pausing ~1 s
between snaps (the detector has a 250 ms debounce). Then deliberately produce
near-misses — clap, flick your wrist, knock a table, take a few steps — so the
rejected traces are in the file too. Tuning a detector is mostly a matter of
studying what it *almost* caught.

**One permission is load-bearing.** Android 12 (API 31) caps third-party sensor
sampling at 200 Hz unless the app holds `HIGH_SAMPLING_RATE_SENSORS`. It is
declared in the watch manifest. Without it, a reading of "200 Hz" would be the
permission talking rather than the hardware — so if you ever see exactly 200 Hz,
check the permission survived before believing the ceiling.

### Getting the traces off the watch

Every completed transient — accepted *and* rejected — is sent to the phone and
appended to a CSV. Phone app → *Pointing & snap probe* shows the running count
and the exact file path. Pull it with the adb-over-Wi-Fi workflow from
[INSTALL.md](INSTALL.md):

```
adb pull /sdcard/Android/data/com.bluetier.flippercompanion/files/snap-traces.csv
```

One row per transient. `waveform` is a space-separated
`offsetMicros:accelMagnitude:gyroMagnitude` list, with offsets relative to the
transient's start — **negative in the pre-roll**, since the captured window opens
before the sample that triggered detection.

### Re-tuning without a rebuild

`TransientConfig` holds every threshold, and `TransientDetectorTest` shows the
same signal being re-judged by two different configs. Once you have real traces,
replay them against candidate configs in a unit test rather than guessing on the
wrist.

---

## Spike 2 — how should this phone resolve "pointing"?

**The question.** Pointing is the hard half of the feature. The answer depends
entirely on whether the host phone has a UWB radio: with it, angle of arrival
gives a real bearing (~±5°); without it, you are matching a compass heading
against targets calibrated from a known standing spot, and indoor magnetometer
error of ±10–20° sets the floor on how close two targets can sit.

**Where it lives.** Phone app → *Pointing & snap probe*. Reads once, no
permission needed.

**What it reports.**

- Device model and API level.
- **UWB radio: present / absent** — from `PackageManager.FEATURE_UWB`, a plain
  system-feature query. Deliberately no `androidx.core.uwb` dependency yet: the
  question at this stage is only whether the radio exists. If it does, pulling
  in the UWB library for real ranging is the next step; if not, there is nothing
  to pull it in for.
- Every pointing-relevant sensor with its hardware max rate, resolution and
  vendor.
- A recommended strategy, and a warning if the fused rotation vector is present
  with no magnetometer behind it (which the sensor type alone does not
  advertise, and which would leave it with no true north).

**What each verdict means for the build:**

| Strategy | What to build |
|---|---|
| `UWB_ANGLE_OF_ARRIVAL` | add `androidx.core.uwb`; needs UWB anchors as well as a UWB phone |
| `FUSED_ROTATION_VECTOR` | compass + pitch against a calibrated registry; lean on the BLE census to narrow candidates |
| `GAME_ROTATION_VECTOR_ONLY` | relative only — the registry needs regular re-zeroing; a fallback, not a plan |
| `UNSUPPORTED` | no pointing from this device |

The decision rule is in code (`PointingCapabilities.strategy`) and unit-tested,
so the probe answers the question rather than just dumping a sensor list.

> **Note on the host device.** The session handoff lists a **Pixel 11** as the
> intended host; an earlier conversation mentioned a Pixel 3. It matters, because
> UWB has only ever shipped on Pro-tier Pixels and a Pixel 3 has none, no
> `FEATURE_UWB` string, and no security patches since 2021. Rather than trusting
> a spec sheet, run the probe on whichever phone will host this and let it say.

---

## What is deliberately not built yet

`TargetRegistry` (NFC-tap calibration binding a tag to a BLE address, a hub
entity and a calibrated vector) and `TargetResolver` (scoring orientation
against the live BLE census) both wait on these two numbers. Building them
before knowing the achieved sample rate and the pointing strategy would mean
guessing at both.

Also unresolved, and worth deciding consciously: `BLUETOOTH_SCAN` is currently
declared `neverForLocation`, which filters out some beacons. A resolver that
uses the BLE census to narrow pointing candidates makes that flag load-bearing,
and dropping it means taking the location permission — reversing a deliberate
privacy choice.
