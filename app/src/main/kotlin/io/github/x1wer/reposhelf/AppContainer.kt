// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf

import android.content.Context
import android.util.Log
import io.github.x1wer.reposhelf.core.apk.ApkVerifier
import io.github.x1wer.reposhelf.core.data.catalog.CatalogConfig
import io.github.x1wer.reposhelf.core.data.catalog.CatalogRepository
import io.github.x1wer.reposhelf.core.data.catalog.SignedCatalog
import io.github.x1wer.reposhelf.core.catalog.CatalogSignatureVerifier
import io.github.x1wer.reposhelf.background.BackgroundUpdates
import io.github.x1wer.reposhelf.background.Notifier
import io.github.x1wer.reposhelf.core.data.db.AppDatabase
import io.github.x1wer.reposhelf.core.data.safety.SecurityWarnings
import io.github.x1wer.reposhelf.core.data.safety.UpdateHistory
import io.github.x1wer.reposhelf.core.data.selfupdate.SelfUpdateConfig
import io.github.x1wer.reposhelf.core.data.selfupdate.SelfUpdates
import io.github.x1wer.reposhelf.core.installer.InstallSessions
import io.github.x1wer.reposhelf.selfupdate.SelfUpdater
import io.github.x1wer.reposhelf.core.data.notifications.NotificationLog
import io.github.x1wer.reposhelf.core.data.settings.SettingsRepository
import io.github.x1wer.reposhelf.core.data.details.AppDetailsRepository
import io.github.x1wer.reposhelf.core.data.details.RoomDetailsStore
import io.github.x1wer.reposhelf.core.data.tracked.RequestBudget
import io.github.x1wer.reposhelf.core.data.tracked.TrackedAppsRepository
import io.github.x1wer.reposhelf.core.installer.InstallCoordinator
import io.github.x1wer.reposhelf.core.installer.InstalledPackages
import io.github.x1wer.reposhelf.core.installer.PackageInstallerGateway
import io.github.x1wer.reposhelf.core.installer.PlatformApkInspector
import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.network.ConcurrencyLimiter
import io.github.x1wer.reposhelf.core.network.FileDownloader
import io.github.x1wer.reposhelf.core.network.HttpClients
import io.github.x1wer.reposhelf.core.network.HttpFetcher
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.network.ImageHttpClient
import io.github.x1wer.reposhelf.core.network.RateLimitTracker
import io.github.x1wer.reposhelf.core.source.ProviderRegistry
import io.github.x1wer.reposhelf.core.source.github.GitHubProvider
import io.github.x1wer.reposhelf.core.update.ApkDownloader
import io.github.x1wer.reposhelf.core.update.GlobalSettings
import io.github.x1wer.reposhelf.core.update.InstallInitiator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Instant

/**
 * Kézi függőséginjektálás (D-011): az egész alkalmazás egyetlen példányban létrehozott szolgáltatásai.
 * Minden komponens lustán, első használatkor jön létre.
 */
class AppContainer(private val context: Context) {

    val clock: Clock = Clock.systemUTC()

    /** Az alkalmazás élettartamához kötött háttér-hatókör (háttérben indított telepítések eredménye, ütemezés). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Globális beállítások (C. 12.5–12.6.). */
    val settings by lazy { SettingsRepository.create(context) }

    /** A beállítások pillanatnyi értéke szinkron olvasáshoz (pl. update ownership a telepítéskor). */
    val settingsState: StateFlow<GlobalSettings> by lazy { settings.settings.stateIn(appScope, SharingStarted.Eagerly, GlobalSettings()) }

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
                signatureUrl = "${BuildConfig.CATALOG_URL}.sig".toHttpUrl(),
                verifier = CatalogSignatureVerifier(CatalogSignatureVerifier.parseKeys(BuildConfig.CATALOG_PUBLIC_KEYS)),
                policy = HttpPolicy.of("raw.githubusercontent.com", maxBodyBytes = 8 * HttpPolicy.MIB),
                minimumSerial = BuildConfig.MIN_CATALOG_SERIAL,
            ),
            bundledCatalog = ::readBundledCatalog,
            clock = clock,
        )
    }

    /** A forrásszolgáltatók. Jelenleg csak a GitHub (C. 4.). */
    val sourceProviders by lazy { ProviderRegistry(listOf(GitHubProvider(fetcher))) }

    val appDetails by lazy { AppDetailsRepository(sourceProviders, clock, store = RoomDetailsStore(database)) }

    val installedPackages by lazy { InstalledPackages(context) }

    /** Az internetkapcsolat állapota a felületnek (C. 24.). */
    val connectivity by lazy { io.github.x1wer.reposhelf.ui.common.ConnectivityMonitor(context) }

    /**
     * API-keret a háttérben futó (nem interaktív) ellenőrzésekhez: mindig marad legalább [UI_RESERVE_REQUESTS] kérés a
     * felületnek (D-051).
     */
    val requestBudget = object : RequestBudget {
        override fun canSpend(provider: ProviderId, requests: Int): Boolean =
            rateLimits.hasBudget(rateLimitScope(provider), reserve = UI_RESERVE_REQUESTS + requests - 1)

        override fun resumesAt(provider: ProviderId): Instant? {
            val scope = rateLimitScope(provider)
            return rateLimits.blockedUntil(scope) ?: rateLimits.snapshot(scope)?.resetAt
        }
    }

    private fun rateLimitScope(provider: ProviderId): String = when (provider) {
        ProviderId.GITHUB -> GitHubProvider.RATE_LIMIT_SCOPE
        ProviderId.CODEBERG -> "codeberg" // letiltva; regisztrált szolgáltató nincs hozzá
    }

    /** Biztonsági figyelmeztetések (C. 20.). */
    val warnings by lazy { SecurityWarnings(database, clock) }

    /** Frissítési előzmények (C. 15.). */
    val history by lazy { UpdateHistory(database, clock) }

    /** Követett, kedvenc és saját hozzáadású alkalmazások, frissítésellenőrzés (C. 9–12.1., 19.). */
    val trackedApps by lazy {
        TrackedAppsRepository(database, sourceProviders, installedPackages, installedPackages::deviceProfile, requestBudget, clock, warnings) { settings.current() }
    }

    /** A RepoShelf saját frissítése (C. 14.): a repó és az elvárt aláíró build-paraméter (docs/ARCHITECTURE.md 14.). */
    val selfUpdater by lazy {
        val config = SelfUpdateConfig.from(
            repo = BuildConfig.SELF_UPDATE_REPO,
            signers = BuildConfig.SELF_UPDATE_SIGNERS,
            packageName = context.packageName,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            versionName = BuildConfig.VERSION_NAME,
        )
        val core = SelfUpdates(config, sourceProviders, trackedApps.analyses, settings, warnings, history, installedPackages::deviceProfile, clock)
        SelfUpdater(context, this, core, InstallSessions(context), appScope)
    }

    val installCoordinator by lazy {
        InstallCoordinator(
            // Privát, backupból kizárt könyvtár (docs/ARCHITECTURE.md 9. pont, 4. lépés).
            downloader = ApkDownloader(FileDownloader(httpClient), File(context.noBackupFilesDir, "downloads")),
            verifier = ApkVerifier(PlatformApkInspector(context)),
            installed = installedPackages,
            initiator = InstallInitiator(PackageInstallerGateway(context) { settingsState.value.requestUpdateOwnership }),
            storedPins = trackedApps::pinnedSigners,
        )
    }

    val notificationLog by lazy { NotificationLog(database, clock) }

    val notifier by lazy { Notifier(context, notificationLog) }

    /** Háttérbeli értesítés, letöltés és telepítés (C. 12.3–12.5.). */
    val backgroundUpdates by lazy { BackgroundUpdates(context, this, appScope, clock) }

    /**
     * Az APK-ba csomagolt kezdőkatalógus: első indításkor és offline is elérhető (D-025).
     * Csak az I/O-hibát nyeli el; programhiba (pl. hiányzó API egy régebbi Androidon) ne váljon csendben
     * „nincs katalógus” állapottá (D-070).
     */
    private suspend fun readBundledCatalog(): SignedCatalog? = withContext(Dispatchers.IO) {
        try {
            val catalog = context.assets.open(BUNDLED_CATALOG_ASSET).use { input -> input.readAtMost(MAX_BUNDLED_BYTES) } ?: return@withContext null
            // Az aláírás a beépített katalógusnál is kötelező (ugyanaz a szabály, mint a letöltöttnél).
            val signature = context.assets.open("$BUNDLED_CATALOG_ASSET.sig").use { input -> input.readAtMost(CatalogSignatureVerifier.MAX_SIGNATURE_FILE) }
            SignedCatalog(catalog, signature)
        } catch (e: IOException) {
            Log.w(TAG, "A beépített katalógus nem olvasható", e)
            null
        }
    }

    companion object {
        private const val TAG = "AppContainer"
        private const val MAX_PARALLEL_REQUESTS = 2

        /** Ennyi API-kérés mindig megmarad a felhasználói felületnek (adatlap, hozzáadás; D-051). */
        private const val UI_RESERVE_REQUESTS = 15
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
