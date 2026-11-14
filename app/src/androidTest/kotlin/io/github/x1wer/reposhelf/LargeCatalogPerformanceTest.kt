// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf

import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.x1wer.reposhelf.core.catalog.CatalogSignatureVerifier
import io.github.x1wer.reposhelf.core.catalog.SearchQuery
import io.github.x1wer.reposhelf.core.catalog.TrustedCatalogKey
import io.github.x1wer.reposhelf.core.data.catalog.CatalogConfig
import io.github.x1wer.reposhelf.core.data.catalog.CatalogRepository
import io.github.x1wer.reposhelf.core.data.catalog.InitOutcome
import io.github.x1wer.reposhelf.core.data.catalog.SignedCatalog
import io.github.x1wer.reposhelf.core.data.db.AppDatabase
import io.github.x1wer.reposhelf.core.network.ConcurrencyLimiter
import io.github.x1wer.reposhelf.core.network.HttpClients
import io.github.x1wer.reposhelf.core.network.HttpFetcher
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.network.RateLimitTracker
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Teljesítménymérés a készüléken (B. 11.5.): egy 5000 tételes, generált, aláírt katalógus betöltése (aláírás-ellenőrzés,
 * feldolgozás, Room + FTS4) és keresése a valódi platform-SQLite-tal. Az app adatbázisát nem érinti (memóriabeli
 * adatbázis), hálózati kérés nincs. Futtatás: lásd a CLAUDE.md „Műszeres tesztek” pontját (`-e class …LargeCatalogPerformanceTest`).
 */
@RunWith(AndroidJUnit4::class)
class LargeCatalogPerformanceTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()

    @After
    fun tearDown() = db.close()

    @Test
    fun fiveThousandEntries_loadAndSearchQuickly() = runBlocking {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val now = Instant.now()
        val entries = (1..COUNT).joinToString(",") { i ->
            """{"id":"app-$i","name":"Generated App $i","source":{"provider":"github","repository":"gen$i/repo"},""" +
                """"keywords":["kulcs$i","tool","segédprogram"],"category":"utilities"}"""
        }
        val json = ("""{"schemaVersion":1,"catalogSerial":1,"generatedAt":"${now.minus(Duration.ofDays(1))}",""" +
            """"expiresAt":"${now.plus(Duration.ofDays(90))}","apps":[$entries]}""").toByteArray()
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(json); sign() }
        val signature = """{"format":1,"signatures":[{"keyId":"perf-key","algorithm":"ecdsa-p256-sha256","value":"${Base64.getEncoder().encodeToString(der)}"}]}"""
            .toByteArray()

        val clock = Clock.systemUTC()
        val repository = CatalogRepository(
            dao = db.catalogDao(),
            fetcher = HttpFetcher(HttpClients.create("RepoShelf-Test/1"), RateLimitTracker(clock), ConcurrencyLimiter(1), clock),
            config = CatalogConfig(
                url = "https://raw.githubusercontent.com/example/catalog/main/dist/v1/catalog.json".toHttpUrl(),
                signatureUrl = "https://raw.githubusercontent.com/example/catalog/main/dist/v1/catalog.json.sig".toHttpUrl(),
                verifier = CatalogSignatureVerifier(listOf(TrustedCatalogKey("perf-key", key.public))),
                policy = HttpPolicy.of("raw.githubusercontent.com", maxBodyBytes = 8 * HttpPolicy.MIB),
                minimumSerial = 1,
            ),
            bundledCatalog = { SignedCatalog(json, signature) },
            clock = clock,
        )

        val loadStart = System.nanoTime()
        assertEquals(InitOutcome.Seeded(COUNT), repository.ensureInitialized())
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000

        repository.search(SearchQuery.of("warmup"))
        val prefix = medianMs { assertEquals(1, repository.search(SearchQuery.of("kulcs4321")).size) }
        val multiWord = medianMs { assertTrue(repository.search(SearchQuery.of("generated 4321")).isNotEmpty()) }
        val infix = medianMs { assertTrue(repository.search(SearchQuery.of("enerated app 49")).isNotEmpty()) }
        val broad = medianMs { assertEquals(CatalogRepository.DEFAULT_LIMIT, repository.search(SearchQuery.of("tool")).size) }
        val browse = medianMs { assertEquals(CatalogRepository.DEFAULT_LIMIT, repository.search(SearchQuery.of("")).size) }

        val summary = "json=${json.size} B, load=$loadMs ms, prefix=$prefix ms, multiWord=$multiWord ms, infix=$infix ms, " +
            "broad=$broad ms, browse=$browse ms (median of $RUNS, API ${android.os.Build.VERSION.SDK_INT})"
        Log.i(TAG, summary)
        println("$TAG: $summary")
        // Bőkezű felső korlátok: a cél a gépelés közbeni, érezhető késés nélküli keresés.
        assertTrue(loadMs < 15_000, summary)
        assertTrue(listOf(prefix, multiWord, infix, broad, browse).all { it < 500 }, summary)
    }

    private suspend fun medianMs(block: suspend () -> Unit): Long {
        val samples = (1..RUNS).map {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1_000_000
        }
        return samples.sorted()[RUNS / 2]
    }

    private companion object {
        const val TAG = "RepoShelfPerf"
        const val COUNT = 5000
        const val RUNS = 7
    }
}
