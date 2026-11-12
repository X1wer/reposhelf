// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.catalog

import io.github.x1wer.reposhelf.core.catalog.AcceptedCatalogInfo
import io.github.x1wer.reposhelf.core.catalog.CatalogAcceptance
import io.github.x1wer.reposhelf.core.catalog.CatalogDecision
import io.github.x1wer.reposhelf.core.catalog.CatalogParseResult
import io.github.x1wer.reposhelf.core.catalog.CatalogParser
import io.github.x1wer.reposhelf.core.catalog.CatalogSignatureCheck
import io.github.x1wer.reposhelf.core.catalog.CatalogSignatureVerifier
import io.github.x1wer.reposhelf.core.catalog.SearchIndex
import io.github.x1wer.reposhelf.core.catalog.SearchIndexText
import io.github.x1wer.reposhelf.core.catalog.SearchQuery
import io.github.x1wer.reposhelf.core.catalog.SearchRanker
import io.github.x1wer.reposhelf.core.data.db.CatalogAppEntity
import io.github.x1wer.reposhelf.core.data.db.CatalogDao
import io.github.x1wer.reposhelf.core.data.db.CatalogStateEntity
import io.github.x1wer.reposhelf.core.model.Catalog
import io.github.x1wer.reposhelf.core.model.CatalogApp
import io.github.x1wer.reposhelf.core.model.CatalogCategory
import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.network.FetchRequest
import io.github.x1wer.reposhelf.core.network.FetchResponse
import io.github.x1wer.reposhelf.core.network.HttpFetcher
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.network.Validators
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** A katalógus letöltésének beállításai (docs/ARCHITECTURE.md 7.4., D-021). */
data class CatalogConfig(
    val url: HttpUrl,
    /** A leválasztott aláírás (`catalog.json.sig`, docs/ARCHITECTURE.md 7.3.). */
    val signatureUrl: HttpUrl,
    /** Az alkalmazásba fordított megbízható kulcsokkal ellenőriz (C. 3.2.). */
    val verifier: CatalogSignatureVerifier,
    val policy: HttpPolicy,
    /** Az appba fordított minimális sorszám (`MIN_CATALOG_SERIAL`, D-022). */
    val minimumSerial: Long,
    /** Automatikus frissítésnél ennél gyakrabban nem kérdezzük le a katalógust (C. 16.1.). */
    val minRefreshInterval: Duration = Duration.ofHours(6),
)

enum class CatalogSource { BUNDLED, REMOTE }

enum class CatalogErrorKind { NETWORK, HTTP, RATE_LIMITED, POLICY, INVALID, UNSUPPORTED_SCHEMA, REJECTED, SIGNATURE }

/** Egy katalógusfájl és a leválasztott aláírása (az aláírás hiányozhat: akkor elutasítjuk). */
class SignedCatalog(val catalog: ByteArray, val signature: ByteArray?)

/** A felületnek szóló katalógus-állapot. */
data class CatalogStatus(
    val hasCatalog: Boolean,
    val source: CatalogSource?,
    val serial: Long?,
    val appCount: Int,
    val rejectedCount: Int,
    val generatedAt: Instant?,
    val expiresAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastAttemptAt: Instant?,
    val lastError: CatalogErrorKind?,
    val lastErrorDetail: String?,
) {
    fun isExpired(now: Instant): Boolean = expiresAt?.let { now.isAfter(it) } ?: false

    companion object {
        val EMPTY = CatalogStatus(false, null, null, 0, 0, null, null, null, null, null, null)
    }
}

sealed interface InitOutcome {
    data object AlreadyPresent : InitOutcome
    data class Seeded(val appCount: Int) : InitOutcome
    data object NoBundledCatalog : InitOutcome
    data class BundledCatalogInvalid(val detail: String) : InitOutcome
}

sealed interface RefreshOutcome {
    data object Skipped : RefreshOutcome
    data object Unchanged : RefreshOutcome
    data class Updated(val appCount: Int, val rejectedCount: Int) : RefreshOutcome
    data class Failed(val kind: CatalogErrorKind, val detail: String?) : RefreshOutcome
}

/**
 * A katalógus kezelése (C. 3.2.):
 * - első indításkor az APK-ba csomagolt kezdőkatalógus töltődik be, így a katalógus internet nélkül is elérhető;
 * - frissítés feltételes kéréssel (ETag / If-Modified-Since): a változatlan adat nem töltődik le újra;
 * - az új katalógus csak teljes validáció és a sorszám-ellenőrzés után, egy tranzakcióban váltja le a régit;
 * - bármely hibánál az utolsó érvényes katalógus marad, a hiba pedig az állapotban látható.
 *
 * Hitelesség (C. 3.2., docs/ARCHITECTURE.md 7.3.): a letöltött ÉS a beépített katalógus is csak akkor fogadható el, ha
 * a leválasztott aláírása egy alkalmazásba fordított kulccsal érvényes, a feldolgozás ELŐTT. A GitHubon való elhelyezés
 * önmagában nem tesz megbízhatóvá egy katalógust.
 */
class CatalogRepository(
    private val dao: CatalogDao,
    private val fetcher: HttpFetcher,
    private val config: CatalogConfig,
    private val bundledCatalog: suspend () -> SignedCatalog?,
    private val clock: Clock = Clock.systemUTC(),
    private val parser: CatalogParser = CatalogParser(),
) {
    private val mutex = Mutex()

    val status: Flow<CatalogStatus> = dao.observeState().map { it?.toStatus() ?: CatalogStatus.EMPTY }

    val categoryCounts: Flow<Map<CatalogCategory, Int>> = dao.observeCategoryCounts().map { rows ->
        rows.groupBy({ CatalogCategory.fromId(it.category) }, { it.count }).mapValues { it.value.sum() }
    }

    suspend fun ensureInitialized(): InitOutcome = mutex.withLock {
        if (dao.state() != null) return InitOutcome.AlreadyPresent
        val bundled = bundledCatalog() ?: return InitOutcome.NoBundledCatalog
        val signature = config.verifier.verify(bundled.catalog, bundled.signature)
        if (signature !is CatalogSignatureCheck.Valid) return InitOutcome.BundledCatalogInvalid(signature.toString())
        when (val parsed = parser.parse(bundled.catalog)) {
            is CatalogParseResult.Parsed -> {
                val decision = CatalogAcceptance.decide(null, parsed.catalog, config.minimumSerial)
                if (decision != CatalogDecision.Accept) return InitOutcome.BundledCatalogInvalid(decision.toString())
                store(parsed.catalog, parsed.rejected.size, CatalogSource.BUNDLED, Validators.NONE, previous = null)
                InitOutcome.Seeded(parsed.catalog.apps.size)
            }
            is CatalogParseResult.UnsupportedSchema -> InitOutcome.BundledCatalogInvalid("schemaVersion=${parsed.schemaVersion}")
            is CatalogParseResult.Invalid -> InitOutcome.BundledCatalogInvalid(parsed.reason.name)
        }
    }

    suspend fun refresh(force: Boolean = false): RefreshOutcome = mutex.withLock {
        val state = dao.state()
        val now = clock.instant()
        val lastAttempt = state?.lastAttemptAtMillis?.let(Instant::ofEpochMilli)
        if (!force && lastAttempt != null && Duration.between(lastAttempt, now) < config.minRefreshInterval) {
            return RefreshOutcome.Skipped
        }
        // A beépített katalógusnak nincs szerveroldali ETag-je; a letöltött katalógusnál feltételes kérést küldünk.
        val validators = if (state?.source == CatalogSource.REMOTE.name) Validators(state.etag, state.lastModified) else null
        val request = FetchRequest(
            url = config.url,
            policy = config.policy,
            headers = mapOf("Accept" to "application/json"),
            validators = validators,
        )
        when (val response = fetcher.get(request)) {
            is FetchResponse.NotModified -> {
                if (state != null) {
                    dao.recordUnchanged(now.toEpochMilli(), response.validators.etag, response.validators.lastModified, CatalogSource.REMOTE.name)
                }
                RefreshOutcome.Unchanged
            }
            is FetchResponse.Success -> processRemote(response, state, now)
            is FetchResponse.RateLimited -> fail(state, now, CatalogErrorKind.RATE_LIMITED, response.until?.toString())
            is FetchResponse.HttpError -> fail(state, now, CatalogErrorKind.HTTP, "HTTP ${response.code}")
            is FetchResponse.NetworkFailure -> fail(state, now, CatalogErrorKind.NETWORK, response.cause::class.java.simpleName)
            is FetchResponse.PolicyViolation -> fail(state, now, CatalogErrorKind.POLICY, response.reason.name)
        }
    }

    /** A katalógus aláírásfájlja; `null`, ha nincs (404). Más hibánál a frissítés átmeneti hibaként áll meg. */
    private suspend fun fetchSignature(): Result<ByteArray?> {
        val request = FetchRequest(url = config.signatureUrl, policy = config.policy, headers = mapOf("Accept" to "application/json"), validators = null)
        return when (val response = fetcher.get(request)) {
            is FetchResponse.Success -> Result.success(response.body)
            is FetchResponse.HttpError -> if (response.code == 404) Result.success(null) else Result.failure(SignatureFetchFailure(CatalogErrorKind.HTTP, "HTTP ${response.code}"))
            is FetchResponse.NotModified -> Result.failure(SignatureFetchFailure(CatalogErrorKind.HTTP, "304"))
            is FetchResponse.RateLimited -> Result.failure(SignatureFetchFailure(CatalogErrorKind.RATE_LIMITED, response.until?.toString()))
            is FetchResponse.NetworkFailure -> Result.failure(SignatureFetchFailure(CatalogErrorKind.NETWORK, response.cause::class.java.simpleName))
            is FetchResponse.PolicyViolation -> Result.failure(SignatureFetchFailure(CatalogErrorKind.POLICY, response.reason.name))
        }
    }

    private class SignatureFetchFailure(val kind: CatalogErrorKind, val detail: String?) : Exception()

    private suspend fun processRemote(response: FetchResponse.Success, state: CatalogStateEntity?, now: Instant): RefreshOutcome {
        val signature = fetchSignature().getOrElse { e ->
            val failure = e as SignatureFetchFailure
            return fail(state, now, failure.kind, failure.detail)
        }
        // Az aláírás a feldolgozás ELŐTT: egy hamis katalógus tartalmát még a sorszám-ellenőrzés sem látja.
        when (val check = config.verifier.verify(response.body, signature)) {
            is CatalogSignatureCheck.Valid -> Unit
            CatalogSignatureCheck.Missing -> return fail(state, now, CatalogErrorKind.SIGNATURE, "missing")
            is CatalogSignatureCheck.Invalid -> return fail(state, now, CatalogErrorKind.SIGNATURE, check.reason.name)
        }
        return when (val parsed = parser.parse(response.body)) {
            is CatalogParseResult.UnsupportedSchema ->
                fail(state, now, CatalogErrorKind.UNSUPPORTED_SCHEMA, "schemaVersion=${parsed.schemaVersion}")
            is CatalogParseResult.Invalid ->
                fail(state, now, CatalogErrorKind.INVALID, listOfNotNull(parsed.reason.name, parsed.detail).joinToString(": "))
            is CatalogParseResult.Parsed -> {
                val current = state?.let { AcceptedCatalogInfo(it.catalogSerial, it.contentSha256) }
                when (val decision = CatalogAcceptance.decide(current, parsed.catalog, config.minimumSerial)) {
                    CatalogDecision.Accept -> {
                        store(parsed.catalog, parsed.rejected.size, CatalogSource.REMOTE, response.validators, state, now)
                        RefreshOutcome.Updated(parsed.catalog.apps.size, parsed.rejected.size)
                    }
                    CatalogDecision.NoChange -> {
                        dao.recordUnchanged(now.toEpochMilli(), response.validators.etag, response.validators.lastModified, CatalogSource.REMOTE.name)
                        RefreshOutcome.Unchanged
                    }
                    else -> fail(state, now, CatalogErrorKind.REJECTED, decision.toString())
                }
            }
        }
    }

    private suspend fun fail(state: CatalogStateEntity?, now: Instant, kind: CatalogErrorKind, detail: String?): RefreshOutcome {
        if (state != null) dao.recordFailure(now.toEpochMilli(), kind.name, detail?.take(200))
        return RefreshOutcome.Failed(kind, detail)
    }

    private suspend fun store(
        catalog: Catalog,
        rejectedCount: Int,
        source: CatalogSource,
        validators: Validators,
        previous: CatalogStateEntity?,
        now: Instant = clock.instant(),
    ) {
        val nowMillis = now.toEpochMilli()
        val state = CatalogStateEntity(
            schemaVersion = catalog.schemaVersion,
            catalogSerial = catalog.serial,
            generatedAtMillis = catalog.generatedAt.toEpochMilli(),
            expiresAtMillis = catalog.expiresAt.toEpochMilli(),
            contentSha256 = catalog.contentSha256,
            source = source.name,
            appCount = catalog.apps.size,
            rejectedCount = rejectedCount,
            etag = validators.etag,
            lastModified = validators.lastModified,
            acceptedAtMillis = nowMillis,
            lastAttemptAtMillis = if (source == CatalogSource.REMOTE) nowMillis else previous?.lastAttemptAtMillis,
            lastSuccessAtMillis = if (source == CatalogSource.REMOTE) nowMillis else previous?.lastSuccessAtMillis,
            lastErrorKind = null,
            lastErrorDetail = null,
        )
        dao.replaceCatalog(state, catalog.apps.map { it.toEntity() })
    }

    /**
     * Keresés a helyi katalógusban (C. 5.), hálózati kérés nélkül:
     * először előtag-keresés az FTS4-indexben; ha ez kevés találatot ad, a szó belsejében lévő egyezést is megnézzük.
     * Szöveg nélkül a (kategóriára szűrt) teljes lista jön, név szerint rendezve.
     */
    suspend fun search(query: SearchQuery, limit: Int = DEFAULT_LIMIT): List<CatalogApp> {
        val category = query.category?.id
        if (!query.hasText) return dao.browse(category, limit).mapNotNull { it.toModelOrNull() }
        val prefixHits = dao.searchFts(query.ftsMatch(), category, limit)
        val hits = if (prefixHits.size >= INFIX_FALLBACK_THRESHOLD) {
            prefixHits
        } else {
            val longest = query.tokens.maxBy { it.length }
            val infix = dao.searchInfix("%$longest%", category, limit)
                .filter { query.matchesAllInfix(SearchIndexText(it.searchName, it.searchKeywords)) }
            (prefixHits + infix).distinctBy { it.catalogId }
        }
        return SearchRanker.rank(query, hits, { SearchIndexText(it.searchName, it.searchKeywords) }, { it.name })
            .take(limit)
            .mapNotNull { it.toModelOrNull() }
    }

    suspend fun app(id: String): CatalogApp? = dao.byId(id)?.toModelOrNull()

    private fun CatalogStateEntity.toStatus() = CatalogStatus(
        hasCatalog = true,
        source = runCatching { CatalogSource.valueOf(source) }.getOrNull(),
        serial = catalogSerial,
        appCount = appCount,
        rejectedCount = rejectedCount,
        generatedAt = Instant.ofEpochMilli(generatedAtMillis),
        expiresAt = Instant.ofEpochMilli(expiresAtMillis),
        lastSuccessAt = lastSuccessAtMillis?.let(Instant::ofEpochMilli),
        lastAttemptAt = lastAttemptAtMillis?.let(Instant::ofEpochMilli),
        lastError = lastErrorKind?.let { runCatching { CatalogErrorKind.valueOf(it) }.getOrNull() },
        lastErrorDetail = lastErrorDetail,
    )

    companion object {
        const val DEFAULT_LIMIT = 200
        private const val INFIX_FALLBACK_THRESHOLD = 50
    }
}

internal fun CatalogApp.toEntity(): CatalogAppEntity {
    val index = SearchIndex.build(this)
    return CatalogAppEntity(
        catalogId = id,
        name = name,
        provider = repo.provider.id,
        repoOwner = repo.owner,
        repoName = repo.name,
        repositoryId = repositoryId?.value,
        packageName = packageName,
        signingCertSha256 = signingCertSha256.joinToString(","),
        minVersionCode = minVersionCode,
        keywords = keywords.joinToString(CatalogAppEntity.KEYWORD_SEPARATOR.toString()),
        category = category.id,
        assetFilter = assetFilter,
        searchName = index.name,
        searchKeywords = index.keywords,
    )
}

/** A tárolt sor visszaalakítása; egy sérült sor nem dönti le a listát, hanem kimarad. */
internal fun CatalogAppEntity.toModelOrNull(): CatalogApp? = runCatching {
    CatalogApp(
        id = catalogId,
        name = name,
        repo = RepoRef(checkNotNull(ProviderId.fromId(provider)), repoOwner, repoName),
        repositoryId = repositoryId?.let(::ProviderRepoId),
        packageName = packageName,
        signingCertSha256 = signingCertSha256.split(',').filter { it.isNotEmpty() },
        minVersionCode = minVersionCode,
        keywords = keywords.split(CatalogAppEntity.KEYWORD_SEPARATOR).filter { it.isNotEmpty() },
        category = CatalogCategory.fromId(category),
        assetFilter = assetFilter,
    )
}.getOrNull()
