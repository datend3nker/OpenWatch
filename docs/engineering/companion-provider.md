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
| Commands | `/commands` | OpenWatch | write (`insert`) | "start a workout on the watch" from OpenVitals' recording |

Rows are plain columns, no blobs, with a `contract_version` column on `/meta`. A change is
announced with `notifyChange` on the scope's URI, so a reader never polls.

## Permissions and consent

Three layers, all required:

1. **A permission per scope**, declared by the serving app:
   `tech.mmarca.openvitals.permission.READ_PROFILE`, `…READ_UNITS`,
   `tech.mmarca.openwatch.permission.READ_DEVICES`, `…SEND_COMMANDS`. The provider sets
   `readPermission`/`writePermission` per path (`<path-permission>`), so a grant for one scope
   opens nothing else.
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
OpenWatch's value needs a write scope on OpenVitals (`/profile` with `insert`/`update`,
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
