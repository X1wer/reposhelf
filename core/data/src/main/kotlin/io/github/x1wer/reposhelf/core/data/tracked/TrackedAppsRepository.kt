// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.tracked

import android.database.sqlite.SQLiteConstraintException
import io.github.x1wer.reposhelf.core.apk.CheckStatus
import io.github.x1wer.reposhelf.core.apk.DeviceProfile
import io.github.x1wer.reposhelf.core.apk.DownloadedApk
import io.github.x1wer.reposhelf.core.apk.VerificationReport
import io.github.x1wer.reposhelf.core.data.catalog.toModelOrNull
import io.github.x1wer.reposhelf.core.data.db.ApkAnalysisEntity
import io.github.x1wer.reposhelf.core.data.db.AppDatabase
import io.github.x1wer.reposhelf.core.data.db.AppSettingsOverrideEntity
import io.github.x1wer.reposhelf.core.data.db.SignerPinEntity
import io.github.x1wer.reposhelf.core.data.db.TrackedAppEntity
import io.github.x1wer.reposhelf.core.data.db.TrackedAppWithSettings
import io.github.x1wer.reposhelf.core.model.CatalogApp
import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.ReleaseAsset
import io.github.x1wer.reposhelf.core.model.RepoMetadata
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.model.Severity
import io.github.x1wer.reposhelf.core.source.Fetch
import io.github.x1wer.reposhelf.core.source.ProviderRegistry
import io.github.x1wer.reposhelf.core.update.AppOverrides
import io.github.x1wer.reposhelf.core.update.AppPolicy
import io.github.x1wer.reposhelf.core.update.GlobalSettings
import io.github.x1wer.reposhelf.core.update.InstallOutcome
import io.github.x1wer.reposhelf.core.update.PolicyResolver
import io.github.x1wer.reposhelf.core.update.InstalledAppLookup
import io.github.x1wer.reposhelf.core.update.InstalledRelease
import io.github.x1wer.reposhelf.core.update.InstalledVersion
import io.github.x1wer.reposhelf.core.update.KnownAnalyses
import io.github.x1wer.reposhelf.core.update.KnownAnalysis
import io.github.x1wer.reposhelf.core.update.ReleaseDiscovery
import io.github.x1wer.reposhelf.core.update.RepoIdentity
import io.github.x1wer.reposhelf.core.update.StepResult
import io.github.x1wer.reposhelf.core.update.TrackedSource
import io.github.x1wer.reposhelf.core.update.UpdateCheck
import io.github.x1wer.reposhelf.core.update.UpdateCheckInput
import io.github.x1wer.reposhelf.core.update.UpdateChecker
import io.github.x1wer.reposhelf.core.update.UpdateFailure
import io.github.x1wer.reposhelf.core.update.UpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant

/** Mennyi API-kérés költhető még (C. 16.1., D-051): a felületnek mindig marad tartalék. */
interface RequestBudget {
    fun canSpend(provider: ProviderId, requests: Int): Boolean
    fun resumesAt(provider: ProviderId): Instant?

    companion object {
        val UNLIMITED: RequestBudget = object : RequestBudget {
            override fun canSpend(provider: ProviderId, requests: Int) = true
            override fun resumesAt(provider: ProviderId): Instant? = null
        }
    }
}

/**
 * A követett, kedvenc és saját hozzáadású alkalmazások kezelése (C. 9–11., 12.1., 19.).
 *
 * - Saját repó hozzáadása URL-ből: [preview] (1–5. lépés, mentés nélkül), majd [follow] (6. lépés); a frissítéseket
 *   (7. lépés) a [checkOne] / [checkAll] keresi.
 * - Az aláíró CSOMAGONKÉNT rögzül (első telepítéskor vagy a már telepített példányról), és a követés megszüntetése
 *   után is megmarad.
 * - Az ellenőrzések egymás után futnak, a legrégebben ellenőrzött először, és megállnak, ha az API-keret fogy.
 */
class TrackedAppsRepository(
    db: AppDatabase,
    private val providers: ProviderRegistry,
    private val installed: InstalledAppLookup,
    private val device: () -> DeviceProfile,
    private val budget: RequestBudget,
    private val clock: Clock = Clock.systemUTC(),
    /** A globális beállítások (C. 12.5–12.6.): az alkalmazásonkénti felülírások ezekhez képest érvényesek. */
    private val globalSettings: suspend () -> GlobalSettings = { GlobalSettings() },
) {
    private val dao = db.trackedAppDao()
    private val catalogDao = db.catalogDao()
    private val discovery = ReleaseDiscovery(providers)
    private val analyses = object : KnownAnalyses {
        override suspend fun of(provider: ProviderId, assetId: Long): KnownAnalysis? =
            dao.analysis(provider.id, assetId)?.let { KnownAnalysis(it.versionCode, rejected = it.verdict == VERDICT_REJECTED) }
    }
    private val checker = UpdateChecker(discovery, analyses)
    private val checkMutex = Mutex()

    private val _progress = MutableStateFlow(CheckProgress())
    val progress: StateFlow<CheckProgress> = _progress.asStateFlow()

    fun observeAll(): Flow<List<TrackedApp>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toModel() } }

    fun observe(id: Long): Flow<TrackedApp?> = dao.observe(id).map { it?.toModel() }

    suspend fun get(id: Long): TrackedApp? = dao.byId(id)?.toModel()

    suspend fun forCatalogApp(catalogId: String): TrackedApp? = dao.byCatalogId(catalogId)?.let { dao.byId(it.id) }?.toModel()

    // ---- Saját repó hozzáadása (C. 9.) ----

    suspend fun preview(input: String): AddRepoPreview {
        // 1. lépés: azonosítás (hálózati kérés nélkül). A bemenetből csak `owner/name` lesz; a kérés mindig az API-hosztra megy.
        val ref = providers.parseRepoUrl(input.trim().take(MAX_INPUT)) ?: return AddRepoPreview.InvalidUrl
        dao.byRepoKey(ref.key)?.let { return AddRepoPreview.AlreadyTracked(it.id, it.isFollowed) }
        // 2–4. lépés: elérhetőség, alapadatok, kiadások.
        val found = when (val result = discovery.discover(TrackedSource(ref, null, null, includePrereleases = false))) {
            is StepResult.Ok -> result
            is StepResult.Blocked -> return AddRepoPreview.Failed("blocked")
            is StepResult.Failed -> return when (val failure = result.failure) {
                UpdateFailure.RepoNotFound -> AddRepoPreview.NotFound(ref)
                is UpdateFailure.RepoUnavailable -> AddRepoPreview.Unavailable(ref, failure.reason)
                is UpdateFailure.RateLimited -> AddRepoPreview.RateLimited(failure.until)
                is UpdateFailure.Network -> AddRepoPreview.Failed(failure.detail)
                else -> AddRepoPreview.Failed(failure.toString().take(100))
            }
        }
        val repo = found.value.repo
        (dao.byRepoId(repo.ref.provider.id, repo.id.value) ?: dao.byRepoKey(repo.ref.key))?.let {
            return AddRepoPreview.AlreadyTracked(it.id, it.isFollowed)
        }
        val catalogApp = catalogDao.byRepo(repo.ref.provider.id, repo.id.value, repo.ref.owner, repo.ref.name)?.toModelOrNull()
        // Ha csak előzetes kiadásban van APK, azt is megmutatjuk (jelölve); követéskor a beállítás dönt.
        val discovered = found.value.let { if (it.candidate == null) it.copy(candidate = it.resolved.latestPrerelease) else it }
        val installedNow = catalogApp?.packageName?.let { installedVersion(it, null) }
        // 5. lépés: van-e telepíthető Android-csomag (és az eszközre illik-e).
        val check = checker.evaluate(discovered, found.findings, installedNow, catalogApp?.assetFilter, device())
        return AddRepoPreview.Found(repo, discovered.resolved, discovered.candidate, check, catalogApp)
    }

    /** 6. lépés: követés. A felhasználó dönthet arról, hogy telepíti-e, vagy csak a kiadásait követi (C. 9.). */
    suspend fun follow(found: AddRepoPreview.Found): Long {
        val repo = found.repo
        val catalog = found.catalogApp
        val now = clock.millis()
        // A csomagnév: a katalógusból, vagy egy korábbi (pl. a követés megszüntetése előtti) elfogadott elemzésből.
        val packageName = catalog?.packageName ?: dao.lastAcceptedPackage(repo.ref.key)
        val entity = newEntity(AppOrigin.USER, repo.ref, catalog?.id, repo.id.value, repo.ownerId, packageName, catalog?.name ?: repo.ref.name, now)
            .withCheck(found.check, now)
        val id = insertOrExisting(entity)
        packageName?.let { pinExistingInstall(it) }
        return id
    }

    // ---- Követés, kedvenc, beállítások (C. 10.) ----

    /** Egy katalógusbeli alkalmazás követése (vagy a meglévő sor visszakapcsolása). */
    suspend fun followCatalogApp(app: CatalogApp): Long {
        val existing = rowForCatalog(app)
        val packageName = app.packageName ?: existing?.packageName ?: dao.lastAcceptedPackage(app.repo.key)
        val id = if (existing != null) {
            dao.update(existing.copy(isFollowed = true, packageName = packageName))
            existing.id
        } else {
            insertOrExisting(newEntity(AppOrigin.CATALOG, app.repo, app.id, app.repositoryId?.value, null, packageName, app.name, clock.millis()))
        }
        packageName?.let { pinExistingInstall(it) }
        return id
    }

    suspend fun setFavoriteForCatalogApp(app: CatalogApp, favorite: Boolean): Long {
        val existing = rowForCatalog(app)
        if (existing != null) {
            dao.update(existing.copy(isFavorite = favorite))
            return existing.id
        }
        return insertOrExisting(
            newEntity(AppOrigin.CATALOG, app.repo, app.id, app.repositoryId?.value, null, app.packageName, app.name, clock.millis())
                .copy(isFollowed = false, isFavorite = favorite),
        )
    }

    suspend fun setFollowed(id: Long, followed: Boolean) = modify(id) { it.copy(isFollowed = followed) }

    suspend fun setFavorite(id: Long, favorite: Boolean) = modify(id) { it.copy(isFavorite = favorite) }

    suspend fun setCheckEnabled(id: Long, enabled: Boolean) = updateOverrides(id) { it.copy(checkEnabled = enabled) }

    suspend fun setIncludePrereleases(id: Long, include: Boolean) = updateOverrides(id) { it.copy(includePrereleases = include) }

    /** Alkalmazásonkénti felülírások (C. 12.6.); `null` mező = a globális beállítás érvényes. */
    suspend fun setOverrides(id: Long, overrides: AppOverrides) = updateOverrides(id) { overrides }

    private suspend fun updateOverrides(id: Long, change: (AppOverrides) -> AppOverrides) {
        if (dao.byId(id) == null) return
        val next = change(dao.override(id).toOverrides())
        dao.upsertOverride(
            AppSettingsOverrideEntity(id, next.checkEnabled, next.includePrereleases, next.autoDownload, next.autoInstall, next.notify, next.releaseDelayDays),
        )
    }

    /** Egy alkalmazásra ténylegesen érvényes szabályok (globális beállítás + felülírás). */
    suspend fun policyFor(app: TrackedApp): AppPolicy = PolicyResolver.resolve(globalSettings(), app.overrides)

    /**
     * Eltávolítás a követett alkalmazások listájából. Az Android-alkalmazást NEM távolítja el (C. 10.), és a rögzített
     * aláíró is megmarad. Kedvencnél csak a követés szűnik meg.
     */
    suspend fun remove(id: Long) {
        val row = dao.byId(id)?.app ?: return
        if (row.isFavorite) dao.update(row.copy(isFollowed = false)) else dao.delete(id)
    }

    /**
     * A felhasználó elfogadja a repó megváltozott azonosságát (más azonosító vagy tulajdonos). Ez a felhasználó döntése
     * (C. 19.); az aláíró-folytonosság ettől függetlenül továbbra is kötelező.
     */
    suspend fun acceptRepositoryIdentity(id: Long): Boolean {
        val row = dao.byId(id)?.app ?: return false
        val ref = row.toRef() ?: return false
        val provider = providers.get(ref.provider) ?: return false
        val repo = (provider.repository(ref) as? Fetch.Fresh)?.value ?: return false
        return updateSafely(
            row.copy(
                repoOwner = repo.ref.owner, repoName = repo.ref.name, repoKey = repo.ref.key, repoId = repo.id.value,
                repoOwnerId = repo.ownerId, repoState = RepoState.OK.name, repoStateDetail = null,
                checkStatus = null, lastCheckAtMillis = null,
            ),
        )
    }

    // ---- Aláíró és elemzések (C. 19.) ----

    suspend fun pinnedSigners(packageName: String): List<String> = dao.pin(packageName)?.certsSha256?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    /**
     * A letöltött állomány(ok) vizsgálati eredményének rögzítése assetenként. Csak akkor, ha a fájl sértetlenül
     * megérkezett: a letöltési hiba átmeneti lehet, azt nem jegyezzük meg végleges elutasításként.
     */
    suspend fun recordAnalysis(repo: RepoRef, release: Release, assets: List<ReleaseAsset>, files: List<DownloadedApk>, report: VerificationReport) {
        val provider = repo.provider
        if (report.integrity.any { it.status == CheckStatus.FAILED }) return
        val rejected = !report.isInstallable
        val reason = report.findings.filter { it.severity == Severity.CRITICAL }.joinToString(",") { it.kind.name }.take(200).ifEmpty { null }
        val now = clock.millis()
        assets.zip(files).forEachIndexed { index, (asset, file) ->
            val facts = report.fileFacts.getOrNull(index) ?: report.facts?.takeIf { assets.size == 1 }
            dao.upsertAnalysis(
                ApkAnalysisEntity(
                    provider = provider.id, assetId = asset.id, repoKey = repo.key, releaseId = release.id, sha256 = file.sha256,
                    packageName = facts?.packageName, versionCode = facts?.versionCode, versionName = facts?.versionName?.take(100),
                    signerSha256 = facts?.signers?.current?.joinToString(","),
                    verdict = if (rejected) VERDICT_REJECTED else VERDICT_OK, reason = if (rejected) reason else null, analyzedAtMillis = now,
                ),
            )
        }
    }

    /**
     * A csomagnév megtanulása egy TECHNIKAILAG ÉRVÉNYES (aláírás-ellenőrzött) letöltött APK-ból, ha a követett sornál még
     * nem ismert (a katalógus `packageName` mezője opcionális). Ettől kezdve a telepített verzió kiolvasható, és a
     * frissítés felismerhető. A hamis csomagnév ellen a telepített példány aláírójával való összevetés véd (C. 19.).
     */
    suspend fun learnPackageName(repo: RepoRef, catalogId: String?, packageName: String): Long? {
        val row = catalogId?.let { dao.byCatalogId(it) } ?: dao.byRepoKey(repo.key) ?: return null
        if (row.packageName == null) dao.update(row.copy(packageName = packageName))
        val pkg = row.packageName ?: packageName
        pinExistingInstall(pkg)
        if (withContext(Dispatchers.IO) { installed.get(pkg) } != null && !row.everInstalled) {
            dao.byId(row.id)?.app?.let { dao.update(it.copy(everInstalled = true)) }
        }
        return row.id
    }

    /**
     * Sikeres, ELLENŐRZÖTT telepítés után (8. lépés): a sor létrehozása vagy frissítése, és az aláíró rögzítése.
     * Ha még nem volt rögzített aláíró, ez az első telepítés (TOFU); ha volt, az új (rotált) tanúsítványok hozzáadódnak.
     */
    suspend fun recordInstall(
        trackedId: Long?,
        catalogApp: CatalogApp?,
        repo: RepoRef,
        repoMetadata: RepoMetadata?,
        release: Release,
        outcome: InstallOutcome.Verified,
    ): Long {
        val now = clock.millis()
        val existing = trackedId?.let { dao.byId(it)?.app } ?: catalogApp?.let { rowForCatalog(it) } ?: dao.byRepoKey(repo.key)
        val base = existing ?: newEntity(
            if (catalogApp != null) AppOrigin.CATALOG else AppOrigin.USER, repo, catalogApp?.id,
            repoMetadata?.id?.value ?: catalogApp?.repositoryId?.value, repoMetadata?.ownerId, outcome.packageName,
            catalogApp?.name ?: repo.name, now,
        )
        val updated = base.copy(
            packageName = outcome.packageName,
            isFollowed = true,
            installedViaUs = true,
            everInstalled = true,
            installedReleaseId = release.id,
            installedReleasePublishedAtMillis = (release.publishedAt ?: release.createdAt)?.toEpochMilli(),
            installedReleaseVersionCode = outcome.versionCode,
            latestReleaseId = base.latestReleaseId ?: release.id,
            latestTag = base.latestTag ?: release.tagName,
            latestName = base.latestName ?: release.name,
            latestPublishedAtMillis = base.latestPublishedAtMillis ?: release.publishedAt?.toEpochMilli(),
            latestIsPrerelease = if (base.latestReleaseId == null) release.isPrerelease else base.latestIsPrerelease,
            checkStatus = if (base.latestReleaseId == null || base.latestReleaseId == release.id) UpdateStatus.UP_TO_DATE.name else base.checkStatus,
            checkConfirmed = base.latestReleaseId == null || base.latestReleaseId == release.id || base.checkConfirmed,
        )
        val id = if (existing == null) insertOrExisting(updated) else {
            dao.update(updated)
            existing.id
        }
        val pin = dao.pin(outcome.packageName)
        if (pin == null) {
            dao.upsertPin(SignerPinEntity(outcome.packageName, outcome.signerHistory.joinToString(","), PinSource.FIRST_INSTALL_VIA_US.name, now, now))
        } else {
            val merged = (pin.certsSha256.split(',').filter { it.isNotEmpty() } + outcome.signerHistory).distinct()
            dao.upsertPin(pin.copy(certsSha256 = merged.joinToString(","), lastVerifiedAtMillis = now))
        }
        return id
    }

    /**
     * A telepítettségi állapot frissítése a PackageManager alapján: a „már nem telepített” állapothoz megjegyezzük,
     * hogy valaha telepítve volt, és a máshonnan telepített példány aláíróját rögzítjük (C. 19.: első találkozás).
     */
    suspend fun refreshInstalledStates() {
        for (row in dao.followedOldestCheckFirst().map { it.app }) {
            val pkg = row.packageName ?: continue
            val info = withContext(Dispatchers.IO) { installed.get(pkg) } ?: continue
            if (!row.everInstalled) dao.update(row.copy(everInstalled = true))
            pinExistingInstall(pkg, info.signingHistory)
        }
    }

    // ---- Frissítésellenőrzés (C. 11., 12.1.) ----

    suspend fun checkOne(id: Long): TrackedApp? = checkMutex.withLock {
        val row = dao.byId(id) ?: return null
        val provider = row.app.toRef()?.provider ?: return null
        if (!budget.canSpend(provider, REQUESTS_PER_CHECK)) {
            dao.update(row.app.copy(lastErrorKind = UpdateStatus.RATE_LIMITED.name, lastErrorAtMillis = clock.millis()))
        } else {
            runCheck(row)
        }
        dao.byId(id)?.toModel()
    }

    /**
     * Az összes követett (és figyelt) alkalmazás ellenőrzése egymás után. Ha az API-keret elfogyna, megáll: a többi
     * alkalmazás állapota változatlan marad, és az összegzés jelzi, hány maradt ki és mikor folytatható.
     */
    suspend fun checkAll(onChecked: suspend (TrackedApp, UpdateCheck) -> Unit = { _, _ -> }): CheckSummary = checkMutex.withLock {
        val global = globalSettings()
        val rows = dao.followedOldestCheckFirst().filter { PolicyResolver.resolve(global, it.override.toOverrides()).checkEnabled }
        var checked = 0
        var updates = 0
        var failed = 0
        _progress.value = CheckProgress(running = true, done = 0, total = rows.size)
        try {
            for ((index, row) in rows.withIndex()) {
                val provider = row.app.toRef()?.provider
                if (provider == null) {
                    failed++
                    continue
                }
                if (!budget.canSpend(provider, REQUESTS_PER_CHECK)) {
                    return CheckSummary(checked, updates, failed, rows.size - index, budget.resumesAt(provider))
                }
                val check = runCheck(row, global)
                dao.byId(row.app.id)?.toModel()?.let { onChecked(it, check) }
                when (check.status) {
                    UpdateStatus.RATE_LIMITED -> return CheckSummary(checked, updates, failed, rows.size - index, budget.resumesAt(provider))
                    UpdateStatus.ERROR -> failed++
                    UpdateStatus.UPDATE_AVAILABLE -> {
                        checked++
                        updates++
                    }
                    else -> checked++
                }
                _progress.value = CheckProgress(running = true, done = index + 1, total = rows.size)
            }
            CheckSummary(checked, updates, failed, 0, null)
        } finally {
            _progress.value = CheckProgress()
        }
    }

    private suspend fun runCheck(row: TrackedAppWithSettings, global: GlobalSettings? = null): UpdateCheck {
        val policy = PolicyResolver.resolve(global ?: globalSettings(), row.override.toOverrides())
        val app = row.app
        val ref = app.toRef() ?: return UpdateCheck(UpdateStatus.ERROR, null, null, null, emptyList(), UpdateFailure.Other("ref"))
        val catalog = app.catalogId?.let { catalogDao.byId(it) }?.toModelOrNull()
        val source = TrackedSource(ref, app.repoId?.let(::ProviderRepoId), app.repoOwnerId, policy.includePrereleases, policy.releaseDelay)
        val installedNow = app.packageName?.let { installedVersion(it, app) }
        val check = checker.check(UpdateCheckInput(source, installedNow, catalog?.assetFilter, device()))
        val now = clock.millis()
        val current = dao.byId(app.id)?.app ?: return check
        updateSafely(current.withCheck(check, now).copy(everInstalled = current.everInstalled || installedNow != null))
        return check
    }

    // ---- Segédfüggvények ----

    private suspend fun installedVersion(packageName: String, row: TrackedAppEntity?): InstalledVersion? {
        val info = withContext(Dispatchers.IO) { installed.get(packageName) } ?: return null
        val ours = row?.installedReleaseId?.let { releaseId ->
            InstalledRelease(releaseId, row.installedReleasePublishedAtMillis?.let(Instant::ofEpochMilli), row.installedReleaseVersionCode ?: -1)
        }
        return InstalledVersion(info.versionCode, info.versionName, ours)
    }

    /** A máshonnan (nem általunk) telepített példány aláírójának rögzítése, ha még nincs rögzített aláíró. */
    private suspend fun pinExistingInstall(packageName: String, history: List<String>? = null) {
        if (dao.pin(packageName) != null) return
        val signers = history ?: withContext(Dispatchers.IO) { installed.get(packageName) }?.signingHistory ?: return
        if (signers.isEmpty()) return
        val now = clock.millis()
        dao.upsertPin(SignerPinEntity(packageName, signers.joinToString(",") { it.lowercase() }, PinSource.EXISTING_INSTALL.name, now, now))
    }

    private suspend fun rowForCatalog(app: CatalogApp): TrackedAppEntity? =
        dao.byCatalogId(app.id)
            ?: app.repositoryId?.let { dao.byRepoId(app.repo.provider.id, it.value) }
            ?: dao.byRepoKey(app.repo.key)

    private suspend fun modify(id: Long, change: (TrackedAppEntity) -> TrackedAppEntity) {
        val row = dao.byId(id)?.app ?: return
        dao.update(change(row))
    }

    /** Beszúrás; ha közben (vagy más azonosítóval) már létezik ugyanez a repó, a meglévő sor azonosítója. */
    private suspend fun insertOrExisting(entity: TrackedAppEntity): Long = try {
        dao.insert(entity)
    } catch (_: SQLiteConstraintException) {
        val existing = entity.repoId?.let { dao.byRepoId(entity.provider, it) } ?: dao.byRepoKey(entity.repoKey)
        checkNotNull(existing) { "ütközés, de nincs meglévő sor" }.also { dao.update(it.copy(isFollowed = it.isFollowed || entity.isFollowed, isFavorite = it.isFavorite || entity.isFavorite)) }.id
    }

    /** Frissítés úgy, hogy egy átnevezés miatti kulcsütközés (két sor ugyanarra a repóra) ne dobjon kivételt. */
    private suspend fun updateSafely(entity: TrackedAppEntity): Boolean = try {
        dao.update(entity)
        true
    } catch (_: SQLiteConstraintException) {
        false
    }

    private fun newEntity(
        origin: AppOrigin, ref: RepoRef, catalogId: String?, repoId: Long?, ownerId: Long?, packageName: String?, name: String, now: Long,
    ) = TrackedAppEntity(
        origin = origin.name, catalogId = catalogId, provider = ref.provider.id, repoOwner = ref.owner, repoName = ref.name,
        repoKey = ref.key, repoId = repoId, repoOwnerId = ownerId, packageName = packageName, displayName = name.take(MAX_NAME),
        isFollowed = true, isFavorite = false, installedViaUs = false, everInstalled = false, addedAtMillis = now,
        lastCheckAtMillis = null, checkStatus = null, checkConfirmed = false, lastErrorKind = null, lastErrorAtMillis = null,
        repoState = RepoState.OK.name, repoStateDetail = null, latestReleaseId = null, latestTag = null, latestName = null,
        latestPublishedAtMillis = null, latestIsPrerelease = false, latestHtmlUrl = null, installedReleaseId = null,
        installedReleasePublishedAtMillis = null, installedReleaseVersionCode = null,
    )

    /** Egy ellenőrzés eredményének beírása a sorba. Az átmeneti hiba a korábbi állapotot nem írja felül. */
    private fun TrackedAppEntity.withCheck(check: UpdateCheck, now: Long): TrackedAppEntity {
        if (check.status == UpdateStatus.RATE_LIMITED || check.status == UpdateStatus.ERROR) {
            val kind = when (val failure = check.failure) {
                is UpdateFailure.RateLimited -> UpdateStatus.RATE_LIMITED.name
                is UpdateFailure.Network -> "NETWORK"
                else -> failure?.javaClass?.simpleName ?: UpdateStatus.ERROR.name
            }
            return copy(lastErrorKind = kind, lastErrorAtMillis = now)
        }
        var row = copy(lastCheckAtMillis = now, checkStatus = check.status.name, checkConfirmed = check.decision?.confirmed == true, lastErrorKind = null, lastErrorAtMillis = null)
        row = when (check.status) {
            UpdateStatus.REPO_ID_CHANGED -> row.copy(repoState = RepoState.ID_CHANGED.name, repoStateDetail = check.findings.firstOrNull()?.detail?.take(200))
            UpdateStatus.REPO_NOT_FOUND -> row.copy(repoState = RepoState.NOT_FOUND.name, repoStateDetail = null)
            UpdateStatus.REPO_UNAVAILABLE -> row.copy(repoState = RepoState.UNAVAILABLE.name, repoStateDetail = null)
            else -> row
        }
        val found = check.discovery ?: return row
        val repo = found.repo
        row = when (found.identity) {
            RepoIdentity.FIRST_SEEN -> row.copy(repoId = repo.id.value, repoOwnerId = repo.ownerId, repoState = RepoState.OK.name, repoStateDetail = null)
            RepoIdentity.UNCHANGED -> row.copy(repoOwnerId = row.repoOwnerId ?: repo.ownerId, repoState = RepoState.OK.name, repoStateDetail = null)
            // Átnevezés (azonos azonosító és tulajdonos): az új nevet átvesszük, tájékoztatásként jelezzük.
            RepoIdentity.RENAMED -> row.copy(
                repoOwner = repo.ref.owner, repoName = repo.ref.name, repoKey = repo.ref.key, repoOwnerId = row.repoOwnerId ?: repo.ownerId,
                repoState = RepoState.RENAMED.name, repoStateDetail = "${row.repoOwner}/${row.repoName} → ${repo.ref.path}".take(200),
            )
            // Tulajdonosváltás: a rögzített tulajdonos marad, amíg a felhasználó nem nyugtázza (a figyelmeztetés addig látszik).
            RepoIdentity.OWNER_CHANGED -> row.copy(repoState = RepoState.OWNER_CHANGED.name, repoStateDetail = "${row.repoOwner}/${row.repoName} → ${repo.ref.path}".take(200))
        }
        val candidate = found.candidate
        return row.copy(
            latestReleaseId = candidate?.id,
            latestTag = candidate?.tagName,
            latestName = candidate?.name,
            latestPublishedAtMillis = (candidate?.publishedAt ?: candidate?.createdAt)?.toEpochMilli(),
            latestIsPrerelease = candidate?.isPrerelease == true,
            latestHtmlUrl = candidate?.htmlUrl,
        )
    }

    private fun AppSettingsOverrideEntity?.toOverrides(): AppOverrides =
        if (this == null) AppOverrides() else AppOverrides(checkEnabled, includePrereleases, autoDownload, autoInstall, notify, releaseDelayDays)

    private fun TrackedAppEntity.toRef(): RepoRef? = ProviderId.fromId(provider)?.let { RepoRef.parsePath(it, "$repoOwner/$repoName") }

    private fun TrackedAppWithSettings.toModel(): TrackedApp? {
        val ref = app.toRef() ?: return null
        return TrackedApp(
            id = app.id,
            origin = runCatching { AppOrigin.valueOf(app.origin) }.getOrDefault(AppOrigin.USER),
            catalogId = app.catalogId,
            repo = ref,
            repoId = app.repoId?.takeIf { it > 0 }?.let(::ProviderRepoId),
            ownerId = app.repoOwnerId,
            packageName = app.packageName,
            displayName = app.displayName,
            isFollowed = app.isFollowed,
            isFavorite = app.isFavorite,
            installedViaUs = app.installedViaUs,
            everInstalled = app.everInstalled,
            addedAt = Instant.ofEpochMilli(app.addedAtMillis),
            lastCheckAt = app.lastCheckAtMillis?.let(Instant::ofEpochMilli),
            status = app.checkStatus?.let { runCatching { UpdateStatus.valueOf(it) }.getOrNull() },
            statusConfirmed = app.checkConfirmed,
            lastErrorKind = app.lastErrorKind,
            lastErrorAt = app.lastErrorAtMillis?.let(Instant::ofEpochMilli),
            repoState = runCatching { RepoState.valueOf(app.repoState) }.getOrDefault(RepoState.OK),
            repoStateDetail = app.repoStateDetail,
            latest = app.latestReleaseId?.let {
                LatestRelease(it, app.latestTag.orEmpty(), app.latestName, app.latestPublishedAtMillis?.let(Instant::ofEpochMilli), app.latestIsPrerelease, app.latestHtmlUrl)
            },
            installedRelease = app.installedReleaseId?.let {
                InstalledRelease(it, app.installedReleasePublishedAtMillis?.let(Instant::ofEpochMilli), app.installedReleaseVersionCode ?: -1)
            },
            checkEnabled = override?.checkEnabled ?: true,
            includePrereleases = override?.includePrereleases ?: false,
            overrides = override.toOverrides(),
        )
    }

    companion object {
        /** Egy ellenőrzés két API-kérés: repó (azonosság) + kiadások. */
        const val REQUESTS_PER_CHECK = 2
        const val VERDICT_OK = "OK"
        const val VERDICT_REJECTED = "REJECTED"
        private const val MAX_INPUT = 500
        private const val MAX_NAME = 100
    }
}
