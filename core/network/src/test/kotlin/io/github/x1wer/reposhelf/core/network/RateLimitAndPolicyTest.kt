// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.network

import io.github.x1wer.reposhelf.core.network.testing.MutableClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.headersOf
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RateLimitAndPolicyTest {

    private val now = Instant.parse("2026-11-10T10:00:00Z")

    @Test
    fun tracker_parsesHeaders_andKeepsReserve() {
        val clock = MutableClock(now)
        val tracker = RateLimitTracker(clock)
        tracker.record(
            "github",
            headersOf(
                "x-ratelimit-limit", "60", "x-ratelimit-remaining", "16",
                "x-ratelimit-reset", now.plusSeconds(600).epochSecond.toString(), "x-ratelimit-resource", "core",
            ),
        )
        val snapshot = tracker.snapshot("github")!!
        assertEquals(60, snapshot.limit)
        assertEquals(16, snapshot.remaining)
        assertEquals("core", snapshot.resource)
        assertTrue(tracker.hasBudget("github", reserve = 15))
        assertFalse(tracker.hasBudget("github", reserve = 16))
        // A reset után a keret ismét rendelkezésre áll.
        clock.advance(Duration.ofSeconds(601))
        assertTrue(tracker.hasBudget("github", reserve = 16))
    }

    @Test
    fun tracker_ignoresResponsesWithoutRateLimitHeaders() {
        val tracker = RateLimitTracker(MutableClock(now))
        tracker.record("github", headersOf("Content-Type", "application/json"))
        assertNull(tracker.snapshot("github"))
        assertTrue(tracker.hasBudget("github", reserve = 100))
    }

    @Test
    fun tracker_blockExpires() {
        val clock = MutableClock(now)
        val tracker = RateLimitTracker(clock)
        tracker.blockUntil("github", now.plusSeconds(60))
        assertEquals(now.plusSeconds(60), tracker.blockedUntil("github"))
        // Egy rövidebb tiltás nem írja felül a hosszabbat.
        tracker.blockUntil("github", now.plusSeconds(10))
        assertEquals(now.plusSeconds(60), tracker.blockedUntil("github"))
        clock.advance(Duration.ofSeconds(61))
        assertNull(tracker.blockedUntil("github"))
    }

    @Test
    fun decision_capsAbsurdResetValues() {
        val headers = headersOf("x-ratelimit-remaining", "0", "x-ratelimit-reset", now.plusSeconds(86_400 * 30).epochSecond.toString())
        assertEquals(now.plus(RateLimitDecision.MAX_BLOCK), RateLimitDecision.blockedUntil(403, headers, null, now))
    }

    @Test
    fun decision_notRateLimitedFor404OrPlain403() {
        assertNull(RateLimitDecision.blockedUntil(404, headersOf(), null, now))
        assertNull(RateLimitDecision.blockedUntil(403, headersOf(), "Forbidden", now))
    }

    @Test
    fun decision_acceptsHttpDateRetryAfter() {
        val headers = headersOf("Retry-After", "Tue, 10 Nov 2026 10:05:00 GMT")
        assertEquals(Instant.parse("2026-11-10T10:05:00Z"), RateLimitDecision.blockedUntil(429, headers, null, now))
    }

    @Test
    fun retryPolicy_isExponentialAndCapped() {
        val policy = RetryPolicy(maxAttempts = 10, baseDelay = 1.seconds, maxDelay = 8.seconds)
        val random = Random(7)
        val d1 = policy.delayBeforeRetry(1, random)
        val d4 = policy.delayBeforeRetry(4, random)
        val d9 = policy.delayBeforeRetry(9, random)
        assertTrue(d1 >= 500.milliseconds && d1 <= 1.seconds, "1. várakozás: $d1")
        assertTrue(d4 >= 4.seconds && d4 <= 8.seconds, "4. várakozás: $d4")
        assertTrue(d9 <= 8.seconds, "felső korlát: $d9")
    }

    @Test
    fun hostPattern_wildcardMatchesOnlyRealSubdomains() {
        val pattern = HostPattern.parse("*.githubusercontent.com")
        assertTrue(pattern.matches("raw.githubusercontent.com"))
        assertTrue(pattern.matches("RELEASE-ASSETS.githubusercontent.com"))
        assertFalse(pattern.matches("githubusercontent.com"))
        assertFalse(pattern.matches("evilgithubusercontent.com"))
        assertFalse(pattern.matches("raw.githubusercontent.com.evil.example"))
        val exact = HostPattern.parse("api.github.com")
        assertTrue(exact.matches("API.GITHUB.COM"))
        assertFalse(exact.matches("xapi.github.com"))
    }

    @Test
    fun concurrencyLimiter_limitsParallelism() = runBlocking {
        val limiter = ConcurrencyLimiter(2)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        (1..6).map {
            async {
                limiter.withPermit {
                    val current = active.incrementAndGet()
                    maxActive.accumulateAndGet(current, ::maxOf)
                    delay(20)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(2, maxActive.get())
    }
}
