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
class ApkDownloader(
    private val downloader: FileDownloader,
    private val downloadsDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val partialBudgetBytes: Long = PARTIAL_BUDGET_BYTES,
) {

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
            prunePartials(keep = target)
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

    /**
     * A félbemaradt letöltések összkvótája (DL-05): a többi állomány részfájljai (`.part` + `.part.etag`) együtt legfeljebb
     * [partialBudgetBytes] (alapértelmezés: [PARTIAL_BUDGET_BYTES]) bájtot foglalhatnak; a legrégebbiek törlődnek először. A most folytatandó állományé és
     * a nemrég (az utolsó [ACTIVE_WINDOW_MILLIS]-ben) írt részfájl nem törlődik: azt egy párhuzamos letöltés írhatja.
     * A kész (ellenőrzött, jóváhagyásra váró) állományokat ez nem érinti; azokat az időalapú takarítás kezeli.
     */
    private fun prunePartials(keep: File) {
        val keepPart = keep.path + FileDownloader.PART_SUFFIX
        val now = clock()
        val partials = downloadsDir.listFiles { f -> f.isFile && f.name.endsWith(FileDownloader.PART_SUFFIX) }.orEmpty()
            .filter { it.path != keepPart }
        var total = partials.sumOf { it.length() }
        if (total <= partialBudgetBytes) return
        for (part in partials.sortedBy { it.lastModified() }) {
            if (total <= partialBudgetBytes) break
            if (now - part.lastModified() < ACTIVE_WINDOW_MILLIS) continue
            val size = part.length()
            if (part.delete()) {
                total -= size
                File(part.path.removeSuffix(FileDownloader.PART_SUFFIX) + FileDownloader.META_SUFFIX).delete()
            }
        }
    }

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    companion object {
        /** A többi állomány félbemaradt letöltéseinek összkvótája (DL-05). */
        const val PARTIAL_BUDGET_BYTES = 512L * 1024 * 1024

        /** Ennyi időn belül írt részfájlt aktívnak tekintünk (a letöltő inaktivitási időkorlátja ennél jóval rövidebb). */
        const val ACTIVE_WINDOW_MILLIS = 10L * 60 * 1000
    }
}
