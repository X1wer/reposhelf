// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.x1wer.reposhelf.RepoShelfApplication
import io.github.x1wer.reposhelf.core.catalog.SearchQuery
import io.github.x1wer.reposhelf.core.data.catalog.CatalogRepository
import io.github.x1wer.reposhelf.core.data.catalog.CatalogStatus
import io.github.x1wer.reposhelf.core.data.catalog.RefreshOutcome
import io.github.x1wer.reposhelf.core.model.CatalogApp
import io.github.x1wer.reposhelf.core.model.CatalogCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DiscoverUiState(
    val query: String = "",
    val category: CatalogCategory? = null,
    val results: List<CatalogApp> = emptyList(),
    val status: CatalogStatus = CatalogStatus.EMPTY,
    val categoryCounts: Map<CatalogCategory, Int> = emptyMap(),
    val isInitializing: Boolean = true,
    val isRefreshing: Boolean = false,
)

/** A Felfedezés képernyő állapota: keresés a helyi katalógusban (hálózati kérés nélkül) és a katalógus frissítése. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class DiscoverViewModel(private val repository: CatalogRepository) : ViewModel() {

    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<CatalogCategory?>(null)
    private val initializing = MutableStateFlow(true)
    private val refreshing = MutableStateFlow(false)
    private val refreshEvents = Channel<RefreshOutcome>(Channel.BUFFERED)

    /** Egyszeri események (pl. kézi frissítés eredménye) a snackbarhoz. */
    val events: Flow<RefreshOutcome> = refreshEvents.receiveAsFlow()

    /** Egy keresés kulcsa: a lekérdezés MELLETT a katalógus verziója is benne van. */
    private data class SearchKey(val query: SearchQuery, val catalogSerial: Long?, val appCount: Int, val initializing: Boolean)

    private val results: Flow<List<CatalogApp>> = combine(
        query.debounce(SEARCH_DEBOUNCE_MS),
        category,
        repository.status,
        initializing,
    ) { q, c, status, init -> SearchKey(SearchQuery.of(q, c), status.serial, status.appCount, init) }
        // A katalógus betöltése vagy cseréje után a keresést akkor is újra kell futtatni, ha a lekérdezés nem változott.
        // (Hiba volt: az első, még üres adatbázison futó keresés eredménye maradt látható.)
        .distinctUntilChanged()
        .mapLatest { repository.search(it.query) }

    val uiState: StateFlow<DiscoverUiState> = combine(
        combine(query, category, results) { q, c, r -> Triple(q, c, r) },
        repository.status,
        repository.categoryCounts,
        initializing,
        refreshing,
    ) { (q, c, r), status, counts, init, refreshingNow ->
        DiscoverUiState(q, c, r, status, counts, init, refreshingNow)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoverUiState())

    init {
        viewModelScope.launch {
            repository.ensureInitialized()
            initializing.value = false
            // Automatikus frissítés indításkor: legfeljebb 6 óránként, feltételes kéréssel (C. 3.2., 16.1.).
            refreshing.value = true
            try {
                repository.refresh(force = false)
            } finally {
                refreshing.value = false
            }
        }
    }

    fun onQueryChange(value: String) {
        query.value = value.take(MAX_QUERY_LENGTH)
    }

    fun onCategorySelected(value: CatalogCategory?) {
        category.value = value
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                refreshEvents.send(repository.refresh(force = true))
            } finally {
                refreshing.value = false
            }
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 150L
        private const val MAX_QUERY_LENGTH = 200

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as RepoShelfApplication
                DiscoverViewModel(app.container.catalogRepository)
            }
        }
    }
}
