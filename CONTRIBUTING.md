<!-- SPDX-FileCopyrightText: 2026 RepoShelf contributors -->
<!-- SPDX-License-Identifier: CC-BY-4.0 -->

# Contributing to RepoShelf

Thank you for your interest! Bug reports, documentation fixes, translations and code are welcome. Security
vulnerabilities must be reported privately – see [SECURITY.md](SECURITY.md).

To propose an app for the catalog, use the catalog repository and its submission guide, not this repository.

## Ground rules

- **Security first.** RepoShelf installs software. The priority order is: security → reliability → core features →
  usability → maintainability → performance → appearance → convenience. A change that weakens a check (signer, package
  name, version code, repository identity, catalog signature, HTTPS, size limits) will not be accepted without a strong,
  documented reason.
- **Only official Android APIs.** No root, Shizuku, hidden APIs, or ways around Android's installation rules.
- **No tracking.** No analytics, advertising, crash-reporting services or Google Play Services / Firebase.
- **Few dependencies.** A new library needs a reason, a license compatible with GPL-3.0-or-later (Apache-2.0, MIT, BSD;
  test-only EPL is fine), and the dependency verification and notices updates described below.
- **Never commit secrets** – no tokens, passwords, keystores or private keys, not even test ones. Test keys are generated at
  test time or kept outside the repository.
- **Do not describe unimplemented features as working** in documentation or UI text.

## Developer Certificate of Origin

By contributing you certify the [Developer Certificate of Origin 1.1](https://developercertificate.org/). Sign off each
commit:

```bash
git commit -s -m "fix(update): describe the change"
```

This adds `Signed-off-by: Your Name <email>` to the commit message. Contributions are accepted under the license of the
file you change (GPL-3.0-or-later for code, CC-BY-4.0 for documentation).

## Development environment

- JDK 17 or newer (CI uses Temurin 21; the JetBrains Runtime bundled with Android Studio works too).
- Android SDK with `platforms;android-37.0`, build tools and platform tools; an emulator for instrumented tests (at least
  one Android 9 / API 28 and one recent image).
- Python 3.10+ (helper tools use only the standard library).
- `local.properties` in the repository root: `sdk.dir=<path to the SDK>` (forward slashes on Windows, e.g.
  `C\:/Users/me/AppData/Local/Android/Sdk`). The file is ignored by git.

Build variants: `github` (default, with opt-in self-update) and `fdroid` (`.fdroid` application ID suffix, no
self-update). Task names include the variant, e.g. `assembleGithubDebug`.

## Project layout

| Path | Contents |
|---|---|
| `app/` | Android application: Compose UI, view models, background work, notifications, self-update, manifest |
| `core/model`, `core/network`, `core/catalog`, `core/source`, `core/apk`, `core/update` | Pure JVM modules: domain types, HTTP rules, catalog parsing and signature, GitHub provider and README parser, APK decisions, the eight update steps |
| `core/data` | Room database, DataStore settings, repositories |
| `core/installer` | PackageInstaller, PackageManager, the isolated APK inspection service |
| `tools/` | Python helpers: JVM API-level check, OSV check, release tools, split-install check, third-party notices |
| `gradle/libs.versions.toml` | all dependency versions |
| `gradle/verification-metadata.xml` | checksums of every dependency |

Security-critical logic belongs in the JVM modules, where it can be tested quickly without Android. See
[ARCHITECTURE.md](ARCHITECTURE.md). Existing code comments are mostly in Hungarian; new comments may be in English.

## Checks to run before a pull request

These are the same steps the CI runs (`.github/workflows/ci.yml`):

```bash
./gradlew test assembleGithubDebug assembleFdroidDebug :app:lintGithubDebug :app:assembleGithubDebugAndroidTest :core:installer:assembleDebugAndroidTest
```

```bash
python3 -I -m unittest discover -s tools
```

```bash
./gradlew classes && python3 -I tools/check_jvm_api_level.py "$ANDROID_HOME/platforms/android-37.0/data/api-versions.xml" "$JAVA_HOME/bin/javap" 28 core/*/build/classes/kotlin/main
```

```bash
./gradlew :app:assembleGithubRelease :app:assembleFdroidRelease -Preposhelf.allowTestCatalogKey=true
```

- Lint treats `NewApi` as an error: the minimum SDK is 28. Lint does not check the pure JVM modules, which is why the
  bytecode API-level check exists.
- The release build without `-Preposhelf.allowTestCatalogKey=true` **fails on purpose** while the catalog test key is
  configured. Builds made with the switch get a `-testcatalog` version suffix and must never be distributed.
- On Windows, run Python with `python -X utf8 -I`.

### Instrumented tests

Platform-specific security code is tested on a running emulator, at least on API 28 and on a recent API level:

```bash
./gradlew :core:installer:connectedDebugAndroidTest
```

The app module's overlay test (`ObscuredTouchTest`, runs on API 28–30) must **not** be run with `connected…`, because
that task uninstalls the app and its data afterwards. Install the APKs and run it with `adb shell am instrument`:

```bash
adb install -r -d app/build/outputs/apk/github/debug/app-github-debug.apk
```

```bash
adb install -r -t app/build/outputs/apk/androidTest/github/debug/app-github-debug-androidTest.apk
```

```bash
adb shell am instrument -w -e class io.github.x1wer.reposhelf.ObscuredTouchTest io.github.x1wer.reposhelf.test/androidx.test.runner.AndroidJUnitRunner
```

The same way, `LargeCatalogPerformanceTest` loads and searches a generated, signed 5000-entry catalog in an in-memory
database on the device and logs the timings (`adb logcat -s RepoShelfPerf`):

```bash
adb shell am instrument -w -e class io.github.x1wer.reposhelf.LargeCatalogPerformanceTest io.github.x1wer.reposhelf.test/androidx.test.runner.AndroidJUnitRunner
```

`python3 -I tools/split_install_check.py` (with an emulator running) checks that Android rejects inconsistent split APK
sets; it uses temporary keys.

Debug builds accept two developer intents for emulator testing (ignored in release builds): run the background check
immediately (`--ez io.github.x1wer.reposhelf.debug.RUN_BACKGROUND_CHECK true`) and simulate a self-update from a local APK
(`--es io.github.x1wer.reposhelf.debug.SELF_UPDATE_FROM <file>`).

## Coding rules

- Every source file starts with an SPDX header: `SPDX-FileCopyrightText: 2026 RepoShelf contributors` and
  `SPDX-License-Identifier: GPL-3.0-or-later` (documentation: `CC-BY-4.0`).
- Kotlin official code style. Avoid `/*` inside KDoc text – Kotlin comments nest.
- Every user-visible text is a string resource, in **both** `values/` (English) and `values-hu/` (Hungarian), with
  matching placeholders and plural forms. `LocalizationTest` fails on missing translations and hard-coded texts.
- Treat every external input as untrusted: limit sizes, validate strictly, fail closed.
- A new security check gets a unit test; platform-dependent behavior gets an instrumented test on API 28 and a recent
  level.
- Database changes need a new Room schema version with an automatic (or written) migration and a migration test from the
  previous exported schema. No destructive migrations.
- Accessibility: content descriptions for icons, no information by color only, layouts that survive 200% font size.

## Changing dependencies

1. Edit `gradle/libs.versions.toml`.
2. Regenerate the dependency checksums and review the diff:

   ```bash
   ./gradlew --write-verification-metadata sha256 test assembleGithubDebug assembleFdroidDebug assembleGithubRelease :app:lintGithubDebug :app:assembleGithubDebugAndroidTest :core:installer:assembleDebugAndroidTest -Preposhelf.allowTestCatalogKey=true
   ```

   Platform-specific artifacts (for example `aapt2` for Linux and macOS) must be added by hand from the checksums
   published in Google's Maven repository.
3. Check for known vulnerabilities (sends only public package names and versions to OSV.dev):

   ```bash
   ./gradlew -q :app:dependencies --configuration githubReleaseRuntimeClasspath > build/runtime-deps.txt
   ```

   ```bash
   python3 -I tools/osv_check.py --runtime build/runtime-deps.txt --all gradle/verification-metadata.xml
   ```

4. Update the component list in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) (CI fails with `--check` if it is out
   of date; the file is also packaged into the APK and attached to releases); the tool refuses unknown licenses:

   ```bash
   python3 -I tools/third_party_notices.py --tree build/runtime-deps.txt --file THIRD_PARTY_NOTICES.md
   ```

   If the new component ships a `NOTICE` file or its license requires reproducing a copyright notice, add it to the
   hand-written part of that file.

## Catalog changes

The app contains a built-in copy of the signed catalog (`app/src/main/assets/catalog/catalog-v1.json` and `.sig`), which
must be byte-identical to `dist/v1/catalog.json` in the catalog repository. `BundledCatalogTest` checks the signature
with the compiled-in keys and, when the catalog repository is checked out next to this one (`../catalog`), that both
files are identical. After the catalog is rebuilt and signed, copy both files. Raise `MIN_CATALOG_SERIAL` with releases.

## Commit messages and pull requests

- Conventional style: `feat(scope): …`, `fix(scope): …`, `docs: …`, `build: …`, `test: …`.
- Keep pull requests focused; describe what you tested and how (unit test, emulator with API level, or only built).
- Update [CHANGELOG.md](CHANGELOG.md) under `## [Unreleased]` for user-visible changes, and the user guide (both
  languages) when behavior changes.

## Releases (maintainers)

1. Set `reposhelf.versionName` / `reposhelf.versionCode` in `gradle.properties`, move the changelog entries into a
   `## [X.Y.Z] - YYYY-MM-DD` section, and update the third-party notices.
2. Push a `vX.Y.Z` tag. The release workflow checks that the tag, the version and the changelog section match and that the
   expected signer fingerprint is configured, runs the tests, builds and signs both variants from the protected `release`
   environment, verifies the signer, writes `SHA256SUMS` and creates a **draft** release with the changelog section as
   notes.
3. Review the draft and publish it manually.

The real signing keys do not exist yet; see [SECURITY.md](SECURITY.md#before-the-first-public-release).
