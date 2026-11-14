// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.discover

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.x1wer.reposhelf.core.data.catalog.CatalogConfig
import io.github.x1wer.reposhelf.core.data.catalog.CatalogRepository
import io.github.x1wer.reposhelf.core.data.catalog.SignedCatalog
import io.github.x1wer.reposhelf.core.catalog.CatalogSignatureVerifier
import io.github.x1wer.reposhelf.BuildConfig
import io.github.x1wer.reposhelf.core.data.db.AppDatabase
import io.github.x1wer.reposhelf.core.model.CatalogCategory
import io.github.x1wer.reposhelf.core.network.ConcurrencyLimiter
import io.github.x1wer.reposhelf.core.network.HttpClients
import io.github.x1wer.reposhelf.core.network.HttpFetcher
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.network.RateLimitTracker
import io.github.x1wer.reposhelf.core.network.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regressziós teszt az emulátoron talált hibára: a kezdőkatalógus betöltése után a (még üres) keresés
 * eredménye nem frissült, így a lista üres maradt. Offline helyzetet szimulál (a katalógus-URL nem érhető el).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiscoverViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var viewModel: DiscoverViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        val fetcher = HttpFetcher(HttpClients.create("RepoShelf-Test/1"), RateLimitTracker(java.time.Clock.systemUTC()), ConcurrencyLimiter(1), sleep = {})
        val repository = CatalogRepository(
            dao = db.catalogDao(),
            fetcher = fetcher,
            // Lezárt port: azonnali kapcsolódási hiba, mint offline állapotban.
            config = CatalogConfig(
                url = "https://127.0.0.1:1/catalog.json".toHttpUrl(),
                signatureUrl = "https://127.0.0.1:1/catalog.json.sig".toHttpUrl(),
                verifier = CatalogSignatureVerifier(CatalogSignatureVerifier.parseKeys(BuildConfig.CATALOG_PUBLIC_KEYS)),
                policy = HttpPolicy.of("127.0.0.1", maxBodyBytes = 1024),
                minimumSerial = 1,
            ),
            bundledCatalog = {
                SignedCatalog(File("src/main/assets/catalog/catalog-v1.json").readBytes(), File("src/main/assets/catalog/catalog-v1.json.sig").readBytes())
            },
        )
        viewModel = DiscoverViewModel(repository)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun bundledCatalogResults_appearAfterInitialization_evenWhenOffline(): Unit = runBlocking {
        // A találati lista és a fejléc állapota (status) két külön feliratkozásból érkezik, így egy pillanatra
        // lehet olyan állapot, ahol a lista már kész, a darabszám még nem. A beállt állapotot várjuk.
        val loaded = withTimeout(15_000) {
            viewModel.uiState.first { !it.isInitializing && it.results.isNotEmpty() && it.status.appCount > 0 }
        }
        assertEquals(loaded.status.appCount, loaded.results.size, "szöveg nélkül a teljes katalógus látszik")

        // Szó belsejében lévő részleges egyezés a beépített katalógus egy valódi bejegyzésére (a tartalomtól függetlenül):
        // az első szó 2–4. betűje (pl. „YTDLP Studio” → „tdl”).
        val target = loaded.results.first()
        val firstWord = target.name.lowercase().takeWhile { it.isLetterOrDigit() }
        assertTrue(firstWord.length >= 4, "a teszthez legalább 4 betűs első szó kell: ${target.name}")
        val infix = firstWord.substring(1, 4)
        viewModel.onQueryChange(infix)
        withTimeout(15_000) { viewModel.uiState.first { it.query == infix && it.results.any { r -> r.id == target.id } } }

        // A szűrő azonnal látszik az állapotban, a (késleltetett) keresés eredménye kicsit később: a végállapotot várjuk.
        // Ha a szűrés hibás, a várakozás időtúllépéssel bukik.
        val category = target.category
        val expected = loaded.results.count { it.category == category }
        viewModel.onQueryChange("")
        viewModel.onCategorySelected(category)
        withTimeout(15_000) {
            viewModel.uiState.first { state ->
                state.category == category && state.results.size == expected && state.results.all { it.category == category }
            }
        }
        // Olyan kategória, amelyben nincs bejegyzés: üres találati lista.
        val absent = CatalogCategory.entries.firstOrNull { c -> loaded.results.none { it.category == c } }
        if (absent != null) {
            viewModel.onCategorySelected(absent)
            withTimeout(15_000) { viewModel.uiState.first { it.category == absent && !it.isInitializing && it.results.isEmpty() } }
        }
    }
}
