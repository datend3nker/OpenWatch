# Permissions

OpenWatch asks for permissions by purpose. The app removes inherited network permissions
during manifest merge and ships no `INTERNET`, network-state or Wi-Fi-state permission.

This page mirrors `app/src/main/AndroidManifest.xml`.

## Health Connect Write Permissions

What a watch sync writes: `WRITE_STEPS`, `WRITE_DISTANCE`, `WRITE_EXERCISE`,
`WRITE_EXERCISE_ROUTE`, `WRITE_ELEVATION_GAINED`, `WRITE_ACTIVE_CALORIES_BURNED`,
`WRITE_TOTAL_CALORIES_BURNED`, `WRITE_HEART_RATE`, `WRITE_RESTING_HEART_RATE`,
`WRITE_HEART_RATE_VARIABILITY`, `WRITE_BASAL_METABOLIC_RATE`, `WRITE_FLOORS_CLIMBED`,
`WRITE_WHEELCHAIR_PUSHES`, `WRITE_SPEED`, `WRITE_POWER`, `WRITE_STEPS_CADENCE`,
`WRITE_CYCLING_PEDALING_CADENCE`, `WRITE_OXYGEN_SATURATION`, `WRITE_RESPIRATORY_RATE`,
`WRITE_VO2_MAX`, `WRITE_SLEEP`.

What the entries made on the watch write: `WRITE_HYDRATION`, `WRITE_WEIGHT`,
`WRITE_BLOOD_PRESSURE`, `WRITE_MINDFULNESS` (a breathing session; only where Health Connect
stores mindfulness), `WRITE_HEART_RATE` and `WRITE_HEART_RATE_VARIABILITY` (a spot measurement).

What a scale weigh-in writes: `WRITE_WEIGHT`, `WRITE_BODY_FAT`, `WRITE_LEAN_BODY_MASS`,
`WRITE_BODY_WATER_MASS`.

A record whose write is not granted stays on the device; the rest of a sync goes on.

## Health Connect Read Permissions

What the estimates read: `READ_WEIGHT` and `READ_HEIGHT` (the walking energy and the body
composition), `READ_EXERCISE` (a minute inside a workout gets no walking-energy estimate),
`READ_SLEEP` (a night that already holds a sleep session is left alone),
`READ_RESTING_HEART_RATE`, `READ_HEART_RATE` and `READ_STEPS` (the sync cursors and the
nightly resting heart rate), `READ_HEALTH_DATA_HISTORY`. `READ_SLEEP`, `READ_RESTING_HEART_RATE`, `READ_ACTIVE_CALORIES_BURNED`
and `READ_DISTANCE` also give the watch the last week's nights, resting heart rate, active energy
and distance for its own screens; without a grant that figure stays off the watch.

`READ_HEALTH_DATA_IN_BACKGROUND`: a scheduled sync with the app closed can only see other
apps' weights, workouts and sleep with this grant. Without it the sync still writes the
samples, but no estimate that depends on another app's data.

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
a weigh-in is on the air. Declining is supported: the watch is bonded and syncs regardless.

## Notification Access

`OpenWatchNotificationListenerService` reads the phone's notifications so they can be shown
on a paired Garmin watch. The user grants it from a system settings screen, after a disclosure
in the app. What it reads is held in memory and sent to the watch only.

## Calendar

`READ_CALENDAR`, for the Garmin calendar glance: read only while answering a watch that asked,
behind a per-watch toggle that is off by default.

## Foreground Service Permissions

`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`, for the scale's 45-second
listening service. `WAKE_LOCK` and `POST_NOTIFICATIONS` go with it.

## Removed Network Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` and the `android.hardware.wifi`
feature are removed with `tools:node="remove"`, so no dependency can bring them back.
