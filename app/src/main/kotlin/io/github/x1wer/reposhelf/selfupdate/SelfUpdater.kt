// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.selfupdate

import android.content.Context
import android.util.Log
import io.github.x1wer.reposhelf.AppContainer
import io.github.x1wer.reposhelf.BuildConfig
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.core.apk.DownloadedApk
import io.github.x1wer.reposhelf.core.data.safety.HistoryOutcome
import io.github.x1wer.reposhelf.core.data.safety.Version
import io.github.x1wer.reposhelf.core.data.selfupdate.Reconciliation
import io.github.x1wer.reposhelf.core.data.selfupdate.SelfUpdates
import io.github.x1wer.reposhelf.core.installer.InstallEvent
import io.github.x1wer.reposhelf.core.installer.InstallEvents
import io.github.x1wer.reposhelf.core.installer.InstallSessions
import io.github.x1wer.reposhelf.core.installer.InstallTarget
import io.github.x1wer.reposhelf.core.installer.PreparedInstall
import io.github.x1wer.reposhelf.core.installer.toSignal
import io.github.x1wer.reposhelf.core.model.Finding
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.ReleaseAsset
import io.github.x1wer.reposhelf.core.model.Severity
import io.github.x1wer.reposhelf.core.network.DownloadFailure
import io.github.x1wer.reposhelf.core.update.BackgroundDecision
import io.github.x1wer.reposhelf.core.update.BackgroundPlan
import io.github.x1wer.reposhelf.core.update.GlobalSettings
import io.github.x1wer.reposhelf.core.update.InstallFailureKind
import io.github.x1wer.reposhelf.core.update.InstallSignal
import io.github.x1wer.reposhelf.core.update.NetworkState
import io.github.x1wer.reposhelf.core.update.PolicyResolver
import io.github.x1wer.reposhelf.core.update.StepResult
import io.github.x1wer.reposhelf.core.update.UnattendedReason
import io.github.x1wer.reposhelf.core.update.UpdateCheck
import io.github.x1wer.reposhelf.core.update.UpdateFailure
import io.github.x1wer.reposhelf.core.update.UpdateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/** A saját frissítés állapota a felületen (Beállítások → RepoShelf frissítése). */
sealed interface SelfUpdateState {
    /** Ebben a buildben nincs saját frissítés (üres repó build-paraméter). */
    data object Disabled : SelfUpdateState
    data object Idle : SelfUpdateState
    data object Checking : SelfUpdateState
    data class UpToDate(val at: Instant) : SelfUpdateState
    data class Available(val release: Release, val assets: List<ReleaseAsset>) : SelfUpdateState
    data class NeedsPermission(val available: Available) : SelfUpdateState
    data class Downloading(val done: Long, val total: Long) : SelfUpdateState
    data object Verifying : SelfUpdateState
    data class Ready(val release: Release, val prepared: PreparedInstall.Ready) : SelfUpdateState
    data class Installing(val version: String, val waitingForUser: Boolean) : SelfUpdateState

    /** Az ellenőrzés kritikus problémát talált (pl. más aláíró): nem telepíthető (C. 20.). */
    data class Blocked(val findings: List<Finding>) : SelfUpdateState
    data class CheckFailed(val status: UpdateStatus) : SelfUpdateState
    data class DownloadFailed(val reason: DownloadFailure) : SelfUpdateState
    data class InstallFailed(val cancelled: Boolean, val message: String?) : SelfUpdateState

    /** A legutóbbi saját frissítés nem fejeződött be (pl. a folyamat leállt); újrakezdhető. */
    data class Interrupted(val version: String?) : SelfUpdateState
}

val Release.label: String get() = name?.takeIf { it.isNotBlank() } ?: tagName

/**
 * A RepoShelf saját frissítése (C. 14., docs/ARCHITECTURE.md 14. pont). Ugyanaz a 8 lépés fut, mint a külső
 * alkalmazásoknál (közös `InstallCoordinator`), a 6. lépésben a buildbe fordított aláíró-ujjlenyomattal, vagy ha az nincs
 * megadva, a futó példány saját aláírójával: más kulccsal aláírt APK soha nem települhet saját frissítésként.
 *
 * A telepítés közben a rendszer leállítja a folyamatot, ezért a függő állapot tartós ([SelfUpdates]); az eredményt az
 * új verzió indításkor rögzíti ([onProcessStart]). A felhasználói beavatkozás nélküli frissítés csak ott, ahol az Android
 * hivatalosan engedi (`USER_ACTION_NOT_REQUIRED`, „Updating itself”), és csak a felhasználó kifejezett beállításával.
 */
class SelfUpdater(
    private val context: Context,
    private val container: AppContainer,
    private val core: SelfUpdates,
    private val sessions: InstallSessions,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SelfUpdateState>(if (core.config.enabled) SelfUpdateState.Idle else SelfUpdateState.Disabled)
    val state: StateFlow<SelfUpdateState> = _state.asStateFlow()

    val enabled: Boolean get() = core.config.enabled

    /** A figyelmeztetések címzettje (követett sor nélkül, a saját csomagnévvel). */
    val appRef get() = core.app
    val currentVersion: Version get() = core.current

    /** Egyszerre egy saját frissítési művelet (ellenőrzés, letöltés, telepítés). */
    private val busy = Mutex()
    private var committedFiles: List<DownloadedApk>? = null

    private var startup: Deferred<Unit>? = null

    init {
        scope.launch { InstallEvents.events.collect { event -> onEvent(event) } }
    }

    /**
     * Folyamatindításkor (Application és csomagcsere után): a korábbi folyamatból maradt, commit nélküli sessionök
     * elhagyása, és a függő saját frissítés egyeztetése. Többszöri hívásra egyszer fut.
     */
    @Synchronized
    fun onProcessStart(): Deferred<Unit> = startup ?: scope.async<Unit> {
        val abandoned = withContext(Dispatchers.IO) { sessions.abandonOrphaned() }
        if (abandoned > 0) Log.i(TAG, "Elhagyott telepítési sessionök: $abandoned")
        when (val result = core.reconcile { id -> sessions.exists(id) }) {
            is Reconciliation.Succeeded -> {
                _state.value = SelfUpdateState.UpToDate(Instant.now())
                container.notifier.selfUpdated(result.to.name ?: result.to.code.toString())
            }
            is Reconciliation.Interrupted -> _state.value = SelfUpdateState.Interrupted(result.to.name)
            Reconciliation.StillPending -> core.pending()?.let { _state.value = SelfUpdateState.Installing(it.toName ?: it.toCode.toString(), waitingForUser = true) }
            Reconciliation.NothingPending -> Unit
        }
    }.also { startup = it }

    // ---- Kézi út (Beállítások) ----

    fun checkNow() {
        if (!enabled) return
        scope.launch { if (busy.tryLock()) try { check() } finally { busy.unlock() } }
    }

    fun download() {
        val available = when (val current = _state.value) {
            is SelfUpdateState.Available -> current
            is SelfUpdateState.NeedsPermission -> current.available
            else -> return
        }
        scope.launch {
            if (!busy.tryLock()) return@launch
            try {
                if (!container.installedPackages.canRequestInstalls()) {
                    _state.value = SelfUpdateState.NeedsPermission(available)
                    return@launch
                }
                prepare(available)
            } finally {
                busy.unlock()
            }
        }
    }

    fun install() {
        val ready = _state.value as? SelfUpdateState.Ready ?: return
        scope.launch { if (busy.tryLock()) try { install(ready, unattended = false) } finally { busy.unlock() } }
    }

    /** A felület visszatérésekor: az engedély megadása után a letöltés folytatható. */
    fun onResume() {
        if (_state.value is SelfUpdateState.NeedsPermission && container.installedPackages.canRequestInstalls()) download()
    }

    // ---- Háttérút (UpdateCheckWorker; C. 14.: automatikus ellenőrzés, értesítés, letöltés, frissítés) ----

    suspend fun backgroundCheck(global: GlobalSettings, network: NetworkState) {
        if (!enabled || !global.selfUpdateCheck) return
        if (!busy.tryLock()) return
        try {
            val check = check()
            val plan = BackgroundDecision.plan(check, PolicyResolver.resolve(global, null), global, network, installed = true)
            val available = _state.value as? SelfUpdateState.Available ?: return
            if (plan == BackgroundPlan.NOTHING) return
            val label = available.release.label
            if (!plan.download || !container.installedPackages.canRequestInstalls()) {
                if (plan.notify) container.notifier.selfUpdateAvailable(label, available.release.id)
                return
            }
            when (val prepared = prepare(available)) {
                is PreparedInstall.Ready -> {
                    // A figyelmeztetéseket a letöltött állomány ellenőrzése UTÁN vizsgáljuk (az feloldhatja a kiadáshoz kötötteket).
                    val unattended = plan.install && !core.hasBlockingWarnings() &&
                        container.installCoordinator.unattendedVerdict(prepared, userEnabled = true, ourPackage = context.packageName) == UnattendedReason.ALLOWED
                    if (unattended) {
                        install(_state.value as? SelfUpdateState.Ready ?: return, unattended = true)
                    } else if (plan.notify && _state.value is SelfUpdateState.Ready) {
                        container.notifier.selfUpdateReady(label, available.release.id)
                    }
                }
                is PreparedInstall.Blocked -> container.notifier.selfUpdateBlocked(context.getString(R.string.notif_security_blocked, label), "self:blocked:${available.release.id}")
                else -> Unit
            }
        } finally {
            busy.unlock()
        }
    }

    // ---- A lépések ----

    private suspend fun check(): UpdateCheck {
        _state.value = SelfUpdateState.Checking
        val check = core.check()
        val candidate = check.discovery?.candidate
        _state.value = when (check.status) {
            UpdateStatus.UPDATE_AVAILABLE, UpdateStatus.VERSION_UNKNOWN ->
                if (candidate != null) SelfUpdateState.Available(candidate, check.compatibility?.chosenAssets.orEmpty()) else SelfUpdateState.CheckFailed(check.status)
            UpdateStatus.UP_TO_DATE -> SelfUpdateState.UpToDate(Instant.now())
            else -> SelfUpdateState.CheckFailed(check.status)
        }
        if (check.status == UpdateStatus.REPO_ID_CHANGED) {
            container.notifier.selfUpdateBlocked(context.getString(R.string.repo_state_id_changed), "self:repo-id")
        }
        return check
    }

    /** Az elvárt csomag és aláíró: a buildbe fordított ujjlenyomat, vagy a futó példány saját aláírója (C. 14., 19.). */
    private suspend fun target(): InstallTarget {
        val own = withContext(Dispatchers.IO) { container.installedPackages.get(context.packageName) }?.signingHistory.orEmpty()
        return InstallTarget(
            expectedPackageName = context.packageName,
            pinnedSignerCerts = core.config.pinnedSigners.ifEmpty { own.map { it.lowercase() } },
            minVersionCode = null,
            fromCatalog = false,
        )
    }

    /** 4–6. lépés. */
    private suspend fun prepare(available: SelfUpdateState.Available): PreparedInstall? {
        val assets = available.assets.takeIf { it.isNotEmpty() } ?: run {
            _state.value = SelfUpdateState.CheckFailed(UpdateStatus.NO_INSTALLABLE_RELEASE)
            return null
        }
        val repo = core.config.repo ?: return null
        val provider = container.sourceProviders.get(repo.provider) ?: return null
        val release = available.release
        _state.value = SelfUpdateState.Downloading(0, assets.sumOf { it.size })
        val prepared = container.installCoordinator.prepare(assets, provider.downloadPolicy(AppContainer.MAX_APK_BYTES), target()) { done, total ->
            _state.value = if (done >= total) SelfUpdateState.Verifying else SelfUpdateState.Downloading(done, total)
        }
        onPrepared(release, assets, prepared)
        return prepared
    }

    /** Az 5–6. lépés eredménye: elemzés és figyelmeztetések rögzítése, a felület állapota (C. 20.). */
    private suspend fun onPrepared(release: Release, assets: List<ReleaseAsset>, prepared: PreparedInstall) {
        val repo = core.config.repo ?: return
        when (prepared) {
            is PreparedInstall.Ready -> {
                container.trackedApps.recordAnalysis(repo, release, assets, prepared.files, prepared.report, untracked = core.app)
                val facts = prepared.report.facts
                // A letöltött APK nem újabb a futó verziónál: nincs mit telepíteni (a következő ellenőrzés már tudja).
                if (facts == null || facts.versionCode <= core.config.versionCode) {
                    container.installCoordinator.cleanup(prepared.files)
                    _state.value = SelfUpdateState.UpToDate(Instant.now())
                } else {
                    _state.value = SelfUpdateState.Ready(release, prepared)
                }
            }
            is PreparedInstall.Blocked -> {
                container.trackedApps.recordAnalysis(repo, release, assets, prepared.files, prepared.report, untracked = core.app)
                container.installCoordinator.cleanup(prepared.files)
                val critical = prepared.report.findings.filter { it.severity == Severity.CRITICAL }
                val facts = prepared.report.facts
                core.recordOutcome(
                    Version(facts?.versionName ?: release.tagName, facts?.versionCode), HistoryOutcome.BLOCKED,
                    critical.joinToString(",") { it.kind.name }.ifEmpty { null },
                )
                _state.value = SelfUpdateState.Blocked(critical)
            }
            is PreparedInstall.DownloadFailed -> _state.value = SelfUpdateState.DownloadFailed(prepared.reason)
        }
    }

    /**
     * Csak debug build: a saját frissítés szimulációja helyi APK-val (emulátoros teszt, B. 6.5.), mert a saját repóban
     * még nincs kiadás. Az 5–8. lépés változatlanul fut (integritás, aláíró a futó példányhoz képest, csomagnév,
     * verziókód, telepítés, eredmény); csak a letöltés marad ki. Kiadott buildben ez az ág nem fut (BuildConfig.DEBUG).
     */
    fun debugLoadLocal(file: File, autoInstall: Boolean = false) {
        if (!BuildConfig.DEBUG || !enabled) return
        scope.launch {
            if (!busy.tryLock()) return@launch
            try {
                _state.value = SelfUpdateState.Verifying
                val apk = withContext(Dispatchers.IO) { DownloadedApk(file, sha256(file), file.length(), file.length(), null) }
                val release = Release(0, "local-debug", null, false, false, null, null, null, null, null, emptyList())
                val prepared = container.installCoordinator.verifyLocal(listOf(apk), target())
                onPrepared(release, emptyList(), prepared)
                // A háttérbeli út szimulációja: beavatkozás nélkül, ha az Android hivatalosan engedi (ARCH 11.2.).
                val ready = _state.value as? SelfUpdateState.Ready
                if (autoInstall && ready != null && prepared is PreparedInstall.Ready) {
                    val verdict = container.installCoordinator.unattendedVerdict(prepared, userEnabled = true, ourPackage = context.packageName)
                    Log.i(TAG, "Beavatkozás nélküli saját frissítés döntése: $verdict")
                    install(ready, unattended = verdict == UnattendedReason.ALLOWED)
                }
            } finally {
                busy.unlock()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 7. lépés. A függő állapot a commit ELŐTT kerül rögzítésre (a folyamat a csere közben leáll). */
    private suspend fun install(ready: SelfUpdateState.Ready, unattended: Boolean) {
        val facts = ready.prepared.report.facts ?: return
        val to = Version(facts.versionName, facts.versionCode)
        core.markStarting(to, automatic = unattended)
        _state.value = SelfUpdateState.Installing(facts.versionName ?: facts.versionCode.toString(), waitingForUser = false)
        committedFiles = ready.prepared.files
        when (val start = container.installCoordinator.install(ready.prepared, target(), unattended)) {
            is StepResult.Ok -> core.markCommitted(start.value)
            is StepResult.Blocked -> {
                finish()
                core.recordOutcome(to, HistoryOutcome.BLOCKED, start.findings.joinToString(",") { it.kind.name }.ifEmpty { null }, automatic = unattended)
                _state.value = SelfUpdateState.Blocked(start.findings.filter { it.severity == Severity.CRITICAL })
            }
            is StepResult.Failed -> {
                finish()
                val failure = start.failure
                core.recordOutcome(to, HistoryOutcome.FAILED, failure.code(), (failure as? UpdateFailure.InstallStartFailed)?.reason, automatic = unattended)
                _state.value = SelfUpdateState.InstallFailed(cancelled = false, message = (failure as? UpdateFailure.InstallStartFailed)?.reason)
            }
        }
    }

    /** 8. lépés a saját sessionre (a függő állapotban tárolt azonosító alapján, akár egy új folyamatban is). */
    private suspend fun onEvent(event: InstallEvent) {
        val pending = core.pending() ?: return
        if (pending.sessionId != event.sessionId) return
        val version = pending.toName ?: pending.toCode.toString()
        when (event) {
            is InstallEvent.PendingUserAction -> {
                _state.value = SelfUpdateState.Installing(version, waitingForUser = true)
                // Előtérben a MainActivity azonnal megmutatja; háttérben értesítés kéri (C. 12.4.).
                if (pending.automatic) container.notifier.selfUpdateConfirm(version, event.sessionId)
            }
            // A sikerességet az új verzió rögzíti indításkor; a régi folyamat itt jellemzően már nem fut.
            is InstallEvent.Success -> finish()
            is InstallEvent.Failure -> {
                finish()
                val signal = event.toSignal() as? InstallSignal.Failure
                val cancelled = signal?.kind == InstallFailureKind.ABORTED
                core.recordOutcome(
                    Version(pending.toName, pending.toCode),
                    if (cancelled) HistoryOutcome.CANCELLED_BY_USER else HistoryOutcome.FAILED,
                    signal?.kind?.name, event.message, automatic = pending.automatic,
                )
                _state.value = SelfUpdateState.InstallFailed(cancelled, event.message)
                if (pending.automatic && !cancelled) {
                    container.notifier.selfUpdateFailed(context.getString(R.string.notif_failed_install, event.status), "self:install:${pending.toCode}:${event.status}")
                }
            }
        }
    }

    private fun finish() {
        committedFiles?.let(container.installCoordinator::cleanup)
        committedFiles = null
    }

    /** A letöltött, telepítésre váró fájlok (a háttérbeli takarítás ezeket nem törli). */
    fun referencedFiles(): Set<String> = buildSet {
        (_state.value as? SelfUpdateState.Ready)?.prepared?.files?.forEach { add(it.file.absolutePath) }
        committedFiles?.forEach { add(it.file.absolutePath) }
    }

    private fun UpdateFailure.code(): String = when (this) {
        UpdateFailure.NoInstallPermission -> "NO_INSTALL_PERMISSION"
        is UpdateFailure.InstallStartFailed -> "INSTALL_START_FAILED"
        else -> javaClass.simpleName.uppercase()
    }

    private companion object {
        const val TAG = "SelfUpdater"
    }
}
