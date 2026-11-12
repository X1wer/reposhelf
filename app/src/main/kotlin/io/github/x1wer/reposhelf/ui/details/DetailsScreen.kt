// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.details

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageInstaller
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.RepoShelfApplication
import io.github.x1wer.reposhelf.core.apk.AssetSelection
import io.github.x1wer.reposhelf.core.apk.Check
import io.github.x1wer.reposhelf.core.apk.CheckItem
import io.github.x1wer.reposhelf.core.apk.CheckStatus
import io.github.x1wer.reposhelf.core.apk.PreCheck
import io.github.x1wer.reposhelf.core.apk.PreliminaryCheck
import io.github.x1wer.reposhelf.core.apk.SignerIdentity
import io.github.x1wer.reposhelf.core.apk.VerificationReport
import io.github.x1wer.reposhelf.core.data.details.DetailsError
import io.github.x1wer.reposhelf.ui.common.formatDateTime
import io.github.x1wer.reposhelf.ui.common.webUrl
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import io.github.x1wer.reposhelf.core.data.tracked.RepoState
import io.github.x1wer.reposhelf.core.installer.Shortcuts
import io.github.x1wer.reposhelf.core.model.FindingKind
import io.github.x1wer.reposhelf.core.model.Severity
import io.github.x1wer.reposhelf.core.network.DownloadFailure
import io.github.x1wer.reposhelf.ui.common.NotificationPermissionGate
import io.github.x1wer.reposhelf.ui.common.labelRes
import io.github.x1wer.reposhelf.ui.settings.delayLabel
import io.github.x1wer.reposhelf.core.update.GlobalSettings
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DetailsScreen(key: DetailsKey, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as RepoShelfApplication
    val viewModel: DetailsViewModel = viewModel(key = "details-$key", factory = viewModelFactory { initializer { DetailsViewModel(key, app.container) } })
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Előtérbe kerüléskor: a telepített verzió újraolvasása, és a beállítóképernyőről jövet az engedély újraellenőrzése.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    DetailsContent(state, viewModel, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsContent(state: DetailsUiState, vm: DetailsViewModel, onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val warnings by vm.warnings.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.subject?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val shareTitle = stringResource(R.string.action_share)
                    state.subject?.let { subject ->
                        // Megosztás (C. 26.): csak a repó nyilvános webcíme és a neve, a rendszer megosztó ablakával.
                        IconButton(onClick = {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_SUBJECT, subject.name)
                                .putExtra(android.content.Intent.EXTRA_TEXT, "${subject.name}: ${subject.repo.webUrl()}")
                            io.github.x1wer.reposhelf.ui.common.startSafely(context, android.content.Intent.createChooser(send, shareTitle))
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = shareTitle)
                        }
                    }
                    state.repo?.htmlUrl?.let { url ->
                        IconButton(onClick = { uriHandler.openUri(url) }) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = stringResource(R.string.action_open_repo))
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Header(state) }
            if (state.notFound) item { Text(stringResource(R.string.details_app_not_found), color = MaterialTheme.colorScheme.error) }
            if (state.loading) item { Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.details_loading)) } }
            item { Errors(state, onRetry = { vm.load(force = true) }) }
            item { Info(state) }
            item { TrackingRow(state, vm) }
            item { RepoStateNotice(state, vm) }
            // Biztonsági figyelmeztetések (C. 20.) nyugtázással; a kritikus eset nyugtázás után is tiltja az automatikus telepítést.
            items(warnings, key = { "warning-${it.id}" }) { warning ->
                io.github.x1wer.reposhelf.ui.safety.WarningCard(warning, showAppName = false, onAcknowledge = { vm.acknowledgeWarning(warning.id) })
            }
            item { RulesSection(state, vm) }
            item { ReleaseInfo(state, vm) }
            item { InstallSection(state, vm) }
            state.releaseNotes?.let { notes ->
                item { SectionTitle(stringResource(R.string.details_release_notes)) }
                item { MarkdownView(notes, state.showExternalImages, vm::showExternalImages) }
            }
            item { SectionTitle(stringResource(R.string.details_readme)) }
            item {
                val readme = state.readme
                when {
                    readme == null -> Text(stringResource(R.string.details_readme_missing))
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (readme.restricted) Text(stringResource(R.string.details_readme_restricted), color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.details_screenshots_note), style = MaterialTheme.typography.bodySmall)
                        if (!state.showExternalImages) Text(stringResource(R.string.details_external_images_note), style = MaterialTheme.typography.bodySmall)
                        MarkdownView(readme, state.showExternalImages, vm::showExternalImages)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Header(state: DetailsUiState) {
    val app = state.subject ?: return
    val iconDescription = stringResource(R.string.details_icon_missing)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Helyettesítő ikon (C. 6.2.): a GitHub-repónak nincs szabványos alkalmazásikonja.
        Box(
            Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).semantics { contentDescription = iconDescription },
            contentAlignment = Alignment.Center,
        ) {
            Text(app.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", style = MaterialTheme.typography.headlineSmall)
        }
        Column {
            Text(app.name, style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.details_developer, app.repo.owner), style = MaterialTheme.typography.bodyMedium)
            app.category?.let { Text(stringResource(it.labelRes()), style = MaterialTheme.typography.labelMedium) }
                ?: Text(stringResource(R.string.origin_user), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Errors(state: DetailsUiState, onRetry: () -> Unit) {
    val errors = listOfNotNull(state.releasesError, state.repoError, state.readmeError).distinct()
    if (errors.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.savedAt?.let { Text(stringResource(R.string.details_saved_offline, formatDateTime(it)), color = MaterialTheme.colorScheme.onErrorContainer) }
            errors.forEach { Text(errorText(it), color = MaterialTheme.colorScheme.onErrorContainer) }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@Composable
private fun errorText(error: DetailsError): String = when (error) {
    DetailsError.NotFound -> stringResource(R.string.error_repo_not_found)
    is DetailsError.Unavailable -> stringResource(R.string.error_repo_unavailable)
    is DetailsError.RateLimited -> error.until?.let { stringResource(R.string.error_rate_limited_until, formatTime(it)) }
        ?: stringResource(R.string.error_rate_limited_unknown)
    DetailsError.Network -> stringResource(R.string.error_details_network)
    is DetailsError.Other -> stringResource(R.string.error_details_other, error.description)
}

@Composable
private fun Info(state: DetailsUiState) {
    val app = state.subject ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(state.repo?.description ?: stringResource(R.string.details_description_missing), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.details_repository, app.repo.path), style = MaterialTheme.typography.bodySmall)
        Text(
            state.repo?.licenseSpdx?.let { stringResource(R.string.details_license, it) } ?: stringResource(R.string.details_license_unknown),
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.repo?.isArchived == true) Text(stringResource(R.string.details_archived), color = MaterialTheme.colorScheme.error)
    }
}

/** Követés és kedvenc (C. 9–10.): a felhasználó dönt, hogy telepít-e, vagy csak a kiadásokat követi. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackingRow(state: DetailsUiState, vm: DetailsViewModel) {
    val subject = state.subject ?: return
    val tracked = state.tracked
    // Saját hozzáadású appnál a sor mindig létezik; katalógusbelinél a követés hozza létre.
    if (tracked == null && subject.catalogApp == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Az első követés a megfelelő pillanat az értesítési engedély kérésére (magyarázattal, csak egyszer; ARCH 12.).
        NotificationPermissionGate(onlyOnce = true) { requestNotifications ->
            FilterChip(
                selected = tracked?.isFollowed == true,
                onClick = {
                    if (tracked?.isFollowed != true) requestNotifications()
                    vm.toggleFollow()
                },
                label = { Text(stringResource(if (tracked?.isFollowed == true) R.string.action_following else R.string.action_follow)) },
            )
        }
        FilterChip(
            selected = tracked?.isFavorite == true,
            onClick = vm::toggleFavorite,
            label = { Text(stringResource(R.string.action_favorite)) },
            leadingIcon = { Icon(if (tracked?.isFavorite == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = null) },
        )
    }
    if (tracked?.isFollowed == true && state.installed == null) {
        Text(stringResource(R.string.details_following_not_installed), style = MaterialTheme.typography.bodySmall)
    }
}

/** A repó azonosságának változása (C. 19.): átnevezés (info), tulajdonosváltás (figyelmeztetés), más repó (kritikus). */
@Composable
private fun RepoStateNotice(state: DetailsUiState, vm: DetailsViewModel) {
    val tracked = state.tracked ?: return
    val (textRes, critical) = when (tracked.repoState) {
        RepoState.OK -> return
        RepoState.RENAMED -> R.string.repo_state_renamed to false
        RepoState.OWNER_CHANGED -> R.string.repo_state_owner_changed to false
        RepoState.ID_CHANGED -> R.string.repo_state_id_changed to true
        RepoState.NOT_FOUND -> R.string.repo_state_not_found to false
        RepoState.UNAVAILABLE -> R.string.repo_state_unavailable to false
    }
    val colors = if (critical) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors()
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(textRes), style = MaterialTheme.typography.bodyMedium)
            tracked.repoStateDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            if (tracked.repoState == RepoState.ID_CHANGED || tracked.repoState == RepoState.OWNER_CHANGED) {
                Text(stringResource(R.string.repo_state_accept_note), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = vm::acceptRepositoryIdentity) { Text(stringResource(R.string.action_accept_repo_identity)) }
            }
        }
    }
}

/** Alkalmazásonkénti frissítési szabályok (C. 12.6.): mindegyik „alapértelmezett” (globális), be vagy ki. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesSection(state: DetailsUiState, vm: DetailsViewModel) {
    val tracked = state.tracked ?: return
    if (!tracked.isFollowed) return
    val global by vm.globalSettings.collectAsStateWithLifecycle()
    val o = tracked.overrides
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.rules_title))
            Text(stringResource(R.string.rules_note), style = MaterialTheme.typography.bodySmall)
            TriRule(stringResource(R.string.rule_monitoring), o.checkEnabled, true) { vm.setOverrides(o.copy(checkEnabled = it)) }
            TriRule(stringResource(R.string.rule_auto_download), o.autoDownload, global.autoDownload) { vm.setOverrides(o.copy(autoDownload = it)) }
            TriRule(stringResource(R.string.rule_auto_install), o.autoInstall, global.autoInstall) { vm.setOverrides(o.copy(autoInstall = it)) }
            TriRule(stringResource(R.string.rule_prereleases), o.includePrereleases, global.includePrereleases) { vm.setOverrides(o.copy(includePrereleases = it)) }
            TriRule(stringResource(R.string.rule_notify), o.notify, global.notifyUpdates) { vm.setOverrides(o.copy(notify = it)) }
            Text(stringResource(R.string.rule_delay), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = o.releaseDelayDays == null,
                    onClick = { vm.setOverrides(o.copy(releaseDelayDays = null)) },
                    label = { Text(stringResource(R.string.rule_default, delayLabel(global.releaseDelayDays))) },
                )
                GlobalSettings.RELEASE_DELAY_CHOICES.forEach { days ->
                    FilterChip(
                        selected = o.releaseDelayDays == days,
                        onClick = { vm.setOverrides(o.copy(releaseDelayDays = days)) },
                        label = { Text(delayLabel(days)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriRule(label: String, value: Boolean?, globalValue: Boolean, onChange: (Boolean?) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val globalText = stringResource(if (globalValue) R.string.rule_on else R.string.rule_off)
        FilterChip(selected = value == null, onClick = { onChange(null) }, label = { Text(stringResource(R.string.rule_default, globalText)) })
        FilterChip(selected = value == true, onClick = { onChange(true) }, label = { Text(stringResource(R.string.rule_on)) })
        FilterChip(selected = value == false, onClick = { onChange(false) }, label = { Text(stringResource(R.string.rule_off)) })
    }
}

@Composable
private fun ReleaseInfo(state: DetailsUiState, vm: DetailsViewModel) {
    val resolved = state.resolved ?: return
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val release = state.release
        if (release == null) {
            Text(
                stringResource(if (resolved.hasAnyRelease) R.string.details_no_installable else R.string.details_no_releases),
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(stringResource(R.string.details_latest_release, release.name?.takeIf { it.isNotBlank() } ?: release.tagName), style = MaterialTheme.typography.titleSmall)
            (release.publishedAt ?: release.createdAt)?.let { Text(stringResource(R.string.details_released_on, formatTime(it)), style = MaterialTheme.typography.bodySmall) }
            selectedAssets(state.selection)?.let { assets ->
                Text(stringResource(R.string.details_download_size, Formatter.formatShortFileSize(context, assets.sumOf { it.size })), style = MaterialTheme.typography.bodySmall)
            }
            if (state.usePrerelease) {
                Text(stringResource(R.string.details_showing_prerelease, release.tagName), color = MaterialTheme.colorScheme.tertiary)
                TextButton(onClick = { vm.selectRelease(false) }, enabled = resolved.latestStable != null) { Text(stringResource(R.string.action_use_stable)) }
            } else if (resolved.latestPrerelease != null && resolved.latestStable != null) {
                Text(stringResource(R.string.details_prerelease_available, resolved.latestPrerelease!!.tagName), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.selectRelease(true) }) { Text(stringResource(R.string.action_use_prerelease)) }
            }
        }
        Text(
            state.installed?.let { stringResource(R.string.details_installed_version, it.versionName ?: it.versionCode.toString()) }
                ?: stringResource(R.string.details_not_installed),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun selectedAssets(selection: AssetSelection?) = when (selection) {
    is AssetSelection.Single -> listOf(selection.asset)
    is AssetSelection.SplitSet -> listOf(selection.base) + selection.splits
    else -> null
}

@Composable
private fun InstallSection(state: DetailsUiState, vm: DetailsViewModel) {
    if (state.release == null) return
    val context = LocalContext.current
    val app = LocalContext.current.applicationContext as RepoShelfApplication
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.install_section))
            when (val install = state.install) {
                InstallUiState.Idle, is InstallUiState.ChoosingAsset, is InstallUiState.NeedsPermission -> {
                    Button(onClick = vm::onInstallClicked, enabled = state.selection !is AssetSelection.NoInstallable && state.selection != null && !state.installBlockedByRepo) {
                        Text(stringResource(if (state.installed != null) R.string.action_download_update else R.string.action_download_and_check))
                    }
                    Preliminary(state.preliminary)
                }
                is InstallUiState.Downloading -> {
                    LinearProgressIndicator(progress = { if (install.total > 0) install.done.toFloat() / install.total else 0f }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.downloading, Formatter.formatShortFileSize(context, install.done), Formatter.formatShortFileSize(context, install.total)))
                }
                InstallUiState.Verifying -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.verifying))
                }
                is InstallUiState.Verified -> {
                    if (state.readyFromBackground) Text(stringResource(R.string.details_ready_from_background), style = MaterialTheme.typography.bodySmall)
                    Report(install.prepared.report, state.subject?.repo?.path.orEmpty())
                    Button(onClick = vm::onConfirmInstall) { Text(stringResource(R.string.action_confirm_install)) }
                }
                is InstallUiState.Blocked -> {
                    Text(stringResource(R.string.install_blocked), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                    Report(install.report, state.subject?.repo?.path.orEmpty())
                }
                is InstallUiState.DownloadFailed -> {
                    val reason = downloadFailureText(install.reason, install.detail)
                    Text(
                        stringResource(if (install.resumable) R.string.download_failed_resumable else R.string.download_failed, reason),
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = vm::onInstallClicked) { Text(stringResource(R.string.action_retry)) }
                }
                is InstallUiState.Installing -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.installing))
                }
                is InstallUiState.Succeeded -> Succeeded(install, app)
                is InstallUiState.Failed -> {
                    val text = if (install.status == PackageInstaller.STATUS_FAILURE_ABORTED && install.developerVerificationReason == null) {
                        stringResource(R.string.install_cancelled)
                    } else {
                        stringResource(R.string.install_failed, failureText(install.status, install.developerVerificationReason))
                    }
                    Text(text, color = MaterialTheme.colorScheme.error)
                    install.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    install.explanation?.let { intent ->
                        TextButton(onClick = { startSafely(context, intent) }) { Text(stringResource(R.string.action_more_info)) }
                    }
                    OutlinedButton(onClick = vm::onInstallClicked) { Text(stringResource(R.string.action_retry)) }
                }
                is InstallUiState.StartFailed -> {
                    Text(stringResource(R.string.install_failed, install.reason), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = vm::onInstallClicked) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
    }
    Dialogs(state, vm, app)
}

@Composable
private fun Succeeded(install: InstallUiState.Succeeded, app: RepoShelfApplication) {
    val context = LocalContext.current
    Text(stringResource(R.string.install_success, install.versionName ?: install.packageName), style = MaterialTheme.typography.titleSmall)
    if (!install.verified) Text(stringResource(R.string.install_success_unverified), color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        app.container.installedPackages.launchIntent(install.packageName)?.let { launch ->
            Button(onClick = { startSafely(context, launch) }) { Text(stringResource(R.string.action_open_app)) }
        }
        OutlinedButton(onClick = { startSafely(context, app.container.installedPackages.appInfoIntent(install.packageName)) }) {
            Text(stringResource(R.string.action_app_info))
        }
    }
    // Parancsikon felajánlása, ahol a launcher támogatja (C. 8.). A launcher alapból is kiteheti az ikont.
    var shortcutMessage by remember { mutableStateOf<Int?>(null) }
    if (Shortcuts.isSupported(context)) {
        Text(stringResource(R.string.shortcut_offer), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = {
            shortcutMessage = if (Shortcuts.requestPin(context, install.packageName)) R.string.shortcut_requested else R.string.shortcut_unsupported
        }) { Text(stringResource(R.string.action_add_shortcut)) }
    }
    shortcutMessage?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun Dialogs(state: DetailsUiState, vm: DetailsViewModel, app: RepoShelfApplication) {
    val context = LocalContext.current
    when (val install = state.install) {
        is InstallUiState.ChoosingAsset -> AlertDialog(
            onDismissRequest = vm::dismissInstallDialog,
            title = { Text(stringResource(R.string.choose_asset_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.choose_asset_note))
                    if (install.filterMismatch) Text(stringResource(R.string.choose_asset_filter_mismatch), color = MaterialTheme.colorScheme.error)
                    install.candidates.forEach { asset ->
                        TextButton(onClick = { vm.onAssetChosen(asset) }) {
                            Text("${asset.name} (${Formatter.formatShortFileSize(context, asset.size)})")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::dismissInstallDialog) { Text(stringResource(R.string.action_cancel)) } },
        )
        is InstallUiState.NeedsPermission -> AlertDialog(
            onDismissRequest = vm::dismissInstallDialog,
            title = { Text(stringResource(R.string.permission_title)) },
            text = { Text(stringResource(R.string.permission_text)) },
            confirmButton = {
                TextButton(onClick = { startSafely(context, app.container.installedPackages.unknownSourcesSettingsIntent()) }) {
                    Text(stringResource(R.string.action_open_settings))
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissInstallDialog) { Text(stringResource(R.string.action_cancel)) } },
        )
        else -> Unit
    }
}

/** Előzetes becslés – külön a tényleges vizsgálattól (B. rész 3.6., C. 7.1.). */
@Composable
private fun Preliminary(checks: List<PreliminaryCheck>) {
    if (checks.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.preliminary_title), style = MaterialTheme.typography.labelLarge)
        checks.forEach { check ->
            val text = when (check.check) {
                PreCheck.FORMAT -> stringResource(
                    when (check.status) {
                        CheckStatus.OK -> R.string.pre_format_ok
                        CheckStatus.WARNING -> R.string.pre_format_choose
                        else -> R.string.pre_format_none
                    },
                )
                PreCheck.ABI -> when (check.status) {
                    CheckStatus.OK -> stringResource(R.string.pre_abi_ok, check.detail.orEmpty())
                    CheckStatus.WARNING -> stringResource(R.string.pre_abi_other, check.detail.orEmpty())
                    else -> stringResource(R.string.pre_abi_unknown)
                }
                PreCheck.STORAGE -> stringResource(
                    when (check.status) {
                        CheckStatus.OK -> R.string.pre_storage_ok
                        CheckStatus.WARNING -> R.string.pre_storage_low
                        CheckStatus.FAILED -> R.string.pre_storage_insufficient
                        CheckStatus.NOT_CHECKED -> R.string.pre_storage_unknown
                    },
                )
                PreCheck.ANDROID_VERSION -> stringResource(R.string.pre_android_unknown, check.detail.orEmpty())
                PreCheck.RELEASE_TYPE -> stringResource(R.string.pre_prerelease)
            }
            StatusLine(check.status, text)
        }
        Text(stringResource(R.string.preliminary_note), style = MaterialTheme.typography.bodySmall)
    }
}

/** A tényleges vizsgálat eredménye, a három aláírási szint külön (C. 18.). */
@Composable
private fun Report(report: VerificationReport, repoPath: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.actual_title), style = MaterialTheme.typography.labelLarge)
        report.integrity.filter { it.check != Check.SHA256 }.forEach { CheckLine(it) }
        HorizontalDivider()
        StatusLine(report.signatureValid, stringResource(R.string.sig_level1))
        val identityText = stringResource(
            when (report.identity) {
                SignerIdentity.MATCHES_INSTALLED -> R.string.identity_matches_installed
                SignerIdentity.MATCHES_PINNED -> R.string.identity_matches_pinned
                SignerIdentity.FIRST_SEEN -> R.string.identity_first_seen
                SignerIdentity.MISMATCH -> R.string.identity_mismatch
                SignerIdentity.NOT_CHECKED -> R.string.identity_not_checked
            },
        )
        val identityStatus = when (report.identity) {
            SignerIdentity.MATCHES_INSTALLED, SignerIdentity.MATCHES_PINNED -> CheckStatus.OK
            SignerIdentity.FIRST_SEEN -> CheckStatus.WARNING
            SignerIdentity.MISMATCH -> CheckStatus.FAILED
            SignerIdentity.NOT_CHECKED -> CheckStatus.NOT_CHECKED
        }
        StatusLine(identityStatus, stringResource(R.string.sig_level2) + ": " + identityText)
        report.signerCertSha256.firstOrNull()?.let {
            Text(stringResource(R.string.signer_fingerprint, it.chunked(2).joinToString(":")), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        StatusLine(CheckStatus.NOT_CHECKED, stringResource(R.string.sig_level3) + ": " + stringResource(R.string.publisher_text, "github.com/$repoPath"))
        HorizontalDivider()
        report.compatibility.filter { it.check != Check.SIGNER_IDENTITY }.forEach { CheckLine(it) }
        report.findings.filter { it.kind != FindingKind.NO_PROVIDER_DIGEST || report.integrity.none { i -> i.check == Check.PROVIDER_DIGEST && i.status == CheckStatus.NOT_CHECKED } }
            .forEach { finding ->
                Text(
                    "• " + findingText(finding.kind),
                    color = when (finding.severity) {
                        Severity.CRITICAL -> MaterialTheme.colorScheme.error
                        Severity.WARNING -> MaterialTheme.colorScheme.tertiary
                        Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
    }
}

@Composable
private fun CheckLine(item: CheckItem) {
    val label = stringResource(
        when (item.check) {
            Check.SIZE -> R.string.check_size
            Check.SHA256 -> R.string.check_sha256
            Check.PROVIDER_DIGEST -> R.string.check_provider_digest
            Check.APK_STRUCTURE -> R.string.check_apk_structure
            Check.SIGNATURE -> R.string.check_signature
            Check.SIGNER_IDENTITY -> R.string.check_signer_identity
            Check.PACKAGE_NAME -> R.string.check_package_name
            Check.VERSION_CODE -> R.string.check_version_code
            Check.MIN_SDK -> R.string.check_min_sdk
            Check.TARGET_SDK -> R.string.check_target_sdk
            Check.ABI -> R.string.check_abi
            Check.SPLIT_SET -> R.string.check_split_set
        },
    )
    StatusLine(item.status, label + (item.detail?.let { ": ${it.take(80)}" } ?: ""))
}

@Composable
private fun StatusLine(status: CheckStatus, text: String) {
    val (label, color) = when (status) {
        CheckStatus.OK -> stringResource(R.string.status_ok) to MaterialTheme.colorScheme.primary
        CheckStatus.WARNING -> stringResource(R.string.status_warning) to MaterialTheme.colorScheme.tertiary
        CheckStatus.FAILED -> stringResource(R.string.status_failed) to MaterialTheme.colorScheme.error
        CheckStatus.NOT_CHECKED -> stringResource(R.string.status_not_checked) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Az állapot szövegként is szerepel (nem csak színnel), így TalkBackkel is érthető.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("[$label]", color = color, style = MaterialTheme.typography.labelSmall)
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (status == CheckStatus.FAILED) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
}

@Composable
internal fun findingText(kind: FindingKind): String = stringResource(
    when (kind) {
        FindingKind.SIZE_MISMATCH -> R.string.finding_size_mismatch
        FindingKind.DIGEST_MISMATCH -> R.string.finding_digest_mismatch
        FindingKind.NO_PROVIDER_DIGEST -> R.string.finding_no_provider_digest
        FindingKind.INVALID_APK -> R.string.finding_invalid_apk
        FindingKind.INVALID_SIGNATURE -> R.string.finding_invalid_signature
        FindingKind.MULTIPLE_SIGNERS -> R.string.finding_multiple_signers
        FindingKind.SIGNER_CHANGED -> R.string.finding_signer_changed
        FindingKind.SIGNER_MISMATCH_PINNED -> R.string.finding_signer_mismatch_pinned
        FindingKind.FIRST_SEEN_SIGNER -> R.string.finding_first_seen_signer
        FindingKind.UNVERIFIABLE_SOURCE -> R.string.finding_unverifiable_source
        FindingKind.PACKAGE_MISMATCH -> R.string.finding_package_mismatch
        FindingKind.VERSION_DOWNGRADE -> R.string.finding_version_downgrade
        FindingKind.SAME_VERSION -> R.string.finding_same_version
        FindingKind.BELOW_MIN_VERSION -> R.string.finding_below_min_version
        FindingKind.INCOMPATIBLE_SDK -> R.string.finding_incompatible_sdk
        FindingKind.TARGET_SDK_TOO_OLD -> R.string.finding_target_sdk_too_old
        FindingKind.INCOMPATIBLE_ABI -> R.string.finding_incompatible_abi
        FindingKind.SPLIT_SET_INCONSISTENT -> R.string.finding_split_set_inconsistent
        FindingKind.UNEXPECTED_FORMAT -> R.string.finding_unexpected_format
        FindingKind.PRERELEASE -> R.string.finding_prerelease
        FindingKind.REPO_NOT_FOUND -> R.string.finding_repo_not_found
        FindingKind.REPO_UNAVAILABLE -> R.string.finding_repo_unavailable
        FindingKind.REPO_ID_CHANGED -> R.string.finding_repo_id_changed
        FindingKind.REPO_OWNER_CHANGED -> R.string.finding_repo_owner_changed
        FindingKind.REPO_RENAMED -> R.string.finding_repo_renamed
        FindingKind.NO_INSTALLABLE_RELEASE -> R.string.finding_no_installable_release
        FindingKind.INSTALLED_STATE_MISMATCH -> R.string.finding_installed_state_mismatch
    },
)

@Composable
private fun downloadFailureText(reason: DownloadFailure, detail: String?): String = when (reason) {
    DownloadFailure.NETWORK -> stringResource(R.string.dl_network)
    DownloadFailure.SIZE_MISMATCH -> stringResource(R.string.dl_size)
    DownloadFailure.INSECURE_URL -> stringResource(R.string.dl_insecure)
    DownloadFailure.HOST_NOT_ALLOWED, DownloadFailure.TOO_MANY_REDIRECTS, DownloadFailure.INVALID_REDIRECT -> stringResource(R.string.dl_host)
    DownloadFailure.HTTP_ERROR -> stringResource(R.string.dl_http, detail.orEmpty())
    DownloadFailure.TOO_LARGE -> stringResource(R.string.dl_too_large)
}

@Composable
private fun failureText(status: Int, developerVerificationReason: Int?): String = stringResource(
    when {
        developerVerificationReason != null -> R.string.failure_verification
        status == PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.failure_conflict
        status == PackageInstaller.STATUS_FAILURE_INVALID -> R.string.failure_invalid
        status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.failure_incompatible
        status == PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.failure_storage
        status == PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.failure_blocked
        status == PackageInstaller.STATUS_FAILURE_ABORTED -> R.string.failure_aborted
        else -> R.string.failure_generic
    },
)

private fun startSafely(context: Context, intent: android.content.Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
}

private fun formatTime(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(instant)
