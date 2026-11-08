# Architecture

## Purpose

OpenWatch is a phone app that owns the watch, a watch app, and the link between the two.
This document is the map of the phone app and the rules that hold across it. The device layer
came from OpenVitals unchanged where it could; the sections below are its sections, with the
OpenWatch seams noted.

## Current Snapshot

- App namespace: `tech.mmarca.openwatch`
- Project shape: four Gradle modules. The phone app is `:app`; `:domain` holds the pure code
  the app depends on; `:wear` is the Wear OS watch app with its own CI gate; `:wearlink` is
  the pure-Kotlin link both apps depend on. Package names are the same across `:app` and
  `:domain`.
- Dependency wiring: Hilt in `:app`, rooted at `OpenWatchApp`; modules are `di/AppModule.kt`,
  `di/RepositoryModule.kt` and `di/DevicesModule.kt`.
- UI stack: Jetpack Compose + Material 3 + Navigation Compose + `ViewModel` + coroutines.
- Health data backend: the Health Connect AndroidX client, behind
  [`HealthConnectGateway`](../../app/src/main/kotlin/tech/mmarca/openwatch/healthconnect/HealthConnectGateway.kt).
- No database on the phone: the device registry and the stores are `SharedPreferences`. The
  watch is the store for what it records.
- One integration: Wear OS. The Garmin and Xiaomi scale code that came from OpenVitals was
  removed on 2026-11-07.

### Top-Level Package Map

| Package | Role |
| --- | --- |
| `devices/` | The device layer (below). No Compose, no navigation, no Hilt module. |
| `features/home` | The device list, the watch tile, and the way to the other screens. |
| `features/watches` | The watch list, the add flow and the watch's device screen. |
| `features/healthconnect`, `features/settings` | Health Connect setup, the sync switch, the body profile. |
| `healthconnect/` | `HealthConnectGateway`, the sync gate, intents and availability. |
| `data/repository` | The device registry, the repositories and the SharedPreferences stores. |
| `sensors/ble/` | The BLE discovery scan the add-watch flow uses, and the GATT UUIDs. |
| `core/` | `ScreenError`, `UnitFormatter`, the OS permissions service, the dispatcher provider and the foreground gate. |
| `navigation/`, `ui/` | The routes, the app bar state, the theme and the few shared components. |

## The OpenWatch Seams

Where the copied code met an OpenVitals class that did not come along, it meets this instead:

| OpenVitals | OpenWatch |
| --- | --- |
| `HealthConnectManager`, `HealthRepository` | `HealthConnectGateway`: the granted set (cached thirty seconds, dropped on a return to the foreground), `readsOtherAppsDataNow`, inserts and deletes by client id, a page reader, the exercise session ranges, the latest weight and height. Reads throw on failure. |
| `AppleHealthImportRepository` | `ImportedRecordRepository`, the same three methods. |
| `BodyRepository.resolveBodyProfile` | `BodyProfileProvider`: the declared profile with Health Connect's weight and height on top. |
| `PreferencesRepository` | `OpenWatchPreferences`: the sync switch, the body profile, the fallback exercise type, setup done. |
| `ActivityRecordingController` | Gone: OpenWatch records no activity, so a sync is never refused for one. |
| Body Energy, home widgets | Gone: OpenVitals derives those from the records OpenWatch writes. |

## Device Layer

`devices/` is a layer, not a feature. It owns everything that talks to a physical device over Bluetooth and exposes ports that features consume. It has no Compose code and no navigation. The user-facing screens live in `features/watches`.

### `devices/core`

Vendor-neutral ports, so the watch screens never name an integration.

- `DeviceClassification.kt` — the `DeviceClassifier` function interface; the first non-null classification wins, otherwise the device is a plain sensor the add-watch flow does not list.
- `core/pairing/` — `WatchPairingPort` (bond, unbond, associate, disassociate), its `BleWatchPairing` implementation, `CompanionDevicePairing` around `CompanionDeviceManager`, and `OpenWatchCompanionDeviceService`. The service exists for its binding: the OS raises the process priority while an associated watch is in range.
- `core/sync/DeviceSyncPort.kt` — `canSync(device)` plus a progress-reporting `sync(...)`. `di/DevicesModule.kt` binds it to `WearOsWatchSyncService` directly; a second integration would bring back a dispatcher over several ports.
- `core/sync/EstimatedSleepRecord.kt` — `sleepNightWindow` (18:00 the evening before to 14:00) and `estimatedSleepSessionRecord`, the Health Connect shape of a night `domain/insights/SleepStageEstimator` produced from per-minute movement and heart rate.
- A watch sync writes Health Connect records through `ImportedRecordRepository.insertImportedRecords`, the same deterministic-`clientRecordId` path the Apple Health importer uses, so a re-import upserts instead of duplicating.

### `devices/wearos`

A Wear OS watch is a registered device plus a Bluetooth Classic link to the OpenWatch watch app (`:wear`). There is no vendor protocol to speak: the watch runs our own app, and the phone talks to it over an RFCOMM socket on AOSP APIs only.

- `WearOsDeviceClassifier` / `WearOsDeviceNames` — claims a scanned device as `(WEAROS, WATCH)` when its name is a known smartwatch family or its SDP record lists the OpenWatch app UUID. A wrist-watch Bluetooth class alone is not enough.
- `OnboardWearOsWatchUseCase` — a single optional `ASSOCIATING` step, no bond and no probe; the device is registered with no capabilities. The bond itself comes from Android's Bluetooth settings, or from the vendor's onboarding.
- `WearOsNodePort` — the port: pairing and reachability of the watch app. `WearOsAppStatus` is `NOT_PAIRED`, `NO_ANSWER` or `APP_RUNNING`; a bond alone is never shown as connected.
- `BluetoothWearOsNodePort` — the implementation. `WearOsBondMatcher` finds the bonded Classic entry for the registered watch (exact address, then name with a trailing ` LE` stripped: the scan address is a BLE private address), then one `PING`/`PONG` exchange with an eight-second watchdog. RFCOMM is Classic, so no radio lease is taken.
- `WearOsCompanionManager` — the thin facade the watch screen calls.
- The line protocol itself lives in `:wearlink` (`WearLinkProtocol`), compiled into both apps: `PING`/`PONG`; `HR_SINCE <epochMillis> <limit>` answered with `HR <epochMillis> <bpm>` lines; `SM_SINCE <epochMillis> <limit>` answered with `SM` lines, one per clock minute, nineteen integer fields defined in [sleep-minute-features.md](sleep-minute-features.md); `ST_SINCE <epochMillis> <limit>` answered with `ST <minuteStartMillis> <steps>` lines, asked only of a watch whose hello announces `st`; `AS_SINCE <epochMillis> <limit>` answered with `AS <minuteStartMillis> <decimetres>` lines, the height climbed, asked only of a watch that announces `as` (builds that sent whole floors announced `fl` and are not asked); `HR_LIVE <seconds>` (at most 10, below the watch's 15-second idle timeout) answered with an `HR` line per reading as the sensor takes it, asked only of a watch that announces `hl`, and answered at once with none while the watch is off the wrist; each reply ends with `END <count> <more>`. One request per connection.
- `WearOsWatchSyncService` — the `DeviceSyncPort` for Wear OS. Heart rate first: pulls pages from the watch's cursor (`WearOsSyncCursorStore`, the newest sample time per device), writes each page through `ImportedRecordRepository.insertImportedRecords` before asking for the next, and advances the cursor after each write, so a sync that dies mid-way resumes where it stopped. Then steps, the same way, except that each pull takes the cursor's minute again, because the watch adds late steps only to its newest minute; that minute is written apart from the rest, so an unchanged one does not make the batch fall back to one record at a time. The step pull also reaches back to the minute before the floors cursor, for the floors filter, without writing those minutes again. Then ascent, the same way, written as floors and filtered by the step minutes of the same sync. Without the Health Connect grant to write steps, steps and floors stay on the watch and the sync goes on. Then sleep: pulls the minutes from the start of the night the sleep cursor sits in (`WearOsSleepImport.pullStart`), estimates every night those minutes touch and writes one `SleepSessionRecord` per night, skipping a night whose window already holds a session from anywhere else (the vendor's app, the user's own entry), then moves the sleep cursor. Refuses while an activity recording holds the foreground slot. `Succeeded.fileCount` carries the heart rate sample count, `Succeeded.stepMinuteCount` the minutes of steps.
- `WearOsHeartRateImport` — pure mapping: one `HeartRateRecord` per clock hour, keyed `wearos_hr_<first sample millis>`, so a re-pull upserts. The watch keeps a week of samples and prunes on its own; nothing is deleted on the phone's behalf.
- `WearOsStepsImport` — pure mapping: one `StepsRecord` per minute, keyed `wearos_steps_<minute millis>`, with the count as `clientRecordVersion`, so a re-pulled minute that grew replaces its record and one that did not is skipped (`data/repository/insertSkippingPresent`; the phone-to-phone sync uses the same `insertWithOutcomes`). The same minutes become a `StepsCadenceRecord` each (`wearos_cadence_`) and, when a body weight is known (Health Connect's latest, else the body profile's), an `ActiveCaloriesBurnedRecord` each (`wearos_walk_kcal_`, versioned on the step count like the others, so the estimate is frozen at first write and a later weight or session does not revise it) from `domain/insights/WalkingEnergy` (in `:domain`): Moore et al. 2021's cadence equation, net of one resting MET. A minute inside any exercise session in Health Connect gets none, so a recorded walk's own active calories are not counted twice. The weight is read once per sync, and only when a minute costs more than rest; without one the walking energy is skipped and `DeviceSyncResult.Succeeded.missingBodyWeight` lets the sync card say so. The write grants for steps (Health Connect files cadence under it) and active calories, and the exercise read grant the walking energy filters with, are checked before the pull: a withheld one skips its series, never failing the sync, `DeviceSyncResult.Succeeded.unwritten` (`UnwrittenSeries`) tells the sync card which series to name, and `withheldPermissions` which grants its button asks for. A minute is estimated when its steps are written (new or grown); one synced without the grant, the weight or visible sessions (in the background without the background-read grant, `HealthConnectGateway.readsOtherAppsDataNow`, or when the session read fails) keeps no estimate. Cadence and walking energy follow the steps insert: a minute Health Connect already held is skipped, except past the step cursor, where only a sync that failed after its steps leaves minutes, so they are tried again. The energy grants and the weight hint are reported only when a pulled minute outside a recorded workout would have had walking energy; without `READ_WEIGHT` the result says so (`weightReadWithheld`) and lists the grant. The weight and the sessions (`HealthConnectGateway.readExerciseSessions`) are read under `withStrictHealthConnectReads`, so a failed read skips the estimate rather than passing for "none". Cadence and walking-energy writes are optional: a failure is logged and skipped, never failing the sync. Every minute record carries the zone offset in force at its minute. It also stays put when the exercise sessions cannot be seen: in the background without the background-read grant (`HealthConnectGateway.readsOtherAppsDataNow`), where other apps' sessions and weights are invisible, or when the session read fails.
- `WearOsFloorsImport` — pure mapping: one `FloorsClimbedRecord` per minute, keyed `wearos_floors_<minute millis>`, the credited metres over 3 m with the fraction kept, and one `ElevationGainedRecord` of the same credited metres, keyed `wearos_elevation_<minute millis>`; both versioned by the credited decimetres. Height counts only with 10 steps per floor's height in its minute and the one before, so elevator rides and weather drift are dropped; the threshold is an app heuristic. The rule is `AscentCredit` in `:wearlink`, so the watch app counts floors the same way.
- `WearOsSleepMinuteMapping` — the wire row (`WearLinkProtocol.SleepMinute`, integers) into `domain/model/WearSleepMinute` (physical units, `WearMinuteFlags`).
- `WearOsSleepImport` — pure: the nights a pull touched, each night's rows inside its window, the night through `domain/insights/WearSleepEstimator`, and the record through `estimatedSleepSessionRecord`, keyed `wearos_sleep_est_<night>` with the sync time as version so the later estimate replaces the earlier. The phone stores no minutes: the watch is the store and a whole night window is re-pulled to re-estimate it.
- `WearOsRestingHeartRateImport` — pure: one `RestingHeartRateRecord` per estimated night, keyed `wearos_rhr_est_<night>` with the sync time as version, stamped at the night's end, from `domain/insights/NocturnalRestingHeartRate` (the lowest 30-minute mean of the per-minute heart rate between onset and end, at least 20 minutes with a reading; the rule Dial et al. 2025 report for Garmin). No value while the night is in progress. The sync writes it only when the calendar day of the night's end holds no resting heart rate from elsewhere (the day the app averages), removes its own when one turns up later, replaces a stale version like the session, and fails closed when the read fails, the grants are missing (`UnwrittenSeries.RESTING_HEART_RATE`) or other apps' data is unreadable in the background.

The Wear OS night itself is estimated by pure objects in `domain/insights`, in the order the actigraphy literature settled on: `WearMinuteGrid` (the rows on a one-minute grid, missing minutes absent), `WearStateDetector` (worn or not per minute: charging, off-body, a pulseless run while heart rate is recorded, then van Hees' still-hour rule vetoed by any pulse in the hour; a not-worn minute is a gap to everything after it), `SptWindowDetector` (the night's window: HDCZA on the z-angle change, tightened at its end by the heart rate quantile window), `SleepWakeScorer` (one linear score of Cole-Kripke movement, heart rate volatility after Walch, the z-angle range and the median small z-angle drift, then `WebsterRescoring`), and `SleepStageEstimator.estimateWindow` for the stages, with `Lids` as a prior behind `lidsWeight`. `WearSleepEstimator` strings them together and returns the night with its window and worn counts. The per-minute features are specified in [sleep-minute-features.md](sleep-minute-features.md).

On the watch, `WearAppService` hosts the RFCOMM listener and the recorders, and all write to one `MetricStore`: a single plain-SQLite table keyed by metric and time whose rows are the values' `:wearlink` protocol lines, so the format is defined and tested in one place and the store holds exactly what the phone receives. Each series is a `WearMetric` in `WearMetrics`, which names its key (the capability the link announces), its line codec, whether a second value at the same time is kept or replaces the first, and its retention (a week). The link's request handler pages any metric with one generic function. Adding a metric, one pull request per metric, is: its line and request in `:wearlink`, a `WearMetric`, a recorder that calls `MetricStore.put`, and the phone's pull; the store, its pruning and its tests (`MetricStoreTest`, instrumented, run on a watch) do not change. `LegacyStoreImport` moves the per-metric databases of earlier builds into the store once, at service start. `HeartRateRecorder` is a batched `TYPE_HEART_RATE` listener that keeps one sample per ten seconds and hands each stored sample and each no-contact reading to the minute recorder. `StepRecorder` is the non-waking `TYPE_STEP_COUNTER` at the heart rate's report latency, or its waking variant, where the watch has one, while the heart rate is not running and nothing of ours would wake the processor; `StepMinuteCounter` (pure) turns its running total into one row per clock minute, spreading the difference between two readings over the minutes between them, at most the hour before the newer reading (of a longer gap only that hour's share, at the gap's pace), and never writing before the newest minute. A batch is written once, a second after its last event, together with the last reading in one transaction (`MetricStore`'s state table), so a restart neither loses nor double-counts steps. On a new boot the counter starts again at 0, so its first total is credited from the boot time. `AscentRecorder` reads the barometer (`TYPE_PRESSURE`, no grant) once a second at the same latency; `AscentMinuteCounter` (pure) averages 10 seconds, converts to altitude and counts a rise once it reaches 3 m above the last low point, then with all of its metres, in decimetres, one row per clock minute. The 3 m dead band keeps the barometer's wobble of about a metre from adding up. Each recorder starts only when the watch has its sensor and the grant is in (`ACTIVITY_RECOGNITION` for steps). `SleepMinuteRecorder` keeps one row per clock minute: `MinuteAggregator` (pure) and `AccelerationMinuteFeatures` compute the specification's fields from the batched accelerometer in g, the heart rate samples, the off-body sensor, the charger broadcasts and screen wake-ups. `WornMonitor` is the one owner of the off-body sensor: it hands each reading to the minute recorder and stops `HeartRateRecorder` after 60 seconds off the wrist, starting it again at once when the watch is back on (`WornGate`, pure). Because the off-body sensor may depend on the heart rate sensor on some watches, a screen wake-up or a pulled charger runs the heart rate for another 60 seconds, enough for an "on" to arrive. The accelerometer keeps running: it is cheap, and its events close the minutes. A watch without an off-body sensor keeps its heart rate on. While the heart rate rests, `StepRecorder` switches to the waking step counter; off the wrist it reports nothing, so it wakes nothing. `LiveHeartRate` serves `HR_LIVE`: each window registers its own unbatched heart rate listener and drops it at the end, only while `HeartRateRecorder` runs, and stores nothing. On the phone, `WearOsCompanionManager.liveHeartRate` holds one connection for up to 8 minutes, asks window after window, and reconnects 15 seconds after the watch reports none; `WatchDeviceViewModel` shares it `WhileSubscribed`, so it runs only while the watch's device screen is open. The accelerometer needs no permission and shares the heart rate's report latency, so the two batches arrive in one wake-up. That is not enough on the Galaxy Watch8: its accelerometer cannot wake the processor and buffers 48 seconds, and the hub lets a still arm sleep for minutes, so most of a night's movement was lost. While bedtime mode is on (`BedtimeMonitor`: Samsung's `setting_bedtime_mode_running_state`, falling back to Do Not Disturb) and the watch is worn and off the charger, the recorder holds a partial wake lock, renewed once a minute with a ten-minute timeout (`BedtimeHold`); the rest of the day it holds nothing. `NightHrvRecorder` measures heart rate variability at night: while bedtime mode holds the processor and the last ten minute rows show a still arm, it listens for five minutes, at most every 30 minutes, to an `HrvWindowSource`: Samsung's raw PPG (`PpgWindowSource`, `com.samsung.sensor.hr_raw` under the heart rate grant, unbatched), whose beats `PpgBeats` finds, or, chosen on the status screen of a watch that has the Samsung Health Platform, the platform's own intervals (`SamsungIbiSource`; see `samsung-health-and-privacy.md`); `HrvRmssd` (pure) turns either into an RMSSD, and the window is stored as one `HV` row only once the minute rows that cover it show the arm stayed still (`NightHrvSchedule`). A watch without the sensor does not announce `hv`. The phone writes each window as a `HeartRateVariabilityRmssdRecord` (`WearOsHrvSync`), keyed on its start; without the write grant the windows stay on the watch and the sync card asks for it. `HeartRateRecorder` runs at the plan `WearAppService` gives it (`HeartRateSchedule`, pure, from OpenVitals): the full rate in bedtime mode, otherwise the day setting on the status screen (`WearSettings`): every 10 seconds, batched, or power save, a window about every 10 minutes on `AlarmManager.setAndAllowWhileIdle` (`HeartRateAlarmReceiver`) that closes at the first usable reading. Only the full rate from the sensor counts as dense for the sleep minutes and keeps the step counter non-waking. On a watch with the Samsung Health Platform the heart rate can come from it instead (`SamsungHeartRateFeed`, see `samsung-health-and-privacy.md`). Debuggable builds keep a measurement protocol (`DebugProtocol`, `DebugSnapshots`): a JSON line at each battery percent and each screen, Doze, charger or plan change, with per-sensor delivery counts, plus events from the heart rate, the Samsung feed and the HRV windows; `scripts/wear-debug-report.py` pulls it and prints the drain per state and the source answers. `PpgRawLogger` is a research spike for debuggable builds only: a long press on the status screen dumps Samsung's raw PPG sensor to a CSV; nothing depends on it, and the host-side inspection script was dropped in 2026-10. The service's foreground type is `connectedDevice`, plus `health` once the heart rate or step grant is in. Android 16 gates the sensor behind `android.permission.health.READ_HEART_RATE`, not `BODY_SENSORS`; `WearPermissions` picks the right one per release.

The watch's tiles (Heart rate, Steps, Floors) are `MetricTileService` subclasses in `MetricTiles.kt`. They only read `MetricStore`, on the IO dispatcher, and start no sensor. `TileDay` (pure) cuts today from local midnight to the next into quarter hours on an elapsed-time axis, so a 23- or 25-hour day keeps its hour marks where those hours fell and the line ends at now; the floors go through `AscentCredit.creditedPerMinute` in `:wearlink`, the rule the phone's `WearOsFloorsImport` uses, so both count alike. `TileChart` draws the day's curve into a PNG, styled like the phone's `BodyEnergyPlot`. A tile names the PNG's hash and size as its resources version and keeps the PNG under that name in the cache directory (the four newest per tile), so `onTileResourcesRequest` serves exactly that image; when it is gone, the service asks Wear OS for the tile again instead of drawing a different one under the old version. Two choices are deliberate, both from a Wear OS 4 watch: a plain `TileService` with its own resources request rather than `Material3TileService`, whose images inside the tile never reached that renderer, and the value in a plain font rather than the Material 3 display and numeral styles, whose variable font made the renderer drop the text. The chart is a PNG of unspecified format, not raw ARGB pixels, because the latter need a newer renderer than Wear OS 3.

The UUID and the protocol words come from `:wearlink` on both sides. Verified on 2026-10-07 with a Galaxy Watch8 and a phone that never ran Galaxy Wearable: the watch accepted a plain second-phone bond, and the ping answered.

### Hilt wiring

`di/DevicesModule.kt` is the only module for this layer. It binds `BleWatchPairing` to `WatchPairingPort`, `WearOsWatchSyncService` to `DeviceSyncPort` and `BluetoothWearOsNodePort` to `WearOsNodePort`. Everything else is constructor injection. There are no `@Module` declarations inside `devices/` itself; keep it that way. `DevicesLayeringTest` fails on one.

The BLE discovery scan the add-watch flow uses lives in `sensors/ble`, trimmed to discovery: OpenWatch streams no live sensor.

## Module `:domain`

The app-local code that nothing in the rest of the phone app sits below, in `domain/`: the packages `domain/model`, `domain/insights` and `domain/preferences`. Package names are unchanged, so an import does not say which module a class is in. `:app` depends on it; `:wear` does not.

It is an Android library, not plain Kotlin: `SystemUnitSystemProvider` uses the platform's locale data, and Android lint then still checks it. It has the app's build types (`ci`, `nightly`), so the app's `ci` variant takes the module's `ci` variant and the module's tests and lint run on that one compile. Nothing in it is injected: it holds no Hilt module, and its classes are constructed by the app's repositories and the app's `di/` modules.

Its test fixtures (`testFixtures`) hold the wear sleep fixtures that both its own tests and the app's Wear OS sync tests use.

The module exists for the build: the app's lint and tests no longer cover these lines, and a change elsewhere leaves this module's compile, lint and test results in the build cache. What does not belong here: anything with an inbound dependency from `data/`, `healthconnect/` or `features/` (`domain/query` and `domain/usecase` stay in `:app` for that reason), resources, and Hilt wiring.

## Shared module `:wearlink`

The phone-to-watch link, in `wearlink/`, pure Kotlin on the JDK with no Android dependency, compiled into both `:app` and `:wear`. It exists because both apps need exactly the same protocol and the copied file it replaced was held in sync only by a test. Package `tech.mmarca.openvitals.wearlink`:

- `WearLinkProtocol` — the line grammar over the RFCOMM socket: words, limits, the `SM` row type and every format and parse function. The `SM` fields are specified in [sleep-minute-features.md](sleep-minute-features.md).
- `WearLinkLines` — the framing, defined once for both ends: UTF-8 lines, `\n` terminated, a trailing `\r` tolerated, a line over 512 bytes closes the connection.
- `WearLinkToken` — the secret a phone presents on every connection: 32 random bytes as Base64, compared in constant time. The bond says the devices know each other; the token says this app on this phone is the one the wearer allowed. The phone keeps one per added watch (`WearOsLinkStore`, keyed by the address the watch was added under, since pairing again can list the bond under another address) and drops it when the watch is removed; the watch keeps one per phone address. A known phone presenting another token, as after a reinstall, is asked about again like a new one, and allowing it replaces the old token.

Its tests run in both gates (`verifyCiUnitTest` and `verifyWearCi` depend on `:wearlink:test`), its lockfile is `wearlink/gradle.lockfile`, and the two ratchet tests hold its files to the same limits as the phone app's. `ArchitectureDocTest` fails when a Gradle module is missing from this document.

## Cross-Cutting Rules

These rules hold app-wide. Breaking one is not a local decision.

### 1. No foreground service on the phone

A watch sync runs with **no** foreground service; its process-priority story is the
companion-device association. `WatchAutoSyncWorker` is a plain `CoroutineWorker`, never an
expedited or foreground one.

### 2. No radio lease

The Wear OS link is RFCOMM on the Classic radio, one request per connection, and takes no
lease. OpenVitals' `RadioLeases`, which kept its BLE integrations from holding one watch at
once, left with the Garmin code. A future integration that opens BLE links brings it back.

### 3. Health Connect is reached through `HealthConnectGateway` only

[`HealthConnectGateway`](../../app/src/main/kotlin/tech/mmarca/openwatch/healthconnect/HealthConnectGateway.kt)
is the one class that holds the client. The devices write through
`ImportedRecordRepository.insertImportedRecords` with a deterministic `clientRecordId`, so a
re-sync upserts; Health Connect replaces a record only when its `clientRecordVersion` is
higher, so a record that is rewritten as it grows carries a rising version.

**A reader is strict.** Every read in the gateway throws when it fails. Nothing here turns a
refusal into an empty list, because every caller saves what it read: a null weight would skip
an estimate, an empty session list would double-count a workout.

**A writer checks that it can see other apps' data.** In the background, without the
background-read grant, Health Connect answers a read with this app's own records only, and
raises nothing. `HealthConnectGateway.readsOtherAppsDataNow()` is the test: a started
Activity, or the grant. Code that saves what it read while no screen is up calls it first and
skips the pass when it is false.

There is no rate-limit backoff, no call pacer and no call tally: the devices issue a handful
of reads per sync, and there is no dashboard fanning out dozens. If a quota refusal is ever
seen, OpenVitals' `HealthConnectRateLimit.kt` is the shape to copy.

### 4. A missing permission is a type, not a message

`Throwable.isPermissionFailure()` (`core/presentation/ScreenError.kt`) is the single predicate
for "this failed because a permission is missing". It walks the cause chain looking for
`SecurityException`, because Health Connect throws that for an ungranted read or write and the
repositories throw the same type when they short-circuit a call whose permission they know is
missing. `ScreenErrorHandler.handle` checks that predicate first and returns
`ScreenError.PermissionDenied`, which the screens render as a grant affordance. Reuse it; do
not pattern-match on exception messages.

### 5. Nothing waits on the main thread

- Health Connect calls are `suspend` and run on `dispatchers.io`. Inject
  `DispatcherProvider` so tests can swap it.
- No `runBlocking` in `src/main`. `NoRunBlockingRatchetTest` holds the (empty) allow-list.
- A receiver never holds a broadcast for a Health Connect read.

### 6. Text that leaves the app is structure, not data

A log line never splices in a value: log a count, a class name, or pass the throwable as the
last argument. `LogMessageInterpolationRatchetTest` holds the count of log calls that splice in
an exception message, and it may only fall. Nothing in OpenWatch builds a report or a crash
draft; if one is added, it must seed sentinel values in a test and assert none comes out, as
OpenVitals' reports do.
