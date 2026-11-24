# Permissions

OpenWatch asks for permissions by purpose. The app removes inherited network permissions
during manifest merge and ships no `INTERNET`, network-state or Wi-Fi-state permission.

This page mirrors `app/src/main/AndroidManifest.xml` and, for the watch app, `wear/src/main/AndroidManifest.xml`.

## Health Connect Write Permissions

What a watch sync writes: `WRITE_STEPS`, `WRITE_DISTANCE`, `WRITE_EXERCISE`,
`WRITE_EXERCISE_ROUTE`, `WRITE_ELEVATION_GAINED`, `WRITE_ACTIVE_CALORIES_BURNED`,
`WRITE_TOTAL_CALORIES_BURNED`, `WRITE_HEART_RATE`, `WRITE_RESTING_HEART_RATE`,
`WRITE_HEART_RATE_VARIABILITY`, `WRITE_BASAL_METABOLIC_RATE`, `WRITE_FLOORS_CLIMBED`,
`WRITE_SPEED`, `WRITE_POWER`,
`WRITE_OXYGEN_SATURATION`, `WRITE_RESPIRATORY_RATE`, `WRITE_SKIN_TEMPERATURE`,
`WRITE_VO2_MAX`, `WRITE_SLEEP`. Steps cadence falls under `WRITE_STEPS` and cycling cadence under `WRITE_EXERCISE`:
Health Connect has no separate cadence permission.

Three of these are written only for a watch that measures them and only while their option is
on: `WRITE_RESPIRATORY_RATE` from a Wear OS watch (the "Breathing rate from the watch" switch in
Settings, on by default), and `WRITE_OXYGEN_SATURATION` and `WRITE_SKIN_TEMPERATURE` from a Samsung
watch (the "SpO2 hourly at night" and "Skin temperature at night" switches on the watch's
settings card, off until the user switches them on).
Skin temperature is one record per night, the difference from the wearer's own usual night.
A Garmin watch's oxygen saturation and respiratory rate come with its files.

What the entries made on a Wear OS watch write, each when the phone pulls it: `WRITE_HYDRATION`,
`WRITE_WEIGHT`, `WRITE_BLOOD_PRESSURE`, `WRITE_BODY_FAT`, `WRITE_OXYGEN_SATURATION`,
`WRITE_RESPIRATORY_RATE`, `WRITE_BODY_TEMPERATURE`, `WRITE_BLOOD_GLUCOSE` and `WRITE_HEIGHT` (a value logged in
the watch's quick log: a hand-logged blood oxygen or breathing rate is written whatever the night
switches say), `WRITE_MINDFULNESS` (a breathing session, only where Health Connect
stores mindfulness sessions), and `WRITE_HEART_RATE`, `WRITE_HEART_RATE_VARIABILITY` and, on a
Samsung watch, `WRITE_OXYGEN_SATURATION` and `WRITE_BODY_FAT`, `WRITE_LEAN_BODY_MASS` and
`WRITE_BODY_WATER_MASS` (a spot measurement, the last three from a body composition). An entry whose write is not granted
waits on the watch, and the watch's card asks for the grant; the entries whose write is granted go on.

A workout recorded on a Wear OS watch also writes `WRITE_VO2_MAX`: an estimate for a walk or run
with a route, from the heart rate the watch recorded and the body profile's resting heart rate,
left out without the grant while the session is written.

What a scale weigh-in writes: `WRITE_WEIGHT`, `WRITE_BODY_FAT`, `WRITE_LEAN_BODY_MASS`,
`WRITE_BODY_WATER_MASS`.

What a heart rate strap on Bluetooth's standard Heart Rate profile writes: `WRITE_HEART_RATE`, one
record per minute of what it streamed while its screen was open or an app you allowed recorded
with it. What a device on the standard Blood Pressure, Glucose or Weight Scale profile hands over
when it wakes: `WRITE_BLOOD_PRESSURE` (with the pulse as `WRITE_HEART_RATE`),
`WRITE_BLOOD_GLUCOSE` and `WRITE_WEIGHT`. What a device of the standard profiles hands over waits on the phone until its grant is
in, and its device screen asks for it.

A record whose write is not granted stays on the device; the rest of a sync goes on.

## Health Connect Read Permissions

What the estimates read: `READ_WEIGHT` and `READ_HEIGHT` (the walking energy and the body
composition), `READ_EXERCISE` (a minute inside a workout gets no walking-energy estimate),
`READ_SLEEP` (a night that already holds a sleep session is left alone),
`READ_RESTING_HEART_RATE` (a day that already holds a resting heart rate is left alone, and
the heart rate zones the watch shows), `READ_HEART_RATE` (the highest heart rate, for those
zones, and a workout's energy). `READ_HEALTH_DATA_HISTORY`: the latest weight and height are
often older than the 30 days Health Connect shows without it.

What a Wear OS watch shows of the phone's: after each sync the phone hands it the last week's
nights (`READ_SLEEP`), resting heart rates (`READ_RESTING_HEART_RATE`), and each day's active
calories and distance from every app (`READ_ACTIVE_CALORIES_BURNED`, `READ_DISTANCE`). A figure
whose read is not granted stays off the watch, and a scheduled sync that cannot see other apps'
data sends none.

`READ_HEALTH_DATA_IN_BACKGROUND`: a scheduled sync with the app closed can only see other
apps' weights, workouts and sleep with this grant. Without it the sync still writes the
samples, but no estimate that depends on another app's data.

## Every Health Permission, By Source

The list the app itself shows (Settings, "What OpenWatch reads and writes"). It is
`healthconnect/HealthDataCatalog`; a manifest that declares a health permission missing from
it fails `HealthPermissionsTest`.

<!-- health-data:begin (generated from HealthDataCatalog; HealthPermissionsTest checks it) -->

**Written from a Garmin watch**

| Permission | When, or what for |
| --- | --- |
| `WRITE_EXERCISE` |  |
| `WRITE_EXERCISE_ROUTE` |  |
| `WRITE_DISTANCE` |  |
| `WRITE_SPEED` |  |
| `WRITE_POWER` |  |
| `WRITE_ELEVATION_GAINED` |  |
| `WRITE_FLOORS_CLIMBED` |  |
| `WRITE_STEPS` |  |
| `WRITE_ACTIVE_CALORIES_BURNED` |  |
| `WRITE_TOTAL_CALORIES_BURNED` |  |
| `WRITE_BASAL_METABOLIC_RATE` |  |
| `WRITE_HEART_RATE` |  |
| `WRITE_RESTING_HEART_RATE` |  |
| `WRITE_HEART_RATE_VARIABILITY` |  |
| `WRITE_OXYGEN_SATURATION` |  |
| `WRITE_RESPIRATORY_RATE` |  |
| `WRITE_VO2_MAX` |  |
| `WRITE_SLEEP` |  |
| `WRITE_WEIGHT` | A weight a Garmin scale relays through the watch. |

**Written from a Wear OS watch**

| Permission | When, or what for |
| --- | --- |
| `WRITE_HEART_RATE` |  |
| `WRITE_STEPS` |  |
| `WRITE_ACTIVE_CALORIES_BURNED` | An estimate of what walking cost, from your steps and your weight, and a workout's calories. |
| `WRITE_FLOORS_CLIMBED` |  |
| `WRITE_ELEVATION_GAINED` |  |
| `WRITE_SLEEP` | One estimated session per night and per nap. |
| `WRITE_RESTING_HEART_RATE` |  |
| `WRITE_HEART_RATE_VARIABILITY` | At night, on a watch that can measure it. |
| `WRITE_EXERCISE` | A workout you record on the watch, with its route and distance. |
| `WRITE_EXERCISE_ROUTE` |  |
| `WRITE_DISTANCE` |  |
| `WRITE_RESPIRATORY_RATE` | At night only with “Breathing rate from the watch” switched on in Settings; and a breathing rate you log on the watch. |
| `WRITE_OXYGEN_SATURATION` | At night only from a Samsung watch, with “SpO2 hourly at night” switched on for that watch; and a blood oxygen you log or measure on the watch. |
| `WRITE_SKIN_TEMPERATURE` | Only from a Samsung watch, with “Skin temperature at night” switched on for that watch. One record per night: the difference from your own usual night. |
| `WRITE_HYDRATION` | What you log on the watch, by hand. |
| `WRITE_WEIGHT` | What you log on the watch, by hand. |
| `WRITE_BLOOD_PRESSURE` | What you log on the watch, by hand. |
| `WRITE_MINDFULNESS` | A breathing session you do on the watch, where Health Connect keeps them. |
| `WRITE_BODY_FAT` | What you log on the watch, by hand, and a body composition you measure on a Samsung watch. |
| `WRITE_LEAN_BODY_MASS` | A body composition you measure on a Samsung watch. |
| `WRITE_BODY_WATER_MASS` | A body composition you measure on a Samsung watch. |
| `WRITE_BODY_TEMPERATURE` | What you log on the watch, by hand. |
| `WRITE_BLOOD_GLUCOSE` | What you log on the watch, by hand. |
| `WRITE_HEIGHT` | What you log on the watch, by hand. |
| `WRITE_VO2_MAX` | An estimate from a walk or run you record on the watch with its route, when your body profile holds a resting heart rate. |

**Written from the scale**

| Permission | When, or what for |
| --- | --- |
| `WRITE_WEIGHT` |  |
| `WRITE_HEART_RATE` |  |
| `WRITE_BODY_FAT` | Estimated on the phone from the scale's measurement and your body profile. |
| `WRITE_LEAN_BODY_MASS` | Estimated on the phone from the scale's measurement and your body profile. |
| `WRITE_BODY_WATER_MASS` | Estimated on the phone from the scale's measurement and your body profile. |

**Written from a device on a standard Bluetooth profile**

| Permission | When, or what for |
| --- | --- |
| `WRITE_HEART_RATE` | From a heart rate strap, while its screen is open or an app you allowed records with it: one record per minute. |
| `WRITE_BLOOD_PRESSURE` | From a blood pressure monitor, each reading it hands over when it wakes, with the pulse it measured written as a heart rate. |
| `WRITE_BLOOD_GLUCOSE` | From a glucose meter, the readings it stored, handed over when it wakes. A check with control solution is not written. |
| `WRITE_WEIGHT` | From a scale on the standard Weight Scale profile, each weigh-in it hands over when it wakes. |

**Read from Health Connect**

| Permission | When, or what for |
| --- | --- |
| `READ_WEIGHT` | For the walking calories and the scale's body composition. |
| `READ_HEIGHT` | For the scale's body composition. |
| `READ_EXERCISE` | So a minute inside a workout gets no second calorie estimate. |
| `READ_SLEEP` | So a night that already holds a sleep session is left alone. |
| `READ_RESTING_HEART_RATE` | So a day that already holds a resting heart rate is left alone, and for the heart rate zones the watch shows. |
| `READ_HEART_RATE` | Your highest heart rate, for those zones, and a workout's energy. |
| `READ_ACTIVE_CALORIES_BURNED` | The day's total from every app, which the Wear OS watch shows. |
| `READ_DISTANCE` | The day's total from every app, which the Wear OS watch shows. |
| `READ_HEALTH_DATA_HISTORY` | Your latest weight and height are often older than the 30 days Health Connect shows without it. |
| `READ_HEALTH_DATA_IN_BACKGROUND` | A sync that runs on a schedule, with the app closed, can only see another app's weights and workouts with it. |

**Asked by the watch app, on the watch**

| Permission | When, or what for |
| --- | --- |
| `READ_HEART_RATE` | The watch's heart rate sensor: heart rate, the night's heart rate variability and the sleep estimate. |
| `READ_HEALTH_DATA_IN_BACKGROUND` | To keep recording with the watch's screen off. |
| `READ_SKIN_TEMPERATURE` | Only on a Samsung watch, and only when you switch on skin temperature at night. |
| `READ_OXYGEN_SATURATION` | Only on a Samsung watch: when you switch on SpO2 at night, and when you measure your blood oxygen on the watch. |

<!-- health-data:end -->

## The Watch App's Permissions

The Wear OS app records with the watch's own sensors, so its permissions are the watch's,
asked on the wrist:

- `android.permission.health.READ_HEART_RATE` on Wear OS 6 (Android 16), `BODY_SENSORS`
  before it, with `READ_HEALTH_DATA_IN_BACKGROUND` or `BODY_SENSORS_BACKGROUND`: the heart
  rate sensor, also behind the night's HRV and the sleep estimate.
- `ACTIVITY_RECOGNITION`: the step counter.
- `android.permission.health.READ_SKIN_TEMPERATURE` and `READ_OXYGEN_SATURATION`: asked only
  when the user switches on "Skin temperature at night" or "SpO2 hourly at night" on a watch that has the
  Samsung Health Platform, which checks them per tracker, and `READ_OXYGEN_SATURATION` when the
  user starts a blood oxygen spot measurement there.
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` and `FOREGROUND_SERVICE_LOCATION`: a
  workout recorded with GPS on the watch, and only while it runs.
- `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
  `FOREGROUND_SERVICE_HEALTH`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`,
  `VIBRATE`: the link to the phone and the recording service.

The watch app removes the same three network permissions as the phone app.

## Bluetooth Permissions

`BLUETOOTH_SCAN` (never for location) and `BLUETOOTH_CONNECT` on Android 12 and later;
`BLUETOOTH`, `BLUETOOTH_ADMIN` and `ACCESS_FINE_LOCATION` on Android 11 and older, where a
scan needs them.

## Companion Device Permissions

`REQUEST_COMPANION_RUN_IN_BACKGROUND`, `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`
and `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`: install-time grants with no prompt of their
own. The user's consent is the system "Allow OpenWatch to access <device>?" dialog. The
association keeps the app alive while a watch is in range, so a file sync that runs for
minutes is not killed; for the scale, it is what lets Android wake the app for the two seconds
a weigh-in is on the air, and for a blood pressure monitor, a glucose meter or a standard
scale, it is how Android tells the app the device woke with a reading, so such a device is added
only with it. For a Garmin watch, declining is supported: the watch is bonded and
syncs regardless. A Wear OS watch is added only with the association, because Android lets an
app start a foreground service from the background only for an associated device. OpenWatch
starts none for a watch today; the association keeps that open for a workout another app starts
on the watch. A Wear OS watch added before this rule, or whose
association the user removed in Android's settings, shows a "Link watch" button on its device
screen.

## Notification Access

`OpenWatchNotificationListenerService` reads the phone's notifications so they can be shown
on a paired Garmin watch. The user grants it from a system settings screen, after a disclosure
in the app. What it reads is held in memory and sent to the watch only.

## Calendar

`READ_CALENDAR`, for the Garmin calendar glance: read only while answering a watch that asked,
behind a per-watch toggle that is off by default.

## Foreground Service Permissions

`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`, for the scale's 45-second
listening service. `POST_NOTIFICATIONS` goes with it. `WAKE_LOCK` keeps the phone awake for a
watch sync no scheduled job holds awake (one you tap, one another app asks for over OpenLink, one
the watch starts by announcing a recording), never for more than 30 minutes.

## Overlay Protection

`HIDE_OVERLAY_WINDOWS`, a normal permission with no prompt. While the Connected apps screen
shows, OpenWatch hides other apps' overlays (API 31 and newer) and drops touches that pass
through another app's window, so no app can draw over Allow or a capability switch.

## Removed Network Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` and the `android.hardware.wifi`
feature are removed with `tools:node="remove"`, so no dependency can bring them back.
