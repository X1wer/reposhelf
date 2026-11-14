<!-- SPDX-FileCopyrightText: 2026 RepoShelf contributors -->
<!-- SPDX-License-Identifier: CC-BY-4.0 -->

# RepoShelf

RepoShelf is a free and open-source Android app for finding, installing and updating Android apps that are published
as **GitHub Releases**. It shows a small, signed catalog of open-source apps, lets you add any public GitHub repository
yourself, checks every downloaded APK before installation, and keeps your apps up to date – manually or in the
background, only where Android officially allows it.

*Magyarul:* a felhasználói kézikönyv magyarul is elérhető: [USER_GUIDE.hu.md](USER_GUIDE.hu.md). Adatkezelés:
[PRIVACY_POLICY.hu.md](PRIVACY_POLICY.hu.md), felelősségi tájékoztató: [DISCLAIMER.hu.md](DISCLAIMER.hu.md).

> **Project status: in development, not yet released.** Version 0.1.0 is being prepared. There is no public release
> and no published APK yet. The catalog is signed with its real key (`catalog-2026a`); the release signing key has not
> been created yet, so signed release builds cannot be produced from this repository until the maintainer completes the steps in
> [SECURITY.md](SECURITY.md#before-the-first-public-release). Everything described as working below has been tested on
> the Android emulator (Android 9 and Android 17) and with automated tests; nothing has been tested on a physical device
> yet.

## What it does

- **Discover:** search a local, signed catalog by name, keyword or category – partial words, multiple words, accents and
  letter case do not matter. Searching works offline.
- **App pages:** repository, developer, license, description, latest release, release notes, download size, the README
  (rendered natively, without a web view or JavaScript), and a clear note when information or an installable APK is
  missing.
- **Install from GitHub Releases:** RepoShelf picks the APK that fits your device's processor (or lets you choose),
  downloads it directly from GitHub over HTTPS, checks it, and asks Android to install it with the official
  `PackageInstaller` API. Android always shows its own confirmation for a first installation.
- **Checks before installing:** file size and SHA-256 (against the checksum published by GitHub, when available), APK
  structure, package name, version code (no downgrades), minimum Android version, processor architecture, and the
  signature in three separate levels: technical validity, the signer compared to what is already known, and publisher
  trust (which cannot be proven cryptographically and is never claimed).
- **Your own repositories:** add any public GitHub repository by URL or `owner/name`. RepoShelf identifies it, checks
  its releases and whether it contains an installable APK, then lets you install it or just follow its releases.
- **Updates:** manual checks for one app or all apps, and optional background checks (every 6 or 12 hours, daily,
  weekly, or manual only). Optional automatic download and automatic installation – the latter only where Android allows
  installing without asking (see [the user guide](USER_GUIDE.md#8-automatic-updates)). Per-app rules override the global
  settings.
- **Update safety:** the signing key of every installed app is recorded, and an update signed with a different key is
  never installed. Repository renames are followed; a change of owner or a different repository under the same name
  raises a warning or blocks updates.
- **Security warnings, notifications, history:** warnings with three severity levels, seven notification channels, and
  a searchable update history.
- **Self-update** (GitHub build only, opt-in): RepoShelf can update itself from its own repository with the same checks.
- **Other:** favorites, offline mode with saved app pages, export and import of your app list (never installs
  anything), light/dark/system theme, English and Hungarian interface.

What RepoShelf does **not** do: it has no server, no account and no analytics; it does not build apps from source code;
it does not use root, Shizuku or any hidden API; it does not bypass Android's installation rules; and it does not
repackage or modify APKs.

## Requirements

- Android 9 (API 28) or newer. Some features need newer versions: installation without confirmation needs Android 12,
  per-app language selection in system settings and the notification permission prompt need Android 13, update
  ownership needs Android 14.
- An internet connection for checking, downloading and installing. Browsing the catalog and your saved data works
  offline.

## Installing RepoShelf

There is no published release yet. Once the first version is released:

- **GitHub Releases** will be the primary channel. Each release will contain the signed APKs and a `SHA256SUMS` file.
  The release is a draft until the maintainer reviews and publishes it.
- An **F-Droid** build variant exists (`io.github.x1wer.reposhelf.fdroid`, without self-update), but the app has not
  been submitted to F-Droid.
- RepoShelf is **not** planned for Google Play: Play's policies do not allow an app store to use the install permission.

You will need to allow RepoShelf to install apps ("Install unknown apps") the first time you install something with it.
From 2027, Android's developer verification may require extra steps on certified devices for apps from unregistered
developers; see the [user guide](USER_GUIDE.md#12-common-problems).

## Building from source

Requirements: JDK 17 or newer (CI uses JDK 21; the JetBrains Runtime bundled with Android Studio also works), the
Android SDK with the Android 17 platform (`platforms;android-37.0`), and Python 3.10+ for the helper tools. Create `local.properties` with `sdk.dir=<path to the Android SDK>` (use forward slashes on
Windows).

```bash
./gradlew test assembleGithubDebug assembleFdroidDebug
```

```bash
./gradlew :app:lintGithubDebug
```

The debug APKs are written to `app/build/outputs/apk/<flavor>/debug/`. Release builds are unsigned unless the release
signing environment variables are set (only in the protected release workflow); see [CONTRIBUTING.md](CONTRIBUTING.md) for the full list of checks, instrumented
tests, release signing and the dependency verification workflow.

## Documentation

| Document | Contents |
|---|---|
| [USER_GUIDE.md](USER_GUIDE.md) · [USER_GUIDE.hu.md](USER_GUIDE.hu.md) | User guide (English / Hungarian) |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Modules, data flows, the eight-step update process, technical decisions |
| [SECURITY.md](SECURITY.md) | Security model, checks, known limitations, reporting vulnerabilities |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Development setup, tests, coding rules, release process |
| [CHANGELOG.md](CHANGELOG.md) | Version history |
| [PRIVACY_POLICY.md](PRIVACY_POLICY.md) · [PRIVACY_POLICY.hu.md](PRIVACY_POLICY.hu.md) | What data RepoShelf handles and where it goes |
| [DISCLAIMER.md](DISCLAIMER.md) · [DISCLAIMER.hu.md](DISCLAIMER.hu.md) | Responsibility for third-party apps, limits of the checks |
| [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) | Licenses of the components packaged into the app |

The catalog lives in a separate repository (`reposhelf-catalog`), with its own schema, submission guide and license.

## License

RepoShelf is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
See [LICENSE](LICENSE).

The documentation in this repository is licensed under [CC BY 4.0](LICENSES/CC-BY-4.0.txt); the Gradle wrapper scripts
are Apache-2.0. Each source file states its license in an `SPDX-License-Identifier` header. The catalog data is CC0 1.0.

Being listed in the RepoShelf catalog is **not** a security certification. The apps you install are made by independent
developers – see [DISCLAIMER.md](DISCLAIMER.md).

"GitHub" is a trademark of GitHub, Inc. RepoShelf is an independent project and is not affiliated with or endorsed by
GitHub or Google.
