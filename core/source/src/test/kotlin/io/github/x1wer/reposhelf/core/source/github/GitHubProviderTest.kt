// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.source.github

import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.network.ConcurrencyLimiter
import io.github.x1wer.reposhelf.core.network.FetchResponse
import io.github.x1wer.reposhelf.core.network.HttpClients
import io.github.x1wer.reposhelf.core.network.HttpFetcher
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.network.RateLimitTracker
import io.github.x1wer.reposhelf.core.network.Validators
import io.github.x1wer.reposhelf.core.network.testing.HttpsTestServer
import io.github.x1wer.reposhelf.core.network.testing.MutableClock
import io.github.x1wer.reposhelf.core.source.Fetch
import io.github.x1wer.reposhelf.core.source.SourceError
import io.github.x1wer.reposhelf.core.source.UnavailableReason
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubProviderTest {

    private lateinit var https: HttpsTestServer
    private lateinit var provider: GitHubProvider
    private val clock = MutableClock(Instant.parse("2026-11-10T10:00:00Z"))
    private val repo = RepoRef(ProviderId.GITHUB, "ImranR98", "Obtainium")

    @Before
    fun setUp() {
        https = HttpsTestServer()
        val client = HttpClients.create("RepoShelf-Test/1") { https.trustTestCertificate(this) }
        val fetcher = HttpFetcher(client, RateLimitTracker(clock), ConcurrencyLimiter(2), clock, random = Random(1), sleep = {})
        val policy = HttpPolicy.of(https.host, maxBodyBytes = 64 * 1024)
        provider = GitHubProvider(
            fetcher = fetcher,
            apiBase = https.server.url("/"),
            policy = policy,
            readmePolicy = policy.copy(maxBodyBytes = 1024),
        )
    }

    @After
    fun tearDown() = https.close()

    private fun json(code: Int, body: String, vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.build()

    private val repoJson = """
        {"id":523534328,"name":"Obtainium","full_name":"ImranR98/Obtainium",
         "owner":{"login":"ImranR98","id":12345,"type":"User"},
         "description":"Get Android app updates straight from the source.",
         "license":{"key":"gpl-3.0","spdx_id":"GPL-3.0"},
         "default_branch":"main","html_url":"https://github.com/ImranR98/Obtainium",
         "archived":false,"disabled":false,"fork":false,"stargazers_count":99999,"unknown_future_field":{"x":1}}
    """.trimIndent()

    @Test
    fun repository_mapsFields_andSendsStableHeaders() = runBlocking {
        https.server.enqueue(json(200, repoJson, "ETag" to "W/\"r1\""))
        val fresh = assertIs<Fetch.Fresh<*>>(provider.repository(repo))
        val meta = fresh.value as io.github.x1wer.reposhelf.core.model.RepoMetadata
        assertEquals(ProviderRepoId(523534328), meta.id)
        assertEquals(12345L, meta.ownerId)
        assertEquals("GPL-3.0", meta.licenseSpdx)
        assertEquals("main", meta.defaultBranch)
        assertEquals("W/\"r1\"", fresh.validators.etag)

        val recorded = https.server.takeRequest()
        assertEquals("/repos/ImranR98/Obtainium", recorded.url.encodedPath)
        assertEquals("application/vnd.github+json", recorded.headers["Accept"])
        assertEquals(GitHubProvider.API_VERSION, recorded.headers["X-GitHub-Api-Version"])
        assertNull(recorded.headers["Authorization"], "Alapból névtelen (D-050)")
    }

    @Test
    fun repository_conditional304_isNotModified() = runBlocking {
        https.server.enqueue(MockResponse.Builder().code(304).build())
        assertEquals(Fetch.NotModified, provider.repository(repo, Validators("W/\"r1\"", null)))
        assertEquals("W/\"r1\"", https.server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun repository_errorMapping() = runBlocking {
        https.server.enqueue(json(404, """{"message":"Not Found"}"""))
        assertEquals(Fetch.NotFound, provider.repository(repo))

        https.server.enqueue(json(451, """{"message":"Repository access blocked","block":{"reason":"dmca"}}"""))
        assertEquals(Fetch.Unavailable(UnavailableReason.LEGAL_TAKEDOWN), provider.repository(repo))

        https.server.enqueue(json(403, """{"message":"Repository access blocked"}"""))
        assertEquals(Fetch.Unavailable(UnavailableReason.ACCESS_BLOCKED), provider.repository(repo))

        https.server.enqueue(json(200, "{not json"))
        assertEquals(Fetch.Failed(SourceError.InvalidResponse), provider.repository(repo))
    }

    @Test
    fun repository_rejectsResponseWithInvalidOwnerName() = runBlocking {
        https.server.enqueue(json(200, repoJson.replace("\"login\":\"ImranR98\"", "\"login\":\"../evil\"")))
        assertEquals(Fetch.Failed(SourceError.InvalidResponse), provider.repository(repo))
    }

    @Test
    fun repository_rateLimited() = runBlocking {
        https.server.enqueue(
            json(
                403, """{"message":"API rate limit exceeded"}""",
                "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to clock.instant().plusSeconds(900).epochSecond.toString(),
            ),
        )
        val result = assertIs<Fetch.RateLimited>(provider.repository(repo))
        assertEquals(clock.instant().plusSeconds(901), result.until)
    }

    @Test
    fun repository_followsRenameRedirectOnSameHost() = runBlocking {
        https.server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "/repositories/523534328").build())
        https.server.enqueue(json(200, repoJson.replace("\"name\":\"Obtainium\"", "\"name\":\"Obtainium2\"")))
        val fresh = assertIs<Fetch.Fresh<*>>(provider.repository(repo))
        val meta = fresh.value as io.github.x1wer.reposhelf.core.model.RepoMetadata
        assertEquals("Obtainium2", meta.ref.name)
        assertEquals(ProviderRepoId(523534328), meta.id)
    }

    @Test
    fun releases_parsesAssetsDigestsAndFlags() = runBlocking {
        val body = """
            [{"id":1,"tag_name":"v1.7.4","name":"v1.7.4","draft":false,"prerelease":false,
              "created_at":"2026-11-01T09:00:00Z","published_at":"2026-11-01T10:00:00Z","body":"notes",
              "html_url":"https://github.com/ImranR98/Obtainium/releases/tag/v1.7.4","immutable":false,
              "assets":[
                {"id":11,"name":"app-arm64-v8a-release.apk","content_type":"application/vnd.android.package-archive","size":1234,
                 "browser_download_url":"https://github.com/ImranR98/Obtainium/releases/download/v1.7.4/app-arm64-v8a-release.apk",
                 "digest":"sha256:ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789","state":"uploaded"},
                {"id":12,"name":"insecure.apk","size":10,"browser_download_url":"http://example.com/insecure.apk"},
                {"id":13,"name":"old.apk","size":10,"browser_download_url":"https://github.com/a/b/old.apk","digest":null},
                {"id":14,"name":"md5.apk","size":10,"browser_download_url":"https://github.com/a/b/md5.apk","digest":"md5:abc"}
              ]},
             {"id":2,"tag_name":"v1.8.0-beta","draft":true,"prerelease":true,"published_at":"not a date","assets":[]}]
        """.trimIndent()
        https.server.enqueue(json(200, body, "ETag" to "\"rel1\""))
        val fresh = assertIs<Fetch.Fresh<List<io.github.x1wer.reposhelf.core.model.Release>>>(provider.releases(repo, limit = 10))
        val releases = fresh.value
        assertEquals(2, releases.size)
        val stable = releases[0]
        assertEquals("v1.7.4", stable.tagName)
        assertEquals(Instant.parse("2026-11-01T10:00:00Z"), stable.publishedAt)
        assertEquals(false, stable.isImmutable)
        assertEquals(listOf(11L, 13L, 14L), stable.assets.map { it.id }, "A nem HTTPS asset kimarad")
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", stable.assets[0].sha256)
        assertNull(stable.assets[1].sha256)
        assertNull(stable.assets[2].sha256, "Csak sha256 digest fogadható el")
        val draft = releases[1]
        assertTrue(draft.isDraft && draft.isPrerelease)
        assertNull(draft.publishedAt, "Hibás dátum → null, nem kivétel")
        assertEquals("10", https.server.takeRequest().url.queryParameter("per_page"))
    }

    @Test
    fun readme_returnsRawMarkdown_andRespectsSizeLimit() = runBlocking {
        https.server.enqueue(MockResponse.Builder().code(200).body("# Obtainium\n\nHello").build())
        val fresh = assertIs<Fetch.Fresh<io.github.x1wer.reposhelf.core.model.Readme>>(provider.readme(repo))
        assertEquals("# Obtainium\n\nHello", fresh.value.markdown)
        assertEquals("application/vnd.github.raw+json", https.server.takeRequest().headers["Accept"])

        https.server.enqueue(MockResponse.Builder().code(200).body("x".repeat(4096)).build())
        assertEquals(Fetch.Failed(SourceError.Policy(FetchResponse.Violation.BODY_TOO_LARGE)), provider.readme(repo))
    }

    @Test
    fun digestParsing() {
        assertEquals("a".repeat(64), GitHubProvider.parseDigest("sha256:" + "A".repeat(64)))
        assertNull(GitHubProvider.parseDigest("sha256:" + "a".repeat(63)))
        assertNull(GitHubProvider.parseDigest("sha512:" + "a".repeat(64)))
        assertNull(GitHubProvider.parseDigest(null))
    }

    // ---- DL-04: 0 méretű vagy még fel nem töltött asset nem telepíthető állomány ----

    @Test
    fun releases_dropZeroSizedAndNotYetUploadedAssets() = runBlocking {
        val body = """
            [{"id":1,"tag_name":"v1.0","draft":false,"prerelease":false,"published_at":"2026-11-01T10:00:00Z",
              "assets":[
                {"id":21,"name":"empty.apk","size":0,"browser_download_url":"https://github.com/a/b/empty.apk","state":"uploaded"},
                {"id":22,"name":"starter.apk","size":10,"browser_download_url":"https://github.com/a/b/starter.apk","state":"starter"},
                {"id":23,"name":"negative.apk","size":-1,"browser_download_url":"https://github.com/a/b/negative.apk"},
                {"id":24,"name":"good.apk","size":10,"browser_download_url":"https://github.com/a/b/good.apk","state":"uploaded"}
              ]}]
        """.trimIndent()
        https.server.enqueue(json(200, body))
        val fresh = assertIs<Fetch.Fresh<List<io.github.x1wer.reposhelf.core.model.Release>>>(provider.releases(repo, limit = 10))
        assertEquals(listOf(24L), fresh.value.single().assets.map { it.id })
    }
}
