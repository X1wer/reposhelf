// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.installer

import io.github.x1wer.reposhelf.core.apk.ApkVerifier
import io.github.x1wer.reposhelf.core.apk.DownloadedApk
import io.github.x1wer.reposhelf.core.apk.VerificationContext
import io.github.x1wer.reposhelf.core.apk.VerificationReport
import io.github.x1wer.reposhelf.core.model.ReleaseAsset
import io.github.x1wer.reposhelf.core.network.DownloadFailure
import io.github.x1wer.reposhelf.core.network.DownloadRequest
import io.github.x1wer.reposhelf.core.network.DownloadResult
import io.github.x1wer.reposhelf.core.network.FileDownloader
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/** Mit várunk el a telepítendő alkalmazástól (a katalógusból vagy korábbi telepítésből). */
data class InstallTarget(
    val expectedPackageName: String?,
    val pinnedSignerCerts: List<String>,
    val minVersionCode: Long?,
    val fromCatalog: Boolean,
)

sealed interface PreparedInstall {
    /** Letöltve és ellenőrizve, telepíthető. */
    data class Ready(val report: VerificationReport, val files: List<DownloadedApk>, val isUpdate: Boolean) : PreparedInstall

    /** Letöltve, de az ellenőrzés kritikus problémát talált: nem telepíthető (C. 20.). */
    data class Blocked(val report: VerificationReport) : PreparedInstall

    data class DownloadFailed(val reason: DownloadFailure, val detail: String?, val resumable: Boolean) : PreparedInstall
}

/**
 * Az első telepítés útja (docs/ARCHITECTURE.md 9. pont, 4–7. lépés): letöltés → integritás → aláírás és
 * kompatibilitás → PackageInstaller. A letöltött fájlok az alkalmazás privát, backupból kizárt könyvtárában vannak.
 */
class InstallCoordinator(
    private val downloader: FileDownloader,
    private val verifier: ApkVerifier,
    private val installed: InstalledPackages,
    private val gateway: PackageInstallerGateway,
    private val downloadsDir: File,
) {
    suspend fun prepare(
        assets: List<ReleaseAsset>,
        policy: HttpPolicy,
        target: InstallTarget,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): PreparedInstall {
        require(assets.isNotEmpty())
        val total = assets.sumOf { it.size }
        var completedBytes = 0L
        val files = mutableListOf<DownloadedApk>()
        for (asset in assets) {
            val url = asset.downloadUrl.toHttpUrlOrNull()
                ?: return PreparedInstall.DownloadFailed(DownloadFailure.INVALID_REDIRECT, "URL", false)
            val file = File(downloadsDir, "${asset.id}-${safeName(asset.name)}")
            when (val result = downloader.download(DownloadRequest(url, policy, file, asset.size)) { done, _ -> onProgress(completedBytes + done, total) }) {
                is DownloadResult.Failed -> {
                    if (!result.resumable) files.forEach { it.file.delete() }
                    return PreparedInstall.DownloadFailed(result.reason, result.detail, result.resumable)
                }
                is DownloadResult.Completed -> {
                    completedBytes += result.size
                    files += DownloadedApk(result.file, result.sha256, result.size, asset.size, asset.sha256)
                }
            }
        }
        return withContext(Dispatchers.Default) { verify(files, target) }
    }

    private fun verify(files: List<DownloadedApk>, target: InstallTarget): PreparedInstall {
        val device = installed.deviceProfile()
        fun context(packageName: String?) = VerificationContext(
            expectedPackageName = target.expectedPackageName,
            pinnedSignerCerts = target.pinnedSignerCerts,
            minVersionCode = target.minVersionCode,
            installed = packageName?.let(installed::get),
            device = device,
        )
        var report = verifier.verify(files, context(target.expectedPackageName))
        // Ha a csomagnév csak az APK-ból derült ki, a telepített példánnyal (aláíró, verzió) is össze kell vetni.
        val actualPackage = report.facts?.packageName
        if (target.expectedPackageName == null && actualPackage != null) {
            report = verifier.verify(files, context(actualPackage))
        }
        val isUpdate = report.facts?.packageName?.let(installed::get) != null
        return if (report.isInstallable) PreparedInstall.Ready(report, files, isUpdate) else {
            cleanup(files)
            PreparedInstall.Blocked(report)
        }
    }

    suspend fun install(prepared: PreparedInstall.Ready, fromCatalog: Boolean): InstallStart {
        val facts = checkNotNull(prepared.report.facts)
        val perFile = prepared.report.fileFacts
        // Az alap-APK kerül először a sessionbe, utána a splitek.
        val ordered = prepared.files.mapIndexed { i, file -> VerifiedApk(file.file, file.sha256, perFile.getOrNull(i)?.splitName) }
            .sortedBy { it.splitName != null }
        return gateway.install(ordered, facts.packageName, fromCatalog = fromCatalog, isUpdate = prepared.isUpdate)
    }

    fun cleanup(files: List<DownloadedApk>) {
        files.forEach { it.file.delete() }
    }

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
}
