// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.model.FindingKind
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.Severity
import io.github.x1wer.reposhelf.core.source.Fetch
import io.github.x1wer.reposhelf.core.source.ProviderRegistry
import io.github.x1wer.reposhelf.core.source.SourceError
import io.github.x1wer.reposhelf.core.source.UnavailableReason
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 1. lépés: frissítés keresése (C. 34.: törölt kiadás, megszakadt kapcsolat; C. 19.: átnevezés, tulajdonosváltás). */
class ReleaseDiscoveryTest {
    private val provider = FakeProvider()
    private val discovery = ReleaseDiscovery(ProviderRegistry(listOf(provider)))
    private val apk = asset(1, "app-universal.apk")
    private val stable = release(10, "v1.2.0", listOf(apk), published = "2026-10-01T10:00:00Z")
    private val beta = release(11, "v1.3.0-beta1", listOf(asset(2, "app.apk")), prerelease = true, published = "2026-10-05T10:00:00Z")

    private fun source(repoId: Long? = 42, ownerId: Long? = 7, prerelease: Boolean = false) =
        TrackedSource(ref("owner/app"), repoId?.let(::ProviderRepoId), ownerId, prerelease)

    private fun ok(result: StepResult<Discovery>): StepResult.Ok<Discovery> = assertIs<StepResult.Ok<Discovery>>(result)

    @Test
    fun unchangedRepo_returnsLatestStable_andIgnoresPrereleaseByDefault() = runTest {
        provider.byName = { fresh(metadata("owner/app", 42, 7)) }
        provider.releaseList = { fresh(listOf(beta, stable)) }
        val result = ok(discovery.discover(source()))
        assertEquals(RepoIdentity.UNCHANGED, result.value.identity)
        assertEquals(stable, result.value.candidate)
        assertTrue(result.findings.isEmpty())

        val withBeta = ok(discovery.discover(source(prerelease = true)))
        assertEquals(beta, withBeta.value.candidate)
    }

    @Test
    fun firstCheck_recordsIdentity() = runTest {
        provider.byName = { fresh(metadata("owner/app", 42, 7)) }
        provider.releaseList = { fresh(listOf(stable)) }
        assertEquals(RepoIdentity.FIRST_SEEN, ok(discovery.discover(source(repoId = null, ownerId = null))).value.identity)
    }

    @Test
    fun renamedRepo_isFollowed_withInfoFinding() = runTest {
        // A régi név átirányít az újra; az azonosító és a tulajdonos ugyanaz.
        provider.byName = { fresh(metadata("owner/app-new", 42, 7)) }
        provider.releaseList = { fresh(listOf(stable)) }
        val result = ok(discovery.discover(source()))
        assertEquals(RepoIdentity.RENAMED, result.value.identity)
        assertEquals("owner/app-new", result.value.repo.ref.path)
        assertTrue(result.findings.any { it.kind == FindingKind.REPO_RENAMED && it.severity == Severity.INFO })
        assertTrue("releases:owner/app-new" in provider.calls, "a kiadásokat már az új néven kéri le")
    }

    @Test
    fun ownerChange_isWarning() = runTest {
        provider.byName = { fresh(metadata("someone-else/app", 42, 99)) }
        provider.releaseList = { fresh(listOf(stable)) }
        val result = ok(discovery.discover(source()))
        assertEquals(RepoIdentity.OWNER_CHANGED, result.value.identity)
        assertTrue(result.findings.any { it.kind == FindingKind.REPO_OWNER_CHANGED && it.severity == Severity.WARNING })
    }

    @Test
    fun differentRepoUnderSameName_isBlockedAsCritical() = runTest {
        provider.byName = { fresh(metadata("owner/app", 4242, 7)) }
        provider.releaseList = { fresh(listOf(stable)) }
        val result = assertIs<StepResult.Blocked>(discovery.discover(source()))
        assertTrue(result.findings.single().let { it.kind == FindingKind.REPO_ID_CHANGED && it.severity == Severity.CRITICAL })
        assertTrue(provider.calls.none { it.startsWith("releases") }, "a gyanús repó kiadásait le sem kéri")
    }

    @Test
    fun missingRepo_isFoundAgainByStableId() = runTest {
        provider.byName = { Fetch.NotFound }
        provider.byId = { fresh(metadata("owner/moved-app", 42, 7)) }
        provider.releaseList = { fresh(listOf(stable)) }
        val result = ok(discovery.discover(source()))
        assertEquals(RepoIdentity.RENAMED, result.value.identity)
    }

    @Test
    fun deletedRepo_isReportedAsWarning() = runTest {
        provider.byName = { Fetch.NotFound }
        provider.byId = { Fetch.NotFound }
        val result = assertIs<StepResult.Failed>(discovery.discover(source()))
        assertEquals(UpdateFailure.RepoNotFound, result.failure)
        assertTrue(result.findings.any { it.kind == FindingKind.REPO_NOT_FOUND && it.severity == Severity.WARNING })
    }

    @Test
    fun legalTakedown_rateLimit_andNetworkLoss_areFailures() = runTest {
        provider.byName = { Fetch.Unavailable(UnavailableReason.LEGAL_TAKEDOWN) }
        assertIs<UpdateFailure.RepoUnavailable>(assertIs<StepResult.Failed>(discovery.discover(source())).failure)

        val until = Instant.parse("2026-11-10T12:00:00Z")
        provider.byName = { Fetch.RateLimited(until) }
        assertEquals(UpdateFailure.RateLimited(until), assertIs<StepResult.Failed>(discovery.discover(source())).failure)

        provider.byName = { fresh(metadata("owner/app", 42, 7)) }
        provider.releaseList = { Fetch.Failed(SourceError.Network("UnknownHostException")) }
        assertIs<UpdateFailure.Network>(assertIs<StepResult.Failed>(discovery.discover(source())).failure)
    }

    @Test
    fun deletedRelease_orReleaseWithoutApk_meansNoCandidate() = runTest {
        provider.byName = { fresh(metadata("owner/app", 42, 7)) }
        // A korábban látott kiadást törölték: a lista üres.
        provider.releaseList = { fresh(emptyList()) }
        val empty = ok(discovery.discover(source()))
        assertNull(empty.value.candidate)
        assertTrue(empty.findings.any { it.kind == FindingKind.NO_INSTALLABLE_RELEASE && it.detail == "no-release" })

        // Van kiadás, de APK nélkül (csak forráskód és AAB), illetve a draft nem számít.
        provider.releaseList = {
            fresh(listOf(release(20, "v2.0", listOf(asset(5, "app.aab"), asset(6, "source.zip"))), release(21, "v2.1", listOf(apk), draft = true)))
        }
        val noApk = ok(discovery.discover(source()))
        assertNull(noApk.value.candidate)
        assertTrue(noApk.findings.any { it.kind == FindingKind.NO_INSTALLABLE_RELEASE && it.detail == "no-apk" })
    }
}
