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
| Devices | `/devices` | OpenWatch, any recorder | read | the paired devices, what they can record, the state of a running workout |
| Commands | `call()` | OpenWatch, any recorder | write | OpenVitals starts, pauses and stops a workout the device records |

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
the beats, an exercise session with its route from a workout the watch recorded). The
provider adds no computation: it carries settings, state and commands.

## Delegated recording: OpenVitals starts, a connected device records (decided, 2026-11-08)

When the user starts a workout in OpenVitals and a connected app has a device that can record
it, that device records the whole workout, GPS included, and its app writes it to Health
Connect. OpenVitals is then only the remote control: it starts, pauses, resumes and stops, and
shows the state. **Whoever records, writes.**

- One recorder, one `ExerciseSessionRecord` with its route in Health Connect. Nothing is merged,
  and no measured value goes through the provider.
- OpenVitals records nothing itself in that case and needs no foreground service for it.
- The workout goes on when the phone stays behind.
- Health Connect lets only the writing app edit a record, so the user edits or deletes such a
  workout in the recorder's app. OpenVitals shows it like any workout another app wrote.
- There is no mixed recording (phone GPS with the watch's heart rate). A device without GPS does
  not offer outdoor workouts with a route; OpenVitals records those itself, and the watch writes
  its heart rate to Health Connect as it always does.

### Finding recorders

The contract is open, so OpenVitals knows no fixed package names for recorders.

- A recorder app exports a service with the action `<contract>.action.WORKOUT_RECORDER` and names
  its provider's authority in that service's `meta-data`. The service does nothing else; it
  exists to be found.
- OpenVitals declares `<queries><intent><action android:name="<contract>.action.WORKOUT_RECORDER" /></intent></queries>`
  and lists recorders with `queryIntentServices`. It then checks each as in "Reading it in
  OpenWatch": the provider's owner is the package that answered, and the user approved it in
  OpenVitals' sharing screen. An app the user did not approve is not offered.
- Each recorder app lists its devices on `/devices`, one row per device, with:
  - `device_id` (stable within that app), `name`, `link` (`connected`/`lost`);
  - `capabilities`, a list: `workout.record`, `workout.gps`, `workout.heart_rate`,
    `workout.pause`;
  - `exercise_types`, the Health Connect `ExerciseSessionRecord` types it records;
  - `workout_state`, `workout_session_id`, `workout_state_at` (below).

### Choosing at the start

- When the user taps start, OpenVitals offers "Phone" and every connected device that can record
  that type: it has `workout.record`, the type is in `exercise_types`, and it has `workout.gps`
  when the type needs a route.
- With only "Phone" there is no question. Otherwise the choice made last time for this type is
  preselected; one tap starts.
- OpenVitals hands over only workouts the recorder covers completely. Sets, rests between sets
  (`RESTING`) and the heart rate recovery test exist only in OpenVitals; such workouts it records
  itself.
- **When the hand-over fails** (`busy`, `unavailable`, device not reachable, no answer within
  10 seconds), OpenVitals asks: "The watch cannot start. Record on the phone?" It never starts
  on the phone by itself, so a workout is not recorded twice when the watch started after all.

### Commands

OpenVitals calls the recorder's provider with `call()`. Each needs the recorder's
`SEND_COMMANDS` permission and the user's approval of OpenVitals in the recorder's sharing
screen.

| Method | Extras | Answer |
| --- | --- | --- |
| `start_workout` | `device_id`, `session_id` (string, chosen by OpenVitals), `exercise_type` (int), `at`, and for later: `group_id`, `role` (`primary`), `write_health_connect` (`true`) | `status`; `ok` means accepted, not yet running |
| `pause_workout` | `session_id`, `at` | `status` |
| `resume_workout` | `session_id`, `at` | `status` |
| `stop_workout` | `session_id`, `at` | `status` |

`at` is OpenVitals' time of the command (epoch millis). `status` is `refused` with a `reason`
(`busy`, `not_owner`, `rate_limit`, `bad_argument`) when the recorder will not carry it out.

### Rules for the recorder

- **One workout per device.** A `start_workout` for a device that records already answers
  `refused`, `reason = busy`. Nothing is queued, nothing is taken over.
- **Idempotent.** A repeated command with the same `session_id` changes nothing and answers `ok`.
  `stop_workout` with a `session_id` that is not running answers `ok` and never stops another
  workout. A caller may repeat any command after a failure.
- **Only the starting app controls.** Pause, resume and stop with that `session_id` come from the
  app that started it, or from the device itself. Another app gets `refused`,
  `reason = not_owner`.
- **The recorder holds the state.** It keeps the desired state (`running`, `paused`, `stopped`)
  with the `at` of the last change and brings the device to it whenever the link is up. A
  command older than the last change is ignored. When the device is out of reach, `call()` still
  answers `ok`, `/devices` shows `link = lost`, and the device follows when the link is back.
- **The device controls too.** Pause, resume and stop on the watch set the same state, stamped
  with the phone's time when the message arrives, and appear on `/devices`. OpenVitals observes
  `/devices` while the workout runs and shows what the recorder reports; it has no state of its
  own that could disagree. All times are the phone's clock, so the watch's clock plays no part.
- **Stop asks once on the watch** ("End workout?"); pause asks nothing. A stop from either side
  ends the workout, and the recorder saves it to Health Connect at once. OpenVitals needs no
  save step of its own.
- **The watch shows who started it:** it vibrates and shows "Started by OpenVitals" with its
  controls.
- **Limits.** One `start_workout` per caller per 10 seconds. A workout without a stop ends after
  6 hours. Every command goes into the recorder's log for that caller (method, time, result; no
  values).
- **What OpenVitals shows** while the workout runs: the device, the state and the time since the
  start, from `/devices`. No distance, no heart rate: those are measured values and stay on the
  device and in Health Connect.

### Start from the background (OpenWatch)

The call reaches OpenWatch on a binder thread, often while OpenWatch is in the background. From
Android 12 an app in the background may not start a foreground service, and a provider call is
no exemption. OpenWatch's manifest already requests
`REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`, which lets it start one while the
watch has an active companion device association. The only foreground service OpenWatch
declares today is the scale's `ScaleListeningService`, so a recorded workout needs its own
service of type `connectedDevice` (and `location` while the watch sends GPS through the phone,
if it does). The Wear OS onboarding makes the association optional
(`OnboardWearOsWatchUseCase`). Without one, `start_workout` answers `unavailable` while OpenWatch
is in the background, and OpenVitals asks as above. OpenWatch also needs Health Connect's
`WRITE_EXERCISE` and `WRITE_EXERCISE_ROUTE`.

### Later: the same workout on several devices

To compare devices, the user may want one workout recorded on several at once. That raises
problems the first version does not solve:

- **Duplicates.** N sessions in Health Connect count distance and energy N times, in OpenVitals
  and in every other app. So exactly one device is `primary` and writes to Health Connect; the
  others start with `role = secondary` and `write_health_connect = false` and keep their
  recording in their own app, to export as a file for comparison.
- **No comparison through the provider.** Comparing the recordings would move measured values;
  the comparison works on files the user exports.
- **Partial starts.** OpenVitals waits for every answer. If the primary fails, it asks as above;
  if only a secondary fails, the workout goes on and OpenVitals says which device is missing.
- **Pause and stop on one device** apply to all: OpenVitals observes every device, takes the
  newest `at`, and sends it to the others.
- One `group_id` per workout ties the sessions together; each device has its own `session_id`.

The first version already carries `group_id`, `role` and `write_health_connect`, and a recorder
answers `refused`, `reason = bad_argument`, to `role = secondary` until it supports it. So the
later version needs no new contract version.

## Open points

1. ~~Protection level~~: `dangerous`, with a trust warning for an unknown signer (decided).
2. ~~Profile and units~~: OpenVitals owns them; the user settles differences (decided).
3. The contract's home: proposed a published library, included as a submodule until released.
4. ~~Commands~~: delegated recording, whoever records writes (decided). Still open: whether the
   Wear OS onboarding should require the companion device association, which the background
   start needs.
5. ~~Stop on the watch~~: ends the workout, the recorder saves it, the watch asks once (decided).
6. Several devices for one workout: sketched under "Later", not in the first version.

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
