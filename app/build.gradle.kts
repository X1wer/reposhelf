// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// A katalógus-aláírás megbízható kulcsai (keyId:base64 SPKI, vesszővel); üres vagy hibás lista nem fogadható el.
val catalogKeys: String = providers.gradleProperty("reposhelf.catalog.publicKeys").orNull ?: ""
require(catalogKeys.isNotBlank() && catalogKeys.split(',').all { it.trim().matches(Regex("^[a-z0-9][a-z0-9._-]{1,63}:[A-Za-z0-9+/=]{40,400}$")) }) {
    "Érvénytelen vagy hiányzó reposhelf.catalog.publicKeys"
}

/** TESZT-kulcs a listában (az azonosítója tartalmazza a „test” szót): kiadható buildbe nem kerülhet. */
val testCatalogKeyIds: List<String> = catalogKeys.split(',').map { it.substringBefore(':').trim() }.filter { it.contains("test", ignoreCase = true) }

/** Csak teszteléshez (pl. release-szerű próba-build a valódi kulcs elkészülte előtt): -Preposhelf.allowTestCatalogKey=true */
val allowTestCatalogKey: Boolean = providers.gradleProperty("reposhelf.allowTestCatalogKey").orNull == "true"

android {
    // Ideiglenes azonosító (docs/DECISIONS.md D-002, Q-004): az első nyilvános kiadásig változtatható.
    namespace = "io.github.x1wer.reposhelf"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.x1wer.reposhelf"
        minSdk = 28
        targetSdk = 37
        // A versionCode felülírható (-Preposhelf.versionCode=N): a saját frissítés emulátoros szimulációjához két,
        // azonos kulccsal aláírt, eltérő verziókódú build kell (B. 6.5.). Alapérték: 1.
        versionCode = providers.gradleProperty("reposhelf.versionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("reposhelf.versionName").orNull ?: "0.1.0-dev"

        // A katalógus helye (docs/ARCHITECTURE.md 7.4., D-021). A repó még nincs publikálva:
        // amíg nem érhető el, az app a beépített (APK-ba csomagolt) katalógussal működik.
        buildConfigField(
            "String",
            "CATALOG_URL",
            "\"https://raw.githubusercontent.com/X1wer/reposhelf-catalog/main/dist/v1/catalog.json\"",
        )
        // Ennél régebbi katalógust a kliens nem fogad el (D-022). Legalább a beépített katalógus sorszáma.
        buildConfigField("long", "MIN_CATALOG_SERIAL", "1L")
        buildConfigField("String", "CATALOG_PUBLIC_KEYS", "\"$catalogKeys\"")
        // Saját frissítés (C. 14., D-062): a saját repó és a release-aláíró elvárt SHA-256 ujjlenyomata(i) build-paraméter
        // (gradle.properties). Üres ujjlenyomat esetén a futó app saját aláírója az elvárt (a telepített példányhoz
        // képest ellenőrzünk, ARCH 10.3.). A végleges release-kulcs a 9. munkamenetben (Q-007).
        val selfRepo = providers.gradleProperty("reposhelf.selfUpdate.repo").orNull ?: ""
        val selfSigners = providers.gradleProperty("reposhelf.selfUpdate.signerSha256").orNull ?: ""
        require(selfRepo.isEmpty() || selfRepo.matches(Regex("^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$"))) { "Érvénytelen reposhelf.selfUpdate.repo" }
        require(selfSigners.isEmpty() || selfSigners.split(',').all { it.matches(Regex("^[0-9a-f]{64}$")) }) { "Érvénytelen reposhelf.selfUpdate.signerSha256" }
        buildConfigField("String", "SELF_UPDATE_REPO", "\"$selfRepo\"")
        buildConfigField("String", "SELF_UPDATE_SIGNERS", "\"$selfSigners\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Teszt-kulcsos próba-build: a verziónévből is látszik, hogy nem kiadható.
            if (testCatalogKeyIds.isNotEmpty() && allowTestCatalogKey) versionNameSuffix = "-testcatalog"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // A tiszta JVM-modulokat is az app minSdk-jával vizsgálja (NewApi: pl. Java 11+ API, ami API 28-on
        // hiányzik; D-070). Az ilyen hibánál a lint megáll.
        checkDependencies = true
        abortOnError = true
        error += "NewApi"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

/**
 * Kiadási kapu (biztonsági audit, CAT-01): release build NEM készülhet a katalógus teszt-kulcsával. A teszt-kulcs privát
 * része egy fejlesztői gépen van, így egy vele kiadott kliens bármilyen, azzal aláírt katalógust elfogadna.
 */
val checkReleaseCatalogKey = tasks.register("checkReleaseCatalogKey") {
    val testKeys = testCatalogKeyIds
    val allowed = allowTestCatalogKey
    doLast {
        if (testKeys.isNotEmpty() && !allowed) {
            throw GradleException(
                "A reposhelf.catalog.publicKeys teszt-kulcsot tartalmaz ($testKeys): release build így nem készíthető. " +
                    "Add meg a valódi katalóguskulcsot, vagy kizárólag teszteléshez: -Preposhelf.allowTestCatalogKey=true",
            )
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseCatalogKey) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:catalog"))
    implementation(project(":core:network"))
    implementation(project(":core:source"))
    implementation(project(":core:data"))
    implementation(project(":core:apk"))
    implementation(project(":core:installer"))
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
}
