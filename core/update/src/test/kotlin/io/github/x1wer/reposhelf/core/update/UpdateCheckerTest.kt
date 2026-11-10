// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.apk.DeviceProfile
import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.source.Fetch
import io.github.x1wer.reposhelf.core.source.ProviderRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Az 1–3. lépés együtt, és a 3. lépés külön (C. 34.: több APK-s kiadás, eltérő nevű APK, nem kompatibilis kiadás). */
class UpdateCheckerTest {
    private val provider = FakeProvider().apply { byName = { fresh(metadata("owner/app", 42, 7)) } }
    private val known = mutableMapOf<Long, KnownAnalysis>()
    private val checker = UpdateChecker(ReleaseDiscovery(ProviderRegistry(listOf(provider))), object : KnownAnalyses {
        override suspend fun of(provider: ProviderId, assetId: Long) = known[assetId]
    })
    private val device = DeviceProfile(35, listOf("arm64-v8a", "armeabi-v7a"), freeBytes = 10_000_000_000)
    private val source = TrackedSource(ref("owner/app"), ProviderRepoId(42), 7, includePrereleases = false)

    private suspend fun check(installed: InstalledVersion?, vararg releases: io.github.x1wer.reposhelf.core.model.Release, filter: String? = null): UpdateCheck {
        provider.releaseList = { fresh(releases.toList()) }
        return checker.check(UpdateCheckInput(source, installed, filter, device))
    }

    @Test
    fun statuses() = runTest {
        val r = release(10, "v1.1.0", listOf(asset(1, "app-arm64-v8a-release.apk"), asset(2, "app-x86_64-release.apk")))
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, check(InstalledVersion(10, "1.0.0", null), r).status)
        assertEquals(UpdateStatus.UP_TO_DATE, check(InstalledVersion(11, "1.1.0", null), r).status)
        assertEquals(UpdateStatus.NOT_INSTALLED, check(null, r).status)
        assertEquals(UpdateStatus.VERSION_UNKNOWN, check(InstalledVersion(11, "nightly", null), release(10, "build-latest", listOf(asset(1, "app.apk")))).status)
        assertEquals(UpdateStatus.NO_INSTALLABLE_RELEASE, check(null).status)
    }

    @Test
    fun knownVersionCodeOfSelectedAsset_confirmsTheUpdate() = runTest {
        known[1] = KnownAnalysis(12, rejected = false)
        val result = check(InstalledVersion(11, "1.1.0", null), release(10, "v1.1.0", listOf(asset(1, "app-arm64-v8a.apk"), asset(2, "app-x86_64.apk"))))
        // A verziónév azonos, de a kiválasztott (arm64) állomány elemzett versionCode-ja újabb.
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, result.status)
        assertTrue(result.decision!!.confirmed)
    }

    @Test
    fun previouslyRejectedAsset_isNotOfferedAgain() = runTest {
        // Az állományt korábban letöltöttük, és a vizsgálat elutasította (pl. más aláíró): nem ajánljuk fel újra.
        known[1] = KnownAnalysis(versionCode = null, rejected = true)
        val result = check(InstalledVersion(10, "1.0.0", null), release(10, "v1.1.0", listOf(asset(1, "app-arm64-v8a.apk"))))
        assertEquals(UpdateStatus.UPDATE_BLOCKED, result.status)
        // Ha a telepített verzió már naprakész, a régi elutasítás nem számít.
        assertEquals(UpdateStatus.UP_TO_DATE, check(InstalledVersion(11, "1.1.0", null), release(10, "v1.1.0", listOf(asset(1, "app-arm64-v8a.apk")))).status)
    }

    @Test
    fun multiApkRelease_needsUserChoice_differentlyNamedApkStillSelected() = runTest {
        val twoVariants = check(null, release(10, "v1", listOf(asset(1, "app-foss.apk"), asset(2, "app-full.apk"))))
        assertTrue(twoVariants.compatibility!!.needsUserChoice)
        assertTrue(twoVariants.compatibility!!.installable)
        // A katalógus szűrője egyértelművé teszi.
        val filtered = check(null, release(10, "v1", listOf(asset(1, "app-foss.apk"), asset(2, "app-full.apk"))), filter = "foss")
        assertEquals(listOf(1L), filtered.compatibility!!.chosenAssets.map { it.id })
        // Szokatlan nevű, de egyetlen APK: kiválasztható.
        assertEquals(listOf(5L), check(null, release(10, "v1", listOf(asset(5, "Signed_Final(2).apk")))).compatibility!!.chosenAssets.map { it.id })
    }

    @Test
    fun releaseOnlyForOtherArchitecture_isIncompatible() = runTest {
        val result = check(InstalledVersion(10, "1.0", null), release(10, "v1.1", listOf(asset(1, "app-x86_64.apk"))))
        assertEquals(UpdateStatus.INCOMPATIBLE, result.status)
    }

    @Test
    fun repositoryProblems_mapToStatuses() = runTest {
        provider.byName = { fresh(metadata("owner/app", 4242, 7)) }
        assertEquals(UpdateStatus.REPO_ID_CHANGED, check(null).status)
        provider.byName = { Fetch.NotFound }
        assertEquals(UpdateStatus.REPO_NOT_FOUND, check(null).status)
        provider.byName = { Fetch.RateLimited(Instant.EPOCH) }
        assertEquals(UpdateStatus.RATE_LIMITED, check(null).status)
    }
}
