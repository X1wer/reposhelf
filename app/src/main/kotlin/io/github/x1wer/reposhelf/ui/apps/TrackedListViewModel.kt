// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.apps

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.x1wer.reposhelf.AppContainer
import io.github.x1wer.reposhelf.RepoShelfApplication
import io.github.x1wer.reposhelf.core.apk.InstalledAppInfo
import io.github.x1wer.reposhelf.core.data.tracked.AppOrigin
import io.github.x1wer.reposhelf.core.data.tracked.CheckProgress
import io.github.x1wer.reposhelf.core.data.tracked.CheckSummary
import io.github.x1wer.reposhelf.core.data.tracked.RepoState
import io.github.x1wer.reposhelf.core.data.tracked.TrackedApp
import io.github.x1wer.reposhelf.core.update.UpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** C. 10.: telepített, követett de nem telepített, és már nem telepített (korábban követett) alkalmazás. */
enum class InstallState { INSTALLED, NOT_INSTALLED, NO_LONGER_INSTALLED }

enum class AppFilter { ALL, INSTALLED, FOLLOWED_ONLY, FAVORITES, OWN, NO_LONGER_INSTALLED }

data class TrackedRow(val app: TrackedApp, val installed: InstalledAppInfo?, val installState: InstallState) {
    /** Figyelmet igényel-e (Frissítések képernyő): frissítés vagy a repóval kapcsolatos probléma. */
    val needsAttention: Boolean
        get() = app.isFollowed && (
            app.status in ATTENTION ||
                app.repoState == RepoState.ID_CHANGED || app.repoState == RepoState.OWNER_CHANGED ||
                app.repoState == RepoState.NOT_FOUND || app.repoState == RepoState.UNAVAILABLE
            )

    private companion object {
        val ATTENTION = setOf(UpdateStatus.UPDATE_AVAILABLE, UpdateStatus.UPDATE_BLOCKED, UpdateStatus.REPO_ID_CHANGED, UpdateStatus.VERSION_UNKNOWN)
    }
}

data class TrackedListUiState(
    val rows: List<TrackedRow> = emptyList(),
    val total: Int = 0,
    val filter: AppFilter = AppFilter.ALL,
    val loaded: Boolean = false,
    val progress: CheckProgress = CheckProgress(),
    val summary: CheckSummary? = null,
)

sealed interface ListEvent {
    data class Checked(val name: String, val rateLimited: Boolean) : ListEvent
}

/**
 * Az „Alkalmazásaim” és a „Frissítések” képernyő közös állapota (C. 10., 12.1.). A telepítettség igazságforrása a
 * PackageManager (ARCH 13.): a sorokhoz tartozó telepített verziót és ikont a képernyő megjelenésekor olvassuk ki.
 */
class TrackedListViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.trackedApps
    private val installedInfo = MutableStateFlow<Map<String, InstalledAppInfo?>>(emptyMap())
    private val filter = MutableStateFlow(AppFilter.ALL)
    private val summary = MutableStateFlow<CheckSummary?>(null)
    private val _icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    private val eventChannel = Channel<ListEvent>(Channel.BUFFERED)

    val icons: StateFlow<Map<String, ImageBitmap>> = _icons
    val events: Flow<ListEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<TrackedListUiState> = combine(
        repository.observeAll(),
        installedInfo,
        filter,
        repository.progress,
        summary,
    ) { apps, installed, currentFilter, progress, lastSummary ->
        val rows = apps.filter { it.isFollowed || it.isFavorite }.map { app ->
            val info = app.packageName?.let { installed[it] }
            val state = when {
                info != null -> InstallState.INSTALLED
                app.everInstalled -> InstallState.NO_LONGER_INSTALLED
                else -> InstallState.NOT_INSTALLED
            }
            TrackedRow(app, info, state)
        }
        TrackedListUiState(rows.filter { it.matches(currentFilter) }, rows.size, currentFilter, loaded = true, progress, lastSummary)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackedListUiState())

    init {
        // Új csomagnév megjelenésekor (pl. telepítés vagy hozzáadás után) a telepítettség újraolvasása.
        viewModelScope.launch {
            repository.observeAll().map { apps -> apps.mapNotNull { it.packageName }.toSet() }.distinctUntilChanged().collect { refreshInstalled(it) }
        }
    }

    private fun TrackedRow.matches(filter: AppFilter): Boolean = when (filter) {
        AppFilter.ALL -> true
        AppFilter.INSTALLED -> installState == InstallState.INSTALLED
        AppFilter.FOLLOWED_ONLY -> app.isFollowed && installState != InstallState.INSTALLED
        AppFilter.FAVORITES -> app.isFavorite
        AppFilter.OWN -> app.origin == AppOrigin.USER
        AppFilter.NO_LONGER_INSTALLED -> installState == InstallState.NO_LONGER_INSTALLED
    }

    /** A képernyő megjelenésekor (pl. eltávolítás vagy máshonnan telepítés után) a PackageManager újraolvasása. */
    fun onResume() {
        viewModelScope.launch {
            repository.refreshInstalledStates()
            refreshInstalled(state.value.rows.mapNotNull { it.app.packageName }.toSet() + installedInfo.value.keys)
        }
    }

    private suspend fun refreshInstalled(packages: Set<String>) {
        val (infos, icons) = withContext(Dispatchers.IO) {
            val infos = packages.associateWith { container.installedPackages.get(it) }
            val icons = infos.filterValues { it != null }.keys.mapNotNull { pkg ->
                container.installedPackages.icon(pkg)?.let { pkg to it.toBitmap(ICON_PX, ICON_PX).asImageBitmap() }
            }.toMap()
            infos to icons
        }
        installedInfo.value = infos
        _icons.value = icons
    }

    fun setFilter(value: AppFilter) {
        filter.value = value
    }

    fun checkAll() {
        if (repository.progress.value.running) return
        viewModelScope.launch { summary.value = repository.checkAll() }
    }

    fun checkOne(app: TrackedApp) {
        viewModelScope.launch {
            val result = repository.checkOne(app.id)
            eventChannel.send(ListEvent.Checked(app.displayName, rateLimited = result?.lastErrorKind == UpdateStatus.RATE_LIMITED.name))
        }
    }

    fun setFavorite(app: TrackedApp, favorite: Boolean) = viewModelScope.launch { repository.setFavorite(app.id, favorite) }

    fun setMonitoring(app: TrackedApp, enabled: Boolean) = viewModelScope.launch { repository.setCheckEnabled(app.id, enabled) }

    /** Eltávolítás a listából – az Android-alkalmazás telepítve marad (C. 10.). */
    fun removeFromList(app: TrackedApp) = viewModelScope.launch { repository.remove(app.id) }

    companion object {
        private const val ICON_PX = 96

        val Factory = viewModelFactory {
            initializer { TrackedListViewModel((this[APPLICATION_KEY] as RepoShelfApplication).container) }
        }
    }
}
