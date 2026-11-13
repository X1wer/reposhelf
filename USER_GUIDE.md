<!-- SPDX-FileCopyrightText: 2026 RepoShelf contributors -->
<!-- SPDX-License-Identifier: CC-BY-4.0 -->

# RepoShelf user guide

*Magyar változat: [USER_GUIDE.hu.md](USER_GUIDE.hu.md)*

This guide describes RepoShelf 0.1.0 (in development). Labels in quotes are the texts of the English interface. Where a
feature is planned but not available yet, the guide says so explicitly.

Contents:
[1. Installation](#1-installation) ·
[2. First start](#2-first-start) ·
[3. Browsing the catalog](#3-browsing-the-catalog) ·
[4. Installing apps](#4-installing-apps) ·
[5. Adding your own repository](#5-adding-your-own-repository) ·
[6. Managing apps](#6-managing-apps) ·
[7. Checking for updates](#7-checking-for-updates) ·
[8. Automatic updates](#8-automatic-updates) ·
[9. Notifications](#9-notifications) ·
[10. Settings](#10-settings) ·
[11. Security warnings](#11-security-warnings) ·
[12. Common problems](#12-common-problems) ·
[13. Troubleshooting](#13-troubleshooting)

---

## 1. Installation

**Requirements:** Android 9 or newer, and free storage for RepoShelf (the APK is about 5 MB) and for the apps you
download.

RepoShelf is distributed as an APK on its GitHub Releases page. *No public release exists yet; this section describes how
installation will work.*

1. Download the APK from the release page. Each release also lists a `SHA256SUMS` file; if you want, compare the
   checksum of the downloaded file with it.
2. Open the APK. Android asks you to allow your browser or file manager to install apps ("Install unknown apps") –
   allow it for that app only.
3. Confirm the installation in Android's dialog.

There are two variants: the standard (GitHub) build, which can update itself, and an F-Droid build
(`io.github.x1wer.reposhelf.fdroid`) without self-update. They are separate apps and do not update each other. The
F-Droid build has not been submitted to F-Droid yet.

> **Android developer verification (2027):** Google announced that from 2027 certified Android devices will require apps
> to come from registered developers, with an "advanced flow" for apps from unregistered developers. Whether RepoShelf
> will be registered has not been decided yet. If Android blocks the installation of RepoShelf or of an app you install
> with it, see [Common problems](#12-common-problems).

## 2. First start

- **Catalog:** RepoShelf starts with a built-in, signed copy of the catalog, so you can browse immediately, even without
  internet. When online, it tries to download a newer catalog (at most every 6 hours on start-up). A status line on
  Discover shows where the catalog comes from ("Built-in catalog – not yet updated from the internet" or "Catalog updated:
  …").
- **Self-update question (GitHub build only):** RepoShelf asks once whether it may check its own GitHub repository for new
  versions ("Updates for RepoShelf itself"). "Check for updates" and "Don't check" are equal choices; you can change your
  answer later under Settings → "RepoShelf updates". Nothing is downloaded without your consent.
- **No account:** RepoShelf needs no registration and no sign-in. It uses the GitHub API anonymously.
- **Background checks** are on by default (daily). Automatic download and automatic installation are off by default.
- **Notifications:** on Android 13 and newer, RepoShelf asks for the notification permission the first time you follow
  an app (or when you turn notifications on in Settings), after explaining why – not at first start.

The main screens are: **Discover**, **My apps**, **Updates**, **My sources** (shown as "Sources" in the navigation bar) and
**Settings**. On wide screens and in landscape the navigation is a side rail.

## 3. Browsing the catalog

**Discover** shows the catalog.

- **Search** ("Search apps") finds apps by name, keyword or category. Partial words work ("gall" finds "Gallery"), several
  words narrow the results, and accents and letter case are ignored ("terkep" finds "Térkép"). Searching uses only the
  data on your device.
- **Categories:** the category filter narrows the list to one category; "All" removes the filter.
- **Refresh catalog** (the refresh icon) downloads a newer catalog if there is one. A catalog is accepted only with a
  valid signature; otherwise the last valid catalog stays in use and the reason is shown (for example "the catalog
  signature is missing or invalid, so it was not accepted").
- If the catalog has not been renewed by its maintainers for a long time, RepoShelf shows "The catalog has not been
  renewed recently and may be out of date." You can keep using it.

> "Being listed in the catalog is not a security certification." The catalog only records which repository an app comes
> from (and, for some apps, its package name and signing key). See [DISCLAIMER.md](DISCLAIMER.md).

Tap an app to open its **app page**. The page loads from GitHub: developer, repository, license, description, latest
release and its date, release notes, download size, and the project's README. When something is missing, the page says
so ("The repository has no description.", "License: not specified", "No icon available") – RepoShelf never makes up data.
Apps usually have no icon before installation; images from the README are shown instead of screenshots.

**README display:** the README is shown as formatted text without a web browser engine. Images hosted on GitHub load
automatically; images from other websites are only loaded when you tap "Load external images", because loading them
reveals your IP address to those servers. SVG images are not shown. Links that lead outside GitHub show the full address
and ask before opening ("Open this external link?").

**Share** (on the app page) shares the repository's web address with another app.

## 4. Installing apps

On the app page, under **Installation**:

1. **Preliminary estimate (before download)** shows what can be known before downloading: whether there is an installable
   package, whether it was built for your device's processor, whether there is enough storage, and whether it is a
   pre-release. "Not a guarantee": the minimum Android version, the package name and the signature are checked after the
   download.
2. Tap **Download and check**. The first time, RepoShelf explains that it needs the "Install unknown apps" permission;
   "Open settings" takes you to the system switch. Allow it for RepoShelf. Every installation still needs your
   confirmation.
3. Before downloading, RepoShelf checks that the repository is still the same one (its permanent GitHub ID). If this
   cannot be checked right now (no connection, request limit reached), nothing is downloaded.
4. The download comes directly from GitHub over HTTPS. If it is interrupted, "Retry" continues where it stopped.
5. **Actual check (downloaded package)** lists every check with its result (OK / Warning / Failed / Not checked): file
   size, SHA-256 of the downloaded file, the checksum published by GitHub, package structure, package name, version code,
   minimum and target Android version, processor architecture, and the **signature in three separate levels**:
   1. *Technical validity of the signature* – checked by Android's own verifier.
   2. *Signer compared to the known state* – "Same signer as the installed app", "Matches the signer recorded in the
      catalog", or "First installation: the signer will be recorded now (trust on first use)".
   3. *Publisher trust* – "Cannot be verified cryptographically". RepoShelf shows the source; it does not rate apps as
      "safe".
   The signer certificate's SHA-256 fingerprint is shown, so you can compare it with the one the developer publishes.
6. Tap **Install**. Android shows its own confirmation dialog. If a critical problem was found, the button is replaced by
   "Installation blocked: a critical problem was found."
7. After installation RepoShelf reads the installed version back and confirms it. "Open" starts the app. If your launcher
   supports it, RepoShelf offers "Add shortcut" – only needed if the launcher did not add an icon by itself.

**Several packages in one release:** RepoShelf picks the package for your processor (for example `arm64-v8a`, or a
"universal" APK). If it cannot decide, it asks you to choose ("Choose a package"). App bundles (`.aab`) and archive
formats such as `.apks` or `.xapk` cannot be installed and are skipped with an explanation.

**Split APK sets:** some releases publish a base APK plus separate "split" files. RepoShelf fully checks the base package,
but the split files can only be checked by Android during installation. RepoShelf therefore asks first ("Install a split
APK set?" → "Install anyway") and never installs such a set automatically. If the release also contains a standalone APK,
that one is used.

**Pre-releases:** if a newer pre-release exists, the page says so and offers "Use pre-release". Pre-releases may be
unstable.

## 5. Adding your own repository

You can add any **public GitHub repository**, even if it is not in the catalog.

1. Open **My sources**.
2. Enter a GitHub URL (`https://github.com/owner/repo`) or `owner/name` and tap **Check**.
3. RepoShelf shows each step: the repository was identified, is available and public, its basic information loaded,
   releases found (and the latest), whether there is an installable Android package and whether it fits your device.
   Archived repositories are marked.
4. Choose **Follow and open** (to open the app page and install from there) or **Follow releases** (only follow new
   releases, without installing).

If the repository is also in the catalog, the catalog's recorded data (package name, signer) is used. Repositories you
added are listed under "Added by you" and are marked "Own source" in **My apps**.

Possible errors: "Not recognized as a GitHub repository", "Repository not found, or it is not public", "The repository is
not available", and the GitHub request limit ("Too many requests … Try again after …").

*Planned, not available:* GitLab and Codeberg repositories, and signing in to GitHub for a higher request limit.

## 6. Managing apps

**My apps** lists every app you follow, have marked as favorite, or have installed with RepoShelf.

Filters: "All", "Installed", "Followed only", "Favorites", "Own sources", "No longer installed".

Each row shows the name, icon (for installed apps), source repository, installed and latest version (pre-releases are
marked), the time of the last check, the update status, whether automatic updating is on, and the last temporary error if
there was one. The main button is "Update", "Install" or "Open", depending on the state.

The **⋮ menu** ("More actions") offers: "Check now", "Open repository", "App info" and "Uninstall app" (for installed
apps; uninstalling opens Android's own confirmation), "Stop monitoring updates" / "Monitor updates", "Favorite" /
"Remove from favorites", and "Remove from list".

- **Remove from list** removes the app from RepoShelf's list only. The app stays installed. Its recorded signer is kept,
  so a later update signed with a different key will still be refused.
- **Favorites** are independent of installation: you can mark catalog apps you have not installed.
- **Installed status** comes from Android itself. RepoShelf can only see apps that have a launcher icon (it does not ask
  for permission to see all installed apps). For a catalog app installed in another way, RepoShelf recognizes it after
  the first checked download; until then it shows "Not installed (or not visible to RepoShelf)".

On the app page, **Update rules for this app** let you override the global settings for this app (see
[Settings](#10-settings)).

## 7. Checking for updates

RepoShelf only looks at **GitHub Releases** – not at commits or tags. A newer release is an update if its package has a
higher **version code** (Android's internal version number). Because the version code is only known after downloading,
a release may first appear as "Update probably available (confirmed after download)". When version names cannot be
compared, RepoShelf says so instead of guessing. An update that once failed the checks is not offered again.

- **Check all** (on **My apps**) checks every followed app, one after another, oldest check first. "Check now" (⋮ menu)
  checks one app.
- RepoShelf uses the GitHub API **without signing in**: about **60 requests per hour**, shared by all devices on your
  network (your IP address). Each app check uses about 2 requests. RepoShelf always keeps some requests for the screens you
  open, so a large "Check all" may stop early and tell you how many apps were skipped and when it can continue.
- Temporary errors (no connection, request limit) do not overwrite the previous result; the row shows the last error
  separately.
- **Updates** lists apps with an available update and apps with problems. Followed apps that are not installed appear
  here when a new release is published. "History" (on the Updates screen) opens the update history.

## 8. Automatic updates

Background checks run with the frequency set under Settings → "Update checks". Android decides the exact time (battery
saving), so checks may run later than set. They need a network connection and a battery that is not low.

What happens when an update is found depends on your settings (globally, or per app):

| Setting | Effect |
|---|---|
| Only notifications (default) | "Update available" notification; you install from the app page. |
| "Download updates automatically" | The update is downloaded and checked in the background (on mobile data only if "Also on mobile data" is on). You get "Ready to install"; the app page installs it without downloading again. |
| "Install updates automatically where Android allows" | Downloaded and checked updates are installed without asking – **only when Android allows it** (see below). Otherwise you get a notification and confirm as usual. |

### When does Android NOT allow installation without confirmation?

Installing without your confirmation is an Android feature with strict conditions. If any of them is not met, Android
asks you to confirm – **this is normal behavior, not an error.** RepoShelf then sends a "Confirm the update" notification.

1. **First installation.** Installing a new app always needs your confirmation. Only updates can be automatic.
2. **Android version below 12.** Android 9–11 has no such feature; every update needs confirmation.
3. **RepoShelf is not the installer of the app.** Android allows it only if RepoShelf installed the app (it is the
   "installer of record") or is its "update owner". If the app came from another store, a browser or a computer (adb), the
   first update through RepoShelf needs your confirmation; after that RepoShelf is the installer of record. If another
   store is the app's update owner, RepoShelf cannot update it without confirmation.
4. **The app targets an old Android version.** The app being updated must target a recent enough Android version
   (target SDK): at least API 29 on Android 12, 30 on Android 13, 31 on Android 14, 33 on Android 15, 34 on Android 16 and
   35 on Android 17. RepoShelf uses the stricter documented values.
5. **Android decides otherwise.** Even when all conditions are met, Android may still ask – for example, for repeated
   updates of the same app in a short time, or because of device or manufacturer policy. From 2027, developer
   verification may add further checks.
6. **Play Protect** may show its own scan dialog for apps it does not know. This is outside RepoShelf's control.

RepoShelf adds its own conditions on top: the update must pass all checks with **the same signer as the installed app**,
there must be no critical security warning (and no unacknowledged warning) for the app, and **split APK sets are never
installed automatically**. Updates with a security problem are never installed automatically.

*Not available yet:* waiting with an automatic update until the app is not in use ("gentle update"). An automatic update
may therefore restart an app that is currently open.

## 9. Notifications

RepoShelf uses seven notification channels, which you can turn on or off one by one in Android's notification settings:

| Channel | When |
|---|---|
| App updates available | A followed, installed app has an update; an update is ready to install; Android asks you to confirm an update. |
| Updates installed | An update was installed and verified. |
| Failed downloads or installs | A background download or installation failed. |
| Security warnings | A security problem needs your decision (see [section 11](#11-security-warnings)). |
| Catalog updates | The catalog was updated. |
| RepoShelf updates | Updates of RepoShelf itself (GitHub build, if you allowed self-update checks). |
| Download progress | Technical, low-priority channel. |

- RepoShelf does not repeat a notification about an unchanged state.
- Tapping a notification opens the app page of the affected app.
- "Notify about available updates" in Settings turns update notifications off; per app, the "Update notifications" rule
  does the same.
- **If notifications are off,** the Updates screen and Settings show "Notifications are off. Updates and security
  warnings are still shown in the app." with a link to "Notification settings".

## 10. Settings

**Update checks**
- "Check automatically": every 6 hours, every 12 hours, daily (default), weekly, or "Only manually".
- "Check only on Wi-Fi (unmetered networks)".
- The time and result of the last background check.
- "Battery optimization settings" opens Android's settings. RepoShelf does not ask to be exempted from battery
  optimization; aggressive battery saving on some devices can delay or skip background checks.

**Download and install**
- "Download updates automatically" (off by default) and "Also on mobile data" (off by default).
- "Install updates automatically where Android allows" (off by default) – see [section 8](#8-automatic-updates).
- "Ask other installers to get my confirmation (update ownership, Android 14+)" (on by default): when RepoShelf installs
  an app for the first time, other stores need your confirmation before they can update it.

**Releases**
- "Include pre-releases (beta versions)" (off by default).
- "Delay new releases": no delay, 1, 3 or 7 days. A release is only considered after it has been public for that long.

**Notifications**
- "Notify about available updates" and a link to the system notification settings.

**Appearance**
- "Theme": system default, light or dark. "Use colors from the wallpaper" (Android 12+).
- "Language": system default, English or Magyar. On Android 13+ this is the same as the app language in Android's
  settings.
- Text size follows the system font size.

**Export and import**
- "Export…" saves your followed and favorite apps, your own sources and the per-app rules into a file you choose. The
  file contains no tokens, signing keys or installation data.
- "Import…" reads such a file. Before anything is changed, RepoShelf shows a summary and lists the own sources the file
  would add ("Import this list?"). Importing installs and downloads nothing, does not change apps already on your list,
  and never turns on automatic download, automatic installation or pre-releases. Only import files you trust.
- Settings, history and recorded signers are not exported. RepoShelf is excluded from Android's backup and device-to-device
  transfer; use export/import to move your list to a new device.

**RepoShelf updates** (GitHub build)
- "Check for RepoShelf updates automatically" (your answer to the first-start question), "Check now", then "Download and
  verify" and "Install". RepoShelf updates itself with the same checks as other apps; the signing key must match the
  installed RepoShelf. In the F-Droid build this section says "Self-update is not available in this build."

**Per-app rules** (app page → "Update rules for this app"): "Update monitoring", "Automatic download", "Automatic
install", "Pre-releases", "Update notifications", "Release delay". Each can be "Default (…)" – which follows the global
setting shown in brackets – "On" or "Off".

## 11. Security warnings

RepoShelf raises a warning when something looks wrong. Warnings have three levels:

- **Critical** – "Automatic installation is blocked." The affected update is not installed.
- **Warning** – "Automatic installation is paused until you acknowledge this."
- **Info** – shown where it is relevant (for example in the check results), not as a separate alert.

| Warning | Level | What it means / what to do |
|---|---|---|
| A different repository exists under the same name | Critical | The original repository was deleted or renamed and another one took its name (possible "repository hijacking"). Installing and updating are blocked. Only accept ("Accept new repository") if you are sure; the signer check still applies. If the catalog records the repository, this cannot be overridden. |
| The signing key changed | Critical | The update is signed with a different key than the installed app. RepoShelf never installs it and never offers to uninstall and reinstall. Ask the developer; a legitimate key change uses Android key rotation, which RepoShelf accepts. |
| The update has a different package name | Critical | The download contains a different app. |
| The update has an older version code than the installed app | Critical | A downgrade, which can reintroduce fixed bugs. |
| The downloaded file is corrupted or invalid | Critical | The file was deleted; try again later. |
| The repository owner changed | Warning | The repository now belongs to someone else. Check that this is expected, then "Acknowledge". |
| The repository disappeared or is unavailable | Warning | Checks pause; the installed app is not affected. |
| Not compatible with this device / unexpected format / authenticity cannot be verified | Info | Shown with the check results. "Cannot be verified" is normal for a first installation without a signer recorded in the catalog. |

Open warnings appear on the **Updates** screen (with "Acknowledge"), on the app page, and – for RepoShelf itself – in
Settings. A warning about a specific release is resolved when a newer release passes all checks with the verified signer.
Warnings are shown in the app even if notifications are off.

Repository renames are followed automatically ("The repository was renamed; RepoShelf follows the new name.").

*Not available yet:* a warning when an update requests new sensitive permissions, and a warning when a catalog entry
changes.

**Update history** (Updates → "History") records every installation attempt: app, package name, old and new version,
time, result ("Successful", "Failed", "Cancelled", "Blocked"), a short reason, and the method ("Manual", "Automatic",
"Automatic, confirmed by you", "RepoShelf self-update"). You can filter and search it. It keeps the last 1000 entries.

## 12. Common problems

| Problem | Explanation |
|---|---|
| "Permission needed" before downloading | Allow RepoShelf under Settings → "Install unknown apps" ("Open settings"). |
| An installed app shows "Not installed (or not visible to RepoShelf)" | RepoShelf can only see apps with a launcher icon, and for catalog apps it learns the package name from the first checked download. Use "Download and check" once. |
| "GitHub API limit reached" | The anonymous limit is 60 requests per hour per IP address, shared with other devices on your network (for example mobile carriers' shared addresses). Wait until the time shown. |
| "This repository has no release with an installable Android package (APK)" | The project publishes no APK on GitHub Releases. RepoShelf does not build from source code. |
| The release only contains an `.aab`, `.apks` or `.xapk` | These formats cannot be installed by RepoShelf. |
| "The signer differs from the installed app" | See [Security warnings](#11-security-warnings). Typically the app was installed from another source (for example F-Droid, which signs with its own key). RepoShelf cannot update it; this is intentional. |
| Automatic installation still asks for confirmation | Normal – see [section 8](#8-automatic-updates). |
| Background checks do not run on time | Android delays background work to save battery. Check battery optimization settings; use "Check all" when needed. |
| Nothing reacts to taps (Android 9–11) and a bar says "Another app … is drawing over RepoShelf" | A screen filter, night light or similar overlay app is active. RepoShelf ignores touches under other apps' windows to prevent tapjacking. Turn the overlay off. |
| Installation fails with "the developer is not verified by Android developer verification" | Android's developer verification blocked the app. Follow Android's own instructions; RepoShelf does not offer workarounds. |
| "The catalog signature is missing or invalid" | The downloaded catalog was rejected; the last valid catalog stays in use. Usually temporary. |
| "A newer catalog format is available" | Update RepoShelf to receive catalog updates; the current catalog keeps working. |

## 13. Troubleshooting

1. **Check the connection.** Offline, a bar shows what still works (catalog, your apps, saved app pages, history,
   settings); checking, downloading and installing need a connection.
2. **Read the reason.** Errors in RepoShelf name a reason (for example "size mismatch (incomplete or modified file)"). The
   update history also records the reason of failed installations.
3. **Retry an interrupted download.** "Retry" continues where it stopped. Incomplete downloads are cleaned up
   automatically after a week.
4. **App not detected after installing elsewhere:** see [Common problems](#12-common-problems).
5. **Reset RepoShelf:** Android Settings → Apps → RepoShelf → Storage → "Clear storage". This deletes all RepoShelf data:
   your list, settings, history and recorded signers. Export your list first. Installed apps are not affected.
6. **Uninstall RepoShelf:** apps you installed with it stay installed.
7. **Report a problem:** open an issue in the RepoShelf GitHub repository with your Android version, the RepoShelf
   version (Android Settings → Apps → RepoShelf) and the exact message. Do not include personal data.
   Report security vulnerabilities privately – see [SECURITY.md](SECURITY.md).
