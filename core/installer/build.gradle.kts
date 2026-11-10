// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// PackageInstaller, PackageManager és a platform-alapú ApkInspector helye (3–4. munkamenet).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.x1wer.reposhelf.core.installer"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:apk"))
}
