# Development

## Build

The phone app, the watch app, the pure-Kotlin link and the domain module build with the
Gradle wrapper. JDK 17 or 21, the Android platform 37 and build-tools 37.0.0.

```bash
./gradlew :app:assembleDebug
./gradlew :wear:assembleDebug
```

`scripts/ci-android-gradle.sh` wraps `./gradlew` with the JDK and SDK checks the CI image
needs; it works locally too.

## The Gates

Two gates, as in OpenVitals: the phone gate and the watch gate. Both run the link
module's tests, since both apps compile it.

```bash
./gradlew verifyCi        # the phone app and the domain module: unit tests, translations, duplicate classes, android-test compile, lint
./gradlew verifyWearCi    # the watch app: unit tests, lint, build, android-test compile
```

`verifyCi` is three tasks that CI runs as separate jobs: `verifyCiUnitTest`,
`verifyCiPreflight` and `verifyCiLint`. The phone app's tests run on the `ci` build type.

The verify workflow (`.github/workflows/verify.yml`) diffs a pull request or push against
its base and runs the phone jobs for anything outside `wear/` and the watch job for `wear/`,
`wearlink/` and the shared build files. A change to the root Gradle files runs both.

## Dependency Locks

Every module locks its dependencies (`dependencyLocking` in the root build). After a
dependency change, regenerate the module's lockfile and commit it:

```bash
./gradlew :app:dependencies :domain:dependencies :wear:dependencies :wearlink:dependencies --write-locks
```

## Strings

`app/src/main/res/values*/strings.xml` came from OpenVitals, trimmed to the keys this code
references. `scripts/extract-strings.py --source <openvitals checkout>` redoes the trim after
more code is copied over; `scripts/verify-translations.py` checks every locale for extra keys,
placeholder and plural mismatches. The strings OpenWatch adds of its own live in `values/`
only, until a translation arrives.

## The Watch App

The watch app in `wear/` shares the phone app's `applicationId`, `tech.mmarca.openwatch`,
so a store lists it as a form factor of OpenWatch rather than a second app. The Kotlin
namespace is `tech.mmarca.openwatch.wear`. The debug build type adds the same `.debug` suffix
as the phone app, so a debug watch pairs with a debug phone.

One listing means one versionCode space. The watch owns the range `2000000001` to
`2099999999`; phone codes stay below it. `wear/build.gradle.kts` reads
`OPENWATCH_WEAR_VERSION_CODE` and `OPENWATCH_WEAR_VERSION_NAME` the way the phone build reads
`OPENWATCH_VERSION_CODE` and `OPENWATCH_VERSION_NAME`, and fails the build when the code is
outside the watch range.

The watch's instrumented tests (`wear/src/androidTest`, the metric store against real SQLite)
are compiled by the gate but run only on a device. Do not use `connectedDebugAndroidTest` on a
watch you wear: it uninstalls the app afterwards, which deletes the recorded data and the
trusted phones. Install the two APKs over the app instead and run the runner detached:

```sh
./gradlew :wear:assembleDebug :wear:assembleDebugAndroidTest
adb -s <watch> install -r wear/build/outputs/apk/debug/wear-debug.apk
adb -s <watch> install -r -t wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk
adb -s <watch> shell 'nohup am instrument -w tech.mmarca.openwatch.debug.test/androidx.test.runner.AndroidJUnitRunner > /data/local/tmp/ow-inst.txt 2>&1 &'
adb -s <watch> shell cat /data/local/tmp/ow-inst.txt   # until it ends in OK or FAILURES
```

Between releases, or on a watch without the Play Store, the watch app is sideloaded over Wi-Fi adb.
`scripts/wear-pair.sh` pairs the computer, `scripts/wear-install.sh` builds, installs and
grants the permissions, and `scripts/wear-skip-onboarding.sh` marks a never-onboarded watch
as set up. The walkthrough is [docs/how-to/wear-os-sideload.md](../how-to/wear-os-sideload.md).

## Docs and the Wiki

The [wiki](https://github.com/Hale-MTU/OpenWatch/wiki) is a mirror of the repository's Markdown, rebuilt by `.github/workflows/wiki.yml` on every push to `main` that touches a `.md` file: `README.md` is Home, every file under `docs/` and the other root Markdown files (changelog, privacy policy, agent notes) are one page each, named after their first heading, with a sidebar by folder. Edit the files here, never the wiki: the next run overwrites it.

`scripts/sync-wiki.py` rewrites each relative link to the page it points at, or to the file on GitHub for anything that is not a page. Its `--check` mode runs in the verify gate and fails on a relative link to a file that does not exist. To see the pages locally:

```bash
python3 scripts/sync-wiki.py --repo Hale-MTU/OpenWatch --out /tmp/openwatch-wiki
```

Two Markdown files with the same first heading would become one page; the script stops and names both. The same script runs in OpenVitals, OpenLink and OpenBridge; keep the copies alike.

## Releases

`.github/workflows/release.yml` is the release, on the schedule, a version tag and the manual
run; it is OpenVitals' pipeline with the watch app added. A tag or the nightly is cut from a
commit on `main`, whose push ran the verify workflow; the release's `context` job looks that
run up, waits for it if it is still going, and stops the release if there is none or it
failed. The verify run's `lint` and `wear-test` jobs skip when their paths did not change
and still leave the run successful, which is what the gate reads.

Its `build` job signs everything once, with one key, in one Gradle invocation: the phone
APK, the phone debug APK, the phone AAB, the watch APK and the watch AAB. They are the run's
`release-build` artifact; every job after it, production included, ships those files as
they are. Two GitHub release outputs:

- The `00:00 UTC` schedule, or a manual run of the release workflow with target `nightly`,
  builds `:app:assembleNightly`, `:app:bundleNightly`, `:app:assembleDebug`,
  `:wear:assembleNightly` and `:wear:bundleNightly`, then publishes `OpenWatch-nightly.apk`,
  `OpenWatch-nightly-debug.apk`, `OpenWatch-nightly.aab`, `OpenWatch-wear-nightly.apk` and
  `OpenWatch-wear-nightly.aab` to the fixed GitHub `nightly` prerelease. The same run uploads
  the phone AAB to the Google Play open testing track (`beta` in the Play Developer API) and
  the watch AAB to the Wear OS form-factor track (`wear:beta`), or to internal testing
  (`internal`, `wear:internal`) under `OPENWATCH_PLAY_TESTING_TRACK=internal` or while
  `OPENWATCH_PLAY_INTERNAL_ONLY=true` holds.
- A pushed `vX.Y.Z` or `VX.Y.Z` tag builds the `release` variants and publishes the same
  five files under `OpenWatch-vX.Y.Z` and `OpenWatch-wear-vX.Y.Z` names to its own versioned
  GitHub prerelease, uploads both AABs to open testing (or internal testing, under the same
  variables), then waits for the `production` environment's approval. Approving the run
  promotes the phone from `beta` to `production` with the store listing, the watch from
  `wear:beta` to `wear:production`, marks the GitHub release stable, and posts the notes to
  the Zulip `releases` channel (`scripts/announce-zulip.sh`). The promotion reads the
  version from whichever testing track has it, open or internal.

Each AAB has a `.sha256` beside it. A manual run with target `production`, started from the
tag, downloads both AABs from the prerelease, checks them, and runs the production jobs on
them: the way to ship a tag whose own run is gone. Started from `main` with the `tag` input
naming the version instead, it does the same with `main`'s workflow, lanes and scripts, and
the tag's own `gradle.properties` for the version: the way to apply a fix to the release
machinery to a tag already cut.

### One listing, two form factors, one version

The phone app and the watch app share the `applicationId` `tech.mmarca.openwatch`, so Play
lists the watch as a form factor of the one app. The release version lives once, in
`gradle.properties`:

```
openwatch.versionCode=N
openwatch.versionName=X.Y.Z
```

The phone ships `versionCode` N and the watch `2000000000 + N` (`wear/build.gradle.kts`
accepts `2000000001..2099999999`, so N stays at or below `99999999`;
`scripts/ci-release-context.sh` checks). `versionName` and `versionCode` are detached:
`versionName` is the human name (`0.1.0`, `0.1.0-nightly.12`, the suffix being the run
number), `versionCode` a monotonic update counter shared by nightlies and releases. CI
reads `OpenWatch-Version-Code` markers from the GitHub release notes plus the append-only
`refs/version-code/*` refs, uses `max(markers, refs, openwatch.versionCode) + 1` for a new
build, writes the chosen code into the release notes, and records it as a ref before the
nightly tag moves (`scripts/move-nightly-tag.sh`). The marker holds the phone code; the
watch code is derived. The fastlane lanes compare the incoming code with the live Play track
(`internal`, `beta`, `production` and their `wear:` twins) before uploading, so a code Play
already serves fails with a message instead of an opaque API error. The nightly run also
prunes old versioned GitHub releases, keeping the newest nine.

The scheduled run skips the build when there is nothing new: the `nightly` tag already
points at HEAD, or HEAD has no commits after the latest `vX.Y.Z`. Manual runs always build.

### Secrets and variables

The workflow's `GITHUB_TOKEN` creates and edits releases and pushes the `nightly` tag and
the `refs/version-code/*` refs; a tag ruleset that blocks either breaks the nightly.
Repository Actions secrets:

- `OPENWATCH_RELEASE_KEYSTORE_BASE64`, `OPENWATCH_RELEASE_STORE_PASSWORD`,
  `OPENWATCH_RELEASE_KEY_ALIAS` and `OPENWATCH_RELEASE_KEY_PASSWORD`: a PKCS12 upload key,
  base64-encoded (`base64 -w0 openwatch-release.p12`). One key signs both form factors and
  the published debug APK, so every build updates the one before.
- `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_BASE64`: the JSON key of a service account that can
  release `tech.mmarca.openwatch` to testing and production. If it may stage edits but not
  send them for review, set the variable `OPENWATCH_PLAY_CHANGES_NOT_SENT_FOR_REVIEW=true`.
- `ZULIP_BOT_EMAIL` and `ZULIP_BOT_API_KEY` for a bot on `openvitals.zulipchat.com`
  subscribed to the `releases` channel; only the `announce-zulip` job sees them.

The `production` environment needs required reviewers, or a production run never waits.

The variable `OPENWATCH_PLAY_TESTING_TRACK` names the testing track the nightly and a tag
upload to: `beta`, open testing, when unset, or `internal`, internal testing. Production is
promoted from whichever track has the version, so `internal` gives an internal testing,
then production, flow with no open testing in between. A version code Play already holds
is never uploaded twice: on a track, it is skipped by the upload and promoted from where it
sits; in Play's bundle library only (a hand upload left as a draft), the release is made from
that bundle, after its sha256 is checked against the run's.

Two more variables hold Play back, for two situations:

- Before Google has approved the app, only internal testing may take a build: Play reviews
  a closed or open testing release and a production release, and a new app's first review
  is also the gate on every later track. The variable `OPENWATCH_PLAY_INTERNAL_ONLY=true`
  sends the nightly's and a tag's uploads to internal testing (`internal`, `wear:internal`)
  instead of open testing, and makes the production job upload nothing; the run still waits
  for approval, marks the GitHub release stable and posts to Zulip. Open testing and
  production are not touched while it is set. Once Google has approved the app, delete the
  variable; the next tag goes to the testing track and then production as usual, and a
  version that is waiting on internal testing is promoted from there by a manual run of
  the workflow from its tag with target `production`.
- While a release is in Play review, a new upload to any track restarts the review. The
  variable `OPENWATCH_PLAY_PAUSED=true` holds every Play upload, phone and watch, internal
  testing included: the nightly skips its Play job, and a production run still waits for
  approval, marks the GitHub release stable and posts to Zulip, but uploads nothing.
  Delete the variable to resume.

### Before the first run

By hand, once, outside the repository:

1. The keystore: `keytool -genkeypair -storetype PKCS12 -keystore openwatch-release.p12
   -alias openwatch -keyalg RSA -keysize 4096 -validity 10000`; the four secrets above (the
   key password equals the store password for PKCS12). Keep the file offline.
2. Play Console: create the app `tech.mmarca.openwatch`, enrol it in Play App Signing with
   this key as the upload key, add the Wear OS form factor (Advanced settings, Form factors)
   so the `wear:*` tracks exist, create internal testing and open testing for both form
   factors, fill the store listing (`fastlane/metadata/android/` holds the text and the
   512 px icon; the feature graphic and screenshots are added in the Console), the content
   rating, the Data safety form and the Health Connect declaration, and set the privacy
   policy URL to
   `https://github.com/Hale-MTU/OpenWatch/blob/main/PRIVACY.md`. Play refuses an API
   upload to an app with no release, so the first bundle on each track goes up by hand, from
   the first run's GitHub prerelease.
3. The service account, invited in Users and permissions with release rights on this app.
4. The Zulip bot, the `production` environment, and the variables: `OPENWATCH_PLAY_PAUSED`
   for the first run, `OPENWATCH_PLAY_INTERNAL_ONLY=true` for every run until Google has
   approved the app.
5. A manual `nightly` run with `OPENWATCH_PLAY_PAUSED=true` proves the GitHub side; a second
   without it proves the Play side, the `wear:internal` upload included.

## Release Checklist

The procedure, written out step by step, is `.claude/skills/release/SKILL.md`. It is
OpenVitals' procedure, and the two skills are kept alike. In short:

1. Write the documents first: `CHANGELOG.md` (six language sections), `docs/releases/X.Y.Z.md` (it becomes the tag
   message and the release notes), the README, and one Play changelog per locale under
   `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`, named by the phone's
   code (the watch has none), 490 characters or fewer.
2. `./gradlew verifyCi verifyWearCi` and `git diff --check`.
3. `bash scripts/release.sh X.Y.Z`: it computes the code with `scripts/version-code.sh
   next`, patches `gradle.properties`, commits `chore: release X.Y.Z`, tags `vX.Y.Z` with the
   release notes and pushes `main` and the tag together. Check the printed code against the
   changelog file names.
4. The tag's run builds, publishes the prerelease and waits. Approve it (Actions, the run,
   Review deployments). If the run is gone, run the workflow by hand from the tag with target
   `production`.
5. The companion repositories: the docs site ports the release notes page
   (`scripts/app-docs.json` there) and the landing page's OpenWatch copy follows a headline
   feature.

CI has no device, so it never runs the instrumented tests. To run them, connect a phone or
emulator: `ANDROID_SERIAL=<serial> ./gradlew verifyAndroidTest`.
