# AGENTS.md

The implementation guide for anyone, agent or person, working in this repository.

## What this is

OpenWatch is the devices half of OpenVitals: the Wear OS watch app (`:wear`), the
phone-to-watch link (`:wearlink`), and a phone app (`:app`) that pairs Wear OS watches,
writes what they record to Health Connect, and manages them. OpenVitals is the dashboard.
The two share no code at run time; this repository's device code was copied from OpenVitals
2.16.0 and adapted at a few seams (`docs/engineering/architecture.md` lists them). Wear OS is
the only integration: the Garmin and Xiaomi scale code that came with the copy was removed.

Health Connect's data origin is the writing app's package, so a record OpenVitals wrote
cannot be edited here, nor the reverse. Both apps may be installed side by side.

## Map

- `app/src/main/kotlin/tech/mmarca/openwatch/`
  - `devices/` — the device layer: `core` (ports, companion pairing, the sync port) and
    `wearos` (the phone side of the watch app). No Compose, no navigation, no Hilt module.
  - `features/` — the screens: `home`, `watches`, `healthconnect`, `settings`.
  - `healthconnect/` — `HealthConnectGateway`, the one class that touches the client.
  - `data/` — the device registry, the repositories and the preference stores. No database.
  - `sensors/ble/` — the BLE discovery scan the add-watch flow uses.
  - `di/` — the Hilt modules. `DevicesModule` is the only place a `devices/` class is bound.
- `domain/` — pure code with the same package names: `domain/model`, `domain/insights` (the
  sleep and walking-energy estimators), `domain/preferences`.
- `wear/` — the watch app, no DI. `wearlink/` — the protocol, pure JVM.

## Invariants

Do not break these without an explicit decision.

- **No `INTERNET` permission.** The manifest removes `INTERNET`, `ACCESS_NETWORK_STATE` and
  `ACCESS_WIFI_STATE`. Never add a dependency that needs a socket.
- **The watch app is offline too.** Its manifest removes the same three network permissions.
  It reads the raw Android sensors; Health Services and the Samsung Health Sensor SDK are not
  dependencies, and the SDK's `.aar` never enters the repository or a build. Why, and what
  a Samsung source would have to respect: `docs/engineering/samsung-health-and-privacy.md`.
- **No foreground service on the phone.** A watch sync runs with none; its process-priority
  story is the companion association.
- **One companion device service.** `OpenWatchCompanionDeviceService` exists for its binding:
  the OS raises the process priority while an associated watch is in range.
- **Health Connect is reached through `HealthConnectGateway` only.** Every device write goes
  through `ImportedRecordRepository.insertImportedRecords` with a deterministic
  `clientRecordId`, so a re-sync upserts; the gateway's reads throw on failure, they never
  return an empty answer that would pass for "no data". There is no rate-limit backoff: the
  devices issue a handful of reads per sync. Add one only once a refusal is seen.
- **A missing permission is `ScreenError.PermissionDenied`.** Use `isPermissionFailure()` /
  `toScreenError()`; never pattern-match exception messages.
- **Nothing waits on the main thread.** No `runBlocking` in `src/main`
  (`NoRunBlockingRatchetTest`); Health Connect calls are `suspend` on
  `dispatchers.io`; a receiver never holds a broadcast for a Health Connect read.
- **Text that leaves the app is structure, not data.** A log line logs a count or passes the
  throwable, never a value (`LogMessageInterpolationRatchetTest` holds the count).
- **Every locale ships complete for the copied strings.** A key that comes from OpenVitals is
  re-extracted with `scripts/extract-strings.py`; a key OpenWatch adds lives in `values/`
  until it is translated. `python3 scripts/verify-translations.py` checks the files.
- **The ratchets hold.** `DevicesLayeringTest` (no `@Module` under `devices/`),
  `CompanionPairingWiringTest` (an Activity calls `attachToActivity`),
  `FileSizeRatchetTest` (800 lines, with a ceiling list) and
  `NoRunBlockingRatchetTest`. When one fails, fix the code, not the test.

## Rules

- Protocol changes for the Wear OS link go in `:wearlink`, never into a copy.
- A new device integration implements the `devices/core` ports, keeps its protocol code
  transport-free, and is bound in `di/DevicesModule`. `DeviceSyncPort` is bound to the Wear OS
  service directly; a second integration brings back a dispatcher over several ports.
- Keep the copied code close to OpenVitals where it can be: a fix that applies to both
  repositories is easier to carry across when the files still read the same.
- Prefer one ViewModel per screen; ViewModels own loading state and call repositories.

## Verification

`./gradlew verifyCi` (phone app and domain) and `./gradlew verifyWearCi` (watch app), or
`scripts/ci-android-gradle.sh <task>` on a machine without the SDK pieces set up. See
`docs/engineering/development.md`.

## Releases

One release ships both form factors: the version lives in `gradle.properties`
(`openwatch.versionCode`, `openwatch.versionName`), the watch adds 2000000000 to the code,
and `.github/workflows/release.yml` signs both with one key and uploads the phone bundle to
the Play `beta`/`production` tracks and the watch bundle to `wear:beta`/`wear:production`.
`scripts/release.sh X.Y.Z` cuts a release; the procedure is `.claude/skills/release/SKILL.md`.
