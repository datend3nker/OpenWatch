# Watches

> **Status:** Current implemented behavior. Experimental.
> **Audience:** Users and contributors.
> **Implementation:** `devices/core`, `devices/wearos`, `features/watches`.
> **Navigation:** `Screen.Home`, `Screen.WatchDevice`.
> **Related:** [Permissions](../app/permissions.md), [Install the watch app on a Wear OS watch](../how-to/wear-os-sideload.md).
>
> This page came over from OpenVitals with the code. Where it names a dashboard, a widget, Body Energy or another OpenVitals screen, that part stays in OpenVitals: OpenWatch writes the records to Health Connect and OpenVitals shows them.

OpenWatch has experimental support for Wear OS watches. The home screen pairs a watch and copies what it recorded onto the phone over Bluetooth. There is no watch-vendor account and no network step; the app declares no internet permission.

A Wear OS watch runs the OpenWatch Wear OS companion app (`:wear`). The phone app checks whether the watch is paired and whether the watch app answers, and syncs the heart rate, steps and floors the watch app recorded into Health Connect, plus a sleep session per night estimated from the movement and heart rate the watch app recorded. No vendor app is needed on the phone once the watch is paired.

Garmin watches, which OpenVitals also reads, are not supported.

## Wear OS Companion App

OpenWatch includes a Wear OS companion app (`:wear`) that runs directly on wrist devices.

The companion app communicates with the phone app over standard Android Bluetooth RFCOMM sockets using a dedicated service UUID (`4838d728-6e5a-4b95-a29d-a60032338301`). This design uses standard Android OS (AOSP) APIs only, maintaining 100% open-source compatibility suitable for F-Droid without depending on closed-source Google Play Services libraries.

The watch app runs `WearAppService`, a `connectedDevice` foreground service with an ongoing "Phone link" notification, so it stays reachable after the app is closed. It starts once the watch app has the Nearby devices permission (`BLUETOOTH_CONNECT`, asked on first launch), reopens its listener when Bluetooth comes back on, and answers a `PING` line with `PONG`. `WearBootReceiver` starts it again after a watch restart or an app update, as long as the permission is granted.

The watch app also offers three tiles, added from the watch's tile list: **Heart rate**, **Steps** and **Floors**. Each shows today's value in the metric's colour from the phone app and the day's curve from midnight to midnight, drawn up to now, over the hours 0, 6, 12, 18 and 24: the heart rate as quarter-hour means, broken where the watch stored none (off the wrist or charging), the steps and floors as a running total. The steps are the ones the watch counted itself, so they can fall short of the phone app's total, which adds the phone's own steps and other apps' from Health Connect. The heart rate is the newest sample with its age; one older than an hour shows as a dash. Under its curve is the day's lowest and highest sample. The floors are counted as the phone counts them before writing them to Health Connect: only the height that enough steps carry (`AscentCredit`), three metres a floor, both rounded down; under the curve are the metres that counted. A value whose permission is off, or whose sensor the watch lacks, shows as a dash with the reason. The tiles only read the watch's metric store and start no sensor; a tile counts as stale five minutes after it was drawn, so Wear OS asks for a new one. Tapping a tile opens the watch app. Verified on a TicWatch Pro 5 with Wear OS 4.

When opening a paired Wear OS watch on its device screen from the home screen:
- **Paired**: OpenWatch looks for the watch among the phone's bonded devices. The address stored at onboarding comes from the BLE scan, and Wear OS watches advertise with a private address, so the match falls back to the name (a trailing ` LE` ignored). There is no guess beyond that: another bonded watch is never reported as this one.
- **Found when adding**: the Watches scan offers a bonded device as a Wear OS watch when its name is a known smartwatch family or its service list names the OpenWatch Wear OS app. A wrist-watch Bluetooth class alone is not enough, since Garmin, Fitbit and Huawei watches report it too. The service list Android caches at pairing predates the app, so the scan re-queries it (SDP) for bonded dual-mode devices that nothing has claimed yet and that are not audio, peripheral or imaging devices. A watch with an unknown name is therefore found once the OpenWatch Wear OS app runs on it.
- **App answers**: the phone pings the watch over RFCOMM, giving up after 8 seconds. No answer means the watch is off or out of range, or the watch app is not running; a bond alone is never shown as connected.
- **Permission**: without Nearby devices on the phone, the card shows a grant callout instead of a status.
- **Check again**: the "Validate Wear OS App" button re-runs the check.

The UUID and the protocol words come from the shared `:wearlink` module on both sides.

### Watch Sync

The watch app records heart rate on its own, from the moment it is granted heart rate access, with the screen off and the app closed: the same foreground service that answers the phone keeps the sensor on, batched so the watch wakes once every few minutes rather than once per beat. It keeps one sample every ten seconds and a week of history, pruning older samples itself.

It counts steps the same way, from the watch's own step counter, once it is granted physical activity access: one count per clock minute, a week of history. Steps taken while the watch app was not running, for example after a restart of the watch, are spread evenly over the minutes in between. The watch's screen shows whether step counting is on and offers the grant if it is not. A watch without a step counter simply records none.

While the watch is off the wrist for a minute, it stops measuring heart rate, which is what costs the most battery, and starts again as soon as it is back on. Turning the screen on or taking the watch off the charger also restarts it, in case the watch cannot tell it was put on while the sensor rests. A watch without an off-body sensor measures all the time. Not yet tried on a watch.

It measures the height climbed from the barometer, which needs no permission: a rise counts once it reaches 3 m, so the barometer's small wobble does not add up, and then with all of its metres; one value per clock minute, a week of history. The phone turns the metres into floors, 3 m each, keeping partial floors so a day's total adds up. The barometer cannot tell stairs from an elevator, so the phone keeps the height only when the watch counted at least 10 steps per floor's height in that minute and the one before. A watch without a barometer records none.

On the phone, the Watch Sync card on the watch's device screen has a Sync watch button. A sync first asks the watch for every heart rate sample newer than the last one already written, one page of up to 2000 samples at a time, writes each page to Health Connect as one heart rate record per clock hour before asking for the next, and remembers how far it got after each page. A sync interrupted half-way therefore resumes where it stopped, and a page written twice updates rather than duplicates. Then the step minutes, written as one steps record per minute; each pull takes the last minute again, because the watch keeps adding late steps to it, and a minute that grew replaces its record. If OpenWatch may not write steps to Health Connect, the steps and floors stay on the watch and the rest of the sync goes on. Each minute is also written as a cadence (steps per minute) and, once your weight is known from Health Connect or the body profile, as an estimate of the calories walking cost above rest. That estimate uses the cadence equation of Moore and colleagues (Med Sci Sports Exerc 2021), which holds for level walking: the extra cost of hills and stairs is not counted, so it tends to come in low. A minute inside a workout that Health Connect holds, a run or a ride, gets no estimate. A run that no app recorded is not recognised, though: its minutes are estimated as fast walking, beyond the range the equation was fitted on. An estimate, once written, stays: a weight entered later, or a workout saved to Health Connect after the sync, does not change the minutes already estimated. Without a weight it is skipped, and the card says what is missing. If you withheld Health Connect access for one of these estimates, the sync writes the rest. The estimate covers the minutes synced once the weight is known and the access given; minutes synced before stay without one. An automatic sync with the app closed and no background access to Health Connect cannot see other apps' weights and workouts, so it writes no estimate for its minutes. Then the ascent minutes, pulled the same way and written as one floors record per minute that had the steps, and the same metres again as elevation gained. Distance is not sent by the watch: the opt-in "distance from steps" setting under Activities turns the watch's steps into distance like any other steps in Health Connect. Then the sleep minutes follow, as the Sleep section describes. The card reports how many heart rate samples and minutes of steps the run brought and how many nights of sleep it estimated, or that there was nothing new. A sync is refused while an activity is being recorded.

On Android 16 the heart rate sensor sits behind the Health Connect `READ_HEART_RATE` permission rather than `BODY_SENSORS`; the watch app asks for the right one, then for the background grant, on first launch. Without the grant the link still answers, and the watch screen says heart rate access is off.

### Sleep

A Wear OS watch does not hand its sleep to anyone: the vendor's watch app keeps it for the vendor's phone app, and nothing on the watch writes it to the watch's own Health Connect. So the watch app records what a sleep estimate needs and the phone estimates the night, following the published actigraphy methods step by step.

On the watch, one row per clock minute: the accelerometer's movement count (readings whose acceleration jumped, scaled onto the scale the estimator was fitted on; a still wrist counts nothing, a turn in bed a short burst), the per-axis mean and spread of the acceleration, the arm's angle and how much it changed, the minute's mean heart rate and its spread, and flags for the charger, the off-body sensor, a lost pulse and a screen wake-up. The accelerometer needs no permission. A week of minutes is kept.

**Turn on bedtime mode at night.** A sleeping Galaxy Watch8 wakes its processor only every few minutes while the arm is still, and its accelerometer keeps just the last 48 seconds, so on a night without bedtime mode most minutes arrive with no movement in them and the phone may find no night at all. While bedtime mode is on, the watch is on the wrist and off the charger, the watch app keeps the processor awake so every minute is recorded whole; the rest of the day it lets the watch sleep. The watch app's screen says which: recording every minute, paused off the wrist, paused while charging, or a reminder to turn bedtime mode on. The fields are specified in [sleep-minute-features.md](../engineering/sleep-minute-features.md).

On the phone, the same sync pulls the minutes after the heart rate, from the start of the night the last pull reached, so a night is always estimated whole, and runs them through four steps:

1. **Was the watch worn?** A minute on the charger, one the off-body sensor called off the wrist, a pulseless stretch while the heart rate was being recorded, and an hour as still as a table with no pulse anywhere in it (the van Hees accelerometer rule, which the published actigraphy packages use) are not worn. Such a minute can never be sleep, and a night with fewer than three worn hours is not written at all. A watch left on the table or the charger therefore produces nothing.
2. **When was the night?** The arm angle finds the sleep period (the HDCZA method, validated against polysomnography), and the heart rate may shorten its end but never its start, because the heart rate keeps falling for an hour after falling asleep.
3. **Which minutes were sleep?** Movement by the Cole-Kripke weights, the heart rate's volatility, whether the arm kept moving, and whether the wrist kept drifting by a degree or so without a movement count, which is how lying awake in bed looks, combined the way Walch and colleagues found raises wake detection on polysomnography-labelled nights, then the standard rescoring rules.
4. **Which stage?** An estimator from the heart rate against its own baseline and its variability, with the wrist's inactivity rhythm as an extra prior.

Each finished night with sleep also gives a resting heart rate: the lowest half-hour average of the heart rate during the night, written to Health Connect at the end of the night. A night still running gets none. It is a nocturnal value, so it sits a little below one measured sitting in a clinic. The app averages the resting heart rates of a calendar day, so a day that already holds one from elsewhere, such as the vendor's app, keeps that one. If the vendor's value arrives after ours, the next sync removes ours. This needs the Health Connect grants to read and write resting heart rate, and in the background the right to read other apps' data; without them nothing is written, and the watch card asks for the grant.

One sleep session with estimated stages is written per night under a fixed id, so the later estimate replaces the earlier as the night fills in. A night whose window already holds a sleep session from anywhere else, the vendor's app through Health Connect or a session entered by hand, is left alone: those win over an estimate. The session's notes say it was estimated by OpenWatch from the watch app's heart rate and movement.

The stages are a heuristic, not a sleep lab: read them as when heart rate was low and steady versus high and variable. The pipeline is evaluated offline against polysomnography-labelled nights (PhysioNet's sleep-accel dataset, 31 adults wearing an Apple Watch in a sleep lab); the thresholds change only with that evaluation's numbers in the commit message. As of 2026-10-08, per minute over the 31 nights: 90% of minutes scored right, 92% of sleep minutes found, 68% of wake minutes found (Cole-Kripke alone finds 56%), the night's start within 9 minutes and its end within 13 minutes on average, total sleep within 23 minutes, and REM told from non-REM 80% of the time; one night of the 31 went unfound because its recording was mostly empty.

Naps are written too, each as its own sleep session: a still, worn stretch of at least 20 minutes whose pulse falls clearly below your own waking level (the lower quarter of the day's pulse, so resting on the sofa does not count), at most four hours long. A nap that overlaps a session from another app is left alone. The thresholds are judgement, not fitted: no nap was recorded against a reference yet.

The watch also keeps every minute whole without bedtime mode once it sees sleep starting: ten still minutes on the wrist with a falling pulse (twenty by day, so reading or a film does not start it). It stops after five waking minutes in a row, off the wrist, on the charger, after ten hours, or at once when you unlock your phone: the phone tells the watch, at most every five minutes. A glance at the watch does not stop it. Holding the processor awake costs battery; see Night measurements below.

While the watch's device screen is open and the watch is worn, its status card shows the heart rate as it happens ("Now: 72 bpm"). The watch measures it for the phone only while someone looks, and nothing live is stored: the same readings arrive with the next sync. Not yet tried on a watch.

Not yet: series beyond heart rate, steps and sleep. Recorded data from the vendor app, if it is installed, still reaches OpenWatch through Health Connect as before.

Verified on 2026-10-07 with a Galaxy Watch8 (Wear OS on Android 16) and a GrapheneOS Pixel 6 Pro that never ran Galaxy Wearable: the watch accepted a plain bond from the phone's Bluetooth settings (it appears twice in the list, once per radio; either entry bonds both), the phone's cached service list then carried the OpenWatch UUID, and the status card got its answer. The bond is Android's, not the vendor app's, so the plan to onboard with the vendor app, install OpenWatch on the watch and then uninstall the vendor app rests on something that holds. Not yet verified: the bond and the watch service surviving a reboot, and Samsung's battery manager leaving a sideloaded service alone overnight. The watch app is installed over adb; see [Install the watch app on a Wear OS watch](../how-to/wear-os-sideload.md), which also covers skipping the vendor onboarding on the watches that allow adb before onboarding (the Galaxy Watch8 does not; it is onboarded once with Galaxy Wearable, which is uninstalled afterwards).

### Night measurements

While sleep is held, the watch can measure more than the minutes:

- **Heart rate variability (HRV)**: five-minute windows one after another once the arm was still for ten minutes, each written as RMSSD to Health Connect; apps that read Health Connect take the night's value as their mean. A window counts only with a minute of clean beats that cover most of it. The beats come from the raw pulse signal by default, or from the Samsung platform's own beat intervals.
- **Breathing rate**: from the same windows, written only when you switch on *Breathing rate from the watch (experimental)* in the phone's settings; it is not yet checked against a reference.
- **SpO2** (Samsung watches): a spot measurement once an hour while the arm is still, written as oxygen saturation.
- **Skin temperature** (Samsung watches): one value a minute, written per night as the difference from your own usual night.

None of these is checked against a reference device yet. Each needs its Health Connect grant on the phone; without it the values wait on the watch for a week and the sync card asks.

### Workouts

The watch app's screen has a **Workout** button: pick a sport (walking, running, hiking, cycling and twelve indoor ones), then the watch shows the time, heart rate, distance, steps and climb, with pause, resume and stop. Sports with a route ask for location once and use the watch's GPS directly (no Google Play services); refused, they record without a route. The heart rate runs every second-or-so during the workout, and the watch keeps a workout for a month. A workout shorter than half a minute is dropped; one the watch app lost to a crash is saved at the next start.

The phone writes each workout as an exercise session with its route and pauses, and a distance for one with GPS. Steps, climbed height and heart rate are not written again: the watch's all-day series already carry them minute by minute. This needs the Health Connect grants for exercise, exercise routes and distance; without them the workouts wait on the watch. Not yet tried on a watch; the ambient screen and the watch face indicator OpenVitals has are not ported.

### Settings on the watch and from the phone

The watch app's screen and the phone's *Watch settings* card (on the watch's device screen) show the same settings, kept on the watch:

- **Power save**: the one switch that may cost data. Off (the default), the watch records without loss: the heart rate every 10 seconds, every movement at night. On, the heart rate comes about every 10 minutes by day, and at night the watch wakes only every 4 minutes while you lie still, keeping the last 48 seconds of movement of each pause; it wakes fully again as soon as it feels you move. Bedtime mode and a sleep hold always measure the heart rate every 10 seconds.
- **Sleep: wake lock or synced wake-ups**: how a sleep hold keeps the night. The wake lock keeps every movement; synced wake-ups let the processor sleep between the heart rate sensor's batches and empty every sensor then, for less battery; whether they keep every movement is still being measured. Synced wake-ups take the heart rate from the watch's sensor while you sleep.
- **Samsung sources** (only on a watch with the Samsung Health Platform): heart rate and HRV from the platform, skin temperature and SpO2 at night. They need the platform's Developer Mode; without it the platform asks Samsung about this app. A Samsung source that fails falls back to the sensor until a setting changes. Skin temperature and SpO2 also need a permission that only the watch can grant: switch them on once on the watch.

On a Galaxy Watch7 a night with the Samsung heart rate and a wake lock cost about 4.7% battery an hour, one with the sensor's heart rate and a wake lock about 8%; the debug build's measurement protocol is there to compare the settings.

## Experimental Status

Watch support is offered in the spirit of OpenVitals' Bluetooth LE sensors: useful, honest about its limits, and not a substitute for the vendor's own app. Sync happens by hand, or on a schedule the user chose per watch. Nothing syncs until one of those two says so.

## Pairing A Watch

The home screen shows the paired watches, or "No watch paired" with a Pair a watch button.

1. OpenWatch asks for nearby-device Bluetooth permission where Android requires it, then scans. Watches already bonded with the phone appear in the list even when they advertise nothing useful; a Wear OS watch is offered when its name is a known smartwatch family or it runs the OpenWatch watch app (see Found when adding, above).
2. Picking a watch asks Android whether OpenWatch may access it. This step is optional. Allowing it lets Android keep OpenWatch alive while the watch is nearby, so a sync that takes minutes is not interrupted. Declining still adds the watch.

The watch itself is paired with the phone in Android's Bluetooth settings, or by the vendor's onboarding; OpenWatch does not bond it.

The checklist before the scan offers the OS-level permissions a watch benefits from: nearby devices (or location, on Android 11 and older), and exemption from battery optimization when no watch is associated yet, so overnight syncs run close to their schedule. Each row explains itself and opens the right system screen; all but Bluetooth can be declined and granted later.

A watch can be renamed, switched off without unpairing, or removed. Removing it drops the companion association and the watch's link token, so adding it again asks on the watch once more. Data already written to Health Connect is kept.

## Syncing

### Automatic Sync

"Automatic sync", on the device screen of a watch, syncs the watch on its own every 30 minutes, hour, or two hours. Off by default, and set per watch, so two paired watches can be on different schedules or only one of them on a schedule at all. Between 23:00 and 07:00 the scheduled runs are skipped, so the watch's radio stays quiet while you sleep; the first run after 07:00 brings the whole night. A sync by hand still runs at any hour.

A scheduled sync runs while the app is closed. Health Connect then lets OpenWatch read other apps' records only with its "Access data in the background" grant. Without it the watch's data is still saved, but everything built from all apps' records, such as Body Energy and the history caches, waits until the app is opened. So switching Automatic sync on asks for that grant when it is missing, and the card keeps a line and an "Allow background access" button for as long as it is.

A scheduled run does exactly what tapping Sync does, with two differences:

- It is quiet. A run that could not reach the watch leaves no error on screen; the last sync time on the device screen and the watches list is what says whether the schedule is working. A watch out of range at 3am is not a fault to report.
- It refuses rather than queues. If an activity recording is in progress, or a sync is already running, the run steps aside and waits for the next one. It retries a couple of times on a short backoff first, since a watch that just walked out of range is usually back within minutes.

The exact moment is Android's to choose, not the app's. The schedule is a floor, not an alarm: a run can arrive late, and the phone being in Doze, below its low-battery mark, or out of range of the watch all delay it. Granting the battery-optimization exemption the pairing checklist offers is what keeps overnight runs close to their schedule. The schedule survives a reboot and an app update without being re-armed.

Nothing about this changes what is written or where. The same import runs, the same cursors apply, and syncing the same day twice still does not double anything.

## Privacy

Nothing leaves the phone. The watch is read over Bluetooth and the results go to Health Connect. A Samsung source talks to the Samsung Health Platform on the watch, a Samsung service; what that service sends and how to keep it quiet is in [samsung-health-and-privacy.md](../engineering/samsung-health-and-privacy.md). The phone keeps no copy of the watch's data: the watch is the store, for a week.

See [Permissions](../app/permissions.md) for the full boundary, including why the companion association is asked for and why it is optional.

## Known Limitations

- The Connected and Not connected labels reflect whether the watch is switched on in OpenWatch, not whether a Bluetooth link is open right now.
- Wear OS watches sync heart rate, steps, floors, an estimated sleep session per night and per nap, the night's resting heart rate, and, from the night measurements, HRV, the breathing rate (opt-in), SpO2 and skin temperature, by hand or on the automatic schedule. Workouts recorded on the watch are written as sessions with their route.
- Garmin watches are not supported.
