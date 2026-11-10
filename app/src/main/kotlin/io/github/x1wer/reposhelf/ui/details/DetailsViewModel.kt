// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.details

import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.x1wer.reposhelf.AppContainer
import io.github.x1wer.reposhelf.core.apk.AssetSelection
import io.github.x1wer.reposhelf.core.apk.AssetSelector
import io.github.x1wer.reposhelf.core.apk.DownloadedApk
import io.github.x1wer.reposhelf.core.apk.InstalledAppInfo
import io.github.x1wer.reposhelf.core.apk.PreliminaryCheck
import io.github.x1wer.reposhelf.core.apk.PreliminaryCompatibility
import io.github.x1wer.reposhelf.core.apk.ReleaseResolver
import io.github.x1wer.reposhelf.core.apk.ResolvedReleases
import io.github.x1wer.reposhelf.core.apk.VerificationReport
import io.github.x1wer.reposhelf.core.data.details.DetailsError
import io.github.x1wer.reposhelf.core.data.tracked.RepoState
import io.github.x1wer.reposhelf.core.data.tracked.TrackedApp
import io.github.x1wer.reposhelf.core.installer.InstallEvent
import io.github.x1wer.reposhelf.core.installer.InstallEvents
import io.github.x1wer.reposhelf.core.installer.InstallTarget
import io.github.x1wer.reposhelf.core.installer.PreparedInstall
import io.github.x1wer.reposhelf.core.model.CatalogApp
import io.github.x1wer.reposhelf.core.model.CatalogCategory
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.ReleaseAsset
import io.github.x1wer.reposhelf.core.model.RepoMetadata
import io.github.x1wer.reposhelf.core.model.RepoRef
import io.github.x1wer.reposhelf.core.network.DownloadFailure
import io.github.x1wer.reposhelf.core.source.readme.LinkContext
import io.github.x1wer.reposhelf.core.source.readme.MarkdownDocument
import io.github.x1wer.reposhelf.core.source.readme.ReadmeParser
import io.github.x1wer.reposhelf.core.update.InstallOutcome
import io.github.x1wer.reposhelf.core.update.StepResult
import io.github.x1wer.reposhelf.core.update.UpdateFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Melyik alkalmazás adatlapja: katalógusbeli (azonosító) vagy saját hozzáadású (a követett sor azonosítója). */
sealed interface DetailsKey {
    data class Catalog(val id: String) : DetailsKey
    data class Tracked(val id: Long) : DetailsKey
}

/** Az adatlap tárgya. A katalógusbeli adatok (csomagnév, rögzített aláíró, szűrő) csak katalógusbeli appnál vannak. */
data class AppSubject(val name: String, val repo: RepoRef, val catalogApp: CatalogApp?, val packageName: String?) {
    val category: CatalogCategory? get() = catalogApp?.category
}

/** A telepítés állapota a felületen (docs/ARCHITECTURE.md 9. pont, 3–8. lépés). */
sealed interface InstallUiState {
    data object Idle : InstallUiState
    data class ChoosingAsset(val candidates: List<ReleaseAsset>, val filterMismatch: Boolean) : InstallUiState
    data class NeedsPermission(val assets: List<ReleaseAsset>) : InstallUiState
    data class Downloading(val done: Long, val total: Long) : InstallUiState
    data object Verifying : InstallUiState
    data class Verified(val prepared: PreparedInstall.Ready) : InstallUiState
    data class Blocked(val report: VerificationReport) : InstallUiState
    data class DownloadFailed(val reason: DownloadFailure, val detail: String?, val resumable: Boolean) : InstallUiState
    data class Installing(val report: VerificationReport, val waitingForUser: Boolean) : InstallUiState
    data class Succeeded(val packageName: String, val versionName: String?, val verified: Boolean) : InstallUiState
    data class Failed(val status: Int, val message: String?, val developerVerificationReason: Int?, val explanation: Intent?) : InstallUiState
    data class StartFailed(val reason: String) : InstallUiState
}

data class DetailsUiState(
    val subject: AppSubject? = null,
    val tracked: TrackedApp? = null,
    val notFound: Boolean = false,
    val loading: Boolean = true,
    val repo: RepoMetadata? = null,
    val repoError: DetailsError? = null,
    val releasesError: DetailsError? = null,
    val readme: MarkdownDocument? = null,
    val readmeError: DetailsError? = null,
    val resolved: ResolvedReleases? = null,
    val usePrerelease: Boolean = false,
    val release: Release? = null,
    val releaseNotes: MarkdownDocument? = null,
    val selection: AssetSelection? = null,
    val preliminary: List<PreliminaryCheck> = emptyList(),
    val installed: InstalledAppInfo? = null,
    val install: InstallUiState = InstallUiState.Idle,
    val showExternalImages: Boolean = false,
) {
    /** Azonos néven más repó: a telepítés tiltva, amíg a felhasználó nem dönt (C. 19.). */
    val installBlockedByRepo: Boolean get() = tracked?.repoState == RepoState.ID_CHANGED
}

class DetailsViewModel(private val key: DetailsKey, private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DetailsUiState())
    val state: StateFlow<DetailsUiState> = _state.asStateFlow()

    private var sessionId: Int? = null
    private var committedFiles: List<DownloadedApk>? = null
    private var trackedJob: Job? = null

    init {
        load(force = false)
        viewModelScope.launch {
            InstallEvents.events.collect { event -> if (event.sessionId == sessionId) onInstallEvent(event) }
        }
    }

    private suspend fun resolveSubject(): Pair<AppSubject, TrackedApp?>? = when (key) {
        is DetailsKey.Catalog -> {
            val app = container.catalogRepository.app(key.id)
            app?.let {
                val tracked = container.trackedApps.forCatalogApp(it.id)
                // A katalógus csomagneve opcionális; ha nincs, a korábban megtanult (ellenőrzött APK-ból származó) érvényes.
                AppSubject(it.name, it.repo, it, it.packageName ?: tracked?.packageName) to tracked
            }
        }
        is DetailsKey.Tracked -> {
            val tracked = container.trackedApps.get(key.id)
            tracked?.let { t ->
                val catalog = t.catalogId?.let { container.catalogRepository.app(it) }
                AppSubject(t.displayName, t.repo, catalog, catalog?.packageName ?: t.packageName) to t
            }
        }
    }

    fun load(force: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val (subject, tracked) = resolveSubject() ?: run {
                _state.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            _state.update { it.copy(subject = subject, tracked = tracked) }
            observeTracked(tracked?.id)
            val details = container.appDetails.load(subject.repo, force)
            val repo = details.repo.value
            val context = LinkContext(subject.repo.owner, subject.repo.name, repo?.defaultBranch ?: "HEAD")
            val readme = details.readme.value?.let { withContext(Dispatchers.Default) { ReadmeParser.parse(it.markdown, context) } }
            val resolved = details.releases.value?.let(ReleaseResolver::resolve)
            val installed = withContext(Dispatchers.IO) { subject.packageName?.let(container.installedPackages::get) }
            _state.update {
                it.copy(
                    loading = false,
                    repo = repo,
                    repoError = details.repo.error,
                    releasesError = details.releases.error,
                    readme = readme,
                    readmeError = details.readme.error,
                    resolved = resolved,
                    installed = installed ?: it.installed,
                    usePrerelease = it.usePrerelease || (tracked?.includePrereleases == true),
                )
            }
            selectRelease(_state.value.usePrerelease)
        }
    }

    /** A követett sor változásainak figyelése (követés, kedvenc, repó-állapot). */
    private fun observeTracked(id: Long?) {
        trackedJob?.cancel()
        if (id == null) return
        trackedJob = viewModelScope.launch {
            container.trackedApps.observe(id).collect { tracked -> _state.update { it.copy(tracked = tracked) } }
        }
    }

    /** Végleges vagy előzetes kiadás választása; ebből lesz az APK-választás és az előzetes becslés. */
    fun selectRelease(usePrerelease: Boolean) {
        val current = _state.value
        val subject = current.subject ?: return
        val resolved = current.resolved ?: return
        val release = if (usePrerelease) resolved.latestPrerelease ?: resolved.latestStable else resolved.latestStable ?: resolved.latestPrerelease
        val device = container.installedPackages.deviceProfile()
        val selection = release?.let { AssetSelector.select(it.assets, device.supportedAbis, subject.catalogApp?.assetFilter) }
        val preliminary = if (release != null && selection != null) PreliminaryCompatibility.assess(selection, release, device) else emptyList()
        val linkContext = LinkContext(subject.repo.owner, subject.repo.name, current.repo?.defaultBranch ?: "HEAD")
        _state.update {
            it.copy(
                usePrerelease = usePrerelease && resolved.latestPrerelease != null,
                release = release,
                releaseNotes = release?.body?.takeIf { body -> body.isNotBlank() }?.let { body -> ReadmeParser.parse(body, linkContext) },
                selection = selection,
                preliminary = preliminary,
            )
        }
    }

    fun showExternalImages() = _state.update { it.copy(showExternalImages = true) }

    // ---- Követés és kedvenc (C. 9–10.) ----

    fun toggleFollow() {
        val state = _state.value
        viewModelScope.launch {
            val tracked = state.tracked
            val id = when {
                tracked != null -> tracked.id.also { container.trackedApps.setFollowed(it, !tracked.isFollowed) }
                state.subject?.catalogApp != null -> container.trackedApps.followCatalogApp(state.subject.catalogApp)
                else -> return@launch
            }
            if (tracked == null) observeTracked(id)
        }
    }

    fun toggleFavorite() {
        val state = _state.value
        viewModelScope.launch {
            val tracked = state.tracked
            when {
                tracked != null -> container.trackedApps.setFavorite(tracked.id, !tracked.isFavorite)
                state.subject?.catalogApp != null -> observeTracked(container.trackedApps.setFavoriteForCatalogApp(state.subject.catalogApp, true))
            }
        }
    }

    /** A felhasználó elfogadja a repó megváltozott azonosságát (C. 19.); az aláíró-ellenőrzés ettől függetlenül kötelező. */
    fun acceptRepositoryIdentity() {
        val tracked = _state.value.tracked ?: return
        viewModelScope.launch { if (container.trackedApps.acceptRepositoryIdentity(tracked.id)) load(force = true) }
    }

    // ---- Telepítés és frissítés (4–8. lépés) ----

    fun onInstallClicked() {
        if (_state.value.installBlockedByRepo) return
        when (val selection = _state.value.selection) {
            is AssetSelection.Single -> startDownload(listOf(selection.asset))
            is AssetSelection.SplitSet -> startDownload(listOf(selection.base) + selection.splits)
            is AssetSelection.Ambiguous -> _state.update { it.copy(install = InstallUiState.ChoosingAsset(selection.candidates, selection.filterMismatch)) }
            else -> Unit
        }
    }

    fun onAssetChosen(asset: ReleaseAsset) = startDownload(listOf(asset))

    fun dismissInstallDialog() = _state.update { it.copy(install = InstallUiState.Idle) }

    /**
     * A képernyő előtérbe kerülésekor: a telepített verzió újraolvasása (közben máshol frissíthették vagy eltávolíthatták;
     * az igazságforrás a PackageManager, ARCH 13.), és ha a beállítóképernyőről jövünk, az engedély újraellenőrzése.
     */
    fun onResume() {
        refreshInstalled()
        val pending = _state.value.install as? InstallUiState.NeedsPermission ?: return
        if (container.installedPackages.canRequestInstalls()) startDownload(pending.assets)
    }

    private fun refreshInstalled() {
        val current = _state.value
        val subject = current.subject ?: return
        val packageName = subject.packageName ?: current.tracked?.packageName ?: return
        // Telepítés közben nem írjuk felül (a 8. lépés saját maga olvassa ki az eredményt).
        if (current.install is InstallUiState.Installing) return
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { container.installedPackages.get(packageName) }
            _state.update { it.copy(subject = it.subject?.copy(packageName = packageName), installed = info) }
        }
    }

    private fun target(): InstallTarget? {
        val subject = _state.value.subject ?: return null
        return InstallTarget(
            expectedPackageName = subject.packageName,
            pinnedSignerCerts = subject.catalogApp?.signingCertSha256.orEmpty(),
            minVersionCode = subject.catalogApp?.minVersionCode,
            fromCatalog = subject.catalogApp != null,
        )
    }

    private fun currentAssets(): List<ReleaseAsset> = when (val selection = _state.value.selection) {
        is AssetSelection.Single -> listOf(selection.asset)
        is AssetSelection.SplitSet -> listOf(selection.base) + selection.splits
        else -> emptyList()
    }

    private fun startDownload(assets: List<ReleaseAsset>) {
        if (assets.isEmpty()) {
            _state.update { it.copy(install = InstallUiState.Idle) }
            return
        }
        val subject = _state.value.subject ?: return
        val release = _state.value.release ?: return
        if (!container.installedPackages.canRequestInstalls()) {
            _state.update { it.copy(install = InstallUiState.NeedsPermission(assets)) }
            return
        }
        val provider = container.sourceProviders.get(subject.repo.provider) ?: return
        val target = target() ?: return
        viewModelScope.launch {
            _state.update { it.copy(install = InstallUiState.Downloading(0, assets.sumOf { a -> a.size })) }
            val prepared = container.installCoordinator.prepare(assets, provider.downloadPolicy(AppContainer.MAX_APK_BYTES), target) { done, total ->
                _state.update { s -> s.copy(install = if (done >= total) InstallUiState.Verifying else InstallUiState.Downloading(done, total)) }
            }
            // Az elemzés eredménye assetenként megmarad: az elutasított frissítést nem ajánljuk fel újra (ARCH 11.1.).
            when (prepared) {
                is PreparedInstall.Ready -> container.trackedApps.recordAnalysis(subject.repo, release, assets, prepared.files, prepared.report)
                is PreparedInstall.Blocked -> {
                    container.trackedApps.recordAnalysis(subject.repo, release, assets, prepared.files, prepared.report)
                    container.installCoordinator.cleanup(prepared.files)
                }
                is PreparedInstall.DownloadFailed -> Unit
            }
            // Ha a csomagnév eddig nem volt ismert, a vizsgált APK-ból megtanuljuk (a telepített verzió így látszik).
            val learned = when (prepared) {
                is PreparedInstall.Ready -> prepared.report.facts
                is PreparedInstall.Blocked -> prepared.report.facts
                is PreparedInstall.DownloadFailed -> null
            }?.packageName
            if (learned != null && subject.packageName == null) {
                container.trackedApps.learnPackageName(subject.repo, subject.catalogApp?.id, learned)
                val info = withContext(Dispatchers.IO) { container.installedPackages.get(learned) }
                _state.update { it.copy(subject = subject.copy(packageName = learned), installed = info ?: it.installed) }
            }
            _state.update {
                it.copy(
                    install = when (prepared) {
                        is PreparedInstall.Ready -> InstallUiState.Verified(prepared)
                        is PreparedInstall.Blocked -> InstallUiState.Blocked(prepared.report)
                        is PreparedInstall.DownloadFailed -> InstallUiState.DownloadFailed(prepared.reason, prepared.detail, prepared.resumable)
                    },
                )
            }
        }
    }

    fun onConfirmInstall() {
        val verified = _state.value.install as? InstallUiState.Verified ?: return
        val target = target() ?: return
        viewModelScope.launch {
            _state.update { it.copy(install = InstallUiState.Installing(verified.prepared.report, waitingForUser = false)) }
            committedFiles = verified.prepared.files
            when (val start = container.installCoordinator.install(verified.prepared, target)) {
                is StepResult.Ok -> sessionId = start.value
                is StepResult.Blocked -> {
                    finishSession()
                    _state.update { it.copy(install = InstallUiState.Blocked(verified.prepared.report.copy(findings = verified.prepared.report.findings + start.findings))) }
                }
                is StepResult.Failed -> {
                    finishSession()
                    _state.update {
                        it.copy(
                            install = when (val failure = start.failure) {
                                // Az engedélyt közben visszavonták: engedély után újra letöltjük és ellenőrizzük.
                                UpdateFailure.NoInstallPermission -> InstallUiState.NeedsPermission(currentAssets())
                                is UpdateFailure.InstallStartFailed -> InstallUiState.StartFailed(failure.reason)
                                else -> InstallUiState.StartFailed(failure.toString())
                            },
                        )
                    }
                }
            }
        }
    }

    private fun onInstallEvent(event: InstallEvent) {
        val installing = _state.value.install as? InstallUiState.Installing
        when (event) {
            is InstallEvent.PendingUserAction ->
                if (installing != null) _state.update { it.copy(install = installing.copy(waitingForUser = true)) }
            is InstallEvent.Success -> viewModelScope.launch {
                val facts = installing?.report?.facts
                // 8. lépés: a ténylegesen telepített csomag a vártnak felel-e meg (verziókód, aláíró).
                val outcome = facts?.let { container.installCoordinator.verifyResult(event, it) }
                val packageName = event.packageName ?: facts?.packageName.orEmpty()
                val actual = withContext(Dispatchers.IO) { container.installedPackages.get(packageName) }
                if (outcome is InstallOutcome.Verified) recordInstall(outcome)
                finishSession()
                _state.update {
                    it.copy(installed = actual ?: it.installed, install = InstallUiState.Succeeded(packageName, actual?.versionName, outcome is InstallOutcome.Verified))
                }
            }
            is InstallEvent.Failure -> {
                finishSession()
                _state.update { it.copy(install = InstallUiState.Failed(event.status, event.message, event.developerVerificationReason, event.explanationIntent)) }
            }
        }
    }

    /** Sikeres, ellenőrzött telepítés: követés, és az aláíró rögzítése (első telepítéskor TOFU; C. 19.). */
    private suspend fun recordInstall(outcome: InstallOutcome.Verified) {
        val state = _state.value
        val subject = state.subject ?: return
        val release = state.release ?: return
        val id = container.trackedApps.recordInstall(state.tracked?.id, subject.catalogApp, subject.repo, state.repo, release, outcome)
        if (state.tracked == null) observeTracked(id)
    }

    /** A session lezárása: a letöltött fájlok a telepítés után feleslegesek (C. 21.: ideiglenes APK-fájlok kezelése). */
    private fun finishSession() {
        sessionId = null
        committedFiles?.let(container.installCoordinator::cleanup)
        committedFiles = null
    }

    override fun onCleared() {
        // Ha a felhasználó a telepítés jóváhagyása előtt elhagyja az adatlapot, az ellenőrzött fájlok is törlődnek.
        (_state.value.install as? InstallUiState.Verified)?.let { container.installCoordinator.cleanup(it.prepared.files) }
    }

    companion object {
        fun isUserCancel(status: Int) = status == PackageInstaller.STATUS_FAILURE_ABORTED
    }
}
