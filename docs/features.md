# Features

A quick tour of what Mogluco does.

## Home

<div markdown>
<img src="../img/home.png" width="240" alt="Home screen">
<img src="../img/home-inspect.png" width="240" alt="Tap and hold to inspect a point">
</div>

The home screen is the default view:

- **Current value + trend arrow**, color-coded against your target range, with how long ago it was read and the sensor's day-of-life.
- An **interactive graph**:
    - tap and hold to inspect any point (right, above);
    - switch the x-axis time range;
    - A long no-data stretch breaks the line instead of drawing a straight line (with a configurable threshold).
- The **readings / events list** below the graph, and a **+** button to log an event (Carbs, Insulin, Activity).
- Optional **EMA smoothing** of the trace, with adjustable strength (Settings → Glucose).

## Events

<img src="../img/events.png" width="260" align="right" alt="Log event sheet">

Log the things that move your glucose — **carbs, fast/slow insulin, dextrose, activity, and blood-glucose checks**. Events appear as markers on the graph and in the list. In the settings, you can pick which kinds show.

Tap the **+** on the home screen to open the log sheet: pick the event type, enter the amount, and adjust the time if you are logging it after the fact.

<br clear="all"/>

## Calendar

<img src="../img/calendar.png" width="260" align="right" alt="Calendar screen">

Page back through your history a day at a time:

- A **month grid** with markers for days that have data.
- A **per-day graph** plus that day's readings and events.
- The **sensor timeline** (which sensor was active, its day-of-life).

<br clear="all"/>

## Average day

<img src="../img/averages.png" width="260" align="right" alt="Average-day statistics">

The statistics view answers "what does my typical day look like?":

- A **mean-glucose-per-time-of-day** curve with a **±1 SD band**.
- **Section averages** for each 6-hour block.
- Range selector: **7d / 30d / 90d / 120d / year-to-date**.

<br clear="all"/>

## Alarms, notification & always-on display

<div markdown>
<img src="../img/alarms.png" width="240" alt="Target range & alarms">
<img src="../img/notifications.png" width="240" alt="Notification & always-on-display settings">
</div>

- **Low / high / signal-loss alarms**, with snooze and repeat windows, and an optional **override of Do Not Disturb**.
- A **persistent notification** on the lock screen with the current reading and a mini graph.
- An opt-in **always-on-display** graph overlay, with an adjustable vertical position.

## Wear OS companion

<div markdown>
<img src="../img/watch.png" width="200" alt="Watch — value & trend">
<img src="../img/watch-graph.png" width="200" alt="Watch — glanceable graph">
<img src="../img/watch-alarms.png" width="200" alt="Watch — on-watch alarm settings">
</div>

The WearOS app is a proper companion, not a mirror-only:

- Live **value + trend** and a **glanceable graph** (tap to cycle the window).
- A **tile** and **watch-face complications**.
- **On-watch alarms** that ring on the wrist whether or not the phone is reachable.
- **Sensor hand-off**: move the sensor connection between phone and watch; missed readings are backfilled from the sensor's memory on reclaim.

<br clear="all"/>

## Settings & export

<div markdown>
<img src="../img/settings.png" width="240" alt="Settings screen">
<img src="../img/export.png" width="240" alt="Data & export options">
</div>

Everything is configured from a clean settings screen:

- **Sensor & calibration**, target range & alarms, unit, smoothing.
- **Notifications** and always-on display.
- **WearOS** sync and sensor transfer.
- **Data**: export to **Health Connect** (opt-in) and **LibreView**, plus **CSV / TSV** export and full **backup / restore**.

<br clear="all"/>

!!! info "Private by default"
    Nothing is uploaded unless you explicitly turn on an export.
    Health Connect asks for permission only when you enable it, and LibreView upload needs the account ID you set on the sensor screen.
