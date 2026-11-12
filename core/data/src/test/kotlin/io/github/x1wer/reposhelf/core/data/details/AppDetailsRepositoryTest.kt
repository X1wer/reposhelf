// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.details

import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.Readme
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.RepoMetadata
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.network.Validators
import io.github.x1wer.reposhelf.core.network.testing.MutableClock
import io.github.x1wer.reposhelf.core.source.Fetch
import io.github.x1wer.reposhelf.core.source.ProviderRegistry
import io.github.x1wer.reposhelf.core.source.SourceProvider
import kotlinx.coroutines.runBlocking
import io.github.x1wer.reposhelf.core.model.ReleaseAsset
import io.github.x1wer.reposhelf.core.source.SourceError
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AppDetailsRepositoryTest {

    private val ref = RepoRef(ProviderId.GITHUB, "owner", "app")
    private val meta = RepoMetadata(ref, ProviderRepoId(1), 2, "desc", "GPL-3.0", "main", "https://github.com/owner/app", false, false, false)
    private val clock = MutableClock(Instant.parse("2026-11-10T10:00:00Z"))

    /** Hamis szolgáltató: számolja a hívásokat, és a megadott válaszokat adja. */
    private class FakeProvider : SourceProvider {
        override val id = ProviderId.GITHUB
        var calls = 0
        var repoResult: Fetch<RepoMetadata>? = null
        val validatorsSeen = mutableListOf<Validators?>()
        override fun parseRepoUrl(input: String): RepoRef? = null
        override suspend fun repository(ref: RepoRef, validators: Validators?): Fetch<RepoMetadata> {
            calls++
            validatorsSeen += validators
            return checkNotNull(repoResult)
        }
        override suspend fun repositoryById(id: ProviderRepoId, validators: Validators?): Fetch<RepoMetadata> = error("nem használt")
        override suspend fun readme(ref: RepoRef, validators: Validators?): Fetch<Readme> { calls++; return Fetch.Fresh(Readme("# x", null), Validators("r", null)) }
        override suspend fun releases(ref: RepoRef, validators: Validators?, limit: Int): Fetch<List<Release>> { calls++; return Fetch.Fresh(emptyList(), Validators("l", null)) }
        override fun downloadPolicy(maxBytes: Long) = HttpPolicy.of("github.com", maxBodyBytes = maxBytes)
    }

    @Test
    fun withinTtl_noRequests_afterTtl_conditionalRequest() = runBlocking {
        val provider = FakeProvider().apply { repoResult = Fetch.Fresh(meta, Validators("\"e1\"", null)) }
        val repo = AppDetailsRepository(ProviderRegistry(listOf(provider)), clock, ttl = Duration.ofMinutes(30))
        assertEquals("desc", repo.load(ref).repo.value?.description)
        assertEquals(3, provider.calls)

        clock.advance(Duration.ofMinutes(10))
        repo.load(ref)
        assertEquals(3, provider.calls, "a TTL-en belül nincs új kérés (a névtelen keret kímélése)")

        clock.advance(Duration.ofMinutes(25))
        provider.repoResult = Fetch.NotModified
        val again = repo.load(ref)
        assertEquals(6, provider.calls)
        assertEquals("\"e1\"", provider.validatorsSeen.last()?.etag, "feltételes kérés a tárolt ETag-gel")
        assertEquals("desc", again.repo.value?.description, "304 esetén a tárolt érték marad")
    }

    @Test
    fun rateLimited_keepsStaleValue_andReportsError() = runBlocking {
        val provider = FakeProvider().apply { repoResult = Fetch.Fresh(meta, Validators("\"e1\"", null)) }
        val repo = AppDetailsRepository(ProviderRegistry(listOf(provider)), clock, ttl = Duration.ofMinutes(30))
        repo.load(ref)
        val until = clock.instant().plusSeconds(600)
        provider.repoResult = Fetch.RateLimited(until)
        val loaded = repo.load(ref, force = true)
        assertEquals("desc", loaded.repo.value?.description)
        assertEquals(DetailsError.RateLimited(until), loaded.repo.error)
    }

    private class MemoryStore : DetailsStore {
        val rows = mutableMapOf<String, StoredDetails>()
        override suspend fun read(key: String) = rows[key]
        override suspend fun write(key: String, details: StoredDetails) { rows[key] = details }
    }

    /** C. 24.: a korábban megnyitott adatlap egy új indítás után, hálózat nélkül is megjelenik, a hiba jelzésével. */
    @Test
    fun storedDetails_areShownOffline_afterARestart_withTheNetworkError(): Unit = runBlocking {
        val store = MemoryStore()
        val online = FakeProvider().apply { repoResult = Fetch.Fresh(meta, Validators("\"e1\"", null)) }
        AppDetailsRepository(ProviderRegistry(listOf(online)), clock, store = store).load(ref)
        assertEquals(3, store.rows.size)

        clock.advance(Duration.ofHours(2))
        val offline = object : SourceProvider by online {
            override suspend fun repository(ref: RepoRef, validators: Validators?) = Fetch.Failed(SourceError.Network("offline"))
            override suspend fun readme(ref: RepoRef, validators: Validators?) = Fetch.Failed(SourceError.Network("offline"))
            override suspend fun releases(ref: RepoRef, validators: Validators?, limit: Int) = Fetch.Failed(SourceError.Network("offline"))
        }
        val restarted = AppDetailsRepository(ProviderRegistry(listOf(offline)), clock, store = store).load(ref)
        assertEquals(meta, restarted.repo.value, "a tárolt repó-adat visszaolvasva")
        assertEquals("# x", restarted.readme.value?.markdown)
        assertEquals(emptyList(), restarted.releases.value)
        assertEquals(DetailsError.Network, restarted.repo.error)
        assertEquals(Instant.parse("2026-11-10T10:00:00Z"), restarted.repo.fetchedAt, "a mentés ideje látszik")
    }

    @Test
    fun withinTtl_aRestartedAppUsesTheStoredDetails_withoutRequests(): Unit = runBlocking {
        val store = MemoryStore()
        val provider = FakeProvider().apply { repoResult = Fetch.Fresh(meta, Validators("\"e1\"", null)) }
        AppDetailsRepository(ProviderRegistry(listOf(provider)), clock, store = store).load(ref)
        clock.advance(Duration.ofMinutes(5))
        AppDetailsRepository(ProviderRegistry(listOf(provider)), clock, store = store).load(ref)
        assertEquals(3, provider.calls, "a tartós gyorsítótár kíméli az API-keretet")
    }

    @Test
    fun codecs_roundTripReleasesWithAssets_andRejectCorruptData() {
        val release = Release(
            7, "v1.0", "One", true, false, Instant.parse("2026-01-01T00:00:00Z"), null, "notes", "https://github.com/o/a/releases/tag/v1.0", true,
            listOf(ReleaseAsset(9, "app.apk", "lbl", "application/vnd.android.package-archive", 1234, "https://github.com/o/a/releases/download/v1.0/app.apk", "ab".repeat(32), "uploaded")),
        )
        assertEquals(listOf(release), DetailsCodecs.releases.decode(DetailsCodecs.releases.encode(listOf(release))))
        assertEquals(meta, DetailsCodecs.repo.decode(DetailsCodecs.repo.encode(meta)))
        assertNull(DetailsCodecs.repo.decode("{nem json"))
        assertNull(DetailsCodecs.repo.decode(DetailsCodecs.repo.encode(meta).replace("\"owner\":\"owner\"", "\"owner\":\"bad owner\"")), "érvénytelen tárolt érték: kimarad")
    }

    @Test
    fun notFound_clearsValue(): Unit = runBlocking {
        val provider = FakeProvider().apply { repoResult = Fetch.NotFound }
        val loaded = AppDetailsRepository(ProviderRegistry(listOf(provider)), clock).load(ref)
        assertNull(loaded.repo.value)
        assertIs<DetailsError.NotFound>(loaded.repo.error)
    }
}
