<!-- SPDX-FileCopyrightText: 2026 RepoShelf contributors -->
<!-- SPDX-License-Identifier: CC-BY-4.0 -->

# Architecture

This document describes RepoShelf as implemented (version 0.1.0, in development). Items that are planned but not
implemented are marked **(not implemented)**. Security aspects are summarized in [SECURITY.md](SECURITY.md).

## 1. Principles

1. **No server of our own.** The catalog and the APK files live on GitHub; searching, version comparison, APK checks and
   scheduling all run on the device.
2. **All external data is untrusted:** GitHub API responses, README files, catalog entries, release metadata, download
   URLs, APKs, import files. Every input has a size limit, strict validation and fail-closed handling.
3. **Official Android APIs only.** No root, Shizuku, hidden APIs, or bypassing installation restrictions.
4. **Security checks are code, not documentation.** Each check is a separate, unit-tested component.
5. **Priorities:** security → reliability → core features → usability → maintainability → performance → appearance →
   convenience.
6. **Few dependencies, few abstractions.** Abstractions exist only where required (the source provider interface) or
   where the platform boundary has to be testable.

```
┌──────────────────────────────┐       ┌───────────────────────────────────────┐
│ catalog repository (GitHub)  │       │ app repositories (GitHub)             │
│  apps/*.json  (sources, PRs) │       │  REST API: repository, README,        │
│  dist/v1/catalog.json        │       │  releases; release assets (APKs)      │
│  dist/v1/catalog.json.sig    │       └───────────────────┬───────────────────┘
└──────────────┬───────────────┘                           │ HTTPS, ETag, rate limits
               │ HTTPS, ETag                               │
               ▼                                           ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ RepoShelf (Android)                                                          │
│  Compose UI → ViewModels → update process (8 steps) → repositories           │
│     → Room / DataStore · source provider (GitHub) · APK verifier             │
│     → isolated APK inspection process · PackageInstaller                     │
│  WorkManager: scheduled checks, background download and installation         │
└──────────────────────────────────────────────────────────────────────────────┘
```

## 2. Modules and layers

| Module | Type | Responsibility |
|---|---|---|
| `:core:model` | JVM | Provider-independent domain types: repository references and IDs, releases, assets, APK facts, findings, severities, versions, `DisplayText` (rejects invisible/control characters) |
| `:core:network` | JVM | OkHttp configuration, HTTPS enforcement, host allow-lists, manual redirect handling, size limits, timeouts, conditional requests, rate-limit tracking, `Retry-After`, exponential backoff, concurrency limit, safe file downloader |
| `:core:catalog` | JVM | Catalog schema parsing, entry validation, signature verification, `assetFilter` syntax, search normalization |
| `:core:source` | JVM | `SourceProvider` interface, `ProviderRegistry`, URL recognition; GitHub implementation; README parser (Markdown → restricted document model); disabled Codeberg stub |
| `:core:apk` | JVM | Asset classification and selection by ABI, version-name comparison, preliminary compatibility, ZIP inspection, `ApkVerifier` (integrity and the three signature levels) |
| `:core:update` | JVM | The eight update steps, update decisions, warning classification, policies (per-app overrides, automatic-install conditions, background decisions) |
| `:core:data` | Android library | Room database, DAOs, migrations; DataStore settings; repositories (catalog, tracked apps, details cache, security warnings, history, self-update state, import/export) |
| `:core:installer` | Android library | `PackageInstaller` sessions and result receiver, installed-package lookup (`PackageManager`, `SigningInfo`), platform `ApkInspector` and the isolated inspection service, uninstall request, shortcuts |
| `:app` | Android app | Compose UI, navigation, view models, manual DI container (`AppContainer`), WorkManager worker, notifications, self-update, build variants |

**Layering:** UI (Compose + ViewModel) → update process (`:core:update`) → repositories (`:core:data`) → data sources
(`:core:source`, Room, `:core:installer`). The UI never calls the network or GitHub-specific code directly.

The security-critical logic (catalog signature, APK decisions, update steps, network rules) lives in pure JVM modules so
that it can be tested quickly without Android. Screens are packages inside `:app` (`ui/discover`, `ui/details`,
`ui/apps`, `ui/sources`, `ui/settings`, `ui/history`, `ui/safety`), not modules.

**Dependency injection** is manual (`AppContainer`, lazily created singletons) – no Hilt or Koin. **Concurrency** uses
Kotlin coroutines; the UI observes Room `Flow`s (the database is the single source of truth).

## 3. Technologies

All runtime dependencies are Apache-2.0 or BSD licensed ([THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)); versions are
pinned in `gradle/libs.versions.toml` and verified by checksum.

| Area | Choice |
|---|---|
| Language, build | Kotlin 2.4, AGP 9.4, Gradle 9.6 (Kotlin DSL, version catalog) |
| UI | Jetpack Compose, Material 3, Navigation Compose |
| Data | Room (KSP) with FTS4; Preferences DataStore; `SharedPreferences` for theme and (Android 9–12) language, which must be available before the first frame |
| Background | WorkManager 2.12 |
| HTTP | OkHttp 5 directly (no Retrofit), for full control of headers, redirects, validators and limits |
| JSON | kotlinx.serialization, unknown keys ignored |
| Images | Coil 3 with OkHttp, without SVG decoder |
| Markdown | commonmark-java + GFM tables → own restricted model → own Compose renderer (no WebView) |
| APK verification | Android's own verifier (`getPackageArchiveInfo` with `GET_SIGNING_CERTIFICATES`) in a separate process; `apksig` only in JVM tests and fixture generation |
| Catalog crypto | platform JCA `SHA256withECDSA` (same code on JVM and Android) |
| Tests | JUnit 4, kotlin-test, coroutines-test, Turbine, MockWebServer with real TLS, Robolectric, AndroidX Test for instrumented tests |

No Google Play Services, Firebase, analytics or crash reporting.

## 4. Data model

Room database, schema version 7 (exported schemas in `core/data/schemas/`; every version has an automatic migration and
a migration test from the previous exported schema).

| Table | Contents |
|---|---|
| `catalog_state` | the accepted catalog's schema version, serial, timestamps, expiry, signing key ID, content hash, HTTP validators, last attempt and error |
| `catalog_app`, `catalog_app_fts` | catalog entries with normalized search columns; FTS4 index |
| `tracked_app` | every app the user has a relationship with: origin (catalog / user), repository path and permanent ID, owner ID, learned package name, follow and favorite flags, installation flags, last check status and error, repository state (OK / renamed / owner changed / ID changed / not found / unavailable), latest release and installed release data |
| `app_settings_override` | per-app overrides (`null` = global setting): monitoring, pre-releases, automatic download, automatic install, notifications, release delay |
| `signer_pin` | recorded signing certificates **per package** (source: first install via RepoShelf / existing install / catalog) and the highest version code ever seen installed on this device; kept when an app is removed from the list or uninstalled |
| `apk_analysis` | per-asset analysis result (SHA-256, package, version, signer, verdict) – confirms updates and prevents re-offering a rejected file |
| `details_cache` | saved repository, release and README data for offline app pages (max. 300 rows) |
| `security_warning` | one row per warning case (`dedupKey`), severity, first/last seen, acknowledged, resolved |
| `update_history` | installation attempts with versions, time, outcome, short reason (filtered), method (max. 1000 rows) |
| `notification_log` | last notified state per key, for de-duplication |

**Global settings** are in Preferences DataStore (`SettingsRepository`): check frequency and network type, automatic
download (and on mobile data), automatic install, pre-releases, release delay (0/1/3/7 days), update notifications,
update ownership, the last background run summary, the self-update opt-in and pending self-update state, and the recorded
identity of RepoShelf's own repository. Unknown or corrupted values fall back to safe defaults.

## 5. Source provider

```kotlin
interface SourceProvider {
    val id: ProviderId
    fun parseRepoUrl(input: String): RepoRef?
    suspend fun repository(ref: RepoRef, validators: Validators? = null): Fetch<RepoMetadata>
    suspend fun repositoryById(id: ProviderRepoId, validators: Validators? = null): Fetch<RepoMetadata>
    suspend fun readme(ref: RepoRef, validators: Validators? = null): Fetch<Readme>
    suspend fun releases(ref: RepoRef, validators: Validators? = null, limit: Int = 10): Fetch<List<Release>>
    fun downloadPolicy(maxBytes: Long): HttpPolicy
}
```

`Fetch` distinguishes fresh data (with validators), not modified, not found, unavailable (blocked/removed), rate limited
and failed. Version detection, asset selection and update checks are provider-independent and work on the
`Release`/`ReleaseAsset` model.

**GitHub implementation:** `GET /repos/{owner}/{repo}` (metadata and identity), `GET /repositories/{id}` (fallback to find
renamed or moved repositories), `GET /repos/{owner}/{repo}/readme` (raw Markdown) and
`GET /repos/{owner}/{repo}/releases?per_page=10` (not `/releases/latest`, which is not a reliable "newest" signal).
Constant headers (`Accept`, `X-GitHub-Api-Version`, a `User-Agent` with only the app name and version) so that ETags
match. APKs are downloaded from `browser_download_url`, which does not count against the API limit. Downloads may only go
to `github.com` and `*.githubusercontent.com`.

**Other providers:** a disabled `CODEBERG` provider ID and a network-free `CodebergStubProvider` (not registered) showed
that Codeberg/Forgejo fits the interface. GitLab would additionally need a namespace path in `RepoRef` (nested groups).
**(Not implemented:** GitLab and Codeberg providers.)

## 6. Network layer

| Rule | Implementation |
|---|---|
| HTTPS only | `network_security_config` (no cleartext, system CAs only); every request and redirect checked for `https` |
| Redirects | followed manually, at most 5 hops, only to hosts allowed for that request type |
| Size limits | API JSON 2 MiB, README 512 KiB, catalog 8 MiB, catalog signature 16 KiB, APK ≤ announced size and ≤ 1 GiB, README images 10 MiB |
| Timeouts | connect 15 s, read 30 s, whole call 60 s; downloads have no whole-call limit but keep the inactivity (read) timeout |
| Conditional requests | ETag / Last-Modified stored with cached data; 304 accepted only for conditional requests; server validators must be short printable ASCII |
| Rate limits | `RateLimitTracker` records `x-ratelimit-*`; `Retry-After` and reset times are respected; transient errors use exponential backoff with jitter and a small retry limit |
| Concurrency | at most 2 API requests at a time; update checks run app by app |
| Budget | background runs never use the requests reserved for the UI (at least 15); checks stop when the budget is exhausted and report when they can continue |

The anonymous API limit (60/hour per IP address, also consumed by 304 responses) is the main constraint: an update check
costs about 2 requests per app (repository + releases), the catalog costs none (raw host).
**(Not implemented:** optional GitHub token or device-flow sign-in for a higher limit.)

## 7. Catalog

The catalog repository holds one JSON file per app (`apps/<id>.json`); a deterministic build produces
`dist/v1/catalog.json`, which is signed with a detached signature (`catalog.json.sig`). See the catalog repository's
`docs/SCHEMA.md` for the format.

- **Fields:** required `id`, `name`, `source.provider`, `source.repository`, `category`; optional `source.repositoryId`,
  `packageName`, `signingCertSha256`, `minVersionCode`, `keywords`, `assetFilter`. No descriptions, icons or screenshots:
  those come from the repository.
- **Versioning:** the major schema version is in the path (`dist/v1/`). Compatible additions keep the version; the client
  ignores unknown fields. A newer `schemaVersion` is not processed: the last valid catalog stays and the UI asks to update
  the app.
- **Signature:** ECDSA P-256 / SHA-256 over the exact file bytes; trusted public keys are compiled into the app
  (`reposhelf.catalog.publicKeys`, `keyId:base64 SPKI`, P-256 only); at least one trusted key's valid signature is
  required; up to 8 signatures (key rotation). Verified **before** parsing, for downloaded and built-in catalogs.
- **Freshness:** serial must increase (equal serial with different content is rejected); `MIN_CATALOG_SERIAL` is compiled
  in; an expired catalog or one dated more than 24 hours in the future is not accepted from the network; validity at most
  400 days. The stored catalog remembers the accepting key and is rebuilt from the built-in catalog if that key is no
  longer trusted or its serial is below the compiled-in minimum.
- **Invalid entries** are skipped; if too many are invalid, or no entry is usable, the whole update is rejected. Entries
  for unknown providers are skipped without counting as errors.
- **Storage:** the client downloads from `raw.githubusercontent.com` (not counted against the API limit) with conditional
  requests; the replacement is a single Room transaction. **(Not implemented:** fallback hosts such as GitHub Pages or a
  release asset.)
- **Built-in catalog:** `assets/catalog/catalog-v1.json` (+ `.sig`), a byte-identical copy of the catalog repository's
  `dist`, loaded with the same rules on first start. A downloaded catalog replaces it only with a higher serial.

## 8. Search

Text is normalized on storage and query in Kotlin (Unicode NFD, combining marks removed, `Locale.ROOT` lower case,
punctuation to spaces), independent of the device's SQLite. FTS4 prefix queries on name, keywords and category (all
tokens must match); if this returns fewer than 50 results, an infix `LIKE` fallback runs. Only normalized tokens reach
the FTS query, so FTS operators cannot be injected. Ranking: exact name > name prefix > keyword > category, then by name.
Searching makes no network requests. Measured on Robolectric with 5000 entries: about 10 ms.

## 9. The eight-step update process

Each step is a separate class with its own input and output, returning `StepResult` (`Ok(value, findings)`,
`Blocked(findings)`, `Failed(error)`). First installations and updates use the same path (`InstallCoordinator`).

| # | Step (class) | Main checks |
|---|---|---|
| 1 | Discovery (`ReleaseDiscovery`) | repository identity (permanent ID, renames, owner changes, hijacking); drafts excluded; pre-releases only if enabled; release delay; only releases with an installable asset |
| 2 | Version (`VersionResolver`) | analyzed version code of the selected asset > identity of the installed release > version name > release date > "cannot be determined" (not an error) |
| 3 | Compatibility (`CompatibilityAssessor`) | format (`.apk`; `.aab`/`.apks`/`.xapk` rejected), ABI tokens, `assetFilter`, storage; **preliminary estimate** |
| 4 | Download (`ApkDownloader`) | HTTPS and host rules, expected size, resumable `.part` file with identical-content check, SHA-256 while downloading; partial files of other downloads limited to 512 MiB in total (oldest removed first, never one written in the last 10 minutes) |
| 5 | Integrity (`ApkVerifier.checkIntegrity`) | size, SHA-256 vs. GitHub digest, ZIP structure, valid APK |
| 6 | Signature (`ApkVerifier.checkSignature`) | the three levels (see SECURITY.md); package name, version code (for an app that is not installed: a warning if it is older than the highest version previously installed on the device), minimum SDK, ABI |
| 7 | Install start (`InstallInitiator`) | re-checks package name, version code and signer against the state **at install time**; no critical finding; session bytes re-hashed |
| 8 | Result (`InstallResultVerifier`) | installed version code and signer read back; signer recorded on first install; history entry; failure reasons mapped (including developer verification) |

Before step 4 on the manual path, a fresh repository identity check runs (`confirmIdentity`); the background path does
this in step 1. Because the version code is only known after downloading, a background check reports a "probable"
update that the analysis confirms or rejects; the result is stored per asset.

## 10. Installation

- **Session:** `PackageInstaller` session with the expected package name (`setAppPackageName`),
  `INSTALL_REASON_USER`, package source (`STORE` for catalog apps, `DOWNLOADED_FILE` for own sources, Android 13+), and on
  first installation `setRequestUpdateOwnership(true)` (Android 14+, if enabled). First installations always require
  user action.
- **No check/use gap:** the checked file is streamed into the session; before commit the session content is re-hashed
  with `openRead()` and must match.
- **Status:** an explicit, non-exported receiver; its `PendingIntent` is mutable as Android requires (the only mutable one
  in the app). `STATUS_PENDING_USER_ACTION` is shown immediately when the app is in the foreground, otherwise via a
  notification that opens our own activity.
- **Split APK sets:** base APK fully checked; split files checked for size, SHA-256 and ZIP structure; package name,
  version code and signer of the splits are enforced by Android at install time (verified with
  `tools/split_install_check.py` on API 28 and 37); explicit user confirmation; never unattended; self-update accepts only
  a standalone APK.
- **Shortcuts:** `INSTALL_REASON_USER` lets AOSP-style launchers add the icon; additionally, if supported, the app page
  offers a pinned shortcut (`requestPinShortcut`). **(Not implemented:** disabling the shortcut when the app is removed.)

### Installation without user action

`UnattendedInstallPolicy` allows `USER_ACTION_NOT_REQUIRED` only if **all** hold; otherwise the normal confirmation flow
runs, which is not an error:

1. the user enabled automatic installation (globally or for the app);
2. it is an update, not a first installation;
3. Android 12+ (API 31);
4. RepoShelf is the app's update owner, or (if there is no update owner) its installer of record – or the package is
   RepoShelf itself ("updating itself");
5. the update's target SDK reaches the documented threshold (device API 31/32 → 29, 33 → 30, 34 → 31, 35 → 33, 36 → 34,
   37+ → 35);
6. the update passed every check with the installed app's signer and has no critical finding;
7. no open critical (or unacknowledged warning-level) security warning for the app; not a split set.

Android may still ask for confirmation; RepoShelf then notifies the user. **(Not implemented:** `GENTLE_UPDATE` /
`commitSessionAfterInstallConstraintsAreMet`, and user pre-approval.) On the Android 17 emulator an update was installed
without a dialog; behavior under the 2027 developer verification rules is not yet verified.

## 11. Background work and notifications

- **`UpdateCheckWorker`:** unique periodic work (`ExistingPeriodicWorkPolicy.UPDATE`), frequency from settings,
  constraints `CONNECTED` (or `UNMETERED`) and battery not low, **no** device-idle constraint; cancelled in "manual only"
  mode; always returns success (no retry storms). It refreshes the catalog conditionally, checks followed apps one by one
  within the request budget, and acts per app according to `BackgroundDecision` (notify / download / install). Finally, if
  enabled and the budget allows, it checks for a RepoShelf update. Old downloads are cleaned up (complete files after 2
  days, partial files after 7).
- **Download in the background** is a short, interruptible worker step without a foreground service; an interrupted
  `.part` file is resumed on the next run. Downloads on metered networks only if allowed.
- **Rescheduling after reboot** is handled by WorkManager.
- **Notifications:** seven channels in one group (`updates`, `installed`, `failures`, `security`, `catalog`,
  `self_update`, `progress`); de-duplication through `notification_log` (only notifications that could actually be posted
  are recorded); stable tag/ID pairs replace older notifications; explicit immutable `PendingIntent`s to `MainActivity`
  with a validated internal ID. `POST_NOTIFICATIONS` is requested with an explanation on first follow or when enabling
  notifications; if notifications are blocked, an in-app banner says so.
- A debug-only intent extra runs the background check immediately for emulator testing.
- **(Not implemented:** persistent state for background installations of other apps between commit and result; a
  long-running download job.)

## 12. Package visibility

`<queries>` with the `MAIN`/`LAUNCHER` intent makes launchable apps visible, so their version and signer can be read;
`QUERY_ALL_PACKAGES` is not requested. Packages without a launcher icon are not visible, and the UI says "not installed
(or not visible)". The installed state is always read from `PackageManager` when a screen comes to the foreground. Catalog
entries usually have no package name; it is learned from the first accepted, checked APK.

## 13. Self-update

- Build parameters: `reposhelf.selfUpdate.repo` (default `X1wer/reposhelf`; empty = self-update disabled, as in the
  `fdroid` variant) and `reposhelf.selfUpdate.signerSha256` (expected release signer; empty = the running app's current
  signer).
- The same eight steps on stable releases only, with the repository's permanent ID recorded on first contact; the version
  code must be higher than the running one; the signer must match the compiled-in fingerprint (or the running app).
- Manual path in Settings; background path (opt-in) at the end of the background check.
- Interrupted updates: the pending state is stored before commit, the session ID after; reconciled at process start and
  on `MY_PACKAGE_REPLACED` (success, still waiting, or interrupted); orphaned uncommitted sessions are abandoned
  (Android 10+).
- Opt-in with a neutral first-start question; the manual check is always available.

## 14. Android versions

- **minSdk 28** (Android 9): rotation-aware `SigningInfo`, `hasSigningCertificate` and `getLongVersionCode` are
  available, so signer continuity has one platform-supported code path (about 97% of devices, October 2026).
- **compileSdk / targetSdk 37** (Android 17).
- Version-dependent features are checked at runtime: unattended updates (31+), package source (33+), notification
  permission (33+), per-app language API (33+), update ownership (34+).
- API levels are enforced by Android Lint (`NewApi` as error) and, for pure JVM modules, by a bytecode check
  (`tools/check_jvm_api_level.py`).

## 15. Build variants, signing and release

| | `github` (default) | `fdroid` |
|---|---|---|
| Application ID | `io.github.x1wer.reposhelf` | `io.github.x1wer.reposhelf.fdroid` |
| Self-update | yes, opt-in | none (no question, no request) |
| Channel | GitHub Releases | F-Droid (not submitted yet) |

No Play variant: Play's policies do not allow app stores to use the install permission or self-update. Debug builds get
a `-dev` version suffix. Release signing comes only from environment variables (`REPOSHELF_KEYSTORE_FILE`,
`REPOSHELF_KEYSTORE_PASSWORD`, `REPOSHELF_KEY_ALIAS`, `REPOSHELF_KEY_PASSWORD`); without them the APK is unsigned.
Release builds are refused while a catalog **test** key is configured (`-Preposhelf.allowTestCatalogKey=true` builds a
non-distributable `-testcatalog` variant for testing). Dependency metadata encrypted for Google is not included in the APK.

**CI** (`.github/workflows/ci.yml`, on pull requests and `main`): read-only permissions, actions pinned to commit SHAs,
Python tool tests, unit tests, both debug variants, lint, instrumented-test APKs, JVM API-level check, R8 release build
with the test catalog key, OSV vulnerability check, artifacts. **Release** (`release.yml`, on `vX.Y.Z` tags): checks tag,
version and changelog section; requires the configured signer fingerprint; tests; signs both variants with secrets of the
protected `release` environment; verifies the signer; writes `SHA256SUMS`; creates a **draft** release. Dependabot
updates the pinned actions. The workflows have not run on GitHub yet.

## 16. Testing

- **JVM unit tests** for every core module, including the eight steps, catalog parsing and signatures, network behavior
  over real TLS (MockWebServer with `okhttp-tls`; the product code has no test exception for HTTP), APK checks with real
  test-key-signed fixture APKs (`core/apk/testdata/generate_fixtures.py`).
- **Robolectric** for Room (FTS, migrations), repositories and view models.
- **Instrumented tests** for platform-specific security code (`PlatformApkInspector`, the isolated inspector, the overlay
  protection) on API 28 and API 37 emulators.
- **Localization test**: every string exists in English and Hungarian with matching placeholders and plurals; no
  hard-coded UI text.
- **Python tests** for the release, OSV and notices tools.
- Emulator runs for each feature (installation, real update, background work, self-update simulation, offline mode,
  import/export, languages and themes) on Android 9 and Android 17. **No physical device has been tested yet.**

## 17. Not implemented / future work

- GitHub sign-in (token or device flow), GitLab and Codeberg providers.
- `GENTLE_UPDATE` for unattended updates, user pre-approval, a warning for newly requested sensitive permissions, a
  warning when a followed app's catalog entry changes, persistent state for background installations of other apps.
- Replay protection for a first installation on a device that never had the app (only the catalog's `minVersionCode`
  applies there; after an uninstall, an older package gets a warning).
- Catalog fallback hosts; a releases hint file to save API requests.
