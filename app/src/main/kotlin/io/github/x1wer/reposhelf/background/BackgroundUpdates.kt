// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.background

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.x1wer.reposhelf.AppContainer
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.core.apk.ApkFacts
import io.github.x1wer.reposhelf.core.data.safety.AppRef
import io.github.x1wer.reposhelf.core.data.safety.HistoryOutcome
import io.github.x1wer.reposhelf.core.data.safety.InstallMethod
import io.github.x1wer.reposhelf.core.data.safety.Version
import io.github.x1wer.reposhelf.core.data.tracked.TrackedApp
import io.github.x1wer.reposhelf.core.installer.toSignal
import io.github.x1wer.reposhelf.core.update.InstallFailureKind
import io.github.x1wer.reposhelf.core.update.InstallSignal
import io.github.x1wer.reposhelf.core.update.RepoIdentity
import io.github.x1wer.reposhelf.core.installer.InstallEvent
import io.github.x1wer.reposhelf.core.installer.InstallEvents
import io.github.x1wer.reposhelf.core.installer.InstallTarget
import io.github.x1wer.reposhelf.core.installer.PreparedInstall
import io.github.x1wer.reposhelf.core.model.CatalogApp
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.ReleaseAsset
import io.github.x1wer.reposhelf.core.model.Severity
import io.github.x1wer.reposhelf.core.update.BackgroundDecision
import io.github.x1wer.reposhelf.core.update.BackgroundPlan
import io.github.x1wer.reposhelf.core.update.GlobalSettings
import io.github.x1wer.reposhelf.core.update.InstallOutcome
import io.github.x1wer.reposhelf.core.update.NetworkState
import io.github.x1wer.reposhelf.core.update.StepResult
import io.github.x1wer.reposhelf.core.update.UnattendedReason
import io.github.x1wer.reposhelf.core.update.UpdateCheck
import io.github.x1wer.reposhelf.core.update.UpdateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Egy háttérben letöltött és ellenőrzött, a felhasználó jóváhagyására váró frissítés (az adatlap ezt használja). */
data class ReadyUpdate(val releaseId: Long, val release: Release, val assets: List<ReleaseAsset>, val prepared: PreparedInstall.Ready)

/** Egy háttérben indított telepítési munkamenet adatai a 8. lépéshez. */
private data class BackgroundSession(
    val app: TrackedApp,
    val catalogApp: CatalogApp?,
    val release: Release,
    val facts: ApkFacts,
    val prepared: PreparedInstall.Ready,
    /** A telepítés előtti verzió (előzmények, C. 15.). */
    val from: Version?,
    /** A rendszer mégis jóváhagyást kért (a módszer ekkor „automatikus, jóváhagyással”). */
    val prompted: Boolean = false,
)

/**
 * A háttérben futó ellenőrzés utáni teendők (C. 12.3–12.5.): értesítés, automatikus letöltés és – ahol az Android
 * hivatalosan engedi – felhasználói beavatkozás nélküli telepítés. A döntés tiszta logika ([BackgroundDecision],
 * `UnattendedInstallPolicy`); ez az osztály csak végrehajtja. Kritikus megállapítás minden úton blokkol.
 */
class BackgroundUpdates(
    private val context: Context,
    private val container: AppContainer,
    scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val sessions = ConcurrentHashMap<Int, BackgroundSession>()

    /** A háttérben letöltött, jóváhagyásra váró frissítések (követett sor azonosítója szerint). */
    val readyUpdates = ConcurrentHashMap<Long, ReadyUpdate>()

    init {
        // A háttérben indított munkamenetek eredménye (a PackageInstaller a nem exportált receiverünkre küldi).
        scope.launch { InstallEvents.events.collect { event -> sessions[event.sessionId]?.let { onEvent(event, it) } } }
    }

    /** Hívás az egyes alkalmazások ellenőrzése után (a `checkAll` visszahívása). */
    suspend fun onChecked(app: TrackedApp, check: UpdateCheck, global: GlobalSettings, network: NetworkState) {
        securityNotice(app, check)
        val installed = app.packageName?.let { withContext(Dispatchers.IO) { container.installedPackages.get(it) } }
        val policy = container.trackedApps.policyFor(app)
        // A figyelmeztetések az értesítést és a letöltést nem befolyásolják; az automatikus telepítést a telepítés
        // pillanatában vizsgáljuk (a letöltött állomány ellenőrzése feloldhatja a kiadáshoz kötött eseteket; C. 20.).
        val plan = BackgroundDecision.plan(check, policy, global, network, installed != null)
        if (plan == BackgroundPlan.NOTHING) return
        val release = check.discovery?.candidate ?: return
        val label = release.name?.takeIf { it.isNotBlank() } ?: release.tagName
        if (!plan.download) {
            if (plan.notify) container.notifier.updateAvailable(app.id, app.displayName, label, release.id)
            return
        }
        downloadAndMaybeInstall(app, release, check.compatibility?.chosenAssets.orEmpty(), plan, label)
    }

    /**
     * Biztonsági értesítés (C. 13., 20.) csak a lényeges, a felhasználó döntését igénylő esetekről, állapotonként
     * egyszer (a `NotificationLog` deduplikál): azonos néven más repó, elutasított frissítés, tulajdonosváltás, eltűnt repó.
     */
    private suspend fun securityNotice(app: TrackedApp, check: UpdateCheck) {
        val (text, state) = when {
            check.status == UpdateStatus.REPO_ID_CHANGED -> context.getString(R.string.repo_state_id_changed) to "id"
            check.status == UpdateStatus.UPDATE_BLOCKED -> context.getString(R.string.upd_blocked) to "blocked:${app.latest?.releaseId}"
            check.status == UpdateStatus.REPO_NOT_FOUND -> context.getString(R.string.repo_state_not_found) to "gone"
            check.discovery?.identity == RepoIdentity.OWNER_CHANGED -> context.getString(R.string.repo_state_owner_changed) to "owner:${check.discovery?.repo?.ownerId}"
            else -> return
        }
        container.notifier.security(app.id, app.displayName, text, "${check.status}:$state")
    }

    private suspend fun downloadAndMaybeInstall(app: TrackedApp, release: Release, assets: List<ReleaseAsset>, plan: BackgroundPlan, label: String) {
        if (assets.isEmpty()) return
        // A katalógusbejegyzés csak akkor érvényes erre az appra, ha most is ugyanarra a repóra mutat (C. 3., 19.).
        val catalogApp = container.trackedApps.boundCatalogApp(app.catalogId, app.repo)
        val target = InstallTarget(
            expectedPackageName = catalogApp?.packageName ?: app.packageName,
            pinnedSignerCerts = catalogApp?.signingCertSha256.orEmpty(),
            minVersionCode = catalogApp?.minVersionCode,
            fromCatalog = catalogApp != null,
        )
        val provider = container.sourceProviders.get(app.repo.provider) ?: return
        readyUpdates[app.id]?.takeIf { it.releaseId != release.id }?.let { stale ->
            container.installCoordinator.cleanup(stale.prepared.files)
            readyUpdates.remove(app.id)
        }
        val prepared = readyUpdates[app.id]?.prepared
            ?: container.installCoordinator.prepare(assets, provider.downloadPolicy(AppContainer.MAX_APK_BYTES), target) { _, _ -> }
        when (prepared) {
            is PreparedInstall.DownloadFailed -> {
                // A félbemaradt letöltés a következő futáskor folytatódik; a végleges hibáról értesítünk.
                if (!prepared.resumable) container.notifier.failed(app.id, app.displayName, context.getString(R.string.notif_failed_download), "download:${release.id}:${prepared.reason}")
                else if (plan.notify) container.notifier.updateAvailable(app.id, app.displayName, label, release.id)
            }
            is PreparedInstall.Blocked -> {
                container.trackedApps.recordAnalysis(app.repo, release, assets, prepared.files, prepared.report)
                container.installCoordinator.cleanup(prepared.files)
                val kinds = prepared.report.findings.filter { it.severity == Severity.CRITICAL }.joinToString { it.kind.name }
                val facts = prepared.report.facts
                container.history.record(
                    AppRef(app.id, facts?.packageName ?: app.packageName, app.displayName), installedVersion(app),
                    Version(facts?.versionName ?: release.tagName, facts?.versionCode), HistoryOutcome.BLOCKED, InstallMethod.AUTOMATIC,
                    kinds.replace(", ", ",").ifEmpty { null },
                )
                container.notifier.security(app.id, app.displayName, context.getString(R.string.notif_security_blocked, label), "blocked:${release.id}:$kinds")
            }
            is PreparedInstall.Ready -> {
                container.trackedApps.recordAnalysis(app.repo, release, assets, prepared.files, prepared.report)
                // Nyugtázatlan figyelmeztetés vagy kritikus eset: nincs automatikus telepítés, csak „telepítésre kész” (C. 20.).
                val mayInstall = plan.install && !container.warnings.hasBlocking(app.id)
                if (mayInstall && tryUnattended(app, catalogApp, release, prepared, target)) return
                readyUpdates[app.id] = ReadyUpdate(release.id, release, assets, prepared)
                if (plan.notify) container.notifier.readyToInstall(app.id, app.displayName, label, release.id)
            }
        }
    }

    /** `true`, ha a beavatkozás nélküli telepítés elindult (az eredményt az [onEvent] kezeli). */
    private suspend fun tryUnattended(app: TrackedApp, catalogApp: CatalogApp?, release: Release, prepared: PreparedInstall.Ready, target: InstallTarget): Boolean {
        val verdict = container.installCoordinator.unattendedVerdict(prepared, userEnabled = true, ourPackage = context.packageName)
        if (verdict != UnattendedReason.ALLOWED) return false
        val facts = prepared.report.facts ?: return false
        val from = installedVersion(app)
        return when (val start = container.installCoordinator.install(prepared, target, unattended = true)) {
            is StepResult.Ok -> {
                sessions[start.value] = BackgroundSession(app, catalogApp, release, facts, prepared, from)
                true
            }
            is StepResult.Blocked -> {
                // A telepítés pillanatában újra ellenőrizve már nem telepíthető (pl. közben megváltozott a telepített példány).
                container.history.record(
                    AppRef(app.id, facts.packageName, app.displayName), from, Version(facts.versionName, facts.versionCode),
                    HistoryOutcome.BLOCKED, InstallMethod.AUTOMATIC, start.findings.joinToString(",") { it.kind.name }.ifEmpty { null },
                )
                false
            }
            is StepResult.Failed -> false
        }
    }

    private suspend fun onEvent(event: InstallEvent, session: BackgroundSession) {
        val app = session.app
        when (event) {
            // A rendszer mégis jóváhagyást kér: normál működés (C. 12.4.). Előtérben a MainActivity azonnal megmutatja.
            is InstallEvent.PendingUserAction -> {
                sessions[event.sessionId] = session.copy(prompted = true)
                container.notifier.confirmInstall(app.id, app.displayName, event.sessionId)
            }
            is InstallEvent.Success -> {
                sessions.remove(event.sessionId)
                when (val outcome = container.installCoordinator.verifyResult(event, session.facts)) {
                    is InstallOutcome.Verified -> {
                        container.trackedApps.recordInstall(app.id, session.catalogApp, app.repo, null, session.release, outcome)
                        session.record(HistoryOutcome.SUCCESS, Version(outcome.versionName, outcome.versionCode))
                        container.notifier.installed(app.id, app.displayName, outcome.versionName ?: outcome.versionCode.toString())
                    }
                    else -> {
                        session.record(HistoryOutcome.FAILED, failureCode = FAILURE_UNVERIFIED)
                        container.notifier.failed(app.id, app.displayName, context.getString(R.string.install_success_unverified), "unverified:${event.sessionId}")
                    }
                }
                container.installCoordinator.cleanup(session.prepared.files)
            }
            is InstallEvent.Failure -> {
                sessions.remove(event.sessionId)
                container.installCoordinator.cleanup(session.prepared.files)
                val kind = (event.toSignal() as? InstallSignal.Failure)?.kind
                val cancelled = kind == InstallFailureKind.ABORTED
                session.record(if (cancelled) HistoryOutcome.CANCELLED_BY_USER else HistoryOutcome.FAILED, failureCode = kind?.name, failureMessage = event.message)
                if (!cancelled) {
                    container.notifier.failed(app.id, app.displayName, context.getString(R.string.notif_failed_install, event.status), "install:${session.release.id}:${event.status}")
                }
            }
        }
    }

    private suspend fun BackgroundSession.record(outcome: HistoryOutcome, to: Version? = null, failureCode: String? = null, failureMessage: String? = null) =
        container.history.record(
            AppRef(app.id, facts.packageName, app.displayName), from, to ?: Version(facts.versionName, facts.versionCode), outcome,
            if (prompted) InstallMethod.AUTOMATIC_PROMPTED else InstallMethod.AUTOMATIC, failureCode, failureMessage,
        )

    private suspend fun installedVersion(app: TrackedApp): Version? =
        app.packageName?.let { withContext(Dispatchers.IO) { container.installedPackages.get(it) } }?.let { Version(it.versionName, it.versionCode) }

    /**
     * Az elhagyott ideiglenes telepítőfájlok törlése (C. 21.): a kész fájlok 2 nap, a félbemaradt (`.part`) fájlok
     * 7 nap után, ha egy függő frissítés sem hivatkozik rájuk.
     */
    fun pruneDownloads(dir: File) {
        val now = clock.millis()
        val referenced = readyUpdates.values.flatMap { it.prepared.files.map { f -> f.file.absolutePath } }.toSet() +
            sessions.values.flatMap { it.prepared.files.map { f -> f.file.absolutePath } } + container.selfUpdater.referencedFiles()
        dir.listFiles()?.forEach { file ->
            if (!file.isFile || file.absolutePath in referenced) return@forEach
            val maxAge = if (file.name.endsWith(".part") || file.name.endsWith(".etag")) Duration.ofDays(7) else Duration.ofDays(2)
            if (now - file.lastModified() > maxAge.toMillis()) file.delete()
        }
    }

    companion object {
        /** A rendszer sikert jelzett, de a telepített csomag nem az, amit ellenőriztünk (8. lépés). */
        const val FAILURE_UNVERIFIED = "UNVERIFIED"

        /** A pillanatnyi hálózat: van-e kapcsolat, és forgalomkorlátos-e (mobiladat). */
        fun networkState(context: Context): NetworkState {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkState(connected = false, metered = true)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkState(connected = false, metered = true)
            val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            return NetworkState(connected, metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        }
    }
}
