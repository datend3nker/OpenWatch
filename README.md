# OpenWatch

The devices half of [OpenVitals](https://github.com/Hale-MTU/OpenVitals): a Wear OS
watch app, the phone-to-watch link, and a phone app that pairs a Garmin or Wear OS watch and
a Xiaomi S400 scale, copies what they record into Health Connect, and manages them. OpenVitals
stays the dashboard that shows the data; OpenWatch is where it comes from.

No account, no server: the phone app declares no `INTERNET` permission, and everything
travels over Bluetooth between the device and the phone. Open source under AGPL-3.0-or-later.

## What it does

- **Garmin watches**, over Garmin's own Bluetooth protocol: sync of activity, sleep and
  wellness files, written to Health Connect where it has a record type; notification
  forwarding, the watch's settings tree and alarms, find my watch, music controls, calendar
  and weather on the watch, CoMaps guidance on the wrist, a GPS ephemeris upload, saved
  locations. The watch-only measurements Health Connect cannot hold (stress, Body Battery,
  sleep score and the like) stay in OpenWatch's own database and its Watch Data screen.
- **Wear OS watches**, through the OpenWatch watch app in `wear/`: heart rate, steps and
  floors recorded on the watch, synced to Health Connect on a tap or a schedule, with an
  estimated sleep session per night and the night's resting heart rate. The watch app also
  offers three tiles.
- **Xiaomi Body Composition Scale S400 and S400 Pro**: every weigh-in saved to Health Connect
  as it happens, with the app open or closed, decoded from the scale's own broadcast with the
  key from your Xiaomi account. Weight and heart rate as measured; body fat, lean mass and
  body water estimated on the phone.
- **OpenVitals and other apps on the phone**, over
  [OpenLink](https://github.com/Hale-MTU/OpenLink): the body profile kept the same in both
  apps, the paired watches and the scale with battery and last sync, a sync on request, and a
  watch's live heart rate while the other app records an activity. OpenVitals connects
  without a prompt; any other app waits for your answer on the Connected apps screen, where
  each thing an app may do has its own switch.

The device code, its screens and its tests came from OpenVitals 2.16.0 unchanged where the
seams allowed; [docs/features/watches.md](docs/features/watches.md) describes the behaviour.

## Layout

| Module | What |
| --- | --- |
| `app/` | The phone app: `devices/` (the Garmin stack, the Wear OS phone side, the scale listener), `features/` (the screens), `healthconnect/` (the one gateway to Health Connect), `activityimport/` (FIT activity files), `data/` (Room and the preference stores) |
| `domain/` | The pure code the app depends on: FIT decoding, the sleep and walking-energy estimators, the device models, the body profile |
| `wear/` | The Wear OS watch app |
| `wearlink/` | The line protocol between the two, pure Kotlin, compiled into both apps |

[AGENTS.md](AGENTS.md) holds the rules the code follows and
[docs/engineering/architecture.md](docs/engineering/architecture.md) the map.

## Build

JDK 17 or 21 and the Android SDK (platform 37, build-tools 37.0.0).

```bash
./gradlew :app:assembleDebug
./gradlew :wear:assembleDebug
```

The gates: `./gradlew verifyCi` for the phone app and `./gradlew verifyWearCi` for the watch
app; see [docs/engineering/development.md](docs/engineering/development.md). A watch app you
built yourself is installed over Wi-Fi adb:
[docs/how-to/wear-os-sideload.md](docs/how-to/wear-os-sideload.md).

## Releases

Both apps share the `applicationId` `tech.mmarca.openwatch`, so one Google Play listing
carries both form factors, and one release ships both. A nightly build is published every
night to the `nightly` GitHub prerelease and to Play open testing; a tagged version gets its
own GitHub release and goes to open testing, then to production once approved. The GitHub
release holds the signed phone APK, a signed debug APK, the signed watch APK and both app
bundles, for a sideload or a Play upload by hand. The pipeline, its secrets and the release
procedure are in [docs/engineering/development.md](docs/engineering/development.md); the
Play listing text is under `fastlane/metadata/android/`. The privacy policy is
[PRIVACY.md](PRIVACY.md).

## Coming from OpenVitals

OpenVitals 2.16.0 and earlier synced watches and the scale themselves; from the next version
that is OpenWatch's job, and an updated OpenVitals says so once, with a link here. Three things
to know:

- **Health Connect records belong to the app that wrote them.** OpenWatch cannot update or
  delete a record OpenVitals wrote. The sync cursors and file watermarks start empty, so
  OpenWatch's first sync imports what the watch still holds, as new records under OpenWatch.
- **The Wear OS watch app is a different app.** It installs beside OpenVitals' old watch app
  under its own id and answers only OpenWatch's phone app (a different link UUID). Uninstall
  the OpenVitals watch app so the two do not both record the sensors.
- **The watch-only Garmin data is not copied over.** Stress, Body Battery and the other
  measurements OpenVitals kept on the phone are not in Health Connect; OpenWatch fills its own
  store from the next syncs.

OpenVitals and OpenWatch talk over [OpenLink](https://github.com/Hale-MTU/OpenLink) on the
phone: the body profile, the paired watches for OpenVitals' dashboard tile, and a watch's live
heart rate while OpenVitals records an activity. OpenWatch asks before it answers an app it
does not know, on its Connected apps screen.

## Privacy

Nothing leaves the phone. Notifications forwarded to a watch are held in memory and sent to
the watch only. The scale's key is stored in the app's private preferences. The app has no
`INTERNET` permission, which the manifest enforces by removing the permission from every
dependency too.
