// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.x1wer.reposhelf.AppContainer
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.RepoShelfApplication
import io.github.x1wer.reposhelf.core.data.safety.HistoryEntry
import io.github.x1wer.reposhelf.core.data.safety.HistoryOutcome
import io.github.x1wer.reposhelf.core.data.safety.InstallMethod
import io.github.x1wer.reposhelf.core.data.safety.Version
import io.github.x1wer.reposhelf.core.model.Finding
import io.github.x1wer.reposhelf.core.model.FindingKind
import io.github.x1wer.reposhelf.core.model.Severity
import io.github.x1wer.reposhelf.core.update.WarningClassifier
import io.github.x1wer.reposhelf.ui.common.formatDateTime
import io.github.x1wer.reposhelf.ui.safety.labelRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Locale

enum class HistoryFilter { ALL, SUCCESS, FAILED, CANCELLED, BLOCKED, AUTOMATIC }

data class HistoryUiState(
    val entries: List<HistoryEntry> = emptyList(),
    val total: Int = 0,
    val query: String = "",
    val filter: HistoryFilter = HistoryFilter.ALL,
    val loaded: Boolean = false,
)

/** Szűrés eredmény / módszer szerint és keresés a nevekben (C. 15.: kereshető, szűrhető). */
fun filterHistory(entries: List<HistoryEntry>, query: String, filter: HistoryFilter): List<HistoryEntry> {
    val needle = query.trim().lowercase(Locale.ROOT)
    return entries.filter { entry ->
        val byFilter = when (filter) {
            HistoryFilter.ALL -> true
            HistoryFilter.SUCCESS -> entry.outcome == HistoryOutcome.SUCCESS
            HistoryFilter.FAILED -> entry.outcome == HistoryOutcome.FAILED
            HistoryFilter.CANCELLED -> entry.outcome == HistoryOutcome.CANCELLED_BY_USER
            HistoryFilter.BLOCKED -> entry.outcome == HistoryOutcome.BLOCKED
            HistoryFilter.AUTOMATIC -> entry.method == InstallMethod.AUTOMATIC || entry.method == InstallMethod.AUTOMATIC_PROMPTED
        }
        byFilter && (needle.isEmpty() || entry.appName.lowercase(Locale.ROOT).contains(needle) || entry.packageName?.lowercase(Locale.ROOT)?.contains(needle) == true)
    }
}

class HistoryViewModel(container: AppContainer) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(HistoryFilter.ALL)

    val state: StateFlow<HistoryUiState> = combine(container.history.observe(), query, filter) { entries, q, f ->
        HistoryUiState(filterHistory(entries, q, f), entries.size, q, f, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun setQuery(value: String) {
        query.value = value.take(100)
    }

    fun setFilter(value: HistoryFilter) {
        filter.value = value
    }

    companion object {
        val Factory = viewModelFactory { initializer { HistoryViewModel((this[APPLICATION_KEY] as RepoShelfApplication).container) } }
    }
}

/** Frissítési előzmények (C. 15.): név, korábbi és új verzió, időpont, eredmény, ok, módszer; szűrés és keresés. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    label = { Text(stringResource(R.string.history_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                val label = stringResource(R.string.filter_label)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).semantics { contentDescription = label },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HistoryFilter.entries.forEach { filter ->
                        FilterChip(selected = state.filter == filter, onClick = { viewModel.setFilter(filter) }, label = { Text(stringResource(filter.labelRes())) })
                    }
                }
            }
            if (state.loaded && state.entries.isEmpty()) {
                item {
                    Text(
                        stringResource(if (state.total == 0) R.string.history_empty else R.string.history_filter_empty),
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(state.entries, key = { it.id }) { entry ->
                HistoryRow(entry)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val failed = entry.outcome != HistoryOutcome.SUCCESS
        Text("${entry.appName} · ${stringResource(entry.outcome.labelRes())}", style = MaterialTheme.typography.titleSmall, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(
            stringResource(R.string.history_versions, entry.from?.display() ?: stringResource(R.string.history_new_install), entry.to?.display() ?: "?"),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("${formatDateTime(entry.at)} · ${stringResource(entry.method.labelRes())}", style = MaterialTheme.typography.bodySmall)
        if (entry.outcome == HistoryOutcome.FAILED || entry.outcome == HistoryOutcome.BLOCKED) {
            reasonText(entry.failureCode)?.let { Text(stringResource(R.string.history_reason, it), style = MaterialTheme.typography.bodySmall) }
            entry.failureMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun Version.display(): String {
    val n = name
    return when {
        n != null && code != null -> "$n ($code)"
        n != null -> n
        else -> code.toString()
    }
}

/** A gépi hibakód olvasható alakja; az ismeretlen kód változatlanul látszik (a felhasználó bejelentheti). */
@Composable
private fun reasonText(code: String?): String? {
    if (code == null) return null
    val fixed = when {
        code == "INTERRUPTED" -> R.string.history_reason_interrupted
        code == "UNVERIFIED" -> R.string.history_reason_unverified
        code == "CONFLICT" -> R.string.history_reason_conflict
        code == "STORAGE" -> R.string.history_reason_storage
        code == "INCOMPATIBLE" -> R.string.history_reason_incompatible
        code == "INVALID" -> R.string.history_reason_invalid
        code == "BLOCKED" -> R.string.history_reason_system_blocked
        code == "DEVELOPER_VERIFICATION" -> R.string.history_reason_developer_verification
        code == "INSTALL_START_FAILED" -> R.string.history_reason_start
        code.startsWith("DOWNLOAD_") -> R.string.history_reason_download
        else -> null
    }
    if (fixed != null) return stringResource(fixed)
    // Vizsgálati megállapítások (pl. SIGNER_MISMATCH_PINNED): a C. 20. esetének neve.
    val findings = code.split(',').mapNotNull { name -> FindingKind.entries.firstOrNull { it.name == name.trim() } }.map { Finding(it, Severity.CRITICAL) }
    val categories = WarningClassifier.fromFindings(findings).map { it.category }
    return if (categories.isEmpty()) code else categories.map { stringResource(it.labelRes()) }.joinToString("; ")
}

private fun HistoryFilter.labelRes(): Int = when (this) {
    HistoryFilter.ALL -> R.string.history_filter_all
    HistoryFilter.SUCCESS -> R.string.history_filter_success
    HistoryFilter.FAILED -> R.string.history_filter_failed
    HistoryFilter.CANCELLED -> R.string.history_filter_cancelled
    HistoryFilter.BLOCKED -> R.string.history_filter_blocked
    HistoryFilter.AUTOMATIC -> R.string.history_filter_automatic
}

private fun HistoryOutcome.labelRes(): Int = when (this) {
    HistoryOutcome.SUCCESS -> R.string.history_outcome_success
    HistoryOutcome.FAILED -> R.string.history_outcome_failed
    HistoryOutcome.CANCELLED_BY_USER -> R.string.history_outcome_cancelled
    HistoryOutcome.BLOCKED -> R.string.history_outcome_blocked
}

private fun InstallMethod.labelRes(): Int = when (this) {
    InstallMethod.MANUAL -> R.string.history_method_manual
    InstallMethod.AUTOMATIC -> R.string.history_method_automatic
    InstallMethod.AUTOMATIC_PROMPTED -> R.string.history_method_prompted
    InstallMethod.SELF_UPDATE -> R.string.history_method_self
}
