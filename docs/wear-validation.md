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
