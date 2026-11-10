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

/** Egy már ellenőrzött állomány, amely a telepítési munkamenetbe kerül. */
data class VerifiedFile(val file: File, val sha256: String, val splitName: String?)

data class InstallRequest(
    /** Az alap-APK az első, utána a splitek. */
    val files: List<VerifiedFile>,
    val packageName: String,
    val fromCatalog: Boolean,
    val isUpdate: Boolean,
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
        val ordered = files.mapIndexed { i, file -> VerifiedFile(file.file, file.sha256, report.fileFacts[i].splitName) }
            .sortedBy { it.splitName != null }
        return when (val start = port.install(InstallRequest(ordered, facts.packageName, expectation.fromCatalog, isUpdate = installed != null))) {
            is InstallStart.Committed -> StepResult.Ok(start.sessionId)
            is InstallStart.Failed -> StepResult.Failed(UpdateFailure.InstallStartFailed(start.reason))
        }
    }
}

/** A készüléken telepített csomag lekérdezése (Androidon a PackageManager; csak a látható csomagok, ARCH 13.). */
fun interface InstalledAppLookup {
    fun get(packageName: String): InstalledAppInfo?
}
