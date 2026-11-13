// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // A Gradle Plugin Portal csak a Kotlin- és a KSP-plugin jelölőire és moduljaira (SUP-02): minden más függőség a
        // Google Mavenből vagy a Maven Centralból jön, így egy portálra feltöltött, azonos nevű csomag nem kerülhet be.
        gradlePluginPortal {
            content {
                includeGroupByRegex("org\\.jetbrains\\.kotlin.*")
                includeGroup("com.google.devtools.ksp")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "RepoShelf"

// Modulszerkezet: docs/ARCHITECTURE.md 2. pont (D-010).
include(
    ":app",
    ":core:model",
    ":core:network",
    ":core:catalog",
    ":core:source",
    ":core:apk",
    ":core:update",
    ":core:data",
    ":core:installer",
)
