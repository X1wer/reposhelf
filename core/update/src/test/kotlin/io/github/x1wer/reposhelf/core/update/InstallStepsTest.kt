// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.apk.ApkFacts
import io.github.x1wer.reposhelf.core.apk.CheckStatus
import io.github.x1wer.reposhelf.core.apk.DownloadedApk
import io.github.x1wer.reposhelf.core.apk.InstalledAppInfo
import io.github.x1wer.reposhelf.core.apk.SignerIdentity
import io.github.x1wer.reposhelf.core.apk.SignerInfo
import io.github.x1wer.reposhelf.core.apk.VerificationReport
import io.github.x1wer.reposhelf.core.model.Finding
import io.github.x1wer.reposhelf.core.model.FindingKind
import io.github.x1wer.reposhelf.core.model.Severity
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A 7. (telepítés kezdeményezése) és a 8. (eredmény ellenőrzése) lépés (C. 34.). */
class InstallStepsTest {
    private val pkg = "com.example.app"
    private val certA = "a".repeat(64)
    private val certB = "b".repeat(64)

    private fun facts(code: Long = 20, signers: SignerInfo = SignerInfo(listOf(certA), listOf(certA), false), split: String? = null) =
        ApkFacts(pkg, code, "2.0", 28, 35, split, signers)

    private fun report(facts: ApkFacts?, findings: List<Finding> = emptyList(), fileFacts: List<ApkFacts> = listOfNotNull(facts)) =
        VerificationReport(emptyList(), CheckStatus.OK, SignerIdentity.MATCHES_INSTALLED, emptyList(), facts, emptyList(), emptySet(), findings, fileFacts)

    private val file = DownloadedApk(File("base.apk"), "c".repeat(64), 10, 10, null)

    private class RecordingPort(private val result: InstallStart = InstallStart.Committed(77)) : PackageInstallPort {
        var request: InstallRequest? = null
        override suspend fun install(request: InstallRequest): InstallStart = result.also { this.request = request }
    }

    private fun installed(code: Long, vararg history: String) = InstalledAppInfo(pkg, code, "1.0", history.toList())

    @Test
    fun successfulStart_passesVerifiedFilesBaseFirst() = runTest {
        val port = RecordingPort()
        val split = DownloadedApk(File("split.apk"), "d".repeat(64), 5, 5, null)
        val r = report(facts(), fileFacts = listOf(facts(split = "config.arm64_v8a"), facts()))
        val result = InstallInitiator(port).initiate(r, listOf(split, file), InstallExpectation(pkg, fromCatalog = true), installed(10, certA), canRequestInstalls = true)
        assertEquals(77, assertIs<StepResult.Ok<Int>>(result).value)
        val request = port.request!!
        assertEquals(listOf(null, "config.arm64_v8a"), request.files.map { it.splitName })
        assertTrue(request.isUpdate)
        assertEquals(pkg, request.packageName)
    }

    @Test
    fun missingInstallPermission_isReportedBeforeAnything() = runTest {
        val port = RecordingPort()
        val result = InstallInitiator(port).initiate(report(facts()), listOf(file), InstallExpectation(pkg, true), null, canRequestInstalls = false)
        assertEquals(UpdateFailure.NoInstallPermission, assertIs<StepResult.Failed>(result).failure)
        assertNull(port.request)
    }

    @Test
    fun gate_blocksCriticalPackageMismatchDowngradeAndChangedSigner() = runTest {
        val port = RecordingPort()
        val initiator = InstallInitiator(port)
        suspend fun blockedKind(r: VerificationReport, expected: String? = pkg, installedNow: InstalledAppInfo? = null): FindingKind =
            assertIs<StepResult.Blocked>(initiator.initiate(r, listOf(file), InstallExpectation(expected, false), installedNow, true)).findings.first().kind

        assertEquals(FindingKind.DIGEST_MISMATCH, blockedKind(report(facts(), listOf(Finding(FindingKind.DIGEST_MISMATCH, Severity.CRITICAL)))))
        assertEquals(FindingKind.PACKAGE_MISMATCH, blockedKind(report(facts()), expected = "com.other.app"))
        // A vizsgálat óta a felhasználó máshonnan újabb verziót telepített: a régebbit már nem telepítjük rá.
        assertEquals(FindingKind.VERSION_DOWNGRADE, blockedKind(report(facts(code = 20)), installedNow = installed(30, certA)))
        // A vizsgálat óta a telepített példány aláírója megváltozott.
        assertEquals(FindingKind.SIGNER_CHANGED, blockedKind(report(facts()), installedNow = installed(10, certB)))
        assertEquals(FindingKind.INVALID_APK, blockedKind(report(null)))
        assertNull(port.request, "egyik esetben sem indult munkamenet")
    }

    @Test
    fun rotatedSigner_inLineage_isAccepted() = runTest {
        val rotated = facts(signers = SignerInfo(listOf(certB), listOf(certA, certB), false))
        val result = InstallInitiator(RecordingPort()).initiate(report(rotated), listOf(file), InstallExpectation(pkg, false), installed(10, certA), true)
        assertIs<StepResult.Ok<Int>>(result)
    }

    @Test
    fun platformRefusingTheSession_isAFailure() = runTest {
        val result = InstallInitiator(RecordingPort(InstallStart.Failed("IOException"))).initiate(report(facts()), listOf(file), InstallExpectation(pkg, false), null, true)
        assertEquals(UpdateFailure.InstallStartFailed("IOException"), assertIs<StepResult.Failed>(result).failure)
    }

    @Test
    fun result_success_isVerifiedAgainstTheInstalledPackage() {
        val expected = facts(code = 20)
        val ok = InstallResultVerifier.verify(InstallSignal.Success(pkg), expected, installed(20, certA))
        assertEquals(listOf(certA), assertIs<InstallOutcome.Verified>(ok).signerHistory)

        // Siker, de a készüléken más verzió vagy más aláíró van: nem rögzítünk semmit.
        assertIs<InstallOutcome.Unverified>(InstallResultVerifier.verify(InstallSignal.Success(pkg), expected, installed(19, certA)))
        val wrongSigner = assertIs<InstallOutcome.Unverified>(InstallResultVerifier.verify(InstallSignal.Success(pkg), expected, installed(20, certB)))
        assertEquals(Severity.CRITICAL, wrongSigner.finding.severity)
        assertIs<InstallOutcome.Unverified>(InstallResultVerifier.verify(InstallSignal.Success(pkg), expected, null))
    }

    @Test
    fun result_userCancelAndSystemFailures() {
        val expected = facts()
        assertEquals(InstallOutcome.CancelledByUser, InstallResultVerifier.verify(InstallSignal.Failure(InstallFailureKind.ABORTED, null), expected, null))
        val conflict = assertIs<InstallOutcome.Failed>(InstallResultVerifier.verify(InstallSignal.Failure(InstallFailureKind.CONFLICT, "sig"), expected, null))
        assertEquals(InstallFailureKind.CONFLICT, conflict.kind)
        assertEquals(
            InstallFailureKind.DEVELOPER_VERIFICATION,
            assertIs<InstallOutcome.Failed>(InstallResultVerifier.verify(InstallSignal.Failure(InstallFailureKind.DEVELOPER_VERIFICATION, null), expected, null)).kind,
        )
    }
}
