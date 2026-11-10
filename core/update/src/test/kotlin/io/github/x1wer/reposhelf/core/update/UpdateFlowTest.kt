// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.apk.ApkFixtures
import io.github.x1wer.reposhelf.core.apk.ApkFixtures.CERT_A
import io.github.x1wer.reposhelf.core.apk.ApkFixtures.CERT_B
import io.github.x1wer.reposhelf.core.apk.ApkFixtures.PACKAGE
import io.github.x1wer.reposhelf.core.apk.ApkVerifier
import io.github.x1wer.reposhelf.core.apk.ApksigInspector
import io.github.x1wer.reposhelf.core.apk.DeviceProfile
import io.github.x1wer.reposhelf.core.apk.DownloadedApk
import io.github.x1wer.reposhelf.core.apk.InstalledAppInfo
import io.github.x1wer.reposhelf.core.apk.SignerIdentity
import io.github.x1wer.reposhelf.core.apk.VerificationContext
import io.github.x1wer.reposhelf.core.apk.VerificationReport
import io.github.x1wer.reposhelf.core.model.FindingKind
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.network.DownloadFailure
import io.github.x1wer.reposhelf.core.network.FileDownloader
import io.github.x1wer.reposhelf.core.network.HttpClients
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.network.testing.HttpsTestServer
import io.github.x1wer.reposhelf.core.source.ProviderRegistry
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A teljes frissítési folyamat (1–8. lépés) valódi HTTPS-letöltéssel és teszt-kulccsal aláírt valódi APK-kkal.
 * A C. 34. kötelező esetei közül azok, amelyek a platform nélkül reprodukálhatók; a platformot (telepítő) hamisítvány
 * helyettesíti, a telepítés utáni állapotot a teszt adja meg.
 */
class UpdateFlowTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var https: HttpsTestServer
    private lateinit var downloader: ApkDownloader
    private val provider = FakeProvider()
    private val checker = UpdateChecker(ReleaseDiscovery(ProviderRegistry(listOf(provider))), KnownAnalyses.NONE)
    private val verifier = ApkVerifier(ApksigInspector())
    private val device = DeviceProfile(35, listOf("x86_64", "arm64-v8a"), freeBytes = 10_000_000_000)
    private val source = TrackedSource(ref("owner/fixture"), ProviderRepoId(42), 7, includePrereleases = false)

    @Before
    fun setUp() {
        https = HttpsTestServer()
        downloader = ApkDownloader(FileDownloader(HttpClients.create("RepoShelf-Test/1") { https.trustTestCertificate(this) }), temp.newFolder("downloads"))
        provider.byName = { fresh(metadata("owner/fixture", 42, 7)) }
    }

    @After
    fun tearDown() = https.close()

    private val policy get() = HttpPolicy.of(https.host, maxBodyBytes = 10_000_000)

    /** A kiadás egyetlen állománya a teszt-szerveren; a GitHub-digest a valódi SHA-256. */
    private fun publish(tag: String, fixture: String, id: Long = 1) {
        val bytes = ApkFixtures.bytes(fixture)
        val url = https.server.url("/download/$tag/app.apk").toString()
        provider.releaseList = { fresh(listOf(release(id, tag, listOf(asset(id, "app.apk", bytes.size.toLong(), url, ApkFixtures.sha256(bytes)))))) }
    }

    private fun serve(fixture: String) {
        https.server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(ApkFixtures.bytes(fixture))).build())
    }

    private fun installed(code: Long, vararg history: String) = InstalledAppInfo(PACKAGE, code, "$code.0", history.toList())

    /** 1–3. lépés: ellenőrzés; 4. lépés: letöltés; 5–6. lépés: vizsgálat. */
    private suspend fun checkDownloadVerify(installedNow: InstalledAppInfo?, pins: List<String> = emptyList()): Pair<VerificationReport, List<DownloadedApk>> {
        val check = checker.check(UpdateCheckInput(source, installedNow?.let { InstalledVersion(it.versionCode, it.versionName, null) }, null, device))
        val assets = check.compatibility!!.chosenAssets
        val files = assertIs<StepResult.Ok<List<DownloadedApk>>>(downloader.download(assets, policy)).value
        val integrity = verifier.checkIntegrity(files)
        val report = verifier.checkSignature(files, integrity, VerificationContext(PACKAGE, pins, null, installedNow, device))
        return report to files
    }

    private class FakePort : PackageInstallPort {
        var requests = 0
        override suspend fun install(request: InstallRequest): InstallStart = InstallStart.Committed(++requests)
    }

    @Test
    fun successfulUpdate_allEightSteps() = runBlocking {
        val before = installed(1, CERT_A)
        publish("v2.0", "fixture-v2-keyA.apk")
        serve("fixture-v2-keyA.apk")
        val check = checker.check(UpdateCheckInput(source, InstalledVersion(1, "1.0", null), null, device))
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, check.status)

        val (report, files) = checkDownloadVerify(before, pins = listOf(CERT_A))
        assertEquals(SignerIdentity.MATCHES_INSTALLED, report.identity)
        assertTrue(report.isInstallable)

        val port = FakePort()
        val started = InstallInitiator(port).initiate(report, files, InstallExpectation(PACKAGE, fromCatalog = false), before, canRequestInstalls = true)
        assertIs<StepResult.Ok<Int>>(started)

        val outcome = InstallResultVerifier.verify(InstallSignal.Success(PACKAGE), report.facts!!, installed(2, CERT_A))
        assertEquals(2, assertIs<InstallOutcome.Verified>(outcome).versionCode)
    }

    @Test
    fun keyRotation_withValidLineage_isAccepted() = runBlocking {
        publish("v3.0", "fixture-v3-rotatedAtoB.apk")
        serve("fixture-v3-rotatedAtoB.apk")
        val (report, files) = checkDownloadVerify(installed(2, CERT_A), pins = listOf(CERT_A))
        assertEquals(SignerIdentity.MATCHES_INSTALLED, report.identity)
        assertIs<StepResult.Ok<Int>>(InstallInitiator(FakePort()).initiate(report, files, InstallExpectation(PACKAGE, false), installed(2, CERT_A), true))
        val outcome = InstallResultVerifier.verify(InstallSignal.Success(PACKAGE), report.facts!!, installed(3, CERT_A, CERT_B))
        assertEquals(listOf(CERT_A, CERT_B), assertIs<InstallOutcome.Verified>(outcome).signerHistory)
    }

    @Test
    fun differentSigner_isBlocked_andNoSessionIsStarted() = runBlocking {
        publish("v2.0", "fixture-v2-keyB.apk")
        serve("fixture-v2-keyB.apk")
        val (report, files) = checkDownloadVerify(installed(1, CERT_A))
        assertTrue(report.findings.any { it.kind == FindingKind.SIGNER_CHANGED })
        val port = FakePort()
        assertIs<StepResult.Blocked>(InstallInitiator(port).initiate(report, files, InstallExpectation(PACKAGE, false), installed(1, CERT_A), true))
        assertEquals(0, port.requests)
    }

    @Test
    fun differentSigner_againstPinWhenAppIsNotVisible_isBlocked() = runBlocking {
        // A telepített példány nem látható (vagy közben eltávolították), de az első telepítéskor rögzített aláíró megvan.
        publish("v2.0", "fixture-v2-keyB.apk")
        serve("fixture-v2-keyB.apk")
        val (report, _) = checkDownloadVerify(null, pins = listOf(CERT_A))
        assertTrue(report.findings.any { it.kind == FindingKind.SIGNER_MISMATCH_PINNED })
        assertTrue(!report.isInstallable)
    }

    @Test
    fun wrongPackage_corruptedApk_downgrade_andIncompatibleAndroid_areBlocked() = runBlocking {
        suspend fun kinds(tag: String, fixture: String, installedNow: InstalledAppInfo?, sdk: Int = 35): Set<FindingKind> {
            publish(tag, fixture)
            serve(fixture)
            val check = checker.check(UpdateCheckInput(source, null, null, device))
            val files = assertIs<StepResult.Ok<List<DownloadedApk>>>(downloader.download(check.compatibility!!.chosenAssets, policy)).value
            val report = verifier.verify(files, VerificationContext(PACKAGE, emptyList(), null, installedNow, device.copy(sdkInt = sdk)))
            assertIs<StepResult.Blocked>(InstallInitiator(FakePort()).initiate(report, files, InstallExpectation(PACKAGE, false), installedNow, true))
            return report.findings.map { it.kind }.toSet()
        }
        assertTrue(FindingKind.PACKAGE_MISMATCH in kinds("v2.0", "other-v2-keyA.apk", null))
        assertTrue(FindingKind.INVALID_SIGNATURE in kinds("v2.0", "fixture-v2-tampered.apk", null))
        assertTrue(FindingKind.VERSION_DOWNGRADE in kinds("v1.0", "fixture-v1-keyA.apk", installed(2, CERT_A)))
        assertTrue(FindingKind.INCOMPATIBLE_SDK in kinds("v2.0", "fixture-v2-minsdk99-keyA.apk", null))
    }

    @Test
    fun interruptedDownload_isResumableFailure_andDeletedAsset_isHttpError() = runBlocking {
        publish("v2.0", "fixture-v2-keyA.apk")
        val check = checker.check(UpdateCheckInput(source, null, null, device))
        https.server.enqueue(
            MockResponse.Builder().code(200).body(Buffer().write(ApkFixtures.bytes("fixture-v2-keyA.apk")))
                .onResponseBody(SocketEffect.CloseSocket()).build(),
        )
        val interrupted = assertIs<UpdateFailure.Download>(assertIs<StepResult.Failed>(downloader.download(check.compatibility!!.chosenAssets, policy)).failure)
        assertEquals(DownloadFailure.NETWORK, interrupted.reason)
        assertTrue(interrupted.resumable)

        // A kiadást az ellenőrzés óta törölték: a letöltés 404-et kap.
        https.server.enqueue(MockResponse.Builder().code(404).build())
        val gone = assertIs<UpdateFailure.Download>(assertIs<StepResult.Failed>(downloader.download(check.compatibility!!.chosenAssets, policy)).failure)
        assertEquals(DownloadFailure.HTTP_ERROR, gone.reason)
    }
}
