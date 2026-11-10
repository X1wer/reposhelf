// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(false)
    }
}

dependencies {
    api(project(":core:model"))
    // Csak tesztben: a JVM-es ApkInspector-implementáció és a teszt-APK-k (testFixtures; D-031). Más modulok
    // tesztjei is használják: testImplementation(testFixtures(project(":core:apk"))).
    testFixturesImplementation(libs.apksig)
    testImplementation(libs.apksig)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
