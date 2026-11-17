# Watch: Automatic Activity Detection, More From The Sensors, Workout UI

> **Status (2026-11-04, workout table updated 2026-11-10):** Research and proposal, nothing built. Written in the OpenVitals repository while the watch app lived there, and moved here with it. Current behaviour: [Watches](../features/watches.md). Sections say what was checked and what is this project's own judgement.

## Goal

1. The watch notices an activity by itself, without GPS, and offers to keep it or records it.
2. Everything the sensors give and is useful is worked out from them: VO2 max, then walking analysis and the same for cycling.
3. The workout screens use what Wear OS offers, as Samsung Health and the others do.

## How the others detect an activity

Checked against vendor pages and manuals on 2026-11-04. Anything not found is marked "not found" instead of guessed.

| Vendor | What is detected | When it starts | Source |
| --- | --- | --- | --- |
| Samsung Galaxy Watch | Walking/running, cycling (not on Watch4/5), elliptical, rowing machine, dynamic workout, swimming | After 10 minutes of the activity; how it ends and whether the first 10 minutes are kept: not found in Samsung's page. A user manual says tracking stops after a pause of over a minute. | [Samsung support](https://www.samsung.com/us/support/answer/ANS10003306/) |
| Garmin Move IQ | Movement that matches familiar patterns, shown on the timeline; older devices start timed walks and runs | The minimum duration per activity type is a setting; the default was not confirmed | [Garmin manual](https://www8.garmin.com/manuals/webhelp/venu/EN-US/GUID-19399E88-6C66-434F-8587-0B75C70E34C7.html) |
| Fitbit SmartTrack (2015) | Continuous movement, the type guessed from intensity and pattern | 15 minutes by default, adjustable per type; no GPS, no live stats, no distance or pace in the history | [Fitbit](https://canarywww.fitbit.com/smarttrack), [Wareable](https://www.wareable.com/fitness-trackers/fitbit-updates-surge-and-charge-hr-with-automatic-exercise-detection-1978) |
| Apple Watch | A reminder to start, for running, walking, swimming, elliptical and rower; a reminder to end for all but "Other" | Not found | [MacRumors](https://www.macrumors.com/how-to/use-automatic-workout-detection-watchos-5/) |

What this says, and what it does not:

- Everyone waits roughly 10 to 15 minutes before calling it a workout. Short walks are the usual complaint (users report false and missed detections for Samsung and Fitbit, anecdotal).
- Apple asks ("reminder") and Garmin shows it on the timeline; Samsung and Fitbit record. Asking is the cautious choice and it is what is proposed first.
- No vendor says how it decides. Their pages describe the behaviour, not the rule. The rule below is therefore this project's own, to be tuned.

## What the watch already has

Per clock minute, for a week: steps, height climbed, heart rate, and the movement features that sleep detection uses (`AccelerationMinuteFeatures`, `docs/engineering/sleep-minute-features.md`). Nothing new has to be sensed to see a walk, a run or a ride; GPS is not needed.

## Proposed rule (judgement, to be tuned with data)

A detector over the last minutes of the watch's own minute rows, run when a minute row closes:

- **Walking/running/hiking**: step cadence above a floor for N consecutive minutes (cadence tells walk from run; a run is clearly higher). Heart rate above resting by a margin makes it stronger evidence, but is not required (a slow walk).
- **Cycling**: a steady heart rate raise **with a still wrist and few steps**; the steps stay low while the heart rate rises. Needs the barometer or later GPS to say outdoors from a spin bike. Built without the wrist and the barometer (`ActivityDetector.detectRide`): ten minutes, one miss allowed, each with fewer than 30 steps and a median heart rate at or above zone 3's lower bound (the ACSM's moderate intensity, so only once the phone sent a maximum heart rate), offered as a ride without GPS. Power save, a sample every ten minutes, sees no ride. Not tuned on data yet.
- **Ending**: the cadence or heart rate drops below the floor for a few minutes.
- **First action**: a notification ("Looks like a walk, 12 min. Keep recording?") and the minutes already recorded are credited to the workout from the minute rows, as Fitbit and Samsung do. The numbers (N, the cadence floor, the margin) are not set here: they come from the data below, not from a paper.

The watch is the only place this can run: the phone is not always near.

## Data to collect

The rule is only as good as the data it is tuned on.

- **Our own**: an opt-in recorder on the watch next to `PpgRawLogger`: the minute rows plus the label the wearer gives (a started workout, or a "this was a walk" tap afterwards), exported by hand. Nothing leaves the device by itself.
- **Public**: CAPTURE-24 (Oxford, wrist accelerometer, 151 participants, 3883 hours of which 2562 annotated, CC BY 4.0; [Scientific Data](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC11484779/)) has free-living labels. It is a 100 Hz Axivity AX3 on the dominant hand, not a watch: useful for the walk/run/sit shape, not for the exact numbers. PAMAP2 was not checked.

## VO2 max: what Samsung does, and what holds for women and men

Checked 2026-11-04 from public pages only; the Samsung app was not decompiled. Samsung publishes no formula. What is public:

- **Samsung's own description** (from secondary pages, not Samsung's): wrist heart rate together with GPS pace and distance during a qualifying outdoor run or brisk walk. Treat as unconfirmed.
- **Samsung Electronics patent applications** (abstracts only; the full texts could not be fetched): [US 2017/0079572](https://patents.google.com/patent/US20170079572A1/en) builds heart rate statistics from the wearer's measured heart rate and applies them in a regression equation for maximal oxygen uptake, ventilatory and lactate threshold and maximal heart rate; [US 2017/0282011](https://patents.justia.com/patent/20170282011) assesses fitness from the heart rate relative to the heart rate range and the exercise intensity measured by the sensors. Coefficients were not seen. A patent is not proof of what ships.
- **US 2017/0079572 A1, read in full** (Jang, Ko, Bae; filed 2016, Korean priority 2015): the heart rate is recorded during an exercise whose load rises (a graded test on a treadmill, ergometer or step, or a daily run, jog, walk or stair climb), averaged per minute and binned into a histogram. From it come statistics: skewness and kurtosis (the usual standardised third and fourth moments), quantiles (25 % or 75 %), mode, median, mean, standard deviation and the middle of minimum and maximum. VO2 max, the ventilatory and lactate thresholds and the maximal heart rate are each a linear regression on one statistic (`Y = a*X + b`), or on a statistic plus body information (`Y = a1*kurtosis + a2*BMI + b`, or with age as a third term). Body information may be sex, age, height, weight, waist-hip ratio and BMI. The idea: a fit person's heart rate rises more slowly under rising load, so the distribution has lower skewness and higher kurtosis. **No coefficients, no study population, no validation and nothing separate for women and men are given.** Speed and GPS do not appear. So the patent describes the shape of a model, not one we could rebuild, and its legal status was not checked: building this exact method would need that check first.
- **No peer-reviewed validation of Samsung's VO2 max was found.** Several search hits claim figures (a University of Michigan study, 82 % correlation, 1.8 ml/kg/min error); they came from low-quality sites and are not used here. A Samsung news item says a Michigan study is comparing smartwatch data with reference data; no results were found. The one peer-reviewed Galaxy Watch paper found, [Ferreira et al., JMIR Formative Research 2026](https://formative.jmir.org/2026/1/e83090/PDF), is about energy expenditure (148 adults, 80 men and 68 women, Cosmed K5), not VO2 max.
- **Garmin/Firstbeat** relate heart rate to running speed in qualifying sessions, a model rather than a plain regression ([Firstbeat](https://firstbeat.com/en/science-and-physiology/white-papers-and-publications/oxygen-consumption-and-maximal-oxygen-consumption-vo2max)). A 2016 Finnish master's thesis ([JYX](https://jyx.jyu.fi/handle/123456789/52750), 15 women, 14 men, cycling field test) found a mean overestimate of 0.54 ml/kg/min with limits of agreement about -8.9 to 5.2. A blog claims Garmin underestimates women and overestimates men; unverified.

So "the same as Samsung" cannot be rebuilt, and nothing shows it would be a good target. What can be built from verified sources:

| Route | Inputs | Sources and sex |
| --- | --- | --- |
| Heart rate ratio | HRmax, resting heart rate | [Uth et al. 2004](https://link.springer.com/doi/10.1007/s00421-003-0988-y): 15.3 x HRmax / HRrest, shown in well-trained men; the authors say other groups await validation. **Not for women or untrained people as it stands.** A 2022 study of male footballers found 14.6; women have a different factor in a separate Uth study, not read |
| Non-exercise equation | Age, waist, activity level, resting heart rate | [Nes et al. 2011, HUNT](https://www.qxmd.com/r/21502897) (doi 10.1249/MSS.0b013e31821d3f6f): sex-specific models, 4637 healthy adults, R2 0.61 men and 0.56 women, SEE 5.70 and 5.14 ml/kg/min. Coefficients not seen yet: read the full text. Needs waist circumference and activity level the app does not hold, which the app must ask for rather than guess |
| Women, cross-validated | Routine clinical measures | [Clasey et al. 2020](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9902048/), 188 midlife and older women against treadmill testing; not read in detail |
| From a run | Speed and heart rate on a qualifying outdoor effort | Needs GPS or the cadence and a calibrated stride; the fitting sources (ACSM equations, Firstbeat) were not read |

Recommendation: first the sex-specific HUNT equation as a resting estimate, labelled as one, once the coefficients are read and the missing inputs are asked for; a run-based estimate later. Both are estimates with a standard error of about 5 ml/kg/min and must say so. Nothing here is implemented.

## Public data to test walking analysis

Checked 2026-11-04; licences not all confirmed, so read each page before use.

| Dataset | What it holds | Fit |
| --- | --- | --- |
| [OxWalk](https://ora.ox.ac.uk/objects/uuid:19d3cb34-e2b3-4177-91b6-1bad0e0163e7) (Oxford, 2022) | 39 healthy adults, free living; Axivity AX3 on the dominant wrist and the hip, 100 Hz and 25 Hz side by side; steps labelled from foot video at heel strike | Best match: wrist, 25 Hz like `hr_raw`, step labels. Tests step detection and cadence, not step length |
| [CAPTURE-24](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC11484779/) | 151 people, wrist, 100 Hz, free-living labels, CC BY 4.0 | Walking versus other activities |
| PAMAP2 | 9 people, IMUs on wrist, chest and ankle at 100 Hz plus heart rate; walking, running, cycling | Heart rate with movement and the three sports; small |
| WISDM smartwatch | 51 people, watch and phone, 20 Hz, 18 activities | Walking detection at a watch-like rate; versions differ, check the one used |
| [PhysioNet multimodal gait](https://physionet.org/files/multimodal-gait-dataset/1.0.0/Dataset/README.md) | 59 young adults on a treadmill at 0.5, 0.75 and 1.0 m/s with IMU, EEG, EMG | Gait kinematics at fixed speeds; no wrist |

What a wrist can and cannot give is open: OxWalk's labels allow testing step detection and cadence from the wrist. Step length, asymmetry and double support need foot-worn sensors or a validated wrist model; no source for those was found yet, so they stay off the list until one is.

## More from the sensors

Each item needs a verified, cited source before it is implemented. Listed, not started.

| Metric | Inputs the watch has | Source status |
| --- | --- | --- |
| VO2 max (resting estimate) | Resting heart rate, a measured or age-estimated maximum heart rate | Uth, Sørensen, Overgaard, Pedersen, "Estimation of VO2max from the ratio between HRmax and HRrest", Eur J Appl Physiol 91:111-115 (2004): VO2max about 15.3 x HRmax / HRrest. The authors say it was shown for well-trained men and awaits validation in other groups; with an age-estimated HRmax the error stays comparable to other indirect tests. A weak estimate for a general user: show it as such, or leave it |
| VO2 max (from a run) | Speed from GPS or cadence, heart rate | Not looked up yet |
| Walking analysis (step length, asymmetry, double support, steadiness) | Wrist accelerometer, cadence | Not looked up yet. A wrist sees the arm swing, not the feet: what a wrist can do is a research question first |
| Cycling (cadence, power estimate, climbs) | Barometer, GPS, heart rate; no cadence sensor on the wrist | Not looked up yet |
| Training load, recovery | Heart rate over a workout | Not looked up yet |

## Wear OS workout idioms

OpenWatch's workout (`wear/workout/WorkoutScreen.kt`) is one screen: time, heart rate, distance and steps, with pause, resume and stop buttons, and a haptic cue when it ends.

| Idiom | Status |
| --- | --- |
| Horizontal pager with the page indicator, controls one swipe to the left | Open: the values and the buttons share one screen |
| Bottom action button (`EdgeButton`) | Open: the controls are full-width buttons |
| Ongoing Activity: the pill on the watch face and in recents, with a stopwatch | Open (OpenVitals had it on a branch that was not ported) |
| Ambient (always-on) mode of the workout screen | Open: the screen must dim and show only the elapsed time and heart rate; needs a watch to try |
| Haptic tick at each kilometre or mile, and at a heart rate zone change | Open: only the end of a workout has a cue |
| Auto-pause when standing still | Open: the tracker has pauses; the rule is not written |
| Watch face complications (steps, heart rate) | Open: tiles exist (`MetricTileService`), complications do not |
| Rotary crown on lists | The Compose lists scroll with it already |
| Always-on tile for a running workout | Open |

## Order of work

1. Collect labelled minute rows on the wearer's own watch for a few weeks (the opt-in recorder).
2. Write the detector as pure Kotlin in health-calculations' `signal` package, beside `AccelerationMinuteFeatures`, tuned on that data and CAPTURE-24, and its tests.
3. The notification first; recording by itself only after the detector has run silently for a while.
4. The metrics above one at a time, each after its source is checked and listed.
