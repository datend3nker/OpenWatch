# OpenWatch Privacy Policy

Last updated: November 23, 2026

OpenWatch is a local-first Android app that pairs a Garmin or Wear OS watch, a Xiaomi body composition scale and devices that use Bluetooth's standard health profiles, such as a heart rate strap, with your phone and writes what they record to Health Connect.

This policy applies to the OpenWatch phone app and the OpenWatch Wear OS app, both published under the package name `tech.mmarca.openwatch` by Mittetulundusühing Hale (MTÜ Hale), a non-profit association registered in Estonia, registry code 80677610.

Privacy contact: hello@hale.ngo

## Summary

OpenWatch is designed to keep health data on your device.

- No account is required.
- No health data is uploaded to OpenWatch servers. There are none.
- The phone app has no internet permission. Android itself stops it from using the network.
- No ads are shown.
- No analytics SDKs are included.
- No health data is sold or shared with third parties.
- Data travels only over Bluetooth, between the devices you pair (a watch, a scale, a strap or a meter) and your phone, and inside the phone to apps you connect (see "Apps You Connect On Your Phone").
- OpenWatch writes to Health Connect only what a device you paired recorded, and only after you grant the relevant Android permissions.
- OpenWatch reads from Health Connect only the few things a sync needs, listed below.

## Devices OpenWatch Talks To

OpenWatch uses Bluetooth to reach the devices you add in the app, and nothing else:

- A Garmin watch, over Garmin's own Bluetooth protocol, after you pair it in the app. Android's companion device pairing lets the app notice the watch coming into range so a sync can run on its own.
- A Wear OS watch running the OpenWatch watch app, over Bluetooth. The first time a phone connects, the watch asks you to allow or block it; an allowed phone's token is kept on the watch, and no other phone is answered.
- A heart rate strap that uses Bluetooth's standard Heart Rate profile, after you add it in the app. OpenWatch connects to it only while its screen in the app is open, or while an app you allowed records with it, and reads its heart rate and its battery level. It asks for no pairing and keeps no link otherwise.
- A blood pressure monitor, a glucose meter or a weight scale that uses Bluetooth's standard Blood Pressure, Glucose or Weight Scale profile, after you add it in the app. It is paired with your phone, and Android's companion device pairing tells the app when it wakes after a measurement; OpenWatch then connects, takes the readings it hands over (from a glucose meter, the ones it stored since the last time) and disconnects. When the device names a user, only the readings of the first user it named are kept: on a device several people share, the others' readings are left out.
- A Xiaomi Body Composition Scale S400 or S400 Pro. The scale broadcasts each weigh-in; OpenWatch decodes the broadcast with the key you take from your Xiaomi account and type into the app. The key is stored in the app's private preferences and is used for nothing else.

The app asks for Bluetooth and, on Android 11 and older, the location permission, because Android requires them to scan for and connect to Bluetooth devices. On those older versions, where the phone app holds that permission, a Garmin watch that asks for the phone's position is given the last one Android already has; OpenWatch requests no position fix of its own. It keeps no location history.

## What OpenWatch Writes To Health Connect

What a paired device recorded, when you sync it or when a sync runs on a schedule you chose:

- From a Garmin watch: activities with their route and sensor samples, steps, distance, calories, heart rate, resting heart rate, heart rate variability, oxygen saturation, respiratory rate, VO2 max, basal metabolic rate, sleep, and the weight a Garmin scale relays through the watch.
- From a Wear OS watch: heart rate, steps (with their cadence and an estimate of the calories walking cost), floors and elevation gained recorded on the watch, an estimated sleep session per night, the night's resting heart rate and, where the watch can measure it, heart rate variability, and workouts recorded on the watch with their route, distance, calories, kilometre laps and heart rate figures, and for a walk or run with a route an estimated VO2 max. Unless you switch it off, the breathing rate at night; if you switch them on, on a Samsung watch, blood oxygen and skin temperature at night. Skin temperature is written as the difference from your own usual night. What you log on the watch by hand (water, weight, blood pressure, body fat, blood oxygen, breathing rate, body temperature, blood glucose, height, a breathing session) and what you measure on it on the spot (heart rate, heart rate variability, and on a Samsung watch blood oxygen) are written as entries.
- From a heart rate strap: the heart rate it measured while OpenWatch was connected to it, one record per minute.
- From a blood pressure monitor: each reading's systolic and diastolic pressure, and the pulse it measured as a heart rate. From a glucose meter: each blood glucose reading, with where the sample came from and how it stood to a meal when the meter says so; a check with control solution is not written. From a weight scale on the standard profile: each weight.
- From the scale: weight and heart rate as the scale measured them, and the body fat, lean mass and body water the app estimates from the scale's measurement and your body profile.

Each record names OpenWatch as its source. Records OpenWatch wrote can be deleted from Health Connect's own settings at any time. Records written by other apps are never changed.

## What OpenWatch Reads From Health Connect

OpenWatch is not a dashboard; it shows no history. It reads, with your permission:

- Your weight and height, to estimate body composition from the scale and to let the Wear OS watch app estimate calories.
- Heart rate, resting heart rate, sleep and exercise sessions: to leave alone a day another source already covers (and a night, if you switch off writing the watch's sleep beside other apps), to keep a workout's calories from being counted twice, to work out a workout's energy and its other figures (the time in heart rate zones, the training load, VO2 max, the heart rate recovery), and to send the watch what it needs for its own display, such as your heart rate zones.
- Each day's active calories and distance, from every app, with the last week's nights and resting heart rates: sent to the Wear OS watch after a sync, so the watch app shows them. They are kept on the watch for two weeks and go nowhere else.
- Records older than 30 days, only to find your latest weight and height when they were entered longer ago than that.

Background reading is used only for a scheduled sync.

## Every Health Data Type

The complete list, by Health Connect permission. The app shows the same list under Settings, "What OpenWatch reads and writes", and Health Connect opens it from its own permission page.

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
| `WRITE_BODY_FAT` | What you log on the watch, by hand. |
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

## Watch Features On The Phone

If you switch them on, and only then:

- **Notifications.** With notification access granted, OpenWatch forwards your phone's notifications to a Garmin watch, title and text, except those of the apps you switch off in the app. A notification its app marks secret, or whose channel you set not to show on the lock screen, is never forwarded; one whose channel you set to hide sensitive content on the lock screen goes as its title, or the app's public version, unless you turn that off. The phone's Do Not Disturb is respected. They are held in memory while they are shown and are never written to storage or sent anywhere else.
- **Calendar.** With calendar permission granted, upcoming events are sent to a Garmin watch's calendar view. Nothing is stored.
- **Music.** The title, artist and album of what your phone is playing are sent to the watch for its music controls. Nothing is stored.
- **Navigation.** If you use the CoMaps app, its turn-by-turn guidance can be shown on a Garmin watch. OpenWatch reads the guidance from CoMaps and passes it on; it keeps none of it.
- **Weather.** Weather another app broadcasts on the phone can be passed to the watch. OpenWatch fetches no weather itself.
- **Find my watch, alarms, watch settings, saved locations.** Sent to the watch at your request.

## The Wear OS Watch App

The watch app records with the watch's own sensors and keeps what it records on the watch until the phone collects it, and what you log on it (a glass of water, a weight, a blood pressure, a body fat, a blood oxygen, a breathing rate, a body temperature, a blood glucose, a height, a breathing session) or measure on the spot, for a month. It keeps the figures the phone sends it for your last two weeks (sleep, resting heart rate, active calories, distance), to show them, and the weight, height, birth year and sex of your body profile, which the phone sends for a workout's figures. Like the phone app, it has no internet permission. It asks, on the watch:

- The heart rate sensor (Android's body sensors or heart rate permission, and its background form), for heart rate, the night's heart rate variability and the sleep estimate.
- Physical activity, for steps.
- Location, only while you record a workout with GPS on the watch, for its route.
- On a Samsung watch, and only if you switch the option on: skin temperature and blood oxygen at night; and blood oxygen when you measure it on the spot. For these, and optionally for heart rate, the watch app asks the health service Samsung ships on the watch for the reading. OpenWatch sends nothing to Samsung.
- Bluetooth, to answer the phone you allowed, and notifications, for its own status.

## Local Storage

Health Connect is the source of truth for every record it has a type for. OpenWatch also keeps, in its private storage on your device:

- App preferences: the paired devices, the sync schedule, the notification apps you chose, your body profile, the scale's key, and the acknowledged prompts.
- A small database of the watch measurements Health Connect has no record type for (such as stress, Body Battery and sleep scores from a Garmin watch), shown on the Watch Data screen.
- Sync cursors and file watermarks, so a sync fetches only what is new.
- Readings a device handed over that Health Connect has not taken yet (a permission missing, sync paused), kept until they are written or you remove the device.

The Wear OS watch app keeps a week of sensor readings and a month of recorded workouts in its own private storage on the watch, whether or not the phone has collected them, and deletes them after that.

OpenWatch opts out of Android's cloud backup. When you move to a new phone, Android can copy the app's data across; from version 0.4.1 the scale's key, the Wear OS link tokens and the list of apps you allowed stay behind, so the new phone asks for them again. From the same version the scale's key and the link tokens are kept encrypted, on the phone and on the watch, under a key held by the device's Android Keystore. The watch app keeps all its data on the watch. The phone app's manifest removes inherited Android network permissions, including `INTERNET`.

## Diagnostics

OpenWatch sends no reports by itself. What it logs stays in Android's own log on the device. The release builds of the phone app and the watch app log counts, types and reasons, never a value, a record's time, a name or a device address. Debug builds can also log a reading with its time, for diagnosis; a log reaches no one unless you export it yourself.

## Apps You Connect On Your Phone

Other apps on the same phone can ask to use OpenWatch through OpenLink, an interface that
works only inside the phone. OpenVitals is the first. Nothing travels over a network.

- OpenVitals, signed by the same developer, is connected without asking. Any other app
  waits until you allow it on OpenWatch's Connected apps screen, and you can block or
  forget an app, or switch off any one thing it may do, at any time.
- What a connected app can be allowed: your body profile (birth year, sex, height,
  weight), shared both ways so you enter it once; the list of your watches and scale with
  their battery, last sync and how it ended, and a request to sync one; your daily goals (steps, active
  energy, water), shared both ways and sent to your Wear OS watch for its rings; keeping your
  watch streaming while the app records an activity, or having your Wear OS watch record the
  workout itself, which OpenWatch then writes to Health Connect; and the watch's heart rate and
  steps while it does.
- An app allowed to see your devices also hears when OpenWatch wrote new data to Health
  Connect: which kinds of record (for example heart rate or weight) and the time they cover,
  never a value, so it knows when to read Health Connect again. The kinds alone say what your
  devices measured, which is why only an app you allowed to see your devices hears them.
- Live readings go only to the app that asked for them, while its recording runs, and are
  not stored by OpenWatch. While they flow, OpenWatch shows a notification naming the app. Notifications, calendar events, music, locations and the scale's
  key are never shared.

## Data Sharing

OpenWatch does not sell, rent, transfer, or share your health data with third parties.

OpenWatch does not include advertising SDKs or analytics SDKs.

## Data Retention And Deletion

Health data in Health Connect is controlled by your device and Health Connect settings. Records OpenWatch wrote there stay after you uninstall the app; you can delete them from Health Connect's own settings at any time.

Removing a watch, the scale or another device in the app deletes its local data and forgets its pairing. You can revoke OpenWatch permissions at any time from Android Settings or Health Connect settings. You can delete all local OpenWatch data by clearing the app's storage in Android settings or uninstalling the app.

## Security

OpenWatch relies on Android and Health Connect permission controls to protect access to health data. Its local data sits in app-private storage, which other apps cannot read. The link to a Wear OS watch runs over a paired, encrypted Bluetooth connection and answers only the phone you allowed on the watch.

Because OpenWatch works locally and does not upload health data, there is no OpenWatch cloud account or cloud health-data store.

## Children

OpenWatch is not directed to children and is not designed for child-directed use.

## Changes To This Policy

This privacy policy may be updated when OpenWatch changes how it accesses, uses, stores, or shares data. The "Last updated" date above will be changed when this policy is updated.

## Contact

For privacy questions, contact:

hello@hale.ngo
