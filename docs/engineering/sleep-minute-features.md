# Sleep minute features (SM line, version 3)

The OpenWatch Wear OS app records one row per clock minute as the input of the phone's sleep pipeline. The row is computed in Kotlin on the watch (`wear/.../MinuteAggregator.kt` and `AccelerationMinuteFeatures.kt`) from this specification.

The pipeline that consumes the rows is described in [architecture.md](architecture.md) under `devices/wearos` and `domain/insights`; the research behind each rule is in [watches.md](../features/watches.md).

## The line

```
SM <epochMillis> <kind> <offsetSeconds> <flags> <n> <mv10> <bpm|-> <hsd10|-> <hn> <mx> <my> <mz> <sx> <sy> <sz> <zmin|-> <zmax|-> <zd10|->
```

Nineteen space-separated fields. Every numeric field is an integer, so both implementations format identically; `-` means "none". A line the receiver cannot parse is skipped.

| Field | Meaning |
|---|---|
| `epochMillis` | Start of the minute: `t - t mod 60000` of the samples in it. |
| `kind` | `R` raw, `A` awake, `U` unmeasurable. See *Kind*. |
| `offsetSeconds` | The watch's UTC offset at the minute, seconds. Decides which night the minute belongs to. |
| `flags` | Bit field. See *Flags*. |
| `n` | Accelerometer samples in the minute. |
| `mv10` | Movement × 10. See *Movement*. |
| `bpm` | Mean heart rate of the minute's samples, or `-` when `hn` is 0. |
| `hsd10` | Population standard deviation of the minute's heart rate samples × 10, or `-` when `hn` is 0. |
| `hn` | Heart rate samples in the minute. |
| `mx my mz` | Per-axis mean acceleration, milli-g, signed. |
| `sx sy sz` | Per-axis population standard deviation of acceleration, milli-g. |
| `zmin zmax` | Lowest and highest 5-second z-angle in the minute, degrees, or `-` when no epoch is valid. |
| `zd10` | Mean absolute change between successive valid 5-second z-angles × 10, or `-` when there is no change to measure. |

## Inputs

- Accelerometer samples `(t, ax, ay, az)` in **g**. The watch divides the sensor's m/s² by 9.80665 before anything else. A sample belongs to the minute `floor(t / 60000)`.
- Heart rate samples `(t, bpm)`: the samples the watch stores, one every ten seconds at most, never a no-contact reading. A sample belongs to the minute of its `t`.
- Events, each stamped: `worn(bool)` from the off-body sensor (sticky until the next event; initially worn), `charging(bool)` (sticky; initially from the battery state), `screenOn` (instantaneous), `hrContact(false)` when the heart rate sensor reported no contact, an unreliable reading or 0 bpm.
- `hrRecording`: whether heart rate is being recorded at all while the minute closes.

## Rounding

Every integer field is `floor(x + 0.5)` of the real value: `Math.round(x: Double)` in Kotlin, `math.floor(x + 0.5)` in Python. Never a language's default `round`, which ties to even in Python.

## Movement

The actigraphy count on the scale the sleep estimator was fitted on (zero for a still minute, around ten for a restless one, from the Garmin nights in `app/src/test/resources/fit/sleep/`).

1. `mag_i = sqrt(ax² + ay² + az²)` for every sample, in g.
2. `counts` = the number of samples in the minute whose `|mag_i - mag_{i-1}| > 0.051` (g; the previous sample may belong to the previous minute, so the first sample of a minute is compared across the boundary; the first sample ever is not counted).
3. `countsNorm = counts × 300 / n` (the count at the nominal five readings a second; the Watch8 delivers 6.25 Hz when 5 Hz is asked). 0 when `n` is 0.
4. `mv = 0` when `countsNorm < 3`; otherwise `min(30, 0.2 × countsNorm)`.
5. `mv10 = round(mv × 10)`.

## Per-axis mean and standard deviation

Over the minute's samples, in g, two-pass (mean first, then the mean of squared deviations), population form (divide by `n`). `mx = round(mean × 1000)`, `sx = round(sd × 1000)`; the same for y and z. All zero when `n` is 0.

These let the phone pool a window's variance exactly: `var_window = mean(sd_i² + mean_i²) - mean(mean_i)²` over the minutes, weighted by `n`.

## z-angle

The angle of the watch's z axis against the horizontal, the HDCZA input (van Hees 2018).

1. The minute is cut into twelve epochs of five seconds: epoch `e` holds the samples with `floor((t - epochMillis) / 5000) = e`.
2. An epoch is valid when it holds at least three samples. Its angle is `atan2(median(az), sqrt(median(ax)² + median(ay)²)) × 180 / π`, degrees, from the per-axis medians (sorted; the middle value, or the mean of the two middle values).
3. `zmin`, `zmax` = `round` of the lowest and highest valid angle. `-` when no epoch is valid.
4. `zd` = the mean of `|angle_e - angle_prev|` over every valid epoch that has a previous valid epoch, where the previous one is the nearest earlier valid epoch in the same minute, or for the first valid epoch of the minute the last valid epoch of the previous minute (carried; nothing before the first minute ever). `-` when there is no pair. `zd10 = round(zd × 10)`.

## Heart rate

`hn` = samples in the minute. `bpm = round(mean)`, `hsd10 = round(populationSd × 10)`, both `-` when `hn` is 0.

## Flags

| Bit | Value | Name | Set when |
|---|---|---|---|
| 0 | 1 | `CHARGING` | charging at any point of the minute |
| 1 | 2 | `OFF_BODY` | not worn at any point of the minute |
| 2 | 4 | `SCREEN_ON` | the screen became interactive during the minute |
| 3 | 8 | `HR_NO_CONTACT` | the heart rate sensor reported no contact, unreliable, or 0 bpm during the minute |
| 4 | 16 | `HR_RECORDING` | heart rate was being recorded when the minute closed |
| 5 | 32 | `SPARSE` | `n < 150`, half the nominal budget: the sensor hub dropped part of the minute |

## Kind

Instantaneous evidence only; windowed rules belong to the phone.

- `U` when `CHARGING` or `OFF_BODY` is set, or when `HR_RECORDING` is set and `hn` is 0.
- otherwise `A` when `SCREEN_ON` is set.
- otherwise `R`.

## Closing a minute

The watch closes a minute 90 seconds after it started; by then every batch that belongs to it has arrived (batches are at most 60 seconds late). The Python implementation closes a minute when it sees the first sample of a later minute, or at the end of the input.

## What the watch actually delivers

The Watch8's accelerometer cannot wake the watch and buffers 300 readings, 48 seconds at the 6.25 Hz the hub delivers; readings older than that are lost when the buffer wraps. The heart rate sensor can wake the watch, so its report latency sets how often the watch wakes at night and is kept at 40 seconds so the accelerometer buffer is drained before it wraps. A minute still ends up sparse now and then (`SPARSE` flag, `n` under 150), with few or no valid five-second epochs; the phone's window detector carries the last known angle change across such minutes rather than reading them as movement. The first recorded night, before the latency change, kept a median of 30 readings a minute.

### Automatic hold on the watch

The watch does not need bedtime mode to record whole minutes at night or during a nap: `SleepHold` (in `:wear`) starts the wake lock when the last ten minutes were worn, still (no steps, no screen, movement at most 2) and the heart rate fell (later half at least 2 bpm under the earlier half and at least 4 bpm under the median of the last 180 readings; three readings, one per half, are enough). It stops after five waking minutes in a row (screen on, steps, or movement of at least 8), when the watch leaves the wrist or goes on the charger, or after ten hours, and waits an hour after the cap. A minute that arrives sparse because the processor dozed counts as still only when it has no steps and no screen, so walking with the screen off is not read as rest. Not yet verified on a watch.

Against false starts the hold needs twenty quiet minutes by day (06:00 to 22:00) instead of ten, and the phone reports an unlock (`PHONE_ACTIVE` over the link, `WearOsPhoneActivity`, at most one every five minutes): a hold `SleepHold` started ends at once and none starts for thirty minutes. A glance at the watch is not use: its screen-on minute is one waking minute of the five that release the hold, and a bedtime-mode hold is never ended by the phone.

### Synced wake-ups instead of a lasting wake lock

Only wake-up sensors wake the processor, and on the Galaxy Watch7 that is the batched heart rate sensor. The accelerometer (its buffer holds 48 seconds at 6.25 Hz, the slowest it runs), the barometer and the step counter deliver on their own timers and only while the processor is awake; the timers drift against the heart rate's, and the accelerometer lost readings even with the heart rate waking the watch every 40 seconds (2026-11-08 01:00-02:00: a median of 0 of 375 readings a minute). `SensorWakeSync` therefore flushes every sensor's buffer at each heart rate batch and holds a short wake lock until the flushes complete, so one wake-up empties everything. The flush runs on a beat of `WakeClock`, the app's only scheduled wake source. With "Sleep: synced wake-ups" on the status screen (`WearSettings.syncedSleepHold`) a sleep hold relies on the dense beat alone and takes no lasting lock; it then takes the heart rate from the sensor, whose batches are the beat. Without the heart rate grant there is no beat and the hold keeps the lock. Outside a hold the accelerometer is not kept whole, by design: on a working day worn off the charger (2026-11-09, 11:15-17:42, the raw heart rate sensor's batches as the beat) 17 % of its readings arrived, 22 % while the screen was off but the watch awake with a beat every 35 seconds and 12 % in doze with one every 62 seconds, so its buffer behaves as if it held seconds, not 48 seconds. Every heart rate reading arrived. By day the movement only feeds the start of a sleep hold and the nap estimate, which normalise to the readings a minute had and flag a thin minute sparse; keeping every reading would need a lasting wake lock all day.

The night of 2026-11-08/09 is not a measurement of this app alone: Sleep as Android's watch app tracked that night and woke the watch 2,843 times with its own `ACTION_FLUSH_SENSOR_BATCH` alarm, and it reads the accelerometer itself. The heart rate batches came 150 to 220 times an hour instead of 90, because the hub delivers its wake-up buffers whenever something wakes the processor, and the flush recovered only about 48 accelerometer readings a beat (some 7,000 an hour against the 22,500 of whole minutes), likely because the other app's registration shares the buffer. With the power-save switch on (`WearSettings.heartRateDay` power save), the only setting that may cost data, a sleep hold runs `WakeClock` quiet: dense while the wearer moves, and while still the heart rate's batch latency, and so the beat, stretches to four minutes; minutes between two quiet beats keep only the accelerometer's last 48 seconds and are flagged `FLAG_SPARSE`. The wake-up device-orientation and wrist-tilt sensors bring the dense beat back for five minutes after the last motion, and a minute closes only once a stretched batch can have arrived. That night ran the quiet hold beside the other app, which could not show what it saves. Measure the synced hold again on a night without another sleep tracker.

The night of 2026-11-02/03 used to check the night estimator (the estimate 01:38-08:02, 6 h 13 sleep, against Samsung Health's 01:25-08:17, 6 h 35) is not a typical night: Sleep as Android ran from 01:18 with its watch companion, which streams the watch's accelerometer and heart rate and very likely kept the processor awake, so its movement is denser than a normal night without bedtime mode. Do not tune thresholds to that density; the automatic hold is what has to make an ordinary night as dense.

## Naps

`WearNaps` (in `:domain`, run on the phone) finds sleep outside the main night in the same rows. It reads the day, not only the night window, and leaves the night to `WearNight`: the night's estimate does not change, a test checks that.

A nap is a run of minutes that are all of: worn, scored as sleep by `SleepWakeScorer` (still arm, quiet pulse), and with the 5-minute median heart rate clearly under the wearer's waking level. The waking level is the lower quarter of the heart rate of the worn minutes between 06:00 and 22:00 outside the night sessions; with fewer than 90 such minutes there is no level and no nap.

| Rule | Value |
| --- | --- |
| Minimum sleep | 20 minutes |
| Longest nap | 4 hours (a longer run is a night not estimated yet) |
| Heart rate drop by day (06:00-22:00) | at least 10 bpm and 12% of the waking level |
| Heart rate drop 22:00-06:00 | at least 6 bpm and 8%, and only when the minutes hold a night session |
| Gap that joins two runs | up to 5 minutes, worn |
| Distance from a night session | a run within 30 minutes of one is part of that night |
| Onset | moved back up to 15 minutes over still, worn minutes with the pulse at least 3 bpm under the waking level |

The thresholds are judgement, not fitted: no nap was recorded against a reference yet. Sitting still on a sofa or in a cinema lowers the pulse by a few beats, so by day the drop is the stricter rule. A nap has one `LIGHT` stage for its sleep, `AWAKE` for wake minutes; it is too short for deep and REM. A nap has no resting heart rate. Its identity is its onset minute, so re-estimating as the day fills in gives the same nap; if the onset moves, the phone replaces the old record.

## Breathing rate and skin temperature

Each night HRV window also gives a breathing rate (`RespiratoryRate`, from OpenVitals): from the raw PPG by two routes, the beat intervals (respiratory sinus arrhythmia) and the signal's baseline, kept only when they agree within three breaths a minute; from the Samsung platform by the intervals alone. It is stored as an `RR` row with the number of routes. The phone writes it as a `RespiratoryRateRecord` only when the user switched "Breathing rate from the watch (experimental)" on: it is not yet checked against a reference, and Samsung Health's own sleep breathing rate is the first comparison. Every window's two estimates and their peak shares go to the debug protocol.

With "Skin temperature: Samsung, at night" on (a watch with the Samsung platform only), the watch runs the platform's continuous skin temperature tracker while sleep is held (`SkinTemperatureRecorder`) and keeps one `SK` row per minute, skin and ambient. In the bench test the tracker delivered nothing; the debug protocol counts what arrives. The tracker needs `READ_SKIN_TEMPERATURE` on Android 16 and delivers one point a minute, handed over hourly (Galaxy Watch7, 2026-11-08: 120 minutes around 34.3 °C skin, the ambient sensor close by inside the case). The phone cuts the minutes into night runs (a gap over 30 minutes starts a new one) and writes each finished run of at least 30 minutes as one `SkinTemperatureRecord` at the wrist, with one delta a minute against a baseline: the median of the earlier nights' medians once there are three, the run's own median before (`WearOsSkinTemperatureImport`). Health Connect versions without the skin temperature feature are skipped.

With "SpO2: Samsung, hourly at night" on, `Spo2Recorder` starts the platform's on-demand SpO2 tracker while sleep is held and the last ten minutes were still, at most once an hour (after a failed try, fifteen minutes later), and keeps the first completed reading (status 2; on the bench it came after 13 seconds) as an `O2` row. The phone writes each as an `OxygenSaturationRecord`.

Each Samsung source falls back to the raw sensor by itself: the heart rate after a platform error, a refused bind or five minutes without a point; the HRV windows after a window with an error or no point. The fall-back holds until a setting changes; the debug protocol logs it.

The platform also offers an `ACCELEROMETER` tracker. Tested on 2026-11-08 with the processor free to sleep, it ran at a fixed 25 Hz (three axes, about 4096 counts per g, no holes) and delivered every 12 seconds with the screen off: more wake-ups than the synced heart-rate wake-ups, at four times the data. It is not a way to keep the minutes whole for less battery.

