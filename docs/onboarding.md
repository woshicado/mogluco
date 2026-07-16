# Onboarding

This walks through getting your first reading on the phone, then adding the watch.

## 1. First launch & permissions

On first run, allow the permissions Mogluco asks for:

- **Notifications**: the persistent reading and the alarms need it.
- **Nearby devices / Bluetooth**: to receive readings from the sensor.
- **Battery: Unrestricted / allow background activity**: so readings and alarms keep working when the app isn't open. Keep the ongoing notification visible.

## 2. Set your unit and target range

Open **Settings → Glucose**:

- Pick your **unit** (mg/dL or mmol/L).
- Open **Target range & alarms** to set your in-range band (it shades the graph and drives the low/high alarms).

## 3. Add your sensor

<img src="../img/sensor.png" width="260" align="right" alt="Sensor & calibration screen">

Open **Settings → Sensor & calibration**.

To **take over an already-activated** Libre 3 / 3+ sensor, Mogluco needs the **LibreView account ID** that was used to activate it:

- Enter the account ID directly, **or** enter your LibreView e-mail + password and tap **Fetch from LibreView** to retrieve it.

To **activate a brand-new** sensor with Mogluco, any number works as the account ID. However, note that if you later want to scan it with a different app, or sync it to LibreView, it is required to use the same account ID.

Then start the sensor:

- Tap **Activate**, and hold the top of your phone to the sensor when prompted (NFC).
- The **first reading takes ~2–10 minutes** to arrive over Bluetooth. During this warm-up the card shows the sensor is connecting.
- The libre 3(+) sensors have a **warm-up phase of 60 minutes**. Mogluco will only start displaying readings once that warm-up phase has passed when activating a sensor for the first time.

<br clear="all"/>

!!! tip "Avoid interference"
    Before scanning, force-stop and turn off/uninstall any other app or reader that was previously connected to the same sensor (e.g., LibreLink or Juggluco).

    Two apps fighting over one sensor can cause dropouts.

## 4. Add a Smartwatch (optional)

<img src="../img/wear-phone.png" width="260" align="right" alt="Wear OS watch settings on the phone">

1. Make sure the **Wear OS companion app is installed** on the watch (see [Installation](installation.md)) and the watch is paired to the phone.
2. On the phone, open **Settings → Wear OS watch**. The watch mirrors the phone automatically once both apps are running. You will see it appear in the sync status.
3. To let the **watch connect to the sensor directly** (so it keeps updating when the phone is away), use **Send sensor to watch**. You can reclaim it later from the same screen; missed readings are backfilled from the sensor's memory on reclaim.
4. On the watch, open **Settings → Background** and set **Battery → Unrestricted** so the watch keeps syncing without the app open.

<br clear="all"/>

Next: **[Features](features.md)**.
