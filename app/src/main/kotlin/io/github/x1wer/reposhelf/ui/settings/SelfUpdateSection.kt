// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.settings

import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.x1wer.reposhelf.AppContainer
import io.github.x1wer.reposhelf.BuildConfig
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.RepoShelfApplication
import io.github.x1wer.reposhelf.core.model.Severity
import io.github.x1wer.reposhelf.core.update.UpdateStatus
import io.github.x1wer.reposhelf.selfupdate.SelfUpdateState
import io.github.x1wer.reposhelf.selfupdate.label
import io.github.x1wer.reposhelf.ui.common.formatDateTime
import io.github.x1wer.reposhelf.ui.common.startSafely
import io.github.x1wer.reposhelf.ui.safety.WarningCard
import io.github.x1wer.reposhelf.ui.safety.labelRes
import kotlinx.coroutines.launch
import io.github.x1wer.reposhelf.ui.common.ObscuredTouchGuard

/**
 * A RepoShelf saját frissítése (C. 14.): kézi ellenőrzés, opt-in automatikus ellenőrzés, letöltés és ellenőrzés,
 * telepítés; a saját repóra vonatkozó biztonsági figyelmeztetések (C. 20.).
 */
@Composable
fun SelfUpdateSection(container: AppContainer, autoCheck: Boolean, onAutoCheck: (Boolean) -> Unit) {
    val updater = container.selfUpdater
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = updater.currentVersion
    Text(stringResource(R.string.self_current, "${version.name} (${version.code})"), style = MaterialTheme.typography.bodyMedium)
    if (!updater.enabled) {
        Text(stringResource(R.string.self_disabled), style = MaterialTheme.typography.bodySmall)
        return
    }
    val state by updater.state.collectAsStateWithLifecycle()
    val warningsFlow = remember(updater) { container.warnings.observeFor(updater.appRef) }
    val warnings by warningsFlow.collectAsStateWithLifecycle(emptyList())
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updater.onResume() }

    Text(stringResource(R.string.self_source, BuildConfig.SELF_UPDATE_REPO), style = MaterialTheme.typography.bodySmall)
    SwitchRow(stringResource(R.string.self_auto_check), autoCheck, onChange = onAutoCheck)
    Text(stringResource(R.string.self_auto_note), style = MaterialTheme.typography.bodySmall)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(state.describe(context), style = MaterialTheme.typography.bodyMedium, color = if (state.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val busy = state is SelfUpdateState.Checking || state is SelfUpdateState.Downloading || state is SelfUpdateState.Verifying || state is SelfUpdateState.Installing
            OutlinedButton(onClick = updater::checkNow, enabled = !busy) { Text(stringResource(R.string.self_check_now)) }
            when (state) {
                is SelfUpdateState.Available -> Button(onClick = updater::download) { Text(stringResource(R.string.self_download)) }
                is SelfUpdateState.NeedsPermission -> Button(onClick = {
                    startSafely(context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
                }) { Text(stringResource(R.string.self_allow)) }
                is SelfUpdateState.Ready -> Button(onClick = updater::install) { Text(stringResource(R.string.self_install)) }
                else -> Unit
            }
        }
    }
    warnings.filter { it.severity >= Severity.WARNING }.forEach { warning ->
        WarningCard(warning, showAppName = false, onAcknowledge = { scope.launch { container.warnings.acknowledge(warning.id) } })
    }
}

private val SelfUpdateState.isProblem: Boolean
    get() = this is SelfUpdateState.Blocked || this is SelfUpdateState.CheckFailed || this is SelfUpdateState.DownloadFailed ||
        (this is SelfUpdateState.InstallFailed && !cancelled) || this is SelfUpdateState.Interrupted

@Composable
private fun SelfUpdateState.describe(context: android.content.Context): String = when (this) {
    SelfUpdateState.Disabled -> stringResource(R.string.self_disabled)
    SelfUpdateState.Idle -> stringResource(R.string.self_state_idle)
    SelfUpdateState.Checking -> stringResource(R.string.self_state_checking)
    is SelfUpdateState.UpToDate -> stringResource(R.string.self_state_up_to_date, formatDateTime(at))
    is SelfUpdateState.Available -> stringResource(R.string.self_state_available, release.label)
    is SelfUpdateState.NeedsPermission -> stringResource(R.string.self_state_needs_permission)
    is SelfUpdateState.Downloading -> stringResource(R.string.self_state_downloading, Formatter.formatShortFileSize(context, done), Formatter.formatShortFileSize(context, total))
    SelfUpdateState.Verifying -> stringResource(R.string.self_state_verifying)
    is SelfUpdateState.Ready -> stringResource(R.string.self_state_ready, prepared.report.facts?.versionName ?: release.label)
    is SelfUpdateState.Installing -> stringResource(if (waitingForUser) R.string.self_state_waiting else R.string.self_state_installing, version)
    is SelfUpdateState.Blocked -> stringResource(
        R.string.self_state_blocked,
        io.github.x1wer.reposhelf.core.update.WarningClassifier.fromFindings(findings).map { stringResource(it.category.labelRes()) }.ifEmpty { listOf(stringResource(R.string.self_reason_unknown)) }.joinToString("; "),
    )
    is SelfUpdateState.CheckFailed -> stringResource(
        R.string.self_state_check_failed,
        stringResource(
            when (status) {
                UpdateStatus.RATE_LIMITED -> R.string.self_reason_rate_limited
                UpdateStatus.REPO_NOT_FOUND, UpdateStatus.REPO_UNAVAILABLE -> R.string.self_reason_not_found
                UpdateStatus.REPO_ID_CHANGED -> R.string.warn_cat_repo_id_changed
                UpdateStatus.NO_INSTALLABLE_RELEASE -> R.string.self_reason_no_release
                UpdateStatus.INCOMPATIBLE -> R.string.self_reason_incompatible
                UpdateStatus.ERROR -> R.string.self_reason_network
                else -> R.string.self_reason_unknown
            },
        ),
    )
    is SelfUpdateState.DownloadFailed -> stringResource(R.string.self_state_download_failed)
    is SelfUpdateState.InstallFailed -> if (cancelled) stringResource(R.string.self_state_cancelled) else stringResource(R.string.self_state_install_failed, message ?: stringResource(R.string.self_reason_unknown))
    is SelfUpdateState.Interrupted -> stringResource(R.string.self_state_interrupted, version ?: "?")
}

/**
 * A saját frissítésről szóló semleges kérdés, egyszer (D-062: opt-in; az F-Droid és az IzzyOnDroid szabályai szerint
 * futtatható kód csak kifejezett beleegyezéssel tölthető le). A két válasz egyenrangú; a bezárás „nem”-nek számít.
 */
@Composable
fun SelfUpdatePrompt() {
    val container = (LocalContext.current.applicationContext as RepoShelfApplication).container
    if (!container.selfUpdater.enabled) return
    val answered by container.settings.selfUpdatePromptAnswered.collectAsStateWithLifecycle(initialValue = true)
    if (answered) return
    val scope = rememberCoroutineScope()
    val answer: (Boolean) -> Unit = { enable -> scope.launch { container.settings.answerSelfUpdatePrompt(enable) } }
    AlertDialog(
        onDismissRequest = { answer(false) },
        title = { Text(stringResource(R.string.self_prompt_title)) },
        text = { Text(stringResource(R.string.self_prompt_text, BuildConfig.SELF_UPDATE_REPO)) },
        confirmButton = { ObscuredTouchGuard(); TextButton(onClick = { answer(true) }) { Text(stringResource(R.string.self_prompt_yes)) } },
        dismissButton = { TextButton(onClick = { answer(false) }) { Text(stringResource(R.string.self_prompt_no)) } },
    )
}
