// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.discover

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.x1wer.reposhelf.core.data.catalog.CatalogConfig
import io.github.x1wer.reposhelf.core.data.catalog.CatalogRepository
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
            config = CatalogConfig("https://127.0.0.1:1/catalog.json".toHttpUrl(), HttpPolicy.of("127.0.0.1", maxBodyBytes = 1024), minimumSerial = 1),
            bundledCatalog = { File("src/main/assets/catalog/catalog-v1.json").readBytes() },
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

        viewModel.onQueryChange("pipe")
        val searched = withTimeout(15_000) { viewModel.uiState.first { it.query == "pipe" && it.results.size == 1 } }
        assertEquals("NewPipe", searched.results.single().name)

        // A szűrő azonnal látszik az állapotban, a (késleltetett) keresés eredménye kicsit később: a végállapotot várjuk.
        // Ha a szűrés hibás, a várakozás időtúllépéssel bukik.
        val expectedSecurity = loaded.results.count { it.category == CatalogCategory.SECURITY }
        assertTrue(expectedSecurity in 1 until loaded.results.size)
        viewModel.onQueryChange("")
        viewModel.onCategorySelected(CatalogCategory.SECURITY)
        withTimeout(15_000) {
            viewModel.uiState.first { state ->
                state.category == CatalogCategory.SECURITY && state.results.size == expectedSecurity &&
                    state.results.all { it.category == CatalogCategory.SECURITY }
            }
        }
    }
}
