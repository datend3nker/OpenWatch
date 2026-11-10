# OpenLink: security fixes, and workouts the watch records itself (proposal)

> **Status:** proposal, nothing built. It builds on OpenLink version 1 as merged
> (`openlink/docs/spec.md`, OpenWatch #15 and #16, OpenVitals #346 and #347).
> **Decided** marks what the proposal's authors settled in discussion; everything else is for
> the maintainers.

OpenLink version 1 is the right base: a bound service gives events, notices when a client
dies, and has no start-up or install-order problem. This proposal keeps all of that. It adds
three things:

1. small security fixes to version 1, each one independent;
2. three design questions about version 1 for the maintainers;
3. a second kind of recording session, in which the watch records the whole workout and
   OpenWatch writes it to Health Connect, with pause and resume from either side.

## 1. Security fixes to version 1

Each fix is small and can be merged on its own.

### 1.1 Keep trust across a key rotation

`OpenLinkCallerResolver` compares only the caller's current certificate
(`signingCertificateHistory.last()`). After a regular key rotation (APK signature scheme v3) a
trusted client drops back to `PENDING`, and the user must approve it again.

Fix: keep the digest stored in the trust record, and check it with
`packageManager.hasSigningCertificate(packageName, digest, PackageManager.CERT_INPUT_SHA256)`
from API 28. That call follows the signing history. When `signingInfo.hasMultipleSigners()`
is true, every signer must match. On API 26 and 27 there is no rotation, and the present
`GET_SIGNATURES` check stays. A known signer is checked the same way.

### 1.2 Warn when a known name comes with an unknown key

An OpenVitals build that OpenWatch's list of known signers does not hold, for example an
F-Droid build or a build someone made themselves, shows on the Connected apps screen like any
other app. Nothing warns that another app may be using the name.

Fix: OpenWatch knows the package names of its first-party clients
(`tech.mmarca.openvitals`, `.debug`, `.ci`). When one of these names comes with a signer that
is not on the list, the consent card says so: "This app calls itself OpenVitals, but it was not
signed by the OpenVitals project. Allow it only if you built or installed it yourself and
trust where it came from."

### 1.3 Protect the consent screen from overlays

The Connected apps screen and the pending card can be opened by another app (through the
`consentIntent`), and nothing stops an app from drawing over them while the user taps "Allow".

Fix: `filterTouchesWhenObscured = true` on "Allow" and on the capability switches. From
API 31 the screen calls `window.setHideOverlayWindows(true)`, which needs the normal
permission `HIDE_OVERLAY_WINDOWS` (no dialog).

### 1.4 Limit pending records

Every `hello()` from an unknown app creates a `PENDING` record and a card on OpenWatch's home
screen. An app can create such a card without the user doing anything.

Fix: show a card only for a client that opened the consent screen from a tap
(`OpenLinkClient.openConsent`), and list the others only under Settings, Connected apps. Keep
at most 10 pending records, drop the oldest, and drop a pending record not seen for 30 days.

### 1.5 Limit recording requests

There is no limit on `startRecording`. Fix: one `startRecording` per client per 10 seconds,
and a session that was not stopped ends after 6 hours. A refused request reports
`FAILED` with the reason `rate_limit`.

## 2. Design questions about version 1

### 2.1 Measured values over IPC

Version 1 sends heart rate, steps, cadence and power live over OpenLink (`live`). That is a
second channel for health values beside Health Connect, open to any app the user allows. The
reason is real: Health Connect has nothing in real time, and a recording needs the current
heart rate.

The proposal's authors would rather send no measured values over OpenLink. If `live` stays,
they propose:

- `live` always asks the user once, also for a client signed by a known signer. Today a known
  signer gets every capability it asks for without a prompt, so the user never agrees to live
  heart rate on its own.
- The consent text for a client that is not first-party says that the app may pass the values
  on.

To check: OpenVitals writes the streamed heart rate into its own recording's samples
(`BleSensorCoordinator.acceptExternalHeartRate`). If OpenWatch also writes the watch's heart
rate for the same time to Health Connect, Health Connect holds that heart rate twice, under
two apps.

### 2.2 The first profile exchange

The profile is "last writer wins" in both directions. On the first connection the older
profile can silently replace the right one, depending on which side changed last.

Proposal: on the first connection, and only then, when both sides hold a profile and the
values differ, OpenWatch shows both field by field and asks which to keep. After that, last
writer wins as now.

### 2.3 Find hosts by action, not by package name

OpenVitals finds OpenWatch by the action `tech.mmarca.openlink.action.HOST` and the fixed
package name in `<queries>`. A second host, such as another watch app, cannot be found.

Proposal: the client declares `<queries><intent><action android:name="tech.mmarca.openlink.action.HOST" /></intent></queries>`
and lists hosts with `queryIntentServices`. This is the prerequisite for more than one
recorder (section 3.4).

OpenVitals' manifest still requests `tech.mmarca.openwatch.permission.OPENLINK`, which
OpenWatch no longer checks. It does no harm; it can go once no older OpenWatch is in use.

## 3. Recording on the watch

### 3.1 What changes

In version 1 a recording session means: the watch keeps sending live readings while
OpenVitals records the activity, the route and the exercise session. Neither watch can start a
workout of its own.

The second kind of session is **delegated recording (decided)**. When the user starts a
workout in OpenVitals and picks the watch, the watch records the whole workout, GPS included,
and OpenWatch writes one `ExerciseSessionRecord` with its route to Health Connect. OpenVitals
only controls it: start, pause, resume, stop. **Whoever records, writes.**

- One recorder, one session in Health Connect. Nothing is merged.
- OpenVitals records nothing itself in that case and needs no foreground service for it.
- The workout goes on when the phone stays behind.
- Health Connect lets only the writing app edit a record, so the user edits such a workout in
  OpenWatch. OpenVitals shows it like any workout another app wrote.
- There is no mixed recording of phone GPS and watch heart rate in this mode. A watch without
  GPS does not offer workouts that need a route.

The version 1 session (the watch streams, OpenVitals records) stays as it is.

### 3.2 Protocol additions

Version 2 of the protocol. AIDL methods are only appended at the end of `IOpenLinkHost`, so
the transaction codes of version 1 stay.

- `OpenLinkDevice` gains `recordingFeatures` (`GPS`, `HEART_RATE`, `PAUSE`) and
  `exerciseTypes` (the Health Connect exercise types the device can record itself).
- `OpenLinkRecordingRequest` gains `mode`: `STREAM` (version 1, the default) or `RECORD`
  (the device records). In `RECORD` mode `deviceId` is required.
- New oneway methods: `pauseRecording(String sessionId, long atMillis)` and
  `resumeRecording(String sessionId, long atMillis)`.
- `OpenLinkRecordingPhase` gains `PAUSED`. The reasons gain `busy`, `rate_limit` and
  `no_association`.

A host on version 1 would ignore `mode` and start a stream. So a client sends `RECORD` only to
a host whose `hello()` answered protocol version 2 or higher.

### 3.3 Rules for a recorded session

- **One workout per device.** A request for a device that records already gets `FAILED`,
  reason `busy`. Nothing is queued, nothing is taken over.
- **Repeats change nothing.** A repeated start, pause, resume or stop for the same `sessionId`
  has no further effect, so a client may repeat any request after a failure.
- **Two sides control it: the client that started it, and the watch.** Another client cannot
  control the session (as in version 1).
- **The host holds the state.** OpenWatch keeps the desired state (recording, paused, stopped)
  with the time of its last change, and brings the watch to it whenever the link is up. A
  request older than the last change is ignored. When the watch is out of reach, the state
  waits, and the watch follows when the link is back.
- **The watch controls too.** A pause, resume or stop on the watch sets the same state and
  reaches the client through `onRecordingStateChanged`. OpenVitals shows what the host reports;
  it holds no state of its own that could disagree.
- **One clock.** Every time is the phone's clock: the client's `atMillis`, and for a press on
  the watch the time the phone receives it.
- **Stop asks once on the watch** ("End workout?"); pause asks nothing. A stop from either side
  ends the workout, and OpenWatch saves it to Health Connect at once.
- **The watch shows who started it:** it vibrates and shows "Started by OpenVitals" with its
  controls.
- **What OpenVitals shows:** the device, the phase and the time since the start. No measured
  values come for a recorded session; a `live` subscription stays a separate capability
  (see 2.1).

### 3.4 Choosing the recorder in OpenVitals

- When the user taps start, OpenVitals offers "Phone" and every device that can record that
  type: `RECORD` support, the type in `exerciseTypes`, and `GPS` when the type needs a route.
- With only "Phone" there is no question. Otherwise the choice made last time for this type is
  preselected.
- Workouts with sets, rests between sets or the heart rate recovery test exist only in
  OpenVitals; it records those itself.
- When the hand-over fails (`FAILED`, or no answer within 10 seconds), OpenVitals asks:
  "The watch cannot start. Record on the phone?" It never switches to the phone by itself.

### 3.5 What OpenWatch needs

- **A workout on the Wear OS watch.** The watch app records GPS with `LocationManager` and heart
  rate with `SensorManager`, without Google's Health Services, so that the app stays free
  software.
- **The companion device association is required (decided).** The request reaches OpenWatch
  while it is in the background. OpenWatch already requests
  `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`, which works only while the
  watch has an association. Today `OnboardWearOsWatchUseCase` succeeds without one. It must
  fail instead, and watches onboarded earlier are asked once. Without an association a request
  fails with the reason `no_association`.
- **A foreground service for the workout.** This breaks OpenWatch's rule "One foreground
  service, and only for the scale" (`docs/engineering/architecture.md`, cross-cutting rule 1),
  and needs the maintainers' decision. The service would run only while a recorded session
  runs, of type `connectedDevice`, with a notification that names the client.
- **Health Connect permissions** `WRITE_EXERCISE` and `WRITE_EXERCISE_ROUTE`.

### 3.6 Later: one workout on several devices

Put off (decided). Several sessions in Health Connect would count distance and energy several
times. A later version would let one device be the primary that writes, and the others record
without writing. In `RECORD` mode `deviceId` is required, so version 2 leaves this open.

## 4. Order of work

1. The security fixes in section 1, each its own pull request in `openlink` or OpenWatch.
2. The answers to section 2.
3. The protocol additions in section 3.2 in `openlink`, with tests of the session rules in
   `OpenLinkHostCore` and `OpenLinkClientCore`.
4. OpenWatch: the association at onboarding, the workout on the watch, the foreground service
   (after the decision in 3.5), and writing the session to Health Connect.
5. OpenVitals: the recorder choice at the start, the question when the hand-over fails, and
   the session display.
