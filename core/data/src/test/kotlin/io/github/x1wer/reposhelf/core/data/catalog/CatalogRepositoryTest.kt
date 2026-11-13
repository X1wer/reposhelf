// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.catalog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.x1wer.reposhelf.core.catalog.SearchQuery
import io.github.x1wer.reposhelf.core.data.db.AppDatabase
import io.github.x1wer.reposhelf.core.model.CatalogCategory
import io.github.x1wer.reposhelf.core.network.ConcurrencyLimiter
import io.github.x1wer.reposhelf.core.network.HttpClients
import io.github.x1wer.reposhelf.core.network.HttpFetcher
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.network.RateLimitTracker
import io.github.x1wer.reposhelf.core.network.testing.HttpsTestServer
import io.github.x1wer.reposhelf.core.network.testing.MutableClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import io.github.x1wer.reposhelf.core.catalog.CatalogSignatureVerifier
import io.github.x1wer.reposhelf.core.catalog.TrustedCatalogKey
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var https: HttpsTestServer
    private val clock = MutableClock(Instant.parse("2026-11-10T10:00:00Z"))
    private var seed: SignedCatalog? = signed(catalogJson(1, SAMPLE))

    private fun repository(minimumSerial: Long = 1): CatalogRepository {
        val client = HttpClients.create("RepoShelf-Test/1") { https.trustTestCertificate(this) }
        val fetcher = HttpFetcher(client, RateLimitTracker(clock), ConcurrencyLimiter(2), clock, random = Random(1), sleep = {})
        return CatalogRepository(
            dao = db.catalogDao(),
            fetcher = fetcher,
            config = CatalogConfig(
                url = https.server.url("/dist/v1/catalog.json"),
                signatureUrl = https.server.url("/dist/v1/catalog.json.sig"),
                verifier = CatalogSignatureVerifier(listOf(TrustedCatalogKey("test-key", KEY.public))),
                policy = HttpPolicy.of(https.host, maxBodyBytes = 8 * HttpPolicy.MIB),
                minimumSerial = minimumSerial,
                minRefreshInterval = Duration.ofHours(6),
            ),
            bundledCatalog = { seed },
            clock = clock,
        )
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        https = HttpsTestServer()
    }

    @After
    fun tearDown() {
        db.close()
        https.close()
    }

    /** A katalógus és a hozzá tartozó aláírás (a kliens az aláírást a katalógus után kéri le). */
    private fun enqueueSigned(body: ByteArray, etag: String? = null, signatureFile: ByteArray? = signatureFile(body)) {
        https.server.enqueue(MockResponse.Builder().code(200).body(String(body)).apply { etag?.let { addHeader("ETag", it) } }.build())
        https.server.enqueue(
            if (signatureFile == null) MockResponse.Builder().code(404).body("Not Found").build()
            else MockResponse.Builder().code(200).body(String(signatureFile)).build(),
        )
    }

    @Test
    fun refresh_unsignedOrTamperedCatalog_isRejected_andTheLastValidStays() = runBlocking {
        val repo = repository()
        repo.ensureInitialized()
        // Hiányzó aláírás.
        enqueueSigned(catalogJson(2, SAMPLE.take(1)), signatureFile = null)
        val missing = assertIs<RefreshOutcome.Failed>(repo.refresh(force = true))
        assertEquals(CatalogErrorKind.SIGNATURE, missing.kind)
        assertEquals("missing", missing.detail)
        // Egy másik (érvényes) katalógus aláírása: a tartalmat módosították.
        enqueueSigned(catalogJson(2, SAMPLE.take(1)), signatureFile = signatureFile(catalogJson(2, SAMPLE)))
        assertEquals(CatalogErrorKind.SIGNATURE, assertIs<RefreshOutcome.Failed>(repo.refresh(force = true)).kind)
        // Nem megbízható kulccsal aláírt katalógus.
        enqueueSigned(catalogJson(2, SAMPLE.take(1)), signatureFile = signatureFile(catalogJson(2, SAMPLE.take(1)), keyPair(), "test-key"))
        assertEquals(CatalogErrorKind.SIGNATURE, assertIs<RefreshOutcome.Failed>(repo.refresh(force = true)).kind)

        val status = repo.status.first()
        assertEquals(CatalogSource.BUNDLED, status.source)
        assertEquals(SAMPLE_APPS.size, status.appCount)
        assertEquals(CatalogErrorKind.SIGNATURE, status.lastError)
    }

    @Test
    fun bundledCatalogWithoutValidSignature_isNotLoaded() = runBlocking {
        seed = SignedCatalog(catalogJson(1, SAMPLE), null)
        assertIs<InitOutcome.BundledCatalogInvalid>(repository().ensureInitialized())
        seed = SignedCatalog(catalogJson(1, SAMPLE), signatureFile(catalogJson(1, SAMPLE.take(1))))
        assertIs<InitOutcome.BundledCatalogInvalid>(repository().ensureInitialized())
        assertTrue(!repository().status.first().hasCatalog)
    }

    private suspend fun CatalogRepository.names(raw: String, category: CatalogCategory? = null) =
        search(SearchQuery.of(raw, category)).map { it.name }

    @Test
    fun bundledCatalog_isLoadedOnFirstStart_andSearchable() = runBlocking {
        val repo = repository()
        assertEquals(InitOutcome.Seeded(SAMPLE_APPS.size), repo.ensureInitialized())
        assertEquals(InitOutcome.AlreadyPresent, repo.ensureInitialized())

        val status = repo.status.first()
        assertEquals(CatalogSource.BUNDLED, status.source)
        assertEquals(SAMPLE_APPS.size, status.appCount)

        assertEquals(listOf("Obtainium"), repo.names("obta"), "előtag (FTS)")
        assertEquals(listOf("NewPipe"), repo.names("pipe"), "részleges egyezés a szó belsejében")
        assertEquals(listOf("Organic Maps"), repo.names("TÉRKÉP"), "ékezet- és kis/nagybetű-független kulcsszó")
        assertEquals(listOf("Organic Maps"), repo.names("navigacio offline"), "több kulcsszó együtt")
        assertEquals(listOf("Unciv"), repo.names("játék"), "kategória magyar neve")
        assertEquals(listOf("Aegis Authenticator"), repo.names("", CatalogCategory.SECURITY), "kategóriaszűrő")
        assertEquals(listOf(), repo.names("nincsilyenapp"))
        assertEquals(SAMPLE_APPS.map { it.second }.sortedBy { it.lowercase() }, repo.names(""), "szöveg nélkül: név szerint")
        assertEquals(1, repo.categoryCounts.first()[CatalogCategory.GAMES])
    }

    @Test
    fun refresh_acceptsNewerCatalog_thenUsesConditionalRequest() = runBlocking {
        val repo = repository()
        repo.ensureInitialized()
        enqueueSigned(catalogJson(2, SAMPLE.take(2)), etag = "\"v2\"")
        assertEquals(RefreshOutcome.Updated(appCount = 2, rejectedCount = 0), repo.refresh(force = true))
        val status = repo.status.first()
        assertEquals(CatalogSource.REMOTE, status.source)
        assertEquals(2L, status.serial)
        assertNull(https.server.takeRequest().headers["If-None-Match"], "a beépített katalógusnak nincs ETag-je")
        assertEquals("/dist/v1/catalog.json.sig", https.server.takeRequest().url.encodedPath, "a katalógus után az aláírása")

        https.server.enqueue(MockResponse.Builder().code(304).build())
        assertEquals(RefreshOutcome.Unchanged, repo.refresh(force = true))
        assertEquals("\"v2\"", https.server.takeRequest().headers["If-None-Match"], "változatlan adat nem töltődik le újra")
        assertEquals(2, repo.status.first().appCount)
    }

    @Test
    fun refresh_rejectsRollback_andKeepsCurrentCatalog() = runBlocking {
        val repo = repository()
        repo.ensureInitialized()
        enqueueSigned(catalogJson(3, SAMPLE))
        assertIs<RefreshOutcome.Updated>(repo.refresh(force = true))

        enqueueSigned(catalogJson(2, SAMPLE.take(1)))
        val outcome = assertIs<RefreshOutcome.Failed>(repo.refresh(force = true))
        assertEquals(CatalogErrorKind.REJECTED, outcome.kind)
        val status = repo.status.first()
        assertEquals(3L, status.serial)
        assertEquals(SAMPLE_APPS.size, status.appCount)
        assertEquals(CatalogErrorKind.REJECTED, status.lastError)
    }

    @Test
    fun refresh_invalidOrUnsupportedCatalog_keepsLastValid() = runBlocking {
        val repo = repository()
        repo.ensureInitialized()

        enqueueSigned("{\"schemaVersion\":1,\"apps\":[".toByteArray())
        assertEquals(CatalogErrorKind.INVALID, assertIs<RefreshOutcome.Failed>(repo.refresh(force = true)).kind)

        enqueueSigned("{\"schemaVersion\":2,\"items\":[]}".toByteArray())
        assertEquals(CatalogErrorKind.UNSUPPORTED_SCHEMA, assertIs<RefreshOutcome.Failed>(repo.refresh(force = true)).kind)
        assertEquals(CatalogErrorKind.UNSUPPORTED_SCHEMA, repo.status.first().lastError)

        https.server.enqueue(MockResponse.Builder().code(404).body("Not Found").build())
        assertEquals(CatalogErrorKind.HTTP, assertIs<RefreshOutcome.Failed>(repo.refresh(force = true)).kind)

        // A beépített katalógus továbbra is teljes egészében elérhető.
        assertEquals(SAMPLE_APPS.size, repo.search(SearchQuery.of("")).size)
        assertEquals(CatalogSource.BUNDLED, repo.status.first().source)
    }

    @Test
    fun refresh_networkFailure_keepsCatalog_andAutomaticRefreshIsThrottled() = runBlocking {
        val repo = repository()
        repo.ensureInitialized()
        https.close() // nincs szerver → kapcsolódási hiba (offline helyzet)
        assertEquals(CatalogErrorKind.NETWORK, assertIs<RefreshOutcome.Failed>(repo.refresh()).kind)
        assertEquals(RefreshOutcome.Skipped, repo.refresh(), "6 órán belül nincs új automatikus próbálkozás")
        clock.advance(Duration.ofHours(7))
        assertIs<RefreshOutcome.Failed>(repo.refresh())
        assertEquals(SAMPLE_APPS.size, repo.status.first().appCount)
        https = HttpsTestServer() // a tearDown miatt
    }

    @Test
    fun bundledCatalogBelowMinimumSerial_isNotLoaded() = runBlocking {
        val repo = repository(minimumSerial = 5)
        assertIs<InitOutcome.BundledCatalogInvalid>(repo.ensureInitialized())
        assertTrue(!repo.status.first().hasCatalog)
    }

    @Test
    fun largeCatalog_searchStaysFast() = runBlocking {
        val many = (1..5000).map { i -> Quad("app-$i", "Generated App $i", "gen$i/repo", listOf("kulcs$i", "tool", "segédprogram"), "utilities") }
        seed = signed(catalogJson(1, many))
        val repo = repository()
        assertEquals(InitOutcome.Seeded(5000), repo.ensureInitialized())
        repo.search(SearchQuery.of("warmup"))
        val prefixMs = measureTimeMillis { assertEquals(1, repo.search(SearchQuery.of("kulcs4321")).size) }
        val infixMs = measureTimeMillis { assertTrue(repo.search(SearchQuery.of("enerated app 49")).isNotEmpty()) }
        val browseMs = measureTimeMillis { assertEquals(CatalogRepository.DEFAULT_LIMIT, repo.search(SearchQuery.of("")).size) }
        println("5000 tétel – előtag: $prefixMs ms, részleges: $infixMs ms, böngészés: $browseMs ms (Robolectric)")
        assertTrue(prefixMs < 1000 && infixMs < 1000 && browseMs < 1000, "lassú keresés: $prefixMs / $infixMs / $browseMs ms")
    }

    private data class Quad(val id: String, val name: String, val repo: String, val keywords: List<String>, val category: String)

    private companion object {
        fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        /** A teszt saját, futásidőben generált kulcsa (privát kulcs nem kerül a repóba). */
        val KEY: KeyPair = keyPair()

        fun signatureFile(bytes: ByteArray, key: KeyPair = KEY, keyId: String = "test-key"): ByteArray {
            val der = Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(bytes); sign() }
            return """{"format":1,"signatures":[{"keyId":"$keyId","algorithm":"ecdsa-p256-sha256","value":"${Base64.getEncoder().encodeToString(der)}"}]}""".toByteArray()
        }

        fun signed(bytes: ByteArray) = SignedCatalog(bytes, signatureFile(bytes))

        /** (id, név, repó, kulcsszavak, kategória) */
        val SAMPLE = listOf(
            Quad("obtainium", "Obtainium", "ImranR98/Obtainium", listOf("update", "frissítés"), "system"),
            Quad("newpipe", "NewPipe", "TeamNewPipe/NewPipe", listOf("youtube", "videó"), "multimedia"),
            Quad("organic-maps", "Organic Maps", "organicmaps/organicmaps", listOf("térkép", "navigáció", "offline"), "maps"),
            Quad("aegis", "Aegis Authenticator", "beemdevelopment/Aegis", listOf("2fa", "totp"), "security"),
            Quad("unciv", "Unciv", "yairm210/Unciv", listOf("strategy", "4x"), "games"),
        )
        val SAMPLE_APPS: List<Pair<String, String>> = SAMPLE.map { it.id to it.name }

        fun catalogJson(serial: Long, apps: List<Quad>): ByteArray {
            val entries = apps.joinToString(",") { a ->
                val keywords = a.keywords.joinToString(",") { "\"$it\"" }
                """{"id":"${a.id}","name":"${a.name}","source":{"provider":"github","repository":"${a.repo}"},"keywords":[$keywords],"category":"${a.category}"}"""
            }
            return """{"schemaVersion":1,"catalogSerial":$serial,"generatedAt":"2026-11-10T00:00:00Z","expiresAt":"2027-02-08T00:00:00Z","apps":[$entries]}"""
                .toByteArray()
        }
    }

    // ---- Biztonsági audit: CAT-03 (a tárolt katalógus újraellenőrzése), CAT-04 (lejárat), CAT-09 (kéretlen 304) ----

    private fun repositoryTrusting(keyId: String, key: KeyPair, minimumSerial: Long = 1): CatalogRepository {
        val client = HttpClients.create("RepoShelf-Test/1") { https.trustTestCertificate(this) }
        val fetcher = HttpFetcher(client, RateLimitTracker(clock), ConcurrencyLimiter(2), clock, random = Random(1), sleep = {})
        return CatalogRepository(
            dao = db.catalogDao(),
            fetcher = fetcher,
            config = CatalogConfig(
                url = https.server.url("/dist/v1/catalog.json"),
                signatureUrl = https.server.url("/dist/v1/catalog.json.sig"),
                verifier = CatalogSignatureVerifier(listOf(TrustedCatalogKey(keyId, key.public))),
                policy = HttpPolicy.of(https.host, maxBodyBytes = 8 * HttpPolicy.MIB),
                minimumSerial = minimumSerial,
            ),
            bundledCatalog = { seed },
            clock = clock,
        )
    }

    @Test
    fun storedCatalog_isDropped_whenItsSigningKeyIsNoLongerTrusted(): Unit = runBlocking {
        // A régi kulccsal: beépített (1), majd letöltött (5) katalógus.
        val old = repository()
        old.ensureInitialized()
        enqueueSigned(catalogJson(5, SAMPLE.take(2)))
        assertIs<RefreshOutcome.Updated>(old.refresh(force = true))
        assertEquals(5L, old.status.first().serial)
        assertEquals(InitOutcome.AlreadyPresent, repository().ensureInitialized(), "változatlan kulcslistánál a tárolt katalógus marad")

        // Alkalmazásfrissítés: a régi kulcs kikerült a megbízhatók közül, az új build katalógusát az új kulcs írta alá.
        val newKey = keyPair()
        val bundled = catalogJson(2, SAMPLE.take(3))
        seed = SignedCatalog(bundled, signatureFile(bundled, newKey, "catalog-new"))
        val updated = repositoryTrusting("catalog-new", newKey)
        assertEquals(InitOutcome.Seeded(3), updated.ensureInitialized())
        val status = updated.status.first()
        assertEquals(2L, status.serial, "a régi kulccsal elfogadott (5-ös) katalógus eltűnt")
        assertEquals(CatalogSource.BUNDLED, status.source)
        assertEquals(InitOutcome.AlreadyPresent, updated.ensureInitialized())

        // Ha a beépített katalógus sem pótolja (nem megbízható aláírás), a régi kulcsú katalógus akkor sem marad használatban.
        val third = keyPair()
        assertIs<InitOutcome.BundledCatalogInvalid>(repositoryTrusting("catalog-third", third).ensureInitialized())
        assertTrue(!updated.status.first().hasCatalog)
    }

    @Test
    fun storedCatalogBelowTheNewMinimumSerial_isReplacedByTheBundledOne(): Unit = runBlocking {
        repository().ensureInitialized()
        assertEquals(1L, repository().status.first().serial)
        // Az új build magasabb minimumot és újabb beépített katalógust hoz.
        seed = signed(catalogJson(4, SAMPLE.take(2)))
        val repo = repository(minimumSerial = 3)
        assertEquals(InitOutcome.Seeded(2), repo.ensureInitialized())
        assertEquals(4L, repo.status.first().serial)
    }

    @Test
    fun absurdSerial_cannotLockTheClient(): Unit = runBlocking {
        val repo = repository()
        repo.ensureInitialized()
        enqueueSigned(catalogJson(Long.MAX_VALUE, SAMPLE))
        assertEquals(CatalogErrorKind.INVALID, assertIs<RefreshOutcome.Failed>(repo.refresh(force = true)).kind)
        // A következő, szabályos katalógus elfogadható marad.
        enqueueSigned(catalogJson(2, SAMPLE))
        assertIs<RefreshOutcome.Updated>(repo.refresh(force = true))
        assertEquals(2L, repo.status.first().serial)
    }

    @Test
    fun expiredRemoteCatalog_isRejected_butAnExpiredBundledCatalogStillLoads(): Unit = runBlocking {
        // A katalógusok 2027-02-08-ig érvényesek; az óra ezután jár.
        clock.advance(Duration.ofDays(120))
        val repo = repository()
        assertIs<InitOutcome.Seeded>(repo.ensureInitialized())
        assertTrue(repo.status.first().isExpired(clock.instant()), "a felület jelzi, hogy a beépített katalógus elavult")
        // Visszajátszott, lejárt (de érvényesen aláírt, magasabb sorszámú) katalógus: nem váltja le a meglévőt.
        enqueueSigned(catalogJson(9, SAMPLE.take(1)))
        val failed = assertIs<RefreshOutcome.Failed>(repo.refresh(force = true))
        assertEquals(CatalogErrorKind.REJECTED, failed.kind)
        assertTrue(failed.detail.orEmpty().contains("RejectExpired"), failed.detail)
        val status = repo.status.first()
        assertEquals(1L, status.serial)
        assertEquals(SAMPLE_APPS.size, status.appCount)
    }

    @Test
    fun unsolicited304_isNotRecordedAsASuccessfulRefresh(): Unit = runBlocking {
        val repo = repository()
        repo.ensureInitialized()
        // A beépített katalógusnál nem küldünk validátort: a 304 itt szabálytalan válasz.
        https.server.enqueue(MockResponse.Builder().code(304).addHeader("ETag", "\"fake\"").build())
        val failed = assertIs<RefreshOutcome.Failed>(repo.refresh(force = true))
        assertEquals(CatalogErrorKind.HTTP, failed.kind)
        val status = repo.status.first()
        assertEquals(CatalogSource.BUNDLED, status.source)
        assertNull(status.lastSuccessAt)
        assertEquals(CatalogErrorKind.HTTP, status.lastError)
    }
}
