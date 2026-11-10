// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// PackageInstaller, PackageManager és a platform-alapú ApkInspector (docs/ARCHITECTURE.md 2., 11. pont).
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
    api(project(":core:apk"))
    api(project(":core:network"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
