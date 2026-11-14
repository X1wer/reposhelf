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

/**
 * Release-aláírás (C. 37., D-129): KIZÁRÓLAG környezeti változókból (CI-ban GitHub Secretsből). A kulcs és a jelszavak
 * soha nem kerülnek a repóba vagy a gradle.properties-be. Ha a változók hiányoznak, a release APK aláíratlan marad.
 */
val releaseKeystorePath: String? = providers.environmentVariable("REPOSHELF_KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() }

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
        val versionNameProperty = providers.gradleProperty("reposhelf.versionName").orNull ?: "0.0.0"
        require(versionNameProperty.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+([-.][A-Za-z0-9.]+)?$"))) { "Érvénytelen reposhelf.versionName" }
        versionName = versionNameProperty

        // A katalógus helye (docs/ARCHITECTURE.md 7.4., D-021). A repó még nincs publikálva:
        // amíg nem érhető el, az app a beépített (APK-ba csomagolt) katalógussal működik.
        buildConfigField(
            "String",
            "CATALOG_URL",
            "\"https://raw.githubusercontent.com/X1wer/reposhelf-catalog/main/dist/v1/catalog.json\"",
        )
        // Ennél régebbi katalógust a kliens nem fogad el (D-022). Legalább a beépített katalógus sorszáma.
        buildConfigField("long", "MIN_CATALOG_SERIAL", "3L")
        buildConfigField("String", "CATALOG_PUBLIC_KEYS", "\"$catalogKeys\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Terjesztési csatornák (C. 32., ARCH 16., D-042). A `play` változat v1-ben nincs.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
            // Saját frissítés (C. 14., D-062): a saját repó és a release-aláíró elvárt SHA-256 ujjlenyomata(i)
            // build-paraméter (gradle.properties). Üres ujjlenyomat esetén a futó app saját aláírója az elvárt.
            val selfRepo = providers.gradleProperty("reposhelf.selfUpdate.repo").orNull ?: ""
            val selfSigners = providers.gradleProperty("reposhelf.selfUpdate.signerSha256").orNull ?: ""
            require(selfRepo.isEmpty() || selfRepo.matches(Regex("^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$"))) { "Érvénytelen reposhelf.selfUpdate.repo" }
            require(selfSigners.isEmpty() || selfSigners.split(',').all { it.matches(Regex("^[0-9a-f]{64}$")) }) { "Érvénytelen reposhelf.selfUpdate.signerSha256" }
            buildConfigField("String", "SELF_UPDATE_REPO", "\"$selfRepo\"")
            buildConfigField("String", "SELF_UPDATE_SIGNERS", "\"$selfSigners\"")
        }
        create("fdroid") {
            dimension = "distribution"
            // Külön csomagnév: a két változat nem frissíti egymást (Obtainium-minta). Az F-Droid frissít, ezért saját
            // frissítés nincs: üres repó-paraméter = kikapcsolva, kérdés és hálózati kérés nélkül (D-042).
            applicationIdSuffix = ".fdroid"
            buildConfigField("String", "SELF_UPDATE_REPO", "\"\"")
            buildConfigField("String", "SELF_UPDATE_SIGNERS", "\"\"")
        }
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("REPOSHELF_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("REPOSHELF_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("REPOSHELF_KEY_PASSWORD").get()
                // minSdk 28: a v2/v3 séma elég; a v3 teszi lehetővé a későbbi kulcsrotációt.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-dev"
            // A debug buildet a fejlesztői debug-kulcs írja alá: a saját frissítésnél a futó példány aláírója az elvárt
            // (D-110), így a helyi szimuláció (D-096) működik. A release build a gradle.properties ujjlenyomatát várja.
            buildConfigField("String", "SELF_UPDATE_SIGNERS", "\"\"")
        }
        release {
            // Teszt-kulcsos próba-build: a verziónévből is látszik, hogy nem kiadható.
            if (testCatalogKeyIds.isNotEmpty() && allowTestCatalogKey) versionNameSuffix = "-testcatalog"
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Az app maga vált nyelvet (D-132): egy esetleges app bundle-ből a nyelvi erőforrások nem válhatnak külön.
    bundle {
        language {
            enableSplit = false
        }
    }

    // A Google-kulccsal titkosított függőség-metaadat nem kerül az APK-ba (F-Droid, reprodukálható build; C. 31.).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
// Minden release-változat (githubRelease, fdroidRelease, …) előfeltétele: pre<Változat>ReleaseBuild.
tasks.matching { it.name.startsWith("pre") && it.name.endsWith("ReleaseBuild") }.configureEach { dependsOn(checkReleaseCatalogKey) }

/**
 * A licencszövegek az APK-ba (D-138): a repó gyökerében lévő `LICENSE` és `THIRD_PARTY_NOTICES.md` (egyetlen forrás,
 * nincs másolat a repóban) generált assetként, `licenses/` alatt; a Beállítások „Névjegy” szakasza mutatja.
 */
abstract class CopyLicenseTexts : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val texts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().asFile.resolve("licenses")
        target.deleteRecursively()
        target.mkdirs()
        texts.files.forEach { it.copyTo(target.resolve(it.name), overwrite = true) }
    }
}
val copyLicenseTexts = tasks.register<CopyLicenseTexts>("copyLicenseTexts") {
    texts.from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY_NOTICES.md"))
}
androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(copyLicenseTexts, CopyLicenseTexts::outputDir) }
}

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

    // Műszeres tesztek (az átfedésvédelem a valódi rendszeren, D-127). Futtatás: lásd CLAUDE.md (am instrument, mert a
    // connectedDebugAndroidTest a végén eltávolítaná az appot az adataival együtt).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlin.test.junit)
}
