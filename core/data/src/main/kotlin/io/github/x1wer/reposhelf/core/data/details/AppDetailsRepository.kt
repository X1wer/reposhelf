// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.details

import io.github.x1wer.reposhelf.core.model.Readme
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.RepoMetadata
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.network.Validators
import io.github.x1wer.reposhelf.core.source.Fetch
import io.github.x1wer.reposhelf.core.source.ProviderRegistry
import io.github.x1wer.reposhelf.core.source.SourceError
import io.github.x1wer.reposhelf.core.source.UnavailableReason
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

sealed interface DetailsError {
    data object NotFound : DetailsError
    data class Unavailable(val reason: UnavailableReason) : DetailsError
    data class RateLimited(val until: Instant?) : DetailsError
    data object Network : DetailsError
    data class Other(val description: String) : DetailsError
}

/** Egy lekért adat, az esetleges hibával együtt. Hiba esetén a korábbi (elavult) érték is megmaradhat. */
data class Loaded<T>(val value: T?, val error: DetailsError?, val fetchedAt: Instant?)

data class AppDetails(val repo: Loaded<RepoMetadata>, val releases: Loaded<List<Release>>, val readme: Loaded<Readme>)

/**
 * Az adatlap GitHub-adatai, memóriában gyorsítótárazva (C. 16.1.: ne töltse le újra minden megnyitáskor).
 *
 * A névtelen API-keret IP-címenként 60 kérés/óra, és a 304 is fogy belőle (docs/RESEARCH.md §6). Ezért [ttl]-en belül
 * egyáltalán nem kérdezünk le újra; utána feltételes kérés megy (ETag). Egy adatlap megnyitása legfeljebb 3 kérés.
 * A tartós (offline) gyorsítótár a 7. munkamenet feladata.
 */
class AppDetailsRepository(
    private val providers: ProviderRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMinutes(30),
) {
    private class Entry<T>(val value: T?, val validators: Validators?, val fetchedAt: Instant?, val error: DetailsError?)

    private val cache = ConcurrentHashMap<String, Entry<*>>()
    private val mutex = Mutex()

    suspend fun load(ref: RepoRef, force: Boolean = false): AppDetails = mutex.withLock {
        val provider = providers.get(ref.provider) ?: return AppDetails(
            Loaded(null, DetailsError.Other("provider"), null), Loaded(null, DetailsError.Other("provider"), null), Loaded(null, DetailsError.Other("provider"), null),
        )
        // A kiadások a legfontosabbak (telepítés), ezért azok mennek elsőként, ha a keret szűkös.
        val releases = cached("releases:${ref.key}", force) { v -> provider.releases(ref, v, limit = 15) }
        val repo = cached("repo:${ref.key}", force) { v -> provider.repository(ref, v) }
        val readme = cached("readme:${ref.key}", force) { v -> provider.readme(ref, v) }
        AppDetails(repo, releases, readme)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> cached(key: String, force: Boolean, fetch: suspend (Validators?) -> Fetch<T>): Loaded<T> {
        val now = clock.instant()
        val previous = cache[key] as Entry<T>?
        if (!force && previous?.fetchedAt != null && previous.error == null && Duration.between(previous.fetchedAt, now) < ttl) {
            return Loaded(previous.value, null, previous.fetchedAt)
        }
        val entry: Entry<T> = when (val result = fetch(previous?.validators)) {
            is Fetch.Fresh -> Entry(result.value, result.validators, now, null)
            Fetch.NotModified -> Entry(previous?.value, previous?.validators, now, null)
            Fetch.NotFound -> Entry(null, null, now, DetailsError.NotFound)
            is Fetch.Unavailable -> Entry(null, null, now, DetailsError.Unavailable(result.reason))
            is Fetch.RateLimited -> Entry(previous?.value, previous?.validators, previous?.fetchedAt, DetailsError.RateLimited(result.until))
            is Fetch.Failed -> Entry(
                previous?.value, previous?.validators, previous?.fetchedAt,
                when (val e = result.error) {
                    is SourceError.Network -> DetailsError.Network
                    is SourceError.Http -> DetailsError.Other("HTTP ${e.code}")
                    SourceError.InvalidResponse -> DetailsError.Other("invalid response")
                    is SourceError.Policy -> DetailsError.Other(e.violation.name)
                },
            )
        }
        cache[key] = entry
        return Loaded(entry.value, entry.error, entry.fetchedAt)
    }
}
