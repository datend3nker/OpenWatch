# Talking to OpenVitals: a companion ContentProvider (proposal)

> **Status:** proposal, nothing built. For the maintainers to decide the open points below.
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

For the protection level of the permissions there are two candidates (open point 1):

- `knownSigner` (Android 12+): granted without a prompt to apps signed with the listed
  certificates. Smooth for OpenVitals and OpenWatch, but only if every build that users get
  (Play, F-Droid, GitHub releases) is signed with a key in the list; an F-Droid build signed
  by F-Droid's key would be refused. Before Android 12 it falls back to `signature`.
- `dangerous`: a runtime prompt in the calling app, as CoMaps does. Works for any signer, and
  is the same path a third-party app takes later.

Either way layer 2 stays: the protection level decides who may ask, the consent list decides
who gets an answer.

## Later: other apps

A public client goes through the same three layers, with `dangerous` permissions and the
consent screen. The consent text says plainly that the other app may send what it reads
elsewhere: the Bridge app, which owns every internet connection by design, would be one such
client. OpenVitals and OpenWatch stay offline; a provider is local IPC and needs no network
permission.

## Where the contract lives (open point 3)

The two apps share no code at run time. The contract (authorities, paths, columns, permission
names, versions) is small; options are a tiny published library both depend on, or the same
file kept in both repositories with a test on each side that checks it against a written
specification. `:wearlink` is the precedent for one source of truth.

## Open points

1. Protection level: `knownSigner` with the release keys, or `dangerous` for everyone.
2. Which app owns the body profile and the units. Proposed: OpenVitals; OpenWatch reads them
   and keeps its own only when OpenVitals is not installed or not granted.
3. The contract's home: a published library or a mirrored file with tests.
4. Commands: which ones, and whether the watch confirms them on its screen.

## First slice, once decided

OpenVitals serves `/meta` and `/profile`; OpenWatch reads the profile when the grant is in,
falls back to its own, and shows the grant on a "Connected apps" screen. Tests on both sides:
the caller check (wrong package, wrong certificate, revoked scope), the columns, and the
change notification.
