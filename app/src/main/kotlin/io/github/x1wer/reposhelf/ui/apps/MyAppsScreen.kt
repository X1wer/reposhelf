// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.apps

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.core.data.tracked.TrackedApp

/** „Alkalmazásaim” (C. 10.): a követett, kedvenc és saját hozzáadású alkalmazások, szűrőkkel és műveletekkel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyAppsScreen(onOpenApp: (TrackedApp) -> Unit, viewModel: TrackedListViewModel = viewModel(factory = TrackedListViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val icons by viewModel.icons.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ListEvent.Checked -> snackbar.showSnackbar(
                    if (event.rateLimited) resources.getString(R.string.upd_rate_limited) else resources.getString(R.string.check_one_done, event.name),
                )
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_my_apps)) }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { CheckAllPanel(state, viewModel::checkAll) }
            item { FilterRow(state.filter, viewModel::setFilter) }
            if (state.loaded && state.rows.isEmpty()) {
                item {
                    Text(
                        stringResource(if (state.total == 0) R.string.my_apps_empty else R.string.my_apps_filter_empty),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(state.rows, key = { it.app.id }) { row ->
                TrackedAppRow(
                    row = row,
                    icon = row.app.packageName?.let { icons[it] },
                    onOpenDetails = { onOpenApp(row.app) },
                    onCheck = { viewModel.checkOne(row.app) },
                    onFavorite = { viewModel.setFavorite(row.app, it) },
                    onMonitoring = { viewModel.setMonitoring(row.app, it) },
                    onRemove = { viewModel.removeFromList(row.app) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun FilterRow(selected: AppFilter, onSelect: (AppFilter) -> Unit) {
    val label = stringResource(R.string.filter_label)
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppFilter.entries.forEach { filter ->
            FilterChip(selected = filter == selected, onClick = { onSelect(filter) }, label = { Text(stringResource(filter.labelRes())) })
        }
    }
}

private fun AppFilter.labelRes(): Int = when (this) {
    AppFilter.ALL -> R.string.filter_all
    AppFilter.INSTALLED -> R.string.filter_installed
    AppFilter.FOLLOWED_ONLY -> R.string.filter_followed_only
    AppFilter.FAVORITES -> R.string.filter_favorites
    AppFilter.OWN -> R.string.filter_own
    AppFilter.NO_LONGER_INSTALLED -> R.string.filter_no_longer
}

/** „Frissítések” (C. 11., 12.1.): a frissíthető és a figyelmet igénylő alkalmazások, kézi ellenőrzéssel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(onOpenApp: (TrackedApp) -> Unit, viewModel: TrackedListViewModel = viewModel(factory = TrackedListViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val icons by viewModel.icons.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    val attention = state.rows.filter { it.needsAttention }
    val followed = state.rows.count { it.app.isFollowed }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_updates)) }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { CheckAllPanel(state, viewModel::checkAll) }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    when {
                        !state.loaded -> Unit
                        followed == 0 -> Text(stringResource(R.string.updates_none_followed))
                        attention.isEmpty() -> Text(stringResource(R.string.updates_empty))
                    }
                    if (followed > 0) Text(stringResource(R.string.updates_up_to_date_count, followed - attention.size), style = MaterialTheme.typography.bodySmall)
                }
            }
            items(attention, key = { it.app.id }) { row ->
                TrackedAppRow(
                    row = row,
                    icon = row.app.packageName?.let { icons[it] },
                    onOpenDetails = { onOpenApp(row.app) },
                    onCheck = { viewModel.checkOne(row.app) },
                    onFavorite = { viewModel.setFavorite(row.app, it) },
                    onMonitoring = { viewModel.setMonitoring(row.app, it) },
                    onRemove = { viewModel.removeFromList(row.app) },
                )
                HorizontalDivider()
            }
        }
    }
}
