# Samsung services on the watch: sources, privacy, and the SDK

> **Status (2026-11-07):** Research, nothing built in OpenWatch. Every claim says how it was
> established: **device** (measured or queried on a Galaxy Watch7, SM-L310, worn overnight at
> rest), **code** (read in the decompiled APKs from that watch), **docs** (Samsung's pages), or
> **not checked**. The harness and raw results live outside this repository (`ov-bench`).

## Why this exists

OpenWatch is meant to replace Samsung Health on the watch. The watch carries Samsung's own
health stack and Google's Health Services. Before the app reaches for either, we need to know
what each can give, what it costs to ask, and whether asking sends anything to a server.

## The three ways to read the watch

| Path | What it gives | Access | License |
| --- | --- | --- | --- |
| Raw sensors (`SensorManager`) | Heart rate, steps, pressure, Samsung's raw PPG (`com.samsung.sensor.hr_raw`). HRV only by our own PPG work. | `BODY_SENSORS` / `READ_HEART_RATE` | AOSP |
| Health Services 1.1.0 | See below | Normal runtime permission | Client library Apache 2.0; the on-device service is Google's binary |
| Samsung Health Sensor SDK 1.4.1 | Heart rate with inter-beat intervals, SpO2, skin temperature, ECG, BIA, EDA, PPG, accelerometer, sweat loss | Developer Mode, or a signature registered with Samsung | Proprietary `.aar` |

**Health Services on the SM-L310 (device, `getCapabilities`):**

- Measure client: heart rate only.
- Passive monitoring: steps, distance, calories, floors, elevation gain (each as interval and
  daily total), and heart rate.
- Exercise client: about 85 exercise types. For running, biking and walking: heart rate, calories,
  distance, location, speed, pace, absolute elevation, elevation gain, steps per minute, and
  auto-pause.
- Not offered: SpO2, skin temperature, inter-beat intervals / HRV, ECG, VO2 max.

The SDK's tracker list (code): `HEART_RATE_CONTINUOUS`, `SPO2(_ON_DEMAND)`,
`SKIN_TEMPERATURE(_ON_DEMAND/_CONTINUOUS)`, `ECG_ON_DEMAND`, `BIA(_ON_DEMAND)`, `MF_BIA_ON_DEMAND`,
`PPG_*`, `ACCELEROMETER_CONTINUOUS`, `EDA_CONTINUOUS`, `SWEAT_LOSS`.

## Measured on the watch (device, night of 2026-11-07)

A foreground service held a partial wake lock and read each path alone for 30 minutes with the
screen off, then all three at once for 30 minutes. Battery charge came from
`BATTERY_PROPERTY_CHARGE_COUNTER`.

**Power: no path can be told apart.** The counter moves in steps of 284 µAh, so a 30-minute
reading is good to about ±0.3 mAh, and the idle baseline itself varied more than the paths did:

| Run (30 min) | Charge used | Mean current |
| --- | --- | --- |
| Idle, first (adb over Wi-Fi in use) | 10.5 mAh | 21 mA |
| Raw sensor | 5.7 mAh | 11 mA |
| Health Services, measure client | 4.5 mAh | 9 mA |
| Health Services, passive | 6.0 mAh | 12 mA |
| SDK, continuous heart rate | 6.5 mAh | 13 mA |
| All three at once | 6.5 mAh | 13 mA |
| Idle, last | 6.8 mAh | 14 mA |

Reading of it, not measured directly: the wake lock dominates, and the heart rate sensor runs
anyway while Samsung Health keeps continuous heart rate on, so reading it costs next to nothing
extra. What the test could not show is the cost of wake-ups: with the screen off, Health
Services and the SDK delivered their samples in batches about every 10 minutes, the raw sensor
every second. Without a held wake lock that difference matters; it needs its own test, and so
does the baseline with Samsung Health disabled.

**Health Services is Samsung's tracker underneath.** In the run with all three at once, Health
Services and the SDK delivered the same 1802 heart rate values in the same order. The raw
sensor is a different stream: mean 55.1 against 55.6 bpm, and at the best alignment the two
differ by 2.5 bpm on average and agree exactly on 25 % of the samples. Without a reference
(a chest strap) this does not say which is closer.

**Inter-beat intervals come only from the SDK.** In the 30 minutes: 1621 intervals with good
status, 41 flagged or out of range, mean 1091 ms, RMSSD over the run 59 ms. Not validated
against ECG.

**SpO2 and skin temperature.** The on-demand SpO2 run gave a finished reading (status 2,
100 %, 51 bpm) once and only low-signal status (-5) in a second run minutes later, both at rest
in bed. `SKIN_TEMPERATURE_CONTINUOUS` delivered nothing in 3 minutes through either client;
the cause is not known.

## The SDK: access rules and decision

- **Developer Mode** works for testing; Samsung says it is not meant for users. Outside it the
  platform checks the calling app's package name and signature against a list Samsung maintains
  (docs, and code below). Whether Samsung's partner programme is open was **not checked**;
  forum posts say it is paused, undated.
- The `.aar` is proprietary. It does not go into this repository or into any APK we build.
- A client written from the observed Binder protocol is allowed by the project, and the
  `OpenVitals-Wearos` branch `research/wear-samsung-health-noblob` started it. It would not remove
  the access rule: the check lives in Samsung's service.

**Own client (device).** A client written from the observed Binder protocol, with no vendor
code, binds the platform service, lists the supported trackers, and receives continuous heart
rate with inter-beat intervals and on-demand SpO2 in the same shape as the SDK. In outline:
bind to `com.samsung.android.service.health` with action
`com.samsung.android.service.health.wear.sts.ACTION_SERVICE`; calls carry the token
`…tracking.IClientEventListener` and two strings, the caller's package name and the tracker
type (`HEART_RATE`, `SPO2`, `SKIN_TEMPERATURE_CONTINUOUS`, …); code 1 creates a tracker (plus an
optional user profile and exercise type), 2 registers a listener binder, 3 removes it, 4
flushes, 5 lists the types. The listener (`…tracking.IServiceEventListener`) gets code 1 with a
typed list of points, 2 with an error code, 3 when a flush is done. A point is a timestamp and
a list of `(int, float, long)` values; heart rate is at 0, its status at 2, the interval count
at 3 and `(interval, status)` pairs from 4; SpO2 has status, value, heart rate and an accuracy
flag at 0–3; skin temperature has object and ambient temperature as floats at 0–1 and the
status at 2. The platform keys clients by package name: the SDK and the own client in the same
app silenced each other, so the two cannot be compared side by side in one process. The
access rules above apply to the own client unchanged.

**Decision (2026-11-08):** the raw sensors are the default. The SDK itself is never used; the
own client is an optional source, off by default and offered only on a watch that has the
platform. The app cannot read Developer Mode (the platform keeps it in its private preferences,
`home_feature_sharedpreferences`, key `common_developer_mode`), so the setting on the watch
says that the source needs it and what happens without it.

**In the watch app.** The night-time HRV windows take their beats from an `HrvWindowSource`:
`PpgWindowSource` (Samsung's raw PPG, beats found by `PpgBeats`) by default, or
`SamsungIbiSource` (the platform's own intervals, through `SamsungHealthPlatform`, the
clean-room client in `wear/.../samsung/`) once chosen on the status screen. The choice
(`HrvSourceSetting`) is shown only where `com.samsung.android.service.health` answers the bind
intent, so a watch from another maker never sees it. The tracker runs only for the five-minute
window. The intervals carry no times of their own: the first point after a start held 535 of
them (about ten minutes of history), and with the screen off the points arrive in batches, so
`SamsungIbiWindow` times each interval back from its point and keeps the ones that ended inside
the window; a flagged interval or a gap between points breaks the run, and no difference is
taken across it. At the window's end a flush asks for the points still held, with a 15-second
limit. Not yet measured on the watch: whether the flush answers with the screen off, how long
the tracker takes to deliver its first point outside a reboot, and how the two sources' RMSSD
compare over a night.

## What leaves the watch

Method: APKs pulled from the watch (Health Platform 1.7.00.05, Samsung Health 7.1.2.003, Health
Monitor 1.4.5, Health Services 0.99.6), decompiled with `jadx`, read. No code is copied into this
repository. Strings and class names show what the code can do, not what runs.

**Traffic and rights (device).** Per-package bytes from `dumpsys netstats detail` over about
2.7 days, and the network permissions each package declares:

| Package | Received | Sent | Declares |
| --- | --- | --- | --- |
| `wear.shealth` (Samsung Health) | 4.8 MB | 162 KB | `INTERNET` |
| `service.health` (Health Platform; the SDK binds here) | 18 KB | 4.9 KB | `INTERNET` |
| `shealthmonitor` | none | none | `ACCESS_NETWORK_STATE` only |
| Google Health Services | none | none | not seen in the check |

**Health Platform (code).**

- **No sensor-value upload was found.** The only network code is the SDK policy client, built
  on OkHttp and Retrofit.
- **Policy client.** `GET sdp/policies?serviceId=<calling app's package name>` and
  `GET sdp/blocklist`, against `api.samsungknowledge.com/knowledge-ws/v1.3/`. The only header
  set is `User-Agent: okhttp/4.11.0`; no device or account id was found. The server still sees
  the IP and the package name. `sdp/blocklist` runs daily; `sdp/policies` runs when a caller is
  not in the local cache. **In Developer Mode the platform skips this check entirely**
  ("No need to check service policy"), so the SDK in Developer Mode makes no policy request.
- **Analytics.** When an app calls `setEventListener`, the platform builds event `HPR2001`
  holding the **package name** and the **tracker type**, for Samsung Analytics. The analytics
  library refuses to send unless the system setting `setting_diagnostic_data_agree` is `1`
  ("user do not agree"). On the test watch it is `0`.
- **Not checked:** the policy sync's request body beyond the interface; what the daily job sends
  besides the URL; a packet capture.

**Overnight (device, 10 hours with the harness, own client and SDK in use).** The harness, the
own client, Health Services and Samsung Health sent nothing. The platform sent 2.6 KB and
received 9 KB.

**That traffic is the daily blocklist fetch (device and code).** The platform's only traffic in
two days fell in the 11:00–12:00 bucket each day (2.2 KB sent on 11-06 over the phone's
Bluetooth proxy, 2.6 KB on 11-07 over Wi-Fi). `SdkPolicyJobService` is a periodic network job
(one day) and ran at 11:36 for half a second. It fetches `sdp/blocklist`, which carries no
package name and, unlike the per-app check, runs in Developer Mode too. The signature check
itself is local: the platform compares the caller's signing certificate with the stored
`credentials` and sends neither.

**Samsung Health (`wear.shealth`).** The strings include `shealth-api.samsunghealth.com`,
`api.samsungcloud.com`, `firebaseinstallations.googleapis.com`, `api.weather.com` and
`api.calm.com`. This is the package that sends data. **Its code is not analysed yet.**

## Keeping the services private

On the watch, once, reversible, over `adb`:

```
# the consent must stay 0 (read it; do not rely on it alone)
adb shell settings get system setting_diagnostic_data_agree

# deny the network to a package, then read the bit back
adb shell cmd connectivity set-package-networking-enabled false com.samsung.android.service.health
adb shell cmd connectivity get-package-networking-enabled com.samsung.android.service.health

# undo
adb shell cmd connectivity set-package-networking-enabled true com.samsung.android.service.health
```

The command exists on the SM-L310 (device, `cmd connectivity help`). It sets a deny bit in the
`FIREWALL_CHAIN_OEM_DENY_3` chain. **Not yet tried:** that the SDK and the sensors keep working
with the platform denied, whether the rule survives a reboot, and what denying `wear.shealth`
does. Verify any change with a netstats delta, not with the command's own answer. The rule is
lost on reboot (tested); set it again after every start.

Denying `service.health` is safe for the Developer Mode path, which needs no policy fetch. It
would break a non-Developer-Mode SDK call, which fails anyway.

**Network deny tested (device, 2026-11-07).** With the network denied to
`com.samsung.android.service.health` and `com.samsung.android.wear.shealth`, the own client and
the SDK still bound, listed the trackers and received heart rate points (the watch was on the
charger, so the points carried the off-wrist status). The rule read back as `deny`. Not yet
**Incident, cause not isolated (device, 2026-11-07, 22:29–23:11).** After a reboot, with the
rule set again about two minutes later for both Samsung packages, Samsung's own heart rate
produced nothing for 40 minutes: the platform's tracker started and the sensor HAL enabled the
Samsung sensor (`enable 24 from SemSensorServices`), but no event came back, to the SDK, the
own client or the Samsung Health app, while the plain Android heart rate sensor kept working.
Allowing the network again did not help; a reboot with no rule set did, and both Samsung Health
and the own client got heart rate again within about two minutes. Candidates, not yet tested
one at a time: the rule set while Samsung's services were still starting, and state left by the
harness (with the batch sensor registered, the platform delivered only part of its data and the
own client none). Until that is settled, do not deny the Samsung packages early after boot.

**The rule does not survive a reboot (device):** after a restart both packages read `allow`
again, so it has to be set anew after every boot, and the platform's daily job may run before
that. Worn runs under the rule (evening of 2026-11-07, after
3.5 days of uptime) delivered heart rate through the SDK.

## Google Health Services, read (code and device)

- **No sockets of its own.** The APK declares no `INTERNET` and has no network code.
- **Telemetry through Play Services.** It bundles Primes (performance metrics: memory, crashes,
  timers, jank, battery, network, storage, CPU profiling) logging to the Clearcut source
  `WEAR_HEALTH_SERVICES_ANDROID_PRIMES`, and Phenotype, which sets those flags from Google's
  side. Both leave through `com.google.android.gms`, so the traffic cannot be told apart from
  other apps' in the netstats. It registers a listener on Google's usage-and-diagnostics
  opt-in (`IUsageReportingOptInOptionsChangedListener`) and carries Google's consent-verifier
  library; that every upload is gated by that opt-in is **not proven** from the obfuscated code.
  No health values were seen in a log path, which is not the same as their absence.
- **It measures nothing itself.** It is a front end for the shared library
  `clockwork-healthservices`, which talks to the system service `HealthService`
  (`IHealthService`), backed by the vendor HAL
  `vendor.google_clockwork.healthservices.IHealthServices/default`, implemented here by Samsung.
  That is why it delivered the SDK's values.
- **The system service offers more than the public API**: automatic exercise detection,
  sleep, fall and abnormal heart rate detection, auto pause and stop, rep counting, golf shots.
- **An ordinary app cannot reach it.** The library is free to load, but SELinux denies the
  lookup: `avc: denied { find } … scontext=untrusted_app … name=HealthService`. Only the
  public Health Services API reaches these features, as far as it exposes them.
- **Removing it.** Samsung Health uses its exercise and passive clients; the Health Platform and
  the own client do not. Once Samsung Health is gone it can be disabled for the user
  (`pm disable-user`), reversibly. What else on the watch uses it (complications, Google apps)
  is not known; disable and watch before going further.

## Which heart rate sensor for what (device list; the use cases are Android sensor semantics)

| Sensor | What it is | For |
| --- | --- | --- |
| `Samsung HR None Wakeup Sensor` (type 21, non-wake-up) | Delivered only while the CPU is awake; while it sleeps, samples sit in the hub's FIFO and old ones drop when it is full | Live values on screen, a workout that holds a wake lock, spot measurements |
| `Samsung HR Batch Sensor` (type 21, wake-up) | The hub wakes the CPU when its FIFO nears full or the report latency passes, so nothing is lost while asleep | All-day and night heart rate at few wake-ups; can replace the 10-minute alarm of the power-save mode |
| `com.samsung.sensor.hr_raw` | Raw PPG at about 25 Hz | Own beat detection and HRV, in short still windows only |

**Measured (device, 2026-11-07 evening, worn).** The batch sensor is a wake-up sensor with a
FIFO of 600 events, ten minutes at 1 Hz. Run side by side for 3 minutes, the batch and the
non-wake-up sensor gave the same values (99 % identical, no offset): one heart rate stream,
two ways of delivery. The SDK and Health Services are a separate computation by the platform,
with inter-beat intervals, and differ from the sensor stream. While the batch sensor was
registered (60 s report latency), the SDK received only 88 points in 5 minutes and the own
client none in 3 minutes; the non-wake-up sensor did not have that effect overnight. The cause
is not known. Not yet measured: the batch sensor's power use with no wake lock held.

## Where the detection logic lives (device)

- The vendor HAL `vendor.google_clockwork.healthservices.IHealthServices/default` is declared in
  `/vendor/etc/vintf/manifest/sensors.default.xml` and runs in
  `android.hardware.sensors-service.multihal` (sub-HAL `sensors.default.so`, 32-bit ARM),
  beside `vendor.samsung.semsensorservices`. Its interface is thin: an auto-start config is an
  exercise type, an event is a type code (fall, sleep, wake-up, high or low heart rate), so the
  detection logic sits in the HAL and in the sensor hub's firmware. The shell may not read
  either (`Permission denied` under `/vendor/lib/hw` and `/vendor/firmware`).
- Hub sensors an ordinary app can read: heart rate (wake-up batched and non-wake-up),
  `hr_raw`, step detector and counter (`ACTIVITY_RECOGNITION`), accelerometer, gyroscope,
  magnetometer and their fused sensors, barometer, `Samsung Altitude Sensor`, both off-body
  detectors and `wrist_tilt_gesture` (no permission).
- Behind `com.samsung.permission.SSENSOR` (`signature|privileged`, never granted to an ordinary
  app): `SContext` (the hub's context engine), `Samsung Movement Sensor`, `Samsung Skin Temp
  Sensor`, `Samsung GPS Batch`, `Samsung wrist down Sensor`. Skin temperature and the like reach
  an ordinary app only through the Health Platform, that is through the SDK or the own client.

## Play Services' diagnostics

Clearcut, which carries Health Services' Primes metrics and other apps' logs, uploads to
`https://play.googleapis.com/log/batch` (string in the Play Services APK on the watch). Ways to
stop it, none tried: switch off usage and diagnostics in the watch's Google settings; deny the
network to `com.google.android.gms` (stops all of Play Services: account, Play Store, push);
or resolve `play.googleapis.com` to nothing through a filtering Private DNS on the watch,
which may break other Google functions on that host and must be checked on both the Wi-Fi and
the phone's Bluetooth proxy path.

## What the app can check and enforce

| Question | Answer | How sure |
| --- | --- | --- |
| Is the diagnostic consent on? | Yes. `Settings.System.getInt(..., "setting_diagnostic_data_agree", 0)` is a public read. | Reading the key is standard Android; the key name is from the platform code. |
| Did a Samsung package send traffic? | Probably, with the Usage Access special permission and `NetworkStatsManager.queryDetailsForUid`. | **Not tested on the watch.** The platform grants other users' apps' stats at "user" level to holders of `PACKAGE_USAGE_STATS`; confirm before building on it. |
| Is the deny rule set? | **No.** No public API reads another package's firewall bit. | Not checked beyond the absence of an API. |
| Can the app set the rule? | **No.** It needs the shell or a system permission. | `cmd connectivity` runs as shell. |

Ways to enforce from the phone app, none chosen: a documented one-time `adb` step (the honest
default); a local VPN that drops the traffic of named packages (no root, but it takes the
single VPN slot and shows a system consent; **not tried on Wear OS**); a Shizuku-style helper
(heavy, not FOSS-minimal). The app can at least show the consent value and the traffic, and say
plainly that the deny rule is outside its reach.

The watch app does the first part: its status screen shows Samsung's diagnostic consent
(`SamsungDiagnostics`), on a watch that has the setting.

## Rules for OpenWatch

Accepted on 2026-11-08 and carried into `AGENTS.md`.

1. The SDK `.aar` stays out of the repository and out of every build.
2. The default sources are the raw Android sensors. Health Services is not a dependency: it is
   to be disabled on the watch, and it adds no metric the raw sensors lack except through the
   exercise client.
3. A Samsung Health Platform source, if one is added, is opt-in, is the clean-room Binder
   client, needs Developer Mode, and never runs without it, so the policy request, which
   carries the package name, is never made.
4. The watch app declares no network permission; its manifest removes `INTERNET`,
   `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` as the phone app's does.
5. A text that leaves the app is structure, not data, as already required in `AGENTS.md`.
6. The network deny rule for Samsung's packages is a documented `adb` step, not an app
   feature: the app can neither read nor set it, it does not survive a reboot, and it is not
   set early after boot (see the incident above).

## Open items

- Analyse `wear.shealth`: cloud endpoints, what is sent, whether it respects the consent.
- Power without a held wake lock, and the idle baseline with Samsung Health disabled.
- Accuracy against a chest strap for heart rate and inter-beat intervals.
- Why skin temperature delivered nothing.
- Test the deny rule: SDK and sensors with the platform denied; reboot persistence.
- Test `NetworkStatsManager` access to other packages on the watch.
