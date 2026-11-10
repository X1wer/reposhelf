// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import okio.Buffer
import java.io.IOException
import java.time.Clock
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Az egyidejű kérések számának korlátja (C. 16.). */
class ConcurrencyLimiter(permits: Int) {
    private val semaphore = Semaphore(permits)
    suspend fun <T> withPermit(block: suspend () -> T): T = semaphore.withPermit { block() }
}

/** Exponenciális visszavárakozás véletlenszerű szórással („equal jitter”). */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelay: Duration = 1.seconds,
    val maxDelay: Duration = 30.seconds,
) {
    init {
        require(maxAttempts >= 1) { "Legalább egy próbálkozás kell" }
    }

    /** A [failedAttempt]-edik sikertelen próbálkozás utáni várakozás: (base·2^(n−1)) / 2 + véletlen [0, fele]. */
    fun delayBeforeRetry(failedAttempt: Int, random: Random): Duration {
        val exponent = (failedAttempt - 1).coerceIn(0, 20)
        val raw = baseDelay * (1 shl exponent)
        val capped = if (raw > maxDelay) maxDelay else raw
        val half = capped / 2
        return half + half * random.nextDouble()
    }

    companion object {
        val DEFAULT = RetryPolicy()
        val NONE = RetryPolicy(maxAttempts = 1)
    }
}

/** Egy GET-kérés leírása. */
data class FetchRequest(
    val url: HttpUrl,
    val policy: HttpPolicy,
    val headers: Map<String, String> = emptyMap(),
    val validators: Validators? = null,
    /** Ha nem `null`, a válasz rate-limit fejlécei ebben a hatókörben rögzülnek, és a tiltás is itt érvényes. */
    val rateLimitScope: String? = null,
    val retry: RetryPolicy = RetryPolicy.DEFAULT,
)

/** A kérés kimenete. A hívó ebből képez szolgáltató-specifikus eredményt. */
sealed interface FetchResponse {
    data class Success(
        val code: Int,
        val body: ByteArray,
        val validators: Validators,
        val headers: Headers,
        val finalUrl: HttpUrl,
    ) : FetchResponse {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    data class NotModified(val validators: Validators) : FetchResponse

    /** Nem átmeneti HTTP-hiba (pl. 404, 410, 451, nem rate-limit jellegű 403), vagy a próbálkozások után is fennálló 5xx. */
    data class HttpError(val code: Int, val bodySnippet: String?) : FetchResponse

    data class RateLimited(val until: Instant?) : FetchResponse

    data class NetworkFailure(val cause: IOException) : FetchResponse

    data class PolicyViolation(val reason: Violation) : FetchResponse

    enum class Violation { INSECURE_URL, HOST_NOT_ALLOWED, TOO_MANY_REDIRECTS, INVALID_REDIRECT, BODY_TOO_LARGE }
}

/**
 * Az alkalmazás egyetlen HTTP-belépési pontja a metaadat-kérésekhez (katalógus, szolgáltatói API).
 *
 * Biztonsági szabályok (docs/ARCHITECTURE.md 6. pont):
 * - csak HTTPS és csak engedélyezett hosztok, az átirányítás minden lépésében is;
 * - hoszton kívüli átirányításnál az `Authorization` fejléc nem megy tovább;
 * - méretkorlát a törzsre;
 * - rate-limitelt hatókörben semmilyen kérés nem megy ki.
 *
 * Újrapróbálkozás csak átmeneti hibánál van (I/O, 408, 5xx), exponenciális visszavárakozással.
 */
class HttpFetcher(
    private val client: OkHttpClient,
    private val rateLimits: RateLimitTracker,
    private val limiter: ConcurrencyLimiter,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val random: Random = Random.Default,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
) {

    suspend fun get(request: FetchRequest): FetchResponse {
        if (!request.url.isHttps) return FetchResponse.PolicyViolation(FetchResponse.Violation.INSECURE_URL)
        if (!request.policy.isHostAllowed(request.url.host)) {
            return FetchResponse.PolicyViolation(FetchResponse.Violation.HOST_NOT_ALLOWED)
        }
        request.rateLimitScope?.let { scope ->
            rateLimits.blockedUntil(scope)?.let { return FetchResponse.RateLimited(it) }
        }

        var attempt = 1
        while (true) {
            val outcome: Attempt = try {
                limiter.withPermit { withContext(ioDispatcher) { executeOnce(request) } }
            } catch (e: IOException) {
                Attempt.Retryable(FetchResponse.NetworkFailure(e))
            }
            when (outcome) {
                is Attempt.Done -> return outcome.response
                is Attempt.Retryable -> if (attempt >= request.retry.maxAttempts) return outcome.response
            }
            sleep(request.retry.delayBeforeRetry(attempt, random))
            attempt++
        }
    }

    private sealed interface Attempt {
        val response: FetchResponse

        data class Done(override val response: FetchResponse) : Attempt
        data class Retryable(override val response: FetchResponse) : Attempt
    }

    private suspend fun executeOnce(request: FetchRequest): Attempt {
        var url = request.url
        var redirects = 0
        while (true) {
            val response = client.newCall(buildRequest(request, url)).executeAsync()
            val next: HttpUrl = response.use { r ->
                request.rateLimitScope?.let { rateLimits.record(it, r.headers) }
                if (r.code in REDIRECT_CODES) {
                    val location = r.header("Location")
                        ?: return Attempt.Done(FetchResponse.PolicyViolation(FetchResponse.Violation.INVALID_REDIRECT))
                    val target = url.resolve(location)
                        ?: return Attempt.Done(FetchResponse.PolicyViolation(FetchResponse.Violation.INVALID_REDIRECT))
                    if (!target.isHttps) return Attempt.Done(FetchResponse.PolicyViolation(FetchResponse.Violation.INSECURE_URL))
                    if (!request.policy.isHostAllowed(target.host)) {
                        return Attempt.Done(FetchResponse.PolicyViolation(FetchResponse.Violation.HOST_NOT_ALLOWED))
                    }
                    redirects++
                    if (redirects > request.policy.maxRedirects) {
                        return Attempt.Done(FetchResponse.PolicyViolation(FetchResponse.Violation.TOO_MANY_REDIRECTS))
                    }
                    target
                } else {
                    return handleFinal(request, r)
                }
            }
            url = next
        }
    }

    internal fun buildRequest(request: FetchRequest, url: HttpUrl): Request {
        val sameHostAsOriginal = url.host.equals(request.url.host, ignoreCase = true)
        val builder = Request.Builder().url(url).get()
        for ((name, value) in request.headers) {
            // Hitelesítő adat soha nem megy át másik hosztra (docs/ARCHITECTURE.md 6. pont).
            if (name.equals("Authorization", ignoreCase = true) && !sameHostAsOriginal) continue
            builder.header(name, value)
        }
        request.validators?.etag?.let { builder.header("If-None-Match", it) }
        request.validators?.lastModified?.let { builder.header("If-Modified-Since", it) }
        return builder.build()
    }

    private fun handleFinal(request: FetchRequest, r: Response): Attempt {
        val validators = Validators(r.header("ETag"), r.header("Last-Modified"))
        return when {
            r.code == 304 -> Attempt.Done(
                FetchResponse.NotModified(if (validators.isEmpty) request.validators ?: Validators.NONE else validators),
            )
            r.isSuccessful -> {
                val body = readLimited(r, request.policy.maxBodyBytes)
                    ?: return Attempt.Done(FetchResponse.PolicyViolation(FetchResponse.Violation.BODY_TOO_LARGE))
                Attempt.Done(FetchResponse.Success(r.code, body, validators, r.headers, r.request.url))
            }
            r.code == 403 || r.code == 429 -> {
                val snippet = readSnippet(r)
                val until = RateLimitDecision.blockedUntil(r.code, r.headers, snippet, clock.instant())
                if (until != null) {
                    request.rateLimitScope?.let { rateLimits.blockUntil(it, until) }
                    Attempt.Done(FetchResponse.RateLimited(until))
                } else {
                    Attempt.Done(FetchResponse.HttpError(r.code, snippet))
                }
            }
            r.code in RETRYABLE_CODES -> Attempt.Retryable(FetchResponse.HttpError(r.code, null))
            else -> Attempt.Done(FetchResponse.HttpError(r.code, readSnippet(r)))
        }
    }

    /** A törzset legfeljebb [max] bájtig olvassa be; ha nagyobb, `null` (a kapcsolat ekkor lezárul). */
    private fun readLimited(r: Response, max: Long): ByteArray? {
        val body = r.body
        val declared = body.contentLength()
        if (declared > max) return null
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(buffer, SEGMENT)
            if (read == -1L) break
            total += read
            if (total > max) return null
        }
        return buffer.readByteArray()
    }

    /** Diagnosztikai célú, rövid, nyomtatható részlet a hibaválaszból (legfeljebb 200 karakter). */
    private fun readSnippet(r: Response): String? = try {
        val bytes = readLimited(r, SNIPPET_BYTES) ?: return null
        String(bytes, Charsets.UTF_8).take(200).filter { it == ' ' || !it.isISOControl() }
    } catch (_: IOException) {
        null
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val RETRYABLE_CODES = setOf(408, 500, 502, 503, 504)
        const val SEGMENT = 8192L
        const val SNIPPET_BYTES = 4096L
    }
}
