// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.source.github

import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.Readme
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.ReleaseAsset
import io.github.x1wer.reposhelf.core.model.RepoMetadata
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.network.FetchRequest
import io.github.x1wer.reposhelf.core.network.FetchResponse
import io.github.x1wer.reposhelf.core.network.HttpFetcher
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.network.Validators
import io.github.x1wer.reposhelf.core.source.Fetch
import io.github.x1wer.reposhelf.core.source.SourceError
import io.github.x1wer.reposhelf.core.source.SourceProvider
import io.github.x1wer.reposhelf.core.source.UnavailableReason
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * GitHub REST API forrásszolgáltató (docs/ARCHITECTURE.md 5.1., D-050, D-051).
 *
 * - Alapból névtelen. A 60/óra keret IP-címenként közös, és a 304 is fogy belőle, ezért a hívó takarékoskodik.
 * - Konstans fejlécek, hogy az ETag-ek egyezzenek.
 * - A kérések kizárólag az [apiBase] hosztjára mennek; az átirányítás is csak ott követhető (pl. átnevezett repó).
 */
class GitHubProvider(
    private val fetcher: HttpFetcher,
    private val apiBase: HttpUrl = DEFAULT_API_BASE,
    private val policy: HttpPolicy = HttpPolicy.of(DEFAULT_API_BASE.host, maxBodyBytes = 2 * HttpPolicy.MIB),
    private val readmePolicy: HttpPolicy = policy.copy(maxBodyBytes = 512 * HttpPolicy.KIB),
    /** Opcionális, felhasználó által megadott token (D-050). Az appba beégetett token tilos. */
    private val token: () -> String? = { null },
) : SourceProvider {

    override val id: ProviderId = ProviderId.GITHUB

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override fun parseRepoUrl(input: String): RepoRef? = GitHubUrlParser.parse(input)

    override suspend fun repository(ref: RepoRef, validators: Validators?): Fetch<RepoMetadata> {
        require(ref.provider == ProviderId.GITHUB)
        val url = apiBase.newBuilder().addPathSegment("repos").addPathSegment(ref.owner).addPathSegment(ref.name).build()
        return fetchJson(url, validators, GitHubRepoDto.serializer(), policy, ACCEPT_JSON) { it.toModel() }
    }

    override suspend fun repositoryById(id: ProviderRepoId, validators: Validators?): Fetch<RepoMetadata> {
        val url = apiBase.newBuilder().addPathSegment("repositories").addPathSegment(id.value.toString()).build()
        return fetchJson(url, validators, GitHubRepoDto.serializer(), policy, ACCEPT_JSON) { it.toModel() }
    }

    override suspend fun readme(ref: RepoRef, validators: Validators?): Fetch<Readme> {
        val url = apiBase.newBuilder().addPathSegment("repos").addPathSegment(ref.owner).addPathSegment(ref.name)
            .addPathSegment("readme").build()
        return when (val response = fetcher.get(request(url, validators, readmePolicy, ACCEPT_RAW))) {
            is FetchResponse.Success -> Fetch.Fresh(Readme(String(response.body, Charsets.UTF_8), path = null), response.validators)
            else -> mapFailure(response)
        }
    }

    override suspend fun releases(ref: RepoRef, validators: Validators?, limit: Int): Fetch<List<Release>> {
        require(limit in 1..100) { "A per_page 1 és 100 között lehet" }
        val url = apiBase.newBuilder().addPathSegment("repos").addPathSegment(ref.owner).addPathSegment(ref.name)
            .addPathSegment("releases").addQueryParameter("per_page", limit.toString()).build()
        return fetchJson(url, validators, ListSerializer(GitHubReleaseDto.serializer()), policy, ACCEPT_JSON) { list ->
            list.map { it.toModel() }
        }
    }

    /**
     * A kiadási állományok a `github.com/…/releases/download/…` címről a GitHub CDN-jére irányítanak
     * (jelenleg `release-assets.githubusercontent.com`). Konkrét CDN-hosztnevet nem rögzítünk, mert a GitHub
     * dokumentáció nélkül váltja (docs/RESEARCH.md §6), ezért a `*.githubusercontent.com` engedélyezett.
     */
    override fun downloadPolicy(maxBytes: Long): HttpPolicy = HttpPolicy.of("github.com", "*.githubusercontent.com", maxBodyBytes = maxBytes)

    private fun request(url: HttpUrl, validators: Validators?, policy: HttpPolicy, accept: String): FetchRequest {
        val headers = buildMap {
            put("Accept", accept)
            put("X-GitHub-Api-Version", API_VERSION)
            token()?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }
        return FetchRequest(url = url, policy = policy, headers = headers, validators = validators, rateLimitScope = RATE_LIMIT_SCOPE)
    }

    private suspend fun <D, T> fetchJson(
        url: HttpUrl,
        validators: Validators?,
        deserializer: DeserializationStrategy<D>,
        policy: HttpPolicy,
        accept: String,
        map: (D) -> T?,
    ): Fetch<T> = when (val response = fetcher.get(request(url, validators, policy, accept))) {
        is FetchResponse.Success -> {
            val decoded = try {
                map(json.decodeFromString(deserializer, String(response.body, Charsets.UTF_8)))
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (decoded == null) Fetch.Failed(SourceError.InvalidResponse) else Fetch.Fresh(decoded, response.validators)
        }
        else -> mapFailure(response)
    }

    private fun mapFailure(response: FetchResponse): Fetch<Nothing> = when (response) {
        is FetchResponse.Success -> error("A sikeres választ a hívó kezeli")
        is FetchResponse.NotModified -> Fetch.NotModified
        is FetchResponse.RateLimited -> Fetch.RateLimited(response.until)
        is FetchResponse.NetworkFailure -> Fetch.Failed(SourceError.Network(response.cause::class.java.simpleName))
        is FetchResponse.PolicyViolation -> Fetch.Failed(SourceError.Policy(response.reason))
        is FetchResponse.HttpError -> when {
            response.code == 404 -> Fetch.NotFound
            response.code == 410 -> Fetch.Unavailable(UnavailableReason.GONE)
            response.code == 451 -> Fetch.Unavailable(UnavailableReason.LEGAL_TAKEDOWN)
            response.code == 403 && response.bodySnippet?.contains("access blocked", ignoreCase = true) == true ->
                Fetch.Unavailable(UnavailableReason.ACCESS_BLOCKED)
            else -> Fetch.Failed(SourceError.Http(response.code))
        }
    }

    private fun GitHubRepoDto.toModel(): RepoMetadata? {
        val ref = RepoRef.parsePath(ProviderId.GITHUB, "${owner.login}/$name") ?: return null
        if (id <= 0) return null
        return RepoMetadata(
            ref = ref,
            id = ProviderRepoId(id),
            ownerId = owner.id?.takeIf { it > 0 },
            description = description?.take(MAX_DESCRIPTION),
            licenseSpdx = license?.spdxId?.takeIf { it.isNotBlank() && it != "NOASSERTION" }?.take(64),
            defaultBranch = defaultBranch?.take(255),
            htmlUrl = htmlUrl?.takeIf { it.toHttpUrlOrNull()?.isHttps == true },
            isArchived = archived,
            isDisabled = disabled,
            isFork = fork,
        )
    }

    private fun GitHubReleaseDto.toModel(): Release = Release(
        id = id,
        tagName = tagName.take(255),
        name = name?.take(255),
        isPrerelease = prerelease,
        isDraft = draft,
        createdAt = parseInstant(createdAt),
        publishedAt = parseInstant(publishedAt),
        body = body?.take(MAX_RELEASE_BODY),
        htmlUrl = htmlUrl?.takeIf { it.toHttpUrlOrNull()?.isHttps == true },
        isImmutable = immutable,
        // Nem HTTPS letöltési URL-ű assetet nem veszünk fel (a letöltéskor is újra ellenőrizzük).
        // A 0 méretű vagy még fel nem töltött (állapota nem „uploaded”) asset sem telepíthető.
        assets = assets.filter { it.name.isNotBlank() && it.size > 0 && (it.state == null || it.state == "uploaded") && it.browserDownloadUrl.toHttpUrlOrNull()?.isHttps == true }
            .map { it.toModel() },
    )

    private fun GitHubAssetDto.toModel(): ReleaseAsset = ReleaseAsset(
        id = id,
        name = name.take(255),
        label = label?.take(255),
        contentType = contentType?.take(255),
        size = size,
        downloadUrl = browserDownloadUrl,
        sha256 = parseDigest(digest),
        state = state,
    )

    companion object {
        val DEFAULT_API_BASE: HttpUrl = "https://api.github.com/".toHttpUrl()
        const val API_VERSION = "2026-03-10"
        const val RATE_LIMIT_SCOPE = "github"
        private const val ACCEPT_JSON = "application/vnd.github+json"
        private const val ACCEPT_RAW = "application/vnd.github.raw+json"
        private const val MAX_DESCRIPTION = 1000
        private const val MAX_RELEASE_BODY = 64 * 1024
        private val SHA256_DIGEST = Regex("^sha256:([0-9a-fA-F]{64})$")

        /** `sha256:<64 hex>` → kisbetűs hex; minden más (null, más algoritmus, hibás formátum) → `null`. */
        internal fun parseDigest(digest: String?): String? =
            digest?.trim()?.let { SHA256_DIGEST.matchEntire(it) }?.groupValues?.get(1)?.lowercase()

        internal fun parseInstant(value: String?): Instant? = try {
            value?.let(Instant::parse)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
