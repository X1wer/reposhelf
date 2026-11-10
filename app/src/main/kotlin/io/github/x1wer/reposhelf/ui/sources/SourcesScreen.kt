// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.core.apk.AssetSelection
import io.github.x1wer.reposhelf.core.apk.CheckStatus
import io.github.x1wer.reposhelf.core.data.tracked.AddRepoPreview
import io.github.x1wer.reposhelf.core.data.tracked.TrackedApp
import io.github.x1wer.reposhelf.core.update.UpdateStatus
import io.github.x1wer.reposhelf.ui.apps.statusText
import io.github.x1wer.reposhelf.ui.common.formatDateTime

/** „Saját források” (C. 9.): tetszőleges nyilvános GitHub-repó hozzáadása URL-ből, a központi katalógustól függetlenül. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(onOpenTracked: (Long) -> Unit, viewModel: SourcesViewModel = viewModel(factory = SourcesViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val own by viewModel.ownRepos.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    LaunchedEffect(viewModel) { viewModel.openEvents.collect(onOpenTracked) }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_sources)) }) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.sources_intro), style = MaterialTheme.typography.bodyMedium) }
            item {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = viewModel::onInputChange,
                    label = { Text(stringResource(R.string.sources_input_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { focus.clearFocus(); viewModel.check() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(onClick = { focus.clearFocus(); viewModel.check() }, enabled = state.input.isNotBlank() && !state.checking) {
                    Text(stringResource(R.string.action_check_repo))
                }
            }
            if (state.checking) item { Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.sources_checking)) } }
            state.preview?.let { preview -> item { PreviewCard(preview, state.followedId, viewModel, onOpenTracked) } }
            item { Text(stringResource(R.string.sources_own_list), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
            if (own.isEmpty()) item { Text(stringResource(R.string.sources_own_empty), style = MaterialTheme.typography.bodySmall) }
            items(own, key = { it.id }) { app -> OwnRepoRow(app, onOpen = { onOpenTracked(app.id) }, onRemove = { viewModel.remove(app) }) }
        }
    }
}

@Composable
private fun PreviewCard(preview: AddRepoPreview, followedId: Long?, vm: SourcesViewModel, onOpenTracked: (Long) -> Unit) {
    val error = preview !is AddRepoPreview.Found && preview !is AddRepoPreview.AlreadyTracked
    val colors = if (error) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors()
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (preview) {
                AddRepoPreview.InvalidUrl -> Text(stringResource(R.string.add_invalid_url))
                is AddRepoPreview.AlreadyTracked -> {
                    Text(stringResource(R.string.add_already))
                    OutlinedButton(onClick = { onOpenTracked(preview.id) }) { Text(stringResource(R.string.action_open)) }
                }
                is AddRepoPreview.NotFound -> Text(stringResource(R.string.add_not_found))
                is AddRepoPreview.Unavailable -> Text(stringResource(R.string.add_unavailable))
                is AddRepoPreview.RateLimited -> Text(
                    preview.until?.let { stringResource(R.string.add_rate_limited, formatDateTime(it)) } ?: stringResource(R.string.add_rate_limited_unknown),
                )
                is AddRepoPreview.Failed -> Text(stringResource(R.string.add_failed, preview.detail.orEmpty()))
                is AddRepoPreview.Found -> Found(preview, followedId, vm, onOpenTracked)
            }
        }
    }
}

/** A C. 9. lépései, ahogy lefutottak: azonosítás, elérhetőség, alapadatok, kiadások, telepíthető csomag. */
@Composable
private fun Found(preview: AddRepoPreview.Found, followedId: Long?, vm: SourcesViewModel, onOpenTracked: (Long) -> Unit) {
    val repo = preview.repo
    Text(preview.catalogApp?.name ?: repo.ref.name, style = MaterialTheme.typography.titleMedium)
    repo.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    Step(CheckStatus.OK, stringResource(R.string.step_identified, repo.ref.path))
    Step(CheckStatus.OK, stringResource(R.string.step_available))
    Step(CheckStatus.OK, stringResource(R.string.step_metadata))
    if (repo.isArchived) Text(stringResource(R.string.add_archived), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    val candidate = preview.candidate
    when {
        !preview.resolved.hasAnyRelease -> Step(CheckStatus.FAILED, stringResource(R.string.step_releases_none))
        else -> Step(CheckStatus.OK, stringResource(R.string.step_releases, (preview.resolved.latestStable ?: preview.resolved.latestPrerelease)?.tagName ?: "–"))
    }
    val compatibility = preview.check.compatibility
    when {
        candidate == null -> Step(CheckStatus.FAILED, stringResource(R.string.step_installable_none))
        compatibility == null || !compatibility.installable -> Step(CheckStatus.FAILED, stringResource(R.string.step_incompatible))
        compatibility.selection is AssetSelection.Ambiguous -> Step(CheckStatus.WARNING, stringResource(R.string.step_installable_choice))
        else -> Step(CheckStatus.OK, stringResource(R.string.step_installable, compatibility.chosenAssets.joinToString { it.name }))
    }
    if (candidate?.isPrerelease == true && preview.resolved.latestStable == null) {
        Text(stringResource(R.string.step_prerelease_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
    }
    if (preview.catalogApp != null) Text(stringResource(R.string.add_in_catalog), style = MaterialTheme.typography.bodySmall)
    statusTextFor(preview).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (followedId == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.follow(open = true) }) { Text(stringResource(R.string.action_follow_and_open)) }
            OutlinedButton(onClick = { vm.follow(open = false) }) { Text(stringResource(R.string.action_follow_only)) }
        }
    } else {
        Text(stringResource(R.string.action_following), color = MaterialTheme.colorScheme.primary)
        OutlinedButton(onClick = { onOpenTracked(followedId) }) { Text(stringResource(R.string.action_open)) }
    }
}

@Composable
private fun statusTextFor(preview: AddRepoPreview.Found): String = when (preview.check.status) {
    UpdateStatus.UP_TO_DATE -> stringResource(R.string.upd_up_to_date)
    UpdateStatus.UPDATE_AVAILABLE -> stringResource(R.string.upd_available_likely)
    else -> ""
}

@Composable
private fun Step(status: CheckStatus, text: String) {
    val (mark, color) = when (status) {
        CheckStatus.OK -> "✓" to MaterialTheme.colorScheme.primary
        CheckStatus.WARNING -> "!" to MaterialTheme.colorScheme.tertiary
        CheckStatus.FAILED -> "✗" to MaterialTheme.colorScheme.error
        CheckStatus.NOT_CHECKED -> "–" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(mark, color = color, style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OwnRepoRow(app: TrackedApp, onOpen: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(app.displayName, style = MaterialTheme.typography.titleSmall)
        Text(app.repo.path, style = MaterialTheme.typography.bodySmall)
        Text(statusText(app), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onRemove) { Text(stringResource(R.string.action_remove_from_list)) }
        HorizontalDivider()
    }
}
