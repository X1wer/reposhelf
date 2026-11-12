// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.core.data.selfupdate

import io.github.x1wer.reposhelf.core.apk.DeviceProfile
import io.github.x1wer.reposhelf.core.data.safety.AppRef
import io.github.x1wer.reposhelf.core.data.safety.HistoryOutcome
import io.github.x1wer.reposhelf.core.data.safety.InstallMethod
import io.github.x1wer.reposhelf.core.data.safety.SecurityWarnings
import io.github.x1wer.reposhelf.core.data.safety.UpdateHistory
import io.github.x1wer.reposhelf.core.data.safety.Version
import io.github.x1wer.reposhelf.core.data.settings.PendingSelfUpdate
import io.github.x1wer.reposhelf.core.data.settings.SettingsRepository
import io.github.x1wer.reposhelf.core.model.ProviderId
import io.github.x1wer.reposhelf.core.model.ProviderRepoId
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.source.ProviderRegistry
import io.github.x1wer.reposhelf.core.update.InstalledVersion
import io.github.x1wer.reposhelf.core.update.KnownAnalyses
import io.github.x1wer.reposhelf.core.update.ReleaseDiscovery
import io.github.x1wer.reposhelf.core.update.RepoIdentity
import io.github.x1wer.reposhelf.core.update.TrackedSource
import io.github.x1wer.reposhelf.core.update.UpdateCheck
import io.github.x1wer.reposhelf.core.update.UpdateCheckInput
import io.github.x1wer.reposhelf.core.update.UpdateChecker
import io.github.x1wer.reposhelf.core.update.UpdateFailure
import io.github.x1wer.reposhelf.core.update.UpdateStatus
import io.github.x1wer.reposhelf.core.update.WarningClassifier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration

/**
 * A saját frissítés build-paraméterei (C. 14., docs/ARCHITECTURE.md 14. pont): a saját repó és a buildbe fordított
 * release-aláíró(k). Ha a repó üres, a saját frissítés ebben a buildben ki van kapcsolva (pl. a későbbi F-Droid-változat).
 */
data class SelfUpdateConfig(
    val repo: RepoRef?,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    /** A buildbe fordított aláíró-ujjlenyomatok; üres lista: a futó példány saját aláírója az elvárt. */
    val pinnedSigners: List<String>,
) {
    val enabled: Boolean get() = repo != null

    companion object {
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /** A BuildConfig szöveges értékeiből. Érvénytelen repó → kikapcsolt saját frissítés (biztonságos alapérték). */
        fun from(repo: String, signers: String, packageName: String, versionCode: Long, versionName: String) = SelfUpdateConfig(
            repo = repo.trim().takeIf { it.isNotEmpty() }?.let { RepoRef.parsePath(ProviderId.GITHUB, it) },
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            pinnedSigners = signers.split(',').map { it.trim().lowercase() }.filter { it.matches(SHA256) },
        )
    }
}

/** Az indításkori egyeztetés eredménye (C. 14.: a megszakadó folyamatok kezelése). */
sealed interface Reconciliation {
    data object NothingPending : Reconciliation

    /** Az új verzió fut: a frissítés sikerült (az előzményekben rögzítve). */
    data class Succeeded(val from: Version, val to: Version) : Reconciliation

    /** A telepítés még folyamatban lehet (pl. a rendszer jóváhagyásra vár): nem döntünk. */
    data object StillPending : Reconciliation

    /** Nem történt meg, és már nem is fog: sikertelenként rögzítve, a folyamat tisztán újrakezdhető. */
    data class Interrupted(val to: Version) : Reconciliation
}

/**
 * A RepoShelf saját frissítése (C. 14.), a hálózat- és platformfüggetlen rész:
 * - ellenőrzés ugyanazzal az 1–3. lépéssel, mint a külső alkalmazásoknál, a saját repó stabil azonosítójának
 *   rögzítésével (repó-eltérítés ellen, C. 19.), figyelmeztetésekkel (C. 20.);
 * - a telepítés idejére tartós „függő” állapot, mert a rendszer a csere közben leállítja a folyamatot;
 * - indításkor egyeztetés: siker, még folyamatban, vagy megszakadt; az eredmény az előzményekbe kerül (C. 15.).
 *
 * A letöltés, az ellenőrzés (5–6. lépés, a buildbe fordított aláíróval) és a telepítés a közös `InstallCoordinator`-on át
 * fut (az app modulban), így nincs külön, gyengébb út.
 */
class SelfUpdates(
    val config: SelfUpdateConfig,
    providers: ProviderRegistry,
    known: KnownAnalyses,
    private val settings: SettingsRepository,
    private val warnings: SecurityWarnings,
    private val history: UpdateHistory,
    private val device: () -> DeviceProfile,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** A figyelmeztetések és az előzmények címzettje: követett sor nélkül, a saját csomagnévvel. */
    val app = AppRef(null, config.packageName, APP_NAME)

    val current: Version get() = Version(config.versionName, config.versionCode)

    private val checker = UpdateChecker(ReleaseDiscovery(providers, clock = clock), known)
    private val mutex = Mutex()

    /** 1–3. lépés a saját repóra; csak végleges kiadások (előzetes kiadás saját frissítésként nem jön). */
    suspend fun check(): UpdateCheck {
        val repo = config.repo ?: return UpdateCheck(UpdateStatus.ERROR, null, null, null, emptyList(), UpdateFailure.Other("disabled"))
        val (repoId, ownerId) = settings.selfRepoIdentity()
        val source = TrackedSource(repo, repoId?.let(::ProviderRepoId), ownerId, includePrereleases = false)
        val check = checker.check(UpdateCheckInput(source, InstalledVersion(config.versionCode, config.versionName, null), null, device()))
        val found = check.discovery
        // Az első sikeres ellenőrzéskor rögzített azonosító: ettől kezdve az azonos nevű, más repó kritikus eset.
        if (found != null && found.identity == RepoIdentity.FIRST_SEEN) settings.setSelfRepoIdentity(found.repo.id.value, found.repo.ownerId)
        // Egy még sosem látott saját repó hiánya nem „eltűnés” (pl. még nincs közzétéve): ne legyen félrevezető (C. 20.).
        val neverSeen = repoId == null && (check.status == UpdateStatus.REPO_NOT_FOUND || check.status == UpdateStatus.REPO_UNAVAILABLE)
        if (!neverSeen) warnings.record(app, WarningClassifier.fromCheck(check))
        warnings.resolve(app, WarningClassifier.resolvedByCheck(check))
        return check
    }

    /** Nyugtázatlan figyelmeztetés vagy kritikus eset tartja-e vissza az automatikus telepítést. */
    suspend fun hasBlockingWarnings(): Boolean = warnings.hasBlocking(app)

    /**
     * Közvetlenül a telepítés indítása előtt. Ha egy korábbi, más célverziójú függő frissítés nem valósult meg,
     * az megszakadtként kerül az előzményekbe.
     */
    suspend fun markStarting(to: Version, automatic: Boolean) = mutex.withLock {
        settings.pendingSelfUpdate()?.let { previous ->
            if (previous.toCode != to.code && !applied(previous)) recordInterrupted(previous)
        }
        settings.setPendingSelfUpdate(PendingSelfUpdate(config.versionCode, config.versionName, to.code ?: 0, to.name, clock.instant(), null, automatic))
    }

    /** A commit után: a session-azonosító alapján később (akár egy új folyamatban is) megállapítható az eredmény. */
    suspend fun markCommitted(sessionId: Int) = mutex.withLock {
        settings.pendingSelfUpdate()?.let { settings.setPendingSelfUpdate(it.copy(sessionId = sessionId)) }
    }

    suspend fun pending(): PendingSelfUpdate? = settings.pendingSelfUpdate()

    /** A telepítés nem az új verzió elindulásával ért véget (hiba, megszakítás, tiltás): rögzítés, a függő állapot törlése. */
    suspend fun recordOutcome(to: Version?, outcome: HistoryOutcome, failureCode: String? = null, failureMessage: String? = null, automatic: Boolean = false) =
        mutex.withLock {
            history.record(app, current, to, outcome, if (automatic) InstallMethod.AUTOMATIC else InstallMethod.SELF_UPDATE, failureCode, failureMessage)
            settings.setPendingSelfUpdate(null)
        }

    /**
     * Indításkor (és a csomagcsere után): a függő saját frissítés egyeztetése a ténylegesen futó verzióval.
     * [sessionAlive]: létezik-e még a rendszerben az adott telepítési session (pl. jóváhagyásra vár).
     */
    suspend fun reconcile(sessionAlive: (Int) -> Boolean): Reconciliation = mutex.withLock {
        val pending = settings.pendingSelfUpdate() ?: return Reconciliation.NothingPending
        val to = Version(pending.toName, pending.toCode)
        when {
            applied(pending) -> {
                val from = Version(pending.fromName, pending.fromCode)
                history.record(app, from, current, HistoryOutcome.SUCCESS, if (pending.automatic) InstallMethod.AUTOMATIC else InstallMethod.SELF_UPDATE)
                settings.setPendingSelfUpdate(null)
                Reconciliation.Succeeded(from, current)
            }
            pending.sessionId != null && sessionAlive(pending.sessionId) && Duration.between(pending.at, clock.instant()) < STALE_AFTER &&
                config.versionCode == pending.fromCode -> Reconciliation.StillPending
            else -> {
                recordInterrupted(pending)
                settings.setPendingSelfUpdate(null)
                Reconciliation.Interrupted(to)
            }
        }
    }

    private fun applied(pending: PendingSelfUpdate) = config.versionCode >= pending.toCode

    private suspend fun recordInterrupted(pending: PendingSelfUpdate) = history.record(
        app, Version(pending.fromName, pending.fromCode), Version(pending.toName, pending.toCode), HistoryOutcome.FAILED,
        if (pending.automatic) InstallMethod.AUTOMATIC else InstallMethod.SELF_UPDATE, FAILURE_INTERRUPTED, null,
    )

    companion object {
        const val APP_NAME = "RepoShelf"
        const val FAILURE_INTERRUPTED = "INTERRUPTED"

        /** Ennél régebbi, meg nem valósult függő frissítés megszakadtnak számít (a rendszer is lezárja a sessiont). */
        val STALE_AFTER: Duration = Duration.ofHours(24)
    }
}
