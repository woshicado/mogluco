# Libre 3 / 3+ handshake failures (upstream issue #405) — notes for the redesign

> Reference for when the real Libre 3+ sensor misbehaves on the Compose redesign.
> Source thread: https://github.com/j-kaltes/Juggluco/issues/405
> Written 2026-06-25. Inspection only — no code was changed.

## TL;DR

1. **The redesign reuses the original Libre 3 code verbatim.** The Compose `:app` is only a
   UI/orchestration layer; the NFC activation, the entire BLE security handshake, and the crypto
   all live in `:core` + the prebuilt native `libg.so`. Any bug behind #405 is present in the
   redesign too — you are **not** insulated.
2. **The redesign now surfaces the handshake failure** (implemented 2026-06-25 — see "Visibility:
   implemented" below). When the Libre 3 handshake fails, the home screen shows an error banner
   with the BLE status code and the failed handshake stage, plus a time-based "no data after
   warm-up" fallback. Previously it showed a sensor stuck *warming up forever* with no clue —
   worse than the original app, which at least shows `Status=147` / `SEC_CHAR_CHALLENGE_DATA`.

## What #405 actually is

The thread mixes several *unrelated* problems. The maintainer (j-kaltes) is right about that, and
disputes that it's a Juggluco regression (it works for him on Libre 3+, Android 8–17). Sorted by
relevance to us:

| Reported symptom | Relevant? | Nature |
|---|---|---|
| **`status 19` disconnect mid-handshake** (orig. poster, andlrc, Emeryjoule analysis) | **Most** | Handshake reaches `sendSecurityCommand 8`, sensor terminates the link, `kAuth` never obtained, `libre3initialized=0`. Inside shared code. |
| **`SEC_CHAR_CHALLENGE_DATA` / `status 147`** (many users) | **High** | Same security-challenge stage. Shared code. |
| **`status 133` / "no connection"** | Low | Connection never establishes — usually BT stack / sensor / permissions. |
| **State=2 "sensor not ready" / "sensor error"** | No | Comes *from* the sensor → Abbott's domain. |
| **SIGSEGV in `Libre3GattCallback.m`** (orig. poster trace) | Robustness | Native crash in the shared handshake path. |

### Two signals worth weighting

- **Downgrade evidence (strong but contested).** Several independent users report **10.6.2 / 10.6.4 /
  10.6.5 work, 10.7.x / 10.8 / 10.9 fail** on the *same* sensor + phone — pointing at a regression
  after 10.6.x. **The redesign is built on the newer (post-10.6) code**, i.e. the regressed side.
  Caveat: Emeryjoule and electricar say older versions did *not* help them, so sensor-batch
  variability is likely layered on top.
- **LOT / new-batch theory.** Users tie failures to a new "T + 8-digit" lot numbering scheme. If
  Abbott shipped a sensor revision with changed handshake timing/auth, that's upstream — can't be
  papered over in the UI layer.

## Proof the redesign shares the path

- `app/.../onboarding/Libre3SensorActivator.kt:22` → delegates straight to
  `tk.glucodata.Libre3Activation.activate()` in `:core` (NFC).
- BLE streaming + the full security handshake → `core/.../Libre3GattCallback.java`. Diffed against
  the original `Common/src/libre3/.../Libre3GattCallback.java`: **functionally identical** — the only
  changes are added `Sensors.notifyConnection(...)` calls. The state machine is the same
  (`sendSecurityCommand`, `challenge67`, `status==19` handling at `Libre3GattCallback.java:246-251`,
  `SEC_CHAR_CHALLENGE_DATA` at `:692`).
- Crypto/auth (`Natives.processbar`, `setLibre3kAuth`, `getLibre3kAuth`) is in the prebuilt native
  engine: `Common/src/main/cpp/libre3/loadlibs.cpp`, `bluetooth.cpp`. There is **no
  `core/src/main/cpp`** — `:core` links the same `libg.so`.

## Will I know I hit this error? — YES (implemented 2026-06-25)

> Historical note: before the 2026-06-25 change the answer was **no** — the banner only covered
> hardware faults and glucose alarms, the BLE status int was dropped at `notifyConnection`, and a
> failed handshake looked like an endless warm-up. The "Visibility: implemented" section below is
> the current behaviour; the trace beneath it documents the original gap for reference.

**The home-screen error banner now surfaces BLE/handshake failures** (see "Visibility:
implemented"). Original gap, for reference:

- Banner source: `HomeScreen.kt:162/174/199` → `state.sensor?.error?.let { ErrorBanner(it) }`.
- `SensorInfo.error` is set only in `NativeGlucoseRepository.updateSensor()` (`:96-116`), from:
  - `SensorError.fromSensorState(...)` → only **hardware** states 6/7/9 (error/replace/ended), and
  - `SensorError.fromGlucoseAlarm(...)` → only per-reading glucose alarms 3/4/5.
  Both require the sensor to be **streaming or reporting hardware state** — neither covers a
  handshake that never completes.
- `updateSensor()` runs on a glucose reading, or when `Sensors.setStateListener` fires with
  `connected == true` (`NativeGlucoseRepository.kt:60`). **On disconnect (`connected == false`) it
  does nothing** — `if (connected) ...`.
- The BLE status int (19/133/147) is **dropped at the boundary**: `Libre3GattCallback.java:236`
  calls `Sensors.notifyConnection(serial, false)` — boolean only, no code. The status lives in
  `SuperGattCallback.constatstatusstr` / `handshake`, and **nothing in `:app` reads those** (grep:
  zero consumers).

**Net behaviour when #405 strikes:** the sensor briefly connects, so `_sensor` is populated and you
see the **warm-up note** (`WarmupNote`, `HomeScreen.kt:175/200/418`). The handshake then fails and
the link drops silently. No reading ever arrives, the warm-up note never clears, **no banner, no
status code.** It looks like a sensor stuck warming up forever.

## Visibility: implemented (2026-06-25)

Two independent mechanisms now surface a stuck handshake. **You get an error banner on home.**

### 1. Engine-driven connection error (the precise path)

The BLE disconnect status + the failed handshake stage are plumbed from the engine to the UI:

- `Sensors.StateListener.onConnectionChanged(serial, connected, status, detail)` — widened to carry
  the GATT `status` and a `detail` string (`core/.../Sensors.java`).
- `Libre3GattCallback.onConnectionStateChange` passes the status on connect and disconnect, and on
  disconnect computes `detail` from the engine's handshake-failure record
  (`wrotepass[1] >= wrotepass[0] ? handshake : null`) — so a locally-aborted handshake (status 0,
  e.g. an unexpected SEC_CHAR_CHALLENGE_DATA length) is still reported, not just peer terminations.
- `SensorError.fromConnection(status, detail)` (`SensorInfo.kt`) maps it to a "Sensor setup failed"
  error: 8 = link timed out, 19 = sensor dropped the link, 133 = connection failed, 147 = secure
  handshake rejected, plus the handshake stage when known.
- `NativeGlucoseRepository.handleDisconnect` gates when it shows:
  - **ignored while data is flowing** (a reading within `STREAMING_WINDOW_MS` = 6 min) so normal
    signal-loss reconnects stay silent;
  - a recorded handshake failure (`detail != null`) shows **immediately**;
  - a bare error status must recur `STATUS_FAIL_THRESHOLD` (3) times, so one warm-up Bluetooth
    glitch doesn't false-alarm;
  - cleared as soon as a reading arrives or a new sensor session begins.
  - Banner priority: hardware fault (6/7/9) > connection/handshake error > glucose alarm.

### 2. Time-based "no data after warm-up" fallback

`SensorStatusBanner` in `HomeScreen.kt` (replaced the old `WarmupNote`): error banner → warm-up
countdown → **"No data after warm-up"** banner once `warmupEndMillis() + STUCK_GRACE_MS` (5 min)
elapses with still no reading. Catches a silent failure that produced no mapped disconnect.

### What you'll see when #405 strikes

The status-19 / SEC_CHAR_CHALLENGE_DATA family produces repeated failing disconnects, so within a
couple of minutes the **"Sensor setup failed (code N)"** banner appears with the stage detail. Even
in a fully silent case, the **"No data after warm-up"** banner appears ~65 min after activation. No
more sensor-stuck-warming-up-forever with zero feedback.

### Still possible future work

- Expose `constatstatusstr` / `handshake` (live, in `SuperGattCallback`) on a dedicated
  sensor-info/debug screen, mirroring the original app's Info section, for richer diagnostics.
- Consider a **notification** (not just an in-app banner) when setup fails — the user may not have
  the app open during the 60-min warm-up.

## When testing the real sensor

- **Test on a physical Libre 3+** (this path can't be exercised without one). Mind the
  device-testing hazard (pin adb/gradle to the emulator; your phone may be attached).
- Prefer a sensor from a **recent lot** (the "T+…" numbering people flag) to actually reproduce.
- If it fails, the home banner now names it ("Sensor setup failed (code N)" + stage). For the full
  picture pull the underlying status via `adb logcat` (filter `Libre3GattCallback` /
  `SuperGattCallback`), or use a logging build, and look for:
  `onConnectionStateChange ... status:19`, `SEC_CHAR_CHALLENGE_DATA`, `getLibre3kAuth !hasAuth`.
- Watch the `status==19` path specifically (`Libre3GattCallback.java:246-251`, reconnect branch at
  `:842` `if (autoconnect && status != 19)`) — that's the heart of the most-reported failure.
- A fix for #405 must land in `core/.../Libre3GattCallback.java` or the native `libre3` cpp — **not**
  in the Compose layer. If upstream root-causes it, that's the region to pull in.

## Key files

- `app/src/main/java/nl/juggluco/onboarding/Libre3SensorActivator.kt`
- `app/src/main/java/nl/juggluco/data/NativeGlucoseRepository.kt` (`updateSensor`, state listener)
- `app/src/main/java/nl/juggluco/data/SensorInfo.kt` (`SensorError` mapping)
- `app/src/main/java/nl/juggluco/ui/home/HomeScreen.kt` (`ErrorBanner`, `WarmupNote`)
- `core/src/main/java/tk/glucodata/Libre3GattCallback.java` (handshake state machine)
- `core/src/main/java/tk/glucodata/Sensors.java` (`notifyConnection`, `sensorState`)
- `Common/src/main/cpp/libre3/{loadlibs,bluetooth}.cpp` (native crypto/auth)
