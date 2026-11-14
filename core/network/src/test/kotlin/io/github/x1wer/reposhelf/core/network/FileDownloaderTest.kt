// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.network

import io.github.x1wer.reposhelf.core.network.testing.HttpsTestServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    // ---- NET-01: az állományletöltésre nincs teljes hívás-időkorlát (csak inaktivitási) ----

    @Test
    fun slowButSteadyDownload_isNotCutByTheCallTimeout() = runBlocking {
        val base = HttpClients.create("RepoShelf-Test/1") {
            https.trustTestCertificate(this)
            callTimeout(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        fun slow() = MockResponse.Builder().code(200).body(body(content)).throttleBody(60_000, 250, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        // A metaadat-kliens (rövid teljes időkorláttal) a kb. 1,2 mp-es letöltést megszakítaná.
        server.enqueue(slow())
        val cut = assertIs<DownloadResult.Failed>(FileDownloader(base).download(request()))
        assertEquals(DownloadFailure.NETWORK, cut.reason)
        File(target().path + FileDownloader.PART_SUFFIX).delete()
        // A letöltőkliensen nincs teljes időkorlát, az olvasási (inaktivitási) korlát megmarad.
        val downloads = HttpClients.forDownloads(base)
        assertEquals(0, downloads.callTimeoutMillis)
        assertEquals(base.readTimeoutMillis, downloads.readTimeoutMillis)
        assertEquals(base.connectTimeoutMillis, downloads.connectTimeoutMillis)
        server.enqueue(slow())
        val done = assertIs<DownloadResult.Completed>(FileDownloader(downloads).download(request()))
        assertEquals(contentSha, done.sha256)
    }

    // ---- DL-02: a folytatás csak ugyanarra a tartalomra és a várt teljes méretre fogadható el ----

    private fun leavePartial(bytes: Int, etag: String): File {
        val part = File(target().path + FileDownloader.PART_SUFFIX)
        part.parentFile!!.mkdirs()
        part.writeBytes(content.copyOf(bytes))
        File(target().path + FileDownloader.META_SUFFIX).writeText(etag)
        return part
    }

    @Test
    fun resume_isRejected_whenTheServerAnswersWithDifferentContentOrSize() = runBlocking {
        val half = 1000
        val etag = "\"blob1\""
        // Más ETag a 206-os válaszban: a szerver nem ugyanazt a tartalmat folytatja.
        var part = leavePartial(half, etag)
        server.enqueue(
            MockResponse.Builder().code(206).body(body(content.copyOfRange(half, content.size)))
                .addHeader("Content-Range", "bytes $half-${content.size - 1}/${content.size}").addHeader("ETag", "\"blob2\"").build(),
        )
        val otherContent = assertIs<DownloadResult.Failed>(downloader.download(request()))
        assertEquals(DownloadFailure.HTTP_ERROR, otherContent.reason)
        assertFalse(otherContent.resumable)
        assertFalse(part.exists(), "a nem folytatható részfájl törlődik")
        assertFalse(File(target().path + FileDownloader.META_SUFFIX).exists())
        assertFalse(target().exists())

        // Más teljes méret a Content-Range-ben.
        part = leavePartial(half, etag)
        server.enqueue(
            MockResponse.Builder().code(206).body(body(content.copyOfRange(half, content.size)))
                .addHeader("Content-Range", "bytes $half-${content.size - 1}/${content.size + 5}").addHeader("ETag", etag).build(),
        )
        assertEquals(DownloadFailure.HTTP_ERROR, assertIs<DownloadResult.Failed>(downloader.download(request())).reason)
        assertFalse(part.exists())

        // Ismeretlen teljes méret ("/*").
        part = leavePartial(half, etag)
        server.enqueue(
            MockResponse.Builder().code(206).body(body(content.copyOfRange(half, content.size)))
                .addHeader("Content-Range", "bytes $half-${content.size - 1}/*").addHeader("ETag", etag).build(),
        )
        assertEquals(DownloadFailure.HTTP_ERROR, assertIs<DownloadResult.Failed>(downloader.download(request())).reason)
        assertFalse(part.exists())

        // A következő próbálkozás tisztán, elölről indul (Range nélkül), és sikerül.
        repeat(3) { server.takeRequest() }
        server.enqueue(MockResponse.Builder().code(200).body(body(content)).build())
        val clean = assertIs<DownloadResult.Completed>(downloader.download(request()))
        assertEquals(contentSha, clean.sha256)
        assertNull(server.takeRequest().headers["Range"])
    }

    // ---- NET-03: a nem ASCII / túl hosszú validátor olyan, mintha nem lenne ----

    @Test
    fun unsafeStoredEtag_isIgnored_andTheDownloadRestartsWithoutThrowing() = runBlocking {
        leavePartial(1000, "\"\u00e9\u0007\"")
        server.enqueue(MockResponse.Builder().code(200).body(body(content)).build())
        val result = assertIs<DownloadResult.Completed>(downloader.download(request()))
        assertEquals(contentSha, result.sha256)
        val recorded = server.takeRequest()
        assertNull(recorded.headers["Range"])
        assertNull(recorded.headers["If-Range"])
    }

    // ---- DL-04: a 0 méretű állomány megbízhatatlan bemenet, nem programhiba ----

    @Test
    fun zeroSizedAsset_isAFailure_notAnException() = runBlocking {
        val result = assertIs<DownloadResult.Failed>(downloader.download(request(expected = 0)))
        assertEquals(DownloadFailure.SIZE_MISMATCH, result.reason)
        assertEquals(0, server.requestCount)
    }

    // ---- DL-03: ugyanarra a célfájlra egyszerre csak egy letöltés fut ----

    @Test
    fun concurrentDownloadsOfTheSameTarget_doNotCorruptEachOther() = runBlocking {
        repeat(2) {
            server.enqueue(MockResponse.Builder().code(200).body(body(content)).throttleBody(100_000, 50, java.util.concurrent.TimeUnit.MILLISECONDS).build())
        }
        val results = listOf(
            async(Dispatchers.IO) { downloader.download(request()) },
            async(Dispatchers.IO) { downloader.download(request()) },
        ).map { it.await() }
        results.forEach { assertEquals(contentSha, assertIs<DownloadResult.Completed>(it).sha256) }
        assertEquals(contentSha, sha256(target().readBytes()), "a lemezen lévő fájl a jelentett tartalom")
        assertFalse(File(target().path + FileDownloader.PART_SUFFIX).exists())
    }

    // ---- MD-04: a képek mérethatára a ténylegesen beolvasott bájtokra érvényes ----

    @Test
    fun imageBodyLimit_appliesToBytesActuallyRead_notOnlyToTheDeclaredLength() {
        val client = okhttp3.OkHttpClient.Builder().apply { https.trustTestCertificate(this) }.addInterceptor(ImageBodyLimitInterceptor).build()
        val tooBig = ByteArray((ImageHttpClient.MAX_IMAGE_BYTES + 64 * 1024).toInt())
        // Content-Length nélküli válasz: a deklarált hossz ellenőrzése nem fogná meg.
        server.enqueue(MockResponse.Builder().code(200).body(body(tooBig)).removeHeader("Content-Length").build())
        val failure = kotlin.runCatching { client.newCall(okhttp3.Request.Builder().url(server.url("/big.png")).build()).execute().use { it.body.bytes() } }
        assertTrue(failure.exceptionOrNull() is java.io.IOException, failure.toString())
        server.enqueue(MockResponse.Builder().code(200).body(body(content)).removeHeader("Content-Length").build())
        val ok = client.newCall(okhttp3.Request.Builder().url(server.url("/small.png")).build()).execute().use { it.body.bytes() }
        assertEquals(content.size, ok.size, "a kis kép teljes egészében megérkezik")
        assertTrue(ok.contentEquals(content))
    }

    // ---- MD-03: a képkérés minden lépése megbízható hosztra megy, hacsak a felhasználó nem járult hozzá ----

    private fun imageClient(trusted: (String) -> Boolean) = okhttp3.OkHttpClient.Builder().apply { https.trustTestCertificate(this) }
        .followRedirects(true).addNetworkInterceptor(ImageHostInterceptor(trusted)).build()

    @Test
    fun imageRequests_stayOnTrustedHosts_unlessTheUserConsented() {
        assertTrue(ImageHttpClient.isGitHubHost("github.com") && ImageHttpClient.isGitHubHost("RAW.githubusercontent.com"))
        assertTrue(ImageHttpClient.isGitHubHost("private-user-images.githubusercontent.com"))
        assertFalse(ImageHttpClient.isGitHubHost("evilgithubusercontent.com") || ImageHttpClient.isGitHubHost("github.com.evil.example") || ImageHttpClient.isGitHubHost("githubusercontent.com"))

        // Nem megbízható hoszt hozzájárulás nélkül: a kérés ki sem megy.
        val untrusted = kotlin.runCatching { imageClient { false }.newCall(okhttp3.Request.Builder().url(server.url("/a.png")).build()).execute().close() }
        assertTrue(untrusted.exceptionOrNull() is java.io.IOException, untrusted.toString())
        assertEquals(0, server.requestCount)

        // Megbízható hosztról induló kérés, amely nem megbízható hosztra irányít át: a második lépés nem megy ki.
        // A szerver neve platformfüggő (Windows: „127.0.0.1”, Linux: „localhost”); az átirányítás célja a másik név,
        // ugyanarra a szerverre (a teszt-tanúsítvány mindkettőre érvényes).
        val trustedHost = server.url("/").host
        val otherHost = if (trustedHost == "127.0.0.1") "localhost" else "127.0.0.1"
        val other = "https://$otherHost:${server.port}/tracker.png"
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", other).build())
        val redirected = kotlin.runCatching { imageClient { it == trustedHost }.newCall(okhttp3.Request.Builder().url(server.url("/b.png")).build()).execute().close() }
        assertTrue(redirected.exceptionOrNull() is java.io.IOException, redirected.toString())
        assertEquals(1, server.requestCount, "az átirányítás célja nem kapott kérést")
        server.takeRequest()

        // A felhasználó hozzájárulásával a kérés (és az átirányítása) kimegy, de a belső jelölő soha nem jut el a szerverhez.
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", other).build())
        server.enqueue(MockResponse.Builder().code(200).body(body(content)).build())
        val consented = okhttp3.Request.Builder().url(server.url("/c.png")).header(ImageHttpClient.EXTERNAL_CONSENT_HEADER, "1").build()
        imageClient { false }.newCall(consented).execute().use { assertEquals(200, it.code) }
        repeat(2) { assertNull(server.takeRequest().headers[ImageHttpClient.EXTERNAL_CONSENT_HEADER]) }
    }
}
