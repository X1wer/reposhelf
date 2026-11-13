// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.tracked

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.x1wer.reposhelf.core.apk.CheckStatus
import io.github.x1wer.reposhelf.core.apk.DeviceProfile
import io.github.x1wer.reposhelf.core.apk.DownloadedApk
import io.github.x1wer.reposhelf.core.apk.InstalledAppInfo
import io.github.x1wer.reposhelf.core.apk.SignerIdentity
import io.github.x1wer.reposhelf.core.apk.VerificationReport
import io.github.x1wer.reposhelf.core.data.catalog.toModelOrNull
import io.github.x1wer.reposhelf.core.data.db.AppDatabase
import io.github.x1wer.reposhelf.core.data.db.CatalogAppEntity
import io.github.x1wer.reposhelf.core.data.db.CatalogStateEntity
import io.github.x1wer.reposhelf.core.model.Finding
import io.github.x1wer.reposhelf.core.model.FindingKind
import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.Readme
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.ReleaseAsset
import io.github.x1wer.reposhelf.core.model.RepoMetadata
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.model.Severity
import io.github.x1wer.reposhelf.core.network.HttpPolicy
import io.github.x1wer.reposhelf.core.network.Validators
import io.github.x1wer.reposhelf.core.network.testing.MutableClock
import io.github.x1wer.reposhelf.core.source.Fetch
import io.github.x1wer.reposhelf.core.source.ProviderRegistry
import io.github.x1wer.reposhelf.core.source.SourceError
import io.github.x1wer.reposhelf.core.source.SourceProvider
import io.github.x1wer.reposhelf.core.source.github.GitHubUrlParser
import io.github.x1wer.reposhelf.core.update.InstallOutcome
import io.github.x1wer.reposhelf.core.update.UpdateStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A követett alkalmazások kezelése (C. 9–11., 12.1., 19.) valódi (memóriabeli) Room-adatbázissal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackedAppsRepositoryTest {

    private class Provider : SourceProvider {
        override val id = ProviderId.GITHUB
        val repos = mutableMapOf<String, RepoMetadata>()
        val releases = mutableMapOf<String, List<Release>>()
        var failure: Fetch<Nothing>? = null
        val calls = mutableListOf<String>()
        override fun parseRepoUrl(input: String): RepoRef? = GitHubUrlParser.parse(input)
        override suspend fun repository(ref: RepoRef, validators: Validators?): Fetch<RepoMetadata> {
            calls += "repo:${ref.path}"
            failure?.let { return it }
            return repos[ref.key]?.let { Fetch.Fresh(it, Validators(null, null)) } ?: Fetch.NotFound
        }
        override suspend fun repositoryById(id: ProviderRepoId, validators: Validators?): Fetch<RepoMetadata> =
            repos.values.firstOrNull { it.id == id }?.let { Fetch.Fresh(it, Validators(null, null)) } ?: Fetch.NotFound
        override suspend fun readme(ref: RepoRef, validators: Validators?): Fetch<Readme> = Fetch.NotFound
        override suspend fun releases(ref: RepoRef, validators: Validators?, limit: Int): Fetch<List<Release>> {
            calls += "releases:${ref.path}"
            return Fetch.Fresh(releases[ref.key].orEmpty(), Validators(null, null))
        }
        override fun downloadPolicy(maxBytes: Long) = HttpPolicy.of("github.com", maxBodyBytes = maxBytes)
    }

    /** Az API-keretet a szolgáltatóhoz érkezett kérések száma alapján számolja (mint a valódi rate-limit fejlécek). */
    private class Budget(private val calls: List<String>) : RequestBudget {
        var limit = Int.MAX_VALUE
        override fun canSpend(provider: ProviderId, requests: Int) = calls.size + requests <= limit
        override fun resumesAt(provider: ProviderId): Instant? = Instant.parse("2026-11-10T13:00:00Z")
    }

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val provider = Provider()
    private val installedApps = mutableMapOf<String, InstalledAppInfo>()
    private val budget = Budget(provider.calls)
    private val clock = MutableClock(Instant.parse("2026-11-10T10:00:00Z"))
    private val device = DeviceProfile(35, listOf("arm64-v8a"), 10_000_000_000)
    private val repository = TrackedAppsRepository(db, ProviderRegistry(listOf(provider)), { installedApps[it] }, { device }, budget, clock)

    @After
    fun tearDown() = db.close()

    private fun ref(path: String) = checkNotNull(RepoRef.parsePath(ProviderId.GITHUB, path))

    private fun repo(path: String, id: Long, ownerId: Long = 1) = RepoMetadata(ref(path), ProviderRepoId(id), ownerId, "desc", "GPL-3.0", "main", "https://github.com/$path", false, false, false)

    private fun asset(id: Long, name: String = "app-release.apk") = ReleaseAsset(id, name, null, null, 1000, "https://github.com/x/y/releases/download/v/$name", null, null)

    private fun release(id: Long, tag: String, vararg assets: ReleaseAsset, prerelease: Boolean = false, published: String = "2026-10-01T00:00:00Z") =
        Release(id, tag, tag, prerelease, false, Instant.parse(published), Instant.parse(published), null, "https://github.com/x/y/releases/tag/$tag", null, assets.toList())

    private fun publish(path: String, id: Long, vararg releases: Release) {
        provider.repos[ref(path).key] = repo(path, id)
        provider.releases[ref(path).key] = releases.toList()
    }

    private suspend fun seedCatalog(vararg apps: CatalogAppEntity) {
        val state = CatalogStateEntity(0, 1, 1, 0, Long.MAX_VALUE, "x", "BUNDLED", apps.size, 0, null, null, 0, null, null, null, null)
        db.catalogDao().replaceCatalog(state, apps.toList())
    }

    private fun catalogEntry(id: String, path: String, repoId: Long, pkg: String) = CatalogAppEntity(
        id, id.replaceFirstChar { it.uppercase() }, "github", path.substringBefore('/'), path.substringAfter('/'), repoId, pkg, "", null, "", "security", null, id, "",
    )

    @Test
    fun addFromUrl_previewThenFollow(): Unit = runBlocking {
        assertEquals(AddRepoPreview.InvalidUrl, repository.preview("not a url"))
        assertEquals(AddRepoPreview.InvalidUrl, repository.preview("https://gitlab.com/owner/app"))
        assertIs<AddRepoPreview.NotFound>(repository.preview("https://github.com/owner/missing"))

        publish("owner/app", 42, release(10, "v1.0", asset(1)))
        val preview = assertIs<AddRepoPreview.Found>(repository.preview("https://github.com/owner/app/releases"))
        assertEquals(UpdateStatus.NOT_INSTALLED, preview.check.status)
        assertNull(preview.catalogApp)
        assertTrue(repository.observeAll().first().isEmpty(), "az előnézet nem ment semmit")

        val id = repository.follow(preview)
        val saved = assertNotNull(repository.get(id))
        assertEquals(AppOrigin.USER, saved.origin)
        assertEquals(ProviderRepoId(42), saved.repoId)
        assertEquals("v1.0", saved.latest?.tag)
        assertTrue(saved.isFollowed)
        assertIs<AddRepoPreview.AlreadyTracked>(repository.preview("github.com/owner/app"))
    }

    @Test
    fun addFromUrl_repoWithoutApk_andRateLimit_areReported(): Unit = runBlocking {
        publish("owner/lib", 7, release(3, "v2.0", asset(9, "lib-2.0.jar")))
        val noApk = assertIs<AddRepoPreview.Found>(repository.preview("owner/lib"))
        assertEquals(UpdateStatus.NO_INSTALLABLE_RELEASE, noApk.check.status)

        provider.failure = Fetch.RateLimited(Instant.parse("2026-11-10T11:00:00Z"))
        assertIs<AddRepoPreview.RateLimited>(repository.preview("owner/other"))
        provider.failure = Fetch.Failed(SourceError.Network("timeout"))
        assertIs<AddRepoPreview.Failed>(repository.preview("owner/other"))
    }

    @Test
    fun repoAlsoInCatalog_isLinkedToTheCatalogEntry(): Unit = runBlocking {
        seedCatalog(catalogEntry("aegis", "beemdevelopment/Aegis", 6, "com.beemdevelopment.aegis"))
        publish("beemdevelopment/Aegis", 6, release(1, "v3.4.3", asset(1)))
        val preview = assertIs<AddRepoPreview.Found>(repository.preview("https://github.com/beemdevelopment/aegis"))
        assertEquals("aegis", preview.catalogApp?.id)
        val app = assertNotNull(repository.get(repository.follow(preview)))
        assertEquals("com.beemdevelopment.aegis", app.packageName)
        assertEquals("aegis", app.catalogId)
    }

    @Test
    fun followingAnInstalledApp_pinsItsSigner_andRemovingKeepsThePin(): Unit = runBlocking {
        seedCatalog(catalogEntry("aegis", "beemdevelopment/Aegis", 6, "com.beemdevelopment.aegis"))
        val catalogApp = assertNotNull(assertNotNull(db.catalogDao().byId("aegis")).toModelOrNull())
        installedApps["com.beemdevelopment.aegis"] = InstalledAppInfo("com.beemdevelopment.aegis", 81, "3.4.2", listOf("C6DB"))
        val id = repository.followCatalogApp(catalogApp)
        assertEquals(listOf("c6db"), repository.pinnedSigners("com.beemdevelopment.aegis"))

        // Követés megszüntetése: a sor törlődik (nem kedvenc), az aláíró-pin megmarad.
        repository.remove(id)
        assertNull(repository.get(id))
        assertEquals(listOf("c6db"), repository.pinnedSigners("com.beemdevelopment.aegis"))

        // Kedvencnél a sor megmarad, csak a követés szűnik meg.
        val favId = repository.setFavoriteForCatalogApp(catalogApp, true)
        repository.setFollowed(favId, true)
        repository.remove(favId)
        val fav = assertNotNull(repository.get(favId))
        assertTrue(fav.isFavorite)
        assertFalse(fav.isFollowed)
    }

    @Test
    fun packageNameLearnedFromVerifiedApk_enablesUpdateDetectionForAnAppInstalledElsewhere(): Unit = runBlocking {
        // A katalógusbejegyzésben nincs csomagnév; az app máshonnan (pl. adb-vel) régebbi verzióban telepítve van.
        seedCatalog(catalogEntry("aegis", "beemdevelopment/Aegis", 6, "x").copy(packageName = null))
        publish("beemdevelopment/Aegis", 6, release(2, "v3.4.3", asset(5)))
        val catalogApp = assertNotNull(assertNotNull(db.catalogDao().byId("aegis")).toModelOrNull())
        val id = repository.followCatalogApp(catalogApp)
        installedApps["com.beemdevelopment.aegis"] = InstalledAppInfo("com.beemdevelopment.aegis", 81, "3.4.2", listOf("c6db"))
        assertEquals(UpdateStatus.NOT_INSTALLED, repository.checkOne(id)?.status, "csomagnév nélkül a telepített példány nem azonosítható")

        repository.learnPackageName(catalogApp.repo, catalogApp.id, "com.beemdevelopment.aegis")
        assertEquals("com.beemdevelopment.aegis", repository.get(id)?.packageName)
        assertEquals(listOf("c6db"), repository.pinnedSigners("com.beemdevelopment.aegis"), "a máshonnan telepített példány aláírója rögzül")
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, repository.checkOne(id)?.status)
    }

    @Test
    fun refollowingAfterRemoval_restoresThePackageNameFromAnAcceptedAnalysis(): Unit = runBlocking {
        seedCatalog(catalogEntry("aegis", "beemdevelopment/Aegis", 6, "x").copy(packageName = null))
        val catalogApp = assertNotNull(assertNotNull(db.catalogDao().byId("aegis")).toModelOrNull())
        val release = release(2, "v3.4.3", asset(5))
        val facts = io.github.x1wer.reposhelf.core.apk.ApkFacts(
            "com.beemdevelopment.aegis", 82, "3.4.3", 23, 36, null, io.github.x1wer.reposhelf.core.apk.SignerInfo(listOf("c6db"), listOf("c6db"), false),
        )
        val accepted = VerificationReport(
            listOf(io.github.x1wer.reposhelf.core.apk.CheckItem(io.github.x1wer.reposhelf.core.apk.Check.SIZE, CheckStatus.OK)),
            CheckStatus.OK, SignerIdentity.MATCHES_INSTALLED, emptyList(), facts, emptyList(), emptySet(), emptyList(), listOf(facts),
        )
        val id = repository.followCatalogApp(catalogApp)
        repository.recordAnalysis(catalogApp.repo, release, release.assets, listOf(DownloadedApk(File("a.apk"), "ab".repeat(32), 1000, 1000, null)), accepted)
        repository.learnPackageName(catalogApp.repo, catalogApp.id, facts.packageName)
        repository.remove(id)
        assertNull(repository.get(id))

        val again = assertNotNull(repository.get(repository.followCatalogApp(catalogApp)))
        assertEquals("com.beemdevelopment.aegis", again.packageName)
    }

    @Test
    fun globalSettingsApplyToChecks_andPerAppOverridesWin(): Unit = runBlocking {
        val global = io.github.x1wer.reposhelf.core.update.GlobalSettings(includePrereleases = true)
        val repo = TrackedAppsRepository(db, ProviderRegistry(listOf(provider)), { installedApps[it] }, { device }, budget, clock) { global }
        publish("owner/app", 42, release(10, "v1.0", asset(1)), release(11, "v1.1-beta", asset(2), prerelease = true, published = "2026-10-05T00:00:00Z"))
        val id = repo.follow(assertIs(repo.preview("owner/app")))
        assertEquals("v1.1-beta", repo.checkOne(id)?.latest?.tag, "a globális beállítás szerint az előzetes kiadás is jelölt")
        repo.setOverrides(id, io.github.x1wer.reposhelf.core.update.AppOverrides(includePrereleases = false))
        assertEquals("v1.0", repo.checkOne(id)?.latest?.tag, "az alkalmazásonkénti felülírás nyer")
        repo.setCheckEnabled(id, false)
        assertEquals(false, repo.get(id)?.overrides?.checkEnabled)
        assertEquals(false, repo.get(id)?.overrides?.includePrereleases, "a többi felülírás megmarad")
        provider.calls.clear()
        assertEquals(0, repo.checkAll().checked)
        assertTrue(provider.calls.isEmpty(), "kikapcsolt figyelésnél nincs kérés")
    }

    @Test
    fun checks_recordAndResolveSecurityWarnings(): Unit = runBlocking {
        val store = io.github.x1wer.reposhelf.core.data.safety.SecurityWarnings(db, clock)
        val repo = TrackedAppsRepository(db, ProviderRegistry(listOf(provider)), { installedApps[it] }, { device }, budget, clock, warnings = store)
        publish("owner/app", 42, release(10, "v1.0", asset(1)))
        val id = repo.follow(assertIs(repo.preview("owner/app")))
        // A repó eltűnik: figyelmeztetés.
        provider.repos.clear()
        assertEquals(UpdateStatus.REPO_NOT_FOUND, repo.checkOne(id)?.status)
        assertEquals(io.github.x1wer.reposhelf.core.update.WarningCategory.REPO_GONE, store.observeOpen().first().single().category)
        // Újra elérhető: a figyelmeztetés feloldva.
        publish("owner/app", 42, release(10, "v1.0", asset(1)))
        repo.checkOne(id)
        assertTrue(store.observeOpen().first().isEmpty())
        // Azonos néven más repó: kritikus.
        provider.repos[ref("owner/app").key] = repo("owner/app", 999)
        repo.checkOne(id)
        val critical = store.observeOpen().first().single()
        assertEquals(io.github.x1wer.reposhelf.core.model.Severity.CRITICAL, critical.severity)
        assertTrue(store.hasBlocking(id))
    }

    @Test
    fun recordInstall_pinsOnFirstInstall_andMergesRotation(): Unit = runBlocking {
        publish("owner/app", 42, release(10, "v1.0", asset(1)))
        val id = repository.follow(assertIs(repository.preview("owner/app")))
        val release = release(10, "v1.0", asset(1))
        repository.recordInstall(id, null, ref("owner/app"), null, release, InstallOutcome.Verified("com.example", 10, "1.0", listOf("aa")))
        val app = assertNotNull(repository.get(id))
        assertTrue(app.installedViaUs)
        assertEquals("com.example", app.packageName)
        assertEquals(UpdateStatus.UP_TO_DATE, app.status)
        assertEquals(10L, app.installedRelease?.releaseId)
        assertEquals(listOf("aa"), repository.pinnedSigners("com.example"))

        // Érvényes kulcsrotáció utáni frissítés: az új tanúsítvány hozzáadódik, a régi megmarad.
        repository.recordInstall(id, null, ref("owner/app"), null, release(11, "v1.1", asset(2)), InstallOutcome.Verified("com.example", 11, "1.1", listOf("aa", "bb")))
        assertEquals(listOf("aa", "bb"), repository.pinnedSigners("com.example"))
    }

    @Test
    fun checkAll_runsOldestFirst_andStopsBeforeExhaustingTheBudget(): Unit = runBlocking {
        publish("owner/a", 1, release(10, "v1.1", asset(1)))
        publish("owner/b", 2, release(20, "v2.0", asset(2)))
        publish("owner/c", 3, release(30, "v3.0", asset(3)))
        val a = repository.follow(assertIs(repository.preview("owner/a")))
        clock.advance(Duration.ofMinutes(1))
        val b = repository.follow(assertIs(repository.preview("owner/b")))
        val c = repository.follow(assertIs(repository.preview("owner/c")))
        repository.setCheckEnabled(c, false)

        provider.calls.clear()
        budget.limit = 2 // pontosan egy ellenőrzésre elég
        val summary = repository.checkAll()
        assertEquals(1, summary.checked)
        assertEquals(1, summary.notChecked, "a figyelés nélküli (c) nem számít bele")
        assertNotNull(summary.resumesAt)
        assertEquals(listOf("repo:owner/a", "releases:owner/a"), provider.calls, "a legrégebben ellenőrzött az első")
        assertNotNull(repository.get(b))
    }

    @Test
    fun checkResults_updateAvailable_rename_idChange_andTransientErrors(): Unit = runBlocking {
        publish("owner/app", 42, release(10, "v1.0", asset(1)))
        val id = repository.follow(assertIs(repository.preview("owner/app")))
        installedApps["com.example"] = InstalledAppInfo("com.example", 10, "1.0", listOf("aa"))
        repository.recordInstall(id, null, ref("owner/app"), null, release(10, "v1.0", asset(1)), InstallOutcome.Verified("com.example", 10, "1.0", listOf("aa")))

        provider.releases[ref("owner/app").key] = listOf(release(11, "v1.1", asset(2), published = "2026-10-05T00:00:00Z"))
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, repository.checkOne(id)?.status)

        // Átmeneti hiba: az előző állapot megmarad, a hiba külön látszik.
        provider.failure = Fetch.Failed(SourceError.Network("timeout"))
        val afterError = assertNotNull(repository.checkOne(id))
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, afterError.status)
        assertEquals("NETWORK", afterError.lastErrorKind)
        provider.failure = null

        // Átnevezés: azonos azonosító, új név → átvesszük.
        provider.repos.clear()
        provider.repos[ref("owner/app-renamed").key] = repo("owner/app-renamed", 42)
        provider.releases[ref("owner/app-renamed").key] = listOf(release(11, "v1.1", asset(2)))
        provider.repos[ref("owner/app").key] = repo("owner/app-renamed", 42) // a régi név átirányít
        val renamed = assertNotNull(repository.checkOne(id))
        assertEquals("owner/app-renamed", renamed.repo.path)
        assertEquals(RepoState.RENAMED, renamed.repoState)

        // Azonos néven más repó: tiltás, amíg a felhasználó nem dönt.
        provider.repos[ref("owner/app-renamed").key] = repo("owner/app-renamed", 999)
        val hijacked = assertNotNull(repository.checkOne(id))
        assertEquals(UpdateStatus.REPO_ID_CHANGED, hijacked.status)
        assertEquals(RepoState.ID_CHANGED, hijacked.repoState)
        assertTrue(repository.acceptRepositoryIdentity(id))
        assertEquals(ProviderRepoId(999), repository.get(id)?.repoId)
    }

    @Test
    fun rejectedAnalysis_blocksTheSameUpdate_butTransientDownloadErrorsAreNotRemembered(): Unit = runBlocking {
        publish("owner/app", 42, release(10, "v1.0", asset(1)))
        val id = repository.follow(assertIs(repository.preview("owner/app")))
        installedApps["com.example"] = InstalledAppInfo("com.example", 10, "1.0", listOf("aa"))
        repository.recordInstall(id, null, ref("owner/app"), null, release(10, "v1.0", asset(1)), InstallOutcome.Verified("com.example", 10, "1.0", listOf("aa")))
        val update = release(11, "v1.1", asset(2), published = "2026-10-05T00:00:00Z")
        provider.releases[ref("owner/app").key] = listOf(update)

        val file = DownloadedApk(File("x.apk"), "ff".repeat(32), 1000, 1000, null)
        fun report(findings: List<Finding>, integrityFailed: Boolean) = VerificationReport(
            integrity = listOf(io.github.x1wer.reposhelf.core.apk.CheckItem(io.github.x1wer.reposhelf.core.apk.Check.SIZE, if (integrityFailed) CheckStatus.FAILED else CheckStatus.OK)),
            signatureValid = CheckStatus.OK, identity = SignerIdentity.MISMATCH, compatibility = emptyList(), facts = null,
            splitFacts = emptyList(), nativeAbis = emptySet(), findings = findings,
        )
        // Megszakadt/sérült letöltés: nem jegyezzük meg.
        repository.recordAnalysis(ref("owner/app"), update, update.assets, listOf(file), report(listOf(Finding(FindingKind.SIZE_MISMATCH, Severity.CRITICAL)), integrityFailed = true))
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, repository.checkOne(id)?.status)
        // Más aláíró: megjegyezzük, és ugyanazt a frissítést nem ajánljuk fel újra.
        repository.recordAnalysis(ref("owner/app"), update, update.assets, listOf(file), report(listOf(Finding(FindingKind.SIGNER_CHANGED, Severity.CRITICAL)), integrityFailed = false))
        assertEquals(UpdateStatus.UPDATE_BLOCKED, repository.checkOne(id)?.status)
    }

    // ---- SIG-01: a repó azonossága a kézi telepítési úton (repojacking első telepítésnél) ----

    @Test
    fun identityGate_blocksAHijackedCatalogRepo_beforeAnythingIsDownloaded(): Unit = runBlocking {
        val store = io.github.x1wer.reposhelf.core.data.safety.SecurityWarnings(db, clock)
        val repo = TrackedAppsRepository(db, ProviderRegistry(listOf(provider)), { installedApps[it] }, { device }, budget, clock, warnings = store)
        seedCatalog(catalogEntry("aegis", "beemdevelopment/Aegis", 6, "com.beemdevelopment.aegis"))
        val catalogApp = assertNotNull(db.catalogDao().byId("aegis")?.toModelOrNull())
        // Az eredeti repó (6) helyén azonos néven egy másik (666) van.
        publish("beemdevelopment/Aegis", 666, release(1, "v9.9.9", asset(1)))

        // Nem követett katalógusbeli app: a katalógus aláírt azonosítója dönt.
        val verdict = repo.confirmRepositoryIdentity(catalogApp.repo, catalogApp, trackedId = null)
        val mismatch = assertIs<io.github.x1wer.reposhelf.core.update.RepoIdentityVerdict.Mismatch>(verdict)
        assertEquals(ProviderRepoId(6), mismatch.expected)
        assertEquals(ProviderRepoId(666), mismatch.actual)
        assertEquals(listOf("repo:beemdevelopment/Aegis"), provider.calls, "csak az azonosság-kérés ment ki, kiadás-lekérés nem")
        assertTrue(repo.observeAll().first().isEmpty(), "az eltérített azonosítóval nem jött létre sor")

        // Követett sor: megjelölve, kritikus figyelmeztetéssel; az automatikus telepítés tiltva.
        val id = repo.followCatalogApp(catalogApp)
        assertIs<io.github.x1wer.reposhelf.core.update.RepoIdentityVerdict.Mismatch>(repo.confirmRepositoryIdentity(catalogApp.repo, catalogApp, id))
        val row = assertNotNull(repo.get(id))
        assertEquals(RepoState.ID_CHANGED, row.repoState)
        assertEquals("6 → 666", row.repoStateDetail)
        assertEquals(ProviderRepoId(6), row.repoId, "a rögzített azonosító nem íródik át")
        assertTrue(store.hasBlocking(id))

        // A katalógus aláírtan rögzített azonosítóját a felhasználó elfogadása sem írhatja felül.
        assertFalse(repo.acceptRepositoryIdentity(id))
        assertEquals(ProviderRepoId(6), repo.get(id)?.repoId)

        // Ha a néven újra az eredeti repó van, a kapu átenged.
        publish("beemdevelopment/Aegis", 6, release(1, "v3.4.3", asset(1)))
        assertIs<io.github.x1wer.reposhelf.core.update.RepoIdentityVerdict.Confirmed>(repo.confirmRepositoryIdentity(catalogApp.repo, catalogApp, id))
    }

    @Test
    fun identityGate_unreachableRepo_isUnconfirmed_andUserRepoIsPinnedByTheTrackedRow(): Unit = runBlocking {
        publish("owner/app", 42, release(10, "v1.0", asset(1)))
        val id = repository.follow(assertIs(repository.preview("owner/app")))
        provider.failure = Fetch.Failed(SourceError.Network("offline"))
        assertIs<io.github.x1wer.reposhelf.core.update.RepoIdentityVerdict.Unconfirmed>(repository.confirmRepositoryIdentity(ref("owner/app"), null, id))
        provider.failure = null
        // Saját forrásnál a követett sor korábban rögzített azonosítója az elvárt.
        provider.repos[ref("owner/app").key] = repo("owner/app", 4242)
        assertIs<io.github.x1wer.reposhelf.core.update.RepoIdentityVerdict.Mismatch>(repository.confirmRepositoryIdentity(ref("owner/app"), null, id))
        assertEquals(RepoState.ID_CHANGED, repository.get(id)?.repoState)
    }

    @Test
    fun acceptIdentity_onlyAcceptsTheRepoTheUserWasShown(): Unit = runBlocking {
        publish("owner/app", 42, release(10, "v1.0", asset(1)))
        val id = repository.follow(assertIs(repository.preview("owner/app")))
        provider.repos[ref("owner/app").key] = repo("owner/app", 999)
        assertEquals(UpdateStatus.REPO_ID_CHANGED, repository.checkOne(id)?.status)
        // A felhasználó a 999-es repót látta, de mire dönt, a néven már egy harmadik (1000) van: a döntés nem érvényes.
        provider.repos[ref("owner/app").key] = repo("owner/app", 1000)
        assertFalse(repository.acceptRepositoryIdentity(id, shownRepoId = ProviderRepoId(999)))
        assertEquals(ProviderRepoId(42), repository.get(id)?.repoId)
        assertTrue(repository.acceptRepositoryIdentity(id, shownRepoId = ProviderRepoId(1000)))
        assertEquals(ProviderRepoId(1000), repository.get(id)?.repoId)
    }

    @Test
    fun recordInstall_neverStoresAFetchedIdOverTheCatalogId(): Unit = runBlocking {
        seedCatalog(catalogEntry("aegis", "beemdevelopment/Aegis", 6, "com.beemdevelopment.aegis"))
        val catalogApp = assertNotNull(db.catalogDao().byId("aegis")?.toModelOrNull())
        val id = repository.recordInstall(
            null, catalogApp, catalogApp.repo, repo("beemdevelopment/Aegis", 666), release(1, "v3.4.3", asset(1)),
            InstallOutcome.Verified("com.beemdevelopment.aegis", 82, "3.4.3", listOf("aa")),
        )
        assertEquals(ProviderRepoId(6), repository.get(id)?.repoId)
    }

    // ---- CAT-05: a katalógusazonosító önmagában nem köti a követett appot a katalógus adataihoz ----

    @Test
    fun catalogEntryRepointedToAnotherRepo_isNotAppliedToTheTrackedApp(): Unit = runBlocking {
        seedCatalog(catalogEntry("aegis", "beemdevelopment/Aegis", 6, "com.beemdevelopment.aegis"))
        val original = assertNotNull(db.catalogDao().byId("aegis")?.toModelOrNull())
        val id = repository.followCatalogApp(original)
        val tracked = assertNotNull(repository.get(id))
        assertEquals("aegis", repository.boundCatalogApp(tracked.catalogId, tracked.repo)?.id)
        // Katalógusfrissítés: ugyanaz az azonosító most más repóra (és csomagnévre) mutat.
        seedCatalog(catalogEntry("aegis", "evil/Aegis", 777, "com.evil.aegis"))
        assertNull(repository.boundCatalogApp(tracked.catalogId, tracked.repo), "a más repóra mutató bejegyzés nem alkalmazható")
        // A bejegyzés eltűnik a katalógusból: szintén nincs katalógus-adat.
        seedCatalog()
        assertNull(repository.boundCatalogApp(tracked.catalogId, tracked.repo))
    }

    // ---- IMP-02: a telepített alkalmazások ellenőrzése megelőzi a csak követettekét ----

    @Test
    fun checkAll_checksInstalledAppsFirst_evenWhenManyNeverCheckedReposWereImported(): Unit = runBlocking {
        publish("owner/installed", 1, release(10, "v1.0", asset(1)))
        val installedId = repository.follow(assertIs(repository.preview("owner/installed")))
        installedApps["com.example.installed"] = InstalledAppInfo("com.example.installed", 10, "1.0", listOf("aa"))
        repository.recordInstall(installedId, null, ref("owner/installed"), null, release(10, "v1.0", asset(1)), InstallOutcome.Verified("com.example.installed", 10, "1.0", listOf("aa")))
        clock.advance(Duration.ofDays(1))
        // Importból érkezett, még soha nem ellenőrzött repók (a régi sorrendben ezek kerültek volna előre).
        val imported = (1..20).map { io.github.x1wer.reposhelf.core.data.transfer.AppListEntry(ref("bulk/repo$it"), null, null, "r$it", followed = true, favorite = false, overrides = null) }
        assertEquals(20, repository.importList(imported).added)

        provider.calls.clear()
        budget.limit = 2 // pontosan egy ellenőrzésre elég
        val summary = repository.checkAll()
        assertEquals(listOf("repo:owner/installed", "releases:owner/installed"), provider.calls, "a telepített alkalmazás az első")
        assertEquals(1, summary.checked)
        assertEquals(20, summary.notChecked)
    }
}
