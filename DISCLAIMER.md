<!-- SPDX-FileCopyrightText: 2026 RepoShelf contributors -->
<!-- SPDX-License-Identifier: CC-BY-4.0 -->

# Disclaimer and terms of use

*Magyar változat: [DISCLAIMER.hu.md](DISCLAIMER.hu.md)*

Last updated: 2026-11-14.

> This text has not been reviewed by a lawyer. Having it reviewed by a legal professional is recommended before the first
> public release, in particular for consumer-protection rules in the countries where RepoShelf is distributed.

## Third-party apps

RepoShelf helps you find, install and update Android apps published on GitHub. Please be aware that:

1. **The apps are made by independent developers.** They are not developed, reviewed, endorsed or distributed by the
   RepoShelf project. Each app is governed by its own license and terms, set by its developer.
2. **The catalog's maintainers do not guarantee** that any app is free of bugs, works as described, is suitable for any
   purpose, or will remain available or maintained.
3. **Being available on GitHub does not prove that an app is safe.** Anyone can publish code and releases on GitHub.
   Being listed in the RepoShelf catalog is not a security certification either: the catalog only records which
   repository an app comes from and, for some apps, its expected package name and signing key.
4. **You can always check the source.** Every app page shows the source repository, its license (as reported by GitHub)
   and the signer certificate's fingerprint, and links to the repository, so you can review the code, the releases and the
   developer before installing.
5. **RepoShelf's technical checks are not a full security audit.** RepoShelf checks the integrity, structure, package
   name, version and signature of a downloaded APK, and whether its signer matches the one it already knows. These checks
   can detect corrupted files, unexpected signer changes and several known attack patterns. They cannot tell whether the
   code of an app is malicious, collects your data, or contains vulnerabilities. An app signed with the developer's genuine
   key can still be harmful – for example if the developer or their key was compromised.

Install only apps you trust. You decide what you install; Android asks for your confirmation for every first
installation.

## How RepoShelf handles third-party files

- APK files are downloaded **directly from the developer's GitHub Releases**. The RepoShelf catalog does not store or
  mirror third-party APK files.
- RepoShelf **does not repackage, modify or re-sign** third-party APK files. It passes the checked file unchanged to
  Android's installer.
- RepoShelf does not build apps from source code.

These measures and the checks described in [SECURITY.md](SECURITY.md) are RepoShelf's actual protections. This disclaimer
does not replace them; it describes the limits that remain.

## No warranty for RepoShelf itself

RepoShelf is free software, distributed under the GNU General Public License, version 3 or later. As stated in sections 15
and 16 of that license: **to the extent permitted by applicable law**, RepoShelf is provided "as is", without warranty of
any kind, and the copyright holders and contributors are not liable for damages arising from its use. See
[LICENSE](LICENSE) for the exact terms.

**Nothing in this disclaimer limits or excludes any liability that cannot be limited or excluded under applicable law**
– for example, depending on the jurisdiction, liability for damage caused intentionally or by gross negligence, for harm
to life, body or health, or rights that consumers have under mandatory consumer-protection law.

## Trademarks and affiliation

RepoShelf is an independent project. It is not affiliated with, sponsored or endorsed by GitHub, Inc., Google LLC or the
developers of the listed apps. "GitHub" and "Android" are trademarks of their respective owners. App names and icons
belong to their owners and are shown only to identify the apps.

## Reporting problems with a listed app

If an app in the catalog appears to be malicious, infringing or abandoned, report it in the catalog repository (see its
submission guide). Security vulnerabilities in RepoShelf itself should be reported privately as described in
[SECURITY.md](SECURITY.md).
