// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.network

import io.github.x1wer.reposhelf.core.network.testing.HttpsTestServer
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var https: HttpsTestServer
    private lateinit var downloader: FileDownloader
    private val content = Random(3).nextBytes(300_000)
    private val contentSha = sha256(content)

    @Before
    fun setUp() {
        https = HttpsTestServer()
        downloader = FileDownloader(HttpClients.create("RepoShelf-Test/1") { https.trustTestCertificate(this) })
    }

    @After
    fun tearDown() = https.close()

    private val server get() = https.server
    private fun policy(maxBytes: Long = 10_000_000) = HttpPolicy.of(https.host, maxBodyBytes = maxBytes)
    private fun target(): File = File(temp.root, "downloads/app.apk")
    private fun request(path: String = "/asset.apk", expected: Long = content.size.toLong(), maxBytes: Long = 10_000_000) =
        DownloadRequest(server.url(path), policy(maxBytes), target(), expected)

    private fun body(bytes: ByteArray) = Buffer().write(bytes)

    @Test
    fun download_succeeds_withHashAndProgress() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(body(content)).addHeader("ETag", "\"blob1\"").build())
        val progress = mutableListOf<Long>()
        val result = assertIs<DownloadResult.Completed>(downloader.download(request()) { done, _ -> progress += done })
        assertEquals(contentSha, result.sha256)
        assertEquals(content.size.toLong(), result.size)
        assertTrue(result.file.readBytes().contentEquals(content))
        assertFalse(File(target().path + FileDownloader.PART_SUFFIX).exists(), "nem marad ideiglenes fájl")
        assertEquals(content.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun redirectWithinAllowList_isFollowed_butHttpAndForeignHostsAreNot() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/blob?sig=abc").build())
        server.enqueue(MockResponse.Builder().code(200).body(body(content)).build())
        assertIs<DownloadResult.Completed>(downloader.download(request()))

        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://${https.host}:${server.port}/x").build())
        assertEquals(DownloadFailure.INSECURE_URL, assertIs<DownloadResult.Failed>(downloader.download(request())).reason)

        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://objects.evil.example/x").build())
        assertEquals(DownloadFailure.HOST_NOT_ALLOWED, assertIs<DownloadResult.Failed>(downloader.download(request())).reason)
    }

    @Test
    fun plainHttpOrForeignStartUrl_isRejectedWithoutRequest() = runBlocking {
        val insecure = DownloadRequest("http://${https.host}:${server.port}/a.apk".toHttpUrl(), policy(), target(), 10)
        assertEquals(DownloadFailure.INSECURE_URL, assertIs<DownloadResult.Failed>(downloader.download(insecure)).reason)
        val foreign = DownloadRequest("https://example.org/a.apk".toHttpUrl(), policy(), target(), 10)
        assertEquals(DownloadFailure.HOST_NOT_ALLOWED, assertIs<DownloadResult.Failed>(downloader.download(foreign)).reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun sizeLimits_areEnforced() = runBlocking {
        // A közölt méret meghaladja a felső korlátot: kérés sem megy ki.
        assertEquals(DownloadFailure.TOO_LARGE, assertIs<DownloadResult.Failed>(downloader.download(request(maxBytes = 1000))).reason)
        assertEquals(0, server.requestCount)
        // A szerver más méretet jelez, mint amit az API közölt.
        server.enqueue(MockResponse.Builder().code(200).body(body(content)).build())
        val mismatch = assertIs<DownloadResult.Failed>(downloader.download(request(expected = 1000)))
        assertEquals(DownloadFailure.SIZE_MISMATCH, mismatch.reason)
        assertFalse(target().exists())
    }

    @Test
    fun serverDeclaringDifferentSize_isRejected() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(body(content.copyOf(content.size / 2))).build())
        val result = assertIs<DownloadResult.Failed>(downloader.download(request()))
        assertEquals(DownloadFailure.SIZE_MISMATCH, result.reason)
        assertFalse(result.resumable)
    }

    @Test
    fun interruptedDownload_isDetected_andResumedWithRange() = runBlocking {
        // Első próbálkozás: a szerver a teljes méretet jelzi, de a kapcsolat a törzs közben megszakad.
        server.enqueue(
            MockResponse.Builder().code(200).body(body(content)).addHeader("ETag", "\"blob1\"")
                .onResponseBody(SocketEffect.CloseSocket()).build(),
        )
        val first = assertIs<DownloadResult.Failed>(downloader.download(request()))
        assertEquals(DownloadFailure.NETWORK, first.reason)
        assertTrue(first.resumable)
        assertFalse(target().exists(), "csonka fájl nem kapja meg a végleges nevet")
        val half = File(target().path + FileDownloader.PART_SUFFIX).length().toInt()
        assertTrue(half in 1 until content.size, "részleges fájl: $half bájt")
        server.takeRequest()

        // Folytatás: Range + If-Range; a szerver 206-tal a hiányzó részt küldi.
        server.enqueue(
            MockResponse.Builder().code(206).body(body(content.copyOfRange(half, content.size)))
                .addHeader("Content-Range", "bytes $half-${content.size - 1}/${content.size}").addHeader("ETag", "\"blob1\"").build(),
        )
        val resumed = assertIs<DownloadResult.Completed>(downloader.download(request()))
        assertEquals(contentSha, resumed.sha256, "a hash a teljes fájlra vonatkozik")
        val recorded = server.takeRequest()
        assertEquals("bytes=$half-", recorded.headers["Range"])
        assertEquals("\"blob1\"", recorded.headers["If-Range"])
    }

    @Test
    fun resume_whenServerIgnoresRange_restartsCleanly() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(body(content)).addHeader("ETag", "\"blob1\"")
                .onResponseBody(SocketEffect.CloseSocket()).build(),
        )
        assertIs<DownloadResult.Failed>(downloader.download(request()))
        server.enqueue(MockResponse.Builder().code(200).body(body(content)).addHeader("ETag", "\"blob2\"").build())
        val result = assertIs<DownloadResult.Completed>(downloader.download(request()))
        assertEquals(contentSha, result.sha256)
        assertTrue(result.file.readBytes().contentEquals(content))
    }

    @Test
    fun deletedReleaseAsset_isHttpError() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("Not Found").build())
        val result = assertIs<DownloadResult.Failed>(downloader.download(request()))
        assertEquals(DownloadFailure.HTTP_ERROR, result.reason)
        assertFalse(result.resumable)
    }

    @Test
    fun serverUnreachable_isNetworkFailure() = runBlocking {
        val url = server.url("/a.apk")
        https.close()
        val result = assertIs<DownloadResult.Failed>(downloader.download(DownloadRequest(url, policy(), target(), 100)))
        assertEquals(DownloadFailure.NETWORK, result.reason)
        assertNull(target().takeIf { it.exists() })
        https = HttpsTestServer()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
