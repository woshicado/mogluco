# WearOS Phase 5 — real-hardware validation (phone + Pixel Watch 3 + live Libre 3)

Everything below needs physical hardware; the code paths were verified on emulators as far
as an unpaired device allows. Run in order — later steps assume earlier ones passed.

## Setup

1. **Watch debugging**: on the PW3 enable Developer options → Wireless debugging, then
   `adb pair <ip:pairport>` + `adb connect <ip:port>`. Always target devices explicitly:
   `adb -s <serial> …` (the phone and watch are both attached).
2. **Confirm arm64**: `adb -s <watch> shell getprop ro.product.cpu.abilist` → must contain
   `arm64-v8a` (Libre 3 blobs are arm64-only). PW3 is expected to pass.
3. **Build + install** (same debug signing on both — machine-built, so keys match):
   - `./gradlew :app:assembleDebug :wear:assembleDebug`
   - `adb -s <phone> install -r app/build/outputs/apk/debug/app-debug.apk`
   - `adb -s <watch> install -r wear/build/outputs/apk/debug/wear-debug.apk`
   - Phone and watch must already be paired via the normal Wear OS companion.
4. **Important**: force-stop / disable Bluetooth in the LEGACY Juggluco (tk.glucodata) first
   if it's installed — two apps must never hold the sensor at once. The debug pair
   (`tk.glucodata.next.debug`) is Data-Layer-isolated from legacy, but BLE is not.

## A. Mirror (phone owns the sensor)

1. Open the watch app → Settings → "Phone sync" should show the phone's node name.
2. Phone → Settings → Watch → "Init watch app". First sync moves the full history; putting
   the watch on the charger helps (WiFi turns on).
3. PASS: watch home shows the live value within ~2 min; new phone readings appear on the
   watch each minute; the tile + complications + chip update; watch graph shows history.
4. Reboot the watch → values resume without manual steps (BootReceiver + mirror reconnect).

## B. Complications + tile on real slots

1. Add "Glucose value", "Glucose trend", "Glucose + trend" to watch-face slots; add the tile.
2. PASS: correct rendering + tap opens the app. After 5.5 min without data (airplane-mode
   the phone) they show `--`.

## C. On-watch alarms

1. Watch Settings → "Test alarm": rings + vibrates; check Dismiss/Snooze from the
   notification; check whether the full-screen alarm surface appears (if not, grant:
   `adb -s <watch> shell appops set tk.glucodata.next.debug USE_FULL_SCREEN_INTENT allow`
   and note the result — decides whether we add a grant deep-link in settings).
2. With the watch on DND: no ring with "Ring through DND" off; rings with it on
   (grant notification-policy access if prompted in logs:
   `adb -s <watch> shell cmd notification allow_dnd tk.glucodata.next.debug` on API 34).
3. Set a tight target range on the WATCH settings so the live value is out of range →
   low/high alarm fires on the watch with the phone in airplane mode.

## D. Sensor transfer (direct sensor→watch BLE)

1. Phone → Settings → Watch → "Send sensor to watch". State goes "Waiting for the watch…".
2. PASS: within ~1–6 min the state flips to "Watch has the sensor"; watch keeps getting
   readings with the phone's BLE off; phone keeps showing values (mirror-returned).
   Wear the watch on the same arm as the sensor for link quality.
3. **Failed-transfer revert**: turn the watch OFF, then "Send sensor to watch" → after the
   6-min timeout the phone must show "Transfer failed — sensor stayed on the phone" and its
   readings must continue (BLE re-enabled automatically).
4. **Take-back**: "Take sensor back" on the phone → phone reconnects (readings continue);
   watch returns to mirror mode. Also test the watch's "Hand sensor back" button.
5. **Backfill**: while the watch owns the sensor, put the phone in airplane mode 30+ min →
   reconnect → the phone chart must fill the gap (mirror lastpos backfill).

## E. Auto-reclaim (watch dies)

1. Enable "Auto-reclaim sensor" (10 min window), transfer the sensor to the watch.
2. Power the watch OFF. PASS: after ~10–11 min the phone posts "Sensor reclaimed from
   watch" and its own readings resume (Libre 3 backfills the BLE gap on reconnect).
3. Power the watch ON. PASS: the watch does NOT steal the connection back — the phone
   orders it off as soon as it reappears; watch resumes mirror display.

## F. Release build

- `minifyEnabled` is off, so no R8 keep rules are needed yet. If minification is ever
  enabled, keep: `tk.glucodata.MessageSender`, `tk.glucodata.Applic`, `tk.glucodata.Natives`,
  all complication/tile/alarm services, and the JNI-bound classes listed in
  `core/.../JniLoadStubs.java`.
- Repeat A + D once with `assembleRelease` on both (same keystore).

## Debug logging

The sync layer logs to the engine's native log, not logcat:
`adb -s <dev> shell "run-as tk.glucodata.next.debug sh -c 'tail -f files/logs/trace.log'"`
— grep for `MessageSender`, `MessageReceiver`, `WatchLink`, `netinfo`.

## G. After the October 2026 review pass

These changes could only be reasoned through, not run. Check them with the phone, the watch and
a live sensor; `trace.log` on both devices shows the lines named below.

1. **Normal use first.** Phone owns the sensor: readings every minute on phone and watch, graph
   complete after force-stopping and reopening the phone app (no hole of ~20 min at the right
   edge, also with a sensor older than 6 days), no "re-asserting" lines in the phone's trace.
2. **Transfer and back** (D.1/D.2 as before): zero "re-asserting" lines; "Watch has the sensor"
   only once the watch streams.
3. **Take-back while the watch is away.** Take the sensor back with the watch out of range (or
   let auto-reclaim do it), then bring the watch back. Expect: the watch ends at
   `usebluetooth=false`, the phone keeps streaming to it, at most a few "re-asserting" lines, and
   no `watchdog: no BLE manager…` on the watch. With the watch in flight mode during the return:
   "not delivered — keeping the marker", then delivery on the next node event.
4. **Watchdog.** With the watch owning a sensor in warm-up: no reconnect kicks for the hour. With
   the sensor out of range: kicks at 5, 5, then every 10 min; history gaps fill on the next
   connection.
5. **Activation.** A new sensor ends on the warm-up card with ~60 min and connects as before; a
   rescan of the running sensor ends on Done; activating while the watch owns the previous sensor
   ends on Done with the phone taking the role.
6. **Sensor lifetime.** The sensor card and the calendar show the same "/ N" for the current
   sensor (logcat at activation: `wearduration=`); a Libre 3 Plus must read 15.
7. **Signal loss.** Sensor out of range with the phone idle and unplugged: the alarm comes ~16 min
   after the last reading (a few minutes later without the battery exemption), on the watch too.
   After "Deactivate sensor": no signal-loss alarm, also not after a restart.
8. **Stale values on the watch face.** Stop readings and leave the watch idle for 6+ min: each
   complication shows "--" (or an empty slot) and the tile a grey "--" with a grey dot.
9. **Two sensors.** Activate a new sensor while the old one still transmits: after the new one's
   warm-up the value follows the new sensor only.
10. **Restart during an alarm.** While a Low rings (repeat Off, "until dismissed"): reboot the
    phone, and separately the watch — each rings again on the first low reading after start. Kill
    only the app's process instead: exactly one ring comes back, with its own text.
11. **Sensor card from start.** Force-stop and reopen the phone app with Bluetooth off: Home shows
    the sensor ("Connecting…"), Settings offers Deactivate, and after five minutes the neutral
    "Waiting for the sensor" note — not the red "No data after warm-up" banner.
12. **Complications on a steady value.** With glucose flat for 10+ minutes the complications keep
    showing the value (no "--" in between).
13. **Replacement sensor that never starts.** Deactivate, activate a new sensor and keep it out
    of reach: no alarm during warm-up, "Signal lost" one window after warm-up end, on phone and
    watch.
14. **Pull history out of reach.** With the sensor out of range, "Pull history from sensor" says
    "No sensor connected" at once and nothing happens later. And within a minute of a reconnect:
    tap it and expect a result message (the automatic gap-fill must not swallow it).
15. **Time to first reading after a transfer** (for the confirmation question in the changelog):
    measure it over ~10 transfers, including a cold watch.
16. **Alarm right behind a Stop.** Let "Signal lost" ring, then bring the sensor back with a value
    out of range (the Stop of the old alarm and the new Low/High follow each other at once): the
    new alarm rings and stays, the app keeps running (logcat: no "did not then call
    Service.startForeground"), on phone and watch. On a watch that is NOT exempt from battery
    optimisation and has its screen off, the alarm may come as a notification only — it must
    still be there after the Stop, and Dismiss on it must end it.
17. **Two sensors in the history.** With the old sensor still transmitting next to the new one
    (no "Deactivate"): force-stop and reopen the phone app, and restart the watch app — graph and
    lists show one reading per minute from the new sensor's first reading on, and the watch face
    shows the new sensor's value before the first live reading arrives.
18. **Signal loss on a watch that is not battery-exempt.** Stop the readings and leave the watch
    idle with a complication on the face: "Signal lost" comes within about two windows (it used
    to be put off by every restart of the app in the background).
