// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.selfupdate

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.x1wer.reposhelf.core.apk.DeviceProfile
import io.github.x1wer.reposhelf.core.data.db.AppDatabase
import io.github.x1wer.reposhelf.core.data.safety.HistoryOutcome
import io.github.x1wer.reposhelf.core.data.safety.InstallMethod
import io.github.x1wer.reposhelf.core.data.safety.SecurityWarnings
import io.github.x1wer.reposhelf.core.data.safety.UpdateHistory
import io.github.x1wer.reposhelf.core.data.safety.Version
import io.github.x1wer.reposhelf.core.data.settings.SettingsRepository
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
import io.github.x1wer.reposhelf.core.source.SourceProvider
import io.github.x1wer.reposhelf.core.source.github.GitHubUrlParser
import io.github.x1wer.reposhelf.core.update.KnownAnalyses
import io.github.x1wer.reposhelf.core.update.UpdateStatus
import io.github.x1wer.reposhelf.core.update.WarningCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A saját frissítés logikája (B. 6.4.): ellenőrzés, repó-azonosság, függő állapot és indításkori egyeztetés. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SelfUpdatesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class Provider : SourceProvider {
        override val id = ProviderId.GITHUB
        val repos = mutableMapOf<String, RepoMetadata>()
        val releases = mutableMapOf<String, List<Release>>()
        override fun parseRepoUrl(input: String): RepoRef? = GitHubUrlParser.parse(input)
        override suspend fun repository(ref: RepoRef, validators: Validators?): Fetch<RepoMetadata> =
            repos[ref.key]?.let { Fetch.Fresh(it, Validators(null, null)) } ?: Fetch.NotFound
        override suspend fun repositoryById(id: ProviderRepoId, validators: Validators?): Fetch<RepoMetadata> =
            repos.values.firstOrNull { it.id == id }?.let { Fetch.Fresh(it, Validators(null, null)) } ?: Fetch.NotFound
        override suspend fun readme(ref: RepoRef, validators: Validators?): Fetch<Readme> = Fetch.NotFound
        override suspend fun releases(ref: RepoRef, validators: Validators?, limit: Int): Fetch<List<Release>> =
            Fetch.Fresh(releases[ref.key].orEmpty(), Validators(null, null))
        override fun downloadPolicy(maxBytes: Long) = HttpPolicy.of("github.com", maxBodyBytes = maxBytes)
    }

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings by lazy { SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(temp.root, "settings.preferences_pb") }) }
    private val clock = MutableClock(Instant.parse("2026-11-10T10:00:00Z"))
    private val provider = Provider()
    private val warnings = SecurityWarnings(db, clock)
    private val history = UpdateHistory(db, clock)
    private val selfRepo = checkNotNull(RepoRef.parsePath(ProviderId.GITHUB, "X1wer/reposhelf"))

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun selfUpdates(versionCode: Long, versionName: String) = SelfUpdates(
        SelfUpdateConfig(selfRepo, "io.github.x1wer.reposhelf", versionCode, versionName, emptyList()),
        ProviderRegistry(listOf(provider)), KnownAnalyses.NONE, settings, warnings, history,
        { DeviceProfile(35, listOf("x86_64"), 10_000_000_000) }, clock,
    )

    private fun publish(repoId: Long, ownerId: Long, vararg tags: String) {
        provider.repos[selfRepo.key] = RepoMetadata(selfRepo, ProviderRepoId(repoId), ownerId, "d", "GPL-3.0", "main", "https://github.com/X1wer/reposhelf", false, false, false)
        provider.releases[selfRepo.key] = tags.mapIndexed { i, tag ->
            val asset = ReleaseAsset(100L + i, "reposhelf-$tag.apk", null, null, 1000, "https://github.com/X1wer/reposhelf/releases/download/$tag/a.apk", null, null)
            // Az első a legújabb.
            val published = Instant.parse("2026-11-0${9 - i}T00:00:00Z")
            Release(10L + i, tag, tag, false, false, published, published, null, "https://github.com/X1wer/reposhelf/releases/tag/$tag", null, listOf(asset))
        }
    }

    @Test
    fun config_isABuildParameter_andAnEmptyOrInvalidRepoDisablesSelfUpdate() {
        val good = "a".repeat(64)
        val config = SelfUpdateConfig.from("X1wer/reposhelf", "$good, nothex ,${"B".repeat(64)}", "p", 1, "1")
        assertTrue(config.enabled)
        assertEquals("X1wer/reposhelf", config.repo?.path)
        assertEquals(listOf(good, "b".repeat(64)), config.pinnedSigners, "csak érvényes SHA-256 ujjlenyomat marad")
        assertFalse(SelfUpdateConfig.from("", "", "p", 1, "1").enabled)
        assertFalse(SelfUpdateConfig.from("https://evil.example/x", "", "p", 1, "1").enabled)
    }

    @Test
    fun check_findsANewerRelease_andPinsTheRepositoryIdentityOnFirstSight(): Unit = runBlocking {
        publish(repoId = 42, ownerId = 7, "v0.2.0", "v0.1.0")
        val check = selfUpdates(1, "0.1.0").check()
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, check.status)
        assertEquals("v0.2.0", check.discovery?.candidate?.tagName)
        assertEquals(42L to 7L, settings.selfRepoIdentity())

        // Ugyanazon a néven később egy MÁSIK repó: kritikus eset, frissítés nincs, az automatikus telepítés tiltva.
        publish(repoId = 99, ownerId = 8, "v9.9.9")
        val hijacked = selfUpdates(1, "0.1.0")
        assertEquals(UpdateStatus.REPO_ID_CHANGED, hijacked.check().status)
        val warning = warnings.observeFor(hijacked.app).first().single()
        assertEquals(WarningCategory.REPO_ID_CHANGED, warning.category)
        assertEquals(Severity.CRITICAL, warning.severity)
        assertTrue(hijacked.hasBlockingWarnings())
        assertEquals(42L to 7L, settings.selfRepoIdentity(), "a rögzített azonosító nem íródik felül")
    }

    @Test
    fun aRepositoryThatWasNeverSeen_isNotReportedAsDisappeared_butOneThatWasSeenIs(): Unit = runBlocking {
        val self = selfUpdates(1, "0.1.0")
        assertEquals(UpdateStatus.REPO_NOT_FOUND, self.check().status)
        assertTrue(warnings.observeFor(self.app).first().isEmpty(), "még nem közzétett saját repó: nincs riasztás")

        publish(repoId = 42, ownerId = 7, "v0.1.0")
        assertEquals(UpdateStatus.UP_TO_DATE, self.check().status)
        provider.repos.clear()
        assertEquals(UpdateStatus.REPO_NOT_FOUND, self.check().status)
        assertEquals(WarningCategory.REPO_GONE, warnings.observeFor(self.app).first().single().category)
    }

    @Test
    fun ownerChange_isAWarningThatHoldsAutomaticInstallUntilAcknowledged(): Unit = runBlocking {
        publish(repoId = 42, ownerId = 7, "v0.2.0")
        val self = selfUpdates(1, "0.1.0")
        self.check()
        publish(repoId = 42, ownerId = 8, "v0.2.0")
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, self.check().status)
        val warning = warnings.observeOpen().first().single()
        assertEquals(WarningCategory.REPO_OWNER_CHANGED, warning.category)
        assertTrue(self.hasBlockingWarnings())
        warnings.acknowledge(warning.id)
        assertFalse(self.hasBlockingWarnings())
    }

    @Test
    fun interruptedByTheSystemReplacingTheApp_theNewVersionRecordsTheSuccessOnStart(): Unit = runBlocking {
        val old = selfUpdates(1, "0.1.0")
        old.markStarting(Version("0.2.0", 2), automatic = false)
        old.markCommitted(sessionId = 5)
        assertEquals(5, old.pending()?.sessionId)
        // A rendszer lecseréli az appot: a folyamat leáll, az új verzió indul.
        clock.advance(Duration.ofMinutes(1))
        val result = selfUpdates(2, "0.2.0").reconcile { false }
        assertEquals(Reconciliation.Succeeded(Version("0.1.0", 1), Version("0.2.0", 2)), result)
        val entry = history.observe().first().single()
        assertEquals(HistoryOutcome.SUCCESS, entry.outcome)
        assertEquals(InstallMethod.SELF_UPDATE, entry.method)
        assertEquals("RepoShelf", entry.appName)
        assertEquals(Version("0.1.0", 1), entry.from)
        assertEquals(Version("0.2.0", 2), entry.to)
        assertNull(settings.pendingSelfUpdate())
        assertEquals(Reconciliation.NothingPending, selfUpdates(2, "0.2.0").reconcile { false }, "egyszer rögzítjük")
    }

    @Test
    fun aSessionWaitingForApproval_staysPending_butAStaleOrVanishedOneIsRecordedAsInterrupted(): Unit = runBlocking {
        val self = selfUpdates(1, "0.1.0")
        self.markStarting(Version("0.2.0", 2), automatic = true)
        self.markCommitted(5)
        assertEquals(Reconciliation.StillPending, self.reconcile { it == 5 })
        assertTrue(history.observe().first().isEmpty())

        // A session megszűnt (a felhasználó elvetette, a rendszer lezárta), és az új verzió nem indult el.
        assertIs<Reconciliation.Interrupted>(self.reconcile { false })
        val entry = history.observe().first().single()
        assertEquals(HistoryOutcome.FAILED, entry.outcome)
        assertEquals(SelfUpdates.FAILURE_INTERRUPTED, entry.failureCode)
        assertEquals(InstallMethod.AUTOMATIC, entry.method)

        // Túl régi függő állapot akkor is megszakadtnak számít, ha a session még létezik.
        self.markStarting(Version("0.3.0", 3), automatic = false)
        self.markCommitted(6)
        clock.advance(SelfUpdates.STALE_AFTER.plusMinutes(1))
        assertIs<Reconciliation.Interrupted>(self.reconcile { true })
        assertNull(settings.pendingSelfUpdate())
    }

    @Test
    fun aProcessDeathBeforeCommit_isInterrupted_andAnotherTargetReplacesTheOldPendingState(): Unit = runBlocking {
        val self = selfUpdates(1, "0.1.0")
        self.markStarting(Version("0.2.0", 2), automatic = false)
        // A commit előtt leállt a folyamat (nincs session-azonosító): újraindításkor megszakadt.
        assertEquals(Reconciliation.Interrupted(Version("0.2.0", 2)), self.reconcile { true })

        self.markStarting(Version("0.2.0", 2), automatic = false)
        self.markStarting(Version("0.3.0", 3), automatic = false)
        assertEquals(3L, settings.pendingSelfUpdate()?.toCode)
        assertEquals(listOf(HistoryOutcome.FAILED, HistoryOutcome.FAILED), history.observe().first().map { it.outcome })
    }

    @Test
    fun cancelledFailedAndBlockedAttempts_areRecordedAndClearThePendingState(): Unit = runBlocking {
        val self = selfUpdates(1, "0.1.0")
        self.markStarting(Version("0.2.0", 2), automatic = false)
        self.recordOutcome(Version("0.2.0", 2), HistoryOutcome.CANCELLED_BY_USER)
        assertNull(settings.pendingSelfUpdate())
        self.recordOutcome(Version("0.2.0", 2), HistoryOutcome.BLOCKED, "SIGNER_MISMATCH_PINNED")
        self.recordOutcome(Version("0.2.0", 2), HistoryOutcome.FAILED, "CONFLICT", "INSTALL_FAILED_UPDATE_INCOMPATIBLE: /data/app/x.apk")
        val entries = history.observe().first()
        assertEquals(listOf(HistoryOutcome.FAILED, HistoryOutcome.BLOCKED, HistoryOutcome.CANCELLED_BY_USER), entries.map { it.outcome })
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE: <path>", entries.first().failureMessage)
        assertEquals("SIGNER_MISMATCH_PINNED", entries[1].failureCode)
    }
}
