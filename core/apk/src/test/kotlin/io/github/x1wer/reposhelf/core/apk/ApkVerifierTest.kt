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
import kotlin.test.assertNull
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

    private fun fixture(name: String): File = ApkFixtures.file(name)

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun downloaded(file: File, expectedSize: Long = file.length(), provider: String? = null) =
        DownloadedApk(file, sha256(file), file.length(), expectedSize, provider)

    private fun context(
        expectedPackage: String? = "io.github.x1wer.fixture",
        pins: List<String> = emptyList(),
        installed: InstalledAppInfo? = null,
        device: DeviceProfile = x86Device,
        minVersionCode: Long? = null,
        stored: List<String> = emptyList(),
    ) = VerificationContext(expectedPackage, pins, minVersionCode, installed, device, stored)

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

    /** Mint a valódi rendszer (API 28 és 37, műszeres teszttel ellenőrizve): az önálló split-APK-t nem dolgozza fel. */
    private val deviceLikeVerifier = ApkVerifier { file ->
        when (val result = inspector.inspect(file)) {
            is ApkInspection.Valid -> if (result.facts.splitName != null) ApkInspection.Invalid("split") else result
            is ApkInspection.Invalid -> result
        }
    }

    // ---- D-128 (Q-011): a split-készlet kiegészítő állományait a rendszer ellenőrzi; az alap-APK-t mi, teljesen ----

    @Test
    fun splitSetOnDevice_leavesOnlyTheSplitsToTheSystem_whenAllowed() {
        val base = downloaded(fixture("fixture-split-base-v4-keyA.apk"))
        val split = downloaded(fixture("fixture-split-config.arm64_v8a-v4-keyA.apk"))
        val allowed = context(device = armDevice).copy(allowSystemVerifiedSplits = true)
        val ok = deviceLikeVerifier.verify(listOf(base, split), allowed)
        assertTrue(ok.isInstallable, ok.findings.toString())
        assertEquals(1, ok.systemVerifiedSplits)
        assertEquals(Severity.WARNING, ok.findings.single { it.kind == FindingKind.SPLITS_VERIFIED_BY_SYSTEM }.severity)
        assertEquals(listOf(false, true), ok.fileFacts.map { it == null })
        assertEquals(CheckStatus.WARNING, ok.compatibility.single { it.check == Check.SPLIT_SET }.status)
        assertEquals("io.github.x1wer.fixture", ok.facts!!.packageName)

        // Alapértelmezés (pl. a saját frissítés): a telepítés előtt nem ellenőrizhető split elutasítás.
        val strict = deviceLikeVerifier.verify(listOf(base, split), context(device = armDevice))
        assertFalse(strict.isInstallable)
        assertTrue(FindingKind.INVALID_SIGNATURE in kinds(strict))
        assertEquals(0, strict.systemVerifiedSplits)
    }

    @Test
    fun splitSetOnDevice_baseApkIsStillFullyChecked() {
        val base = downloaded(fixture("fixture-split-base-v4-keyA.apk"))
        val split = downloaded(fixture("fixture-split-config.arm64_v8a-v4-keyA.apk"))
        val allowed = context(device = armDevice).copy(allowSystemVerifiedSplits = true)
        // Az alap-APK-nak az elsőnek és teljesen ellenőrizhetőnek kell lennie.
        assertFalse(deviceLikeVerifier.verify(listOf(split, base), allowed).isInstallable)
        assertFalse(deviceLikeVerifier.verify(listOf(split), allowed).isInstallable)
        val tamperedBase = deviceLikeVerifier.verify(listOf(downloaded(fixture("fixture-v2-tampered.apk")), split), allowed)
        assertFalse(tamperedBase.isInstallable)
        // Az alap-APK aláírójára minden korábbi szabály vonatkozik (katalógusban rögzített, telepített, helyben rögzített).
        val pinnedB = deviceLikeVerifier.verify(listOf(base, split), allowed.copy(pinnedSignerCerts = listOf(certB)))
        assertTrue(FindingKind.SIGNER_MISMATCH_PINNED in kinds(pinnedB))
        val installedB = deviceLikeVerifier.verify(listOf(base, split), allowed.copy(installed = installedV(3, certB)))
        assertTrue(FindingKind.SIGNER_CHANGED in kinds(installedB))
        val downgrade = deviceLikeVerifier.verify(listOf(base, split), allowed.copy(installed = installedV(9, certA)))
        assertTrue(FindingKind.VERSION_DOWNGRADE in kinds(downgrade))
        // A sérült split letöltése (méret) a rendszer előtt, nálunk bukik el.
        val truncatedSplit = deviceLikeVerifier.verify(listOf(base, downloaded(split.file, expectedSize = split.size + 1)), allowed)
        assertTrue(FindingKind.SIZE_MISMATCH in kinds(truncatedSplit))
        // Ahol a split elemezhető (tesztkörnyezet), ott eltérő verziónál mi is elutasítjuk.
        val mixed = verifier.verify(listOf(downloaded(fixture("fixture-v2-keyA.apk")), split), allowed)
        assertTrue(FindingKind.SPLIT_SET_INCONSISTENT in kinds(mixed))
    }

    @Test
    fun targetSdkFloor_followsPlatform() {
        assertEquals(24, ApkVerifier.minimumInstallableTargetSdk(37))
        assertEquals(23, ApkVerifier.minimumInstallableTargetSdk(34))
        assertEquals(0, ApkVerifier.minimumInstallableTargetSdk(28))
    }

    // ---- SIG-02: a megbízható forrás (katalógus) és a helyben rögzített aláíró KÜLÖN feltétel ----

    @Test
    fun storedPinAfterRotation_doesNotAcceptTheRetiredKeyAlone() {
        // Korábban A → B rotációval telepítve: a rögzített lánc [A, B], a legutóbb érvényes aláíró B.
        val stored = listOf(certA, certB)
        // Az app most nincs telepítve; egy csak a VISSZAVONT (A) kulccsal aláírt APK nem fogadható el.
        val retired = verifier.verify(listOf(downloaded(fixture("fixture-v2-keyA.apk"))), context(stored = stored))
        assertEquals(SignerIdentity.MISMATCH, retired.identity)
        assertTrue(FindingKind.SIGNER_MISMATCH_PINNED in kinds(retired))
        assertFalse(retired.isInstallable)
        // A jelenlegi kulccsal (B) aláírt és az érvényes rotációs láncú APK elfogadható.
        assertEquals(SignerIdentity.MATCHES_PINNED, verifier.verify(listOf(downloaded(fixture("fixture-v2-keyB.apk"))), context(stored = stored)).identity)
        assertEquals(SignerIdentity.MATCHES_PINNED, verifier.verify(listOf(downloaded(fixture("fixture-v3-rotatedAtoB.apk"))), context(stored = stored)).identity)
        // Rotáció előtti rögzítés ([A]) mellett a szabályosan A → B-re rotált APK folytonos.
        assertEquals(SignerIdentity.MATCHES_PINNED, verifier.verify(listOf(downloaded(fixture("fixture-v3-rotatedAtoB.apk"))), context(stored = listOf(certA))).identity)
    }

    @Test
    fun catalogPin_cannotBeBypassedByALocalPin_andViceVersa() {
        val apkA = listOf(downloaded(fixture("fixture-v2-keyA.apk")))
        // A katalógus B-t rögzít, helyben A van rögzítve: az A-val aláírt APK NEM „egyezik a rögzítettel”.
        val catalogWins = verifier.verify(apkA, context(pins = listOf(certB), stored = listOf(certA)))
        assertEquals(SignerIdentity.MISMATCH, catalogWins.identity)
        assertTrue(FindingKind.SIGNER_MISMATCH_PINNED in kinds(catalogWins))
        // Fordítva: a katalógus A-t enged, de helyben B van rögzítve.
        val storedWins = verifier.verify(apkA, context(pins = listOf(certA), stored = listOf(certB)))
        assertEquals(SignerIdentity.MISMATCH, storedWins.identity)
        // A telepített példány egyezése sem pótolja a katalógus eltérését.
        val installedOnly = verifier.verify(apkA, context(pins = listOf(certB), installed = installedV(1, certA)))
        assertEquals(SignerIdentity.MISMATCH, installedOnly.identity)
        assertFalse(installedOnly.isInstallable)
        // Ha minden forrás egyezik: telepíthető.
        val allAgree = verifier.verify(apkA, context(pins = listOf(certA), stored = listOf(certA), installed = installedV(1, certA)))
        assertEquals(SignerIdentity.MATCHES_INSTALLED, allAgree.identity)
        assertTrue(allAgree.isInstallable, allAgree.findings.toString())
    }

    // ---- SIG-05: ha a telepített példány aláírója nem olvasható ki, az soha nem „első látás” ----

    @Test
    fun installedAppWithoutReadableSigner_isAMismatch_notFirstSeen() {
        val unreadable = InstalledAppInfo("io.github.x1wer.fixture", 1, "1.0", emptyList())
        val report = verifier.verify(listOf(downloaded(fixture("fixture-v2-keyA.apk"))), context(installed = unreadable))
        assertEquals(SignerIdentity.MISMATCH, report.identity)
        assertTrue(FindingKind.SIGNER_CHANGED in kinds(report))
        assertFalse(report.isInstallable)
    }

    // ---- DL-04: a hibás ZIP soha nem dob kivételt ----

    @Test
    fun garbageFile_isInvalidApk_withoutException() {
        val garbage = temp.newFile("garbage.apk").apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        assertNull(ZipInspector.inspect(garbage))
        val report = verifier.verify(listOf(downloaded(garbage)), context())
        assertTrue(FindingKind.INVALID_APK in kinds(report))
        assertFalse(report.isInstallable)
    }
}
