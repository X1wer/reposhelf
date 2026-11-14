// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.update

import io.github.x1wer.reposhelf.core.model.ReleaseAsset
import io.github.x1wer.reposhelf.core.network.FileDownloader
import io.github.x1wer.reposhelf.core.network.HttpClients
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A félbemaradt letöltések összkvótája (DL-05). Hálózat nincs: a kért hoszt nem engedélyezett, a letöltő azonnal elutasít. */
class ApkDownloaderQuotaTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun partial(dir: File, name: String, sizeBytes: Long, ageMillis: Long): File =
        File(dir, "$name${FileDownloader.PART_SUFFIX}").apply {
            RandomAccessFile(this, "rw").use { it.setLength(sizeBytes) }
            setLastModified(now - ageMillis)
            File(path.removeSuffix(FileDownloader.PART_SUFFIX) + FileDownloader.META_SUFFIX).writeText("\"etag\"")
        }

    @Test
    fun oldPartialsBeyondTheBudget_areRemovedOldestFirst_butActiveAndCurrentOnesStay() = runBlocking {
        val dir = temp.newFolder("downloads")
        val kib = 1024L
        val oldest = partial(dir, "1-old.apk", 300 * kib, 5 * hour)
        val older = partial(dir, "2-older.apk", 200 * kib, 4 * hour)
        val active = partial(dir, "3-active.apk", 300 * kib, 60 * 1000L)
        val current = partial(dir, "4-current.apk", 300 * kib, 6 * hour)
        val downloader = ApkDownloader(FileDownloader(HttpClients.create("RepoShelf-Test/1")), dir, { now }, partialBudgetBytes = 512 * 1024)

        val asset = ReleaseAsset(4, "current.apk", null, null, 1000, "https://not-allowed.example/current.apk", null, null)
        val result = downloader.download(listOf(asset), HttpPolicy.of("github.com", maxBodyBytes = 10L * 1024 * 1024))
        assertIs<StepResult.Failed>(result)

        // 800 KiB más részfájl > 512 KiB (a tesztben kicsinyített keret): a legrégebbi törlődik (a metaadat-fájljával
        // együtt); utána 500 KiB marad, ez a kereten belül van, a többi megmarad.
        assertFalse(oldest.exists())
        assertFalse(File(oldest.path.removeSuffix(FileDownloader.PART_SUFFIX) + FileDownloader.META_SUFFIX).exists())
        assertTrue(older.exists())
        // A most írt (párhuzamos letöltés) és a most folytatandó állomány részfájlja soha nem törlődik.
        assertTrue(active.exists())
        assertTrue(current.exists())
    }

    @Test
    fun partialsWithinTheBudget_areKeptForResuming() = runBlocking {
        val dir = temp.newFolder("downloads")
        val small = partial(dir, "1-small.apk", 10L * 1024, 30 * hour)
        val downloader = ApkDownloader(FileDownloader(HttpClients.create("RepoShelf-Test/1")), dir, { now }, partialBudgetBytes = 512 * 1024)
        val asset = ReleaseAsset(2, "other.apk", null, null, 1000, "https://not-allowed.example/other.apk", null, null)
        downloader.download(listOf(asset), HttpPolicy.of("github.com", maxBodyBytes = 1024 * 1024))
        assertTrue(small.exists())
    }
}
