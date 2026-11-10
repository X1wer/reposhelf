// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.apk.DownloadedApk
import io.github.x1wer.reposhelf.core.model.ReleaseAsset
import io.github.x1wer.reposhelf.core.network.DownloadFailure
import io.github.x1wer.reposhelf.core.network.DownloadRequest
import io.github.x1wer.reposhelf.core.network.DownloadResult
import io.github.x1wer.reposhelf.core.network.FileDownloader
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/**
 * 4. lépés: a telepítőállomány(ok) letöltése (docs/ARCHITECTURE.md 9. pont). Csak HTTPS, a szolgáltató
 * hosztjaira; pontos méret; SHA-256 letöltés közben; `.part` fájl; folytatható. A fájlok az app privát,
 * backupból kizárt könyvtárába kerülnek.
 */
class ApkDownloader(private val downloader: FileDownloader, private val downloadsDir: File) {

    suspend fun download(
        assets: List<ReleaseAsset>,
        policy: HttpPolicy,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): StepResult<List<DownloadedApk>> {
        require(assets.isNotEmpty())
        val total = assets.sumOf { it.size }
        var completedBytes = 0L
        val files = mutableListOf<DownloadedApk>()
        for (asset in assets) {
            val url = asset.downloadUrl.toHttpUrlOrNull()
            if (url == null) {
                discard(files)
                return StepResult.Failed(UpdateFailure.Download(DownloadFailure.INVALID_REDIRECT, "URL", resumable = false))
            }
            val target = File(downloadsDir, "${asset.id}-${safeName(asset.name)}")
            when (val result = downloader.download(DownloadRequest(url, policy, target, asset.size)) { done, _ -> onProgress(completedBytes + done, total) }) {
                is DownloadResult.Failed -> {
                    // A félbemaradt, folytatható letöltés `.part` fájlja megmarad; a többi letöltött fájl nem kell.
                    if (!result.resumable) discard(files)
                    return StepResult.Failed(UpdateFailure.Download(result.reason, result.detail, result.resumable))
                }
                is DownloadResult.Completed -> {
                    completedBytes += result.size
                    files += DownloadedApk(result.file, result.sha256, result.size, asset.size, asset.sha256)
                }
            }
        }
        return StepResult.Ok(files)
    }

    /** Az ideiglenes telepítőfájlok törlése (C. 21.). */
    fun discard(files: List<DownloadedApk>) {
        files.forEach { it.file.delete() }
    }

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
}
