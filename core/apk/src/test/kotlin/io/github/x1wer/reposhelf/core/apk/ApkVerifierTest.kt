// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.apk

import io.github.x1wer.reposhelf.core.model.FindingKind
import io.github.x1wer.reposhelf.core.model.Severity
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Az APK-ellenőrzés tesztjei valódi, teszt-kulccsal aláírt APK-kkal (`core/apk/testdata/generate_fixtures.py`).
 * Lefedett esetek (B. rész 3.8., C. 34.): sérült/csonka APK, módosított tartalom, eltérő aláírás, kulcsrotáció,
 * hibás csomagazonosító, visszalépés, inkompatibilis Android-verzió és ABI, több aláíró, eltérő nevű APK, split-készlet.
 */
class ApkVerifierTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val inspector = ApksigInspector()
    private val verifier = ApkVerifier(inspector)
    private val x86Device = DeviceProfile(sdkInt = 37, supportedAbis = listOf("x86_64", "arm64-v8a"), freeBytes = null)
    private val armDevice = DeviceProfile(sdkInt = 37, supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"), freeBytes = null)

    private fun fixture(name: String): File = File(checkNotNull(javaClass.getResource("/fixtures/$name")) { name }.toURI())

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun downloaded(file: File, expectedSize: Long = file.length(), provider: String? = null) =
        DownloadedApk(file, sha256(file), file.length(), expectedSize, provider)

    private fun context(
        expectedPackage: String? = "io.github.x1wer.fixture",
        pins: List<String> = emptyList(),
        installed: InstalledAppInfo? = null,
        device: DeviceProfile = x86Device,
        minVersionCode: Long? = null,
    ) = VerificationContext(expectedPackage, pins, minVersionCode, installed, device)

    private fun certOf(name: String): String =
        assertIs<ApkInspection.Valid>(inspector.inspect(fixture(name))).facts.signers.current.single()

    private val certA by lazy { certOf("fixture-v1-keyA.apk") }
    private val certB by lazy { certOf("fixture-v2-keyB.apk") }

    private fun installedV(versionCode: Long, cert: String) =
        InstalledAppInfo("io.github.x1wer.fixture", versionCode, "$versionCode.0", listOf(cert))

    private fun kinds(report: VerificationReport) = report.findings.map { it.kind }.toSet()

    @Test
    fun freshInstall_isInstallable_withTrustOnFirstUse() {
        val report = verifier.verify(listOf(downloaded(fixture("fixture-v2-keyA.apk"))), context())
        assertTrue(report.isInstallable, report.findings.toString())
        assertEquals(CheckStatus.OK, report.signatureValid)
        assertEquals(SignerIdentity.FIRST_SEEN, report.identity)
        assertEquals(2L, report.facts!!.versionCode)
        assertEquals(listOf(certA), report.signerCertSha256)
        assertTrue(FindingKind.FIRST_SEEN_SIGNER in kinds(report) && FindingKind.NO_PROVIDER_DIGEST in kinds(report))
        assertTrue(report.findings.none { it.severity == Severity.CRITICAL })
    }

    @Test
    fun providerDigest_matchingAndMismatching() {
        val apk = fixture("fixture-v2-keyA.apk")
        val ok = verifier.verify(listOf(downloaded(apk, provider = sha256(apk).uppercase())), context())
        assertTrue(ok.isInstallable)
        assertTrue(ok.integrity.any { it.check == Check.PROVIDER_DIGEST && it.status == CheckStatus.OK })

        val bad = verifier.verify(listOf(downloaded(apk, provider = "0".repeat(64))), context())
        assertFalse(bad.isInstallable)
        assertTrue(FindingKind.DIGEST_MISMATCH in kinds(bad))
    }

    @Test
    fun truncatedDownload_isDetected() {
        val truncated = fixture("fixture-v2-truncated.apk")
        val report = verifier.verify(listOf(downloaded(truncated, expectedSize = fixture("fixture-v2-keyA.apk").length())), context())
        assertFalse(report.isInstallable)
        assertTrue(FindingKind.SIZE_MISMATCH in kinds(report))
        assertTrue(FindingKind.INVALID_APK in kinds(report))
        assertEquals(SignerIdentity.NOT_CHECKED, report.identity)
    }

    @Test
    fun tamperedContent_failsTechnicalSignatureCheck() {
        val report = verifier.verify(listOf(downloaded(fixture("fixture-v2-tampered.apk"))), context())
        assertFalse(report.isInstallable)
        assertEquals(CheckStatus.FAILED, report.signatureValid)
        assertTrue(FindingKind.INVALID_SIGNATURE in kinds(report))
    }

    @Test
    fun update_withSameSigner_matchesInstalled() {
        val report = verifier.verify(listOf(downloaded(fixture("fixture-v2-keyA.apk"))), context(installed = installedV(1, certA)))
        assertTrue(report.isInstallable, report.findings.toString())
        assertEquals(SignerIdentity.MATCHES_INSTALLED, report.identity)
    }

    @Test
    fun update_withDifferentSigner_isBlocked() {
        assertNotEquals(certA, certB)
        val report = verifier.verify(listOf(downloaded(fixture("fixture-v2-keyB.apk"))), context(installed = installedV(1, certA)))
        assertFalse(report.isInstallable)
        assertEquals(SignerIdentity.MISMATCH, report.identity)
        assertTrue(FindingKind.SIGNER_CHANGED in kinds(report))
    }

    @Test
    fun update_withValidKeyRotation_isAccepted() {
        val rotated = assertIs<ApkInspection.Valid>(inspector.inspect(fixture("fixture-v3-rotatedAtoB.apk"))).facts
        assertEquals(listOf(certB), rotated.signers.current)
        assertEquals(listOf(certA, certB), rotated.signers.history)
        val report = verifier.verify(listOf(downloaded(fixture("fixture-v3-rotatedAtoB.apk"))), context(installed = installedV(2, certA)))
        assertTrue(report.isInstallable, report.findings.toString())
        assertEquals(SignerIdentity.MATCHES_INSTALLED, report.identity)
    }

    @Test
    fun catalogPinnedSigner_isEnforced() {
        val wrongPin = verifier.verify(listOf(downloaded(fixture("fixture-v2-keyA.apk"))), context(pins = listOf(certB)))
        assertFalse(wrongPin.isInstallable)
        assertTrue(FindingKind.SIGNER_MISMATCH_PINNED in kinds(wrongPin))
        val rightPin = verifier.verify(listOf(downloaded(fixture("fixture-v2-keyA.apk"))), context(pins = listOf(certA.uppercase())))
        assertTrue(rightPin.isInstallable)
        assertEquals(SignerIdentity.MATCHES_PINNED, rightPin.identity)
    }

    @Test
    fun downgrade_isBlocked() {
        val report = verifier.verify(listOf(downloaded(fixture("fixture-v1-keyA.apk"))), context(installed = installedV(2, certA)))
        assertFalse(report.isInstallable)
        assertTrue(FindingKind.VERSION_DOWNGRADE in kinds(report))
    }

    @Test
    fun belowCatalogMinimumVersion_isBlocked() {
        val report = verifier.verify(listOf(downloaded(fixture("fixture-v1-keyA.apk"))), context(minVersionCode = 2))
        assertTrue(FindingKind.BELOW_MIN_VERSION in kinds(report))
    }

    @Test
    fun wrongPackageName_isBlocked() {
        val report = verifier.verify(listOf(downloaded(fixture("other-v2-keyA.apk"))), context())
        assertFalse(report.isInstallable)
        assertTrue(FindingKind.PACKAGE_MISMATCH in kinds(report))
    }

    @Test
    fun incompatibleAndroidVersion_isBlocked() {
        val report = verifier.verify(listOf(downloaded(fixture("fixture-v2-minsdk99-keyA.apk"))), context())
        assertTrue(FindingKind.INCOMPATIBLE_SDK in kinds(report))
        assertFalse(report.isInstallable)
    }

    @Test
    fun nativeAbi_isCheckedAgainstDevice() {
        val apk = listOf(downloaded(fixture("fixture-v2-arm64-keyA.apk")))
        val onX86Only = verifier.verify(apk, context(device = DeviceProfile(37, listOf("x86_64"), null)))
        assertTrue(FindingKind.INCOMPATIBLE_ABI in kinds(onX86Only))
        val onArm = verifier.verify(apk, context(device = armDevice))
        assertTrue(onArm.isInstallable)
        assertEquals(setOf("arm64-v8a"), onArm.nativeAbis)
    }

    @Test
    fun multipleSigners_areRejected() {
        val report = verifier.verify(listOf(downloaded(fixture("fixture-v2-twoSigners.apk"))), context())
        assertFalse(report.isInstallable)
        assertTrue(FindingKind.MULTIPLE_SIGNERS in kinds(report))
    }

    @Test
    fun differentlyNamedApk_isIdentifiedByContentNotName() {
        val renamed = temp.newFile("Totally Different Name (1).apk")
        fixture("fixture-v2-keyA.apk").copyTo(renamed, overwrite = true)
        val report = verifier.verify(listOf(downloaded(renamed)), context())
        assertTrue(report.isInstallable)
        assertEquals("io.github.x1wer.fixture", report.facts!!.packageName)
    }

    @Test
    fun splitSet_isValidatedAsOneUnit() {
        val base = downloaded(fixture("fixture-split-base-v4-keyA.apk"))
        val split = downloaded(fixture("fixture-split-config.arm64_v8a-v4-keyA.apk"))
        val ok = verifier.verify(listOf(base, split), context(device = armDevice))
        assertTrue(ok.isInstallable, ok.findings.toString())
        assertNotNull(ok.facts)
        assertEquals(listOf("config.arm64_v8a"), ok.splitFacts.map { it.splitName })

        val splitAlone = verifier.verify(listOf(split), context(device = armDevice))
        assertTrue(FindingKind.SPLIT_SET_INCONSISTENT in kinds(splitAlone))

        val mixedVersions = verifier.verify(listOf(downloaded(fixture("fixture-v2-keyA.apk")), split), context(device = armDevice))
        assertTrue(FindingKind.SPLIT_SET_INCONSISTENT in kinds(mixedVersions))
    }

    @Test
    fun targetSdkFloor_followsPlatform() {
        assertEquals(24, ApkVerifier.minimumInstallableTargetSdk(37))
        assertEquals(23, ApkVerifier.minimumInstallableTargetSdk(34))
        assertEquals(0, ApkVerifier.minimumInstallableTargetSdk(28))
    }
}
