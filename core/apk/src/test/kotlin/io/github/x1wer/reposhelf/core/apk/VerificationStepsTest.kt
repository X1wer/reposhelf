// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.apk

import io.github.x1wer.reposhelf.core.model.FindingKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Az 5. (integritás) és a 6. (aláírás) lépés külön-külön (C. 34.). */
class VerificationStepsTest {
    private val device = DeviceProfile(35, listOf("x86_64", "arm64-v8a"), null)
    private val context = VerificationContext(null, emptyList(), null, null, device)

    private fun downloaded(name: String, provider: String? = null, expectedSize: Long? = null): DownloadedApk {
        val file = ApkFixtures.file(name)
        return DownloadedApk(file, ApkFixtures.sha256(file.readBytes()), file.length(), expectedSize ?: file.length(), provider)
    }

    /** Számolja a hívásokat: így ellenőrizhető, hogy sérült fájlon az aláírás-vizsgálat el sem indul. */
    private class CountingInspector : ApkInspector {
        var calls = 0
        private val real = ApksigInspector()
        override fun inspect(file: java.io.File): ApkInspection = real.inspect(file).also { calls++ }
    }

    @Test
    fun integrity_intactFile_passes() {
        val report = ApkVerifier(CountingInspector()).checkIntegrity(listOf(downloaded("fixture-v2-keyA.apk")))
        assertTrue(report.passed)
        assertTrue(report.items.all { it.status == CheckStatus.OK || it.status == CheckStatus.NOT_CHECKED })
    }

    @Test
    fun integrity_digestMismatchOrTruncation_fails() {
        val wrongDigest = downloaded("fixture-v2-keyA.apk", provider = "00".repeat(32))
        val digestReport = ApkVerifier(CountingInspector()).checkIntegrity(listOf(wrongDigest))
        assertFalse(digestReport.passed)
        assertTrue(digestReport.findings.any { it.kind == FindingKind.DIGEST_MISMATCH })

        val truncated = downloaded("fixture-v2-truncated.apk", expectedSize = ApkFixtures.file("fixture-v2-keyA.apk").length())
        val truncatedReport = ApkVerifier(CountingInspector()).checkIntegrity(listOf(truncated))
        assertFalse(truncatedReport.passed)
        assertTrue(truncatedReport.findings.any { it.kind == FindingKind.SIZE_MISMATCH })
    }

    @Test
    fun signature_isNotCheckedOnFailedIntegrity() {
        val inspector = CountingInspector()
        val verifier = ApkVerifier(inspector)
        val files = listOf(downloaded("fixture-v2-keyA.apk", provider = "00".repeat(32)))
        val report = verifier.checkSignature(files, verifier.checkIntegrity(files), context)
        assertEquals(0, inspector.calls)
        assertEquals(CheckStatus.NOT_CHECKED, report.signatureValid)
        assertEquals(SignerIdentity.NOT_CHECKED, report.identity)
        assertFalse(report.isInstallable)
    }

    @Test
    fun signature_onIntactTamperedFile_failsAtLevelOne() {
        val verifier = ApkVerifier(CountingInspector())
        val files = listOf(downloaded("fixture-v2-tampered.apk"))
        val integrity = verifier.checkIntegrity(files)
        // A módosított tartalmú APK ZIP-szerkezete ép: az 5. lépés átengedi, a 6. lépés (1. szint) fogja meg.
        assertTrue(integrity.passed)
        val report = verifier.checkSignature(files, integrity, context)
        assertEquals(CheckStatus.FAILED, report.signatureValid)
        assertTrue(report.findings.any { it.kind == FindingKind.INVALID_SIGNATURE })
    }
}
