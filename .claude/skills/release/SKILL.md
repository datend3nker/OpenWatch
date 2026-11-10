---
name: release
description: Release a new OpenWatch version - changelogs, release notes, fastlane, version code, tag, push, and the companion docs/landing-page repos. Use for "release X.Y.Z" requests.
---

# OpenWatch release process

How a version ships. It is OpenVitals' process, step for step
(`../mobile-app/.claude/skills/release/SKILL.md`); keep the two skills alike, and
when one learns something the other needs, write it in both. What differs is
named where it differs: one release carries the phone app and the Wear OS app,
which share the package name and the version, and the version lives in
`gradle.properties`. The order matters: every document is written FIRST,
because `scripts/release.sh` commits them, tags the commit with the release
notes, and pushes in one motion.

## 0. Pre-flight

- Everything for the release is committed and pushed on `main`; working tree clean.
  Work merged on GitHub by rebase or squash gets new hashes, so a local feature
  branch shows "ahead" of `origin/main` while the trees are identical: check
  `git diff --stat HEAD origin/main` prints nothing, then
  `git checkout main && git merge --ff-only origin/main`. The script pushes
  `main`, so release from `main`.
- Gates are green: `./gradlew verifyCi verifyWearCi`, the phone gate and the
  watch gate. The phone app builds the OpenLink library from the `openlink/`
  submodule: `git submodule update --init` first if `git submodule status`
  starts with `-`. On this laptop the run needs `JAVA_HOME=~/.jdks/jbr-21.0.11`,
  `ANDROID_HOME=~/Android/Sdk`, `LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8`
  (`./gradlew --stop` first if a daemon was started without it) and an init
  script that pins the test workers to English, since they default to Spanish:
  `allprojects { tasks.withType<Test>().configureEach { jvmArgs("-Duser.language=en", "-Duser.country=US") } }`
  passed with `-I`.
- Scope the release: `git log --oneline vLAST..HEAD` is the list of what the notes must cover.
  Read the commit bodies, not only the subjects; the user-facing behaviour is
  usually described there. Translation merges are in scope too (which locales
  moved, whether a new one started). A change in the `openlink/` submodule
  ships with the phone app: read `git diff vLAST..HEAD -- openlink` and that
  repository's log between the two commits.
- Translations: count the keys each `values-*/strings.xml` is missing against
  `values/strings.xml` (translatable ones only) before writing the notes;
  `verifyTranslations` only enforces 70%. The thirteen shipping locales are at
  100% since 2026-11-10 (0.3.0 shipped with the screens OpenWatch added after
  the split still in English). Translate the keys the release adds, or say in
  the notes which screen is English-only. A locale under 70% (Arabic
  `values-ar` and Hebrew `values-iw`) is not in the picker and must not be
  announced as a language.
- `gh` is installed and logged in on this laptop. `gh issue view` has printed
  nothing before; `curl` against
  `https://api.github.com/repos/Hale-MTU/OpenWatch/...` (anonymous, 60 calls
  an hour) reads the same state.
- The release workflow is enabled. A tag pushed while it is disabled starts
  nothing, and enabling it afterwards does not replay the push (OpenVitals
  2.14.0). Check `state` is `active`:
  `gh api repos/Hale-MTU/OpenWatch/actions/workflows/release.yml --jq .state`.
- The Play variables say where the uploads go, and the footer of the notes
  depends on them: `gh variable list` shows `OPENWATCH_PLAY_PAUSED`,
  `OPENWATCH_PLAY_INTERNAL_ONLY` and `OPENWATCH_PLAY_TESTING_TRACK` when they
  are set. None set is the normal state: open testing, then production. They
  are variables; one created as a secret by mistake is not read.

## 1. Version code (read this before touching anything)

`versionCode` is a monotonic install counter, independent of `versionName`.
Nightlies and releases share one counter line. It is computed, never chosen:

```bash
sh scripts/version-code.sh next --floor <current openwatch.versionCode from gradle.properties>
```

That consults GitHub release markers (`<!-- OpenWatch-Version-Code: N -->`)
plus the append-only `refs/version-code/*` refs (release bodies alone are not
a safe database - a deleted release takes its marker with it). The result MUST
exceed whatever nightly users have installed, or Play rejects the rollout with
"does not allow any existing users to upgrade".

The phone ships N; the watch ships 2000000000 + N, derived in
`wear/build.gradle.kts` and `scripts/ci-release-context.sh`, so there is one
number to track. N must stay at or below 99999999. To see what the script sees:

```bash
git ls-remote --refs origin 'refs/version-code/*' | sed 's#.*/##' | sort -n | tail -3
```

**Race caveat:** a nightly can mint a code between your preview and the release
run. Only the release workflow's schedule (00:00 UTC, often started some hours late
by GitHub) and manual runs of `.github/workflows/release.yml` with target
`nightly` build nightlies; a push to main runs tests only. Near midnight UTC,
or while a manual run is going, check the runs first
(`gh run list --workflow release.yml --limit 5`). Either way,
compute the code and name the fastlane files immediately before running the
release script, and afterwards verify the script's printed `versionCode`
matches the filenames.

## 2. Documents to write, all before running the script

1. **`CHANGELOG.md`** - prepend `## X.Y.Z - YYYY-MM-DD` with six language
   sections: `### English`, `### Espanol`, `### Deutsch`, `### Italiano`,
   `### Eesti`, `### Portugues`. House style: ASCII only (no diacritics, no
   em dashes - use `-`), bold-led bullets, one `**Fixes:**` bullet gathering
   the small ones. (0.1.1 has the English section alone; from 0.3.0 the six.)
2. **`docs/releases/X.Y.Z.md`** - the release notes; this file becomes the
   TAG MESSAGE. Shape: `# OpenWatch X.Y.Z`, `Released YYYY-MM-DD.`, one
   narrative paragraph saying what the release is about, then
   `### Added` / `### Changed` / `### Fixed`, then the standard footer: the
   package name and signing certificate note, and the distribution flow.
   The footer reads exactly:

   ```
   The phone app and the Wear OS app share the package name `tech.mmarca.openwatch` and the signing certificate, so both install as a normal update; the watch's versionCode is 2000000000 plus the phone's.

   Distribution flow:

   - GitHub release with the signed phone APK, signed debug APK, signed Wear OS APK and both signed Android App Bundle assets.
   - Direct Google Play production upload, to the phone track and the Wear OS track, from the approved production run of the release workflow.
   ```

   Do not copy the footer from 0.1.1: it says the app awaits Google's approval.

   While the repository variable `OPENWATCH_PLAY_PAUSED=true` holds the Play
   uploads, or `OPENWATCH_PLAY_INTERNAL_ONLY=true` keeps them on internal
   testing, the second line is false. Check (pre-flight), and if one is set,
   say instead that the Google Play update follows later. The fastlane
   changelogs are still written, for when that upload happens.
3. **`README.md`** - add highlight bullets for headline features; update any
   claims the release changes (e.g. the device list, or the Releases section
   when the Play flow changes).
4. **`fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`** - one
   per Play listing locale, all 14: `en-US`, `cs-CZ`, `de-DE`, `es-ES`, `et`,
   `fi-FI`, `fr-FR`, `gl-ES`, `it-IT`, `ja-JP`, `pl-PL`, `pt-PT`, `ru-RU`,
   `zh-CN`. Named by the PHONE code; the watch has no changelog of its own
   (Play shows the listing's). Hard limit 500 characters, single paragraph.
   Write each in its own language and script (UTF-8; the ASCII rule is for
   `CHANGELOG.md` only), with the feature names from that locale's
   `strings.xml`. French says "Santé Connect", as the app does. A screen
   whose title is still English in that locale is named in English, as the
   user will see it.
   - Each note is what a user of that language sees change. A fix that only
     touched the Czech strings goes in the Czech note, not the German one;
     the `en-US` note may summarise all of them.
   - All 14 listings came with 0.1.1. Do not call a listing "new".
   - Japanese and Chinese have no plural forms: leave plural fixes out there.
   - Play counts the file as uploaded, trailing newline included: a note of
     exactly 500 characters plus its newline is rejected as 501 (OpenVitals
     2.13.0's German note, found only in the production run). Count the whole
     file and keep every note at 490 or under, so a late edit has room.
   - Check before running the script (all 14 present, none over 490):
     ```bash
     code=<versionCode>; for d in fastlane/metadata/android/*/; do f="${d}changelogs/$code.txt"; test -f "$f" || echo "missing $f"; python3 -c "import sys; n=len(open(sys.argv[1],encoding='utf-8').read()); print(sys.argv[1], n, 'TOO LONG' if n > 490 else '')" "$f" 2>/dev/null; done
     ```

## 3. Run the release

```bash
bash scripts/release.sh X.Y.Z
```

The script computes the version code, patches `openwatch.versionCode` and
`openwatch.versionName` in `gradle.properties` (both build files read it, and
the `-SNAPSHOT` fallback in `build.gradle.kts` follows), commits
`chore: release X.Y.Z` (staging CHANGELOG, README, docs, fastlane and the
release machinery), tags `vX.Y.Z` with `docs/releases/X.Y.Z.md` as the
annotation, and pushes `main` plus the tag. The annotation drops the
`# OpenWatch X.Y.Z` title line and keeps the rest as written, `###` headings
included; it becomes the GitHub release notes and the Zulip post. Anything
outside what the script stages (this skill, code) is committed before it runs.

Verify the printed `Released vX.Y.Z (versionCode N)` matches the fastlane
changelog filenames; if a nightly stole the code, rename all 14 files and
amend before anyone pulls:

```bash
for f in fastlane/metadata/android/*/changelogs/OLD.txt; do git mv "$f" "${f%OLD.txt}NEW.txt"; done
```

CI (GitHub Actions) takes it from the tag: the tag's release run waits for the
verify run of the pushed commit, builds the five signed artifacts once (phone
APK, debug APK and AAB, watch APK and AAB), publishes them to the GitHub
prerelease, uploads the phone AAB to Play open testing and the watch AAB to
the Wear OS open testing track, and waits for the `production` environment's
approval. Approving that run (Actions > the run > Review deployments) promotes
both form factors from open testing to production (an upload instead if no
testing track had the version, as while Play was paused), marks the release
stable and posts the full notes to the Zulip `releases` channel
(`scripts/announce-zulip.sh`). A tag whose run is gone is shipped by a manual
run of the release workflow with target `production`, started from the tag: it
takes both AABs from the prerelease, no build. Started from `main` with the
`tag` input instead, the same run uses `main`'s pipeline for a tag already
cut, for when the fix is in the release machinery (OpenWatch only).

Two variables OpenVitals does not have: `OPENWATCH_PLAY_TESTING_TRACK=internal`
sends the testing upload to internal testing in place of open testing, and
production is promoted from there; `OPENWATCH_PLAY_INTERNAL_ONLY=true` (for
before Google had approved the app) also makes the production job upload
nothing. A version left waiting under the second is shipped, once the variable
is deleted, by that manual `production` run from its tag.

There is no Mastodon toot, as in OpenVitals since 2.9.1. The narrative
paragraph of `docs/releases/X.Y.Z.md` - the paragraph right after
`Released YYYY-MM-DD.` - is what Zulip readers see first: write it to read
well on its own and front-load it.

## 4. Companion repos (after the tag is pushed)

- **`../docs`** (Nextra site, OpenWatch under `docs/openwatch/`): `git fetch`
  first, since the daily sync may already have moved `main`. The release notes
  page is ported, not written: add
  `"docs/releases/X.Y.Z.md": "openwatch/releases/X.Y.Z.md"` to
  `scripts/app-docs.json` and `'X.Y.Z': 'OpenWatch X.Y.Z'` at the top of
  `docs/openwatch/releases/_meta.ts`, then run `python3 scripts/sync-app-docs.py`,
  which clones the repositories and writes the page (the other ported pages -
  watches, Wear OS install, permissions, privacy, developers - come along if
  the release changed them; never edit those by hand). The pages written on
  the site (`index.md`, `install.md`, `scale.md`, `connected-apps.md`) are
  edited there: add or update one for a new feature, register a new page in
  `docs/openwatch/_meta.ts`, fix any page the release made stale. A feature
  page may already exist when the app links to it: `ls docs/openwatch` before
  writing one. Commit `docs: OpenWatch X.Y.Z - <headline>` and push.
- **`../landing-page`**: update the copy (the `openwatch` block: `devices`,
  `privacy`) when a headline feature warrants it. Each of the 14 languages is
  one file, `lib/locales/<code>.ts`; `en.ts` is the source. Edit all 14, with
  the app's terms from that locale's `strings.xml` (French says "Santé
  Connect"). There is a system Node (v26) but no `node_modules`, so
  `npm run typecheck` picks up a global TypeScript that fails on the repo's
  `baseUrl` (TS5102), unrelated to the edit. Parse-check the edited files with
  `node -e` and `stripTypeScriptTypes` from `node:module` instead, or rely on
  CI. A push to `main` deploys to hale.ngo. Commit
  `content: <what changed>, for OpenWatch X.Y.Z` and push.

## 5. Gotchas that have actually happened

- 0.1.1's tag run failed at Play: the first bundle had been uploaded by hand
  and sat in Play's bundle library with no release, so the code was "already
  used". The lanes now release a held bundle after checking its sha256, but a
  hand upload of a different build under a coming code still stops the run.
- A Play variable created as a repository secret does nothing
  (`OPENWATCH_PLAY_INTERNAL_ONLY`, 2026-11): the workflow reads `vars.*`.
- In OpenVitals, 2.11.2 and 2.12.0 shipped with a stale distribution footer;
  the footer is part of the tag message, so it cannot be fixed afterwards.
  Read the footer, do not paste it.
- OpenVitals 2.14.0 was tagged while the release workflow was disabled, so no
  build, GitHub release or APK followed the tag. Hold Play with the
  `OPENWATCH_PLAY_PAUSED` variable instead, and check the workflow state in
  pre-flight.
- Read facts in the notes against the code, not only the commit message:
  OpenVitals 2.14.0's notes said the Garmin link redials three times, from a
  doc, while `CONNECT_ATTEMPTS = 3` means two redials. The tag kept the wrong
  count.
- A fix that was hidden rather than announced can need announcing later.

- Unescaped `&` in a translated string breaks `verifyTranslations` (XML parse).
- A locale that slips under 70% drops out of the picker silently.
- The fastlane changelog is per-versionCode, not per-versionName: a release
  whose code raced a nightly ships the wrong changelog silently if step 3's
  verification is skipped.
- Release notes and changelog entries are user-facing: name what the user
  sees, not the implementation ("OpenVitals shows your watch's battery", not
  "the OpenLink devices capability").
- New strings land English-only and `verifyTranslations` still passes, since
  its floor is 70%. Before writing the notes, count the keys each locale above
  70% is missing, and translate them or say so in the notes.
