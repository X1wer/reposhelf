// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf

import android.content.Context
import android.util.Log
import io.github.x1wer.reposhelf.core.apk.ApkVerifier
import io.github.x1wer.reposhelf.core.data.catalog.CatalogConfig
import io.github.x1wer.reposhelf.core.data.catalog.CatalogRepository
import io.github.x1wer.reposhelf.core.data.db.AppDatabase
import io.github.x1wer.reposhelf.core.data.details.AppDetailsRepository
import io.github.x1wer.reposhelf.core.installer.InstallCoordinator
import io.github.x1wer.reposhelf.core.installer.InstalledPackages
import io.github.x1wer.reposhelf.core.installer.PackageInstallerGateway
import io.github.x1wer.reposhelf.core.installer.PlatformApkInspector
import io.github.x1wer.reposhelf.core.network.ConcurrencyLimiter
import io.github.x1wer.reposhelf.core.network.FileDownloader
import io.github.x1wer.reposhelf.core.network.HttpClients
import io.github.x1wer.reposhelf.core.network.HttpFetcher
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.network.ImageHttpClient
import io.github.x1wer.reposhelf.core.network.RateLimitTracker
import io.github.x1wer.reposhelf.core.source.ProviderRegistry
import io.github.x1wer.reposhelf.core.source.github.GitHubProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Clock

/**
 * Kézi függőséginjektálás (D-011): az egész alkalmazás egyetlen példányban létrehozott szolgáltatásai.
 * Minden komponens lustán, első használatkor jön létre.
 */
class AppContainer(private val context: Context) {

    val clock: Clock = Clock.systemUTC()

    val rateLimits = RateLimitTracker(clock)

    /** A User-Agent az app nevét és verzióját tartalmazza, személyes adatot nem (GitHub-követelmény). */
    private val userAgent = "RepoShelf/${BuildConfig.VERSION_NAME} (Android)"

    private val httpClient by lazy { HttpClients.create(userAgent = userAgent) }

    val imageHttpClient by lazy { ImageHttpClient.create(userAgent) }

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

    val appDetails by lazy { AppDetailsRepository(sourceProviders, clock) }

    val installedPackages by lazy { InstalledPackages(context) }

    val installCoordinator by lazy {
        InstallCoordinator(
            downloader = FileDownloader(httpClient),
            verifier = ApkVerifier(PlatformApkInspector(context)),
            installed = installedPackages,
            gateway = PackageInstallerGateway(context),
            // Privát, backupból kizárt könyvtár (docs/ARCHITECTURE.md 9. pont, 4. lépés).
            downloadsDir = File(context.noBackupFilesDir, "downloads"),
        )
    }

    /**
     * Az APK-ba csomagolt kezdőkatalógus: első indításkor és offline is elérhető (D-025).
     * Csak az I/O-hibát nyeli el; programhiba (pl. hiányzó API egy régebbi Androidon) ne váljon csendben
     * „nincs katalógus” állapottá (D-070).
     */
    private suspend fun readBundledCatalog(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            context.assets.open(BUNDLED_CATALOG_ASSET).use { input -> input.readAtMost(MAX_BUNDLED_BYTES) }
        } catch (e: IOException) {
            Log.w(TAG, "A beépített katalógus nem olvasható", e)
            null
        }
    }

    companion object {
        private const val TAG = "AppContainer"
        private const val MAX_PARALLEL_REQUESTS = 2
        private const val BUNDLED_CATALOG_ASSET = "catalog/catalog-v1.json"
        private const val MAX_BUNDLED_BYTES = 8 * 1024 * 1024

        /** A letölthető APK felső mérethatára (C. 21.). */
        const val MAX_APK_BYTES = 1024L * 1024 * 1024
    }
}

/**
 * Legfeljebb [limit] bájtot olvas be. Ha a forrás ennél hosszabb, `null`-t ad.
 * Az `InputStream.readNBytes` Androidon csak API 33-tól létezik, ezért saját, API 28-on is működő ciklus.
 */
internal fun InputStream.readAtMost(limit: Int): ByteArray? {
    require(limit >= 0)
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        // Legfeljebb egy bájttal olvasunk a korlát fölé: így derül ki, hogy a forrás hosszabb-e.
        val read = read(buffer, 0, minOf(buffer.size, limit + 1 - total))
        if (read < 0) return out.toByteArray()
        total += read
        if (total > limit) return null
        out.write(buffer, 0, read)
    }
}
