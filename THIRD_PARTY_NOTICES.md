<!-- SPDX-FileCopyrightText: 2026 RepoShelf contributors -->
<!-- SPDX-License-Identifier: CC-BY-4.0 -->

# Third-party notices

RepoShelf is licensed under the GNU General Public License v3.0 or later (see [LICENSE](LICENSE)). The RepoShelf APK also
contains the third-party components listed below. Each component remains under its own license. All of them are
compatible with GPL-3.0-or-later.

This file covers what is **packaged into the app** (the `github` and `fdroid` release builds have the same runtime
components). Build tools, Gradle plugins and test libraries are not distributed with the app and are not listed here; they
are pinned with checksums in `gradle/verification-metadata.xml`.

## Summary

| License | What it covers | Full text |
|---|---|---|
| Apache License 2.0 | AndroidX / Jetpack Compose, Kotlin standard library and kotlinx libraries, OkHttp, Okio, Coil, Accompanist, JSpecify, JetBrains annotations, Guava `listenablefuture` | [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt) |
| BSD 2-Clause | commonmark-java (Markdown parser for README display) | reproduced below |
| BSD 3-Clause | Protocol Buffers runtime repackaged in AndroidX DataStore (`datastore-preferences-external-protobuf`) | reproduced below |
| Mozilla Public License 2.0 | Public Suffix List data file bundled by OkHttp (`assets/PublicSuffixDatabase.list`) | see below |
| CC0 1.0 | the built-in starter catalog (`assets/catalog/catalog-v1.json`) from the RepoShelf catalog repository | [LICENSES/CC0-1.0.txt](LICENSES/CC0-1.0.txt) |

None of the Apache-2.0 components ships a `NOTICE` file (checked in the published artifacts of every component listed
below), so there is no additional attribution text to reproduce for them.

## Notices that must be reproduced

### commonmark-java (BSD 2-Clause)

`org.commonmark:commonmark`, `org.commonmark:commonmark-ext-gfm-tables` – <https://github.com/commonmark/commonmark-java>

```
Copyright (c) 2015, Atlassian Pty Ltd
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### Protocol Buffers (BSD 3-Clause), as repackaged by AndroidX DataStore

`androidx.datastore:datastore-preferences-external-protobuf` – <https://github.com/protocolbuffers/protobuf>

The upstream Protocol Buffers project is Copyright 2008 Google Inc. The license file shipped in the AndroidX artifact reads:

```
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of the copyright holder nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### Public Suffix List (MPL 2.0)

OkHttp (`com.squareup.okhttp3:okhttp-android`) bundles a compact copy of the Public Suffix List maintained by the
Mozilla Foundation and contributors, as the file `assets/PublicSuffixDatabase.list` in the APK. The list is used to
check host names and cookies. This data is subject to the terms of the Mozilla Public License, v. 2.0; a copy of the
license is available at <https://mozilla.org/MPL/2.0/>. The source form of the list is published at
<https://publicsuffix.org/list/>. RepoShelf does not modify this file.

### RepoShelf catalog data (CC0 1.0)

The built-in starter catalog is a byte-identical copy of `dist/v1/catalog.json` from the RepoShelf catalog repository and
is dedicated to the public domain under CC0 1.0. It contains only names, repository paths, keywords and categories of the
listed apps; the apps themselves are not part of RepoShelf.

## Component list

The list below is generated from the dependency graph of the `githubRelease` runtime classpath and the license metadata
(POM files) of each resolved component. Regenerate it after any dependency change – see [CONTRIBUTING.md](CONTRIBUTING.md).
The tool refuses components whose license is missing or not on the reviewed list.

<!-- BEGIN GENERATED COMPONENT LIST (tools/third_party_notices.py) -->

191 components (Apache-2.0: 188, BSD-2-Clause: 2, BSD-3-Clause: 1).

| Component (Maven coordinates) | Version | License | Project |
|---|---|---|---|
| `androidx.activity:activity-compose` | 1.13.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/activity#1.13.0> |
| `androidx.activity:activity-ktx` | 1.13.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/activity#1.13.0> |
| `androidx.activity:activity` | 1.13.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/activity#1.13.0> |
| `androidx.annotation:annotation-experimental` | 1.5.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/annotation#1.5.0> |
| `androidx.annotation:annotation-jvm` | 1.10.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/annotation#1.10.0> |
| `androidx.annotation:annotation` | 1.10.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/annotation#1.10.0> |
| `androidx.appcompat:appcompat-resources` | 1.7.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/appcompat#1.7.1> |
| `androidx.arch.core:core-common` | 2.2.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/arch-core#2.2.0> |
| `androidx.arch.core:core-runtime` | 2.2.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/arch-core#2.2.0> |
| `androidx.autofill:autofill` | 1.0.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| `androidx.collection:collection-jvm` | 1.5.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/collection#1.5.0> |
| `androidx.collection:collection-ktx` | 1.5.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/collection#1.5.0> |
| `androidx.collection:collection` | 1.5.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/collection#1.5.0> |
| `androidx.compose.animation:animation-android` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-animation#1.12.1> |
| `androidx.compose.animation:animation-core-android` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-animation#1.12.1> |
| `androidx.compose.animation:animation-core` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-animation#1.12.1> |
| `androidx.compose.animation:animation` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-animation#1.12.1> |
| `androidx.compose.foundation:foundation-android` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.12.1> |
| `androidx.compose.foundation:foundation-layout-android` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.12.1> |
| `androidx.compose.foundation:foundation-layout` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.12.1> |
| `androidx.compose.foundation:foundation` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.12.1> |
| `androidx.compose.material3:material3-android` | 1.4.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-material3#1.4.0> |
| `androidx.compose.material3:material3` | 1.4.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-material3#1.4.0> |
| `androidx.compose.material:material-icons-core-android` | 1.7.8 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-material#1.7.8> |
| `androidx.compose.material:material-icons-core` | 1.7.8 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-material#1.7.8> |
| `androidx.compose.material:material-ripple-android` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-material#1.12.1> |
| `androidx.compose.material:material-ripple` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-material#1.12.1> |
| `androidx.compose.runtime:runtime-android` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.12.1> |
| `androidx.compose.runtime:runtime-annotation-android` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.12.1> |
| `androidx.compose.runtime:runtime-annotation` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.12.1> |
| `androidx.compose.runtime:runtime-retain-android` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.12.1> |
| `androidx.compose.runtime:runtime-retain` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.12.1> |
| `androidx.compose.runtime:runtime-saveable-android` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.12.1> |
| `androidx.compose.runtime:runtime-saveable` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.12.1> |
| `androidx.compose.runtime:runtime` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.12.1> |
| `androidx.compose.ui:ui-android` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-ui#1.12.1> |
| `androidx.compose.ui:ui-geometry-android` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-ui#1.12.1> |
| `androidx.compose.ui:ui-geometry` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-ui#1.12.1> |
| `androidx.compose.ui:ui-graphics-android` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-ui#1.12.1> |
| `androidx.compose.ui:ui-graphics` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-ui#1.12.1> |
| `androidx.compose.ui:ui-text-android` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-ui#1.12.1> |
| `androidx.compose.ui:ui-text` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-ui#1.12.1> |
| `androidx.compose.ui:ui-tooling-preview-android` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-ui#1.12.1> |
| `androidx.compose.ui:ui-tooling-preview` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-ui#1.12.1> |
| `androidx.compose.ui:ui-unit-android` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-ui#1.12.1> |
| `androidx.compose.ui:ui-unit` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-ui#1.12.1> |
| `androidx.compose.ui:ui-util-android` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-ui#1.12.1> |
| `androidx.compose.ui:ui-util` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-ui#1.12.1> |
| `androidx.compose.ui:ui` | 1.12.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/compose-ui#1.12.1> |
| `androidx.compose:compose-bom` | 2026.09.00 | Apache-2.0 | <https://developer.android.com/jetpack> |
| `androidx.concurrent:concurrent-futures-ktx` | 1.1.0 | Apache-2.0 | <https://developer.android.com/topic/libraries/architecture/index.html> |
| `androidx.concurrent:concurrent-futures` | 1.1.0 | Apache-2.0 | <https://developer.android.com/topic/libraries/architecture/index.html> |
| `androidx.core:core-ktx` | 1.19.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/core#1.19.1> |
| `androidx.core:core-viewtree` | 1.0.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/core#1.0.0> |
| `androidx.core:core` | 1.19.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/core#1.19.1> |
| `androidx.customview:customview-poolingcontainer` | 1.0.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/customview#1.0.0> |
| `androidx.datastore:datastore-android` | 1.2.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1> |
| `androidx.datastore:datastore-core-android` | 1.2.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1> |
| `androidx.datastore:datastore-core-okio-jvm` | 1.2.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1> |
| `androidx.datastore:datastore-core-okio` | 1.2.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1> |
| `androidx.datastore:datastore-core` | 1.2.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1> |
| `androidx.datastore:datastore-preferences-android` | 1.2.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1> |
| `androidx.datastore:datastore-preferences-core-android` | 1.2.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1> |
| `androidx.datastore:datastore-preferences-core` | 1.2.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1> |
| `androidx.datastore:datastore-preferences-external-protobuf` | 1.2.1 | BSD-3-Clause | <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1> |
| `androidx.datastore:datastore-preferences-proto` | 1.2.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1> |
| `androidx.datastore:datastore-preferences` | 1.2.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1> |
| `androidx.datastore:datastore` | 1.2.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/datastore#1.2.1> |
| `androidx.emoji2:emoji2` | 1.4.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/emoji2#1.4.0> |
| `androidx.exifinterface:exifinterface` | 1.4.2 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/exifinterface#1.4.2> |
| `androidx.graphics:graphics-path` | 1.0.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/graphics#1.0.1> |
| `androidx.interpolator:interpolator` | 1.0.0 | Apache-2.0 | http://developer.android.com/tools/extras/support-library.html |
| `androidx.lifecycle:lifecycle-common-java8` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-common-jvm` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-common` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-livedata-core-ktx` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-livedata-core` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-livedata` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-process` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-runtime-android` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-runtime-compose-android` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-runtime-ktx-android` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-runtime` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-service` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-viewmodel-android` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-viewmodel-compose-android` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate-android` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.lifecycle:lifecycle-viewmodel` | 2.11.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/lifecycle#2.11.0> |
| `androidx.navigation:navigation-common-android` | 2.10.2 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/navigation#2.10.2> |
| `androidx.navigation:navigation-common` | 2.10.2 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/navigation#2.10.2> |
| `androidx.navigation:navigation-compose-android` | 2.10.2 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/navigation#2.10.2> |
| `androidx.navigation:navigation-compose` | 2.10.2 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/navigation#2.10.2> |
| `androidx.navigation:navigation-runtime-android` | 2.10.2 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/navigation#2.10.2> |
| `androidx.navigation:navigation-runtime` | 2.10.2 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/navigation#2.10.2> |
| `androidx.navigationevent:navigationevent-android` | 1.1.2 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/navigationevent#1.1.2> |
| `androidx.navigationevent:navigationevent-compose-android` | 1.1.2 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/navigationevent#1.1.2> |
| `androidx.navigationevent:navigationevent-compose` | 1.1.2 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/navigationevent#1.1.2> |
| `androidx.navigationevent:navigationevent` | 1.1.2 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/navigationevent#1.1.2> |
| `androidx.profileinstaller:profileinstaller` | 1.4.1 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/profileinstaller#1.4.1> |
| `androidx.room:room-common-jvm` | 2.8.5 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/room#2.8.5> |
| `androidx.room:room-common` | 2.8.5 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/room#2.8.5> |
| `androidx.room:room-runtime-android` | 2.8.5 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/room#2.8.5> |
| `androidx.room:room-runtime` | 2.8.5 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/room#2.8.5> |
| `androidx.savedstate:savedstate-android` | 1.5.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/savedstate#1.5.0> |
| `androidx.savedstate:savedstate-compose-android` | 1.5.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/savedstate#1.5.0> |
| `androidx.savedstate:savedstate-compose` | 1.5.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/savedstate#1.5.0> |
| `androidx.savedstate:savedstate-ktx` | 1.5.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/savedstate#1.5.0> |
| `androidx.savedstate:savedstate` | 1.5.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/savedstate#1.5.0> |
| `androidx.sqlite:sqlite-android` | 2.6.2 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/sqlite#2.6.2> |
| `androidx.sqlite:sqlite-framework-android` | 2.6.2 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/sqlite#2.6.2> |
| `androidx.sqlite:sqlite-framework` | 2.6.2 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/sqlite#2.6.2> |
| `androidx.sqlite:sqlite` | 2.6.2 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/sqlite#2.6.2> |
| `androidx.startup:startup-runtime` | 1.2.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/startup#1.2.0> |
| `androidx.tracing:tracing-ktx` | 1.2.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/tracing#1.2.0> |
| `androidx.tracing:tracing` | 1.2.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/tracing#1.2.0> |
| `androidx.vectordrawable:vectordrawable-animated` | 1.1.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| `androidx.vectordrawable:vectordrawable` | 1.1.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx> |
| `androidx.versionedparcelable:versionedparcelable` | 1.1.1 | Apache-2.0 | http://developer.android.com/tools/extras/support-library.html |
| `androidx.window:window-core-android` | 1.5.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/window#1.5.0> |
| `androidx.window:window-core` | 1.5.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/window#1.5.0> |
| `androidx.window:window` | 1.5.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/window#1.5.0> |
| `androidx.work:work-runtime-ktx` | 2.12.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/work#2.12.0> |
| `androidx.work:work-runtime` | 2.12.0 | Apache-2.0 | <https://developer.android.com/jetpack/androidx/releases/work#2.12.0> |
| `com.google.accompanist:accompanist-drawablepainter` | 0.37.3 | Apache-2.0 | <https://github.com/google/accompanist/> |
| `com.google.guava:listenablefuture` | 1.0 | Apache-2.0 | <https://github.com/google/guava> |
| `com.squareup.okhttp3:okhttp-android` | 5.5.1 | Apache-2.0 | <https://lysine.dev/okhttp/> |
| `com.squareup.okhttp3:okhttp-coroutines` | 5.5.1 | Apache-2.0 | <https://lysine.dev/okhttp/> |
| `com.squareup.okhttp3:okhttp` | 5.5.1 | Apache-2.0 | <https://lysine.dev/okhttp/> |
| `com.squareup.okio:okio-jvm` | 3.18.1 | Apache-2.0 | <https://github.com/lysine-dev/okio/> |
| `com.squareup.okio:okio` | 3.18.1 | Apache-2.0 | <https://github.com/lysine-dev/okio/> |
| `io.coil-kt.coil3:coil-android` | 3.6.3 | Apache-2.0 | <https://github.com/coil-kt/coil> |
| `io.coil-kt.coil3:coil-compose-android` | 3.6.3 | Apache-2.0 | <https://github.com/coil-kt/coil> |
| `io.coil-kt.coil3:coil-compose-core-android` | 3.6.3 | Apache-2.0 | <https://github.com/coil-kt/coil> |
| `io.coil-kt.coil3:coil-compose-core` | 3.6.3 | Apache-2.0 | <https://github.com/coil-kt/coil> |
| `io.coil-kt.coil3:coil-compose` | 3.6.3 | Apache-2.0 | <https://github.com/coil-kt/coil> |
| `io.coil-kt.coil3:coil-core-android` | 3.6.3 | Apache-2.0 | <https://github.com/coil-kt/coil> |
| `io.coil-kt.coil3:coil-core` | 3.6.3 | Apache-2.0 | <https://github.com/coil-kt/coil> |
| `io.coil-kt.coil3:coil-network-core-android` | 3.6.3 | Apache-2.0 | <https://github.com/coil-kt/coil> |
| `io.coil-kt.coil3:coil-network-core` | 3.6.3 | Apache-2.0 | <https://github.com/coil-kt/coil> |
| `io.coil-kt.coil3:coil-network-okhttp-android` | 3.6.3 | Apache-2.0 | <https://github.com/coil-kt/coil> |
| `io.coil-kt.coil3:coil-network-okhttp` | 3.6.3 | Apache-2.0 | <https://github.com/coil-kt/coil> |
| `io.coil-kt.coil3:coil` | 3.6.3 | Apache-2.0 | <https://github.com/coil-kt/coil> |
| `org.commonmark:commonmark-ext-gfm-tables` | 0.32.0 | BSD-2-Clause | <https://github.com/commonmark/commonmark-java> |
| `org.commonmark:commonmark` | 0.32.0 | BSD-2-Clause | <https://github.com/commonmark/commonmark-java> |
| `org.jetbrains.androidx.lifecycle:lifecycle-common` | 2.9.6 | Apache-2.0 | <https://github.com/JetBrains/compose-jb> |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose` | 2.9.6 | Apache-2.0 | <https://github.com/JetBrains/compose-jb> |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime` | 2.9.6 | Apache-2.0 | <https://github.com/JetBrains/compose-jb> |
| `org.jetbrains.androidx.savedstate:savedstate-compose` | 1.3.6 | Apache-2.0 | <https://github.com/JetBrains/compose-jb> |
| `org.jetbrains.androidx.savedstate:savedstate` | 1.3.6 | Apache-2.0 | <https://github.com/JetBrains/compose-jb> |
| `org.jetbrains.compose.animation:animation-android` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.animation:animation-core-android` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.animation:animation-core` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.animation:animation` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.foundation:foundation-android` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.foundation:foundation-layout-android` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.foundation:foundation-layout` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.foundation:foundation` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.runtime:runtime-android` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.runtime:runtime-saveable-android` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.runtime:runtime-saveable` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.runtime:runtime` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.ui:ui-android` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.ui:ui-geometry-android` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.ui:ui-geometry` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.ui:ui-graphics-android` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.ui:ui-graphics` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.ui:ui-text-android` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.ui:ui-text` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.ui:ui-unit-android` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.ui:ui-unit` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.ui:ui-util-android` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.ui:ui-util` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.compose.ui:ui` | 1.12.0 | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| `org.jetbrains.kotlin:kotlin-stdlib-common` | 2.4.21 | Apache-2.0 | <https://kotlinlang.org/> |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.21 | Apache-2.0 | <https://kotlinlang.org/> |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 | Apache-2.0 | <https://github.com/Kotlin/kotlinx.coroutines> |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom` | 1.11.0 | Apache-2.0 | <https://github.com/Kotlin/kotlinx.coroutines> |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.11.0 | Apache-2.0 | <https://github.com/Kotlin/kotlinx.coroutines> |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | Apache-2.0 | <https://github.com/Kotlin/kotlinx.coroutines> |
| `org.jetbrains.kotlinx:kotlinx-serialization-bom` | 1.12.0 | Apache-2.0 | <https://github.com/Kotlin/kotlinx.serialization> |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | 1.12.0 | Apache-2.0 | <https://github.com/Kotlin/kotlinx.serialization> |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | 1.12.0 | Apache-2.0 | <https://github.com/Kotlin/kotlinx.serialization> |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm` | 1.12.0 | Apache-2.0 | <https://github.com/Kotlin/kotlinx.serialization> |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.12.0 | Apache-2.0 | <https://github.com/Kotlin/kotlinx.serialization> |
| `org.jetbrains:annotations` | 23.0.0 | Apache-2.0 | <https://github.com/JetBrains/java-annotations> |
| `org.jspecify:jspecify` | 1.0.0 | Apache-2.0 | http://jspecify.org/ |

<!-- END GENERATED COMPONENT LIST -->

## Third-party apps

Apps that you find or install with RepoShelf are **not** part of RepoShelf and are not covered by this file. They are
developed by independent developers under their own licenses; the app page shows the license reported by each repository.
See [DISCLAIMER.md](DISCLAIMER.md).
