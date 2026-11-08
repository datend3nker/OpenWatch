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

## The shape

Each app exports one provider, `<applicationId>.provider.companion`, read with
`ContentResolver.query` and observed with `registerContentObserver`, as OpenVitals already
reads CoMaps' navigation provider (`docs/features/comaps-navigation-context.md` in OpenVitals).
Every path is one **scope**, and every scope has its own permission and its own consent.

| Scope | Path | Served by | Read or write | First use |
| --- | --- | --- | --- | --- |
| Meta | `/meta` | both | read, no permission | contract version, which scopes exist |
| Body profile | `/profile` | OpenVitals | read | OpenWatch's walking energy and the watch's calories without a second profile |
| Units | `/units` | OpenVitals | read | the watch shows the user's units |
| Devices | `/devices` | OpenWatch | read | OpenVitals shows the paired watches, their last sync and battery |
| Commands | `/commands` | OpenWatch | write (`call()`) | "start a workout on the watch" from OpenVitals' recording |

Rows are plain columns, no blobs, with a `contract_version` column on `/meta`. A change is
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
   "Connected apps" screen. A reinstall with another key is a new app and asks again. The
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
elsewhere: the Bridge app, which owns every internet connection by design, would be one such
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

## Open points

1. ~~Protection level~~: `dangerous`, with a trust warning for an unknown signer (decided).
2. ~~Profile and units~~: OpenVitals owns them; the user settles differences (decided).
3. The contract's home: proposed a published library, included as a submodule until released.
4. Commands: which ones, and whether the watch confirms them on its screen.

## First slice, once decided

OpenVitals serves `/meta` and `/profile`; OpenWatch reads the profile when the grant is in,
falls back to its own, and shows the grant on a "Connected apps" screen. Tests on both sides:
the caller check (wrong package, wrong certificate, revoked scope), the columns, and the
change notification.

## Implementing it in OpenVitals (handoff)

What a first slice in OpenVitals needs, from the Android `ContentProvider` model
(<https://developer.android.com/reference/android/content/ContentProvider>) and the CoMaps
integration OpenVitals already reads. Written so a new session can start from this section.

### The provider

- One class, `CompanionProvider : ContentProvider`, authority
  `tech.mmarca.openvitals.provider.companion` (the debug build: `${applicationId}` in the
  manifest, so `.debug` gets its own). `onCreate()` does nothing heavy: the provider is created
  on the main thread at process start, before `Application.onCreate` finishes.
- Reads go through `query()` and return a `MatrixCursor` with fixed columns. Ignore
  `selection`, `selectionArgs` and `sortOrder` (or refuse them): there is no database behind it,
  and a caller must not be able to shape a query.
- Writes and commands go through `call(method, arg, extras)`, not `insert`/`update`: one entry
  point, a method name per action, a `Bundle` in and out, easy to version. `insert`, `update`,
  `delete` throw `UnsupportedOperationException`.
- `getType()` returns a `vnd.android.cursor.item/vnd.tech.mmarca.openvitals.<scope>` type per path.
- After a change OpenVitals calls `context.contentResolver.notifyChange(uri, null)` for the
  scope's URI; a reader observes with `registerContentObserver`. Querying starts OpenVitals'
  process if it is not running, so a reader queries only when it needs the value.

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

`/meta` carries no permission. `grantUriPermissions="false"`: no one can hand a URI grant on.
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
4. Not approved: return an empty cursor with one column `status` = `consent_required`, and post
   nothing else. OpenVitals never shows a dialog on its own from the provider; the user approves
   in OpenVitals' "Connected apps" screen, which a caller can open with an explicit intent
   (`ACTION_MANAGE_CONNECTED_APP`, extra: the caller's package and the scopes it asks for).
5. Keep a small log per caller (scope, time, granted or refused) for that screen; log no values.

`Binder.clearCallingIdentity()` only around OpenVitals' own reads inside the provider, restored
in `finally`.

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

### Columns of the first scopes

`/meta`: `contract_version` (int), `scopes` (comma list), `app_version`.
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

### Order of work

1. The contract (authority, paths, columns, permission names, `contract_version = 1`) as one
   Kotlin file, to become the shared library later.
2. `ConsentStore` and the caller check, pure and tested.
3. `CompanionProvider` with `/meta` and `/profile`, and the manifest entries.
4. The "Connected apps" screen with the trust warning.
5. OpenWatch: the `<queries>` entry, the permission request, reading the profile, the
   field-by-field choice when values differ, and its fallback to its own profile.
