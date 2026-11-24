# Watches

> **Status:** Current implemented behavior. Experimental.
> **Audience:** Users and contributors.
> **Implementation:** `devices/core`, `devices/garmin`, `devices/wearos`, `devices/notifications`, `features/watches`.
> **Navigation:** the typed routes `Home`, `DeviceDetail`, `WatchData`, `WatchNotifications`, `WatchSettings`, `WatchAlarms` and `WatchSendPoint` in `navigation/Routes.kt`.
> **Related:** [Supported devices](devices.md), [Permissions](../app/permissions.md), [Install the watch app on a Wear OS watch](../how-to/wear-os-sideload.md).

OpenWatch writes what a watch records to Health Connect and shows no history of its own; OpenVitals, or any other Health Connect app, is where the records are read.

OpenWatch has experimental support for wrist devices. The home screen pairs a watch and copies what it recorded onto the phone over Bluetooth. There is no watch-vendor account and no network step; the app declares no internet permission.

Support differs sharply by make:

- **Garmin** watches are read over Garmin's own Bluetooth protocol. Sync, the watch-only data screen, notification forwarding, the watch's settings tree, and find-my-watch are all Garmin features.
- **WearOS** watches run the OpenWatch Wear OS companion app (`:wear`). The phone app checks whether the watch is paired and whether the watch app answers, and syncs the heart rate and steps the watch app recorded into Health Connect, plus a sleep session per night estimated from the movement and heart rate the watch app recorded. No vendor app is needed on the phone once the watch is paired.

## Wear OS Companion App

OpenWatch includes a Wear OS companion app (`:wear`) that runs directly on wrist devices.

The companion app communicates with the phone app over standard Android Bluetooth RFCOMM sockets using a dedicated service UUID (`4838d728-6e5a-4b95-a29d-a60032338301`). This design uses standard Android OS (AOSP) APIs only, maintaining 100% open-source compatibility suitable for F-Droid without depending on closed-source Google Play Services libraries.

The watch app runs `WearAppService`, a `connectedDevice` foreground service with an ongoing "Phone link" notification, so it stays reachable after the app is closed. It starts once the watch app has the Nearby devices permission (`BLUETOOTH_CONNECT`, asked on first launch), reopens its listener when Bluetooth comes back on, and answers a `PING` line with `PONG`. `WearBootReceiver` starts it again after a watch restart or an app update, as long as the permission is granted.

The watch app also offers five tiles, added from the watch's tile list: **Heart rate**, **Steps**, **Floors**, **OpenWatch Today** and **OpenWatch Quick log**. The first three each show today's value in the metric's colour from the phone app and the day's curve from midnight to midnight, drawn up to now, over the hours 0, 6, 12, 18 and 24: the heart rate as quarter-hour means, broken where the watch stored none (off the wrist or charging), the steps and floors as a running total. The steps are the ones the watch counted itself, so they can fall short of the day's total in Health Connect, which adds the phone's own steps and other apps'. The heart rate is the newest sample with its age; one older than an hour shows as a dash. Under its curve is the day's lowest and highest sample. The floors are counted as the phone counts them before writing them to Health Connect: only the height that enough steps carry (`AscentCredit`), three metres a floor, both rounded down; under the curve are the metres that counted. A value whose permission is off, or whose sensor the watch lacks, shows as a dash with the reason. The tiles only read the watch's metric store and start no sensor; a tile counts as stale five minutes after it was drawn, so Wear OS asks for a new one. Tapping a tile opens the watch app. Verified on a TicWatch Pro 5 with Wear OS 4.

**OpenWatch Today** puts today's steps and the heart rate now side by side, each as its own tile shows it, with an **Open** button for the watch app. **OpenWatch Quick log** logs without opening the app: a ring around the screen fills with the day's water toward two litres, one button adds a glass (250 ml) right on the tile, a heart button starts a heart rate measurement (only on a watch with the sensor) and a plus opens the quick log. A tile refresh never logs a glass a second time, and a glass logged in the app shows on the tile at once. These two tiles open the app only at the quick log and the measurements. Not yet tried on a watch.

Three complications put the same numbers on the watch face, chosen in the watch face's editor: **Heart rate** (the newest sample, a dash once it is older than an hour), **Steps** (today's, as the Steps tile counts them) and **Floors** (today's, as the Floors tile counts them). Each is a short text with the metric's icon and unit; the steps can also fill a ranged slot, an arc toward 8,000 steps, the phone app's first daily goal (the watch has no goal setting). Each reads what its tile reads, starts no sensor, and is asked for again about every five minutes; a day's count is dropped at midnight and a heart rate once it is an hour old, so neither stays on the face until the next update. Tapping one opens the watch app. Not yet tried on a watch.

When opening a paired Wear OS watch on its device screen from the home screen:
- **Paired**: OpenWatch looks for the watch among the phone's bonded devices. The address stored at onboarding comes from the BLE scan, and Wear OS watches advertise with a private address, so the match falls back to the name (a trailing ` LE` ignored). There is no guess beyond that: another bonded watch is never reported as this one.
- **Found when adding**: the Watches scan offers a bonded device as a Wear OS watch when its name is a known smartwatch family or its service list names the OpenWatch Wear OS app. A wrist-watch Bluetooth class alone is not enough, since Garmin, Fitbit and Huawei watches report it too. The service list Android caches at pairing predates the app, so the scan re-queries it (SDP) for bonded dual-mode devices that nothing has claimed yet and that are not audio, peripheral or imaging devices. A watch with an unknown name is therefore found once the OpenWatch Wear OS app runs on it.
- **App answers**: the phone pings the watch over RFCOMM, giving up after 8 seconds. No answer means the watch is off or out of range, or the watch app is not running; a bond alone is never shown as connected.
- **Permission**: without Nearby devices on the phone, the card shows a grant callout instead of a status.
- **Check again**: the "Validate Wear OS App" button re-runs the check.
- **Companion link**: a Wear OS watch is added only when the user allows Android's association dialog; declined, the add sheet stays open with the reason. A watch without the association, added before this rule or with the association removed in Android's settings, shows "Link watch" on its status card.

The UUID and the protocol words come from the shared `:wearlink` module on both sides.

### Watch Sync

The watch app records heart rate on its own, from the moment it is granted heart rate access, with the screen off and the app closed: the same foreground service that answers the phone keeps the sensor on, batched so the watch wakes once every few minutes rather than once per beat. It keeps one sample every ten seconds (every second during a workout) and a week of history, pruning older samples itself. Every series on the watch is kept a week, whether or not a phone collected it, so the watch's status screen shows when a phone last synced; from the fifth day without a sync it says how many days are left, and the watch posts a reminder (the Sync reminders channel), again once readings the phone never got are being dropped. The next sync takes it away.

It counts steps the same way, from the watch's own step counter, once it is granted physical activity access: one count per clock minute, a week of history. Steps taken while the watch app was not running, for example after a restart of the watch, are spread evenly over the minutes in between. The watch's screen shows whether step counting is on and offers the grant if it is not. A watch without a step counter simply records none.

While the watch is off the wrist for a minute, it stops measuring heart rate, which is what costs the most battery, and starts again as soon as it is back on. Turning the screen on or taking the watch off the charger also restarts it, in case the watch cannot tell it was put on while the sensor rests. A watch without an off-body sensor measures all the time. Not yet tried on a watch.

It measures the height climbed from the barometer, which needs no permission: a rise counts once it reaches 3 m, so the barometer's small wobble does not add up, and then with all of its metres; one value per clock minute, a week of history. The phone turns the metres into floors, 3 m each, keeping partial floors so a day's total adds up. The barometer cannot tell stairs from an elevator, so the phone keeps the height only when the watch counted at least 10 steps per floor's height in that minute and the one before. A watch without a barometer records none.

On the phone, the Watch Sync card on the watch's device screen has a Sync watch button. A sync first asks the watch for every heart rate sample newer than the last one already written, one page of up to 2000 samples at a time, writes each page to Health Connect as one heart rate record per clock hour before asking for the next, and remembers how far it got after each page. A sync interrupted half-way therefore resumes where it stopped, and a page written twice updates rather than duplicates. Then the step minutes, written as one steps record per minute; each pull takes the last minute again, because the watch keeps adding late steps to it, and a minute that grew replaces its record. If OpenWatch may not write steps to Health Connect, the steps and floors stay on the watch and the rest of the sync goes on. Each minute is also written as a cadence (steps per minute) and, once your weight is known from Health Connect or the body profile, as an estimate of the calories walking cost above rest. That estimate uses the cadence equation of Moore and colleagues (Med Sci Sports Exerc 2021), which holds for level walking: the extra cost of hills and stairs is not counted, so it tends to come in low. A minute inside a workout that Health Connect holds, a run or a ride, gets no estimate. A run that no app recorded is not recognised, though: its minutes are estimated as fast walking, beyond the range the equation was fitted on. An estimate, once written, stays: a weight entered later, or a workout saved to Health Connect after the sync, does not change the minutes already estimated. Without a weight it is skipped, and the card says what is missing. If you withheld Health Connect access for one of these estimates, the sync writes the rest. The estimate covers the minutes synced once the weight is known and the access given; minutes synced before stay without one. An automatic sync with the app closed and no background access to Health Connect cannot see other apps' weights and workouts, so it writes no estimate for its minutes. Then the ascent minutes, pulled the same way and written as one floors record per minute that had the steps, and the same metres again as elevation gained. Distance is not sent by the watch; OpenVitals' opt-in "Distance from steps" setting turns the watch's steps into distance like any other steps in Health Connect. Then the sleep minutes follow, as the Sleep section describes, and the entries made on the watch (Quick log, Spot measurements), before the workouts. The card reports how many heart rate samples and minutes of steps the run brought and how many nights of sleep it estimated, or that there was nothing new.

On Android 16 the heart rate sensor sits behind the Health Connect `READ_HEART_RATE` permission rather than `BODY_SENSORS`; the watch app asks for the right one, then for the background grant, on first launch. Without the grant the link still answers, and the watch screen says heart rate access is off.

### Sleep

A Wear OS watch does not hand its sleep to anyone: the vendor's watch app keeps it for the vendor's phone app, and nothing on the watch writes it to the watch's own Health Connect. So the watch app records what a sleep estimate needs and the phone estimates the night, following the published actigraphy methods step by step.

On the watch, one row per clock minute: the accelerometer's movement count (readings whose acceleration jumped, scaled onto the scale the estimator was fitted on; a still wrist counts nothing, a turn in bed a short burst), the per-axis mean and spread of the acceleration, the arm's angle and how much it changed, the minute's mean heart rate and its spread, and flags for the charger, the off-body sensor, a lost pulse and a screen wake-up. The accelerometer needs no permission. A week of minutes is kept.

**Turn on bedtime mode at night.** A sleeping Galaxy Watch8 wakes its processor only every few minutes while the arm is still, and its accelerometer keeps just the last 48 seconds, so on a night without bedtime mode most minutes arrive with no movement in them and the phone may find no night at all. While bedtime mode is on, the watch is on the wrist and off the charger, the watch app keeps every minute whole; the rest of the day it lets the watch sleep. On a watch without Samsung's bedtime mode, Do Not Disturb (or theater mode) stands in for it between 21:00 and 09:00 only, so one left on by day costs nothing. The watch app's screen says which: recording every minute, paused off the wrist, paused while charging, or a reminder to turn bedtime mode on. The fields are specified in [sleep-minute-features.md](../engineering/sleep-minute-features.md).

On the phone, the same sync pulls the minutes after the heart rate, from the start of the night the last pull reached, so a night is always estimated whole, and runs them through four steps:

1. **Was the watch worn?** A minute on the charger, one the off-body sensor called off the wrist, a pulseless stretch while the heart rate was being recorded, and an hour as still as a table with no pulse anywhere in it (the van Hees accelerometer rule, which the published actigraphy packages use) are not worn. Such a minute can never be sleep, and a night with fewer than three worn hours is not written at all. A watch left on the table or the charger therefore produces nothing.
2. **When was the night?** The arm angle finds the sleep period (the HDCZA method, validated against polysomnography), and the heart rate may shorten its end but never its start, because the heart rate keeps falling for an hour after falling asleep.
3. **Which minutes were sleep?** Movement by the Cole-Kripke weights, the heart rate's volatility, whether the arm kept moving, and whether the wrist kept drifting by a degree or so without a movement count, which is how lying awake in bed looks, combined the way Walch and colleagues found raises wake detection on polysomnography-labelled nights, then the standard rescoring rules.
4. **Which stage?** The same estimator as for a Garmin watch without stages, from the heart rate against its own baseline and its variability, with the wrist's inactivity rhythm as an extra prior.

Each finished night with sleep also gives a resting heart rate: the lowest half-hour average of the heart rate during the night, written to Health Connect at the end of the night. A night still running gets none. It is a nocturnal value, so it sits a little below one measured sitting in a clinic. Apps that read it, OpenVitals among them, average a calendar day's resting heart rates, so a day that already holds one from elsewhere, such as the vendor's app, keeps that one and gets none from OpenWatch. If the vendor's value arrives after ours, the next sync removes ours. This needs the Health Connect grants to read and write resting heart rate, and in the background the right to read other apps' data; without them nothing is written, and the watch card asks for the grant.

One sleep session with estimated stages is written per night under a fixed id, so the later estimate replaces the earlier as the night fills in. A night whose window already holds a sleep session from anywhere else, the vendor's app through Health Connect or a session entered by hand, is written beside it, and Health Connect's data source priority decides which one apps show; with *Write the watch's sleep beside other apps* switched off in the phone's settings it is left alone, and the other app's session wins. The session's notes say it was estimated by OpenWatch from the watch app's heart rate and movement.

The stages are a heuristic, not a sleep lab: read them as when heart rate was low and steady versus high and variable. The pipeline is evaluated offline against polysomnography-labelled nights (PhysioNet's sleep-accel dataset, 31 adults wearing an Apple Watch in a sleep lab); the thresholds change only with that evaluation's numbers in the commit message. As of 2026-10-08, per minute over the 31 nights: 90% of minutes scored right, 92% of sleep minutes found, 68% of wake minutes found (Cole-Kripke alone finds 56%), the night's start within 9 minutes and its end within 13 minutes on average, total sleep within 23 minutes, and REM told from non-REM 80% of the time; one night of the 31 went unfound because its recording was mostly empty.

Naps are written too, each as its own sleep session: a still, worn stretch of at least 20 minutes whose pulse falls clearly below your own waking level (the lower quarter of the day's pulse, so resting on the sofa does not count), at most four hours long. A nap that overlaps a session from another app is written beside it the same way, or left alone with that switch off. The thresholds are judgement, not fitted: no nap was recorded against a reference yet.

The watch also keeps every minute whole without bedtime mode once it sees sleep starting: ten still minutes on the wrist with a falling pulse (twenty by day, so reading or a film does not start it). It stops after five waking minutes in a row, off the wrist, on the charger, after ten hours, or at once when you unlock your phone: the phone tells the watch, at most every five minutes. A glance at the watch does not stop it. Holding the processor awake costs battery; see Night measurements below.

While the watch's device screen is open and the watch is worn, its status card shows the heart rate as it happens ("Now: 72 bpm"). The watch measures it for the phone only while someone looks, and nothing live is stored: the same readings arrive with the next sync. While a sync runs it pauses, and it comes back once the sync is over. Not yet tried on a watch.

Not yet: series beyond heart rate, steps and sleep. Recorded data from the vendor app, if it is installed, still reaches Health Connect, and the apps that read it, as before.

Verified on 2026-10-07 with a Galaxy Watch8 (Wear OS on Android 16) and a GrapheneOS Pixel 6 Pro that never ran Galaxy Wearable: the watch accepted a plain bond from the phone's Bluetooth settings (it appears twice in the list, once per radio; either entry bonds both), the phone's cached service list then carried the OpenWatch UUID, and the status card got its answer. The bond is Android's, not the vendor app's, so the plan to onboard with the vendor app, install OpenWatch on the watch and then uninstall the vendor app rests on something that holds. Not yet verified: the bond and the watch service surviving a reboot, and Samsung's battery manager leaving a sideloaded service alone overnight. The watch app is installed over adb; see [Install the watch app on a Wear OS watch](../how-to/wear-os-sideload.md), which also covers skipping the vendor onboarding on the watches that allow adb before onboarding (the Galaxy Watch8 does not; it is onboarded once with Galaxy Wearable, which is uninstalled afterwards).

### Night measurements

While sleep is held, the watch can measure more than the minutes:

- **Heart rate variability (HRV)**: a five-minute window once the arm was still for ten minutes, at most every half hour, written as RMSSD to Health Connect. The beats come from the raw pulse signal by default, or from the Samsung platform's own beat intervals.
- **Breathing rate**: from the same windows, written unless you switch off *Breathing rate from the watch (experimental)* in the phone's settings; it is not yet checked against a reference. A window gives one when its two routes agree, or when only one of them has a clear rhythm.
- **SpO2** (Samsung watches): a spot measurement once an hour while the arm is still, written as oxygen saturation.
- **Skin temperature** (Samsung watches): one value a minute, written per night as the difference from your own usual night. The first three nights form that usual night and are not written: the Watch Sync card says so after a sync that brought such a night, and for as long as the usual night is forming it says how many more nights are needed, also between syncs and with the watch out of reach. The count goes while skin temperature is switched off on the watch. A night the tracker dropped out of, or one synced in several parts, counts once, with all of it.

None of these is checked against a reference device yet. Each needs its Health Connect grant on the phone; without it the values wait on the watch for a week and the sync card asks.

### Workouts

The watch app's screen has a **Workout** button: pick a sport (walking, running, hiking, cycling and twelve indoor ones), then the watch shows the time, heart rate, distance, steps and climb, with pause, resume and stop. Sports with a route ask for location once and use the watch's GPS directly (no Google Play services); refused, they record without a route. During a workout the watch reads its own heart rate sensor live, whatever heart rate source is chosen (the Samsung platform hands its readings over minutes late with the screen off), and keeps every reading, one a second, for the zones and the workout's figures; the day's figures on the watch count those seconds as they count the rest of the day's ten-second readings, so a workout does not outweigh the rest of the day's average. The watch keeps a workout for a month. A workout shorter than half a minute is dropped; one the watch app lost to a crash is saved at the next start. A route longer than the 9000 points the link carries (more than about 12 hours at one point every five seconds) is thinned to every second point, as often as it takes, so it still covers the whole workout.

A run or a ride with a route pauses by itself when you stop for 8 seconds, at a crossing or a bench, and goes on when you move again: the GPS speed decides, the time you stood is not counted, and the wrist buzzes once for the pause and twice when it goes on. A pause you tap stays until you tap again. **Auto-pause** in the Recording settings switches it off.

After 10 minutes of brisk walking or running (90 steps a minute or more, a run from a median of 140; one slower minute at a crossing does not break it) without a workout, the watch offers to record it: a notification, "Looks like a walk" or "Looks like a run", with a **Record** button that starts the workout where the walk began, so the minutes already walked count, with their steps and heart rate, without a route. It reads the step minutes the watch keeps anyway, each time new ones are written and at most once a minute, so nothing runs for it; an offer comes once, and nothing is recorded unless you tap Record. **Detect workouts** in the Recording settings switches it off. The numbers are a first guess, to be tuned on real days.

When you stop a workout after standing still for a minute or more, with the heart rate up in the light zone or above, the watch already has the heart rate recovery: the accelerometer saw when your arm came to rest, and the falls are read from that moment without asking (raising the wrist to stop does not count as moving on). Otherwise, with the heart rate in the light zone or above, the watch asks whether to measure it; then for two minutes it keeps reading the heart rate every second and counts them down with the heart rate now, while you keep walking slowly. Without zones from the phone, or with a heart rate below the light zone, it does not ask. The summary then shows how far the heart rate fell after one and after two minutes, from the peak of the last seconds before the stop (Cole and colleagues, N Engl J Med 1999: a fall of 12 or less in the first minute after a walking cool-down was abnormal); a reading where the heart rate was already falling before the stop, or did not fall, shows none and says it is not usable. Under the falls a line says when the reading is not comparable with others, because the effort before the stop was short of near-maximal (Cole and colleagues measured theirs after a maximal exercise test), or only approximate (no maximum to judge it by, or a peak from one reading); the session's notes on the phone say the same. Below the recorded values the summary shows the workout's figures, the same the phone writes: the highest heart rate, the time in each zone, the training load, the energy above rest, each kilometre's time and a VO2max, each where the watch has what it needs. The energy needs the weight, height, birth year and sex of your body profile, which the phone sends the watch after each sync beside the zones' heart rates (the weight from Health Connect, else the profile's); without a weight there is none.

The phone writes each workout as an exercise session with its route and pauses, and a distance for one with GPS. From the heart rate the watch recorded during it, the session also gets its kilometres as laps and a line of notes (the mean and highest heart rate, the minutes in each heart rate zone, and the training load, Banister's TRIMP), and a walk or run with a route a VO2max estimate (the ACSM's walking and running equations on the level over the fraction of heart rate reserve, Swain and Leutholtz 1997), written as its own record where OpenWatch may write VO2 max. The training load and the VO2max need the resting heart rate of your body profile. Where the watch measured the recovery, the notes give its falls too. These are the figures OpenVitals works out from the same formulas, in the shared health-calculations library. Steps, climbed height and heart rate are not written again: the watch's all-day series already carry them minute by minute. This needs the Health Connect grants for exercise, exercise routes and distance; without them the workouts wait on the watch. Each workout also gets its active calories, as an estimate, once your weight is known from Health Connect or the body profile: from the watch's mean heart rate during the workout, each pair of readings counted for the time between them (at most five minutes) and a pause for nothing (Keytel and colleagues, J Sports Sci 2005, which needs sex and birth year and holds at 57 to 90 % of the maximum heart rate), else for a walk or run from its distance at the pace ACSM's metabolic equations cover, else from the sport's value in the 2024 Adult Compendium of Physical Activities. The resting share, from the basal metabolic rate, is taken off. The phone reads the same heart rate the watch's summary does, the watch's own, so the summary's energy and the calories in Health Connect are one number. Without a weight, or without the grant to write active calories, the workout is written without them and the card says what is missing. While a workout runs, the watch face shows its pill (sport and time; a tap opens the workout; its notification counts the time too, for a surface that shows the notification rather than the pill), and the workout screen stays on in ambient mode with a dim layout of its own: the time in whole minutes and dashes where the heart rate and distance would be, since the screen changes only once a minute there. The ambient layout needs the ambient service of the Wear SDK, a system library of recent Wear OS releases; without it the system dims the screen as for any app. Not yet tried on a watch.

### What the watch offers

While the watch app is on screen its steps count live: the step counter hands each step over at once rather than in its usual batches, and the dashboard reads them again within a couple of seconds (not during a workout, which counts its own). The heart rate's detail screen shows the heart rate as it happens while it is open, from a sensor listener of its own that stops when the screen goes. Nothing extra is stored. Not yet tried on a watch.

The watch app offers only what this watch can give. Its dashboard shows a metric the watch records itself only where it has the sensor (the steps, and the distance and active calories that come from them, with a step counter; the heart rate with a heart rate sensor; the floors and the height climbed with a barometer) or already holds a reading of it, a night's vital (HRV, blood oxygen, skin temperature, breathing rate) once a night gave one, and always what the phone sends and what is logged by hand. A workout shows only the values its sensors give: no heart rate without the sensor, no climb without a barometer, no steps without a step counter, and distance and pace only when the workout records a route. On a watch without GPS a sport with a route does not ask for location and records without one. Not yet tried on a watch without one of these sensors.

**Edit tiles**, on the dashboard, chooses which of its tiles show and in which order: the arrows beside a tile move it up or down, a tap switches it off, the tiles below can be switched on, and **Reset default** brings back steps, heart rate, sleep, active calories and water. Only what this watch offers is listed, so a night's vital can be added once a night gave one. A chosen tile that is not on offer stays chosen and shows once it is. Not yet tried on a watch.

### Quick log

The watch app's dashboard has a **Log** button, below the activity rings:

- **Water**: one tap adds a glass, 250 ml, toward a goal of two litres a day; the button shows the day's total against the goal. The **OpenWatch Quick log** tile adds one too.
- **Weight** and **Blood pressure**: a value picked with the + and − buttons or the crown, starting from the last one logged; blood pressure asks for the systolic, then the diastolic. Blood pressure stays within what Health Connect can store (a systolic of up to 200 mmHg).
- **Body fat**, **Blood oxygen**, **Breathing rate**, **Body temperature**, **Blood glucose** and **Height**: one number each, picked the same way, starting from the last one logged, in the watch's units (Fahrenheit, mg/dL and inches with imperial units; a height in whole centimetres or inches).
- **Breathe**: a guided minute, six breaths of four seconds in and six out, a circle that grows and shrinks with a buzz at each turn. The screen stays on for that minute only. A finished minute is logged as a mindful minute; one swiped away is not.

**Edit entries**, the last button, chooses which of these the quick log shows and in which order, with the same arrows as the tile editor; **Reset default** brings back every entry. The newest six entries made by hand are listed below, with their time; a spot measurement's result is not among them. The entries count at once toward the dashboard's Water and Weight tiles and their detail screens (the newest weight is the value shown, however old). The detail screens of HRV, weight, water, blood oxygen and breathing rate list the five newest entries of their kind made on the watch. They are kept on the watch for a month. Each sync writes them to Health Connect, from the watch and as entered by hand: water as hydration, a weight, a blood pressure, a body fat, a blood oxygen, a breathing rate, a body temperature, a blood glucose, a height, and a breathing minute as a mindfulness session, where the phone's Health Connect keeps those (on one that does not, the minute stays on the watch only). An entry whose Health Connect access is off waits on the watch for the next sync, and the watch's card asks for the access; the others are written meanwhile.

### The phone's figures on the watch

After each sync the phone hands the watch the last week's figures that the watch cannot work out itself: the night's sleep with its stages, the resting heart rate, and the day's active calories and distance, as Health Connect holds them from every app. They fill the dashboard's Sleep, Resting heart rate, Active calories and Distance tiles and their detail screens: today's night or total as the value now, and the days before as the week's bars; the sleep detail shows the stages of the night that ended today, and the heart rate detail the resting rate. The watch keeps two weeks of them. A figure the phone may not read from Health Connect stays off the watch, and an automatic sync with the app closed and no background access to Health Connect sends none, since it would see only OpenWatch's own records. Not yet tried on a watch.

### Spot measurements

Where the watch has a sensor for one, its dashboard has a **Measure** button, and the heart rate, HRV and blood oxygen detail screens a **Measure now**:

- **Heart rate**: from the heart rate sensor, held still, usually within 15 seconds. The value is the median of the newest five readings once eight are in, so one stray reading while the sensor locks on does not move it; with none in half a minute it says the watch is off the wrist or the reading was not reliable.
- **Heart rate variability**: three minutes sitting still, from the Samsung Health Platform's own beat intervals where you chose them as the HRV source on the watch's status screen, else from single beats on a watch whose sensor reports each beat, else from Samsung's raw pulse signal, the one the night's windows read. The value is the RMSSD, computed as for a night's windows, on at least 60 clean beats; while it runs the screen shows the pulse, and the heart rate sensor stays on for the pulse signal. A reading from an arm that moved, by the accelerometer, is not kept, and while the watch is taking one of the night's HRV windows a spot HRV says so and waits.
- **Air pressure**: the barometer's mean of five readings, with the altitude it stands for at standard pressure.
- **Blood oxygen** (only on a watch with the Samsung Health Platform): the platform's SpO2 tracker, as at night, held still, a reading after about 15 seconds; an arm that moved gives none. While the watch takes the night's hourly SpO2 a spot one says so and waits.

A heart rate or HRV asks for heart rate access first where it is missing, a blood oxygen for blood oxygen access. The measurement's time fills an arc along the screen's edge, and the wrist buzzes once when it has a reading, twice when it failed. The measurement goes on when the screen goes off, and its result is on the screen when the watch wakes; where the watch keeps the app on screen in ambient mode (the ambient service of the Wear SDK, as for a workout) a running measurement draws a dim layout of its own meanwhile: its name, a dash for the value, which the screen would show only once a minute, and what to do. A heart rate, HRV or blood oxygen is kept as an entry measured on the watch, beside the quick log's, and stands in as the dashboard's value now where the watch's own recording has none that day; the next sync writes it to Health Connect as recorded by the watch. The air pressure is shown and not kept. Not yet tried on a watch.

### Settings on the watch and from the phone

The dashboard's **Settings** button opens the watch's settings, each with how it stands:

- **Phone**: connected while a phone is allowed, still paired and the link is up. Its screen shows the link, a phone asking to be allowed (Allow or Block), the allowed phones and whether each is still paired, with the way to the Bluetooth settings for one that is not, and how to add a phone when there is none.
- **Sensor access**: granted once heart rate, heart rate in the background, steps (on a watch with a step counter) and nearby devices all are. Its screen lists each, asks for the missing ones, and leads to **Recording details**, the status screen with what the watch has stored.
- **Recording**: the settings below, each with a line on what it does, what it costs and what it loses.
- **Units**: metric or imperial, a tap switches. The watch's screens and the quick log tile show their values in it; the phone's own setting is apart. Metric until you choose.

The watch's Recording screen and the phone's *Watch settings* card (on the watch's device screen) show the same settings, kept on the watch, with the same lines on what each does:

- **Heart rate outside bedtime mode**: every 10 seconds (the default) or power save, about every 10 minutes. Bedtime mode and a sleep hold always measure every 10 seconds.
- **Auto-pause** and **Detect workouts** (on the watch only, both on by default): a run or a ride with a route that pauses by itself, and the offer of a walk or a run the watch sees, as described under the workouts above.
- **Sleep: wake lock or synced wake-ups**: how a sleep hold keeps every minute. Synced wake-ups let the processor sleep between the heart rate sensor's wake-ups and empty every sensor then; they work only with the heart rate from the sensor. They are the default on a watch whose heart rate sensor can wake it, the wake lock on one whose sensor cannot; a choice made here or on the phone stays.
- **Samsung sources** (only on a watch with the Samsung Health Platform): heart rate and HRV from the platform, skin temperature and SpO2 at night. They need the platform's Developer Mode; without it the platform asks Samsung about this app. A Samsung source that fails falls back to the sensor until a setting changes. Skin temperature and SpO2 also need a permission that only the watch can grant: switch them on once on the watch.
- **Measurements on the watch** (Samsung watches whose platform lists them; written from the platform's code and not yet run on a watch): an **ECG**, thirty seconds with a finger on the upper button, shown as a trace with its heart rate, and **body composition**, with two fingers on the buttons, from the height, weight, year of birth and sex in your body profile on the phone. The body composition goes to the phone at the next sync and is written to Health Connect as body fat, lean body mass and body water. The ECG is shown only: it describes the rhythm (even or not, with a P wave or without), draws no finding from it and is not stored. A third HRV source, the platform's own green PPG, can be chosen on the watch.

On a Galaxy Watch7 a night with the Samsung heart rate and a wake lock cost about 4.7% battery an hour, one with the sensor's heart rate and a wake lock about 8%; the debug build's measurement protocol is there to compare the settings.

## Experimental Status

Watch support is developed and verified against a single Garmin model. It is offered as useful and honest about its limits, not as a substitute for the vendor's own app.

- The protocol is not model-specific, and OpenWatch asks each watch what it can do rather than assuming. A watch that does not report find-my-watch gets no Find button; one without a settings tree gets no settings row.
- Both Garmin transports sync and forward notifications: current multi-link watches and the older direct-GFDI characteristic pair. Live heart-rate and step streaming, and the newer FileSync listing, need the multi-link transport, so an older watch pairs and syncs but has no live readings. Pairing probes the watch and warns when neither transport can be recognized.
- A file type OpenWatch does not understand is skipped rather than failing the sync.
- Sync happens by hand, or on a schedule the user chose per watch. Nothing syncs until one of those two says so.

## Pairing A Watch

The home screen shows the paired watches, or "No watch paired" with a Pair a watch button.

1. OpenWatch asks for nearby-device Bluetooth permission where Android requires it, then scans. The watch must be awake and close to the phone. Watches already bonded with the phone appear in the list even when they advertise nothing useful.
2. Picking a Garmin watch runs a short checklist: pairing with the watch, asking Android for access, and checking what the watch supports.
3. Android shows its own pairing dialog. The code shown on the watch has to be confirmed.
4. Android then asks separately whether OpenWatch may access this watch. This step is optional. Allowing it lets Android keep OpenWatch alive while the watch is nearby, so a sync that takes minutes is not interrupted. Declining still pairs the watch, and syncing then works while OpenWatch is open.

After pairing, a checklist offers the OS-level permissions a watch benefits from: notification access for forwarding, and exemption from battery optimization so a held link survives the night. Each row explains itself and opens the right system screen; all of them can be declined and granted later.

Android's pairing (the bond) is what proves a device is the user's watch; the Garmin protocol has no check of its own. So OpenWatch connects to a Garmin watch only while that bond exists. If the watch was unpaired in Android's Bluetooth settings, a sync stops with a message that says to remove the watch and add it again.

A watch can be renamed, switched off without unpairing, or removed. Removing it unpairs the watch and forgets which files were already copied, so a future pairing starts fresh. Data already written to Health Connect is kept. Removing the last Garmin watch also deletes the downloaded file copies and the per-minute sleep data, which only serve a paired watch, and the dialog offers to delete the watch-only history (stress, Body Battery, scores) that has no copy in Health Connect. `GarminLocalData` owns all three, and applies their retention at app start as well as inside a sync.

A Garmin Edge bike computer is recognized as a bike computer rather than a watch. It gets a Live sensor section instead of the watch data screen, because broadcast mode is normally only on during a ride.

## Syncing

Tapping a paired Garmin watch opens its device screen, which has a Sync action. The watches list itself shows each watch's last sync time.

The watch hands over the files it recorded since last time and OpenWatch imports them. Each file is saved on the phone before the watch is told it may archive it, and a file is only marked as synced once its import succeeded, so a run that fails partway re-fetches rather than skipping data that never landed. A watch that lists nothing on the first try is asked a second way, so a slow watch is not written off.

The device screen shows whether the watch has ever been synced and when it last was. If the link drops mid-sync, whatever arrived is still imported, but the run is reported as interrupted so a later sync retries the unfinished work.

The home screen carries a watch card showing the most recently synced watch with its battery, last sync time, and a sync button. While live readings are streaming (see below), the card shows the current heart rate and step count beside them. OpenVitals shows the same watch on its dashboard tile and can start a sync from it, through OpenLink, once OpenWatch allows it on the Connected apps screen.

### When A Watch Will Not Connect Or Sync

The link follows what Gadgetbridge does for watches that had trouble connecting or syncing (first reported on a Fenix 6):

- It writes to each characteristic the way the characteristic asks, with a response when the watch offers one, instead of always writing without one.
- A dial that Android's Bluetooth stack fails, such as GATT status 133, is retried after a short settle pause: up to three connection attempts in all. A watch that does not answer at all is not redialled.
- A whole sync may run for up to 30 minutes, enough for a first sync of a watch holding months of activities. The per-stage timeouts still end a stalled step much sooner.
- A watch that never sends its configuration still completes the handshake, from the capabilities record it pushes as FIT messages instead.

The device screen has a **Large Bluetooth packets** switch, on by default and kept per watch. Larger packets make a sync much faster; turning the switch off skips the request and keeps Bluetooth's default packet size, for a watch that keeps dropping the link or never gets a sync going.

### Automatic Sync

"Automatic sync", on the device screen of a Garmin or Wear OS watch, syncs the watch on its own every 30 minutes, hour, or two hours. Off by default, and set per watch, so two paired watches can be on different schedules or only one of them on a schedule at all.

A scheduled sync runs while the app is closed. Health Connect then lets OpenWatch read other apps' records only with its "Access data in the background" grant. Without it the watch's data is still saved, but what needs other apps' records is skipped for that run: a Wear OS sync writes no walking-calorie estimate and no resting heart rate, because it cannot see the weight, the workouts or another app's value for the day. (A scale weigh-in heard with the app closed is affected the same way: it uses the body profile typed in OpenWatch instead of the newest height and weight in Health Connect.) So switching Automatic sync on asks for that grant when it is missing, and the card keeps a line and an "Allow background access" button for as long as it is.

A scheduled run does exactly what tapping Sync does, with three differences:

- It is quiet. A run that could not reach the watch leaves no error on screen; the last sync time on the device screen and the watches list is what says whether the schedule is working. A watch out of range at 3am is not a fault to report.
- It does not linger on the link afterwards. A manual sync holds the connection open a few extra seconds so the watch can run its own errands, notably fetching weather; a scheduled one hangs up as soon as the files are in, because that time costs radio on both sides.
- It refuses rather than queues. If the radio is busy or a sync is already running, the run steps aside and waits for the next one. It retries a couple of times on a short backoff first, since a watch that just walked out of range is usually back within minutes.

The exact moment is Android's to choose, not the app's. The schedule is a floor, not an alarm: a run can arrive late, and the phone being in Doze, below its low-battery mark, or out of range of the watch all delay it. Granting the battery-optimization exemption the pairing checklist offers is what keeps overnight runs close to their schedule. The schedule survives a reboot and an app update without being re-armed.

Nothing about this changes what is written or where. The same importer runs, the same watermarks apply, and syncing the same day twice still does not double anything.

If "Stay connected" is also on, the held link is given up for the duration of the run and re-established afterwards, the same handover a manual sync uses.

### Recorded Activities

Activity files are parsed on the phone and imported in batches, so a single bad file does not stop the rest. The route keeps the altitudes the watch recorded: OpenWatch ships no elevation tiles. (OpenVitals can replace a route's altitudes with offline elevation data on its side.)

Activities are written to Health Connect as exercise sessions with their routes and series, and appear in OpenVitals and other Health Connect apps alongside data from any other source. A file is skipped when the Health Connect write permission it needs is missing.

### Wellness Data

Wellness files are split by where the data belongs.

Written to Health Connect, and therefore visible in OpenVitals and other Health Connect apps:

- Sleep sessions with stages, and naps.
- Heart rate series and resting heart rate.
- Heart rate variability: the nightly summary, and the five-minute values the watch records.
- Respiratory rate.
- VO2 max.
- Blood oxygen: the Pulse Ox readings the watch records, and the value from a Health Snapshot recording.
- Respiratory rate from a Health Snapshot recording.
- Basal metabolic rate.
- Steps, distance, and active calories through the day.
- Body weight, when the watch relays readings from a paired Garmin scale.

Kept in OpenWatch's own storage, because Health Connect has no record type for them: stress, Body Battery, intensity minutes, recovery time, training readiness, training load, functional threshold power, lactate threshold, hill and endurance scores, and the watch's own verdict on a night's sleep, restless moments included.

#### Estimated sleep stages

Some older watches, the Venu SQ for one, never record sleep stages. Garmin works those out on its servers, which OpenWatch never talks to. What such a watch does hand over is a heart rate and a movement count for every minute. OpenWatch keeps those minutes in its own storage for 45 days and estimates the night on the phone: when sleep began and ended, and a light, deep, REM and awake timeline.

- One session per night, written to Health Connect once the estimator finds at least three hours of sleep. It is rewritten in place as the night's files arrive over later syncs, so a night synced at 3am and again at 8am is one session, not two.
- The session's notes say the stages are estimated. They show when heart rate was low and steady and when it was high and variable, not what a sleep lab would see. The session starts at sleep onset, not bed time. Its end can be off by twenty or thirty minutes on a restless morning.
- Naps are not estimated. A watch that records its own stages is left alone; its stages always win.

Syncing the same day twice does not double anything. Files already imported are not downloaded again, Health Connect records carry a stable identifier so a record that does arrive twice updates in place, and watch-only measurements are keyed on the measurement and its instant.

#### The step, distance and calorie counters

These three are the awkward ones. The watch reports them as running daily
totals, so a file only says where the counter stood, never what happened. They
are accumulated across every file of a sync and differenced once at the end
against a stored watermark, which is what lets an interval record say *when* the
walking happened rather than drawing the day as one straight ramp from midnight.

Two properties are load-bearing, and both were learned the hard way:

- **Records never overlap** — not within a sync and not across two. A record's
  end follows the data, so a gap in the counters makes it end where the gap
  ends, past later slots on the 15-minute grid. A sync resuming inside one of
  those slots therefore starts its first record at the point it resumed, not at
  the slot's edge. This matters more than it sounds: Health Connect **discards
  the overlapping span when it aggregates**, so two records sharing a minute
  report less between them than either claims. A real day read 889 steps while
  its own records summed to 1,007.
- **A day differences from where the day before it ended**, per activity type,
  not from zero — the watch does not roll its counters over at local midnight.

A gap between the live step count and the synced total is normal and is not
either of the above: the live reading is the wrist's current number, and the
minutes since the watch last closed a monitoring file have not been handed over
yet. They arrive on the next sync.

### Heart rate zones on the watch

The watch's heart rate zones are the
American College of Sports Medicine's five intensity classes: very light, light,
moderate, vigorous and near-maximal (Garber et al., Med Sci Sports Exerc 2011;
43(7):1334-1359, doi:10.1249/MSS.0b013e318213fefb, table 4). With a resting heart
rate they are shares of the heart rate reserve (Karvonen): below 30, 30 to 39, 40
to 59, 60 to 89 and from 90 percent. Without one, shares of the maximum: below 57,
57 to 63, 64 to 76, 77 to 95 and from 96 percent.

After each sync the phone sends the watch the maximum and the resting heart rate
from the body profile, which OpenLink shares with OpenVitals. The maximum is the
one entered; else the highest heart rate Health Connect holds for the last year,
when it is at least 150 bpm and 60 above resting (OpenVitals' heuristic, no
published source); else 208 - 0.7 x age (Tanaka et al., JACC 2001,
doi:10.1016/S0735-1097(00)01054-8), 206 - 0.88 x age for women (Gulati et al.,
Circulation 2010, doi:10.1161/CIRCULATIONAHA.110.939249). The resting rate is the
one entered, else the latest night's. Until the phone has sent a maximum the watch
shows no zones rather than zones on a guess: the workout's heart page names none and
the heart rate detail counts no time in them.

OpenVitals is meant to work the zones out, since it holds more of the wearer's data, and to share
them over OpenLink; until it does, the profile's values it shares win, and the rest above is
OpenWatch's fallback.

## Watch Data Screen

The Data action on a Garmin watch opens the watch-only measurements: the things the watch makes that Health Connect has no place for. Everything else goes to Health Connect and is not repeated here.

Today:

- Stress, with today's average.
- Body Battery, with today's highest value.
- Intensity minutes, counting vigorous minutes double, against the 150-minute weekly target. The week starts on Monday.

Last night:

- Sleep score.
- Time awake.
- Times woken.
- Restless moments.
- Sleep need, compared with the usual nightly need when the watch reported one.

Training:

- Recovery time.
- Training readiness.
- Training load, showing the acute value with the chronic value beside it.
- Functional threshold power, in watts.
- Lactate threshold, in watts, with the heart rate it was reached at.
- Hill score, with its strength and endurance parts.
- Endurance score.

A footer names the measurements this watch did not send, so an absent row reads as "your watch does not report this" rather than "the app lost it". Until a first sync the screen is empty and says so.

## Notifications On The Watch

Phone notifications can be forwarded to a paired Garmin watch that supports it. The feature is off by default and does nothing at all until it is switched on: no reading, no Bluetooth link, no background work.

1. Open the watch, then Notifications, and turn on "Send notifications to the watch".
2. OpenWatch shows a disclosure explaining exactly what it will read and where it goes, and asks for confirmation.
3. It then opens Android's notification access screen. Access can only be granted there; OpenWatch cannot request it from inside the app.
4. Coming back to OpenWatch, the switch turns itself on once access has been granted.

### Which Apps Send

The list shows the apps installed on the phone. By default every app sends until it is switched off, so the list is a blocklist. "Only apps I choose" turns it into an allow-list: then only the apps switched on send. Each mode keeps its own list, so switching back finds the blocklist as it was.

"Hide sensitive content", on by default, holds back the content of a channel set in Android's notification settings to hide sensitive content on the lock screen (see below).

Some notifications never reach the watch regardless: ongoing and foreground-service notifications, group summaries, notifications an app marks as local to the phone or as secret, notifications from a channel set not to show on the lock screen, notifications with neither a title nor a body, minimum-importance channels, anything at all while the phone's own Do Not Disturb is on, and OpenWatch's own notifications.

A channel set in Android's notification settings to hide sensitive content on the lock screen sends only its title, or the app's public version where it gives one, and none of its actions: an action's label can carry what was held back. Every other notification arrives whole, locked phone or not; Android's own default for a notification is to show it in full. Android gives apps no mark for a one-time code, so the channel's lock-screen setting is the signal; Android 12 and newer let an app read it, and before that only an app's own secret mark is honoured.

### Calls

A ringing call is the one ongoing notification that is forwarded. It arrives as the dialer's own notification, so no phone permission is needed and nothing reads the call log. The watch shows it as an incoming call with the caller as the title. Once the call is answered, or when it was placed from the phone, it leaves the wrist; only ringing is shown. A dialer that uses Android's call-style notification hands over its answer and decline buttons, and the watch draws them in its fixed places. An older dialer's buttons arrive as ordinary labelled actions.

### Acting From The Wrist

Dismissing on the watch clears the notification from the phone. Where the posting app publishes them, a reply action and up to five of the app's own buttons are offered. Actions that would merely open a screen on the phone are not offered, because Android does not let a background app launch them; the button would report success and do nothing.

A watch that does not recognise the posting app asks the phone what it is called, and the phone answers with the app's name. Left unanswered, such a notification never appears.

### The Link

While forwarding is on, the link to the watch is held open for as long as the watch is in range, which is how Garmin watches expect a phone to behave. If the watch goes out of range the link is re-established with a backoff, and anything that arrived while it was away is delivered when it returns.

## Staying Connected

Notification forwarding holds the link only while it has work. "Stay connected", on the watch's device screen, holds it always: whenever the watch is in range, the phone keeps the connection open, the way Garmin's own app behaves. Android's companion-device presence wakes OpenWatch when the watch comes back into range, so the link returns promptly rather than on a retry timer.

On by default — a held link is what the features below ride on, and a watch that behaves as its own app would is the expected thing rather than the surprising one. It costs battery on both sides, so the switch is there; turning it off is remembered, and a later version's default will not undo that.

### Live Readings

With Stay connected on, a second switch streams the watch's live heart rate and step count to the phone. The current value appears on the watch's device screen and on the home screen's watch card ("86 bpm live"). The values live in memory only and disappear when the link drops. Nothing live is ever stored; the same measurements arrive later through the normal sync, with the watch's own timestamps.

Off by default, because an open stream spends the watch's battery.

## Weather On The Watch

The watch's weather glance asks the phone for weather, and OpenWatch answers from a weather app on the phone, not from the internet. Any app that broadcasts the Gadgetbridge generic-weather format works; [Breezy Weather](https://github.com/breezy-weather/breezy-weather) is the tested one (enable its Gadgetbridge broadcast and add OpenWatch to its recipients). The snapshot is considered fresh for six hours. Any app on the phone can send this broadcast, so the receiver caps what it reads: 512 KB after unzipping, 16 levels of nesting, 72 hours and 16 days of forecast. A payload past a cap is dropped, and one that comes within a minute of the last kept is too. The sender is not checked: Android names a broadcast's sender only when the sender opts in, and weather apps do not.

The location the watch shows is the weather app's location. OpenWatch also answers the watch's own position asks from the phone's last known location, which is what arms the glance in the first place.

Known limitation: on the model verified against, the glance arms (it stops saying "Reconnect to phone") but does not always fetch.

## Find My Phone

Works in both directions. The Find action on the device screen makes the watch alert; the watch's own find-my-phone feature makes the phone ring at alarm volume, with a notification to stop it. The phone rings even when silenced, because a phone lost in a couch cushion is the whole point.

## Music Controls On The Watch

"Music controls on watch", on the watch's device screen, puts the phone's player on the watch's music controls. Off by default. The watch shows the player's name, the track, artist and album, and how far into the track playback is. From the wrist the wearer can play, pause, skip to the next or previous track, skip forward or back within one, and change the media volume.

Android shows the phone's players only to an app with notification access, the same grant notification forwarding uses. Switching the feature on without it opens Android's settings, and the card says so while the grant is missing. The player followed is the one Android ranks foremost: the one that is playing, when any is. With no player running, a play button on the wrist acts as a headset button does and wakes the last one.

Everything rides the held link, so it needs "Stay connected". The watch asks which commands the phone takes once per connection, so switching the feature reconnects the watch. Track details go to the watch over Bluetooth and nowhere else, and are never stored. A player's steady position reports are not forwarded: the watch runs its own clock, and only a change, a pause or a seek is sent.

## CoMaps Guidance On The Watch

"CoMaps guidance on watch", on the watch's device screen, puts the turn-by-turn guidance CoMaps is giving on the wrist. Off by default, and complete in itself: OpenWatch reads the guidance from CoMaps directly, so nothing else has to run. Switching it on asks for CoMaps' own permission, and the card says so if that grant is declined or later revoked. Set a route in CoMaps and the turns reach the watch. (OpenVitals reads the same guidance for its own turn strip while it records an activity; see its [CoMaps navigation context](https://github.com/Hale-MTU/OpenVitals/blob/main/docs/features/comaps-navigation-context.md).)

Garmin watches have no turn-by-turn channel a phone can drive, so the guidance travels as a notification: the next manoeuvre as the title ("Turn left", "Roundabout, exit 3", "Arrive at destination"), the distance to it as the subtitle, and the street, the distance left and the time left as the body. One notification is added when guidance starts and updated in place from then on, so the watch does not buzz at every fix; it is withdrawn the moment guidance stops — the route ended, the toggle went off, the watch was forgotten — so a finished route never lingers on the wrist.

A new manoeuvre or street reaches the watch at once. A countdown that merely ticked down is refreshed at most every five seconds, and a reading that says nothing new is not sent at all. Distances and times are shown as CoMaps formatted them, so the wrist and the phone always agree on units. The notification rides the same link forwarded phone notifications use and needs nothing more than a paired Garmin watch: no notification access, no Stay connected.

## Calendar On The Watch

"Calendar on watch", on the watch's device screen, feeds the watch's calendar glance from the phone's calendar. Off by default; switching it on asks for Android's calendar permission.

The watch asks for a window of events and names its own limits (how many events, how long each field may be), and the phone answers within them. Recurring events arrive as their occurrences, declined and cancelled meetings stay off the wrist, and all-day events land on the wearer's midnight. Events go to the watch over Bluetooth and nowhere else; they are never stored and there is no network to send them over.

If the calendar permission is later revoked in system settings, the row says so, and the watch's asks are answered with an empty calendar rather than ignored.

## GPS Ephemeris

Ephemeris, a few days of predicted satellite orbits, is what turns a minutes-long cold GPS fix into a seconds-long one. Garmin's own app downloads it from Garmin silently; OpenWatch has no internet access and does not grow any for this. Instead, the user downloads the file, imports it on the watch's device screen, and the phone hands it over when the watch asks.

The imported file is recognized by its contents (a constellation archive, an rxNetworks blob, or a Sony CPE blob; which one a watch wants is decided by its GPS chipset), and the URL the watch asked for is shown on the screen, since that URL is the only way to know which format to fetch. A stale file is refused rather than served: an out-of-date orbit prediction is worse for the watch than the almanac it already has.

## Send A Point

A Garmin watch that can store locations gets a "Send a point" row. The form takes a name and a position, and the watch keeps the point in its saved locations, where it can be picked as a navigation target. It is meant for a position found on the phone that is wanted on the wrist: a geocache, a trailhead, a parked car.

The coordinates field reads what people actually paste:

- decimal degrees, such as `48.8584, 2.2945`, with a point or a decimal comma;
- degrees and decimal minutes, the geocaching format, such as `N 48° 51.504 E 002° 17.670`;
- degrees, minutes, and seconds.

South and west are a minus sign or a hemisphere letter, never both. Only N, S, E, and W are letters: `O` is west in Spanish and east in German, so it is refused. The field shows the position it read, or says what is wrong, before anything is sent.

A maps app can hand a place over instead. OpenWatch appears as "Send to watch" for a `geo:` link and in the text share sheet, and reads `geo:` links, Google Maps, OpenStreetMap, and OsmAnd URLs, and bare coordinates in the text. A short link such as `maps.app.goo.gl` holds no position, and the app has no internet access to resolve it; the form opens with the name filled in and says the coordinates are missing. A shared place only fills the form. Nothing goes to the watch until Send is tapped.

The point travels as a small FIT location file over a link opened for that one send. Each way it can fail has its own message: the watch already has the point, has no room, does not accept points, or stopped answering. A point with no name is named by its position.

## Settings On The Watch

A Garmin watch that reports a settings tree gets a "Settings on the watch" row, plus a direct Alarms action.

None of these menus are built into OpenWatch. The watch sends its own menu — screens, rows, choices, and current values, already in the language the watch is set to — and the app renders what arrives. A screen OpenWatch has never seen still works, and nothing needs updating when the watch's firmware changes.

Switches, option lists, times, and sub-screens can be changed, and every change applies to the watch itself and is read back to confirm it. Rows the phone cannot act on are shown rather than hidden, because seeing a greyed row says the watch has the setting and the app cannot reach it, which is true.

When the watch cannot be reached, refuses a change, or does not answer, the screen says which of those happened instead of claiming the change worked.

## Alarms Without A Settings Tree

An older Garmin watch, such as the first Instinct, reports no settings tree, so none of its settings can be shown or changed. Alarms are the one exception: its Alarms action opens a list kept on the phone.

The list holds up to ten alarms. Each has a time, the days it repeats on or none for a single ring, a sound or vibration choice, the backlight, and one of the watch's preset labels. Edits are saved on the phone at once. Nothing reaches the watch until "Send to watch" is tapped, and the screen says when the list has changes the watch does not have.

A send travels as a small FIT settings file over a link opened for that one send, and it replaces every alarm on the watch. The watch's own alarms cannot be read, so an alarm set on the watch does not show in the list and is lost on the next send.

## Find My Watch

A watch that reports the capability gets a Find action. It makes the watch alert so it can be located, for about a minute by default, and the same button stops it early. If the watch never answers, the screen says so and suggests bringing it closer.

## One Radio At A Time

Sync, find, sending a point or the alarm list, settings on the watch, music controls, and notification forwarding all speak to the same watch over the same Bluetooth link, and only one of them can hold it.

- A user-initiated action asks for the link and waits a few seconds for whatever holds it to let go. Notification forwarding, the usual holder, gives it up on its next check and resumes afterwards.
- If the link cannot be taken in time, the action reports that the watch is busy and suggests trying again in a moment.
- Sync, Alarms, Find, and sending a point are disabled while a sync, a find, or a send is already running.

Different devices do not contend with each other, so the scale is unaffected by what a watch is doing.

## Privacy

Nothing leaves the phone. The watch is read over Bluetooth, the files are parsed on the device, and the results go to Health Connect or to OpenWatch's own local database. A Samsung source on a Wear OS watch talks to the Samsung Health Platform on the watch, a Samsung service; what that service sends and how to keep it quiet is in [samsung-health-and-privacy.md](../engineering/samsung-health-and-privacy.md). The phone keeps no copy of a Wear OS watch's data: the watch is the store, for a week.

Notification text is read on the device, held in memory only while it is needed, and sent only to the paired watch. It is never written to a file or a database. Turning the feature off, or revoking notification access in Android settings, stops it immediately.

Calendar events follow the same rule: read only while answering a watch that asked, held in memory only, sent only to the watch, never stored. Weather comes from a weather app on the phone and goes only to the watch; OpenWatch itself never talks to a weather service. What the phone is playing is read only while music controls are on for a paired watch, goes only to that watch, and is never stored.

See [Privacy](../../PRIVACY.md) and [Permissions](../app/permissions.md) for the full boundary, including why the companion association is asked for, why it is optional for a Garmin watch and why a Wear OS watch needs it.

## Known Limitations

- Verified end to end against one Garmin model. Other recent models are expected to work, but are untested.
- Older single-link transport watches cannot sync.
- The Connected and Not connected labels reflect whether the watch is switched on in OpenWatch, not whether a Bluetooth link is open right now.
- Wear OS watches sync heart rate, steps, floors, an estimated sleep session per night and per nap, the night's resting heart rate, and, from the night measurements, HRV, the breathing rate (on unless switched off), SpO2 and skin temperature, by hand or on the automatic schedule. Workouts recorded on the watch are written as sessions with their route. Notification forwarding, find and sending a point are Garmin-only.
- Music controls are confirmed on one Garmin model. A volume change made on the phone reaches the watch only with the next player change.
- Sending the alarm list has not yet been confirmed on a watch. Whether an empty list clears the watch's alarms is unknown.
- Sending a point has not yet been confirmed on a watch. Only one point is sent at a time, and points already on the watch cannot be listed, edited, or removed from the phone.
- Health Snapshot values only exist if a Health Snapshot has been recorded on the watch.
- Battery percentage is read during a sync and shown on the device screen, the home screen's watch card and OpenVitals' dashboard tile; charging state is not read.
- The weather glance on the verified model arms but does not always fetch; see Weather On The Watch.
