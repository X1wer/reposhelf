// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf

import android.content.Context
import io.github.x1wer.reposhelf.core.data.catalog.CatalogConfig
import io.github.x1wer.reposhelf.core.data.catalog.CatalogRepository
import io.github.x1wer.reposhelf.core.data.db.AppDatabase
import io.github.x1wer.reposhelf.core.network.ConcurrencyLimiter
import io.github.x1wer.reposhelf.core.network.HttpClients
import io.github.x1wer.reposhelf.core.network.HttpFetcher
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.network.RateLimitTracker
import io.github.x1wer.reposhelf.core.source.ProviderRegistry
import io.github.x1wer.reposhelf.core.source.github.GitHubProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Clock

/**
 * Kézi függőséginjektálás (D-011): az egész alkalmazás egyetlen példányban létrehozott szolgáltatásai.
 * Minden komponens lustán, első használatkor jön létre.
 */
class AppContainer(private val context: Context) {

    val clock: Clock = Clock.systemUTC()

    val rateLimits = RateLimitTracker(clock)

    private val httpClient by lazy {
        // A User-Agent az app nevét és verzióját tartalmazza, személyes adatot nem (GitHub-követelmény).
        HttpClients.create(userAgent = "RepoShelf/${BuildConfig.VERSION_NAME} (Android)")
    }

    val fetcher by lazy { HttpFetcher(httpClient, rateLimits, ConcurrencyLimiter(MAX_PARALLEL_REQUESTS), clock) }

    val database by lazy { AppDatabase.create(context) }

    val catalogRepository by lazy {
        CatalogRepository(
            dao = database.catalogDao(),
            fetcher = fetcher,
            config = CatalogConfig(
                url = BuildConfig.CATALOG_URL.toHttpUrl(),
                policy = HttpPolicy.of("raw.githubusercontent.com", maxBodyBytes = 8 * HttpPolicy.MIB),
                minimumSerial = BuildConfig.MIN_CATALOG_SERIAL,
            ),
            bundledCatalog = ::readBundledCatalog,
            clock = clock,
        )
    }

    /** A forrásszolgáltatók. Jelenleg csak a GitHub (C. 4.). */
    val sourceProviders by lazy { ProviderRegistry(listOf(GitHubProvider(fetcher))) }

    /** Az APK-ba csomagolt kezdőkatalógus: első indításkor és offline is elérhető (D-025). */
    private suspend fun readBundledCatalog(): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(BUNDLED_CATALOG_ASSET).use { input ->
                val bytes = input.readNBytes(MAX_BUNDLED_BYTES + 1)
                bytes.takeIf { it.size <= MAX_BUNDLED_BYTES }
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_PARALLEL_REQUESTS = 2
        const val BUNDLED_CATALOG_ASSET = "catalog/catalog-v1.json"
        const val MAX_BUNDLED_BYTES = 8 * 1024 * 1024
    }
}
