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
import io.github.x1wer.reposhelf.core.apk.InstalledAppInfo
import io.github.x1wer.reposhelf.core.apk.PreliminaryCheck
import io.github.x1wer.reposhelf.core.apk.PreliminaryCompatibility
import io.github.x1wer.reposhelf.core.apk.ReleaseResolver
import io.github.x1wer.reposhelf.core.apk.ResolvedReleases
import io.github.x1wer.reposhelf.core.apk.VerificationReport
import io.github.x1wer.reposhelf.core.data.details.DetailsError
import io.github.x1wer.reposhelf.core.installer.InstallEvent
import io.github.x1wer.reposhelf.core.installer.InstallEvents
import io.github.x1wer.reposhelf.core.installer.InstallStart
import io.github.x1wer.reposhelf.core.installer.InstallTarget
import io.github.x1wer.reposhelf.core.installer.PreparedInstall
import io.github.x1wer.reposhelf.core.model.CatalogApp
import io.github.x1wer.reposhelf.core.model.Release
import io.github.x1wer.reposhelf.core.model.ReleaseAsset
import io.github.x1wer.reposhelf.core.model.RepoMetadata
import io.github.x1wer.reposhelf.core.network.DownloadFailure
import io.github.x1wer.reposhelf.core.source.readme.LinkContext
import io.github.x1wer.reposhelf.core.source.readme.MarkdownDocument
import io.github.x1wer.reposhelf.core.source.readme.ReadmeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val app: CatalogApp? = null,
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
)

class DetailsViewModel(private val catalogId: String, private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DetailsUiState())
    val state: StateFlow<DetailsUiState> = _state.asStateFlow()

    private var sessionId: Int? = null
    private var committedFiles: List<io.github.x1wer.reposhelf.core.apk.DownloadedApk>? = null

    init {
        load(force = false)
        viewModelScope.launch {
            InstallEvents.events.collect { event -> if (event.sessionId == sessionId) onInstallEvent(event) }
        }
    }

    fun load(force: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val app = container.catalogRepository.app(catalogId)
            if (app == null) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            _state.update { it.copy(app = app) }
            val details = container.appDetails.load(app.repo, force)
            val repo = details.repo.value
            val context = LinkContext(app.repo.owner, app.repo.name, repo?.defaultBranch ?: "HEAD")
            val readme = details.readme.value?.let { withContext(Dispatchers.Default) { ReadmeParser.parse(it.markdown, context) } }
            val resolved = details.releases.value?.let(ReleaseResolver::resolve)
            val installed = withContext(Dispatchers.IO) { app.packageName?.let(container.installedPackages::get) }
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
                )
            }
            selectRelease(_state.value.usePrerelease)
        }
    }

    /** Végleges vagy előzetes kiadás választása; ebből lesz az APK-választás és az előzetes becslés. */
    fun selectRelease(usePrerelease: Boolean) {
        val current = _state.value
        val app = current.app ?: return
        val resolved = current.resolved ?: return
        val release = if (usePrerelease) resolved.latestPrerelease ?: resolved.latestStable else resolved.latestStable ?: resolved.latestPrerelease
        val device = container.installedPackages.deviceProfile()
        val selection = release?.let { AssetSelector.select(it.assets, device.supportedAbis, app.assetFilter) }
        val preliminary = if (release != null && selection != null) PreliminaryCompatibility.assess(selection, release, device) else emptyList()
        val linkContext = LinkContext(app.repo.owner, app.repo.name, current.repo?.defaultBranch ?: "HEAD")
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

    fun onInstallClicked() {
        when (val selection = _state.value.selection) {
            is AssetSelection.Single -> startDownload(listOf(selection.asset))
            is AssetSelection.SplitSet -> startDownload(listOf(selection.base) + selection.splits)
            is AssetSelection.Ambiguous -> _state.update { it.copy(install = InstallUiState.ChoosingAsset(selection.candidates, selection.filterMismatch)) }
            else -> Unit
        }
    }

    fun onAssetChosen(asset: ReleaseAsset) = startDownload(listOf(asset))

    fun dismissInstallDialog() = _state.update { it.copy(install = InstallUiState.Idle) }

    /** A beállítóképernyőről visszatérve újraellenőrizzük az engedélyt. */
    fun onPermissionScreenReturned() {
        val pending = _state.value.install as? InstallUiState.NeedsPermission ?: return
        if (container.installedPackages.canRequestInstalls()) startDownload(pending.assets)
    }

    private fun startDownload(assets: List<ReleaseAsset>) {
        val app = _state.value.app ?: return
        if (!container.installedPackages.canRequestInstalls()) {
            _state.update { it.copy(install = InstallUiState.NeedsPermission(assets)) }
            return
        }
        val provider = container.sourceProviders.get(app.repo.provider) ?: return
        viewModelScope.launch {
            _state.update { it.copy(install = InstallUiState.Downloading(0, assets.sumOf { a -> a.size })) }
            val target = InstallTarget(
                expectedPackageName = app.packageName,
                pinnedSignerCerts = app.signingCertSha256,
                minVersionCode = app.minVersionCode,
                fromCatalog = true,
            )
            val prepared = container.installCoordinator.prepare(assets, provider.downloadPolicy(AppContainer.MAX_APK_BYTES), target) { done, total ->
                _state.update { s -> s.copy(install = if (done >= total) InstallUiState.Verifying else InstallUiState.Downloading(done, total)) }
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
        viewModelScope.launch {
            _state.update { it.copy(install = InstallUiState.Installing(verified.prepared.report, waitingForUser = false)) }
            committedFiles = verified.prepared.files
            when (val start = container.installCoordinator.install(verified.prepared, fromCatalog = true)) {
                is InstallStart.Committed -> sessionId = start.sessionId
                is InstallStart.Failed -> {
                    finishSession()
                    _state.update { it.copy(install = InstallUiState.StartFailed(start.reason)) }
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
                val report = installing?.report
                val packageName = event.packageName ?: report?.facts?.packageName.orEmpty()
                // 8. lépés: a ténylegesen telepített csomag a vártnak felel-e meg (verziókód, aláíró).
                val actual = withContext(Dispatchers.IO) { container.installedPackages.get(packageName) }
                val verified = actual != null && report?.facts != null && actual.versionCode == report.facts!!.versionCode &&
                    report.signerCertSha256.any { it in actual.signingHistory }
                finishSession()
                _state.update { it.copy(installed = actual ?: it.installed, install = InstallUiState.Succeeded(packageName, actual?.versionName, verified)) }
            }
            is InstallEvent.Failure -> {
                finishSession()
                _state.update { it.copy(install = InstallUiState.Failed(event.status, event.message, event.developerVerificationReason, event.explanationIntent)) }
            }
        }
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
