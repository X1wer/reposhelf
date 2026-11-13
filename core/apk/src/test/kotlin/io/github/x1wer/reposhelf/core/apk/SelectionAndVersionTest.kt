// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.apk

import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.ReleaseAsset
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionAndVersionTest {

    private var nextId = 1L
    private fun asset(name: String, size: Long = 1_000) =
        ReleaseAsset(nextId++, name, null, null, size, "https://github.com/o/r/releases/download/v1/$name", null, "uploaded")

    private val x86Emulator = listOf("x86_64", "arm64-v8a")
    private val armPhone = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private val armV7Phone = listOf("armeabi-v7a", "armeabi")

    // Valódi állománynevek az Obtainium v1.7.4 kiadásából (2026-11-09).
    private val obtainium = listOf(
        "app-arm64-v8a-fdroid-release.apk", "app-arm64-v8a-release.apk", "app-armeabi-v7a-fdroid-release.apk",
        "app-armeabi-v7a-release.apk", "app-fdroid-release.apk", "app-release.apk", "app-x86_64-fdroid-release.apk",
        "app-x86_64-release.apk",
    ).map { asset(it) }
    private val obtainiumFilter = "^(?!.*fdroid).*\\.apk$"

    private fun single(selection: AssetSelection): String = assertIs<AssetSelection.Single>(selection).asset.name

    @Test
    fun abiDetection() {
        assertEquals(AbiTag.Specific(setOf("arm64-v8a")), AbiDetector.detect("app-arm64-v8a-release.apk"))
        assertEquals(AbiTag.Specific(setOf("x86_64")), AbiDetector.detect("breezy-weather-x86_64-v6.2.2_standard.apk"))
        assertEquals(AbiTag.Specific(setOf("x86")), AbiDetector.detect("gallery-43-foss-x86-release.apk"))
        assertEquals(AbiTag.Specific(setOf("armeabi-v7a")), AbiDetector.detect("Seal-2.0.0-armeabi-v7a-release.apk"))
        assertEquals(AbiTag.Specific(setOf("arm64-v8a")), AbiDetector.detect("split_config.arm64_v8a.apk"))
        assertEquals(AbiTag.Universal, AbiDetector.detect("Seal-2.0.0-githubPreview-universal-release.apk"))
        assertEquals(AbiTag.Unknown, AbiDetector.detect("NewPipe_v0.29.2.apk"))
        assertEquals(AbiTag.Unknown, AbiDetector.detect("aegis-v3.4.3.apk"))
    }

    @Test
    fun obtainium_picksDeviceSpecificNonFdroidBuild() {
        assertEquals("app-x86_64-release.apk", single(AssetSelector.select(obtainium, x86Emulator, obtainiumFilter)))
        assertEquals("app-arm64-v8a-release.apk", single(AssetSelector.select(obtainium, armPhone, obtainiumFilter)))
        assertEquals("app-armeabi-v7a-release.apk", single(AssetSelector.select(obtainium, armV7Phone, obtainiumFilter)))
        // 32 bites x86-on nincs ABI-specifikus build → az ABI-jelölés nélküli (univerzális) változat.
        assertEquals("app-release.apk", single(AssetSelector.select(obtainium, listOf("x86"), obtainiumFilter)))
    }

    @Test
    fun withoutFilter_variantsMakeTheChoiceAmbiguous() {
        val selection = assertIs<AssetSelection.Ambiguous>(AssetSelector.select(obtainium, listOf("x86"), null))
        assertEquals(setOf("app-fdroid-release.apk", "app-release.apk"), selection.candidates.map { it.name }.toSet())
    }

    @Test
    fun fossifyGallery_prefersPrimaryAbi_thenUniversal() {
        val gallery = listOf(
            "gallery-40-foss-release.apk", "gallery-41-foss-armeabi-v7a-release.apk",
            "gallery-42-foss-arm64-v8a-release.apk", "gallery-43-foss-x86-release.apk",
        ).map { asset(it) }
        assertEquals("gallery-42-foss-arm64-v8a-release.apk", single(AssetSelector.select(gallery, armPhone, null)))
        // Emulátoron (x86_64, arm64 fordítással) az univerzális build jobb, mint a fordított arm64.
        assertEquals("gallery-40-foss-release.apk", single(AssetSelector.select(gallery, x86Emulator, null)))
    }

    @Test
    fun singleApk_withUnusualName() {
        assertEquals("NewPipe_v0.29.2.apk", single(AssetSelector.select(listOf(asset("NewPipe_v0.29.2.apk"), asset("source.zip")), armPhone, null)))
        assertEquals("MyApp.APK", single(AssetSelector.select(listOf(asset("MyApp.APK")), armPhone, null)))
    }

    @Test
    fun bundlesAndArchives_areRejectedSafely() {
        val selection = assertIs<AssetSelection.NoInstallable>(
            AssetSelector.select(listOf(asset("app-release.aab"), asset("app.apks"), asset("app.xapk"), asset("checksums.txt")), armPhone, null),
        )
        assertEquals(listOf(AssetRejection.APP_BUNDLE, AssetRejection.SPLIT_ARCHIVE, AssetRejection.SPLIT_ARCHIVE), selection.rejected.map { it.reason })
    }

    @Test
    fun incompatibleAbiOnly_isNotInstallable() {
        val selection = assertIs<AssetSelection.NoInstallable>(AssetSelector.select(listOf(asset("app-x86_64-release.apk")), armPhone, null))
        assertEquals(AssetRejection.INCOMPATIBLE_ABI, selection.rejected.single().reason)
    }

    @Test
    fun filterMatchingNothing_letsTheUserChoose() {
        val selection = assertIs<AssetSelection.Ambiguous>(AssetSelector.select(listOf(asset("a.apk"), asset("b.apk")), armPhone, "^nomatch$"))
        assertTrue(selection.filterMismatch)
    }

    @Test
    fun splitSet_includesOnlyMatchingAbiSplit() {
        val assets = listOf("base.apk", "split_config.arm64_v8a.apk", "split_config.x86_64.apk", "split_config.hu.apk").map { asset(it) }
        val set = assertIs<AssetSelection.SplitSet>(AssetSelector.select(assets, x86Emulator, null))
        assertEquals("base.apk", set.base.name)
        assertEquals(listOf("split_config.x86_64.apk", "split_config.hu.apk"), set.splits.map { it.name })
    }

    @Test
    fun versionNames_compareNumericallyAndHandlePrereleases() {
        assertTrue(VersionNames.compare("v1.2.3", "1.2.4")!! < 0)
        assertTrue(VersionNames.compare("1.10", "1.9")!! > 0)
        assertEquals(0, VersionNames.compare("v2", "2.0.0"))
        assertTrue(VersionNames.compare("1.0.0-beta.2", "1.0.0")!! < 0)
        assertTrue(VersionNames.compare("1.0.0-rc1", "1.0.0-beta3")!! > 0)
        assertTrue(VersionNames.compare("1.0.0-beta.10", "1.0.0-beta.9")!! > 0)
        assertTrue(VersionNames.compare("2026.09.29-32-android", "2026.09.15-1-android")!! > 0)
        assertTrue(VersionNames.compare("release-1.4", "1.3")!! > 0)
        assertNull(VersionNames.compare("nightly", "1.0"))
    }

    private fun release(id: Long, tag: String, pre: Boolean = false, draft: Boolean = false, day: Int, apk: Boolean = true) = Release(
        id = id, tagName = tag, name = tag, isPrerelease = pre, isDraft = draft, createdAt = null,
        publishedAt = Instant.parse("2026-11-%02dT10:00:00Z".format(day)), body = null, htmlUrl = null, isImmutable = null,
        assets = if (apk) listOf(asset("app-$tag.apk")) else listOf(asset("source.zip")),
    )

    @Test
    fun releaseResolver_picksLatestStableAndNewerPrerelease() {
        val resolved = ReleaseResolver.resolve(
            listOf(
                release(5, "v3.0-draft", draft = true, day = 9),
                release(4, "v2.1-beta", pre = true, day = 8),
                release(3, "v2.0", day = 5, apk = false),
                release(2, "v1.9", day = 3),
                release(1, "v1.8-beta", pre = true, day = 1),
            ),
        )
        assertEquals("v1.9", resolved.latestStable?.tagName)
        assertEquals("v2.1-beta", resolved.latestPrerelease?.tagName)
        assertTrue(resolved.hasAnyRelease)
    }

    @Test
    fun releaseResolver_noApkAnywhere() {
        val resolved = ReleaseResolver.resolve(listOf(release(1, "v1", day = 1, apk = false)))
        assertNull(resolved.latestStable)
        assertNull(resolved.latestPrerelease)
        assertTrue(resolved.hasAnyRelease)
        assertTrue(!ReleaseResolver.resolve(emptyList()).hasAnyRelease)
    }

    @Test
    fun preliminaryCompatibility_isAnEstimate() {
        val rel = release(1, "v1", pre = true, day = 1)
        val checks = PreliminaryCompatibility.assess(
            AssetSelection.Single(asset("app-arm64-v8a.apk", size = 10_000_000)), rel,
            DeviceProfile(37, armPhone, freeBytes = 15_000_000),
        ).associateBy { it.check }
        assertEquals(CheckStatus.OK, checks.getValue(PreCheck.ABI).status)
        assertEquals(CheckStatus.WARNING, checks.getValue(PreCheck.STORAGE).status)
        assertEquals(CheckStatus.NOT_CHECKED, checks.getValue(PreCheck.ANDROID_VERSION).status, "a minSdk csak letöltés után ismert")
        assertEquals(CheckStatus.WARNING, checks.getValue(PreCheck.RELEASE_TYPE).status)
    }

    // ---- CAT-02: a nem megengedett (visszalépéses robbanást adó) szűrő soha nem fut le az állományneveken ----

    @Test
    fun unsafeFilter_isNeverEvaluated_andTheUserChooses() {
        val hostile = "app-".repeat(60) + "x.apk"
        val started = System.nanoTime()
        val selection = assertIs<AssetSelection.Ambiguous>(AssetSelector.select(listOf(asset(hostile), asset("b.apk")), armPhone, "^(app-?)+release\\.apk$"))
        assertTrue(selection.filterMismatch)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "a szűrő nem futhatott le")
        // A megengedett szűrő változatlanul működik.
        val picked = assertIs<AssetSelection.Single>(AssetSelector.select(listOf(asset("app-fdroid.apk"), asset("app-release.apk")), armPhone, "^(?!.*fdroid).*\\.apk$"))
        assertEquals("app-release.apk", picked.asset.name)
    }

    // ---- D-128 (Q-011): a split-készlet telepíthető, de előzetesen jelezve; az önálló APK elsőbbséget kap ----

    @Test
    fun splitSet_isSelectedWithAWarning_andAStandaloneApkIsPreferred() {
        assertEquals(true, AssetSelector.SPLIT_SETS_SUPPORTED)
        val splitOnly = listOf("base.apk", "split_config.arm64_v8a.apk", "split_config.x86_64.apk", "split_config.hu.apk").map { asset(it) }
        val release = Release(1, "v1", "v1", false, false, null, null, null, null, null, splitOnly)
        val set = assertIs<AssetSelection.SplitSet>(AssetSelector.select(splitOnly, x86Emulator, null))
        val warned = PreliminaryCompatibility.assess(set, release, DeviceProfile(37, x86Emulator, null)).first { it.check == PreCheck.FORMAT }
        assertEquals(CheckStatus.WARNING, warned.status)
        assertEquals(PreliminaryCompatibility.DETAIL_SPLIT_SET, warned.detail)

        // A hívó (pl. a saját frissítés) a készletet el is utasíthatja: ekkor letöltés nélkül, érthető okkal.
        val none = assertIs<AssetSelection.NoInstallable>(AssetSelector.select(splitOnly, x86Emulator, null, allowSplitSets = false))
        assertEquals(4, none.rejected.size)
        assertTrue(none.rejected.all { it.reason == AssetRejection.SPLIT_SET })
        val format = PreliminaryCompatibility.assess(none, release, DeviceProfile(37, x86Emulator, null)).first { it.check == PreCheck.FORMAT }
        assertEquals(CheckStatus.FAILED, format.status)
        assertEquals(PreliminaryCompatibility.DETAIL_SPLIT_SET, format.detail)
        // Ha a kiadásban önálló (pl. universal) APK is van, az választódik, a készlet tagjai kimaradnak.
        val mixed = splitOnly + asset("app-universal-release.apk")
        val single = assertIs<AssetSelection.Single>(AssetSelector.select(mixed, x86Emulator, null))
        assertEquals("app-universal-release.apk", single.asset.name)
        // Az eszközön nem futó önálló APK nem szorítja ki a készletet.
        val withForeignAbi = splitOnly + asset("app-armeabi-v7a-release.apk")
        assertIs<AssetSelection.SplitSet>(AssetSelector.select(withForeignAbi, x86Emulator, null))
    }
}
