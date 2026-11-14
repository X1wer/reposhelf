<!-- SPDX-FileCopyrightText: 2026 RepoShelf contributors -->
<!-- SPDX-License-Identifier: CC-BY-4.0 -->

# Security

This document describes RepoShelf's security model, the checks it actually performs, their known limits, and how to
report a vulnerability. Architecture details: [ARCHITECTURE.md](ARCHITECTURE.md). Limits of responsibility for
third-party apps: [DISCLAIMER.md](DISCLAIMER.md).

## Reporting a vulnerability

**Please do not report security vulnerabilities in public issues.**

Use GitHub's private vulnerability reporting: in the RepoShelf repository, open the **Security** tab and choose **Report
a vulnerability**. (The repository is not public yet; private reporting will be enabled when it is published.) For
vulnerabilities in the catalog tooling or in a catalog entry, use the same feature in the catalog repository.

Please include the affected version or commit, the Android version, steps to reproduce, and the impact you expect. The
maintainers aim to acknowledge reports within 7 days and to agree on a disclosure date with you; this is a volunteer
project, so there is no guaranteed response time. Do not include personal data of other people.

## Supported versions

| Version | Supported |
|---|---|
| 0.1.x (in development, not yet released) | security fixes go into the next release |

Only the latest release receives security fixes. RepoShelf can update itself (GitHub build, opt-in), and the release
process refuses to publish an APK not signed with the expected key.

## Security model

RepoShelf has no server. Everything it receives is treated as **untrusted**: GitHub API responses, README files,
catalog entries, release metadata, download URLs, APK files and import files. Every decision that matters – whether a
file may be installed – is made from the **downloaded APK itself**, on the device.

### Trust boundaries

- **The catalog** (signed) is trusted only for metadata: which repository an app comes from, and optionally its
  repository ID, package name, signing certificate fingerprints, minimum version code and asset filter. It does not
  certify that an app is safe.
- **GitHub** is trusted to serve what the repository owner published – not to tell whether that is benign. A compromised
  developer account can publish anything; the signer check below is the main defense against that.
- **Android** is trusted as the final gate: its package installer enforces signature continuity for updates and verifies
  APK signatures again.

### What RepoShelf checks

| Area | Protection |
|---|---|
| Network | HTTPS only (cleartext disabled, system CAs only, no user CAs); per-purpose host allow-lists (`api.github.com`, `raw.githubusercontent.com`, `github.com`, `*.githubusercontent.com`); redirects only to allowed hosts and never to HTTP; size limits for every response (API JSON 2 MiB, README 512 KiB, catalog 8 MiB, signature 16 KiB, APK ≤ 1 GiB and the size announced by GitHub); timeouts. No certificate pinning (trust comes from the catalog and APK signatures). |
| Catalog | Detached ECDSA P-256 / SHA-256 signature over the exact bytes, verified **before parsing** against public keys compiled into the app (key rotation supported, P-256 only). Monotonic serial number (rollback protection), minimum serial compiled into the app, expiry (an expired catalog is not accepted from the network; the existing one stays usable with a warning), limits on serial, dates and validity period. Invalid entries are skipped; too many invalid entries reject the whole update. The last valid catalog is kept on any failure. A built-in, signed catalog is verified the same way. |
| Repository identity | The permanent GitHub repository ID is recorded at first contact (or taken from the catalog). Same name with a different ID ("repository hijacking") is **critical**: nothing is downloaded or installed. A fresh identity check runs immediately before every manual download; if identity cannot be confirmed, nothing is downloaded. Renames are followed; an owner change is a warning. A catalog entry's data applies to a followed app only while the entry still points to the same repository. |
| Download | Directly from GitHub Releases into private storage; size check; SHA-256 compared with GitHub's published digest when available; resumable only for identical content (same ETag and size); one download per target file. |
| APK analysis | Parsed by Android's own package parser in a **separate process** (`:apkinspect`) with a timeout, so a malicious file can at most crash or stall that helper process. ZIP structure checked without extracting to disk. |
| Signature, three levels (kept separate in code and UI) | **1. Technical validity:** Android's verifier (`GET_SIGNING_CERTIFICATES`). Invalid → critical. **2. Signer continuity:** compared with the installed app, the locally recorded signer, the catalog's fingerprints, and – for self-update – the fingerprint compiled into the build. Every available source must match independently. Key rotation (APK Signature Scheme v3 lineage) is accepted only if the recorded certificate is in the verified lineage and the most recent recorded signer is still valid. Multiple signers are rejected. A different signer is **never** accepted automatically, and RepoShelf never offers uninstall-and-reinstall. **3. Publisher trust:** shown as facts only; never rated "safe". |
| Package and version | Expected package name (also enforced by the install session); version code must not be lower than the installed one (a downgrade is critical; the same version is a reinstall
with a warning) and not below the catalog's `minVersionCode`; minimum SDK and ABI compatibility. |
| Installation | Official `PackageInstaller` sessions only (no root, Shizuku or hidden APIs). The bytes written into the session are hashed again and must equal the checked file (no time-of-check/time-of-use gap). First installations always require the user's confirmation. Installation without confirmation only for updates, on Android 12+, when Android's documented conditions hold **and** the update passed every check with the installed app's signer and has no open critical warning. Split APK sets: the base APK is fully checked; split files are checked by Android at install time and need explicit confirmation; never installed without confirmation. After installation the installed version and signer are read back. |
| Security warnings | Critical findings block automatic installation on every path, also after acknowledgement; warnings pause it until acknowledged. A release-bound critical warning is only resolved by a newer file that passes every check with a verified signer. |
| README | No WebView, no HTML rendering, no JavaScript: Markdown is parsed into a restricted model and rendered natively. Linear-time HTML stripping, rendering limits, links only `https`/`http`/`mailto`, images only HTTPS; images from outside GitHub load only on request; links outside GitHub need confirmation and show the real target; SVG is not rendered. |
| Import | Strict, size- and depth-limited parsing; confirmation dialog listing new own sources; never installs or downloads, never enables automation, never changes existing apps' settings; trust data (package names, signers) is never imported. |
| Android components | Only the launcher activity is exported (plus library components protected by system permissions); incoming navigation extras are discarded; notification PendingIntents are explicit and immutable (the installer status PendingIntent is explicit and mutable, as Android requires). Overlays: hidden on Android 12+; on Android 9–11 touches arriving through another app's window are dropped, with an explanatory banner. |
| Storage and logs | Private storage only; no cloud backup or device transfer; no tokens exist; logs contain no personal data; the update history strips URLs and paths. |
| Supply chain | Gradle wrapper checksum; Gradle dependency verification (SHA-256 for every artifact); Gradle Plugin Portal restricted to the Kotlin and KSP plugins; GitHub Actions pinned to commit SHAs with minimal permissions; OSV.dev check in CI (a known vulnerability in any component packaged into the APK fails the build); release signing only from a protected GitHub environment; drafts only, published manually; the signer of release APKs is verified against the fingerprint in `gradle.properties`. |

These protections were reviewed in a security audit covering ten areas (network, download and APK analysis, signature and
identity, installation, README rendering, catalog, Android components, storage and logging, import, supply chain): 56
findings (0 critical, 1 high, 15 medium, 30 low, 10 informational). All high and medium findings were fixed with
regression tests (one medium only partly); the rest are fixed, accepted with a reason, or listed below.

## Known limitations

RepoShelf cannot protect against everything. Known, documented limits:

1. **Malicious code signed with the genuine key.** If a developer – or their signing key – is compromised, a malicious
   update signed with the right key passes every check. The "Delay new releases" setting gives the community time to
   notice such releases.
2. **First installation is trust on first use** unless the catalog records the signer. RepoShelf shows the signer
   fingerprint so it can be compared with the developer's published value.
3. **Downgrade protection for apps that are not installed** is limited: RepoShelf remembers the highest version code it
   has seen installed for each package, and a package older than that gets a **warning** (not a block, because a
   developer may withdraw a broken release). On a device that never had the app, only the catalog's optional
   `minVersionCode` and the choice of the latest release apply, so an old, validly signed version could be offered as a
   first installation.
4. **Split APK sets:** split files are verified only by Android at install time (user decision, explicit confirmation).
5. **No warning yet when a catalog entry of a followed app changes** or disappears (the entry's data is already ignored
   if it points elsewhere; planned).
6. **No warning for newly requested sensitive permissions** in an update (not implemented).
7. **Background installations of other apps** keep their in-progress state only in memory; if the process dies between
   commit and result, the history entry and notification are missing (the next check sees the real version).
8. **No "gentle update":** an automatic update may replace an app that is in use.
9. **Download folder:** partial downloads of other files are limited to 512 MiB in total (oldest first); completed files
   waiting for confirmation are removed by age (2 days; partial files after 7 days). There is no hard limit for completed
   files beyond the free-space check.
10. **Package visibility:** only apps with a launcher icon are visible to RepoShelf (it does not request
    `QUERY_ALL_PACKAGES`).
11. **The anonymous GitHub API** limit (60 requests/hour per IP) can be exhausted by others on the same network, which
    delays checks (never weakens them: if identity cannot be confirmed, nothing is downloaded).
12. **The recorded signer cannot be changed in the UI.** Switching to a differently signed build of an app (for example
    F-Droid's) requires uninstalling that app yourself. This is intentional.
13. **Catalog freshness:** if maintainers stop re-signing, clients keep the last catalog with a warning; they cannot get
    new data until a fresh signed catalog is published.
14. **Not yet verified on physical devices;** all device tests so far ran on the Android emulator (Android 9 and 17).

## Before the first public release

These steps are required before any APK may be published, and are deliberately enforced by the build:

1. **Catalog key:** replace the test key (`catalog-test-2026a`) with the real catalog signing key (kept as a GitHub
   Actions secret in a protected environment, with an offline backup), re-sign the catalog, and update
   `reposhelf.catalog.publicKeys` and the built-in catalog. Release builds fail while the test key is configured.
2. **Release key:** create the app signing key offline (PKCS#12, RSA 4096), keep two offline copies, store it only as
   secrets of the protected `release` environment, and put its SHA-256 certificate fingerprint into
   `reposhelf.selfUpdate.signerSha256`. The release workflow refuses to release without it.
3. **Repository settings:** branch and tag protection (pull requests only, code-owner review, required checks, no force
   push), read-only default workflow token, approval for workflows from outside contributors, two-factor authentication,
   secret scanning with push protection, and private vulnerability reporting.
4. **First CI run:** the workflows have so far only been checked locally; their first run on GitHub must be reviewed.
