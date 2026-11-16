# Research: which Galaxy Watch7 sensors an app can read without the Samsung SDK

**Status: research record, moved here from the OpenVitals repository with the watch app; the metric table is updated to what OpenWatch builds.** Companion to
[wear-ppg-hrv.md](wear-ppg-hrv.md). Answers the question "which raw sensors can an
ordinary app read on the watch?" The other two ways to read the watch, Health
Services and the Samsung Health Platform, are in
[samsung-health-and-privacy.md](../engineering/samsung-health-and-privacy.md).

Source: `adb shell dumpsys sensorservice` on a Galaxy Watch7 (`SM-L310`),
2026-11-02. The `perm:` column of each sensor decides who may read it.

## Readable by an ordinary app (no SDK, no signature permission)

| Sensor | Type string | Permission |
|---|---|---|
| Samsung HR Raw Sensor (PPG) | `com.samsung.sensor.hr_raw` | `android.permission.health.READ_HEART_RATE` |
| Samsung HR Batch / None Wakeup | `android.sensor.heart_rate` | `READ_HEART_RATE` |
| Step counter / detector | `android.sensor.step_counter`, `step_detector` | `ACTIVITY_RECOGNITION` |
| Altitude (barometer) | `com.samsung.sensor.altitude` | none |
| Rotation vector, game rotation, linear acceleration | AOSP types | none |
| Off-body detector, low-power off-body | `low_latency_offbody_detect`, `low_power_offbody_detector` | none |

## Gated by `com.samsung.permission.SSENSOR` (Samsung-signed apps only)

| Sensor | Type string | What it would give |
|---|---|---|
| AFE4510 ECG | `com.samsung.sensor.ecg` | ECG |
| AFE4510 BIA | `com.samsung.sensor.bia` | body composition (bioimpedance) |
| Samsung Skin Temp Sensor | `com.samsung.sensor.skin_temp` | skin temperature |
| Samsung Thermistor Sensor | `com.samsung.sensor.thermistor` | temperature |
| HR Raw Fac / Fac2 | `hr_raw_fac`, `hr_raw_fac2` | factory-mode raw PPG, possibly more channels |
| LSM6DSO Accelerometer 32G | `accelerometer_32g` | high-range accelerometer |

Regular accelerometer data is available through the AOSP accelerometer, which
the research logger already records.

## What follows for each wanted metric

| Metric | Route | In OpenWatch |
|---|---|---|
| Heart rate, sleep, steps, ascent | Raw sensors | built |
| Sleep stages | From heart rate and motion (`SleepStageEstimator`) | built |
| HRV (RMSSD) | From `hr_raw` ([wear-ppg-hrv.md](wear-ppg-hrv.md), `PpgBeats`, `HrvRmssd`), or the Samsung platform's inter-beat intervals | built |
| Respiratory rate | From the night HRV windows (beat intervals and PPG baseline) | built, opt-in, not checked against a reference |
| SpO2 | No SpO2 sensor type an ordinary app may read, and `hr_raw` shows only two green-like channels; the Samsung platform's on-demand SpO2 tracker | built, Samsung watches |
| Skin temperature | Only `SSENSOR` as a raw sensor; the Samsung platform's tracker | built, Samsung watches |
| ECG, body composition (BIA) | Only `SSENSOR` as a raw sensor; the Samsung platform's on-demand trackers | built as measurements on the watch, Samsung watches, not yet run on a watch; body composition syncs to Health Connect, the ECG is shown only |
| VO2 max, fall detection, stress | From heart rate, HRV and motion | not built; see [wear-auto-activity.md](../proposals/wear-auto-activity.md) for VO2 max |
| Blood pressure | Needs calibration and Samsung's own app | not planned |

The Samsung platform is reached over Binder without Samsung's library and only
with its Developer Mode or Samsung's approval; defeating the `SSENSOR` signature
permission or any other platform protection stays out of scope.

## Open questions

1. Does `hr_raw` ever expose red/IR channels, for example at another sampling
   rate or sensor configuration?
2. Validate the HRV and breathing rate against a chest strap or ECG reference.
