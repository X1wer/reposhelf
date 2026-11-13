// SPDX-FileCopyrightText: 2026 RepoShelf contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x1wer.reposhelf.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.x1wer.reposhelf.R
import io.github.x1wer.reposhelf.RepoShelfApplication
import io.github.x1wer.reposhelf.core.data.tracked.AppOrigin
import io.github.x1wer.reposhelf.core.data.tracked.TrackedApp
import io.github.x1wer.reposhelf.core.update.UpdateStatus
import io.github.x1wer.reposhelf.ui.common.formatDateTime
import io.github.x1wer.reposhelf.ui.common.openUrl
import io.github.x1wer.reposhelf.ui.common.startSafely
import io.github.x1wer.reposhelf.ui.common.webUrl
import io.github.x1wer.reposhelf.ui.common.ObscuredTouchGuard

/** A frissítési állapot szövege (C. 10.); a nem megerősített frissítést „valószínű”-ként jelzi. */
@Composable
fun statusText(app: TrackedApp): String = when (app.status) {
    null -> stringResource(R.string.upd_never_checked)
    UpdateStatus.UPDATE_AVAILABLE -> stringResource(if (app.statusConfirmed) R.string.upd_available else R.string.upd_available_likely)
    UpdateStatus.UP_TO_DATE -> stringResource(R.string.upd_up_to_date)
    UpdateStatus.NOT_INSTALLED -> stringResource(R.string.upd_not_installed)
    UpdateStatus.VERSION_UNKNOWN -> stringResource(R.string.upd_version_unknown)
    UpdateStatus.NO_INSTALLABLE_RELEASE -> stringResource(R.string.upd_no_installable)
    UpdateStatus.INCOMPATIBLE -> stringResource(R.string.upd_incompatible)
    UpdateStatus.UPDATE_BLOCKED -> stringResource(R.string.upd_blocked)
    UpdateStatus.REPO_NOT_FOUND -> stringResource(R.string.upd_repo_not_found)
    UpdateStatus.REPO_UNAVAILABLE -> stringResource(R.string.upd_repo_unavailable)
    UpdateStatus.REPO_ID_CHANGED -> stringResource(R.string.upd_repo_id_changed)
    UpdateStatus.RATE_LIMITED -> stringResource(R.string.upd_rate_limited)
    UpdateStatus.ERROR -> stringResource(R.string.upd_error)
}

private fun isProblem(status: UpdateStatus?) = status in setOf(
    UpdateStatus.UPDATE_BLOCKED, UpdateStatus.REPO_ID_CHANGED, UpdateStatus.REPO_NOT_FOUND, UpdateStatus.REPO_UNAVAILABLE,
)

/** Egy alkalmazás sora a C. 10. mezőivel és műveleteivel. */
@Composable
fun TrackedAppRow(
    row: TrackedRow,
    icon: ImageBitmap?,
    onOpenDetails: () -> Unit,
    onCheck: () -> Unit,
    onFavorite: (Boolean) -> Unit,
    onMonitoring: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val app = row.app
    val context = LocalContext.current
    val container = (context.applicationContext as RepoShelfApplication).container
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().clickable(onClick = onOpenDetails).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(app.displayName, icon)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(app.displayName, style = MaterialTheme.typography.titleMedium)
                    if (app.isFavorite) {
                        val description = stringResource(R.string.badge_favorite)
                        Icon(Icons.Filled.Favorite, contentDescription = description, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    "${app.repo.path} · ${stringResource(if (app.origin == AppOrigin.CATALOG) R.string.origin_catalog else R.string.origin_user)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    ObscuredTouchGuard()
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_check_now)) }, onClick = { menuOpen = false; onCheck() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_open_repo)) }, onClick = { menuOpen = false; openUrl(context, app.repo.webUrl()) })
                    if (row.installed != null && app.packageName != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_app_info)) },
                            onClick = { menuOpen = false; startSafely(context, container.installedPackages.appInfoIntent(app.packageName!!)) },
                        )
                        // Az eltávolítást a rendszer saját megerősítő ablaka végzi; mi csak kezdeményezzük (C. 10.).
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_uninstall_app)) },
                            onClick = { menuOpen = false; startSafely(context, container.installedPackages.uninstallIntent(app.packageName!!)) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(if (app.checkEnabled) R.string.action_monitoring_off else R.string.action_monitoring_on)) },
                        onClick = { menuOpen = false; onMonitoring(!app.checkEnabled) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (app.isFavorite) R.string.action_unfavorite else R.string.action_favorite)) },
                        onClick = { menuOpen = false; onFavorite(!app.isFavorite) },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_remove_from_list)) }, onClick = { menuOpen = false; confirmRemove = true })
                }
            }
        }
        Text(
            when (row.installState) {
                InstallState.INSTALLED -> stringResource(R.string.row_installed_version, row.installed?.versionName ?: row.installed?.versionCode.toString())
                InstallState.NOT_INSTALLED -> stringResource(R.string.row_not_installed)
                InstallState.NO_LONGER_INSTALLED -> stringResource(R.string.row_no_longer_installed)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        app.latest?.let { latest ->
            val label = latest.name?.takeIf { it.isNotBlank() } ?: latest.tag
            Text(stringResource(if (latest.prerelease) R.string.row_latest_prerelease else R.string.row_latest, label), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            app.lastCheckAt?.let { stringResource(R.string.row_last_check, formatDateTime(it)) } ?: stringResource(R.string.upd_never_checked),
            style = MaterialTheme.typography.bodySmall,
        )
        if (app.status != null) {
            val color = when {
                isProblem(app.status) -> MaterialTheme.colorScheme.error
                app.status == UpdateStatus.UPDATE_AVAILABLE -> MaterialTheme.colorScheme.primary
                else -> Color.Unspecified
            }
            Text(statusText(app), style = MaterialTheme.typography.bodyMedium, color = color)
        }
        app.lastErrorAt?.let { at ->
            val text = if (app.lastErrorKind == UpdateStatus.RATE_LIMITED.name) {
                stringResource(R.string.row_last_error_rate, formatDateTime(at))
            } else {
                stringResource(R.string.row_last_error, formatDateTime(at))
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        // A ténylegesen érvényes szabály (globális beállítás + alkalmazásonkénti felülírás; C. 10., 12.6.).
        Text(
            stringResource(
                when {
                    !row.policy.checkEnabled -> R.string.row_monitoring_off
                    row.policy.autoInstall -> R.string.row_auto_update_on
                    row.policy.autoDownload -> R.string.row_auto_download_on
                    else -> R.string.row_auto_update_off
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            val launch = app.packageName?.takeIf { row.installed != null }?.let { container.installedPackages.launchIntent(it) }
            when {
                app.status == UpdateStatus.UPDATE_AVAILABLE && row.installed != null -> Button(onClick = onOpenDetails) { Text(stringResource(R.string.action_update)) }
                row.installed == null && app.status != UpdateStatus.NO_INSTALLABLE_RELEASE -> OutlinedButton(onClick = onOpenDetails) { Text(stringResource(R.string.action_install)) }
            }
            if (launch != null) OutlinedButton(onClick = { startSafely(context, launch) }) { Text(stringResource(R.string.action_open_app)) }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.remove_dialog_title, app.displayName)) },
            text = { Text(stringResource(R.string.remove_dialog_text)) },
            confirmButton = { ObscuredTouchGuard(); TextButton(onClick = { confirmRemove = false; onRemove() }) { Text(stringResource(R.string.action_remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** A telepített app saját ikonja, vagy (ha nem telepített / nem látható) betű-avatar (C. 6.2.). */
@Composable
fun AppIcon(name: String, icon: ImageBitmap?) {
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        val description = stringResource(R.string.details_icon_missing)
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Az „összes ellenőrzése” gomb, a folyamat és az összegzés (C. 12.1.: látszódjon, ha az API-korlát miatt nem végezhető el). */
@Composable
fun CheckAllPanel(state: TrackedListUiState, onCheckAll: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val progress = state.progress
        if (progress.running) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.check_running, progress.done, progress.total), style = MaterialTheme.typography.bodySmall)
        } else {
            Button(onClick = onCheckAll, enabled = state.total > 0) { Text(stringResource(R.string.action_check_all)) }
        }
        state.summary?.let { summary ->
            Text(stringResource(R.string.check_summary, summary.checked, summary.updatesAvailable, summary.failed), style = MaterialTheme.typography.bodySmall)
            if (summary.notChecked > 0) {
                Text(
                    summary.resumesAt?.let { pluralStringResource(R.plurals.check_summary_limited, summary.notChecked, summary.notChecked, formatDateTime(it)) }
                        ?: pluralStringResource(R.plurals.check_summary_limited_unknown, summary.notChecked, summary.notChecked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Text(stringResource(R.string.check_note), style = MaterialTheme.typography.bodySmall)
    }
}
