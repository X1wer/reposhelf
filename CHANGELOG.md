<!-- SPDX-FileCopyrightText: 2026 RepoShelf contributors -->
<!-- SPDX-License-Identifier: CC-BY-4.0 -->

# Changelog

All notable changes to RepoShelf are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

The release workflow (`.github/workflows/release.yml`) publishes the section whose heading matches the tag
(`## [X.Y.Z]` for tag `vX.Y.Z`) as the release notes, and refuses to release a version without such a section.

## [Unreleased]

### Changed

- The built-in catalog lists the maintainer's own Android apps (FülesÉbresztő, YTDLP Studio) instead of the ten sample
  entries (catalog serial 4).
- Release notes: hand-wrapped CHANGELOG lines are joined, links to repository files point to the tagged version, and a
  "Verification" section lists the signing certificate fingerprint and the SHA-256 sums of the APKs.

## [0.1.0] - 2026-11-14

First public release.

### Added

- Signed app catalog with offline search by name, keyword and category (partial words, several words, accent- and
  case-insensitive); built-in signed starter catalog; category filter; catalog refresh with rollback and expiry
  protection.
- App pages with repository data, license, latest release, release notes, download size and a natively rendered README
  (no web view; external images only on request; confirmation for links outside GitHub); clear notes for missing data.
- Installation from GitHub Releases: APK selection by processor architecture (or user choice), resumable HTTPS
  download, preliminary estimate and actual check shown separately, signature in three separate levels, installation with
  Android's `PackageInstaller`, read-back of the installed version, home-screen shortcut offer.
- Split APK sets: the base APK is fully checked, split files are verified by Android during installation, after an
  explicit confirmation; never installed without confirmation.
- Own sources: add any public GitHub repository by URL or `owner/name`, with a step-by-step check, then install or only
  follow its releases.
- My apps: followed, favorite, own-source and installed apps with filters, status, last check and actions (check, open
  repository, app info, uninstall request, monitoring on/off, favorite, remove from list).
- Update detection from GitHub Releases (version code first, then release identity, version name, release date); manual
  checks for one or all apps that respect the GitHub API limit; pre-releases and release delay as options.
- Background checks with WorkManager (6 h / 12 h / daily / weekly / manual only), automatic download (mobile data only
  if allowed) and installation without confirmation where Android allows it; global settings with safe defaults and
  per-app overrides.
- Seven notification channels with de-duplication; notifications open the affected app page; notification permission
  requested in context.
- Security warnings (critical / warning / info) for repository hijacking, owner change, missing repository, signer
  change, package name change, downgrade, corrupted download and more; critical warnings block automatic installation.
- Update history with filters and search.
- Self-update from the RepoShelf GitHub repository (GitHub build, opt-in) with the same checks, including recovery from
  interrupted updates.
- Export and import of the app list (versioned format, no secrets or trust data; importing never installs anything).
- Offline mode with saved app pages and an offline banner.
- Light, dark and system theme, wallpaper colors (Android 12+), English and Hungarian interface with in-app language
  choice, side navigation rail on wide screens.
- `github` and `fdroid` build variants; the `fdroid` variant has no self-update.
- About section in Settings with the version, the license text and the third-party license notices (packaged into the
  APK; the release workflow also attaches `THIRD_PARTY_NOTICES.md` to every release).
- Documentation: README, architecture, security policy, contributing guide, user guide (English and Hungarian), privacy
  policy and disclaimer (English and Hungarian), third-party notices; license files.

### Security

- Repository identity pinned by permanent GitHub ID; a fresh identity check before every manual download.
- Signer recorded per package; updates signed with a different key are never installed; key rotation accepted only
  through a verified signing lineage; catalog-recorded signers enforced independently.
- APK parsing in a separate process with a timeout; bytes written to the install session re-hashed before commit.
- Catalog signature (ECDSA P-256) verified before parsing; serial, expiry and size limits.
- HTTPS only with host allow-lists and size limits; no backup or device transfer of app data; overlays hidden on
  Android 12+ and obscured touches dropped on Android 9–11.
- Build hardening: Gradle dependency verification, release builds refused with the catalog test key, release signing
  only from a protected CI environment, SHA-pinned GitHub Actions, OSV vulnerability check in CI, third-party notices
  checked in CI.
- A package older than the highest version previously installed on the device gets a warning before installation, even
  after the app was uninstalled; partial downloads are limited to 512 MiB in total.

### Known limitations

- Not yet tested on physical devices (all device tests ran on the Android emulator, Android 9 and 17).
- No GitHub sign-in (anonymous API limit of 60 requests per hour per IP address); GitHub only (no GitLab or Codeberg).
- No "gentle update" for automatic installations; no warning for new sensitive permissions or for changed catalog
  entries. See [SECURITY.md](SECURITY.md#known-limitations).
