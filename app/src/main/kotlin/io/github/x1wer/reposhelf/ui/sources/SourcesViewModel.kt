// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.x1wer.reposhelf.AppContainer
import io.github.x1wer.reposhelf.RepoShelfApplication
import io.github.x1wer.reposhelf.core.data.tracked.AddRepoPreview
import io.github.x1wer.reposhelf.core.data.tracked.AppOrigin
import io.github.x1wer.reposhelf.core.data.tracked.TrackedApp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SourcesUiState(
    val input: String = "",
    val checking: Boolean = false,
    val preview: AddRepoPreview? = null,
    val followedId: Long? = null,
)

/** Saját GitHub-repó hozzáadása URL-ből (C. 9.): előnézet (1–5. lépés), majd követés (6. lépés). */
class SourcesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SourcesUiState())
    val state: StateFlow<SourcesUiState> = _state.asStateFlow()

    /** A felhasználó által hozzáadott repók. */
    val ownRepos: StateFlow<List<TrackedApp>> = container.trackedApps.observeAll()
        .map { apps -> apps.filter { it.origin == AppOrigin.USER && (it.isFollowed || it.isFavorite) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val openChannel = Channel<Long>(Channel.BUFFERED)

    /** A megnyitandó követett alkalmazás azonosítója („Követés és megnyitás”). */
    val openEvents: Flow<Long> = openChannel.receiveAsFlow()

    fun onInputChange(value: String) = _state.update { it.copy(input = value.take(MAX_INPUT), preview = null, followedId = null) }

    fun check() {
        val input = _state.value.input.trim()
        if (input.isEmpty() || _state.value.checking) return
        viewModelScope.launch {
            _state.update { it.copy(checking = true, preview = null, followedId = null) }
            val preview = container.trackedApps.preview(input)
            _state.update { it.copy(checking = false, preview = preview) }
        }
    }

    fun follow(open: Boolean) {
        val found = _state.value.preview as? AddRepoPreview.Found ?: return
        viewModelScope.launch {
            val id = container.trackedApps.follow(found)
            _state.update { it.copy(followedId = id) }
            if (open) openChannel.send(id)
        }
    }

    fun remove(app: TrackedApp) = viewModelScope.launch { container.trackedApps.remove(app.id) }

    companion object {
        private const val MAX_INPUT = 500

        val Factory = viewModelFactory {
            initializer { SourcesViewModel((this[APPLICATION_KEY] as RepoShelfApplication).container) }
        }
    }
}
