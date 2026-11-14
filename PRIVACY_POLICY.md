<!-- SPDX-FileCopyrightText: 2026 RepoShelf contributors -->
<!-- SPDX-License-Identifier: CC-BY-4.0 -->

# Privacy policy

*Magyar változat: [PRIVACY_POLICY.hu.md](PRIVACY_POLICY.hu.md)*

Applies to: the RepoShelf Android app (both the GitHub and the F-Droid build), version 0.1.0 and later.
Last updated: 2026-11-14.

> This policy describes the app as it is actually implemented (checked against the source code). It has not yet been
> reviewed by a lawyer; such a review is recommended.

## Summary

- RepoShelf has **no server** of its own, **no account or registration**, **no analytics, no advertising, no crash
  reporting and no tracking code**. It does not use Google Play Services or Firebase.
- The RepoShelf project (its maintainers) **receives no data about you**.
- Your list of apps, your settings and your history **stay on your device**.
- To work, the app talks directly to **GitHub**. GitHub therefore receives your IP address and learns which public
  repositories the app requests. Images from other websites are only loaded when you ask for them.

## Who is responsible

RepoShelf is an open-source project maintained by volunteers. Because the project operates no service and does not
collect or receive personal data, it does not act as a data controller for the use of the app. The data processing
described in this policy happens on your device, under your control, and – for network requests – at the services the app
connects to (mainly GitHub), which are responsible for their own processing under their own privacy statements.

Questions about this policy: open an issue in the RepoShelf GitHub repository, or use its private security advisory form
for anything sensitive (see [SECURITY.md](SECURITY.md)).

## Network connections

RepoShelf only uses HTTPS and only connects to the hosts below. It does not follow redirects to other hosts (except for an
external README image you explicitly asked to load).

| Host | What for | When |
|---|---|---|
| `raw.githubusercontent.com` | the signed app catalog and its signature; README images stored in the repository | catalog: at most every 6 hours on start-up, on "Refresh catalog", and with background checks; images: when you open an app page |
| `api.github.com` | repository information, releases, README, repository identity checks | when you open an app page, add a repository, check for updates (manually or in the background), before downloading |
| `github.com`, `*.githubusercontent.com` | downloading APK files from GitHub Releases (GitHub redirects to its download servers); images hosted on GitHub | when you (or, if you enabled it, the automatic download) download an app; when an app page shows such images |
| any other HTTPS host | images embedded in a README from other websites | **only** after you tap "Load external images" on that page |
| your browser | links in a README or "Open repository" | only when you tap the link; links outside GitHub ask for confirmation first |

**What these services receive:** like any internet connection, your **IP address**; the request itself (for example which
public repository or file is requested, which reveals which apps you look at, follow or install); and the technical
headers `User-Agent: RepoShelf/<version> (Android)`, the GitHub API version, and cache validators (ETag) of earlier
responses. RepoShelf sends **no** device identifier, advertising ID, account, token, list of installed apps or location.

**GitHub** is operated by GitHub, Inc. Its processing is described in the
[GitHub General Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
GitHub may process this data outside the EU. RepoShelf uses the GitHub API **anonymously**; signing in with a GitHub
token is not implemented.

**External README images** can reveal your IP address to the website that hosts them and could be used for tracking; this
is why they are blocked until you allow them for a page.

**Self-update check** (GitHub build only): only if you agreed when first asked, or turned on "Check for RepoShelf updates
automatically", RepoShelf asks `api.github.com` about its own repository, in the same way as for other apps. The F-Droid
build makes no such request.

**Android itself** (not RepoShelf) may contact Google services when apps are installed – for example Google Play Protect
scanning, or developer verification on certified devices from 2027. These are governed by Google's terms and your device
settings.

## Data stored on your device

All data below is stored in RepoShelf's private app storage, which other apps cannot read. It is **excluded from Android
cloud backup and from device-to-device transfer**.

| Data | Purpose | How long |
|---|---|---|
| The catalog (names, repository paths, keywords, categories) | browsing and offline search | replaced when a newer valid catalog arrives |
| Your followed / favorite apps and own sources, with repository IDs, last check results, learned package names | managing and updating your apps | until you remove the app from the list or clear the app's data |
| Recorded signing certificates (SHA-256) per package | refusing updates signed with a different key | until you clear the app's data (also kept after "Remove from list", on purpose) |
| Results of APK checks (package name, version, signer fingerprint, verdict) | not downloading or offering the same rejected file again | until you clear the app's data |
| Saved app pages (repository data, releases, README text) | offline mode, fewer network requests | at most 300 entries; oldest removed first |
| Security warnings | showing and acknowledging problems | until you clear the app's data |
| Update history (app name, package name, versions, time, result, short reason, method) | the update history screen | the latest 1000 entries |
| A notification log (which state was already notified) | avoiding repeated notifications | until you clear the app's data |
| Settings (update checks, automation, appearance, language, self-update answer) | your preferences | until you clear the app's data |
| Downloaded APK files | checking and installing | deleted after installation; leftovers are deleted after 2 days (complete files) or 7 days (interrupted downloads) |

The update history never contains URLs, file paths or tokens. RepoShelf writes only a few technical lines to Android's
system log (for example that the background schedule could not be set); they contain no personal data.

**Installed apps:** to show installed versions and to compare signers, RepoShelf reads the version and signing certificate
of installed apps that have a launcher icon. It does not request permission to see all installed apps, and this
information never leaves your device.

## Export

"Export…" writes a file to a location **you** choose with Android's file picker. The file contains your followed and
favorite apps, your own sources and per-app rules – no tokens, signing keys, installation data or history. What happens
with the file afterwards is up to you.

## Permissions

| Permission | Why |
|---|---|
| Internet, network state | connecting to GitHub; checking that a network is available before background checks |
| Install apps (`REQUEST_INSTALL_PACKAGES`) | installing apps through Android's installer; you must also allow "Install unknown apps" |
| Update without user action (`UPDATE_PACKAGES_WITHOUT_USER_ACTION`) | automatic updates where Android allows them, only if you turned them on |
| Update ownership (`ENFORCE_UPDATE_OWNERSHIP`) | asking other installers to get your confirmation (Android 14+), can be turned off |
| Request uninstall (`REQUEST_DELETE_PACKAGES`) | "Uninstall app" opens Android's confirmation; nothing is removed in the background |
| Notifications (`POST_NOTIFICATIONS`) | update and security notifications; asked only when needed (Android 13+) |
| Hide overlays (`HIDE_OVERLAY_WINDOWS`) | hiding other apps' overlays while RepoShelf is open (Android 12+), against tapjacking |
| Run at start-up, keep awake, foreground service | used by Android's WorkManager library to keep the background check schedule after a restart and to run it reliably |

RepoShelf does not request location, contacts, camera, microphone, storage or "query all packages" permissions.

## Your choices and rights

- **Turn off network activity you do not want:** set "Check automatically" to "Only manually", keep external images
  blocked, and do not enable self-update checks.
- **Delete your data:** "Remove from list" removes an app's entry (the recorded signer is kept for your protection).
  To delete everything, use Android Settings → Apps → RepoShelf → Storage → "Clear storage", or uninstall RepoShelf.
- **GDPR:** if you are in the EU/EEA, the General Data Protection Regulation gives you rights such as access, rectification
  and erasure. As the RepoShelf project holds no data about you, requests about data processed by GitHub or other
  websites must be addressed to them. Data on your device is fully under your control as described above. You can lodge
  a complaint with your local data protection authority (in Hungary: Nemzeti Adatvédelmi és Információszabadság Hatóság,
  NAIH).

## Children

RepoShelf is a general-purpose tool and is not directed at children. It does not knowingly collect any data from anyone.

## Changes

Changes to this policy are recorded in the repository history and summarized in [CHANGELOG.md](CHANGELOG.md). If a future
version adds a feature that changes how data is handled (for example optional GitHub sign-in), this policy will be updated
before that version is released.
