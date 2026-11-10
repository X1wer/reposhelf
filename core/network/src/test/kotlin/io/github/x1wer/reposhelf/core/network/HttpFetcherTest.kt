// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.network

import io.github.x1wer.reposhelf.core.network.testing.HttpsTestServer
import io.github.x1wer.reposhelf.core.network.testing.MutableClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class HttpFetcherTest {

    private lateinit var https: HttpsTestServer
    private val clock = MutableClock(Instant.parse("2026-11-10T10:00:00Z"))
    private lateinit var tracker: RateLimitTracker
    private val sleeps = mutableListOf<Duration>()
    private lateinit var fetcher: HttpFetcher

    @Before
    fun setUp() {
        https = HttpsTestServer()
        val client = HttpClients.create("RepoShelf-Test/1") {
            https.trustTestCertificate(this)
            readTimeout(500, TimeUnit.MILLISECONDS)
        }
        tracker = RateLimitTracker(clock)
        fetcher = HttpFetcher(
            client = client,
            rateLimits = tracker,
            limiter = ConcurrencyLimiter(2),
            clock = clock,
            ioDispatcher = Dispatchers.IO,
            random = Random(42),
            sleep = { sleeps += it },
        )
    }

    @After
    fun tearDown() {
        https.close()
    }

    private val server get() = https.server

    private fun policy(maxBody: Long = 1024, maxRedirects: Int = 5) =
        HttpPolicy.of(https.host, maxBodyBytes = maxBody, maxRedirects = maxRedirects)

    private fun request(path: String = "/resource", validators: Validators? = null, scope: String? = "github", policy: HttpPolicy = policy()) =
        FetchRequest(url = server.url(path), policy = policy, validators = validators, rateLimitScope = scope)

    @Test
    fun success_returnsBodyAndValidators() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body("hello").addHeader("ETag", "\"abc\"")
                .addHeader("Last-Modified", "Mon, 09 Nov 2026 10:00:00 GMT").build(),
        )
        val result = fetcher.get(request())
        val success = expect<FetchResponse.Success>(result)
        assertEquals("hello", String(success.body))
        assertEquals("\"abc\"", success.validators.etag)
        assertEquals("Mon, 09 Nov 2026 10:00:00 GMT", success.validators.lastModified)
        assertEquals("RepoShelf-Test/1", server.takeRequest().headers["User-Agent"])
    }

    @Test
    fun conditionalRequest_sendsValidators_andMaps304ToNotModified() = runBlocking {
        server.enqueue(MockResponse.Builder().code(304).build())
        val result = fetcher.get(request(validators = Validators("\"abc\"", "Mon, 09 Nov 2026 10:00:00 GMT")))
        val notModified = expect<FetchResponse.NotModified>(result)
        assertEquals("\"abc\"", notModified.validators.etag)
        val recorded = server.takeRequest()
        assertEquals("\"abc\"", recorded.headers["If-None-Match"])
        assertEquals("Mon, 09 Nov 2026 10:00:00 GMT", recorded.headers["If-Modified-Since"])
    }

    @Test
    fun primaryRateLimit_blocksScopeUntilReset_withoutFurtherRequests() = runBlocking {
        val reset = clock.instant().plusSeconds(1200).epochSecond
        server.enqueue(
            MockResponse.Builder().code(403).body("API rate limit exceeded")
                .addHeader("x-ratelimit-limit", "60").addHeader("x-ratelimit-remaining", "0")
                .addHeader("x-ratelimit-reset", reset.toString()).build(),
        )
        val first = expect<FetchResponse.RateLimited>(fetcher.get(request()))
        assertEquals(Instant.ofEpochSecond(reset + 1), first.until)

        // Rate-limitelt állapotban semmilyen kérés nem mehet ki (docs/RESEARCH.md §6).
        val second = fetcher.get(request())
        expect<FetchResponse.RateLimited>(second)
        assertEquals(1, server.requestCount)
        assertTrue(!tracker.hasBudget("github", reserve = 0))

        // A tiltás lejárta után újra mehet kérés.
        clock.advance(java.time.Duration.ofSeconds(1300))
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        expect<FetchResponse.Success>(fetcher.get(request()))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun tooManyRequests_respectsRetryAfter() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "30").build())
        val result = expect<FetchResponse.RateLimited>(fetcher.get(request()))
        assertEquals(clock.instant().plusSeconds(30), result.until)
        assertTrue(sleeps.isEmpty(), "Rate limit esetén nincs helyben várakozó újrapróbálkozás")
    }

    @Test
    fun secondaryRateLimit_withoutHeaders_waitsAtLeastOneMinute() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).body("You have exceeded a secondary rate limit.").build())
        val result = expect<FetchResponse.RateLimited>(fetcher.get(request()))
        assertEquals(clock.instant().plusSeconds(60), result.until)
    }

    @Test
    fun forbiddenWithoutRateLimitSignal_isHttpError() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).body("Repository access blocked").build())
        val result = expect<FetchResponse.HttpError>(fetcher.get(request()))
        assertEquals(403, result.code)
        assertEquals("Repository access blocked", result.bodySnippet)
        assertNull(tracker.blockedUntil("github"))
    }

    @Test
    fun serverError_isRetriedWithBackoff_thenSucceeds() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(502).build())
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        expect<FetchResponse.Success>(fetcher.get(request()))
        assertEquals(3, server.requestCount)
        assertEquals(2, sleeps.size)
        assertTrue(sleeps[1] >= sleeps[0] / 2, "A második várakozás nem lehet sokkal rövidebb (exponenciális)")
    }

    @Test
    fun serverError_afterMaxAttempts_isReturned() = runBlocking {
        repeat(3) { server.enqueue(MockResponse.Builder().code(500).build()) }
        val result = expect<FetchResponse.HttpError>(fetcher.get(request()))
        assertEquals(500, result.code)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun notFound_isNotRetried() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("Not Found").build())
        val result = expect<FetchResponse.HttpError>(fetcher.get(request()))
        assertEquals(404, result.code)
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun timeout_isRetried_thenNetworkFailure() = runBlocking {
        repeat(3) {
            server.enqueue(MockResponse.Builder().code(200).body("late").headersDelay(2, TimeUnit.SECONDS).build())
        }
        expect<FetchResponse.NetworkFailure>(fetcher.get(request()))
        assertEquals(2, sleeps.size)
    }

    @Test
    fun bodyLargerThanLimit_isRejected() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat(2048)).build())
        val result = expect<FetchResponse.PolicyViolation>(fetcher.get(request()))
        assertEquals(FetchResponse.Violation.BODY_TOO_LARGE, result.reason)
    }

    @Test
    fun plainHttpUrl_isRejectedWithoutAnyRequest() = runBlocking {
        val insecure = FetchRequest("http://${https.host}:${server.port}/x".toHttpUrl(), policy())
        val result = expect<FetchResponse.PolicyViolation>(fetcher.get(insecure))
        assertEquals(FetchResponse.Violation.INSECURE_URL, result.reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun hostOutsideAllowList_isRejectedWithoutAnyRequest() = runBlocking {
        val other = FetchRequest(server.url("/x"), HttpPolicy.of("api.github.com", maxBodyBytes = 1024))
        val result = expect<FetchResponse.PolicyViolation>(fetcher.get(other))
        assertEquals(FetchResponse.Violation.HOST_NOT_ALLOWED, result.reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun redirectWithinAllowList_isFollowed() = runBlocking {
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "/moved").build())
        server.enqueue(MockResponse.Builder().code(200).body("moved-content").build())
        val result = expect<FetchResponse.Success>(fetcher.get(request()))
        assertEquals("/moved", result.finalUrl.encodedPath)
        assertEquals("moved-content", String(result.body))
    }

    @Test
    fun redirectToPlainHttp_isRejected() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://${https.host}:${server.port}/x").build())
        val result = expect<FetchResponse.PolicyViolation>(fetcher.get(request()))
        assertEquals(FetchResponse.Violation.INSECURE_URL, result.reason)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun redirectToOtherHost_isRejected() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://evil.example/x").build())
        val result = expect<FetchResponse.PolicyViolation>(fetcher.get(request()))
        assertEquals(FetchResponse.Violation.HOST_NOT_ALLOWED, result.reason)
    }

    @Test
    fun redirectLoop_isStopped() = runBlocking {
        repeat(3) { server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/again").build()) }
        val result = expect<FetchResponse.PolicyViolation>(fetcher.get(request(policy = policy(maxRedirects = 2))))
        assertEquals(FetchResponse.Violation.TOO_MANY_REDIRECTS, result.reason)
    }

    @Test
    fun authorizationHeader_isNeverSentToAnotherHost() {
        val original = FetchRequest(
            url = "https://api.github.com/repos/a/b".toHttpUrl(),
            policy = HttpPolicy.of("api.github.com", "*.githubusercontent.com", maxBodyBytes = 1024),
            headers = mapOf("Authorization" to "Bearer secret", "Accept" to "application/json"),
        )
        val sameHost = fetcher.buildRequest(original, "https://api.github.com/repositories/1".toHttpUrl())
        assertEquals("Bearer secret", sameHost.header("Authorization"))
        val otherHost = fetcher.buildRequest(original, "https://objects.githubusercontent.com/x".toHttpUrl())
        assertNull(otherHost.header("Authorization"))
        assertEquals("application/json", otherHost.header("Accept"))
    }
}

/** Mint az `assertIs`, de hálózati hibánál a kiváltó okot is kiírja. */
internal inline fun <reified T : FetchResponse> expect(result: FetchResponse): T {
    val detail = (result as? FetchResponse.NetworkFailure)?.cause?.let { "${it::class.java.name}: ${it.message}" } ?: result.toString()
    return assertIs<T>(result, "Váratlan eredmény: $detail")
}
