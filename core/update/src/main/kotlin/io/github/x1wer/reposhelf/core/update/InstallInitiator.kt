// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.apk.DownloadedApk
import io.github.x1wer.reposhelf.core.apk.InstalledAppInfo
import io.github.x1wer.reposhelf.core.apk.VerificationReport
import io.github.x1wer.reposhelf.core.model.Finding
import io.github.x1wer.reposhelf.core.model.FindingKind
import io.github.x1wer.reposhelf.core.model.Severity
import java.io.File

/**
 * Egy már ellenőrzött állomány, amely a telepítési munkamenetbe kerül. [isSplit]: kiegészítő állomány (split); a
 * rendszerre bízott ellenőrzésű split is ilyen (D-128).
 */
data class VerifiedFile(val file: File, val sha256: String, val isSplit: Boolean)

data class InstallRequest(
    /** Az alap-APK az első, utána a splitek. */
    val files: List<VerifiedFile>,
    val packageName: String,
    val fromCatalog: Boolean,
    val isUpdate: Boolean,
    /**
     * Felhasználói beavatkozás nélküli telepítés kérése (`USER_ACTION_NOT_REQUIRED`). Csak akkor `true`, ha az
     * [UnattendedInstallPolicy] minden feltételt igazolt; a rendszer ezután is kérhet jóváhagyást (C. 12.4.).
     */
    val unattended: Boolean = false,
)

sealed interface InstallStart {
    data class Committed(val sessionId: Int) : InstallStart
    data class Failed(val reason: String) : InstallStart
}

/** A platform telepítője (Androidon a PackageInstaller-session; docs/ARCHITECTURE.md 11.1.). */
fun interface PackageInstallPort {
    suspend fun install(request: InstallRequest): InstallStart
}

/** Mit várunk a telepítendő csomagtól. */
data class InstallExpectation(
    /** A katalógusban vagy korábbi telepítésből ismert csomagnév; `null`, ha még nem ismert. */
    val packageName: String?,
    val fromCatalog: Boolean,
)

/**
 * 7. lépés: a telepítés kezdeményezése (docs/ARCHITECTURE.md 9. pont). Utolsó kapu a munkamenet előtt: a vizsgálat
 * eredményét a TELEPÍTÉS PILLANATÁBAN érvényes állapottal veti össze (a vizsgálat óta a telepített app is változhatott).
 * Kritikus megállapítás, más csomagnév, visszalépés vagy megváltozott aláíró esetén nem indít telepítést (C. 19.);
 * aláírási ütközést soha nem kerül meg eltávolítással és újratelepítéssel.
 */
class InstallInitiator(private val port: PackageInstallPort) {

    suspend fun initiate(
        report: VerificationReport,
        files: List<DownloadedApk>,
        expectation: InstallExpectation,
        installedNow: InstalledAppInfo?,
        canRequestInstalls: Boolean,
        unattended: Boolean = false,
    ): StepResult<Int> {
        if (!canRequestInstalls) return StepResult.Failed(UpdateFailure.NoInstallPermission)
        val critical = report.findings.filter { it.severity == Severity.CRITICAL }
        if (critical.isNotEmpty()) return StepResult.Blocked(critical)
        val facts = report.facts ?: return StepResult.Blocked(listOf(Finding(FindingKind.INVALID_APK, Severity.CRITICAL)))
        if (report.fileFacts.size != files.size) {
            return StepResult.Blocked(listOf(Finding(FindingKind.SPLIT_SET_INCONSISTENT, Severity.CRITICAL)))
        }
        if (expectation.packageName != null && expectation.packageName != facts.packageName) {
            return StepResult.Blocked(listOf(Finding(FindingKind.PACKAGE_MISMATCH, Severity.CRITICAL, facts.packageName)))
        }
        val installed = installedNow?.takeIf { it.packageName == facts.packageName }
        if (installed != null) {
            if (facts.versionCode < installed.versionCode) {
                return StepResult.Blocked(listOf(Finding(FindingKind.VERSION_DOWNGRADE, Severity.CRITICAL)))
            }
            val installedSigner = installed.signingHistory.lastOrNull()?.lowercase()
            val history = facts.signers.history.ifEmpty { facts.signers.current }.map { it.lowercase() }
            if (installedSigner == null || installedSigner !in history) {
                return StepResult.Blocked(listOf(Finding(FindingKind.SIGNER_CHANGED, Severity.CRITICAL)))
            }
        }
        val ordered = files.mapIndexed { i, file -> VerifiedFile(file.file, file.sha256, isSplit = report.fileFacts[i]?.splitName != null || report.fileFacts[i] == null) }
            .sortedBy { it.isSplit }
        if (ordered.count { !it.isSplit } != 1) {
            return StepResult.Blocked(listOf(Finding(FindingKind.SPLIT_SET_INCONSISTENT, Severity.CRITICAL)))
        }
        // Felhasználói beavatkozás nélkül csak frissítés mehet (az első telepítés mindig jóváhagyásos), és soha nem olyan
        // split-készlet, amelynek a kiegészítő állományait csak a rendszer ellenőrzi (D-128: kifejezett megerősítés kell).
        val allowUnattended = unattended && installed != null && report.systemVerifiedSplits == 0
        val request = InstallRequest(ordered, facts.packageName, expectation.fromCatalog, isUpdate = installed != null, unattended = allowUnattended)
        return when (val start = port.install(request)) {
            is InstallStart.Committed -> StepResult.Ok(start.sessionId)
            is InstallStart.Failed -> StepResult.Failed(UpdateFailure.InstallStartFailed(start.reason))
        }
    }
}

/** A készüléken telepített csomag lekérdezése (Androidon a PackageManager; csak a látható csomagok, ARCH 13.). */
fun interface InstalledAppLookup {
    fun get(packageName: String): InstalledAppInfo?
}
