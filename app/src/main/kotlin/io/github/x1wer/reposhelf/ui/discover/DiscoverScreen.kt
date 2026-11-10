// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.core.data.catalog.CatalogErrorKind
import io.github.x1wer.reposhelf.core.data.catalog.CatalogSource
import io.github.x1wer.reposhelf.core.data.catalog.CatalogStatus
import io.github.x1wer.reposhelf.core.data.catalog.RefreshOutcome
import io.github.x1wer.reposhelf.core.model.CatalogApp
import io.github.x1wer.reposhelf.core.model.CatalogCategory
import io.github.x1wer.reposhelf.ui.common.describe
import io.github.x1wer.reposhelf.ui.common.labelRes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DiscoverScreen(onOpenApp: (String) -> Unit, viewModel: DiscoverViewModel = viewModel(factory = DiscoverViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { outcome ->
            val message = when (outcome) {
                is RefreshOutcome.Updated -> resources.getString(R.string.refresh_updated)
                RefreshOutcome.Unchanged, RefreshOutcome.Skipped -> resources.getString(R.string.refresh_unchanged)
                is RefreshOutcome.Failed -> resources.getString(R.string.refresh_failed, errorText(resources, outcome.kind, outcome.detail))
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    DiscoverContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onQueryChange = viewModel::onQueryChange,
        onCategorySelected = viewModel::onCategorySelected,
        onRefresh = viewModel::refresh,
        onOpenApp = onOpenApp,
    )
}

private fun errorText(resources: android.content.res.Resources, kind: CatalogErrorKind, detail: String?): String = when (kind) {
    CatalogErrorKind.NETWORK -> resources.getString(R.string.error_network)
    CatalogErrorKind.HTTP -> resources.getString(R.string.error_http, detail ?: "?")
    CatalogErrorKind.RATE_LIMITED -> resources.getString(R.string.error_rate_limited)
    CatalogErrorKind.POLICY -> resources.getString(R.string.error_policy)
    CatalogErrorKind.INVALID -> resources.getString(R.string.error_invalid)
    CatalogErrorKind.REJECTED -> resources.getString(R.string.error_rejected)
    CatalogErrorKind.UNSUPPORTED_SCHEMA -> resources.getString(R.string.error_unsupported)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverContent(
    state: DiscoverUiState,
    snackbarHostState: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onCategorySelected: (CatalogCategory?) -> Unit,
    onRefresh: () -> Unit,
    onOpenApp: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_discover)) },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh_catalog))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.isRefreshing || state.isInitializing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear_search))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
            CategoryChips(state.category, state.categoryCounts, onCategorySelected)
            CatalogStatusBanner(state.status, state.isInitializing)
            ResultList(state, onOpenApp)
        }
    }
}

@Composable
private fun CategoryChips(
    selected: CatalogCategory?,
    counts: Map<CatalogCategory, Int>,
    onSelected: (CatalogCategory?) -> Unit,
) {
    val label = stringResource(R.string.category_filter_label)
    LazyRow(
        modifier = Modifier.semantics { contentDescription = label },
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(selected = selected == null, onClick = { onSelected(null) }, label = { Text(stringResource(R.string.category_all)) })
        }
        // Csak a katalógusban ténylegesen előforduló kategóriák jelennek meg.
        items(CatalogCategory.entries.filter { (counts[it] ?: 0) > 0 }) { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelected(if (selected == category) null else category) },
                label = { Text(stringResource(category.labelRes())) },
            )
        }
    }
}

@Composable
private fun CatalogStatusBanner(status: CatalogStatus, initializing: Boolean) {
    if (initializing) return
    val now = Instant.now()
    val lines = buildList {
        when {
            !status.hasCatalog -> add(stringResource(R.string.catalog_empty) to true)
            status.lastError == CatalogErrorKind.UNSUPPORTED_SCHEMA -> add(stringResource(R.string.status_unsupported) to true)
            status.lastError != null -> add(stringResource(R.string.status_update_failed, status.lastError!!.describe(status.lastErrorDetail)) to true)
            status.source == CatalogSource.BUNDLED -> add(stringResource(R.string.status_bundled) to false)
        }
        if (status.hasCatalog && status.isExpired(now)) add(stringResource(R.string.status_expired) to true)
        status.lastSuccessAt?.takeIf { status.source == CatalogSource.REMOTE }?.let {
            add(stringResource(R.string.status_last_updated, formatTime(it)) to false)
        }
    }
    if (lines.isEmpty()) return
    val isWarning = lines.any { it.second }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = if (isWarning) {
            CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.elevatedCardColors()
        },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lines.forEach { (text, _) ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isWarning) MaterialTheme.colorScheme.onErrorContainer else Color.Unspecified,
                )
            }
        }
    }
}

@Composable
private fun ResultList(state: DiscoverUiState, onOpenApp: (String) -> Unit) {
    if (state.isInitializing) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
            Text(stringResource(R.string.catalog_loading))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (state.results.isEmpty() && state.status.hasCatalog) {
            item {
                Text(
                    stringResource(R.string.results_empty),
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        items(state.results, key = { it.id }) { app ->
            AppRow(app, onClick = { onOpenApp(app.id) })
            HorizontalDivider()
        }
        if (state.status.hasCatalog) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        pluralStringResource(R.plurals.catalog_app_count, state.status.appCount, state.status.appCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.catalog_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: CatalogApp, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        overlineContent = { Text(stringResource(app.category.labelRes())) },
        // A rövid leírás és az ikon a repóból jön (3. munkamenet); addig a repó útvonala látszik, kitalált adat nincs.
        supportingContent = { Text(app.repo.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { LetterAvatar(app.name) },
    )
}

/** Helyettesítő ikon (C. 6.2.): a név kezdőbetűje. Dekoratív, a név külön szövegként szerepel. */
@Composable
private fun LetterAvatar(name: String) {
    Box(
        modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private fun formatTime(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(instant)
