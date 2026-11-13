// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Headers
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Egy szolgáltató (pl. `github`) rate-limit állapota, a válaszfejlécekből (`x-ratelimit-*`). */
data class RateLimitSnapshot(
    val limit: Int?,
    val remaining: Int?,
    val resetAt: Instant?,
    val resource: String?,
    val observedAt: Instant,
    /** Eddig az időpontig egyetlen kérés sem mehet ki ebben a hatókörben (GitHub-ajánlás). */
    val blockedUntil: Instant?,
)

/**
 * A rate-limit állapot nyilvántartása hatókörönként. A [state] a felületnek szól („mikor folytatódik”, C. 16.1.).
 * A `remaining` csak tájékoztató érték: a GitHub több régióban dolgozik, így ingadozhat (docs/RESEARCH.md §6).
 */
class RateLimitTracker(private val clock: Clock) {

    private val _state = MutableStateFlow<Map<String, RateLimitSnapshot>>(emptyMap())
    val state: StateFlow<Map<String, RateLimitSnapshot>> = _state.asStateFlow()

    fun record(scope: String, headers: Headers) {
        val limit = headers["x-ratelimit-limit"]?.trim()?.toIntOrNull()
        val remaining = headers["x-ratelimit-remaining"]?.trim()?.toIntOrNull()
        val reset = headers["x-ratelimit-reset"]?.trim()?.toLongOrNull()?.let(::epochSecondOrNull)
        if (limit == null && remaining == null && reset == null) return
        val now = clock.instant()
        _state.update { map ->
            val previous = map[scope]
            map + (scope to RateLimitSnapshot(
                limit = limit,
                remaining = remaining,
                resetAt = reset,
                resource = headers["x-ratelimit-resource"],
                observedAt = now,
                blockedUntil = previous?.blockedUntil?.takeIf { it.isAfter(now) },
            ))
        }
    }

    fun blockUntil(scope: String, until: Instant) {
        val now = clock.instant()
        _state.update { map ->
            val previous = map[scope]
            val merged = previous?.blockedUntil?.takeIf { it.isAfter(until) } ?: until
            map + (scope to (previous?.copy(blockedUntil = merged)
                ?: RateLimitSnapshot(null, null, null, null, now, merged)))
        }
    }

    /** Ha a hatókör le van tiltva, a tiltás vége; különben `null`. */
    fun blockedUntil(scope: String): Instant? {
        val until = _state.value[scope]?.blockedUntil ?: return null
        return until.takeIf { clock.instant().isBefore(it) }
    }

    /**
     * Van-e még keret úgy, hogy legalább [reserve] kérés megmaradjon (pl. az interaktív képernyőknek).
     * Ismeretlen állapotban `true`; lejárt reset után is `true`.
     */
    fun hasBudget(scope: String, reserve: Int): Boolean {
        if (blockedUntil(scope) != null) return false
        val snapshot = _state.value[scope] ?: return true
        val remaining = snapshot.remaining ?: return true
        val reset = snapshot.resetAt
        if (reset != null && !clock.instant().isBefore(reset)) return true
        return remaining > reserve
    }

    fun snapshot(scope: String): RateLimitSnapshot? = _state.value[scope]
}

/**
 * Megállapítja, hogy egy 403-as vagy 429-es válasz rate limitet jelez-e, és ha igen, meddig kell várni
 * (GitHub best practices, docs/RESEARCH.md §6):
 * - ha van `Retry-After`, annyi másodperc;
 * - ha `x-ratelimit-remaining == 0`, a `x-ratelimit-reset`-ig;
 * - egyébként (secondary limit) legalább 60 másodperc.
 *
 * A várakozás felső korlátja [MAX_BLOCK], hogy egy hibás fejléc ne tiltson le órákra.
 */
/**
 * Epoch-másodperc → időpont. Az értelmes tartományon (2000–2100) kívüli érték `null`: a fejléc megbízhatatlan bemenet,
 * és egy szélsőséges szám (`Long.MAX_VALUE`) kivételt dobna az átalakításnál.
 */
internal fun epochSecondOrNull(seconds: Long): Instant? =
    if (seconds in 946_684_800L..4_102_444_800L) Instant.ofEpochSecond(seconds) else null

object RateLimitDecision {
    val SECONDARY_DEFAULT: Duration = Duration.ofSeconds(60)
    val MAX_BLOCK: Duration = Duration.ofHours(2)

    fun blockedUntil(code: Int, headers: Headers, bodySnippet: String?, now: Instant): Instant? {
        if (code != 403 && code != 429) return null
        val retryAfter = parseRetryAfter(headers["Retry-After"], now)
        val remaining = headers["x-ratelimit-remaining"]?.trim()?.toIntOrNull()
        val reset = headers["x-ratelimit-reset"]?.trim()?.toLongOrNull()?.let(::epochSecondOrNull)
        val until = when {
            retryAfter != null -> retryAfter
            remaining == 0 && reset != null -> maxOf(reset.plusSeconds(1), now.plusSeconds(1))
            remaining == 0 -> now.plus(SECONDARY_DEFAULT)
            code == 429 -> now.plus(SECONDARY_DEFAULT)
            bodySnippet != null && bodySnippet.contains("rate limit", ignoreCase = true) -> now.plus(SECONDARY_DEFAULT)
            else -> return null
        }
        val max = now.plus(MAX_BLOCK)
        return if (until.isAfter(max)) max else until
    }

    private fun parseRetryAfter(value: String?, now: Instant): Instant? {
        val v = value?.trim() ?: return null
        // A túl nagy értéket a legnagyobb tiltási időre vágjuk (a fejléc megbízhatatlan; a túlcsordulás kivételt dobna).
        v.toLongOrNull()?.let { seconds -> return if (seconds >= 0) now.plusSeconds(seconds.coerceAtMost(MAX_BLOCK.seconds)) else null }
        // HTTP-dátum formátum is megengedett (RFC 9110).
        return Headers.headersOf("Retry-After", v).getInstant("Retry-After")?.takeIf { it.isAfter(now) }
    }
}
