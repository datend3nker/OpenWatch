# Talking to OpenVitals: a companion ContentProvider (proposal)

> **Status:** proposal, nothing built. Decided points are marked; the rest is for the maintainers.
> **Scope:** OpenVitals and OpenWatch first; other apps later, under the same rules.

## Why

Health Connect carries the health data, and it stays the only channel for it. What it cannot
carry is the rest: settings both apps need (the body profile, units), the state of the other
app (which watches are paired, when they last synced), and commands ("a workout started on
the phone, record it on the watch"). Today a user enters the body profile twice and the two
apps cannot see each other.

Health Connect also tells data apart only by the writing app, so it cannot say which steps came
from the watch and which from another source. The provider does not fix that, and must not
try by copying health values: a value that bypasses Health Connect bypasses its permissions.
A value that came from Health Connect never goes out through the provider, even when the same
field exists in both (the weight in the body profile).

## The shape

Each app exports one provider, `<applicationId>.provider.companion`, read with
`ContentResolver.query` and observed with `registerContentObserver`, as OpenVitals already
reads CoMaps' navigation provider (`docs/features/comaps-navigation-context.md` in OpenVitals).
Every path is one **scope**, and every scope has its own permission and its own consent.

**Beside OpenVitals' data provider (decided, 2026-11-08).** OpenVitals already serves its
read-only data provider `<applicationId>.data`, which OpenBridge reads (11 groups, status in the
cursor extras, a switch "Share with other apps" that starts off). The companion provider stays a
second provider, because it carries writes and commands and the data provider stays read-only.
For users and for other apps both look like one system:

- the same names: `/groups` with an `api_version` column, `status` in the extras, permissions
  named `<applicationId>.permission.<ACTION>_<SCOPE>`;
- one switch: "Share with other apps" also covers the companion scopes, and while it is off
  every scope answers `sharing_off`;
- one screen: the action `<applicationId>.action.MANAGE_DATA_SHARING` opens the serving app's
  sharing screen, which lists every app with its data groups and its companion scopes;
- one contract library for both providers (open point 3), with OpenBridge as its second user.

| Scope | Path | Served by | Read or write | First use |
| --- | --- | --- | --- | --- |
| Groups | `/groups` | both | read, no permission | API version, which scopes exist |
| Body profile | `/profile` | OpenVitals | read | OpenWatch's walking energy and the watch's calories without a second profile |
| Units | `/units` | OpenVitals | read | the watch shows the user's units |
| Devices | `/devices` | OpenWatch | read | OpenVitals shows the paired watches, their last sync and battery |
| Commands | `/commands` | OpenWatch | write (`call()`) | "start a workout on the watch" from OpenVitals' recording |

Rows are plain columns, no blobs, with an `api_version` column on `/groups`. A change is
announced with `notifyChange` on the scope's URI, so a reader never polls.

## Permissions and consent

Three layers, all required:

1. **A permission per scope**, declared by the serving app and named after its application id:
   `<applicationId>.permission.READ_PROFILE`, `…READ_UNITS`, `…WRITE_PROFILE` on OpenVitals,
   `…READ_DEVICES`, `…SEND_COMMANDS` on OpenWatch. The release, `.debug` and `.ci` builds are
   signed with different keys, and Android refuses to install a second app that defines the same
   permission name with another key; with the application id in the name, each build defines its
   own. The provider sets `readPermission` per path (`<path-permission>`), so a grant for one
   scope opens nothing else. Writes and commands go through `call()`, which `<path-permission>`
   does not cover; `call()` checks the write permission itself.
2. **A consent list inside the serving app.** The provider looks up the caller with
   `Binder.getCallingUid()`, its package and its signing certificate digest, and answers only
   if the user approved that package, that certificate and that scope on the serving app's own
   "Share with other apps" screen. A reinstall with another key is a new app and asks again. The
   screen lists every grant, its last use, and revokes in one tap.
3. **Least data.** A scope returns what its use needs and nothing more (the profile returns
   height, weight, sex and birth year, not the name), commands are a fixed list with checked
   arguments, and each command is rate-limited and kept in a log the user can read.

**Protection level (decided, 2026-11-08): `dangerous` for every scope.** The calling app asks
at run time, as with CoMaps, which works whatever key signed a build (Play, F-Droid, GitHub),
and is the same path a third-party app takes later. `knownSigner` was the alternative: no
prompt, but it refuses any build signed by a key not in the list, such as an F-Droid build.

The serving app knows the certificates of the official OpenVitals and OpenWatch builds. When a
caller presents the expected package name with a certificate it does not know, the consent
screen says so before the user approves: "This app calls itself OpenVitals, but it was not
signed by the OpenVitals project. Approve only if you built or installed it yourself and trust
where it came from." A caller that is neither gets the plain consent text for other apps.

The runtime permission decides who may ask; the consent list decides who gets an answer.

## Later: other apps

A public client goes through the same three layers, with `dangerous` permissions and the
consent screen. The consent text says plainly that the other app may send what it reads
elsewhere: OpenBridge, which owns every internet connection by design, would be one such
client. OpenVitals and OpenWatch stay offline; a provider is local IPC and needs no network
permission.

## Who owns the profile and the units (decided, 2026-11-08)

OpenVitals owns them; OpenWatch reads them and keeps its own only as the fallback when
OpenVitals is not installed or not granted. When both hold a value and they differ (the first
connection, or a change made in OpenWatch while disconnected), OpenWatch does not choose: it
shows both values field by field and asks which to keep, OpenVitals' or its own. Keeping
OpenWatch's value needs a write scope on OpenVitals (`call("set_profile_fields")`,
`…WRITE_PROFILE`), granted separately from the read.

## Where the contract lives (open point 3)

The two apps share no code at run time. The contract (authorities, paths, columns, permission
names, versions) is small. What others do:

- **A small published library:** OpenKeychain's `openpgp-api` (used by K-9/Thunderbird),
  Muzei's `muzei-api`, Health Connect's own client. A third party adds one dependency and gets
  the names, the versions and helpers.
- **A written contract only:** CoMaps' navigation provider, which OpenVitals mirrors by hand;
  Gadgetbridge's intent API. Quick for a few partners; every side keeps a copy.

Proposed: the library, because the provider is meant to become public. A repository of its own,
pure Kotlin/JVM with no dependencies (constants, columns, versions, permission names, the
consent texts' keys), published to Maven Central, which F-Droid builds can fetch. Until the
first release both apps include it as a Git submodule, as `:wearlink` is one source for the
watch link.

## What OpenWatch computes

OpenWatch writes the watch's data to Health Connect and computes only what a Health Connect
record needs from the watch's raw values (a sleep session from the minute rows, an RMSSD from
the beats). The provider adds no computation: it carries settings, state and commands.

## Commands: a workout on the watch when OpenVitals starts one

The first command. When the user starts a recording in OpenVitals, the watch starts a workout
with it and stops with it, so the heart rate of the workout comes from the watch.

**Direction.** OpenWatch serves the command; OpenVitals calls it:
`call(authority, "start_workout", null, extras)` on `<openwatch>.provider.companion`. It needs
`<openwatch>.permission.SEND_COMMANDS` and the user's approval in OpenWatch's sharing screen.
The other direction (a workout started on the watch makes OpenVitals record the route) is a
later command served by OpenVitals, under the same rules.

| Method | Extras | Answer |
| --- | --- | --- |
| `start_workout` | `session_id` (string, OpenVitals' recording id), `exercise_type` (int, Health Connect's `ExerciseSessionRecord` type), `started_at` (epoch millis) | `status`: `ok` means accepted, not yet running |
| `pause_workout` | `session_id`, `at` (epoch millis) | `status` |
| `resume_workout` | `session_id`, `at` (epoch millis) | `status` |
| `stop_workout` | `session_id`, `at` (epoch millis) | `status` |

`status` is `refused` with a `reason` (`busy`, `not_owner`, `rate_limit`, `bad_argument`) when
OpenWatch will not carry a command out.

### One workout at a time

- OpenWatch runs **at most one workout**, linked or started on the watch itself. A
  `start_workout` with a new `session_id` while another workout runs answers `refused`,
  `reason = busy`. Nothing is queued and nothing is taken over, as OpenVitals' own foreground
  services refuse rather than queue. OpenVitals then says that the watch already records a
  workout; the user stops it on the watch.
- **A linked workout has two sides: the app that started it and the watch.** Both may pause and
  resume it, and the other side follows (below). Any other app, or a command with another
  `session_id`, gets `refused`, `reason = not_owner`, and changes nothing. `stop_workout` with an
  id that is not running at all answers `ok` and changes nothing; it never stops the running
  workout.
- OpenVitals itself records one activity at a time (its one-foreground-service rule), so it never
  sends a second `session_id` while the first runs.

### Pause and resume

- OpenVitals' recording knows `RECORDING`, `RESTING` (rest between sets) and `PAUSED`. Only
  `PAUSED` sends `pause_workout`. `RESTING` sends nothing: the watch keeps measuring during a
  rest, and OpenVitals' own bookkeeping handles the rest.
- In a pause the watch stops the workout clock, the distance and the energy, and shows "Paused".
  It keeps measuring heart rate, because a heart rate sample in Health Connect stands on its own
  and the recovery in a pause is useful.
- OpenVitals writes the pauses into its `ExerciseSessionRecord` as segments of type
  `EXERCISE_SEGMENT_TYPE_PAUSE`, so Health Connect knows them once, from the session.
- `pause_workout` while paused and `resume_workout` while running answer `ok` and change
  nothing.
- **Pause on the watch pauses OpenVitals too.** The user pauses or resumes on the watch;
  OpenWatch sets the desired state, with the phone's time when the message arrives as `at`, and
  announces it on
  `/devices` (`workout_state = paused`, `workout_state_at`). OpenVitals observes `/devices` for
  the `session_id` it started, during the whole recording (its foreground service keeps it
  running), and pauses or resumes its own recording when the state differs from its own and is
  newer.
- OpenVitals follows by reading, not by a command it serves. It needs no new write scope; the
  `READ_DEVICES` grant it already holds for the linked workout is enough. It never follows a
  state for a `session_id` it did not start.
- **No echo.** When OpenVitals follows a pause from the watch, it sends `pause_workout` like after
  any pause of its own. OpenWatch is already paused and answers `ok`, so the two sides do not
  bounce the state back and forth.
- **Both at the same moment.** Pause on the phone and resume on the watch within a second: the
  newer `at` wins on both sides, because both apply the same rule to the same two times. Every
  `at` is a time of the phone's clock, so the watch's clock plays no part. The pause on the
  watch's screen starts at once; the pause in the record starts when the phone has it, a moment
  later.

### Stop on either side stops and saves both (decided, 2026-11-08)

- **Stop in OpenVitals:** OpenVitals sends `stop_workout`; the watch ends its workout, and
  OpenWatch writes the watch's samples for the session.
- **Stop on the watch:** the watch asks once ("End workout?"), because a stop also ends the
  phone's route recording. Pause asks nothing. OpenWatch then sets the desired state `stopped`
  and announces it on `/devices`; OpenVitals follows as it does for a pause and stops its
  recording.
- **Saving without the phone in hand.** Today a stop in OpenVitals does not save:
  `ActivityEntryViewModel.finishGpsRecording` keeps the finished recording on disk and opens the
  review form, and the user saves there. A stop from the watch needs a second way: OpenVitals
  finishes the recording, saves it at once with the values the review form would start with
  (type, start, end, route, distance), and posts a notification "Workout saved", which opens the
  saved activity in edit mode. The user corrects or deletes it there. The recording stays on disk
  until the save succeeded, as today, so a process death in between loses nothing.
- **Discard in OpenVitals:** OpenVitals sends `stop_workout`; the watch ends its workout. The
  watch's heart rate samples stay in Health Connect, because they are measurements of their own;
  no exercise session is written for them.
- A stop that arrives while the link is lost: the watch keeps running until the link comes back,
  then follows the desired state. The maximum length of a linked workout (6 hours) applies to
  both sides.

### Desired state instead of a command queue

OpenWatch does not forward each command to the watch as it comes. It stores the **desired
state** of the linked workout (`running`, `paused`, `stopped`, with the `at` of the last
command) and brings the watch to it whenever the link is up:

- a command older (`at`) than the last one applied for that session is ignored, so commands that
  arrive out of order cannot undo a newer one;
- when the watch is out of reach, `call()` still answers `ok`; the watch follows when the link
  comes back, and `/devices` shows `workout_state` with `link = lost` until then;
- a repeated command changes nothing, so OpenVitals may repeat any command after a failure.

`/devices` gains `workout_state` values `paused` and `stopping`, and a column `link`
(`connected`/`lost`).

**Rules:**

- **Idempotent by `session_id`.** A second `start_workout` with the same id starts nothing new
  and answers `ok`. OpenVitals may therefore repeat a command after a failure without starting
  two workouts.
- **`call()` answers at once.** The watch link can take seconds, and a binder call must not wait
  for it. The real state goes to `/devices` (`workout_session_id`, `workout_state`:
  `idle`/`starting`/`running`/`paused`/`stopping`/`failed`), announced with `notifyChange`. OpenVitals shows
  "recording on the watch" only once that says `running`.
- **One workout in Health Connect.** OpenVitals writes the `ExerciseSessionRecord` (it started the
  recording and holds the route). For a workout linked by `session_id`, OpenWatch writes only the
  watch's samples (heart rate) for that time and no second session, or Health Connect shows the
  workout twice. Its `clientRecordId`s are derived from `session_id`, so a repeated sync
  overwrites instead of duplicating.
- **Start from the background.** The call reaches OpenWatch on a binder thread, often while
  OpenWatch is in the background. From Android 12 an app in the background may not start a
  foreground service, and a provider call is no exemption. OpenWatch holds a companion device
  association for the watch, and its manifest already requests
  `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`; with an active association for
  the watch it may start a foreground service. The only foreground service OpenWatch declares
  today is the scale's `ScaleListeningService`, so a linked workout needs its own service of type
  `connectedDevice`. The Wear OS onboarding makes the association optional
  (`OnboardWearOsWatchUseCase`: onboarding succeeds without it). Without an association
  `start_workout` answers `unavailable` while OpenWatch is in the background, and OpenVitals says
  that the watch must be connected in OpenWatch first.
- **The watch shows it.** The workout starts without a question on the watch, since the user
  just tapped start on the phone. The watch vibrates, shows "Started by OpenVitals" and offers
  stop. A stop on the watch stops and saves on both sides (below).
- **Limits.** One `start_workout` per caller per 10 seconds; more answer `refused`. Every command
  goes into OpenWatch's log for that app (method, time, result; no values).
- **No stop arrives.** If OpenVitals dies without `stop_workout`, the watch would run on.
  OpenWatch ends a linked workout after a fixed maximum (proposed: 6 hours), and the watch's stop
  always works.

## Open points

1. ~~Protection level~~: `dangerous`, with a trust warning for an unknown signer (decided).
2. ~~Profile and units~~: OpenVitals owns them; the user settles differences (decided).
3. The contract's home: proposed a published library, included as a submodule until released.
4. Commands: `start_workout` and `stop_workout` as above (proposed). Open: the maximum length of a
   linked workout, and whether the Wear OS onboarding should require
   the companion device association, which the background start needs.

5. ~~Stop on the watch~~: stops and saves on both sides, the watch asks once (decided).

## First slice, once decided

OpenVitals serves `/groups` and `/profile`; OpenWatch reads the profile when the grant is in,
falls back to its own, and shows the grant on a "Share with other apps" screen. Tests on both sides:
the caller check (wrong package, wrong certificate, revoked scope), the columns, and the
change notification.

## Implementing it in OpenVitals (handoff)

What a first slice in OpenVitals needs, from the Android `ContentProvider` model
(<https://developer.android.com/reference/android/content/ContentProvider>) and the CoMaps
integration OpenVitals already reads. Written so a new session can start from this section.

### The provider

- One class, `CompanionProvider : ContentProvider`, authority
  `tech.mmarca.openvitals.provider.companion` (the debug build: `${applicationId}` in the
  manifest, so `.debug` gets its own).
- **Process start.** Android creates the provider on the main thread *before*
  `Application.onCreate()` runs, and publishes it to other apps at once. A `query()` or `call()`
  from another app can therefore arrive on a binder thread while `OpenVitalsApp.onCreate()` is
  still running: before Hilt's component exists, and while the Flutter migration
  (`data/migration/`) may still be writing the preferences the profile lives in. So:
  - `onCreate()` returns `true` and does nothing else.
  - `OpenVitalsApp` opens a process-wide startup gate as the last step of `onCreate()`. No such
    signal exists yet; add one (a `CountDownLatch(1)` in an `object`, counted down once).
  - `query()` and `call()` wait on the gate with a timeout of 5 seconds. On timeout they answer
    `status = unavailable`. Only after the gate do they fetch their dependencies, with
    `EntryPointAccessors.fromApplication(context.applicationContext, CompanionEntryPoint::class.java)`;
    Hilt cannot inject a `ContentProvider` with `@AndroidEntryPoint`.
  - A query from another app starts OpenVitals' whole process, migration and start-up work
    included. That is one more reason a reader queries only when it needs the value.
- **Source of the profile.** `/profile` reads only the profile the user entered
  (`PreferencesRepository`, the `body_profile_*` keys). It never falls back to Health Connect's
  latest height or weight, as `BmrEstimateService` does for its own estimate. A user who keeps
  their weight only in Health Connect gets no weight through the provider; that is intended. The
  provider package imports nothing from `healthconnect/`, and `HealthConnectLayeringTest` gets a
  rule for it.
- Reads go through `query()` and return a `MatrixCursor` with fixed columns. Ignore
  `selection`, `selectionArgs` and `sortOrder` (or refuse them): there is no database behind it,
  and a caller must not be able to shape a query.
- Writes and commands go through `call(method, arg, extras)`, not `insert`/`update`: one entry
  point, a method name per action, a `Bundle` in and out, easy to version. `insert`, `update`,
  `delete` throw `UnsupportedOperationException`.
- `getType()` returns a `vnd.android.cursor.item/vnd.tech.mmarca.openvitals.<scope>` type per path.
- After a change OpenVitals calls `context.contentResolver.notifyChange(uri, null)` for the
  scope's URI; a reader observes with `registerContentObserver`. `ConsentStore.approve()` and
  `ConsentStore.revoke()` do the same for every scope they change, so a reader that lost its
  consent learns it at once and does not keep showing old values.
- `/groups` lists the scopes this build serves, not the scopes this caller was granted: it has no
  permission, so it must not tell one caller about another's consent.

### Manifest

```xml
<permission
    android:name="${applicationId}.permission.READ_PROFILE"
    android:label="@string/companion_permission_read_profile"
    android:description="@string/companion_permission_read_profile_body"
    android:protectionLevel="dangerous" />
<!-- …READ_UNITS, WRITE_PROFILE the same way -->

<provider
    android:name=".companion.CompanionProvider"
    android:authorities="${applicationId}.provider.companion"
    android:exported="true"
    android:grantUriPermissions="false">
    <path-permission android:pathPrefix="/profile"
        android:readPermission="${applicationId}.permission.READ_PROFILE" />
    <path-permission android:pathPrefix="/units"
        android:readPermission="${applicationId}.permission.READ_UNITS" />
</provider>
```

`/groups` carries no permission. `grantUriPermissions="false"`: no one can hand a URI grant on.
`call()` is not covered by `<path-permission>`; it checks the permission itself (below). There
is no `writePermission` on a path: no write goes through `insert` or `update`.

The contract builds a permission name from the serving app's package
(`permissionName(servingPackage, "READ_PROFILE")`), so a caller asks for the name that belongs to
the build it found, not a fixed string.

### The caller check, in every entry point

1. `val caller = callingPackage ?: throw SecurityException()` (`ContentProvider.getCallingPackage()`,
   which the system checks against the calling uid).
2. `context.checkCallingPermission(permission) == PERMISSION_GRANTED` for the scope, also in
   `query()` (defence in depth beside the manifest) and always in `call()`.
3. The consent list: `ConsentStore.approvedDigests(caller, scope)` returns the SHA-256 digests
   of the signing certificates the user approved, and the provider checks each with
   `packageManager.hasSigningCertificate(caller, digest, PackageManager.CERT_INPUT_SHA256)`.
   That call follows the signing history, so an app that rotated its key (APK signature scheme
   v3) still matches the certificate the user approved. When `signingInfo.hasMultipleSigners()`
   is true, the provider requires every signer's digest, not one. Do not hash
   `apkContentsSigners` by hand: that misses rotation. A caller whose certificate is not the one
   approved gets nothing, so a reinstall with another key asks again.

   `hasSigningCertificate` needs API 28, and OpenVitals' `minSdk` is 26. Key rotation also came
   with API 28, so on API 26 and 27 the provider reads `getPackageInfo(caller, GET_SIGNATURES)`,
   hashes every signature, and requires the set to equal the approved set. One function holds
   both branches, and the tests cover both.
4. Not approved: return a cursor with the scope's normal columns and no rows, and put
   `status = consent_required` in its extras (below). Post nothing else. OpenVitals never shows a dialog on its own from the provider; the user approves
   in OpenVitals' "Share with other apps" screen, which a caller can open with an explicit intent
   (`<applicationId>.action.MANAGE_DATA_SHARING`, extras: the caller's package and the scopes it
   asks for).
5. Keep a small log per caller (scope, time, granted or refused) for that screen; log no values.

**The status of an answer** travels in `Cursor.setExtras(Bundle)` under the key `status`, as in
OpenBridge's contract with OpenVitals' data provider, never as a column. `call()` puts the same
key in its result bundle.

| `status` | Meaning | Rows |
| --- | --- | --- |
| `ok` | the answer is complete | the data |
| `sharing_off` | the switch "Share with other apps" is off | none |
| `consent_required` | permission granted, but the user has not approved this caller for this scope | none |
| `refused` | a `call()` with arguments out of range, an unknown method, or over its rate limit | none |
| `unavailable` | the startup gate timed out, or this build does not serve the scope | none |

- The columns never change with the status, so a reader knows the shape in advance and a test
  checks one shape per scope.
- A status that is not `ok` comes with no rows. A reader that ignores the status sees an empty
  answer, never wrong values.
- The contract ships `CompanionStatus.of(cursor)`. A missing extra or an unknown value reads as
  `unavailable`, never as `ok`. A later status (for example `partial`) is a new value, not a new
  column.
- `adb shell content query` does not show extras. For debugging, the provider writes one log
  line per answer with the scope and the status, without values. `/groups` does not carry the last
  status, because anyone can read `/groups`.

`Binder.clearCallingIdentity()` only around OpenVitals' own reads inside the provider, restored
in `finally`.

### The consent screen

The "Share with other apps" activity is exported: any app can open it, and the package in the intent
extra comes from that app. So:

- The extra only preselects the app on the screen. If no package of that name is installed, the
  screen says so and offers no approval.
- The certificate digest is read from `PackageManager` when the user taps "Allow", not when the
  screen opens, and that is the digest stored. The screen shows its first 8 hex characters, so a
  user who builds their own copy can compare.
- The "Allow" button sets `filterTouchesWhenObscured="true"`; from API 31 the activity calls
  `window.setHideOverlayWindows(true)`, which needs the `HIDE_OVERLAY_WINDOWS` permission (normal,
  no dialog). Another app cannot then draw over the screen and steer the tap.

### The trust warning

OpenVitals knows the certificate digests of the official OpenWatch builds (Play, F-Droid,
GitHub release) as constants. On the consent screen:

- package `tech.mmarca.openwatch` (or `.debug`) with a known digest: the normal text;
- that package name with an unknown digest: the warning ("calls itself OpenWatch, but was not
  signed by the OpenWatch project; approve only if you built or installed it yourself and
  trust where it came from");
- any other package: the plain text for other apps, which says that the app may send what it
  reads elsewhere.

### Things Android does that matter here

- **Package visibility (Android 11+):** the caller must declare the provider, or OpenVitals is
  invisible to it: `<queries><provider android:authorities="tech.mmarca.openvitals.provider.companion" /></queries>`
  (one per build flavour, as OpenVitals does for CoMaps).
- **Install order of custom permissions:** a `dangerous` permission defined by OpenVitals can be
  requested only once OpenVitals is installed. A caller installed first must request it again
  later, so OpenWatch asks at the moment it wants the profile, not at its own install.
- **No duplicate definitions:** Android refuses to install a second app that defines the same
  permission name with another signer, so a squatter installed first would block OpenVitals'
  install rather than take the permission. The consent list (step 3) is what protects the data
  either way.
- **Runtime dialog text:** custom permissions are no longer grouped (Android 10+); the dialog
  shows the permission's own `label`, so write it as a plain sentence.

### Reading it in OpenWatch

The reader treats OpenVitals as untrusted input, as OpenVitals treats CoMaps:

- **Three builds.** As OpenBridge does, OpenWatch lists the permissions and the `<queries>`
  entries of all three OpenVitals builds (release, `.debug`, `.ci`), finds the installed ones
  with `resolveContentProvider`, and lets the user choose when more than one is installed.

- **Owner check.** Before the first query, `resolveContentProvider(authority)` must name the
  expected OpenVitals package, and that package must carry one of the certificate digests of the
  official OpenVitals builds. A known package name with an unknown digest gets the same warning as
  above, the other way round: "This app calls itself OpenVitals, but was not signed by the
  OpenVitals project."
- **Short cursors.** Every cursor is read inside `use {}` and closed at once. An open cursor holds
  a stable connection to OpenVitals' process, and if that process dies, Android kills the reader
  with it.
- **Failures become "not connected".** `SecurityException` (permission revoked),
  `IllegalArgumentException` (unknown path, older contract), `DeadObjectException` and a missing
  column all end in the same state: OpenWatch uses its own profile and shows the connection as
  off. No retry loop.
- **Observer only while needed.** The `ContentObserver` is registered while a screen or service
  needs the value, and unregistered after.
- Values are checked like any input: ranges for height and weight, known values for `sex` and
  `unit_system`, anything else is dropped.

### Columns of the first scopes

`/groups`: `api_version` (int), `scopes` (comma list), `app_version`.
`/profile`: `height_cm` (real), `weight_kg` (real), `sex` (`female`/`male`/`unspecified`),
`birth_year` (int), `updated_at` (epoch millis). No name, no exact birth date.
`/units`: `unit_system` (`metric`/`imperial`), `updated_at`.
`call("set_profile_fields", null, bundle)`: the fields to overwrite, needs `WRITE_PROFILE`;
answers `ok` or `refused` in the result bundle.

### Tests to write first

- The caller check: no permission, permission but no consent, consent for another certificate,
  revoked consent, approved: one test each, with a fake `PackageManager` and consent store.
- The columns and their types for each scope, and that `selection` is refused.
- The change notification after the profile is edited.
- A provider test through `ProviderTestRule` (or Robolectric's `ContentResolver`) for the whole
  path once the pure parts pass.
- A query before the startup gate opens waits, and one after the timeout answers `unavailable`.
- Revoking a consent notifies the scope's URI.
- `/profile` returns no weight when only Health Connect has one.
- OpenWatch: wrong owner, unknown digest, revoked permission, provider gone; each ends in the
  fallback.

### Order of work

1. The contract (authority, paths, columns, permission names, `api_version = 1`) as one
   Kotlin file, to become the shared library later.
2. `ConsentStore` and the caller check, pure and tested.
3. `CompanionProvider` with `/groups` and `/profile`, and the manifest entries.
4. The "Share with other apps" screen with the trust warning and the overlay protection.
5. OpenWatch: the `<queries>` entry, the permission request, reading the profile, the
   field-by-field choice when values differ, and its fallback to its own profile.

The repository gates in OpenVitals apply to every step:

- The permission labels and descriptions and the consent texts are strings: they go to
  `values/strings.xml` and to every `values-*/strings.xml`, and `python3 scripts/verify-translations.py`
  passes.
- The new package (`companion/`) goes into `docs/engineering/architecture.md`, or
  `ArchitectureDocTest` fails.
- Each new file stays under 800 lines (`FileSizeRatchetTest`), each function under 150
  (`FunctionLengthRatchetTest`).
