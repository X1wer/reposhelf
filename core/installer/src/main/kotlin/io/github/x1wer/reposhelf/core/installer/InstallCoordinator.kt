// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.installer

import io.github.x1wer.reposhelf.core.apk.ApkFacts
import io.github.x1wer.reposhelf.core.apk.ApkVerifier
import io.github.x1wer.reposhelf.core.apk.DownloadedApk
import io.github.x1wer.reposhelf.core.apk.VerificationContext
import io.github.x1wer.reposhelf.core.apk.VerificationReport
import io.github.x1wer.reposhelf.core.model.ReleaseAsset
import io.github.x1wer.reposhelf.core.network.DownloadFailure
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.update.ApkDownloader
import io.github.x1wer.reposhelf.core.update.InstallExpectation
import io.github.x1wer.reposhelf.core.update.InstallInitiator
import io.github.x1wer.reposhelf.core.update.InstallOutcome
import io.github.x1wer.reposhelf.core.update.InstallResultVerifier
import io.github.x1wer.reposhelf.core.update.StepResult
import io.github.x1wer.reposhelf.core.update.UnattendedInstallPolicy
import io.github.x1wer.reposhelf.core.update.UnattendedReason
import io.github.x1wer.reposhelf.core.update.UpdateFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Mit várunk el a telepítendő alkalmazástól (a katalógusból vagy korábbi telepítésből). */
data class InstallTarget(
    val expectedPackageName: String?,
    /** A katalógusban rögzített aláírók; a korábbi telepítésből rögzítetteket a koordinátor maga kérdezi le. */
    val pinnedSignerCerts: List<String>,
    val minVersionCode: Long?,
    val fromCatalog: Boolean,
)

sealed interface PreparedInstall {
    /** Letöltve és ellenőrizve, telepíthető. */
    data class Ready(val report: VerificationReport, val files: List<DownloadedApk>, val isUpdate: Boolean) : PreparedInstall

    /** Letöltve, de az ellenőrzés kritikus problémát talált: nem telepíthető (C. 20.). */
    data class Blocked(val report: VerificationReport, val files: List<DownloadedApk>) : PreparedInstall

    data class DownloadFailed(val reason: DownloadFailure, val detail: String?, val resumable: Boolean) : PreparedInstall
}

/**
 * Az első telepítés ÉS a frissítés közös útja (docs/ARCHITECTURE.md 9. pont, 4–8. lépés): letöltés
 * ([ApkDownloader]) → integritás és aláírás ([ApkVerifier.checkIntegrity], [ApkVerifier.checkSignature]) →
 * a telepítés kezdeményezése ([InstallInitiator]) → az eredmény ellenőrzése ([InstallResultVerifier]).
 */
class InstallCoordinator(
    private val downloader: ApkDownloader,
    private val verifier: ApkVerifier,
    private val installed: InstalledPackages,
    private val initiator: InstallInitiator,
    /** Az első telepítéskor (vagy a már telepített példányról) rögzített aláírók csomagonként (C. 19.). */
    private val storedPins: suspend (packageName: String) -> List<String>,
) {
    /** 4–6. lépés. */
    suspend fun prepare(
        assets: List<ReleaseAsset>,
        policy: HttpPolicy,
        target: InstallTarget,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): PreparedInstall {
        val files = when (val download = downloader.download(assets, policy, onProgress)) {
            is StepResult.Ok -> download.value
            is StepResult.Failed -> {
                val failure = download.failure as? UpdateFailure.Download
                return PreparedInstall.DownloadFailed(failure?.reason ?: DownloadFailure.NETWORK, failure?.detail, failure?.resumable ?: false)
            }
            is StepResult.Blocked -> return PreparedInstall.DownloadFailed(DownloadFailure.NETWORK, null, false)
        }
        return verify(files, target)
    }

    /**
     * 5–6. lépés már a készüléken lévő állományokra. Csak a debug build helyi saját frissítés-szimulációja használja
     * (emulátoros teszt hálózat nélkül); ugyanaz az ellenőrzés fut, mint letöltés után.
     */
    suspend fun verifyLocal(files: List<DownloadedApk>, target: InstallTarget): PreparedInstall = verify(files, target)

    private suspend fun verify(files: List<DownloadedApk>, target: InstallTarget): PreparedInstall {
        val device = installed.deviceProfile()
        val integrity = withContext(Dispatchers.IO) { verifier.checkIntegrity(files) }
        suspend fun context(packageName: String?) = VerificationContext(
            expectedPackageName = target.expectedPackageName,
            pinnedSignerCerts = (target.pinnedSignerCerts + (packageName?.let { storedPins(it) }.orEmpty())).distinct(),
            minVersionCode = target.minVersionCode,
            installed = packageName?.let { withContext(Dispatchers.IO) { installed.get(it) } },
            device = device,
        )
        var report = withContext(Dispatchers.IO) { verifier.checkSignature(files, integrity, context(target.expectedPackageName)) }
        // Ha a csomagnév csak az APK-ból derült ki, a telepített példánnyal és a rögzített aláíróval is össze kell vetni.
        val actualPackage = report.facts?.packageName
        if (target.expectedPackageName == null && actualPackage != null) {
            val context = context(actualPackage)
            report = withContext(Dispatchers.IO) { verifier.checkSignature(files, integrity, context) }
        }
        val isUpdate = report.facts?.packageName?.let { withContext(Dispatchers.IO) { installed.get(it) } } != null
        return if (report.isInstallable) PreparedInstall.Ready(report, files, isUpdate) else PreparedInstall.Blocked(report, files)
    }

    /**
     * 7. lépés: a telepítés kezdeményezése, a telepítés PILLANATÁBAN érvényes állapottal újra összevetve.
     * [unattended]: a hívó (háttérfeladat) az [UnattendedInstallPolicy] döntése alapján kéri a beavatkozás nélküli módot.
     */
    suspend fun install(prepared: PreparedInstall.Ready, target: InstallTarget, unattended: Boolean = false): StepResult<Int> {
        val packageName = prepared.report.facts?.packageName
        val installedNow = packageName?.let { withContext(Dispatchers.IO) { installed.get(it) } }
        return initiator.initiate(
            report = prepared.report,
            files = prepared.files,
            expectation = InstallExpectation(target.expectedPackageName, target.fromCatalog),
            installedNow = installedNow,
            canRequestInstalls = installed.canRequestInstalls(),
            unattended = unattended,
        )
    }

    /** A felhasználói beavatkozás nélküli telepítés döntése (ARCH 11.2.) a telepítés pillanatában érvényes állapottal. */
    suspend fun unattendedVerdict(prepared: PreparedInstall.Ready, userEnabled: Boolean, ourPackage: String): UnattendedReason {
        val packageName = prepared.report.facts?.packageName ?: return UnattendedReason.NOT_VERIFIED
        val source = withContext(Dispatchers.IO) { if (installed.get(packageName) != null) installed.installSource(packageName) else null }
        return UnattendedInstallPolicy.evaluate(userEnabled, android.os.Build.VERSION.SDK_INT, ourPackage, source, prepared.report)
    }

    /** 8. lépés: a rendszer eredményének ellenőrzése a ténylegesen telepített csomaggal. */
    suspend fun verifyResult(event: InstallEvent, expected: ApkFacts): InstallOutcome? {
        val signal = event.toSignal() ?: return null
        val actual = withContext(Dispatchers.IO) { installed.get(expected.packageName) }
        return InstallResultVerifier.verify(signal, expected, actual)
    }

    fun cleanup(files: List<DownloadedApk>) = downloader.discard(files)
}
